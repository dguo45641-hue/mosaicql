package com.mosaicql.runtime;

import com.mosaicql.type.MType;
import java.util.List;

@FunctionalInterface
public interface ReturnTypeInference {
    MType infer(List<MType> argumentTypes);
}
