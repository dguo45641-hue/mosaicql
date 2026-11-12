package com.mosaicql.type;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record TableDefinition(String name, List<ColumnDefinition> columns) {
    public TableDefinition {
        Objects.requireNonNull(name);
        columns = List.copyOf(columns);
        var names = new LinkedHashMap<String, Boolean>();
        for (var column : columns) {
            if (names.put(column.name(), Boolean.TRUE) != null) {
                throw new IllegalArgumentException("Duplicate column: " + column.name());
            }
        }
    }

    public MType columnType(String column) {
        return columns.stream().filter(item -> item.name().equals(column))
            .map(ColumnDefinition::type).findFirst().orElse(UnknownType.INSTANCE);
    }
}
