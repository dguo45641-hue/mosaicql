package com.mosaicql;

import com.mosaicql.plan.DefaultExpressionInterpreter;
import com.mosaicql.runtime.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DeepOptimizationTest {
    private QueryExecutor fallback(SourceProvider data, FunctionRegistry functions) {
        var delegate = new DefaultExpressionInterpreter();
        return new QueryExecutor(data, functions, delegate::evaluate);
    }
    private void equivalent(SourceProvider data, String text) throws Exception {
        var functions = new FunctionRegistry();
        var plan = MosaicQL.compile(text, functions);
        var expected = fallback(data, functions).executeResult(plan);
        var actual = new QueryExecutor(data, functions).executeResult(plan);
        assertEquals(expected, actual, text);
        assertEquals(JsonAdapter.write(expected), JsonAdapter.write(actual), text);
    }
    @Test void countJoinPreservesNullsDuplicatesNumbersAndLeftCardinality() throws Exception {
        var left = new ArrayList<Row>(); var right = new ArrayList<Row>();
        for (Object key : Arrays.asList(1, 1L, new BigDecimal("1.00"), null, "1", true, 99)) {
            var value = new HashMap<String,Object>(); value.put("key", key); left.add(new Row(value));
        }
        for (Object key : Arrays.asList(new BigInteger("1"), 1.0d, null, null, "1", false)) {
            var value = new HashMap<String,Object>(); value.put("key", key); right.add(new Row(value));
        }
        var data = new InMemorySourceProvider(Map.of("a", new Table(left), "b", new Table(right)));
        for (var join : List.of("join", "left join")) for (var on : List.of("a.key = b.key", "a.key = b.key and true", "true and b.key = a.key"))
            equivalent(data, "a " + join + " b on " + on + " { n: count(*), again: count(*) }");
    }
    @Test void countJoinDoesNotDiscardExtraPredicatesOrSideEffects() throws Exception {
        var data = new InMemorySourceProvider(Map.of("a",JsonAdapter.read("[{\"id\":1,\"k\":0},{\"id\":1,\"k\":1}]"),
            "b",JsonAdapter.read("[{\"id\":1,\"k\":1},{\"id\":2,\"k\":0}]")));
        for (var on : List.of("a.id = b.id and a.k = b.k", "a.id = b.id and false", "a.id = b.id or true"))
            equivalent(data,"a join b on " + on + " { n: count(*) }");
        var calls = new AtomicInteger();
        var functions = new FunctionRegistry().register("observe",0,args->{calls.incrementAndGet(); return true;});
        var plan=MosaicQL.compile("a join b on a.id = b.id and observe() { n: count(*) }",functions);
        new QueryExecutor(data,functions).executeResult(plan);
        assertEquals(2,calls.get());
    }
    @Test void countJoinReadsEachProviderOnceAndNeverLoadsRightForEmptyLeft() {
        var reads = new ArrayList<String>();
        SourceProvider empty = name->{reads.add(name); if(name.equals("b"))throw new AssertionError("unexpected right load"); return new Table(List.of());};
        var plan=MosaicQL.compile("a join b on a.key = b.key and true { n: count(*) }");
        assertEquals(0L,new QueryExecutor(empty).executeResult(plan).rows().getFirst().values().get("n"));
        assertEquals(List.of("a"),reads);
        reads.clear();
        // Non-scalar keys use an exact fallback inside the same scan, without reloading providers.
        SourceProvider nonScalar=name->{reads.add(name); return new Table(List.of(new Row(Map.of("key",List.of(1,2)))));};
        assertEquals(1L,new QueryExecutor(nonScalar).executeResult(plan).rows().getFirst().values().get("n"));
        assertEquals(List.of("a","b"),reads);
    }
    @Test void emptyProjectionIsNotAnAggregateAndKeepsJoinCardinality() throws Exception {
        for(String right:List.of("[]","[{\"id\":1},{\"id\":1},{\"id\":2}]")) {
            var data=new InMemorySourceProvider(Map.of("a",JsonAdapter.read("[{\"id\":1},{\"id\":2}]"),"b",JsonAdapter.read(right)));
            for(String join:List.of("join","left join")) for(String shape:List.of("{}","[{}]"))
                equivalent(data,"a "+join+" b on a.id = b.id and true "+shape);
        }
    }
    @Test void onlineAggregatesMatchFallbackForAllKindsEmptyNullAndStableGroupOrder() throws Exception {
        for (String input : List.of("[]", "[{\"g\":null,\"v\":null}]", """
            [{"g":"b","z":1,"v":2},{"g":"a","z":0,"v":null},
             {"g":"b","z":1.00,"v":4.000},{"g":null,"z":1,"v":6},
             {"g":"a","z":0,"v":null},{"g":"b","z":2,"v":8}]
            """)) {
            var data = new InMemorySourceProvider(Map.of("a",JsonAdapter.read(input)));
            for(var tail:List.of("", " order by total desc", " limit 1", " order by total desc limit 1")) {
                equivalent(data,"a [{ g, total: sum(v), n: count(*), nonnull: count(v), mean: avg(v), lo: min(v), hi: max(v) }] group by g"+tail);
                equivalent(data,"a [{ g, z, total: sum(v), n: count(*) }] group by (g, z)"+tail);
            }
            equivalent(data,"a { total: sum(v), n: count(*), nonnull: count(v), mean: avg(v), lo: min(v), hi: max(v) }");
        }
        var strings=new InMemorySourceProvider(Map.of("a",JsonAdapter.read("[{\"s\":\"b\"},{\"s\":\"a\"}]")));
        equivalent(strings,"a { lo: min(s), hi: max(s), n: count(s) }");
    }
    @Test void onlineAggregationDefersErrorsAndHonorsGroupLimit() throws Exception {
        var data=new InMemorySourceProvider(Map.of("a",JsonAdapter.read("[{\"g\":1,\"v\":4},{\"g\":2,\"v\":\"bad\"}]")));
        equivalent(data,"a [{ g, total: sum(v) }] group by g limit 1");
        var badKey=new InMemorySourceProvider(Map.of("a",JsonAdapter.read("[{\"g\":1,\"v\":\"bad\"},{\"g\":[],\"v\":1}]")));
        var plan=MosaicQL.compile("a [{ g, total: sum(v) }] group by g");
        var error=assertThrows(IllegalArgumentException.class,()->new QueryExecutor(badKey).executeResult(plan));
        assertTrue(error.getMessage().startsWith("Group keys must be scalar"));
        var functions=new FunctionRegistry();
        var first=assertThrows(IllegalArgumentException.class,()->fallback(badKey,functions).executeResult(plan));
        assertEquals(first.getMessage(),error.getMessage());
    }
    @Test void aggregateExpressionsAndFunctionsKeepTheirEvaluationOrder() throws Exception {
        var data=new InMemorySourceProvider(Map.of("a",JsonAdapter.read("[{\"g\":1,\"v\":1},{\"g\":2,\"v\":2},{\"g\":1,\"v\":3}]")));
        var seen=new ArrayList<Integer>();
        var functions=new FunctionRegistry().register("observe",1,args->{seen.add(((Number)args.getFirst()).intValue()); return args.getFirst();});
        var plan=MosaicQL.compile("a [{ g, total: sum(observe(v)) }] group by g",functions);
        new QueryExecutor(data,functions).executeResult(plan);
        assertEquals(List.of(1,3,2),seen);
        equivalent(data,"a [{ g, total: sum(v * 2) + count(*) }] group by g");
        equivalent(data,"a [{ g, total: sum(v), n: count(*) }] where v > 1 group by g");
    }
    @Test void optimizedPlansRemainSafeForConcurrentReuse() throws Exception {
        var input=JsonAdapter.read("[{\"g\":1,\"v\":2},{\"g\":1,\"v\":3}]");
        var executor=new QueryExecutor(name->input);
        var plan=MosaicQL.compile("a [{ g, total: sum(v), n: count(*) }] group by g");
        var expected=executor.executeResult(plan);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<QueryResult>>();
            for(int i=0;i<100;i++)futures.add(pool.submit(()->executor.executeResult(plan)));
            for(var future:futures)assertEquals(expected,future.get());
        }
    }
    @Test void optimizedScansStillEnforceCancellationAndRowBudget() throws Exception {
        var input=JsonAdapter.read("[{\"g\":1},{\"g\":2}]");
        var plan=MosaicQL.compile("a [{ g, n: count(*) }] group by g");
        var executor=new QueryExecutor(name->input);
        assertThrows(com.mosaicql.error.MosaicQLException.class,()->executor.executeResult(plan,new ExecutionOptions(0,1,64)));
        Thread.currentThread().interrupt();
        try { assertThrows(com.mosaicql.error.MosaicQLException.class,()->executor.executeResult(plan)); }
        finally { Thread.interrupted(); }
    }
}
