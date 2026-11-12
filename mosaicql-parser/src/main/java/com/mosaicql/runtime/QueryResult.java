package com.mosaicql.runtime;

import com.mosaicql.ast.Ast;

import java.util.List;
import java.util.Objects;

/** A query's output shape, applied after filtering, grouping, sorting and limit. */
public record QueryResult(Ast.ResultShape shape, List<Row> rows) {
    public QueryResult {
        Objects.requireNonNull(shape);
        rows = List.copyOf(rows);
        if (shape == Ast.ResultShape.OBJECT && rows.size() > 1) {
            rows = List.of(rows.getFirst());
        }
    }

    /** Returns Row or null for {}, and List<Row> for [{}]. */
    public Object value() {
        return shape == Ast.ResultShape.ARRAY ? rows : rows.isEmpty() ? null : rows.getFirst();
    }
}
