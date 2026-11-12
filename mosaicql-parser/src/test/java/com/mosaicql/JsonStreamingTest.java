package com.mosaicql;

import com.mosaicql.ast.Ast;
import com.mosaicql.runtime.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class JsonStreamingTest {
    @Test void streamingRetainsPreviousNumericEncodingIncludingFloatConversionAndNegativeZero() throws Exception {
        var values=new LinkedHashMap<String,Object>();
        values.put("a",new BigDecimal("1.2300"));values.put("b",new BigDecimal("0.000"));
        values.put("c",new BigDecimal("1E+10"));values.put("d",-0.0);values.put("e",1.2f);values.put("f",new AtomicInteger(5));
        var row=new Row(values);
        assertEquals("{\"a\":1.23,\"b\":0,\"c\":10000000000,\"d\":0,\"e\":1.2000000476837158,\"f\":5}",JsonAdapter.write(row));
        assertEquals(new BigDecimal("1.2000000476837158"),JsonAdapter.toJson(row).get("e").decimalValue());
    }

    @Test void streamAndTreePreserveNestedShapeEscapingNullsAndFieldOrder() throws Exception {
        var values=new LinkedHashMap<String,Object>();
        values.put("text", "中🙂\"\\\n\t");values.put("nil",null);
        values.put("items",Arrays.asList(null,new Row(Map.of("v",2)),List.of(true,false)));
        var row=new Row(values);
        var encoded=JsonAdapter.write(row);
        assertEquals(encoded,JsonAdapter.write(JsonAdapter.read(encoded).rows().getFirst()));
        assertTrue(encoded.startsWith("{\"text\":"));
        assertFalse(encoded.contains("\n"));
        assertTrue(encoded.contains("\\n"));
    }

    @Test void outputStreamAndWriterAreFlushedButRemainOwnedByTheCaller() throws Exception {
        var result=new QueryResult(Ast.ResultShape.ARRAY,List.of(new Row(Map.of("text","中🙂","n",new BigDecimal("2.00")))));
        var output=new ByteArrayOutputStream(){boolean closed;@Override public void close(){closed=true;}};
        JsonAdapter.write(result,output);
        assertFalse(output.closed);
        assertEquals(JsonAdapter.write(result),output.toString(java.nio.charset.StandardCharsets.UTF_8));
        var writer=new StringWriter(){boolean closed;@Override public void close(){closed=true;}};
        JsonAdapter.write(result,writer);
        assertFalse(writer.closed);assertEquals(JsonAdapter.write(result),writer.toString());
        JsonAdapter.write(new QueryResult(Ast.ResultShape.OBJECT,List.of()),new StringWriter());
    }

    @Test void streamingKeepsStrictValueAndKeyValidation() {
        for(var number:List.of(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Float.NaN)) {
            var row=new Row(Map.of("v",number));
            assertThrows(IllegalArgumentException.class,()->JsonAdapter.write(row));
            assertThrows(IllegalArgumentException.class,()->JsonAdapter.toJson(row));
        }
        for(var unsupported:List.of(LocalDate.of(2026,11,10),new Object(),Map.of(7,"bad key"))) {
            var row=new Row(Map.of("v",unsupported));
            assertThrows(IllegalArgumentException.class,()->JsonAdapter.write(row));
            assertThrows(IllegalArgumentException.class,()->JsonAdapter.toJson(row));
        }
    }
}
