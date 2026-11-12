package com.mosaicql.error;

import java.util.Map;

/** Base exception for errors produced by the language implementation. */
public class MosaicQLException extends IllegalArgumentException {
    private final String code;
    private final String phase;
    private final Map<String, Object> details;

    public MosaicQLException(String code, String phase, String message) {
        this(code, phase, message, Map.of(), null);
    }

    public MosaicQLException(ErrorCode code, String phase, String message) {
        this(code.name(), phase, message);
    }

    public MosaicQLException(String code, String phase, String message,
                             Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.phase = phase;
        this.details = Map.copyOf(details);
    }

    public String code() { return code; }
    public String phase() { return phase; }
    public Map<String, Object> details() { return details; }

    public static MosaicQLException wrap(String code, String phase, String message, Throwable cause) {
        if (cause instanceof MosaicQLException error) return error;
        return new MosaicQLException(code, phase, message, Map.of(), cause);
    }
}
