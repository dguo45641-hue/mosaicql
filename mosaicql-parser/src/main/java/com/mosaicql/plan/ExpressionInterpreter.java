package com.mosaicql.plan;

/** Evaluates a previously typed expression tree. */
@FunctionalInterface
public interface ExpressionInterpreter {
    Object evaluate(TypedExpression expression, ExpressionContext context);

    default Object evaluateAggregate(TypedExpression expression, AggregateExpressionContext context) {
        return evaluate(expression, context);
    }
}
