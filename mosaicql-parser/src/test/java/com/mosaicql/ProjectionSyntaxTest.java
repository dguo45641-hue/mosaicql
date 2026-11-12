package com.mosaicql;

import com.mosaicql.runtime.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ProjectionSyntaxTest {
    @Test void acceptsIdentifierOutputAliases() throws Exception {
        var query = MosaicQLParser.parse("users { display_name: name }");
        var row = new QueryExecutor(new InMemorySourceProvider(Map.of(
            "users", new Table(List.of(new Row(Map.of("name", "Ada"))))))).execute(query).rows().getFirst();
        assertEquals("Ada", row.values().get("display_name"));
    }

    @Test void expandsWildcardForSingleSource() throws Exception {
        var query = MosaicQLParser.parse("a { * }");
        var row = new QueryExecutor(new InMemorySourceProvider(Map.of(
            "a", new Table(List.of(new Row(new java.util.LinkedHashMap<>(Map.of("id", 1, "name", "x")))))))).execute(query).rows().getFirst();
        assertEquals(Map.of("id", 1, "name", "x"), row.values());
    }

    @Test void rejectsDuplicateFieldsDuringJoinWildcardExpansion() throws Exception {
        var query = MosaicQLParser.parse("a join b on a.id = b.id { * }");
        var executor = new QueryExecutor(new InMemorySourceProvider(Map.of(
            "a", new Table(List.of(new Row(Map.of("id", 1)))),
            "b", new Table(List.of(new Row(Map.of("id", 1)))))));
        assertThrows(IllegalArgumentException.class, () -> executor.execute(query));
    }
}
