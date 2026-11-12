package com.mosaicql;

import com.mosaicql.runtime.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class JsonDirectCodecTest {
    private final JsonMapper oracle=JsonMapper.builderWithJackson2Defaults()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    @Test void directParserMatchesTreePathForEveryJsonShapeAndExactDecimals() {
        for(String text:List.of("null","[]","{}","1","1000","1.00","1e3","-0.0","true","\"中🙂\"",
            "[null,1,1000,1.00,1e3,false,\"a\",[],{}]", """
            [{"id":9007199254740993,"n":0.0000000000000000001234000,"v":[{"x":null},1.2500],"s":"中🙂\\n"},
             {"id":922337203685477580812345,"n":1e100,"v":[],"s":""}]
            """)) {
            var expected=JsonAdapter.read(oracle.readTree(text));
            assertEquals(expected,JsonAdapter.read(text),text);
            assertEquals(expected,JsonAdapter.read(text.getBytes(StandardCharsets.UTF_8)),text);
            assertEquals(JsonAdapter.write(expected),JsonAdapter.write(JsonAdapter.read(text)),text);
        }
    }
    @Test void malformedDuplicatesAndTrailingTokensFailAtEveryDepth() {
        for(String text:List.of("{", "[", "[1,", "{\"a\":", "{\"a\":1", "{\"a\":1,\"a\":2}",
            "{\"a\":null,\"a\":null}","[{\"a\":{\"x\":1,\"x\":2}}]", "[1] []", "null true", "{}x", "{\"a\" 1}"))
            assertThrows(JacksonException.class,()->JsonAdapter.read(text),text);
        for(String text:List.of("","  ")) assertThrows(IllegalArgumentException.class,()->JsonAdapter.read(text));
    }
    @Test void parsedRowsAndNestedObjectsStayImmutableAndIndependent() {
        var input=JsonAdapter.read("[{\"x\":1,\"nested\":{\"n\":2}},{\"x\":3,\"nested\":{\"n\":4}}]");
        var first=input.rows().getFirst();
        assertThrows(UnsupportedOperationException.class,()->first.values().put("x",7));
        @SuppressWarnings("unchecked") var nested=(Map<String,Object>)first.values().get("nested");
        assertThrows(UnsupportedOperationException.class,()->nested.put("n",7));
        assertThrows(UnsupportedOperationException.class,()->nested.entrySet().iterator().next().setValue(7));
        assertEquals(new BigDecimal("4"),input.rows().get(1).get(List.of("nested","n")));
    }
    @Test void segmentedWriterHandlesLargeOutputsAndRecoversAfterFailure() {
        String text="中🙂\\\"\n".repeat(200_000);
        var row=new Row(Map.of("text",text));
        var encoded=JsonAdapter.write(row);
        assertEquals(row,JsonAdapter.read(encoded).rows().getFirst());
        for(int i=0;i<10;i++) {
            assertThrows(IllegalArgumentException.class,()->JsonAdapter.write(new Row(Map.of("bad",new Object()))));
            assertEquals(encoded,JsonAdapter.write(row));
        }
    }
    @Test void wideObjectsKeepOrderAndRejectLateDuplicateFields() {
        var fields=new LinkedHashMap<String,Object>();
        for(int i=0;i<1000;i++) fields.put("f"+i,new BigDecimal(i));
        var row=new Row(fields);var encoded=JsonAdapter.write(row);
        assertEquals(row,JsonAdapter.read(encoded).rows().getFirst());
        assertThrows(JacksonException.class,()->JsonAdapter.read(encoded.substring(0,encoded.length()-1)+",\"f999\":1}"));
    }
}
