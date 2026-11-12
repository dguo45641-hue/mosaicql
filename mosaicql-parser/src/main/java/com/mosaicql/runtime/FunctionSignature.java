package com.mosaicql.runtime;

import com.mosaicql.type.MType;
import java.util.List;
import java.util.Objects;

/** Static signature used for validation and runtime invocation of a function. */
public record FunctionSignature(List<MType> parameterTypes, int minArgs, int maxArgs,
                                MType returnType, boolean aggregate) {
    public FunctionSignature {
        parameterTypes = List.copyOf(parameterTypes);
        Objects.requireNonNull(returnType);
        if (minArgs < 0 || maxArgs < minArgs || parameterTypes.isEmpty() && maxArgs > 0) {
            throw new IllegalArgumentException("Invalid function argument range");
        }
    }
    public boolean accepts(int count) {
        return count >= minArgs && count <= maxArgs;
    }
    public MType parameterType(int index) {
        return parameterTypes.get(Math.min(index, parameterTypes.size() - 1));
    }
}
