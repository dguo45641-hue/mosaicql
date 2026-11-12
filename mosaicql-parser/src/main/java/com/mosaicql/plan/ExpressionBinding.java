package com.mosaicql.plan;

import java.util.List;

/** Resolved symbol information attached to a typed expression. */
public sealed interface ExpressionBinding permits ExpressionBinding.Reference, ExpressionBinding.Function, ExpressionBinding.Operator {
    record Reference(String qualifier, List<String> path) implements ExpressionBinding {
        public Reference { path = List.copyOf(path); }
    }
    record Function(String normalizedName) implements ExpressionBinding {}
    record Operator(String symbol) implements ExpressionBinding {}
}
