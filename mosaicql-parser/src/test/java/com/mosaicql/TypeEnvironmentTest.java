package com.mosaicql;

import com.mosaicql.type.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TypeEnvironmentTest {
    @Test void resolvesNestedFieldsAndUsesExplicitCompatibilityRules() {
        var definition = new TableDefinition("users", List.of(
            new ColumnDefinition("id", MType.NUMBER, false),
            new ColumnDefinition("profile", MType.object(Map.of("name", MType.STRING)), true)));
        var env = new TypeEnvironment().bind("u", definition);
        assertEquals(MType.STRING, env.lookup("u", List.of("profile", "name")));
        assertTrue(MTypeFactory.canAssign(MType.NUMBER, MType.NULL));
        assertFalse(MTypeFactory.canAssign(MType.BOOLEAN, MType.NUMBER));
        assertEquals(MType.NUMBER, MTypeFactory.common(MType.NUMBER, MType.NULL));
    }
}
