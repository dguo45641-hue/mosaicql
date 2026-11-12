package com.mosaicql.plan;

import com.mosaicql.ast.Ast;
import com.mosaicql.runtime.Row;
import java.util.List;
import java.util.Map;

/** Context for expressions evaluated over a group rather than one row. */
public interface AggregateExpressionContext extends ExpressionContext {
    List<Map<String, Row>> group();
    Object aggregate(Ast.Function function, List<Object> values);

    default Object aggregate(TypedExpression expression) {
        if (!(expression.ast() instanceof Ast.Function function)) {
            throw new IllegalArgumentException("Aggregate expression must be a function");
        }
        return aggregate(new Ast.Function(function.name().toLowerCase(java.util.Locale.ROOT), function.arguments()), List.of());
    }
}
