package com.mosaicql;

import com.mosaicql.plan.*;
import com.mosaicql.runtime.Row;
import com.mosaicql.type.MType;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpressionInterpreterTest {
    @Test void evaluatesTypedArithmeticTree() throws Exception {
        var query = MosaicQLParser.parse("t { \"x\": 1 + 2 * 3 }");
        var expression = new QueryCompiler(new com.mosaicql.runtime.FunctionRegistry()).compile(query)
            .expressions().getFirst();
        var context = new ExpressionContext() {
            public Object reference(com.mosaicql.ast.Ast.Reference ref) { return null; }
            public Object nested(com.mosaicql.ast.Ast.Query nested) { return List.of(); }
            public Object function(com.mosaicql.ast.Ast.Function fn, List<Object> args) { return null; }
            public Map<String, Row> rows() { return Map.of(); }
        };
        assertEquals(7, ((java.math.BigDecimal) new DefaultExpressionInterpreter().evaluate(expression, context)).intValue());
        assertEquals(MType.NUMBER, expression.type());
    }

    @Test void usesNormalizedBindingsForFunctionDispatch() throws Exception {
        var query = MosaicQLParser.parse("t { \"x\": ABS(2) }");
        var expression = new QueryCompiler(new com.mosaicql.runtime.FunctionRegistry()).compile(query)
            .expressions().getFirst();
        var context = new ExpressionContext() {
            public Object reference(com.mosaicql.ast.Ast.Reference ref) { return null; }
            public Object nested(com.mosaicql.ast.Ast.Query nested) { return List.of(); }
            public Object function(com.mosaicql.ast.Ast.Function fn, List<Object> args) {
                assertEquals("abs", fn.name());
                return args.getFirst();
            }
            public Map<String, Row> rows() { return Map.of(); }
        };
        assertEquals(2, ((java.math.BigDecimal) new DefaultExpressionInterpreter().evaluate(expression, context)).intValue());
    }
}
