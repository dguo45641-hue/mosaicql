package com.mosaicql;

import com.mosaicql.plan.DefaultExpressionInterpreter;
import com.mosaicql.runtime.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SixExecutionFeaturesTest {
    private QueryExecutor reference(SourceProvider sources,FunctionRegistry functions) {
        return new QueryExecutor(sources,functions,new DefaultExpressionInterpreter()::evaluate);
    }
    private void same(SourceProvider sources,String text) {
        var functions=new FunctionRegistry();var plan=MosaicQL.compile(text,functions);
        assertEquals(reference(sources,functions).executeResult(plan),new QueryExecutor(sources,functions).executeResult(plan),text);
    }
    private void sameError(SourceProvider sources,String text) {
        var functions=new FunctionRegistry();var plan=MosaicQL.compile(text,functions);
        var expected=assertThrows(RuntimeException.class,()->reference(sources,functions).executeResult(plan));
        var actual=assertThrows(RuntimeException.class,()->new QueryExecutor(sources,functions).executeResult(plan));
        assertEquals(expected.getClass(),actual.getClass(),text);assertEquals(expected.getMessage(),actual.getMessage(),text);
    }
    @Test void lateTopKRetainsStableTiesNullsOutputAliasesAndDirections() {
        var rows=new ArrayList<Row>();
        for(int i=0;i<200;i++) { var map=new LinkedHashMap<String,Object>();map.put("id",i);map.put("v",i%9==0?null:i%7);rows.add(new Row(map)); }
        SourceProvider sources=name->new Table(rows);
        for(String order:List.of("v", "v desc", "v desc, id", "v, id desc"))for(int limit:List.of(1,17,199,250))
            same(sources,"a [{ id, v }] order by "+order+" limit "+limit);
        same(sources,"a [{ label: v, id }] order by label desc, id limit 11");
        same(sources,"a { id, v } order by v desc");
    }
    @Test void topKStillCountsEveryCandidateAndKeepsLaterFilterErrorPriority() {
        var input=JsonAdapter.read("[{\"v\":1,\"n\":1},{\"v\":\"bad-sort-type\",\"n\":1},{\"v\":2,\"n\":0}]");
        SourceProvider sources=name->input;
        sameError(sources,"a [{ v }] where 1 / n > 0 order by v limit 1");
        var query=MosaicQL.compile("a [{ v }] order by v limit 1");
        var options=new ExecutionOptions(0,2,64);
        var expected=assertThrows(RuntimeException.class,()->reference(sources,new FunctionRegistry()).executeResult(query,options));
        var actual=assertThrows(RuntimeException.class,()->new QueryExecutor(sources).executeResult(query,options));
        assertEquals(expected.getMessage(),actual.getMessage());
    }
    @Test void unsafeTopKProjectionKeepsAllFunctionCallsAndExpressionFailures() {
        var calls=new AtomicInteger();var functions=new FunctionRegistry().register("observe",1,args->{calls.incrementAndGet();return args.getFirst();});
        var input=JsonAdapter.read("[{\"v\":3},{\"v\":2},{\"v\":1}]");
        var plan=MosaicQL.compile("a [{ v: observe(v) }] order by v limit 1",functions);
        new QueryExecutor(name->input,functions).executeResult(plan);assertEquals(3,calls.get());
        sameError(name->input,"a [{ v, bad: 1 / (v - 1) }] order by v desc limit 1");
    }
    @Test void nativeIntegralSortKeysRemainExactBeyondDoublePrecisionAndWithMixedNumbers() {
        var input=new Table(List.of(new Row(Map.of("n",9007199254740993L)),new Row(Map.of("n",Long.MAX_VALUE)),
            new Row(Map.of("n",Long.MIN_VALUE)),new Row(Map.of("n",new java.math.BigDecimal("9007199254740992.5"))),
            new Row(Map.of("n",1)),new Row(Map.of("n",1.0d))));
        same(name->input,"a [{ n }] order by n limit 5");
        same(name->input,"a [{ n }] order by n desc limit 5");
        assertEquals(Long.MIN_VALUE,new QueryExecutor(name->input).executeResult(MosaicQL.compile("a [{ n }] order by n limit 1")).rows().getFirst().values().get("n"));
    }
    @Test void compositeJoinPrunesLowSelectivityFirstKeysAndPreservesResidualCalls() {
        var reads=new AtomicInteger();var left=new ArrayList<Row>();var right=new ArrayList<Row>();
        for(int i=0;i<200;i++) {
            Map<String,Object> nested=new HashMap<>(Map.of("id",i)) {
                @Override public Object get(Object key) { reads.incrementAndGet();return super.get(key); }
            };
            left.add(new Row(Map.of("tenant",1,"key",nested)));right.add(new Row(Map.of("tenant",1,"key",nested)));
        }
        var data=new InMemorySourceProvider(Map.of("a",new Table(left),"b",new Table(right)));
        var calls=new AtomicInteger();var functions=new FunctionRegistry().register("observe",0,args->{calls.incrementAndGet();return true;});
        var plan=MosaicQL.compile("a join b on a.tenant = b.tenant and a.key.id = b.key.id [{ id: a.key.id }]",functions);
        assertEquals(200,new QueryExecutor(data,functions).executeResult(plan).rows().size());
        assertTrue(reads.get()<3000,"reads="+reads);
        reads.set(0);assertEquals(200,reference(data,functions).executeResult(plan).rows().size());assertTrue(reads.get()>70000);
        var residual=MosaicQL.compile("a join b on a.tenant = b.tenant and a.key.id = b.key.id and observe() [{ id: a.key.id }]",functions);
        new QueryExecutor(data,functions).executeResult(residual);assertEquals(200,calls.get());
    }
    @Test void equalityAfterAFunctionCannotMoveIntoCompositeIndex() {
        var table=JsonAdapter.read("[{\"tenant\":1,\"id\":1},{\"tenant\":1,\"id\":2}]");
        var calls=new AtomicInteger();var functions=new FunctionRegistry().register("observe",0,args->{calls.incrementAndGet();return true;});
        var plan=MosaicQL.compile("a join b on a.tenant = b.tenant and observe() and a.id = b.id { n: count(*) }",functions);
        new QueryExecutor(name->table,functions).executeResult(plan);assertEquals(4,calls.get());
    }
    @Test void compositeCountsOuterJoinsAndExactNumericKeysMatchGenericExecution() {
        var a=JsonAdapter.read("[{\"tenant\":1,\"id\":1},{\"tenant\":1.00,\"id\":1.0},{\"tenant\":null,\"id\":null},{\"tenant\":2,\"id\":9}]");
        var b=JsonAdapter.read("[{\"tenant\":1.0,\"id\":1},{\"tenant\":1,\"id\":1.00},{\"tenant\":null,\"id\":null},{\"tenant\":3,\"id\":9}]");
        var data=new InMemorySourceProvider(Map.of("a",a,"b",b));
        for(var join:List.of("join","left join","right join")) {
            same(data,"a "+join+" b on a.tenant = b.tenant and a.id = b.id { n: count(*) }");
            same(data,"a "+join+" b on a.tenant = b.tenant and a.id = b.id [{ a: a.id, b: b.id }]");
        }
        var mixed=JsonAdapter.read("[{\"id\":1},{\"id\":1.0},{\"id\":1.5},{\"id\":9007199254740993},{\"id\":922337203685477580812345},{\"id\":null},{\"id\":\"1\"}]");
        same(name->mixed,"a join b on a.id = b.id and true { n: count(*) }");
        same(name->mixed,"a join b on a.id = b.id [{ id: a.id }]");
    }
    @Test void onlineAggregateSupportsFilterComputedArgumentsCasesAndJoinInputs() {
        var a=JsonAdapter.read("[{\"id\":1,\"g\":\"b\",\"v\":2},{\"id\":2,\"g\":\"a\",\"v\":null},{\"id\":3,\"g\":\"b\",\"v\":4},{\"id\":4,\"g\":null,\"v\":6}]");
        var b=JsonAdapter.read("[{\"owner\":1,\"n\":2},{\"owner\":1,\"n\":3},{\"owner\":3,\"n\":4},{\"owner\":8,\"n\":5}]");
        var data=new InMemorySourceProvider(Map.of("a",a,"b",b));
        same(data,"a [{ g, total: sum(v * 0.9), mean: avg(v * 0.9), n: count(v * 0.9), lo: min(v * 0.9), hi: max(v * 0.9) }] where id > 1 group by g order by total desc");
        same(data,"a [{ g: upper(g), total: sum(case when v > 3 then v else 0 end) + count(*) }] group by (upper(g)) ");
        for(var join:List.of("join","left join","right join"))
            same(data,"a "+join+" b on a.id = b.owner [{ g: a.g, total: sum(b.n * 2), mean: avg(b.n * 2), n: count(*) }] where b.n > 0 group by a.g order by sum(b.n * 2) desc");
        same(data,"a [{ g }] where id > 0 group by g");
        same(data,"a { total: sum(v), n: count(*) } where false");
    }
    @Test void sharedAggregateOperandEvaluatesOncePerInputRow() {
        var reads=new AtomicInteger();var rows=new ArrayList<Row>();
        for(int i=0;i<30;i++) {
            Map<String,Object> nested=new HashMap<>(Map.of("v",i)) {
                @Override public Object get(Object key) { reads.incrementAndGet();return super.get(key); }
            };rows.add(new Row(Map.of("box",nested)));
        }
        var query=MosaicQL.compile("a { total: sum(a.box.v * 2), mean: avg(a.box.v * 2), n: count(a.box.v * 2), lo: min(a.box.v * 2), hi: max(a.box.v * 2) }");
        var result=new QueryExecutor(name->new Table(rows)).executeResult(query);assertEquals(30,reads.get());
        reads.set(0);assertEquals(result,reference(name->new Table(rows),new FunctionRegistry()).executeResult(query));assertEquals(150,reads.get());
    }
    @Test void onlineAggregationPreservesDeferredErrorsLimitAndUnusedBranches() {
        var input=JsonAdapter.read("[{\"id\":1,\"g\":1,\"v\":4},{\"id\":2,\"g\":2,\"v\":0}]");
        same(name->input,"a [{ g, total: sum(1 / v) }] where id > 0 group by g limit 1");
        same(name->input,"a { n: coalesce(1, sum(1 / v)) }");
        same(name->input,"a { n: case when true then 1 else sum(1 / v) end }");
        var bad=JsonAdapter.read("[{\"id\":1,\"g\":[],\"v\":1},{\"id\":2,\"g\":2,\"v\":0}]");
        sameError(name->bad,"a [{ g, total: sum(v) }] where 1 / v > 0 group by g");
        var badOperand=JsonAdapter.read("[{\"id\":1},{\"id\":2}]");
        String operand="case when id = 1 then \"bad\" else 1 / 0 end";
        sameError(name->badOperand,"a { total: sum("+operand+"), n: count("+operand+") }");
        sameError(name->badOperand,"a { n: count("+operand+"), total: sum("+operand+") }");
    }
    @Test void userFunctionsInAggregateArgumentsKeepGroupEvaluationOrder() {
        var seen=new ArrayList<Integer>();var f=new FunctionRegistry().register("observe",1,args->{seen.add(((Number)args.getFirst()).intValue());return args.getFirst();});
        var data=JsonAdapter.read("[{\"g\":1,\"v\":1},{\"g\":2,\"v\":2},{\"g\":1,\"v\":3}]");
        var query=MosaicQL.compile("a [{ g, total: sum(observe(v)) }] group by g",f);
        new QueryExecutor(name->data,f).executeResult(query);assertEquals(List.of(1,3,2),seen);
    }
    @Test void projectionAndOrderFunctionsCanMutateLaterGroupInputsAndMustKeepOriginalOrder() {
        var later=new HashMap<String,Object>();later.put("v",2);
        var table=new Table(List.of(new Row(Map.of("g",1,"box",new HashMap<>(Map.of("v",1)))),new Row(Map.of("g",2,"box",later))));
        var functions=new FunctionRegistry().register("mutate",1,args->{later.put("v",100);return args.getFirst();});
        for(String text:List.of("a [{ g, total: sum(a.box.v), changed: mutate(g) }] group by g",
            "a [{ g, total: sum(a.box.v) }] group by g order by mutate(g)")) {
            var plan=MosaicQL.compile(text,functions);assertFalse(plan.executable().onlineAggregate());
            later.put("v",2);var expected=reference(name->table,functions).executeResult(plan);
            later.put("v",2);var actual=new QueryExecutor(name->table,functions).executeResult(plan);
            assertEquals(expected,actual,text);assertEquals(new java.math.BigDecimal("100"),actual.rows().get(1).values().get("total"));
        }
    }
    @Test void unknownFunctionsCannotMakeCompositeIndexesMissMutatedNestedJoinKeys() {
        var later=new HashMap<String,Object>();later.put("id",2);
        var a=new Table(List.of(new Row(Map.of("tenant",1,"id",1)),new Row(Map.of("tenant",1,"id",2))));
        var b=new Table(List.of(new Row(Map.of("tenant",1,"box",new HashMap<>(Map.of("id",1)))),new Row(Map.of("tenant",1,"box",later))));
        var sources=new InMemorySourceProvider(Map.of("a",a,"b",b));
        var functions=new FunctionRegistry().register("touch",0,args->{later.put("id",1);return true;});
        var query=MosaicQL.compile("a join b on a.tenant = b.tenant and a.id = b.box.id [{ id: a.id, touched: touch() }]",functions);
        later.put("id",2);var expected=reference(sources,functions).executeResult(query);
        later.put("id",2);var actual=new QueryExecutor(sources,functions).executeResult(query);
        assertEquals(expected,actual);assertEquals(2,actual.rows().size());
    }
}
