package com.mosaicql.runtime;

import java.util.List;

/** A scalar function: arguments may contain null; returning null is allowed.
 * Implementations should be deterministic and must not mutate arguments or input data.
 */
@FunctionalInterface
public interface Udf {
    Object apply(List<Object> arguments);
}
