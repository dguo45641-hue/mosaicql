package com.mosaicql;

import com.mosaicql.error.MosaicQLException;
import com.mosaicql.plan.DefaultExpressionInterpreter;
import com.mosaicql.plan.ExpressionInterpreter;
import com.mosaicql.plan.QueryCompiler;
import com.mosaicql.runtime.*;
import com.mosaicql.type.MType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionOptimizationTest {
    private Table table(String json) throws Exception { return JsonAdapter.read(json); }

    private QueryExecutor executor(Table users, FunctionRegistry functions) {
        return new QueryExecutor(new InMemorySourceProvider(Map.of("users", users)), functions);
    }

    @Test void objectResultsStopAtFirstMatchWhileTableApiKeepsAllRows() throws Exception {
        var calls = new AtomicInteger();
        var functions = new FunctionRegistry().register("observe", 1, args -> {
            calls.incrementAndGet();
            return args.getFirst();
        });
        var executor = executor(table("[{\"id\":1},{\"id\":2},{\"id\":3}]"), functions);
        var query = MosaicQL.compile("users { id: observe(id) }", functions);
        assertEquals("{\"id\":1}", JsonAdapter.write(executor.executeResult(query)));
        assertEquals(1, calls.get());
        calls.set(0);
        assertEquals(3, executor.execute(query).rows().size());
        assertEquals(3, calls.get());
    }

    @Test void limitStopsFilteringAndProjectionAfterEnoughMatches() throws Exception {
        var reads = new AtomicInteger();
        var functions = new FunctionRegistry().register("keep", 1, args -> {
            reads.incrementAndGet();
            return ((Number) args.getFirst()).intValue() > 1;
        });
        var executor = executor(table("""
            [{"id":1,"amount":"bad"},{"id":2,"amount":4},{"id":3,"amount":"bad"}]
            """), functions);
        assertEquals("[{\"score\":8}]", JsonAdapter.write(executor.executeResult(
            MosaicQL.compile("users [{ score: amount * 2 }] where keep(id) limit 1", functions))));
        assertEquals(2, reads.get());
        assertThrows(IllegalArgumentException.class, () -> executor.executeResult(
            MosaicQL.compile("users [{ score: amount * 2 }] where keep(id)", functions)));
    }

    @Test void limitZeroValidatesButDoesNotReadSourcesOrEvaluateFunctions() throws Exception {
        var executor = new QueryExecutor(name -> {
            fail("limit 0 must not read sources");
            return null;
        });
        for (var text : List.of("users { id } limit 0", "users [{ id }] order by id limit 0",
                "users { n: count(*) } limit 0", "users [{ id, n: count(*) }] group by id limit 0")) {
            assertTrue(executor.executeResult(MosaicQL.parse(text)).rows().isEmpty());
        }
        assertThrows(UnsupportedOperationException.class, () -> executor.executeResult(
            MosaicQL.parse("users { x: unknown_function(id) } limit 0")));
    }

    @Test void nestedArraysConvertOnlyRowsNeededByTheChildQuery() throws Exception {
        var executor = executor(table("""
            [{"id":1,"children":[{"id":10},42]},
             {"id":2,"children":[{"id":20},null]}]
            """), new FunctionRegistry());
        var query = MosaicQL.compile("""
            users as u [{ id, first: u.children { id }, small: u.children [{ id }] limit 1 }]
            """);
        assertEquals("""
            [{"id":1,"first":{"id":10},"small":[{"id":10}]},
             {"id":2,"first":{"id":20},"small":[{"id":20}]}]
            """.replace("\n", "").replace(" ", ""), JsonAdapter.write(executor.executeResult(query)));
        assertThrows(IllegalArgumentException.class, () -> executor.executeResult(MosaicQL.compile(
            "users as u [{ children: u.children [{ id }] }]")));
    }

    @Test void orderedQueriesEvaluateAllCandidatesBeforeSelectingTheFirstRow() throws Exception {
        var calls = new AtomicInteger();
        var functions = new FunctionRegistry().register("observe", 1, args -> {
            calls.incrementAndGet();
            return args.getFirst();
        });
        var executor = executor(table("[{\"id\":1},{\"id\":3},{\"id\":2}]"), functions);
        assertEquals("{\"id\":3}", JsonAdapter.write(executor.executeResult(MosaicQL.compile(
            "users { id: observe(id) } order by id desc", functions))));
        assertEquals(3, calls.get());
        var invalid = executor(table("[{\"amount\":4},{\"amount\":\"bad\"}]"), functions);
        assertThrows(IllegalArgumentException.class, () -> invalid.executeResult(MosaicQL.compile(
            "users { score: amount * 2 } order by score")));
    }

    @Test void groupedAndGlobalAggregatesStillConsumeAllInputRows() throws Exception {
        var visits = new AtomicInteger();
        var functions = new FunctionRegistry().register("keep", 1, args -> {
            visits.incrementAndGet();
            return true;
        });
        var executor = executor(table("""
            [{"kind":"a","amount":2},{"kind":"b","amount":9},{"kind":"a","amount":3}]
            """), functions);
        assertEquals("{\"kind\":\"a\",\"total\":5}", JsonAdapter.write(executor.executeResult(MosaicQL.compile(
            "users { kind, total: sum(amount) } where keep(amount) group by kind", functions))));
        assertEquals(3, visits.get());
        assertEquals("[{\"total\":14,\"n\":3}]", JsonAdapter.write(executor.executeResult(MosaicQL.compile(
            "users [{ total: sum(amount), n: count(*) }] limit 1", functions))));
        assertEquals("{\"kind\":\"b\",\"total\":9}", JsonAdapter.write(executor.executeResult(MosaicQL.compile(
            "users { kind, total: sum(amount) } group by kind order by total desc"))));
        assertEquals("{\"n\":0}", JsonAdapter.write(executor(new Table(List.of()), functions)
            .executeResult(MosaicQL.compile("users { n: count(*) }"))));
    }

    @Test void nestedPlansAreReusedInsideFunctionsBranchesAndAggregates() throws Exception {
        var inferenceCalls = new AtomicInteger();
        var functions = new FunctionRegistry().register("probe",
            new FunctionSignature(List.of(MType.NUMBER), 1, 1, MType.NUMBER, false),
            types -> { inferenceCalls.incrementAndGet(); return MType.NUMBER; }, args -> args.getFirst());
        var executor = executor(table("""
            [{"id":1,"children":[{"amount":2,"details":[{"amount":7}]}]},
             {"id":2,"children":[{"amount":3,"details":[{"amount":8}]}]}]
            """), functions);
        var compiled = MosaicQL.compile("""
            users as u [{
              id,
              children: if true then u.children as c [{
                amount: probe(c.amount), owner: u.id,
                details: c.details as d [{ amount: probe(d.amount), owner: u.id }]
              }] else null fi,
              first: coalesce((u.absent { amount }), (u.children { amount: probe(amount) }))
            }]
            """, functions);
        int afterCompile = inferenceCalls.get();
        assertTrue(afterCompile > 0);
        String expected = """
            [{"id":1,"children":[{"amount":2,"owner":1,"details":[{"amount":7,"owner":1}]}],"first":{"amount":2}},
             {"id":2,"children":[{"amount":3,"owner":2,"details":[{"amount":8,"owner":2}]}],"first":{"amount":3}}]
            """.replace("\n", "").replace(" ", "");
        assertEquals(expected, JsonAdapter.write(executor.executeResult(compiled)));
        assertEquals(expected, JsonAdapter.write(executor.executeResult(compiled)));
        assertEquals(afterCompile, inferenceCalls.get(), "Execution must not infer types again");

        var aggregate = MosaicQL.compile("""
            users as u { n: sum(length((u.children [{ amount: probe(amount) }]))) }
            """, functions);
        afterCompile = inferenceCalls.get();
        assertEquals("{\"n\":2}", JsonAdapter.write(executor.executeResult(aggregate)));
        assertEquals(afterCompile, inferenceCalls.get());
        try (var pool = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> JsonAdapter.write(executor.executeResult(compiled))));
            for (var future : futures) assertEquals(expected, future.get());
        }
        assertEquals(afterCompile, inferenceCalls.get());
    }

    @Test void aggregateArgumentsUseTheCompiledExpressionInterpreter() throws Exception {
        var executor = executor(table("""
            [{"amount":2,"active":true},{"amount":9,"active":false},{"amount":3,"active":true}]
            """), new FunctionRegistry());
        assertEquals("{\"total\":5}", JsonAdapter.write(executor.executeResult(MosaicQL.compile(
            "users { total: sum(if active then amount else 0 fi) }"))));
    }

    @Test void subqueriesInWhereAndJoinReusePlansAndReadTheCurrentParent() throws Exception {
        var inferenceCalls = new AtomicInteger();
        var functions = new FunctionRegistry().register("probe",
            new FunctionSignature(List.of(MType.NUMBER), 1, 1, MType.NUMBER, false),
            types -> { inferenceCalls.incrementAndGet(); return MType.NUMBER; }, args -> args.getFirst());
        var sources = new InMemorySourceProvider(Map.of(
            "users", table("[{\"id\":1},{\"id\":2}]"),
            "orders", table("[{\"owner\":2}]"),
            "markers", table("[{\"owner\":2}]")));
        var executor = new QueryExecutor(sources, functions);
        var compiled = MosaicQL.compile("""
            users as u join markers as m on length((orders as o [{ owner: probe(o.owner) }]
              where o.owner = u.id)) > 0 [{ id: u.id }]
            where length((orders as o [{ owner: probe(o.owner) }] where o.owner = u.id)) > 0
            """, functions);
        int afterCompile = inferenceCalls.get();
        assertEquals("[{\"id\":2}]", JsonAdapter.write(executor.executeResult(compiled)));
        assertEquals(afterCompile, inferenceCalls.get());
    }

    @Test void hashJoinMatchesFallbackForDuplicatesNullsAndMixedNumericKeys() throws Exception {
        var data = new InMemorySourceProvider(Map.of(
            "a", table("""
                [{"id":1,"label":"x"},{"id":1.00,"label":"y"},{"id":null,"label":"z"},{"id":"1","label":"s"}]
                """),
            "b", table("""
                [{"owner":1.0,"tag":"first"},{"owner":1,"tag":"second"},{"owner":null,"tag":"nil"},{"owner":"1","tag":"text"}]
                """)));
        var optimized = new QueryExecutor(data);
        var delegate = new DefaultExpressionInterpreter();
        ExpressionInterpreter fallbackInterpreter = delegate::evaluate;
        var fallback = new QueryExecutor(data, new FunctionRegistry(), fallbackInterpreter);
        for (var on : List.of("a.id = b.owner", "b.owner = a.id", "a.id = owner", "a.id = b.owner and true")) {
            var query = MosaicQL.compile("a join b on " + on + " [{ label: a.label, tag: b.tag }]");
            assertEquals(fallback.execute(query), optimized.execute(query));
        }
        assertEquals("""
            [{"label":"x","tag":"first"},{"label":"x","tag":"second"},
             {"label":"y","tag":"first"},{"label":"y","tag":"second"},
             {"label":"z","tag":"nil"},{"label":"s","tag":"text"}]
            """.replace("\n", "").replace(" ", ""), JsonAdapter.write(optimized.executeResult(MosaicQL.compile(
            "a join b on a.id = b.owner [{ label: a.label, tag: b.tag }]"))));
    }

    @Test void hashJoinAvoidsComparingEveryPair() throws Exception {
        var reads = new AtomicInteger();
        var left = new ArrayList<Row>();
        var right = new ArrayList<Row>();
        for (int i = 0; i < 200; i++) {
            left.add(new Row(Map.of("key", countedKey(i, reads))));
            right.add(new Row(Map.of("key", countedKey(i, reads))));
        }
        var executor = new QueryExecutor(new InMemorySourceProvider(Map.of("a", new Table(left), "b", new Table(right))));
        var result = executor.execute(MosaicQL.compile("a join b on a.key.id = b.key.id [{ id: a.key.id }]"));
        assertEquals(200, result.rows().size());
        assertTrue(reads.get() < 2_000, "Field reads should grow with input and matches, not their product: " + reads);

        reads.set(0);
        var delegate = new DefaultExpressionInterpreter();
        var fallback = new QueryExecutor(new InMemorySourceProvider(Map.of("a", new Table(left), "b", new Table(right))),
            new FunctionRegistry(), delegate::evaluate);
        assertEquals(result, fallback.execute(MosaicQL.compile("a join b on a.key.id = b.key.id [{ id: a.key.id }]")));
        assertTrue(reads.get() >= 80_000);
    }

    private Map<String, Object> countedKey(int id, AtomicInteger reads) {
        return new LinkedHashMap<>(Map.<String, Object>of("id", id)) {
            @Override public Object get(Object key) { reads.incrementAndGet(); return super.get(key); }
        };
    }

    @Test void joinsKeepCustomInterpreterSemanticsAndComplexEqualityFallback() throws Exception {
        var sources = new InMemorySourceProvider(Map.of(
            "a", new Table(List.of(new Row(Map.of("id", 1)))),
            "b", new Table(List.of(new Row(Map.of("id", 2))))));
        var delegate = new DefaultExpressionInterpreter();
        ExpressionInterpreter interpreter = (expression, context) ->
            expression.ast() instanceof com.mosaicql.ast.Ast.Binary ? true : delegate.evaluate(expression, context);
        assertEquals(1, new QueryExecutor(sources, new FunctionRegistry(), interpreter)
            .execute(MosaicQL.compile("a join b on a.id = b.id [{ id: a.id }]" )).rows().size());
        var objects = new InMemorySourceProvider(Map.of(
            "a", table("[{\"key\":{\"x\":1}},{\"key\":[2]}]"),
            "b", table("[{\"key\":{\"x\":1}},{\"key\":[2]}]")));
        assertEquals(2, new QueryExecutor(objects).execute(MosaicQL.compile(
            "a join b on a.key = b.key [{ key: a.key }]" )).rows().size());
    }

    @Test void customInterpretersKeepTheirProjectionAndAggregateHooks() throws Exception {
        var delegate = new DefaultExpressionInterpreter();
        var projections = new AtomicInteger();
        var interpreter = new ExpressionInterpreter() {
            public Object evaluate(com.mosaicql.plan.TypedExpression expression, com.mosaicql.plan.ExpressionContext context) {
                return delegate.evaluate(expression, context);
            }
            public Object evaluateAggregate(com.mosaicql.plan.TypedExpression expression,
                                            com.mosaicql.plan.AggregateExpressionContext context) {
                projections.incrementAndGet();
                if (expression.ast() instanceof com.mosaicql.ast.Ast.Function function) {
                    return context.aggregate(function, List.of());
                }
                return delegate.evaluate(expression, context);
            }
        };
        var sources = new InMemorySourceProvider(Map.of("users", table("[{\"id\":1},{\"id\":2}]")));
        var executor = new QueryExecutor(sources, new FunctionRegistry(), interpreter);
        assertEquals(2, executor.executeResult(MosaicQL.compile("users [{ id }]")).rows().size());
        assertEquals(2, projections.get());
        assertEquals("{\"total\":3}", JsonAdapter.write(executor.executeResult(MosaicQL.compile("users { total: sum(id) }"))));
        assertEquals(3, projections.get());
    }

    @Test void hashJoinPreservesJavaNumberEqualityPrecisionAndMissingKeys() throws Exception {
        var sources = new InMemorySourceProvider(Map.of(
            "a", new Table(List.of(new Row(Map.of("id", 1)), new Row(Map.of("id", 1L)),
                new Row(Map.of("id", new BigDecimal("1.00"))), new Row(Map.of("id", 9007199254740993L)),
                new Row(Map.of("id", "1")), new Row(Map.of()))),
            "b", new Table(List.of(new Row(Map.of("id", new BigDecimal("1.0"))),
                new Row(Map.of("id", 9007199254740993L)), new Row(Map.of("id", 9007199254740992L)),
                new Row(Map.of("id", "1")), new Row(Map.of())))));
        var result = new QueryExecutor(sources).execute(MosaicQL.compile("a join b on a.id = b.id [{ id: a.id }]"));
        assertEquals(java.util.Arrays.asList(1, 1L, new BigDecimal("1.00"), 9007199254740993L, "1", null),
            result.rows().stream().map(row -> row.values().get("id")).toList());
    }

    @Test void hashJoinFallsBackForNonFiniteNumbersAndChainsInInputOrder() throws Exception {
        var invalid = new InMemorySourceProvider(Map.of(
            "a", new Table(List.of(new Row(Map.of("id", 1)))),
            "b", new Table(List.of(new Row(Map.of("id", Double.NaN))))));
        assertThrows(NumberFormatException.class, () -> new QueryExecutor(invalid).execute(
            MosaicQL.compile("a join b on a.id = b.id [{ id: a.id }]")));
        var sources = new InMemorySourceProvider(Map.of(
            "a", table("[{\"id\":2},{\"id\":1}]"),
            "b", table("[{\"owner\":1,\"key\":10},{\"owner\":2,\"key\":20},{\"owner\":2,\"key\":21}]"),
            "c", table("[{\"id\":20,\"name\":\"x\"},{\"id\":21,\"name\":\"y\"},{\"id\":10,\"name\":\"z\"}]")));
        assertEquals("[{\"name\":\"x\"},{\"name\":\"y\"},{\"name\":\"z\"}]", JsonAdapter.write(new QueryExecutor(sources)
            .executeResult(MosaicQL.compile("a join b on a.id = b.owner join c on b.key = c.id [{ name: c.name }]"))));
    }

    @Test void contextualAndChainedJoinsStopWhenTheResultIsComplete() throws Exception {
        var joinCalls = new AtomicInteger();
        var functions = new FunctionRegistry().register("match", 1, args -> {
            joinCalls.incrementAndGet(); return true;
        });
        var executor = executor(table("""
            [{"id":1,"children":[{"id":10,"leaves":[{"id":100},{"id":101}]}]},
             {"id":2,"children":[{"id":20,"leaves":[{"id":200}]}]}]
            """), functions);
        assertEquals("[{\"id\":100}]", JsonAdapter.write(executor.executeResult(MosaicQL.compile("""
            users as u join u.children as c on true join c.leaves as l on match(l.id)
            [{ id: l.id }] limit 1
            """, functions))));
        assertEquals(1, joinCalls.get());
    }

    @Test void rowLimitsCountProjectedRowsAcrossNestedQueriesAndSortCandidates() throws Exception {
        var executor = executor(table("[{\"id\":1},{\"id\":2},{\"id\":3}]"), new FunctionRegistry());
        var options = new ExecutionOptions(0, 1, 64);
        assertEquals(1, executor.executeResult(MosaicQL.compile("users [{ id }] limit 1"), options).rows().size());
        assertEquals(1, executor.executeResult(MosaicQL.compile("users { id }"), options).rows().size());
        var error = assertThrows(MosaicQLException.class, () -> executor.executeResult(
            MosaicQL.compile("users [{ id }] order by id limit 1"), options));
        assertEquals("MAX_ROWS", error.code());
        var nested = executor(table("[{\"children\":[{\"id\":1}]}]"), new FunctionRegistry());
        error = assertThrows(MosaicQLException.class, () -> nested.executeResult(
            MosaicQL.compile("users as u { child: u.children { id } }"), options));
        assertEquals("MAX_ROWS", error.code());
    }

    @Test void reentrantExecutionRestoresTheOuterResourceLimits() throws Exception {
        var holder = new QueryExecutor[1];
        var inner = MosaicQL.compile("users { id }");
        var functions = new FunctionRegistry().register("reenter", 1, args -> {
            holder[0].executeResult(inner);
            return args.getFirst();
        });
        holder[0] = executor(table("[{\"id\":1},{\"id\":2},{\"id\":3}]"), functions);
        var outer = MosaicQL.compile("users [{ id: reenter(id) }]", functions);
        var error = assertThrows(MosaicQLException.class, () -> holder[0].executeResult(outer,
            new ExecutionOptions(0, 2, 64)));
        assertEquals("MAX_ROWS", error.code());
        assertEquals(3, holder[0].executeResult(outer).rows().size());
    }

    @Test void cancellationAndDepthChecksApplyToOptimizedPathsAndStateIsCleared() throws Exception {
        var executor = executor(table("[{\"id\":1,\"children\":[{\"children\":[{\"id\":3}]}]}]"), new FunctionRegistry());
        var compiled = MosaicQL.compile("users as u { child: u.children as c { child: c.children { id } } }");
        var error = assertThrows(MosaicQLException.class, () -> executor.executeResult(compiled,
            new ExecutionOptions(0, 0, 1)));
        assertEquals("MAX_NESTING_DEPTH", error.code());
        assertDoesNotThrow(() -> executor.executeResult(compiled));
        Thread.currentThread().interrupt();
        try {
            error = assertThrows(MosaicQLException.class, () -> executor.executeResult(MosaicQL.compile("users { id } limit 0")));
            assertEquals("EXECUTION_CANCELLED", error.code());
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, executor.executeResult(MosaicQL.compile("users { id }")).rows().size());
    }

    @Test void timeoutAndCancellationAreCheckedWhileScanningWithoutMatches() throws Exception {
        var functions = new FunctionRegistry().register("expire", 0, args -> {
            java.util.concurrent.locks.LockSupport.parkNanos(20_000_000L);
            return false;
        });
        var executor = executor(table("[{\"id\":1},{\"id\":2}]"), functions);
        var error = assertThrows(MosaicQLException.class, () -> executor.executeResult(
            MosaicQL.compile("users [{ id }] where expire()", functions), new ExecutionOptions(5, 0, 64)));
        assertEquals("EXECUTION_TIMEOUT", error.code());
        assertEquals(2, executor.executeResult(MosaicQL.compile("users [{ id }]", functions)).rows().size());

        functions.register("cancel", 0, args -> { Thread.currentThread().interrupt(); return false; });
        try {
            error = assertThrows(MosaicQLException.class, () -> executor.executeResult(
                MosaicQL.compile("users [{ id }] where cancel()", functions)));
            assertEquals("EXECUTION_CANCELLED", error.code());
        } finally {
            Thread.interrupted();
        }
    }

    @Test void skippedBranchesStayLazyAndNestedAliasesCanShadowParentAliases() throws Exception {
        var functions = new FunctionRegistry().register("broken", 0, args -> { throw new IllegalStateException("must be skipped"); });
        var executor = executor(table("[{\"id\":1,\"children\":[{\"id\":2}]}]"), functions);
        assertEquals("{\"child\":{\"id\":2},\"safe\":1}", JsonAdapter.write(executor.executeResult(MosaicQL.compile("""
            users as u {
              child: u.children as u { id: u.id },
              safe: if true then 1 else u.children { x: broken() } fi
            }
            """, functions))));
    }
}
