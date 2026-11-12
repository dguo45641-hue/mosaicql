package com.mosaicql;

import com.mosaicql.plan.QueryCompiler;
import com.mosaicql.plan.DefaultExpressionInterpreter;
import com.mosaicql.plan.ExpressionContext;
import com.mosaicql.runtime.FunctionRegistry;
import com.mosaicql.runtime.Row;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class IfExpressionTest {
    @Test void parsesIfElseIfElseAsCaseWhen() throws Exception {
        var query = MosaicQLParser.parse("t { \"x\": if true then 1 else if false then 2 else 3 fi }");
        var expression = new QueryCompiler(new FunctionRegistry()).compile(query).expressions().getFirst();
        var context = new ExpressionContext() {
            public Object reference(com.mosaicql.ast.Ast.Reference ref) { return null; }
            public Object nested(com.mosaicql.ast.Ast.Query nested) { return List.of(); }
            public Object function(com.mosaicql.ast.Ast.Function fn, List<Object> args) { return null; }
            public Map<String, Row> rows() { return Map.of(); }
        };
        assertEquals(1, ((java.math.BigDecimal) new DefaultExpressionInterpreter().evaluate(expression, context)).intValue());
    }
}
