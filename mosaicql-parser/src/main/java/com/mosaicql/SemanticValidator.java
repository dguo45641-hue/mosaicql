package com.mosaicql;

import com.mosaicql.ast.Ast;

import java.util.HashSet;
import java.util.Set;

/** Validates names and scopes without executing a query. */
public final class SemanticValidator {
    private SemanticValidator() {}

    public static void validate(Ast.Query query) {
        validateQuery(query, Set.of());
    }

    private static void validateQuery(Ast.Query query, Set<String> parentAliases) {
        validateSource(query.source(), parentAliases);
        var localAliases = new HashSet<String>();
        addAlias(localAliases, query.source());
        var visibleAliases = new HashSet<>(parentAliases);
        visibleAliases.addAll(localAliases);
        for (var join : query.joins()) {
            validateSource(join.source(), visibleAliases);
            if (join.type() == Ast.JoinType.RIGHT && join.source().contextual()) {
                throw new IllegalArgumentException("RIGHT JOIN source must be independent: "
                    + join.source().name());
            }
            addAlias(localAliases, join.source());
            visibleAliases.add(join.source().effectiveAlias());
            validateExpression(join.on(), visibleAliases);
        }
        if (query.where() != null) {
            validateExpression(query.where(), visibleAliases);
        }
        for (var expression : query.groupBy()) {
            validateExpression(expression, visibleAliases);
        }
        for (var order : query.orderBy()) validateExpression(order.expression(), visibleAliases);
        var fieldNames = new HashSet<String>();
        for (var field : query.projection().fields()) {
            if (!fieldNames.add(field.name())) {
                throw new IllegalArgumentException("Duplicate output field: " + field.name());
            }
            validateExpression(field.value(), visibleAliases);
        }
    }

    private static void validateSource(Ast.Source source, Set<String> visibleAliases) {
        if (source.contextual() && !visibleAliases.contains(source.name())) {
            throw new IllegalArgumentException("Unknown source alias: " + source.name());
        }
    }

    private static void addAlias(Set<String> aliases, Ast.Source source) {
        if (!aliases.add(source.effectiveAlias())) {
            throw new IllegalArgumentException("Duplicate source alias: " + source.effectiveAlias());
        }
    }

    private static void validateExpression(Ast.Expr expression, Set<String> visibleAliases) {
        if (expression instanceof Ast.Reference reference) {
            if (reference.qualifier() != null && !visibleAliases.contains(reference.qualifier())) {
                throw new IllegalArgumentException("Unknown source alias: " + reference.qualifier());
            }
        } else if (expression instanceof Ast.Binary binary) {
            validateExpression(binary.left(), visibleAliases);
            validateExpression(binary.right(), visibleAliases);
        } else if (expression instanceof Ast.Unary unary) {
            validateExpression(unary.value(), visibleAliases);
        } else if (expression instanceof Ast.Function function) {
            for (var argument : function.arguments()) {
                validateExpression(argument, visibleAliases);
            }
        } else if (expression instanceof Ast.NestedQuery nested) {
            validateQuery(nested.query(), visibleAliases);
        } else if (expression instanceof Ast.CaseWhen conditional) {
            for (var branch : conditional.branches()) {
                validateExpression(branch.condition(), visibleAliases);
                validateExpression(branch.value(), visibleAliases);
            }
            if (conditional.elseValue() != null) validateExpression(conditional.elseValue(), visibleAliases);
        }
    }
}
