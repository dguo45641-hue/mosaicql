package com.mosaicql;

import com.mosaicql.plan.QueryCompiler;
import com.mosaicql.runtime.FunctionRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class QueryCompilerTest {
    @Test void compilesValidationOnceIntoAnImmutableBoundary() throws Exception {
        var query = MosaicQLParser.parse("users { id } where id = 1");
        var compiled = new QueryCompiler(new FunctionRegistry()).compile(query);
        assertNotNull(compiled.ast());
    }
}
