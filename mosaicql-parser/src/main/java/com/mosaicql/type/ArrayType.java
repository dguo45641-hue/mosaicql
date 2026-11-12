package com.mosaicql.type;

import java.util.Objects;

public record ArrayType(MType elementType) implements MType {
    public ArrayType {
        Objects.requireNonNull(elementType);
    }
}
