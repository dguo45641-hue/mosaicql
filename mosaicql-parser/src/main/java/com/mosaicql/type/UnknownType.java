package com.mosaicql.type;

public record UnknownType() implements MType {
    public static final UnknownType INSTANCE = new UnknownType();
}
