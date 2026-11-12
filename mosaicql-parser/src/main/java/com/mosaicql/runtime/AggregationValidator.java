package com.mosaicql.runtime;

import com.mosaicql.ast.Ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.mosaicql.type.MType;
import com.mosaicql.type.TypeInferencer;

/** Function and grouping checks that do not require a data-source schema. */
public final class AggregationValidator {
    private final FunctionRegistry functions;
    private record Scope(Ast.Query query, List<String> aliases, boolean grouped) {}

    public AggregationValidator(FunctionRegistry functions) {
        this.functions = functions;
    }

    public void validate(Ast.Query query) {
        validateFunctions(query);
        validateGrouping(query, List.of());
    }

    boolean isGrouped(Ast.Query query) {
        return !query.groupBy().isEmpty()
            || query.projection().fields().stream().anyMatch(field -> hasAggregate(field.value()))
            || query.orderBy().stream().anyMatch(order -> hasAggregate(order.expression()));
    }

    private boolean hasAggregate(Ast.Expr expression) {
        if (expression instanceof Ast.Function function) {
            return functions.isAggregate(function.name()) || function.arguments().stream().anyMatch(this::hasAggregate);
        }
        if (expression instanceof Ast.Binary binary) return hasAggregate(binary.left()) || hasAggregate(binary.right());
        if (expression instanceof Ast.Unary unary) return hasAggregate(unary.value());
        if (expression instanceof Ast.CaseWhen conditional) {
            return conditional.branches().stream().anyMatch(branch -> hasAggregate(branch.condition()) || hasAggregate(branch.value()))
                || conditional.elseValue() != null && hasAggregate(conditional.elseValue());
        }
        return false; // A nested query has its own aggregate scope.
    }

    private void validateFunctions(Ast.Query query) {
        for (var join : query.joins()) validateFunctions(join.on(), false);
        if (query.where() != null) validateFunctions(query.where(), false);
        for (var key : query.groupBy()) {
            validateFunctions(key, false);
            if (containsQuery(key)) throw new IllegalArgumentException("Subqueries are not allowed in group by");
        }
        for (var order : query.orderBy()) {
            validateFunctions(order.expression(), true);
            if (containsQuery(orderExpression(query, order.expression()))) {
                throw new IllegalArgumentException("Subqueries cannot be used as sort keys");
            }
        }
        for (var field : query.projection().fields()) {
            if (!(field.value() instanceof Ast.Wildcard)) validateFunctions(field.value(), true);
        }
    }

    private void validateFunctions(Ast.Expr expression, boolean allowAggregate) {
        if (expression instanceof Ast.Function function) {
            functions.validate(function);
            boolean aggregate = functions.isAggregate(function.name());
            if (aggregate && !allowAggregate) {
                throw new IllegalArgumentException("Aggregate is not allowed here: " + function.name());
            }
            for (var argument : function.arguments()) {
                // The registry only accepts a wildcard as the sole argument of count.
                if (!(argument instanceof Ast.Wildcard)) validateFunctions(argument, allowAggregate && !aggregate);
            }
            if (!aggregate && function.arguments().stream().noneMatch(Ast.Wildcard.class::isInstance)) {
                var types = function.arguments().stream()
                    .map(argument -> TypeInferencer.expression(argument, Map.of(), functions)).toList();
                if (types.stream().noneMatch(MType::isUnknown)) functions.returnType(function.name(), types);
            }
        } else if (expression instanceof Ast.NestedQuery nested) {
            validateFunctions(nested.query());
        } else if (expression instanceof Ast.Binary binary) {
            validateFunctions(binary.left(), allowAggregate);
            validateFunctions(binary.right(), allowAggregate);
        } else if (expression instanceof Ast.Unary unary) {
            validateFunctions(unary.value(), allowAggregate);
        } else if (expression instanceof Ast.CaseWhen conditional) {
            for (var branch : conditional.branches()) {
                validateFunctions(branch.condition(), allowAggregate);
                validateFunctions(branch.value(), allowAggregate);
            }
            if (conditional.elseValue() != null) validateFunctions(conditional.elseValue(), allowAggregate);
        } else if (expression instanceof Ast.Wildcard) {
            throw new IllegalArgumentException("* is only allowed in count(*)");
        }
    }

    private boolean containsQuery(Ast.Expr expression) {
        if (expression instanceof Ast.NestedQuery) return true;
        if (expression instanceof Ast.Function function) return function.arguments().stream().anyMatch(this::containsQuery);
        if (expression instanceof Ast.Binary binary) return containsQuery(binary.left()) || containsQuery(binary.right());
        if (expression instanceof Ast.CaseWhen conditional) {
            return conditional.branches().stream().anyMatch(branch -> containsQuery(branch.condition()) || containsQuery(branch.value()))
                || conditional.elseValue() != null && containsQuery(conditional.elseValue());
        }
        return expression instanceof Ast.Unary unary && containsQuery(unary.value());
    }

