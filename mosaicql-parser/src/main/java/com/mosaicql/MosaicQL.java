package com.mosaicql;

import com.mosaicql.ast.Ast;
import com.mosaicql.error.MosaicQLException;
import com.mosaicql.plan.CompiledQuery;
import com.mosaicql.plan.QueryCompiler;
import com.mosaicql.runtime.ExecutionOptions;
import com.mosaicql.runtime.FunctionRegistry;
import com.mosaicql.runtime.JsonAdapter;
import com.mosaicql.runtime.QueryExecutor;
import com.mosaicql.runtime.QueryResult;
import com.mosaicql.runtime.SourceProvider;

/** Stable public facade for parsing, compiling and executing MosaicQL 0.1. */
public final class MosaicQL {
    public static final String VERSION = "0.1";

    private MosaicQL() {}

    public static Ast.Query parse(String text) {
        try {
            return MosaicQLParser.parse(text);
        } catch (Exception error) {
            throw MosaicQLException.wrap("PARSE_ERROR", "parse", "Failed to parse MosaicQL", error);
        }
    }

    public static CompiledQuery compile(String text) {
        return compile(text, new FunctionRegistry());
    }

    public static CompiledQuery compile(String text, FunctionRegistry functions) {
        return compile(text, functions, java.util.Map.of());
    }

    public static CompiledQuery compile(String text, FunctionRegistry functions,
            java.util.Map<String, com.mosaicql.type.TableDefinition> schemas) {
        try {
            return new QueryCompiler(functions).compile(parse(text), schemas);
        } catch (MosaicQLException error) {
            throw error;
        } catch (RuntimeException error) {
            throw MosaicQLException.wrap("COMPILE_ERROR", "compile", "Failed to compile MosaicQL", error);
        }
    }

    public static QueryResult execute(String text, SourceProvider sources) {
        return execute(text, sources, new FunctionRegistry(), ExecutionOptions.DEFAULT);
    }

    public static QueryResult execute(String text, SourceProvider sources,
                                      FunctionRegistry functions, ExecutionOptions options) {
        try {
            var compiled = compile(text, functions);
            return new QueryExecutor(sources, functions).executeResult(compiled, options);
        } catch (MosaicQLException error) {
            throw error;
        } catch (RuntimeException error) {
            throw MosaicQLException.wrap("EXECUTION_ERROR", "execution", "Failed to execute MosaicQL", error);
        }
    }

    public static String executeJson(String text, SourceProvider sources,
                                     FunctionRegistry functions, ExecutionOptions options) {
        try {
            return JsonAdapter.write(execute(text, sources, functions, options));
        } catch (MosaicQLException error) {
            throw error;
        } catch (Exception error) {
            throw MosaicQLException.wrap("EXECUTION_ERROR", "output", "Failed to serialize MosaicQL result", error);
        }
    }
}
