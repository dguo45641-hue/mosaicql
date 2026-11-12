package com.mosaicql;

import com.mosaicql.plan.DefaultExpressionInterpreter;
import com.mosaicql.runtime.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class JoinAndMemoryOptimizationTest {
    private final DefaultExpressionInterpreter delegate = new DefaultExpressionInterpreter();

    private SourceProvider sources(String a, String b) throws Exception {
        return new InMemorySourceProvider(Map.of("a", JsonAdapter.read(a), "b", JsonAdapter.read(b)));
    }

    @Test void compoundJoinRetainsOuterJoinOrderNullsAndResidualConditions() throws Exception {
        var data = sources("""
            [{"id":1,"zone":"x"},{"id":1.00,"zone":"y"},{"id":null,"zone":"x"},{"id":3,"zone":"x"}]
            """, """
            [{"owner":1.0,"zone":"y","enabled":true,"tag":"first"},
             {"owner":1,"zone":"x","enabled":true,"tag":"second"},
             {"owner":1,"zone":"x","enabled":false,"tag":"disabled"},
             {"owner":null,"zone":"x","enabled":true,"tag":"nil"}]
            """);
        for (var type : List.of("join", "left join", "right join")) {
            for (var on : List.of("a.id = b.owner and a.zone = b.zone and b.enabled",
                    "b.owner = a.id and (b.enabled and a.zone = b.zone)",
                    "true and (a.id = b.owner and a.zone = b.zone and b.enabled)")) {
                var query = MosaicQL.compile("a " + type + " b on " + on + " [{ id: a.id, tag: b.tag }]");
                var optimized = new QueryExecutor(data);
                var fallback = new QueryExecutor(data, new FunctionRegistry(), delegate::evaluate);
                assertEquals(fallback.executeResult(query), optimized.executeResult(query), type + " " + on);
            }
        }
        assertEquals("[{\"id\":1,\"tag\":\"second\"},{\"id\":1,\"tag\":\"first\"},{\"id\":null,\"tag\":\"nil\"},{\"id\":3,\"tag\":null}]",
            JsonAdapter.write(new QueryExecutor(data).executeResult(MosaicQL.compile(
                "a left join b on a.id = b.owner and a.zone = b.zone and b.enabled [{ id: a.id, tag: b.tag }]"))));
    }

    @Test void compoundJoinFieldReadsAreLinearRatherThanCartesian() {
        var reads = new AtomicInteger();
        var left = new ArrayList<Row>();
        var right = new ArrayList<Row>();
        for (int i=0;i<200;i++) {
            Map<String,Object> key = new LinkedHashMap<>(Map.of("id",i)) {
                @Override public Object get(Object name) { reads.incrementAndGet(); return super.get(name); }
            };
            left.add(new Row(Map.of("key",key)));
            right.add(new Row(Map.of("key",key,"enabled",i%2==0)));
        }
        var data = new InMemorySourceProvider(Map.of("a",new Table(left),"b",new Table(right)));
        var query = MosaicQL.compile("a join b on a.key.id = b.key.id and b.enabled [{ id: a.key.id }]");
        assertEquals(100,new QueryExecutor(data).executeResult(query).rows().size());
        assertTrue(reads.get()<2_000,"Nested key reads: "+reads);
        reads.set(0);
        assertEquals(100,new QueryExecutor(data,new FunctionRegistry(),delegate::evaluate).executeResult(query).rows().size());
        assertTrue(reads.get()>=80_000);
    }

    @Test void functionPrefixesAndOrBranchesAreNotReordered() throws Exception {
        var data = sources("[{\"id\":1},{\"id\":2}]","[{\"id\":2},{\"id\":3}]");
        var calls = new AtomicInteger();
        var functions = new FunctionRegistry().register("observe",0,args->{calls.incrementAndGet();return true;});
        var query = MosaicQL.compile("a join b on observe() and a.id = b.id [{ id: a.id }]",functions);
        assertEquals(1,new QueryExecutor(data,functions).executeResult(query).rows().size());
        assertEquals(4,calls.get());
        calls.set(0);
        query=MosaicQL.compile("a join b on a.id = b.id or observe() [{ id: a.id }]",functions);
        assertEquals(4,new QueryExecutor(data,functions).executeResult(query).rows().size());
        assertEquals(3,calls.get());
        functions.register("broken",0,args->{throw new IllegalStateException("prefix must execute");});
        var broken=MosaicQL.compile("a join b on broken() and a.id = b.id [{ id: a.id }]",functions);
        assertThrows(IllegalArgumentException.class,()->new QueryExecutor(data,functions).executeResult(broken));
    }

    @Test void residualFunctionsRunOnlyForEqualityMatchesAndCanRejectAnOuterMatch() throws Exception {
        var data=sources("[{\"id\":1},{\"id\":2}]","[{\"id\":2},{\"id\":2},{\"id\":3}]");
        var calls=new AtomicInteger();
        var functions=new FunctionRegistry().register("reject",0,args->{calls.incrementAndGet();return false;});
        var query=MosaicQL.compile("a left join b on a.id = b.id and reject() [{ id: a.id, match: b.id }]",functions);
        assertEquals("[{\"id\":1,\"match\":null},{\"id\":2,\"match\":null}]",JsonAdapter.write(new QueryExecutor(data,functions).executeResult(query)));
        assertEquals(2,calls.get());
    }

    @Test void topKMatchesIndependentStableSortForTiesNullsAndDirections() {
        var input=new ArrayList<Row>();
        for(int i=0;i<200;i++) {
            var values=new LinkedHashMap<String,Object>();values.put("id",i);values.put("score",i%9==0?null:i%7);
            input.add(new Row(values));
        }
        var executor=new QueryExecutor(name->new Table(input));
        for (boolean descending:List.of(false,true)) {
            var comparator=Comparator.comparing((Row r)->(Integer)r.values().get("score"),Comparator.nullsFirst(Comparator.naturalOrder()));
            if(descending)comparator=comparator.reversed();
            var expected=new ArrayList<>(input);expected.sort(comparator);
            for(int limit:List.of(1,17,199,200,250)) {
                var query=MosaicQL.compile("a [{ id, score }] order by score "+(descending?"desc":"asc")+" limit "+limit);
                assertEquals(expected.subList(0,Math.min(limit,expected.size())),executor.executeResult(query).rows());
            }
        }
    }

    @Test void topKStillProjectsEveryCandidateAndDefersComparatorFailures() throws Exception {
        var data=sources("[{\"v\":1},{\"v\":\"incompatible\"},{\"v\":2}]","[]");
        var calls=new AtomicInteger();
        var functions=new FunctionRegistry().register("observe",1,args->{calls.incrementAndGet();return args.getFirst();});
        var query=MosaicQL.compile("a [{ v: observe(v) }] order by v limit 1",functions);
        var error=assertThrows(IllegalArgumentException.class,()->new QueryExecutor(data,functions).executeResult(query));
        assertTrue(error.getMessage().contains("incompatible types"));
        assertEquals(3,calls.get());
        functions.register("late_failure",1,args->{
            if(args.getFirst() instanceof Number n && n.intValue()==2)throw new IllegalStateException("last projection");
            return args.getFirst();
        });
        var late=MosaicQL.compile("a [{ v: late_failure(v) }] order by v limit 1",functions);
        error=assertThrows(IllegalArgumentException.class,()->new QueryExecutor(data,functions).executeResult(late));
        assertTrue(error.getMessage().contains("last projection"));
    }

    @Test void compactRowsRetainNullOrderEqualityAndImmutableViews() throws Exception {
        var result=new QueryExecutor(name->new Table(List.of(new Row(Map.of("id",1)),new Row(Map.of("id",2)))))
            .executeResult(MosaicQL.compile("a [{ id, absent, label: \"x\" }]"));
        var row=result.rows().getFirst();
        assertEquals(List.of("id","absent","label"),new ArrayList<>(row.values().keySet()));
        assertTrue(row.values().containsKey("absent"));assertNull(row.values().get("absent"));
        var expected=new LinkedHashMap<String,Object>();expected.put("id",1);expected.put("absent",null);expected.put("label","x");
        assertEquals(new Row(expected),row);assertEquals(new Row(expected).hashCode(),row.hashCode());
        assertThrows(UnsupportedOperationException.class,()->row.values().put("id",7));
        assertThrows(UnsupportedOperationException.class,()->row.values().entrySet().iterator().next().setValue(7));
        assertThrows(UnsupportedOperationException.class,()->row.values().keySet().remove("id"));
        assertThrows(UnsupportedOperationException.class,()->row.values().values().clear());
        assertThrows(UnsupportedOperationException.class,()->row.values().computeIfAbsent("id",key->7));
        expected.put("id",10);assertEquals(1,row.values().get("id"));
        assertEquals(2,result.rows().get(1).values().get("id"));
    }

    @Test void functionArgumentsStayReadonlyNullableAndDistinctAcrossRows() throws Exception {
        var saved=new ArrayList<List<Object>>();
        var functions=new FunctionRegistry().register("save",2,args->{
            assertThrows(UnsupportedOperationException.class,()->args.set(0,"changed"));
            saved.add(args);return args.getFirst();
        });
        var executor=new QueryExecutor(sources("[{\"v\":1},{\"v\":null}]","[]"),functions);
        executor.executeResult(MosaicQL.compile("a [{ value: save(v, \"fixed\") }]",functions));
        assertEquals(Arrays.asList(new BigDecimal("1"),"fixed"),saved.getFirst());
        assertEquals(Arrays.asList(null,"fixed"),saved.get(1));
        assertNotSame(saved.getFirst(),saved.get(1));
    }

    @Test void specializedGroupKeysPreserveNullsNumericEqualityAndFirstOccurrenceOrder() throws Exception {
        var data=sources("[{\"k\":1.00,\"zone\":\"x\"},{\"k\":null,\"zone\":null},{\"k\":1,\"zone\":\"x\"},{\"k\":1,\"zone\":\"y\"}]","[]");
        var executor=new QueryExecutor(data);
        assertEquals("[{\"k\":1,\"n\":3},{\"k\":null,\"n\":1}]",JsonAdapter.write(executor.executeResult(MosaicQL.compile("a [{ k, n: count(*) }] group by k"))));
        assertEquals("[{\"k\":1,\"zone\":\"x\",\"n\":2},{\"k\":null,\"zone\":null,\"n\":1},{\"k\":1,\"zone\":\"y\",\"n\":1}]",JsonAdapter.write(executor.executeResult(MosaicQL.compile("a [{ k, zone, n: count(*) }] group by (k, zone)"))));
    }
}
