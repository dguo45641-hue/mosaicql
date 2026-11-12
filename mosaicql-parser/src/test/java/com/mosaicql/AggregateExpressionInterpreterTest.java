package com.mosaicql;

import com.mosaicql.plan.QueryCompiler;
import com.mosaicql.runtime.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AggregateExpressionInterpreterTest {
    @Test void evaluatesAggregateInsideArithmeticExpression() throws Exception {
        var registry = new FunctionRegistry();
        var query = MosaicQLParser.parse("sales as s { \"total\": sum(s.amount) + 100 }");
        var expression = new QueryCompiler(registry).compile(query).expressions().getFirst();
        var group = List.of(
            Map.of("s", new Row(Map.of("amount", 2))),
            Map.of("s", new Row(Map.of("amount", 3))));
        var executor = new QueryExecutor(name -> new Table(List.of()), registry);
        var result = executor.evaluateAggregate(expression, group.getFirst(), group, List.of("s"));
        System.out.println(result);
        assertEquals(0, new java.math.BigDecimal("105").compareTo((java.math.BigDecimal) result));
    }
}
