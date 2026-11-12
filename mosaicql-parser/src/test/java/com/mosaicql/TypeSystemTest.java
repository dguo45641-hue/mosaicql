package com.mosaicql;

import com.mosaicql.ast.Ast;
import com.mosaicql.type.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TypeSystemTest {
    @Test void infersRuntimeJsonLikeTypesRecursively() {
        var definition = TypeInferencer.inferTable("users", List.of(
            Map.of("id", 1, "profile", Map.of("city", "Shanghai"), "tags", List.of("a", "b")),
            Map.of("id", 2, "profile", Map.of("city", "Beijing"), "tags", List.of("c"))));

        assertEquals(MType.NUMBER, definition.columnType("id"));
        assertEquals(new ObjectType(Map.of("city", MType.STRING)), definition.columnType("profile"));
        assertEquals(new ArrayType(MType.STRING), definition.columnType("tags"));
        assertEquals(3, definition.columns().size());
    }

    @Test void infersNullableAndMissingFieldsAsNullableUnknownOrConcrete() {
        var first = new java.util.LinkedHashMap<String, Object>();
        first.put("id", 1);
        first.put("name", null);
        var second = new java.util.LinkedHashMap<String, Object>();
        second.put("id", 2);
        var definition = TypeInferencer.inferTable("users", List.of(first, second));
        var name = definition.columns().stream().filter(column -> column.name().equals("name")).findFirst().orElseThrow();
        assertEquals(MType.NULL, name.type());
        assertTrue(name.nullable());
    }

    @Test void infersReferencesPathsAndFunctionResults() throws Exception {
        var source = new TableDefinition("users", List.of(
            new ColumnDefinition("id", MType.NUMBER, false),
            new ColumnDefinition("profile", new ObjectType(Map.of("city", MType.STRING)), true)));
        var sources = Map.of("u", source);
        var query = MosaicQLParser.parse("users as u { \"city\": u.profile.city, \"score\": abs(id), \"name\": lower(u.profile.city) }");
        var fields = query.projection().fields();
        assertEquals(MType.STRING, TypeInferencer.expression(fields.get(0).value(), sources));
        assertEquals(MType.NUMBER, TypeInferencer.expression(fields.get(1).value(), sources));
        assertEquals(MType.STRING, TypeInferencer.expression(fields.get(2).value(), sources));
    }

    @Test void binaryTypesAndUnknownSourcesAreConservative() throws Exception {
        var query = MosaicQLParser.parse("users { \"x\": id + 1, \"ok\": id > 0, \"unknown\": custom(id) }");
        var definition = new TableDefinition("users", List.of(new ColumnDefinition("id", MType.NUMBER, false)));
        var fields = query.projection().fields();
        assertEquals(MType.NUMBER, TypeInferencer.expression(fields.get(0).value(), Map.of("users", definition)));
        assertEquals(MType.BOOLEAN, TypeInferencer.expression(fields.get(1).value(), Map.of("users", definition)));
        assertTrue(TypeInferencer.expression(fields.get(2).value(), Map.of("users", definition)).isUnknown());
    }

    @Test void rejectsDuplicateDefinitionColumnsAndBlankNames() {
        assertThrows(IllegalArgumentException.class, () -> new TableDefinition("t", List.of(
            new ColumnDefinition("id", MType.NUMBER, false), new ColumnDefinition("id", MType.STRING, true))));
        assertThrows(IllegalArgumentException.class, () -> new ColumnDefinition(" ", MType.STRING, true));
    }
}
