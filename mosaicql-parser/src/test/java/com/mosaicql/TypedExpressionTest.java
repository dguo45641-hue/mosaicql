package com.mosaicql;

import com.mosaicql.plan.QueryCompiler;
import com.mosaicql.runtime.FunctionRegistry;
import com.mosaicql.type.MType;
import com.mosaicql.type.TableDefinition;
import com.mosaicql.type.ColumnDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

class TypedExpressionTest {
    @Test void compilerRetainsTypedExpressionTree() throws Exception {
        var query = MosaicQLParser.parse("users { \"value\": abs(1 + 2) } where true");
        var compiled = new QueryCompiler(new FunctionRegistry()).compile(query);
        assertEquals(2, compiled.expressions().size());
        assertEquals(MType.NUMBER, compiled.expressions().getFirst().type());
        assertEquals(1, compiled.projection().size());
        assertEquals(MType.BOOLEAN, compiled.where().type());
        assertEquals(1, compiled.expressions().getFirst().children().size());
        assertEquals("abs", ((com.mosaicql.plan.ExpressionBinding.Function)
            compiled.expressions().getFirst().binding()).normalizedName());
    }

    @Test void compilerUsesRegisteredRowTypesForQualifiedReferences() throws Exception {
        var query = MosaicQLParser.parse("users as u { \"name\": upper(u.name) }");
        var schema = new TableDefinition("users", List.of(
            new ColumnDefinition("name", MType.STRING, false)));
        var compiled = new QueryCompiler(new FunctionRegistry()).compile(query, java.util.Map.of("u", schema));
        assertEquals(MType.STRING, compiled.expressions().getFirst().type());
        assertEquals(MType.STRING, compiled.expressions().getFirst().children().getFirst().type());
    }
}
