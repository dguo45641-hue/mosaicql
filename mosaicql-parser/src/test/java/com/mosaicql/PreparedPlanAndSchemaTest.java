package com.mosaicql;

import com.mosaicql.plan.*;
import com.mosaicql.runtime.*;
import com.mosaicql.type.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PreparedPlanAndSchemaTest {
    @Test void compileBindsProgramsLayoutsAndKeepsPerRequestStateOutOfPlan() {
        var functions=new FunctionRegistry();
        var plan=MosaicQL.compile("a [{ id, label: upper(name), net: amount * 0.9 }] where active",functions);
        assertNotNull(plan.executable());assertNotNull(plan.executable().where());
        assertEquals(List.of("id","label","net"),plan.executable().output().columns());
        assertNotNull(plan.executable().projection(2));assertTrue(plan.executable().compatible(functions));
    }
    @Test void registryIdentityAndRevisionAreGuardedBeforeExecution() {
        var first=new FunctionRegistry().register("value",0,args->1);
        var second=new FunctionRegistry().register("value",0,args->2);
        var compiled=MosaicQL.compile("a { n: value() }",first);
        var data=new Table(List.of(new Row(Map.of())));
        assertEquals(2,new QueryExecutor(name->data,second).executeResult(compiled).rows().getFirst().values().get("n"));
        var functions=new FunctionRegistry().register("choose",new FunctionSignature(List.of(MType.NUMBER),1,1,MType.STRING,false),args->"number");
        var query=MosaicQL.compile("a { n: choose(v) }",functions);
        functions.register("choose",new FunctionSignature(List.of(MType.STRING),1,1,MType.STRING,false),args->"string");
        assertEquals("string",new QueryExecutor(name->new Table(List.of(new Row(Map.of("v","x")))),functions)
            .executeResult(query).rows().getFirst().values().get("n"));
    }
    @Test void callerCanExtendBuiltinOverloadsWithoutReusingStaleBinding() {
        var functions=new FunctionRegistry();var plan=MosaicQL.compile("a [{ v: upper(v) }]",functions);
        var executor=new QueryExecutor(name->new Table(List.of(new Row(Map.of("v",7)))),functions);
        assertThrows(IllegalArgumentException.class,()->executor.executeResult(plan));
        functions.register("upper",new FunctionSignature(List.of(MType.NUMBER),1,1,MType.STRING,false),args->"N"+args.getFirst());
        assertEquals("N7",executor.executeResult(plan).rows().getFirst().values().get("v"));
    }
    @Test void boundPlanPreservesLazyEvaluationAndUnknownRuntimeTypes() {
        var functions=new FunctionRegistry().register("broken",0,args->{throw new AssertionError("unselected branch");});
        var plan=MosaicQL.compile("a [{ a: coalesce(v, broken()), b: case when active then v else broken() end, c: false and broken(), d: true or broken() }]",functions);
        var result=new QueryExecutor(name->new Table(List.of(new Row(Map.of("v",7,"active",true)))),functions).executeResult(plan);
        assertEquals(7,result.rows().getFirst().values().get("a"));
        assertEquals(false,result.rows().getFirst().values().get("c"));
    }
    @Test void schemaRowsDefensivelyCopyValuesAndExposeOnlyImmutableViews() {
        var layout=new RowLayout(List.of("id","name"));var values=new Object[]{1,null};var row=Row.of(layout,values);
        values[0]=7;assertEquals(1,row.values().get("id"));assertTrue(row.values().containsKey("name"));
        assertThrows(UnsupportedOperationException.class,()->row.values().entrySet().iterator().next().setValue(7));
        assertThrows(UnsupportedOperationException.class,()->layout.columns().set(0,"bad"));
        assertThrows(IllegalArgumentException.class,()->new RowLayout(List.of("a","a")));
        assertThrows(IllegalArgumentException.class,()->Row.of(layout,1));
    }
    @Test void schemaSlotsAreGuardedAgainstOtherLayoutsAndMapInputs() {
        var schema=new TableDefinition("a",List.of(new ColumnDefinition("id",MType.NUMBER,true),new ColumnDefinition("name",MType.STRING,true)));
        var plan=MosaicQL.compile("a [{ id, name: upper(name) }]",new FunctionRegistry(),Map.of("a",schema));
        var input=new Table(List.of(Row.of(new RowLayout(List.of("name","id")),"b",2),new Row(Map.of("id",3,"name","c"))));
        var result=new QueryExecutor(name->input).executeResult(plan);
        assertEquals("[{\"id\":2,\"name\":\"B\"},{\"id\":3,\"name\":\"C\"}]",JsonAdapter.write(result));
    }
    @Test void fixedSchemaJsonAcceptsAnyPropertyOrderAndChecksTheWholeDocument() {
        var layout=new RowLayout(List.of("id","name","amount"));
        var input=JsonAdapter.read("[{\"amount\":1.2300,\"name\":\"x\",\"id\":9007199254740993},{\"id\":2}]",layout);
        assertEquals(new BigDecimal("9007199254740993"),input.rows().getFirst().values().get("id"));
        assertNull(input.rows().get(1).values().get("name"));assertEquals(3,input.rows().get(1).values().size());
        for(String invalid:List.of("{\"extra\":1}","{\"id\":1,\"id\":2}","[1]","{} {}","{\"name\":{\"x\":1,\"x\":2}}"))
            assertThrows(RuntimeException.class,()->JsonAdapter.read(invalid,layout),invalid);
        assertEquals(0,JsonAdapter.read("null",layout).rows().size());
    }
    @Test void wideSchemaHasCorrectIndexedAccessWithoutChangingPublicRowEquality() {
        var names=new ArrayList<String>();var expected=new LinkedHashMap<String,Object>();var values=new Object[128];
        for(int i=0;i<values.length;i++) { names.add("f"+i);values[i]=i;expected.put("f"+i,i); }
        var row=Row.of(new RowLayout(names),values);assertEquals(new Row(expected),row);
        for(int i=0;i<values.length;i++) { assertEquals(i,row.get("f"+i,i));assertEquals(i,row.get("f"+i,127-i)); }
    }
    @Test void preparedPlanAndExecutorCanBeSharedAcrossRequestsAndThreads() throws Exception {
        var functions=new FunctionRegistry();var query=MosaicQL.compile("a [{ id, name: upper(name), net: amount * 0.9 }]",functions);
        var data=JsonAdapter.read("[{\"id\":1,\"name\":\"x\",\"amount\":3}]");var executor=new QueryExecutor(name->data,functions);
        var expected=executor.executeResult(query);
        try(var pool=Executors.newFixedThreadPool(4)) {
            var futures=new ArrayList<Future<QueryResult>>();
            for(int i=0;i<100;i++)futures.add(pool.submit(()->executor.executeResult(query)));
            for(var result:futures)assertEquals(expected,result.get());
        }
    }
    @Test void standaloneEvaluationDuringAFunctionStillResolvesTheProvidedContext() {
        var holder=new com.mosaicql.plan.CompiledQuery[1];var executor=new QueryExecutor[1];
        var functions=new FunctionRegistry().register("probe",0,args->executor[0].evaluate(holder[0].projection().getFirst(),
            Map.of("other",new Row(Map.of("id",7)))));
        holder[0]=MosaicQL.compile("a { id: a.id, observed: probe() }",functions);
        executor[0]=new QueryExecutor(name->new Table(List.of(new Row(Map.of("id",1)))),functions);
        var error=assertThrows(IllegalArgumentException.class,()->executor[0].executeResult(holder[0]));
        assertTrue(error.getMessage().contains("Unknown source alias: a"));
    }
}
