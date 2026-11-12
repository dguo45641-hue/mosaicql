package com.mosaicql.runtime;

import java.util.Objects;

/** Complete scalar-function contract shared by validation and execution. */
public record FunctionDefinition(FunctionSignature signature,
                                 ReturnTypeInference returnTypeInference,
                                 Udf implementation) {
    public FunctionDefinition {
        Objects.requireNonNull(signature);
        Objects.requireNonNull(returnTypeInference);
        Objects.requireNonNull(implementation);
    }
}
