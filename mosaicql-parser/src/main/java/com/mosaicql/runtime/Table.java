package com.mosaicql.runtime;

import java.util.List;
import java.util.Objects;

public record Table(List<Row> rows) {
    public Table {
        Objects.requireNonNull(rows);
        rows = List.copyOf(rows);
    }
}
