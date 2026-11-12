package com.mosaicql.runtime;

import java.util.Map;

public final class InMemorySourceProvider implements SourceProvider {
    private final Map<String, Table> sources;

    public InMemorySourceProvider(Map<String, Table> sources) {
        this.sources = Map.copyOf(sources);
    }

    @Override
    public Table get(String sourceName) {
        var table = sources.get(sourceName);
        if (table == null) {
            throw new IllegalArgumentException("Unknown source: " + sourceName);
        }
        return table;
    }
}
