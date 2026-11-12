package com.mosaicql.runtime;

import com.mosaicql.type.TableDefinition;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.List;
import java.util.Objects;

/** Immutable shared column layout. Field positions never depend on JSON property order. */
public final class RowLayout {
    private final String[] names;
    private final Object2IntOpenHashMap<String> positions;

    public RowLayout(List<String> columns) {
        names = columns.toArray(String[]::new);
        positions = new Object2IntOpenHashMap<>(names.length);
        positions.defaultReturnValue(-1);
        for (int i = 0; i < names.length; i++) {
            Objects.requireNonNull(names[i]);
            if (positions.putIfAbsent(names[i], i) != -1)
                throw new IllegalArgumentException("Duplicate column: " + names[i]);
        }
    }
    public static RowLayout of(TableDefinition schema) {
        return new RowLayout(schema.columns().stream().map(column -> column.name()).toList());
    }
    public int size() { return names.length; }
    public String nameAt(int slot) { return names[slot]; }
    public int indexOf(String name) { return positions.getInt(name); }
    public List<String> columns() { return List.of(names); }
    String[] names() { return names; }
}
