package com.mosaicql;

import com.mosaicql.ast.Ast;
import com.mosaicql.plan.QueryCompiler;
import com.mosaicql.runtime.*;
import com.mosaicql.type.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryShapeIntegrationTest {
    private QueryExecutor executor(String json) throws Exception {
        return new QueryExecutor(new InMemorySourceProvider(Map.of("users", JsonAdapter.read(json))));
    }

    private QueryResult run(String query, String json) throws Exception {
        return executor(json).executeResult(MosaicQLParser.parse(query));
    }

    @Test void rootShapeControlsCardinalityAndJson() throws Exception {
        String input = "[{\"id\":2},{\"id\":1}]";
        var object = run("users { * }", input);
        assertEquals(Ast.ResultShape.OBJECT, object.shape());
        assertInstanceOf(Row.class, object.value());
        assertEquals(1, object.rows().size());
        assertEquals("{\"id\":2}", JsonAdapter.write(object));
        assertTrue(JsonAdapter.toJson(object).isObject());

        var array = run("users [{ * }]", input);
        assertEquals(Ast.ResultShape.ARRAY, array.shape());
        assertEquals(array.rows(), array.value());
        assertEquals(input, JsonAdapter.write(array));
        assertTrue(JsonAdapter.toJson(array).isArray());
        assertEquals("[{\"id\":2}]", JsonAdapter.write(run("users [{ * }]", "{\"id\":2}")));
    }

    @Test void objectSelectionFollowsWhereOrderAndLimitIncludingCompiledQueries() throws Exception {
        var executor = executor("[{\"id\":1},{\"id\":3},{\"id\":2}]");
        var compiler = new QueryCompiler(new FunctionRegistry());
        var object = compiler.compile(MosaicQLParser.parse("users { id } where id > 1 order by id desc limit 2"));
        assertEquals("{\"id\":3}", JsonAdapter.write(executor.executeResult(object)));
        var array = compiler.compile(MosaicQLParser.parse("users [{ id }] where id > 1 order by id desc limit 2"));
        assertEquals("[{\"id\":3},{\"id\":2}]", JsonAdapter.write(executor.executeResult(array)));
        assertEquals("null", JsonAdapter.write(executor.executeResult(MosaicQLParser.parse("users { * } limit 0"))));
        assertEquals("[]", JsonAdapter.write(executor.executeResult(MosaicQLParser.parse("users [{ * }] limit 0"))));
    }

    @Test void shapesApplyAfterGroupingAndHandleEmptyGlobalAggregation() throws Exception {
        String input = "[{\"kind\":\"a\"},{\"kind\":\"b\"},{\"kind\":\"b\"}]";
        System.out.println(JsonAdapter.write(run(
                "users { kind, n: count(*) } group by kind order by n desc", input)));
        assertEquals("{\"kind\":\"b\",\"n\":2}", JsonAdapter.write(run(
            "users { kind, n: count(*) } group by kind order by n desc", input)));
        assertEquals("[{\"kind\":\"b\",\"n\":2},{\"kind\":\"a\",\"n\":1}]", JsonAdapter.write(run(
            "users [{ kind, n: count(*) }] group by kind order by n desc", input)));
        assertEquals("{\"n\":0}", JsonAdapter.write(run("users { n: count(*) }", "[]")));
        assertEquals("[{\"n\":0}]", JsonAdapter.write(run("users [{ n: count(*) }]", "[]")));
    }

    @Test void emptyInputAndFilteredResultsHaveDistinctShapes() throws Exception {
        for (String input : List.of("[]", "null", "[{\"id\":1}]")) {
            var object = run("users { * } where false", input);
            assertNull(object.value());
            assertTrue(object.rows().isEmpty());
            assertEquals("null", JsonAdapter.write(object));
            assertEquals("[]", JsonAdapter.write(run("users [{ * }] where false", input)));
        }
    }

    @Test void shapesAreIndependentAtEveryLevelAndDoNotFollowInputShape() throws Exception {
        var result = run("""
            users as u [{
              id,
              profile: u.profile as p { name: p.name },
              profileArray: u.profile [{ * }],
              first: u.orders as o { id } order by id desc,
              orders: u.orders as o [{
                id,
                detail: o.detail { * },
                items: o.items as i [{ code: i.code, owner: u.id }]
              }] order by id,
              missingObject: u.absent { * },
              missingArray: u.absent [{ * }]
            }]
            """, """
            [{"id":1,"profile":{"name":"Ada"},"orders":[
              {"id":20,"detail":{"ok":true},"items":[{"code":"b"},{"code":"c"}]},
              {"id":10,"detail":null,"items":[]}
            ]}]
            """);
        assertEquals("""
            [{"id":1,"profile":{"name":"Ada"},"profileArray":[{"name":"Ada"}],"first":{"id":20},"orders":[{"id":10,"detail":null,"items":[]},{"id":20,"detail":{"ok":true},"items":[{"code":"b","owner":1},{"code":"c","owner":1}]}],"missingObject":null,"missingArray":[]}]
            """.strip(), JsonAdapter.write(result));
    }

    @Test void nullMissingAndEmptyPathSourcesProduceEmptyResults() throws Exception {
        for (String input : List.of("{}", "{\"children\":null}", "{\"children\":[]}")) {
            assertEquals("{\"one\":null,\"many\":[]}", JsonAdapter.write(run(
                "users as u { one: u.children { * }, many: u.children [{ * }] }", input)));
        }
        assertEquals("{\"one\":{},\"many\":[{}]}", JsonAdapter.write(run(
            "users as u { one: u.children { * }, many: u.children [{ * }] }", "{\"children\":{}}")));
    }

    @Test void joinsReadEachLeftRowsObjectOrArrayAndCanChain() throws Exception {
        var result = run("""
            users as u
            join u.profile as p on true
            join u.orders as o on o.enabled
            join o.lines as l on true [{ user_id: u.id, name: p.name, code: l.code }]
            """, """
            [{"id":1,"profile":{"name":"a"},"orders":[
              {"enabled":true,"lines":[{"code":"x"},{"code":"y"}]},
              {"enabled":false,"lines":[{"code":"skip"}]}]},
             {"id":2,"profile":{"name":"b"},"orders":{"enabled":true,"lines":{"code":"z"}}},
             {"id":3,"profile":null,"orders":[]}]
            """);
        assertEquals("[{\"user_id\":1,\"name\":\"a\",\"code\":\"x\"},{\"user_id\":1,\"name\":\"a\",\"code\":\"y\"},{\"user_id\":2,\"name\":\"b\",\"code\":\"z\"}]", JsonAdapter.write(result));
    }

    @Test void localBareReferencesStayLocalInFiltersAndGrouping() throws Exception {
        var result = run("""
            users as u {
              children: u.children as c [{ id, n: count(*) }]
                where id > 1 group by id order by id
            }
            """, "{\"id\":99,\"children\":[{\"id\":1},{\"id\":2},{\"id\":2}]}");
        assertEquals("{\"children\":[{\"id\":2,\"n\":2}]}", JsonAdapter.write(result));
    }

    @Test void supportsRowValuesAndPathsLongerThanFixedLookahead() throws Exception {
        var row = new Row(Map.of("name", "Ada"));
        var executor = new QueryExecutor(name -> new Table(List.of(new Row(Map.of(
            "profile", row, "children", List.of(row), "wrapper", new Row(Map.of("profile", row)))))));
        var result = executor.executeResult(MosaicQLParser.parse("""
            users as u { profile: u.profile { * }, children: u.children [{ * }], wrapped: u.wrapper.profile { * } }
            """));
        assertEquals("{\"profile\":{\"name\":\"Ada\"},\"children\":[{\"name\":\"Ada\"}],\"wrapped\":{\"name\":\"Ada\"}}", JsonAdapter.write(result));
        assertEquals("{\"nested\":[{\"id\":7}]}", JsonAdapter.write(run(
            "users as u { nested: u.a.b.c.d.e.f.g as n [{ * }] }",
            "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":{\"g\":[{\"id\":7}]}}}}}}}")));
    }

    @Test void rejectsScalarPathSourcesAndNonObjectArrayElements() throws Exception {
        for (String input : List.of("{\"x\":1}", "{\"x\":\"text\"}", "{\"x\":[1]}", "{\"x\":[null]}")) {
            var error = assertThrows(IllegalArgumentException.class, () -> run("users as u { x: u.x [{ * }] }", input));
            assertTrue(error.getMessage().contains("u.x"));
        }
        var executor = new QueryExecutor(name -> new Table(List.of(new Row(Map.of("x", Map.of(1, "bad"))))));
        assertThrows(IllegalArgumentException.class, () -> executor.execute(MosaicQLParser.parse("users as u { x: u.x { * } }")));
    }

    @Test void validatesSourceScopesBeforeLoadingData() throws Exception {
        var executor = new QueryExecutor(name -> { fail("Invalid path scopes must be rejected before source access"); return null; });
        for (String query : List.of(
            "u.profile { * }",
            "users as u join later.items as i on true join others as later on true [{ * }]",
            "users as u { x: missing.profile { * } }",
            "users as u { x: if true then missing.profile { * } else null fi }",
            "users as u { id, x: u.children [{ * }] } group by id")) {
            assertThrows(IllegalArgumentException.class, () -> executor.execute(MosaicQLParser.parse(query)), query);
        }
    }

    @Test void arrayBracketsMustBePairedAtEveryLevel() {
        for (String query : List.of("users [{ * }", "users { * }]", "users [ * ]",
            "users [[{ * }]]", "users [{ * }}]", "users { x: child [{ * } }")) {
            assertThrows(ParseException.class, () -> MosaicQLParser.parse(query), query);
        }
    }

    @Test void subqueryShapesWorkInFunctionsConditionsAndAggregateArguments() throws Exception {
        assertEquals("{\"n\":2,\"objectSize\":1,\"choice\":[{\"id\":1},{\"id\":2}],\"fallback\":{\"id\":1}}", JsonAdapter.write(run("""
            users as u {
              n: length((u.children [{ id }])),
              objectSize: length((u.children { id })),
              choice: if true then u.children [{ id }] else u.absent [{ id }] fi,
              fallback: coalesce((u.absent { id }), (u.children { id }))
            }
            """, "{\"children\":[{\"id\":1},{\"id\":2}]}")));
        assertEquals("{\"objects\":1,\"arrays\":2}", JsonAdapter.write(run("""
            users as u { objects: count((u.children { id })), arrays: count((u.children [{ id }])) }
            """, "[{\"children\":[]},{\"children\":[{\"id\":1}]}]")));
    }

    @Test void inferredNestedTypesDistinguishObjectAndArray() throws Exception {
        var query = MosaicQLParser.parse("users { one: children { n: 1 }, many: children [{ n: 1 }] }");
        var compiled = new QueryCompiler(new FunctionRegistry()).compile(query);
        var rowType = new ObjectType(Map.of("n", MType.NUMBER));
        assertEquals(rowType, compiled.projection().get(0).type());
        assertEquals(new ArrayType(rowType), compiled.projection().get(1).type());
        assertEquals(rowType, TypeInferencer.runtime(new Row(Map.of("n", 1))));
    }

    @Test void resultRowsAreImmutableAndOnlyContainSelectedRows() {
        var input = new ArrayList<>(List.of(new Row(Map.of("id", 1)), new Row(Map.of("id", 2))));
        var array = new QueryResult(Ast.ResultShape.ARRAY, input);
        var object = new QueryResult(Ast.ResultShape.OBJECT, input);
        input.clear();
        assertEquals(2, array.rows().size());
        assertEquals(1, object.rows().size());
        assertThrows(UnsupportedOperationException.class, () -> array.rows().clear());
    }
}
