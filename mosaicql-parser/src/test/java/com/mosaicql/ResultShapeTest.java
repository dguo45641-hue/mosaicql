package com.mosaicql;

import com.mosaicql.runtime.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResultShapeTest {
    @Test void objectShapeKeepsOnlyFirstNestedRow() throws Exception {
        var source = new LinkedHashMap<String,Object>();
        source.put("children", List.of(Map.of("id", 1), Map.of("id", 2)));
        var result = new QueryExecutor(new InMemorySourceProvider(Map.of("users", new Table(List.of(new Row(source))))))
            .execute(MosaicQLParser.parse("users as u { profile: u.children as c { * } }"));
        assertInstanceOf(Row.class, result.rows().getFirst().values().get("profile"));
        assertEquals(1, ((Row) result.rows().getFirst().values().get("profile")).values().get("id"));
    }

    @Test void arrayShapeKeepsAllNestedRows() throws Exception {
        var source = new LinkedHashMap<String,Object>();
        source.put("children", List.of(Map.of("id", 1), Map.of("id", 2)));
        var result = new QueryExecutor(new InMemorySourceProvider(Map.of("users", new Table(List.of(new Row(source))))))
            .execute(MosaicQLParser.parse("users as u { profile: u.children as c [{ * }] }"));
        assertInstanceOf(List.class, result.rows().getFirst().values().get("profile"));
        assertEquals(2, ((List<?>) result.rows().getFirst().values().get("profile")).size());
    }
}
