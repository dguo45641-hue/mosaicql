package com.mosaicql.type;

import java.util.Objects;

public record ColumnDefinition(String name, MType type, boolean nullable) {
    public ColumnDefinition {
        Objects.requireNonNull(name);
        Objects.requireNonNull(type);
        if (name.isBlank()) throw new IllegalArgumentException("Column name cannot be blank");
    }
}
