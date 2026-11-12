package com.mosaicql;

import com.mosaicql.plan.QueryCompiler;
import com.mosaicql.runtime.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryExecutorInterpreterTest {
    @Test void executorExposesTypedExpressionEvaluation() throws Exception {
        var query = MosaicQLParser.parse("t as t { \"x\": t.value + 1 }");
        var compiled = new QueryCompiler(new FunctionRegistry()).compile(query);
        var executor = new QueryExecutor(new InMemorySourceProvider(Map.of(
            "t", new Table(java.util.List.of(new Row(Map.of("value", 4)))))));
        assertEquals(5, ((java.math.BigDecimal) executor.evaluate(
            compiled.expressions().getFirst(), Map.of("t", new Row(Map.of("value", 4))))).intValue());
    }
}
