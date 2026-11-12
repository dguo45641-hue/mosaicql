package com.mosaicql.type;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Lexical source/type scope used while binding references. */
public final class TypeEnvironment {
    private final Map<String, TableDefinition> sources;
    private final TypeEnvironment parent;

    public TypeEnvironment() { this(Map.of(), null); }
    private TypeEnvironment(Map<String, TableDefinition> sources, TypeEnvironment parent) {
        this.sources = Map.copyOf(sources);
        this.parent = parent;
    }
    public TypeEnvironment child() { return new TypeEnvironment(Map.of(), this); }
    public TypeEnvironment bind(String alias, TableDefinition definition) {
        var next = new LinkedHashMap<>(sources);
        next.put(Objects.requireNonNull(alias), Objects.requireNonNull(definition));
        return new TypeEnvironment(next, parent);
    }
    public TableDefinition source(String alias) {
        var result = sources.get(alias);
        return result != null ? result : parent == null ? null : parent.source(alias);
    }
    public MType lookup(String alias, java.util.List<String> path) {
        var definition = source(alias);
        if (definition == null || path.isEmpty()) return UnknownType.INSTANCE;
        MType type = definition.columnType(path.getFirst());
        for (int i = 1; i < path.size(); i++) {
            if (!(type instanceof ObjectType object)) return UnknownType.INSTANCE;
            type = object.fields().getOrDefault(path.get(i), UnknownType.INSTANCE);
        }
        return type;
    }
}