    private void validateGrouping(Ast.Query query, List<Scope> parents) {
        var aliases = new ArrayList<String>();
        aliases.add(query.source().effectiveAlias());
        for (var join : query.joins()) aliases.add(join.source().effectiveAlias());
        var scope = new Scope(query, aliases, isGrouped(query));
        checkSource(query.source(), scope, parents);
        for (var join : query.joins()) checkSource(join.source(), scope, parents);
        for (var join : query.joins()) checkReferences(join.on(), scope, parents, false);
        if (query.where() != null) checkReferences(query.where(), scope, parents, false);
        for (var key : query.groupBy()) checkReferences(key, scope, parents, false);
        for (var field : query.projection().fields()) {
            checkReferences(field.value(), scope, parents, scope.grouped());
        }
        for (var order : query.orderBy()) {
            checkReferences(orderExpression(query, order.expression()), scope, parents, scope.grouped());
        }
    }

    private void checkSource(Ast.Source source, Scope scope, List<Scope> parents) {
        if (source.contextual()) {
            checkReferences(new Ast.Reference(source.name(), source.path()), scope, parents, false);
        }
    }

    private Ast.Expr orderExpression(Ast.Query query, Ast.Expr expression) {
        if (expression instanceof Ast.Reference ref && ref.qualifier() == null && ref.path().size() == 1) {
            for (var field : query.projection().fields()) {
                if (field.name().equals(ref.name())) return field.value();
            }
        }
        return expression;
    }

    private void checkReferences(Ast.Expr expression, Scope scope, List<Scope> parents, boolean grouped) {
        if (grouped && matchesKey(expression, scope)) return;
        if (expression instanceof Ast.Reference reference) {
            if (reference.qualifier() == null || scope.aliases().contains(reference.qualifier())) {
                if (grouped) throw new IllegalArgumentException("Field must be grouped or aggregated: " + reference);
            } else {
                for (int i = parents.size() - 1; i >= 0; i--) {
                    var parent = parents.get(i);
                    if (parent.aliases().contains(reference.qualifier())) {
                        if (parent.grouped() && !matchesKey(reference, parent)) {
                            throw new IllegalArgumentException("Correlated field must be grouped: " + reference);
                        }
                        break;
                    }
                }
            }
        } else if (expression instanceof Ast.Function function) {
            boolean rowArgument = functions.isAggregate(function.name());
            for (var argument : function.arguments()) checkReferences(argument, scope, parents, grouped && !rowArgument);
        } else if (expression instanceof Ast.Binary binary) {
            checkReferences(binary.left(), scope, parents, grouped);
            checkReferences(binary.right(), scope, parents, grouped);
        } else if (expression instanceof Ast.Unary unary) {
            checkReferences(unary.value(), scope, parents, grouped);
        } else if (expression instanceof Ast.CaseWhen conditional) {
            for (var branch : conditional.branches()) {
                checkReferences(branch.condition(), scope, parents, grouped);
                checkReferences(branch.value(), scope, parents, grouped);
            }
            if (conditional.elseValue() != null) checkReferences(conditional.elseValue(), scope, parents, grouped);
        } else if (expression instanceof Ast.NestedQuery nested) {
            var ancestors = new ArrayList<>(parents);
            // Subqueries in where/aggregate arguments see input rows, not projected groups.
            ancestors.add(new Scope(scope.query(), scope.aliases(), grouped));
            validateGrouping(nested.query(), ancestors);
        }
    }

    private boolean matchesKey(Ast.Expr expression, Scope scope) {
        var normalized = normalize(expression, scope);
        return scope.query().groupBy().stream().anyMatch(key -> normalize(key, scope).equals(normalized));
    }

    private Ast.Expr normalize(Ast.Expr expression, Scope scope) {
        if (expression instanceof Ast.Reference ref && ref.qualifier() == null && scope.aliases().size() == 1) {
            return new Ast.Reference(scope.aliases().getFirst(), ref.path());
        }
        if (expression instanceof Ast.Function function) {
            return new Ast.Function(FunctionRegistry.normalize(function.name()),
                function.arguments().stream().map(arg -> normalize(arg, scope)).toList());
        }
        if (expression instanceof Ast.Binary binary) {
            return new Ast.Binary(binary.operator(), normalize(binary.left(), scope), normalize(binary.right(), scope));
        }
        if (expression instanceof Ast.Unary unary) {
            return new Ast.Unary(unary.operator(), normalize(unary.value(), scope));
        }
        return expression;
    }
}
