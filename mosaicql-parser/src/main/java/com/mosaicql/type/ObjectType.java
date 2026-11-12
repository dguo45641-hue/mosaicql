package com.mosaicql.type;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ObjectType(Map<String, MType> fields) implements MType {
    public ObjectType {
        Objects.requireNonNull(fields);
        fields = Map.copyOf(new LinkedHashMap<>(fields));
    }
}
