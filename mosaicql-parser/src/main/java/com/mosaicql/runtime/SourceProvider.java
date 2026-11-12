package com.mosaicql.runtime;

@FunctionalInterface
public interface SourceProvider {
    Table get(String sourceName);
}
