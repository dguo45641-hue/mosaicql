package com.mosaicql.type;

import java.util.Map;

public sealed interface MType permits PrimitiveType, ArrayType, ObjectType, UnknownType {
    PrimitiveType NULL = new PrimitiveType(PrimitiveKind.NULL);
    PrimitiveType BOOLEAN = new PrimitiveType(PrimitiveKind.BOOLEAN);
    PrimitiveType NUMBER = new PrimitiveType(PrimitiveKind.NUMBER);
    PrimitiveType STRING = new PrimitiveType(PrimitiveKind.STRING);
    PrimitiveType DATE = new PrimitiveType(PrimitiveKind.DATE);
    PrimitiveType TIMESTAMP = new PrimitiveType(PrimitiveKind.TIMESTAMP);

    default boolean isUnknown() { return this instanceof UnknownType; }

    static MType array(MType element) { return new ArrayType(element); }
    static MType object(Map<String, MType> fields) { return new ObjectType(fields); }
}
