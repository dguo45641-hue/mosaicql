package com.mosaicql.runtime;

import com.mosaicql.error.MosaicQLException;
import java.util.Map;

final class ExecutionState {
    private final ExecutionOptions options;
    private final long deadlineNanos;
    private int rows;

    ExecutionState(ExecutionOptions options) {
        this.options = options;
        this.deadlineNanos = options.timeoutMillis() == 0 ? 0
            : System.nanoTime() + options.timeoutMillis() * 1_000_000L;
    }

    void check(int depth) {
        if (Thread.currentThread().isInterrupted()) {
            throw new MosaicQLException("EXECUTION_CANCELLED", "execution", "Query execution was cancelled",
                Map.of(), null);
        }
        if (deadlineNanos != 0 && System.nanoTime() >= deadlineNanos) {
            throw new MosaicQLException("EXECUTION_TIMEOUT", "execution", "Query execution timed out",
                Map.of("timeoutMillis", options.timeoutMillis()), null);
        }
        if (depth > options.maxNestingDepth()) {
            throw new MosaicQLException("MAX_NESTING_DEPTH", "execution", "Maximum query nesting depth exceeded",
                Map.of("maxNestingDepth", options.maxNestingDepth()), null);
        }
    }

    void row() {
        if (options.maxRows() != 0 && ++rows > options.maxRows()) {
            throw new MosaicQLException("MAX_ROWS", "execution", "Maximum row limit exceeded",
                Map.of("maxRows", options.maxRows()), null);
        }
    }

}
