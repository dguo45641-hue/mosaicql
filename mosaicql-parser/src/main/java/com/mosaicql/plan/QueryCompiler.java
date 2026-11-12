package com.mosaicql.plan;

import com.mosaicql.SemanticValidator;
import com.mosaicql.ast.Ast;
import com.mosaicql.runtime.AggregationValidator;
import com.mosaicql.runtime.FunctionRegistry;
import com.mosaicql.type.TypeInferencer;
import com.mosaicql.type.UnknownType;
import com.mosaicql.type.TableDefinition;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;

/** Performs checks once, before a query is handed to an interpreter. */
public final class QueryCompiler {
    private final FunctionRegistry functions;

    public QueryCompiler(FunctionRegistry functions) {
        this.functions = functions;
    }

    public CompiledQuery compile(Ast.Query query) {
        return compile(query, Map.of());
    }

    public CompiledQuery compileUnchecked(Ast.Query query) {
        return compileUnchecked(query, Map.of());
    }

    public CompiledQuery compile(Ast.Query query, Map<String, TableDefinition> sources) {
        SemanticValidator.validate(query);
        new AggregationValidator(functions).validate(query);
        return compileUnchecked(query, sources);
    }

    public CompiledQuery compileUnchecked(Ast.Query query, Map<String, TableDefinition> sources) {
        var projection = query.projection().fields().stream().map(field -> type(field.value(), sources)).toList();
        var where = query.where() == null ? null : type(query.where(), sources);
        var groupBy = query.groupBy().stream().map(expression -> type(expression, sources)).toList();
        var orderBy = query.orderBy().stream().map(order -> type(order.expression(), sources)).toList();
        var joins = query.joins().stream().map(join -> type(join.on(), sources)).toList();
        var expressions = new ArrayList<>(projection);
        if (where != null) expressions.add(where);
        expressions.addAll(groupBy);
        expressions.addAll(orderBy);
        expressions.addAll(joins);
        var nestedQueries = directNestedQueries(query).stream()
            .map(nested -> compileUnchecked(nested, sources))
            .toList();
        return new CompiledQuery(query, expressions, projection, where, groupBy, orderBy, joins, nestedQueries).prepare(functions, sources);
    }

    private List<Ast.Query> directNestedQueries(Ast.Query query) {
        var result = new ArrayList<Ast.Query>();
        for (var join : query.joins()) collectDirectNested(join.on(), result);
        if (query.where() != null) collectDirectNested(query.where(), result);
        query.groupBy().forEach(expression -> collectDirectNested(expression, result));
        query.orderBy().forEach(order -> collectDirectNested(order.expression(), result));
        query.projection().fields().forEach(field -> collectDirectNested(field.value(), result));
        return result;
    }

    private void collectDirectNested(Ast.Expr expression, List<Ast.Query> result) {
        if (expression instanceof Ast.NestedQuery nested) {
            result.add(nested.query());
            return;
        }
        if (expression instanceof Ast.Binary binary) {
            collectDirectNested(binary.left(), result);
            collectDirectNested(binary.right(), result);
        } else if (expression instanceof Ast.Unary unary) {
            collectDirectNested(unary.value(), result);
        } else if (expression instanceof Ast.Function function) {
            function.arguments().forEach(argument -> collectDirectNested(argument, result));
        } else if (expression instanceof Ast.CaseWhen conditional) {
            conditional.branches().forEach(branch -> {
                collectDirectNested(branch.condition(), result);
                collectDirectNested(branch.value(), result);
            });
            if (conditional.elseValue() != null) collectDirectNested(conditional.elseValue(), result);
        }
    }

    private TypedExpression type(Ast.Expr expression, Map<String, TableDefinition> sources) {
        var children = new ArrayList<TypedExpression>();
        if (expression instanceof Ast.Binary binary) {
            children.add(type(binary.left(), sources)); children.add(type(binary.right(), sources));
        } else if (expression instanceof Ast.Unary unary) {
            children.add(type(unary.value(), sources));
        } else if (expression instanceof Ast.Function function) {
            function.arguments().stream().map(argument -> type(argument, sources)).forEach(children::add);
        } else if (expression instanceof Ast.CaseWhen(List<Ast.When> branches, Ast.Expr elseValue)) {
            branches.forEach(branch -> { children.add(type(branch.condition(), sources)); children.add(type(branch.value(), sources)); });
            if (elseValue != null) children.add(type(elseValue, sources));
        }
        var typed = TypeInferencer.expression(expression, sources, functions);
        ExpressionBinding binding = getBinding(expression);
        return new TypedExpression(expression, typed == null ? UnknownType.INSTANCE : typed, children, binding);
    }

    private static ExpressionBinding getBinding(Ast.Expr expression) {
        ExpressionBinding binding = null;
        if (expression instanceof Ast.Reference(String qualifier, List<String> path)) {
            binding = new ExpressionBinding.Reference(qualifier, path);
        } else if (expression instanceof Ast.Function function) {
            binding = new ExpressionBinding.Function(function.name().toLowerCase(java.util.Locale.ROOT));
        } else if (expression instanceof Ast.Binary binary) {
            binding = new ExpressionBinding.Operator(binary.operator());
        } else if (expression instanceof Ast.Unary unary) {
            binding = new ExpressionBinding.Operator(unary.operator());
        }
        return binding;
    }
}
