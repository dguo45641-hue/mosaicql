package com.mosaicql;

import com.mosaicql.runtime.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StreamingQueryTest {
    @Test void streamingMatchesMaterializedJsonForShapesNestedQueriesSortAndAggregation() throws Exception {
        var table=JsonAdapter.read("[{\"id\":1,\"g\":\"a\",\"v\":2,\"items\":[{\"id\":3}]},{\"id\":2,\"g\":\"b\",\"v\":4,\"items\":[]}]");
        var executor=new QueryExecutor(name->table);
        for(String text:List.of("a [{ id, x: v * 2 }] where id > 0","a { id }","a [{ id }] where false", "a { id } where false",
            "a as u [{ id: u.id, nested: u.items [{ id }] }]", "a [{ id, v }] order by v desc limit 1",
            "a [{ g, total: sum(v * 2), mean: avg(v * 2), n: count(*) }] group by g order by total desc", "a [{}]")) {
            var plan=MosaicQL.compile(text);var expected=JsonAdapter.write(executor.executeResult(plan));
            var bytes=new ByteArrayOutputStream();executor.executeTo(plan,bytes);assertEquals(expected,bytes.toString(java.nio.charset.StandardCharsets.UTF_8),text);
            var writer=new StringWriter();executor.executeTo(plan,writer);assertEquals(expected,writer.toString(),text);
        }
    }
    @Test void forEachEmitsBeforeEvaluatingTheRestOfTheInputAndHonorsShape() {
        var calls=new AtomicInteger();var functions=new FunctionRegistry().register("observe",1,args->{calls.incrementAndGet();return args.getFirst();});
        var input=JsonAdapter.read("[{\"id\":1},{\"id\":2},{\"id\":3}]");var executor=new QueryExecutor(name->input,functions);
        var query=MosaicQL.compile("a [{ id: observe(id) }]",functions);var outputs=new AtomicInteger();
        assertEquals(3,executor.forEach(query,row->{assertEquals(outputs.incrementAndGet(),calls.get());}));
        assertEquals(3,outputs.get());assertEquals(1,executor.forEach(MosaicQL.compile("a { id }"),row->{}));
        assertEquals(3,executor.execute(MosaicQL.compile("a { id }")).rows().size());
    }
    @Test void callerOwnsTargetsAndZeroLimitDoesNotLoadInputs() throws Exception {
        var executor=new QueryExecutor(name->{throw new AssertionError("must not load");});var query=MosaicQL.compile("a [{ id }] limit 0");
        var stream=new ByteArrayOutputStream(){boolean closed;@Override public void close(){closed=true;}};
        executor.executeTo(query,stream);assertEquals("[]",stream.toString());assertFalse(stream.closed);
        var writer=new StringWriter(){boolean closed;@Override public void close(){closed=true;}};
        executor.executeTo(MosaicQL.compile("a { id } limit 0"),writer);assertEquals("null",writer.toString());assertFalse(writer.closed);
    }
    @Test void consumerFailureAndCancellationStopImmediatelyAndRestoreExecutionState() {
        var calls=new AtomicInteger();var functions=new FunctionRegistry().register("observe",1,args->{calls.incrementAndGet();return args.getFirst();});
        var input=JsonAdapter.read("[{\"id\":1},{\"id\":2},{\"id\":3}]");var executor=new QueryExecutor(name->input,functions);
        var query=MosaicQL.compile("a [{ id: observe(id) }]",functions);
        var failure=assertThrows(IllegalStateException.class,()->executor.forEach(query,row->{throw new IllegalStateException("consumer stopped");}));
        assertEquals("consumer stopped",failure.getMessage());assertEquals(1,calls.get());
        assertThrows(com.mosaicql.error.MosaicQLException.class,()->executor.forEach(query,row->Thread.currentThread().interrupt()));
        Thread.interrupted();assertEquals(3,executor.executeResult(query).rows().size());
    }
    @Test void streamingSortCountsDiscardedCandidatesAndDoesNotCloseOnError() {
        var input=JsonAdapter.read("[{\"id\":3},{\"id\":2},{\"id\":1}]");var executor=new QueryExecutor(name->input);
        var output=new ByteArrayOutputStream(){boolean closed;@Override public void close(){closed=true;}};
        var query=MosaicQL.compile("a [{ id }] order by id limit 1");
        assertThrows(com.mosaicql.error.MosaicQLException.class,()->executor.executeTo(query,output,new ExecutionOptions(0,2,64)));
        assertFalse(output.closed);
        assertEquals(1,executor.executeResult(query).rows().size());
    }
    @Test void outputIoFailurePropagatesWithoutContinuingTheQuery() {
        var input=JsonAdapter.read("[{\"id\":1},{\"id\":2}]");var executor=new QueryExecutor(name->input);
        var output=new OutputStream(){@Override public void write(int value)throws IOException { throw new IOException("broken pipe"); }};
        assertThrows(RuntimeException.class,()->executor.executeTo(MosaicQL.compile("a [{ id }]"),output));
        assertEquals(2,executor.executeResult(MosaicQL.compile("a [{ id }]")).rows().size());
    }
    @Test void unknownFunctionOutputKeepsMaterializedMutableValueSemantics() throws Exception {
        var shared=new ArrayList<Object>();var functions=new FunctionRegistry().register("append",1,args->{shared.add(args.getFirst());return shared;});
        var input=JsonAdapter.read("[{\"id\":1},{\"id\":2},{\"id\":3}]");var executor=new QueryExecutor(name->input,functions);
        var query=MosaicQL.compile("a [{ values: append(id) }]",functions);assertFalse(query.executable().streamSafe());
        var expected=JsonAdapter.write(executor.executeResult(query));shared.clear();var bytes=new ByteArrayOutputStream();
        executor.executeTo(query,bytes);assertEquals(expected,bytes.toString(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void explicitlyPureExtensionsCanUseBoundStreamingAndOnlineAggregation() throws Exception {
        var functions=new FunctionRegistry().registerPure("twice",1,args->args.getFirst()==null?null:
            new java.math.BigDecimal(args.getFirst().toString()).multiply(java.math.BigDecimal.valueOf(2)));
        var input=JsonAdapter.read("[{\"g\":1,\"v\":2},{\"g\":1,\"v\":3}]");var executor=new QueryExecutor(name->input,functions);
        var query=MosaicQL.compile("a [{ g, total: sum(twice(v)), mean: avg(twice(v)) }] group by g",functions);
        assertTrue(query.executable().onlineAggregate());assertTrue(query.executable().streamSafe());
        var bytes=new ByteArrayOutputStream();executor.executeTo(query,bytes);
        assertEquals(JsonAdapter.write(executor.executeResult(query)),bytes.toString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
