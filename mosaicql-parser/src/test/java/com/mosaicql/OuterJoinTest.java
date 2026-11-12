package com.mosaicql;

import com.mosaicql.ast.Ast;
import com.mosaicql.plan.DefaultExpressionInterpreter;
import com.mosaicql.runtime.FunctionRegistry;
import com.mosaicql.runtime.InMemorySourceProvider;
import com.mosaicql.runtime.JsonAdapter;
import com.mosaicql.runtime.QueryExecutor;
import com.mosaicql.runtime.Row;
import com.mosaicql.runtime.Table;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OuterJoinTest {
    private InMemorySourceProvider sources(String a, String b) throws Exception {
        return new InMemorySourceProvider(Map.of("a", JsonAdapter.read(a), "b", JsonAdapter.read(b)));
    }

    private String run(String query, String a, String b) throws Exception {
        return JsonAdapter.write(new QueryExecutor(sources(a, b)).executeResult(MosaicQL.compile(query)));
    }

    @Test void parsesExplicitJoinTypesAndKeepsTheExistingAstConstructor() throws Exception {
        for (var syntax : List.of("join", "inner join", "left join", "left outer join", "right join", "right outer join")) {
            var query = MosaicQLParser.parse("a " + syntax + " b on true [{ * }]");
            var expected = syntax.startsWith("left") ? Ast.JoinType.LEFT
                : syntax.startsWith("right") ? Ast.JoinType.RIGHT : Ast.JoinType.INNER;
            assertEquals(expected, query.joins().getFirst().type());
        }
        assertEquals(Ast.JoinType.INNER, new Ast.Join(new Ast.Source("b", null), new Ast.Literal(true)).type());
        assertThrows(ParseException.class, () -> MosaicQLParser.parse("a inner outer join b on true [{ * }]"));
        assertThrows(ParseException.class, () -> MosaicQLParser.parse("a outer join b on true [{ * }]"));
    }

    @Test void leftJoinKeepsUnmatchedRowsDeepNullsAndDuplicateMatchesInOrder() throws Exception {
        assertEquals("""
            [{"id":2,"tag":null,"deep":null},{"id":1,"tag":"x","deep":9},{"id":1,"tag":"y","deep":null}]
            """.strip(), run("a left join b on a.id = b.owner [{ id: a.id, tag: b.tag, deep: b.profile.id }]",
            "[{\"id\":2},{\"id\":1}]", "[{\"owner\":1,\"tag\":\"x\",\"profile\":{\"id\":9}},{\"owner\":1,\"tag\":\"y\"}]"));
    }

    @Test void rightJoinKeepsDuplicatesAndAppendsUnmatchedRightRowsInInputOrder() throws Exception {
        assertEquals("[{\"label\":\"x\",\"owner\":1},{\"label\":\"y\",\"owner\":1},{\"label\":null,\"owner\":2},{\"label\":null,\"owner\":3}]",
            run("a right outer join b on b.owner = a.id [{ label: a.label, owner: b.owner }]",
                "[{\"id\":1,\"label\":\"x\"},{\"id\":1,\"label\":\"y\"}]", "[{\"owner\":2},{\"owner\":1},{\"owner\":3}]"));
    }

    @Test void emptyAndFalseInputsPreserveOnlyTheSpecifiedSide() throws Exception {
        for (var type : List.of("left", "right")) {
            String query = "a " + type + " join b on false [{ id: a.id, owner: b.owner }]";
            String expected = type.equals("left") ? "[{\"id\":1,\"owner\":null}]" : "[{\"id\":null,\"owner\":2}]";
            assertEquals(expected, run(query, "[{\"id\":1}]", "[{\"owner\":2}]"));
            assertEquals(type.equals("left") ? expected : "[]", run(query, "[{\"id\":1}]", "[]"));
            assertEquals(type.equals("right") ? expected : "[]", run(query, "[]", "[{\"owner\":2}]"));
            assertEquals("[]", run(query, "[]", "[]"));
        }
    }

    @Test void whereFiltersAfterOnWithoutInventingAnUnmatchedRow() throws Exception {
        String a = "[{\"id\":1},{\"id\":2}]";
        String b = "[{\"owner\":1,\"enabled\":false}]";
        assertEquals("[{\"id\":1},{\"id\":2}]", run(
            "a left join b on a.id = b.owner and b.enabled [{ id: a.id }] where b.owner = null", a, b));
        assertEquals("[{\"id\":2}]", run(
            "a left join b on a.id = b.owner [{ id: a.id }] where b.owner = null", a, b));
        assertEquals("[]", run("a left join b on a.id = b.owner [{ id: a.id }] where b.enabled", a, b));
        assertEquals("[]", run("a right join b on a.id = b.owner [{ id: a.id }] where a.id = null", a, b));
    }

    @Test void optimizedAndFallbackAgreeForNumericStringNullAndMissingKeys() throws Exception {
        var sources = sources("[{\"id\":1},{\"id\":1.0},{\"id\":null},{},{\"id\":\"1\"},{\"id\":3}]",
            "[{\"owner\":1},{\"owner\":null},{},{\"owner\":\"1\"},{\"owner\":2}]");
        var optimized = new QueryExecutor(sources);
        var delegate = new DefaultExpressionInterpreter();
        var fallback = new QueryExecutor(sources, new FunctionRegistry(), delegate::evaluate);
        for (var type : List.of("left", "right")) {
            for (var on : List.of("a.id = b.owner", "b.owner = a.id", "a.id = b.owner and true", "a.id = owner")) {
                var query = MosaicQL.compile("a " + type + " join b on " + on + " [{ id: a.id, owner: b.owner }]");
                assertEquals(fallback.execute(query), optimized.execute(query));
                assertEquals(8, optimized.execute(query).rows().size());
            }
        }
    }

    @Test void nonEqualityPredicatesPreserveUnmatchedRows() throws Exception {
        assertEquals("[{\"id\":1,\"owner\":2},{\"id\":3,\"owner\":null}]", run(
            "a left join b on a.id < b.owner [{ id: a.id, owner: b.owner }]", "[{\"id\":1},{\"id\":3}]", "[{\"owner\":2}]"));
        assertEquals("[{\"id\":1,\"owner\":2},{\"id\":null,\"owner\":0}]", run(
            "a right join b on a.id < b.owner [{ id: a.id, owner: b.owner }]", "[{\"id\":1}]", "[{\"owner\":2},{\"owner\":0}]"));
    }

    @Test void contextualLeftJoinHandlesObjectsArraysEmptyPathsAndNullExtendedParents() throws Exception {
        var sources = new InMemorySourceProvider(Map.of("users", JsonAdapter.read("""
            [{"id":1,"orders":[{"id":10,"lines":[{"id":100}]}]},
             {"id":2,"orders":[]},{"id":3,"orders":null},{"id":4,"orders":{"id":40}}]
            """)));
        var query = MosaicQL.compile("""
            users as u left join u.orders as o on true left join o.lines as l on true
            [{ id: u.id, order_id: o.id, line_id: l.id, lines: o.lines [{ id }] }]
            """);
        assertEquals("""
            [{"id":1,"order_id":10,"line_id":100,"lines":[{"id":100}]},
             {"id":2,"order_id":null,"line_id":null,"lines":[]},
             {"id":3,"order_id":null,"line_id":null,"lines":[]},
             {"id":4,"order_id":40,"line_id":null,"lines":[]}]
            """.replace("\n", "").replace(" ", ""), JsonAdapter.write(new QueryExecutor(sources).executeResult(query)));
    }

    @Test void nestedOuterQueriesReuseParentReferencesAndLocalAliasShadowing() throws Exception {
        var sources = new InMemorySourceProvider(Map.of("parents", JsonAdapter.read("[{\"id\":7},{\"id\":8}]"),
            "a", JsonAdapter.read("[]"), "b", JsonAdapter.read("[{\"owner\":1}]")));
        var query = MosaicQL.compile("""
            parents as p [{ id: p.id, items: a as p right join b on p.id = b.owner
              [{ id: p.id, owner: b.owner }], related: a right join b on a.id = b.owner
              { parent: p.id, id: a.id } }]
            """);
        assertEquals("[{\"id\":7,\"items\":[{\"id\":null,\"owner\":1}],\"related\":{\"parent\":7,\"id\":null}},"
            + "{\"id\":8,\"items\":[{\"id\":null,\"owner\":1}],\"related\":{\"parent\":8,\"id\":null}}]",
            JsonAdapter.write(new QueryExecutor(sources).executeResult(query)));
    }

    @Test void rightJoinPreservesRowsWhenAnEarlierJoinRemovedAllLeftRows() throws Exception {
        var sources = new InMemorySourceProvider(Map.of("a", JsonAdapter.read("[{\"id\":1}]"),
            "b", JsonAdapter.read("[]"), "c", JsonAdapter.read("[{\"id\":3}]"), "d", JsonAdapter.read("[{\"id\":4}]")));
        var executor = new QueryExecutor(sources);
        assertEquals("[{\"a_id\":null,\"b_id\":null,\"c_id\":3}]", JsonAdapter.write(executor.executeResult(MosaicQL.compile(
            "a join b on true right join c on false [{ a_id: a.id, b_id: b.id, c_id: c.id }]"))));
        assertEquals("[{\"a_id\":null,\"b_id\":null,\"c_id\":null,\"d_id\":4}]", JsonAdapter.write(executor.executeResult(MosaicQL.compile(
            "a left join b on true right join c on false right join d on false [{ a_id: a.id, b_id: b.id, c_id: c.id, d_id: d.id }]"))));
    }

    @Test void earlierRightJoinUnmatchedRowsParticipateInLaterJoinsBeforeTheyComplete() throws Exception {
        var sources = new InMemorySourceProvider(Map.of("a", JsonAdapter.read("[]"),
            "b", JsonAdapter.read("[{\"key\":10}]"), "c", JsonAdapter.read("[{\"key\":10},{\"key\":20}]"),
            "d", JsonAdapter.read("[{\"key\":10}]")));
        assertEquals("[{\"a_id\":null,\"b_key\":10,\"c_key\":10,\"d_key\":10},{\"a_id\":null,\"b_key\":null,\"c_key\":20,\"d_key\":null}]",
            JsonAdapter.write(new QueryExecutor(sources).executeResult(MosaicQL.compile("""
                a right join b on true right join c on b.key = c.key left join d on c.key = d.key
                [{ a_id: a.id, b_key: b.key, c_key: c.key, d_key: d.key }]
                """))));
    }

    @Test void followingJoinsCanUseNullExtendedRowsAndRemoveThem() throws Exception {
        var sources = new InMemorySourceProvider(Map.of("a", JsonAdapter.read("[{\"id\":1}]"),
            "b", JsonAdapter.read("[]"), "c", JsonAdapter.read("[{\"id\":null,\"label\":\"nil\"}]")));
        var executor = new QueryExecutor(sources);
        assertEquals("[{\"label\":\"nil\"}]", JsonAdapter.write(executor.executeResult(MosaicQL.compile(
            "a left join b on true join c on b.id = c.id [{ label: c.label }]"))));
        assertEquals("[]", JsonAdapter.write(executor.executeResult(MosaicQL.compile(
            "a left join b on true join c on b.id != c.id [{ label: c.label }]"))));
    }

    @Test void objectAndExplicitLimitStopLeftJoinOnEvaluationEarly() throws Exception {
        var calls = new AtomicInteger();
        var functions = new FunctionRegistry().register("match", 1, arguments -> { calls.incrementAndGet(); return false; });
        var executor = new QueryExecutor(sources("[{\"id\":1},{\"id\":2}]", "[{\"owner\":1}]"), functions);
        for (var projection : List.of("{ id: a.id, owner: b.owner }", "[{ id: a.id, owner: b.owner }] limit 1")) {
            calls.set(0);
            assertEquals(1, executor.executeResult(MosaicQL.compile(
                "a left join b on match(a.id) " + projection, functions)).rows().size());
            assertEquals(1, calls.get());
        }
    }

    @Test void rightJoinStopsMatchingAfterTheFirstRequiredResult() throws Exception {
        var calls = new AtomicInteger();
        var functions = new FunctionRegistry().register("match", 1, arguments -> { calls.incrementAndGet(); return true; });
        var executor = new QueryExecutor(sources("[{\"id\":1}]", "[{\"owner\":1},{\"owner\":2}]"), functions);
        assertEquals("{\"owner\":1}", JsonAdapter.write(executor.executeResult(MosaicQL.compile(
            "a right join b on match(b.owner) { owner: b.owner }", functions))));
        assertEquals(1, calls.get());
        var unread = new QueryExecutor(name -> { fail("limit 0 must not load sources"); return null; });
        assertEquals(0, unread.executeResult(MosaicQL.compile("a right join b on true [{ * }] limit 0")).rows().size());
    }

    @Test void rightJoinChecksAllLeftInputsBeforeEmittingAnUnmatchedRow() throws Exception {
        var calls = new AtomicInteger();
        var functions = new FunctionRegistry().register("match", 1, arguments -> { calls.incrementAndGet(); return false; });
        var executor = new QueryExecutor(sources("[{\"id\":1},{\"id\":2}]", "[{\"owner\":1},{\"owner\":2}]"), functions);
        assertEquals("{\"id\":null,\"owner\":1}", JsonAdapter.write(executor.executeResult(MosaicQL.compile(
            "a right join b on match(a.id) { id: a.id, owner: b.owner }", functions))));
        assertEquals(4, calls.get());
    }

    @Test void rightMatchIsRecordedBeforeLaterJoinsOrWhereRemoveTheRow() throws Exception {
        var sources = new InMemorySourceProvider(Map.of("a", JsonAdapter.read("[{\"id\":1}]"),
            "b", JsonAdapter.read("[{\"owner\":1}]"), "c", JsonAdapter.read("[{\"id\":1}]")));
        var executor = new QueryExecutor(sources);
        var query = MosaicQL.compile("a right join b on a.id = b.owner join c on a.id = null [{ id: a.id }]");
        assertEquals(0, executor.executeResult(query).rows().size());
        assertEquals(0, executor.executeResult(query).rows().size());
    }

    @Test void rightJoinTracksDuplicateOccurrencesSeparatelyEvenForTheSameRowInstance() throws Exception {
        var right = new Row(Map.of("owner", 1));
        var calls = new AtomicInteger();
        var functions = new FunctionRegistry().register("first", 0, arguments -> calls.incrementAndGet() == 1);
        var executor = new QueryExecutor(new InMemorySourceProvider(Map.of("a", JsonAdapter.read("[{\"id\":1}]"),
            "b", new Table(List.of(right, right)))), functions);
        assertEquals("[{\"id\":1,\"owner\":1},{\"id\":null,\"owner\":1}]", JsonAdapter.write(executor.executeResult(MosaicQL.compile(
            "a right join b on first() [{ id: a.id, owner: b.owner }]", functions))));
    }

    @Test void joinTypeWordsRemainAvailableAsIdentifiers() throws Exception {
        var functions = new FunctionRegistry().register("left", 1, arguments -> arguments.getFirst());
        var sources = new InMemorySourceProvider(Map.of("left", JsonAdapter.read("[{\"left\":1,\"right\":2,\"inner\":3,\"outer\":4}]"),
            "right", JsonAdapter.read("[{\"id\":1}]")));
        var executor = new QueryExecutor(sources, functions);
        assertEquals("[{\"left\":1,\"right\":2,\"inner\":3,\"outer\":4}]", JsonAdapter.write(executor.executeResult(MosaicQL.compile(
            "left as outer left join right as inner on outer.left = inner.id [{ left: left(outer.left), right, inner: outer.inner, outer: outer.outer }]",
            functions))));
        assertEquals("[{\"left\":1}]", JsonAdapter.write(executor.executeResult(MosaicQL.compile("left [{ left }]"))));
    }

    @Test void groupingAndOrderingIncludeNullExtendedRows() throws Exception {
        assertEquals("[{\"id\":1,\"rows\":1,\"matches\":0},{\"id\":2,\"rows\":2,\"matches\":2}]", run(
            "a left join b on a.id = b.owner [{ id: a.id, rows: count(*), matches: count(b.owner) }] group by a.id order by id",
            "[{\"id\":2},{\"id\":1}]", "[{\"owner\":2},{\"owner\":2}]"));
        assertEquals("[{\"id\":null,\"owner\":2}]", run(
            "a right join b on a.id = b.owner [{ id: a.id, owner: b.owner }] order by owner desc limit 1",
            "[{\"id\":1}]", "[{\"owner\":1},{\"owner\":2}]"));
    }

    @Test void wildcardAndBareFieldsSkipTheAbsentRowInTheDynamicModel() throws Exception {
        assertEquals("[{\"id\":1}]", run("a left join b on false [{ * }]", "[{\"id\":1}]", "[{\"tag\":2}]"));
        assertEquals("[{\"tag\":2}]", run("a right join b on false [{ * }]", "[{\"id\":1}]", "[{\"tag\":2}]"));
        assertEquals("[{\"id\":1}]", run("a left join b on false [{ id }]", "[{\"id\":1}]", "[{\"id\":2}]"));
        assertThrows(IllegalArgumentException.class, () -> run("a left join b on true [{ * }]", "[{\"id\":1}]", "[{\"id\":2}]"));
    }

    @Test void sourcesAreLoadedOnceAndCustomInterpretersControlOn() throws Exception {
        var data = sources("[{\"id\":1},{\"id\":2}]", "[{\"owner\":3},{\"owner\":4}]");
        for (var type : List.of("left", "right")) {
            var reads = new ArrayList<String>();
            var delegate = new DefaultExpressionInterpreter();
            var executor = new QueryExecutor(name -> { reads.add(name); return data.get(name); }, new FunctionRegistry(),
                (expression, context) -> expression.ast() instanceof Ast.Binary ? true : delegate.evaluate(expression, context));
            assertEquals(4, executor.executeResult(MosaicQL.compile(
                "a " + type + " join b on a.id = b.owner [{ id: a.id, owner: b.owner }]")).rows().size());
            assertEquals(List.of("a", "b"), reads);
        }
    }

    @Test void rejectsContextualRightSourcesBeforeAnyDataIsRead() throws Exception {
        var executor = new QueryExecutor(name -> { fail("Invalid queries must be rejected before source access"); return null; });
        var query = MosaicQLParser.parse("a right join a.items as b on true [{ * }]");
        var error = assertThrows(IllegalArgumentException.class, () -> executor.executeResult(query));
        assertTrue(error.getMessage().contains("RIGHT JOIN source must be independent"));
        var nested = MosaicQLParser.parse("a { nested: a.items as i right join i.children as c on true [{ * }] }");
        assertThrows(IllegalArgumentException.class, () -> executor.executeResult(nested));
    }
}
