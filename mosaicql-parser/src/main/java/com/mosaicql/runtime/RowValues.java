package com.mosaicql.runtime;

import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Immutable, ordered projection storage. The executor owns both arrays; column names are shared. */
final class RowValues extends AbstractMap<String, Object> {
    private final Object columns;
    private final Object[] values;
    private volatile Map<String, Object> view;

    RowValues(String[] names, Object[] values) {
        this(names, values, null);
    }
    RowValues(RowLayout layout, Object[] values) {
        this(layout.names(), values, layout);
    }
    private RowValues(String[] names, Object[] values, RowLayout layout) {
        if (names.length != values.length) throw new IllegalArgumentException("Column/value count mismatch");
        this.columns = layout == null ? names : layout;
        this.values = values;
    }

    private String[] names() { return columns instanceof RowLayout layout ? layout.names() : (String[])columns; }
    String nameAt(int index) { return names()[index]; }
    Object valueAt(int index) { return values[index]; }
    @Override public int size() { return values.length; }
    private int indexOf(Object key) {
        if (columns instanceof RowLayout layout && key instanceof String name) return layout.indexOf(name);
        var names=names();
        for (int i = names.length - 1; i >= 0; i--) if (java.util.Objects.equals(names[i], key)) return i;
        return -1;
    }
    Object read(String name, int preferredSlot) {
        var names=names();
        if (preferredSlot >= 0 && preferredSlot < names.length && names[preferredSlot].equals(name))
            return values[preferredSlot];
        return get(name);
    }
    @Override public Object get(Object key) { int index = indexOf(key); return index < 0 ? null : values[index]; }
    @Override public boolean containsKey(Object key) { return indexOf(key) >= 0; }
    @Override public boolean containsValue(Object value) {
        for (var item : values) if (java.util.Objects.equals(item, value)) return true;
        return false;
    }
    private Map<String, Object> view() {
        var current = view;
        if (current == null) {
            // Most rows are read by index by the JSON writer. Allocate collection views only on demand.
            current = Collections.unmodifiableMap(new Object2ObjectArrayMap<>(names(), values));
            view = current;
        }
        return current;
    }
    @Override public Set<Entry<String, Object>> entrySet() { return view().entrySet(); }
    @Override public Set<String> keySet() { return view().keySet(); }
    @Override public Collection<Object> values() { return view().values(); }
    @Override public void forEach(BiConsumer<? super String, ? super Object> consumer) {
        java.util.Objects.requireNonNull(consumer);
        var names=names();
        for (int i = 0; i < names.length; i++) consumer.accept(names[i], values[i]);
    }

    // Match the public Row contract, including immutable entry/key/value views and default mutators.
    @Override public Object put(String key, Object value) { throw new UnsupportedOperationException(); }
    @Override public Object remove(Object key) { throw new UnsupportedOperationException(); }
    @Override public void putAll(Map<? extends String, ?> map) { throw new UnsupportedOperationException(); }
    @Override public void clear() { throw new UnsupportedOperationException(); }
    @Override public Object putIfAbsent(String key, Object value) { throw new UnsupportedOperationException(); }
    @Override public boolean remove(Object key, Object value) { throw new UnsupportedOperationException(); }
    @Override public Object replace(String key, Object value) { throw new UnsupportedOperationException(); }
    @Override public boolean replace(String key, Object oldValue, Object newValue) { throw new UnsupportedOperationException(); }
    @Override public void replaceAll(BiFunction<? super String, ? super Object, ?> f) { throw new UnsupportedOperationException(); }
    @Override public Object computeIfAbsent(String key, Function<? super String, ?> f) { throw new UnsupportedOperationException(); }
    @Override public Object computeIfPresent(String key, BiFunction<? super String, ? super Object, ?> f) { throw new UnsupportedOperationException(); }
    @Override public Object compute(String key, BiFunction<? super String, ? super Object, ?> f) { throw new UnsupportedOperationException(); }
    @Override public Object merge(String key, Object value, BiFunction<? super Object, ? super Object, ?> f) { throw new UnsupportedOperationException(); }
}
