package com.mosaicql.type;

import java.util.Objects;

public record PrimitiveType(PrimitiveKind kind) implements MType {
    public PrimitiveType {
        Objects.requireNonNull(kind);
    }
}
