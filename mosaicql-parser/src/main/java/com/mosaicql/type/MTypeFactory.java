package com.mosaicql.type;

import java.util.LinkedHashMap;
import java.util.Map;

/** Construction and compatibility rules for MosaicQL types. */
public final class MTypeFactory {
    private MTypeFactory() {}

    public static MType runtime(Object value) { return TypeInferencer.runtime(value); }

    public static MType common(MType left, MType right) {
        if (left == null || left.isUnknown()) return right == null ? UnknownType.INSTANCE : right;
        if (right == null || right.isUnknown() || left.equals(right)) return left;
        if (left == MType.NULL) return right;
        if (right == MType.NULL) return left;
        if (left instanceof ArrayType(MType leftType) && right instanceof ArrayType(MType rightType)) {
            return new ArrayType(common(leftType, rightType));
        }
        if (left instanceof ObjectType(Map<String, MType> leftType) &&
                right instanceof ObjectType(Map<String, MType> rightType)) {
            var fields = new LinkedHashMap<>(leftType);
            rightType.forEach((name, type) -> fields.merge(name, type, MTypeFactory::common));
            return new ObjectType(fields);
        }
        return UnknownType.INSTANCE;
    }

    public static boolean canAssign(MType target, MType source) {
        if (target == null || source == null || target.isUnknown() || source.isUnknown()) return true;
        if (source == MType.NULL) return true;
        return common(target, source).equals(target);
    }
}
