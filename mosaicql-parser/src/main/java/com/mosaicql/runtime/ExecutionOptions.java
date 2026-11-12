package com.mosaicql.runtime;

/** Resource limits for one query execution. A zero value disables that limit. */
public record ExecutionOptions(long timeoutMillis, int maxRows, int maxNestingDepth) {
    public static final ExecutionOptions DEFAULT = new ExecutionOptions(0, 0, 64);

    public ExecutionOptions {
        if (timeoutMillis < 0) throw new IllegalArgumentException("timeoutMillis cannot be negative");
        if (maxRows < 0) throw new IllegalArgumentException("maxRows cannot be negative");
        if (maxNestingDepth < 1) throw new IllegalArgumentException("maxNestingDepth must be positive");
    }
}
