package com.mosaicql;

import com.mosaicql.runtime.InMemorySourceProvider;
import com.mosaicql.runtime.QueryExecutor;
import com.mosaicql.runtime.QueryResult;
import com.mosaicql.runtime.Row;
import com.mosaicql.runtime.Table;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryExecutorTest {
    private QueryResult run(String text, Map<String, Table> sources) throws ParseException {
        return new QueryExecutor(new InMemorySourceProvider(sources)).executeResult(MosaicQLParser.parse(text));
    }

    @SafeVarargs
    private final Table table(Map<String, Object>... rows) {
        return new Table(java.util.Arrays.stream(rows).map(Row::new).toList());
    }

    @Test void filtersBeforeProjectionAndKeepsOutputOrderAndNulls() throws Exception {
        var result = run("""
            users {
              "renamed": id,
              "empty": null,
              missing,
              "score": amount * 2 + 1
            } where active
            """, Map.of("users", table(
                Map.of("id", 1, "active", true, "amount", 4),
                Map.of("id", 2, "active", false, "amount", "not a number"))));

        var row = result.rows().getFirst();
        assertEquals(1, result.rows().size());
        assertEquals(List.of("renamed", "empty", "missing", "score"), new ArrayList<>(row.values().keySet()));
        assertNull(row.values().get("empty"));
        assertNull(row.values().get("missing"));
        assertEquals(new BigDecimal("9"), row.values().get("score"));
    }

    @Test void comparesNumericTypesWithoutLosingLargeIntegerPrecision() throws Exception {
        var result = run("users [{ id }] where id = 1", Map.of("users", table(
            Map.of("id", 1), Map.of("id", 1L), Map.of("id", new BigDecimal("1.00")), Map.of("id", 2))));
        assertEquals(3, result.rows().size());
        var large = run("users { id } where id = 9007199254740993", Map.of("users", table(
            Map.of("id", 9007199254740992L), Map.of("id", 9007199254740993L))));
        assertEquals(List.of(new Row(Map.of("id", 9007199254740993L))), large.rows());
    }

    @Test void computesDecimalArithmeticAndRejectsDivisionByZero() throws Exception {
        long start = System.currentTimeMillis();
        var sources = Map.of("t", table(Map.of()));
        var row = run("""
            t { "total": 0.1 + 0.2, "negative": -2 * 3, "fraction": 1 / 4 }
            """, sources).rows().getFirst();
        assertEquals(new BigDecimal("0.3"), row.values().get("total"));
        assertEquals(new BigDecimal("-6"), row.values().get("negative"));
        assertEquals(new BigDecimal("0.25"), row.values().get("fraction"));
        assertThrows(ArithmeticException.class, () -> run("t { \"value\": 1 / 0 }", sources));
        System.out.println(System.currentTimeMillis() - start);
    }

    @Test void joinsSequentiallyKeepsDuplicatesAndLoadsEachJoinSourceOnce() throws Exception {
        var sources = new InMemorySourceProvider(Map.of(
            "a", table(Map.of("id", 1), Map.of("id", 2)),
            "b", table(Map.of("owner", 1L, "key", 10), Map.of("owner", 1, "key", 10)),
            "c", table(Map.of("id", 10, "label", "hit"))));
        var reads = new ArrayList<String>();
        var executor = new QueryExecutor(name -> {
            reads.add(name);
            return sources.get(name);
        });
        var result = executor.execute(MosaicQLParser.parse("""
            a join b on a.id = owner
              join c on b.key = c.id [{
                "id": a.id,
                "label": c.label
              }]
            """));
        assertEquals(2, result.rows().size());
        assertEquals(result.rows().getFirst(), result.rows().getLast());
        assertEquals(List.of("a", "b", "c"), reads);
    }

    @Test void executesNestedJoinWithSeparateInnerAndOuterWhere() throws Exception {
        var result = run("""
            users as u [{
              id,
              "orders": orders as o
                join products as p on o.product_id = p.id [{
                  "title": p.title,
                  "owner": u.id
                }] where o.user_id = u.id and p.enabled
            }] where u.active
            """, Map.of(
                "users", table(Map.of("id", 1, "active", true), Map.of("id", 2, "active", true)),
                "orders", table(Map.of("user_id", 1, "product_id", 9)),
                "products", table(Map.of("id", 9, "title", "Book", "enabled", true))));
        assertEquals(List.of(
            new Row(Map.of("id", 1, "orders", List.of(new Row(Map.of("title", "Book", "owner", 1))))),
            new Row(Map.of("id", 2, "orders", List.of()))), result.rows());
    }

    @Test void nestedBareFieldsStayLocalAndParentAliasesRemainVisible() throws Exception {
        var child = new LinkedHashMap<String, Object>();
        child.put("id", null);
        var result = run("""
            users as u {
              "children": children as c [{
                id,
                name,
                "parent": u.id,
                "leaves": leaves as l [{ id, "root": u.id, "child": c.id }]
              }]
            }
            """, Map.of(
                "users", table(Map.of("id", 1, "name", "parent name")),
                "children", table(child),
                "leaves", table(Map.of("id", 3))));
        var children = (List<?>) result.rows().getFirst().values().get("children");
        var row = (Row) children.getFirst();
        assertNull(row.values().get("id"));
        assertNull(row.values().get("name"));
        assertEquals(1, row.values().get("parent"));
        var leaf = (Row) ((List<?>) row.values().get("leaves")).getFirst();
        assertEquals(3, leaf.values().get("id"));
        assertEquals(1, leaf.values().get("root"));
        assertNull(leaf.values().get("child"));
    }

    @Test void nullableDuplicateFieldsAreStillAmbiguous() {
        var nullable = new LinkedHashMap<String, Object>();
        nullable.put("id", null);
        assertThrows(IllegalArgumentException.class, () -> run("a join b on true { id }",
            Map.of("a", table(nullable), "b", table(Map.of("id", 1)))));
    }

    @Test void validatesBeforeReadingSourcesEvenIfTheyWouldBeEmpty() throws Exception {
        var executor = new QueryExecutor(name -> {
            fail("An invalid query must not read any source");
            return null;
        });
        for (var text : List.of(
            "t { id } where unknown.id = 1",
            "a as x join b as x on true { id }",
            "a join b on c.id = b.id join c on true { id }",
            "a { id, \"id\": 1 }",
            "a { \"children\": b as b { id }, \"leak\": b.id }")) {
            var query = MosaicQLParser.parse(text);
            assertThrows(IllegalArgumentException.class, () -> executor.execute(query), text);
        }
    }

    @Test void rejectsUnsupportedFunctionsBeforeReadingSources() throws Exception {
        var executor = new QueryExecutor(name -> {
            fail("Unsupported functions must be rejected before reading sources");
            return null;
        });
        for (var text : List.of("t { \"total\": unknown_function(amount) }",
            "t { \"children\": c { \"value\": custom(id) } } where false")) {
            var query = MosaicQLParser.parse(text);
            assertThrows(UnsupportedOperationException.class, () -> executor.execute(query));
        }
    }

    @Test void booleanOperationsShortCircuitAndRequireBooleanValues() throws Exception {
        var sources = Map.of("t", table(Map.of()));
        assertEquals(0, run("t { id } where false and 1 / 0 > 0", sources).rows().size());
        assertEquals(1, run("t { id } where true or 1 / 0 > 0", sources).rows().size());
        assertEquals(0, run("t { id } where null", sources).rows().size());
        assertThrows(IllegalArgumentException.class, () -> run("t { id } where 1", sources));
    }

    @Test void resolvesNestedMapPathsAndMissingValues() throws Exception {
        var result = run("""
            users as u { "city": u.profile.address.city, "missing": u.profile.zip }
            """, Map.of("users", table(Map.of("profile", Map.of("address", Map.of("city", "Shanghai"))))));
        assertEquals("Shanghai", result.rows().getFirst().values().get("city"));
        assertNull(result.rows().getFirst().values().get("missing"));
    }

    @Test void emptyTablesAndUnknownSourcesAreDifferent() throws Exception {
        assertEquals(List.of(), run("empty { id }", Map.of("empty", new Table(List.of()))).rows());
        assertThrows(IllegalArgumentException.class, () -> run("unknown { id }", Map.of()));
    }
}
