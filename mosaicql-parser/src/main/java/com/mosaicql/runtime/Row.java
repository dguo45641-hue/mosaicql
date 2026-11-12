package com.mosaicql.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public record Row(Map<String, Object> values) {
    public Row {
        Objects.requireNonNull(values);
        if (!(values instanceof RowValues)) {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    /** Only called with arrays exclusively owned by the executor. Public construction still copies maps. */
    static Row projected(String[] columns, Object[] values) { return new Row(new RowValues(columns, values)); }
    static Row projected(RowLayout columns, Object[] values) { return new Row(new RowValues(columns, values)); }

    /** Schema-aware input construction. The layout is shared; caller-owned values are copied. */
    public static Row of(RowLayout layout, Object... values) {
        return projected(Objects.requireNonNull(layout), values.clone());
    }

    /** A compiled slot is guarded by its field name, so other layouts remain compatible. */
    public Object get(String field, int preferredSlot) {
        return values instanceof RowValues row ? row.read(field, preferredSlot) : values.get(field);
    }

    public Object get(List<String> path) {
        Object current = values;
        for (String part : path) {
            if (current instanceof Row(Map<String, Object> row)) current = row;
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }
}
