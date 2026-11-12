package com.mosaicql.plan;

import com.mosaicql.ast.Ast;
import java.util.Objects;
import java.util.List;
import java.util.Map;

/** Immutable result of the language compilation phase. */
public record CompiledQuery(Ast.Query ast, List<TypedExpression> expressions,
                            List<TypedExpression> projection,
                            TypedExpression where,
                            List<TypedExpression> groupBy,
                            List<TypedExpression> orderBy,
                            List<TypedExpression> joins,
                            List<CompiledQuery> nestedQueries, ExecutablePlan executable) {
    public CompiledQuery {
        Objects.requireNonNull(ast);
        expressions = List.copyOf(expressions);
        projection = List.copyOf(projection);
        groupBy = List.copyOf(groupBy);
        orderBy = List.copyOf(orderBy);
        joins = List.copyOf(joins);
        nestedQueries = List.copyOf(nestedQueries);
    }
    public CompiledQuery(Ast.Query ast, List<TypedExpression> expressions, List<TypedExpression> projection,
                         TypedExpression where, List<TypedExpression> groupBy, List<TypedExpression> orderBy,
                         List<TypedExpression> joins, List<CompiledQuery> nestedQueries) {
        this(ast, expressions, projection, where, groupBy, orderBy, joins, nestedQueries, null);
    }
    public CompiledQuery prepare(com.mosaicql.runtime.FunctionRegistry functions,
                                 Map<String, com.mosaicql.type.TableDefinition> schemas) {
        return new CompiledQuery(ast, expressions, projection, where, groupBy, orderBy, joins, nestedQueries,
            new ExecutablePlan(this, functions, schemas));
    }
    public Map<String,Object> explain() {
        return executable==null?Map.of("evaluation","interpreter"):executable.explain();
    }
    public CompiledQuery(Ast.Query ast) { this(ast, List.of()); }
    public CompiledQuery(Ast.Query ast, List<TypedExpression> expressions) {
        this(ast, expressions, List.of(), null, List.of(), List.of(), List.of(), List.of());
    }

    public CompiledQuery(Ast.Query ast, List<TypedExpression> expressions,
                         List<TypedExpression> projection, TypedExpression where,
                         List<TypedExpression> groupBy, List<TypedExpression> orderBy,
                         List<TypedExpression> joins) {
        this(ast, expressions, projection, where, groupBy, orderBy, joins, List.of());
    }

    public CompiledQuery nested(Ast.Query query) {
        for (var nested : nestedQueries) {
            if (nested.ast() == query) return nested;
        }
        return null;
    }
}
