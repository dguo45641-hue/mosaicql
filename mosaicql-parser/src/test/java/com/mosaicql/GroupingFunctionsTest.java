package com.mosaicql;

import com.mosaicql.runtime.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GroupingFunctionsTest {
  private Map<String, Object> map(Object... entries) {
    var result = new java.util.LinkedHashMap<String, Object>();
    for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
    return result;
  }
  @SafeVarargs
  private final Table table(Map<String,Object>... rows) { return new Table(java.util.Arrays.stream(rows).map(Row::new).toList()); }

  @Test void groupsAfterWhereAndComputesAggregates() throws Exception {
    var q = MosaicQLParser.parse("sales { country, \"total\": sum(amount), \"avg\": avg(amount), \"rows\": count(*) } where active group by country");
    var r = new QueryExecutor(new InMemorySourceProvider(Map.of("sales", table(
      Map.of("country","CN","amount",10,"active",true), Map.of("country","CN","amount",20,"active",true),
      map("country","CN","amount",null,"active",true), Map.of("country","US","amount",99,"active",false))))).execute(q);
    assertEquals(List.of(new Row(Map.of("country","CN","total",new BigDecimal("30"),"avg",new BigDecimal("15"),"rows",3L))), r.rows());
  }

  @Test void globalAggregateAndEmptyInput() throws Exception {
    var text = "sales { \"total\": sum(amount), \"rows\": count(*), \"avg\": avg(amount) }";
    var r = new QueryExecutor(new InMemorySourceProvider(Map.of("sales", table(Map.of("amount",10), Map.of("amount",20), map("amount",null)))))
      .execute(MosaicQLParser.parse(text));
    assertEquals(List.of(new Row(Map.of("total",new BigDecimal("30"),"rows",3L,"avg",new BigDecimal("15")))), r.rows());
    var empty = new QueryExecutor(new InMemorySourceProvider(Map.of("sales", new Table(List.of())))).executeResult(MosaicQLParser.parse(text));
    assertEquals(List.of(new Row(map("total",null,"rows",0L,"avg",null))), empty.rows());
  }

  @Test void groupedProjectionRequiresPlainFieldsInGroupBy() {
    assertThrows(IllegalArgumentException.class, () -> new QueryExecutor(new InMemorySourceProvider(Map.of("sales", table(Map.of("country","CN","amount",1)))))
      .execute(MosaicQLParser.parse("sales { country, amount, \"total\": sum(amount) } group by country")));
  }

  @Test void builtInsAndUdfWork() throws Exception {
    var registry = new FunctionRegistry().register("slug", 1, a -> ((String)a.getFirst()).toLowerCase().replace(' ','-'));
    var q = MosaicQLParser.parse("people { \"slug\": slug(trim(name)), \"fallback\": coalesce(nickname, name), \"size\": length(name), \"rounded\": round(score, 1) }");
    var r = new QueryExecutor(new InMemorySourceProvider(Map.of("people", table(map("name"," Alice ","nickname",null,"score",1.25)))), registry).execute(q);
    assertEquals("alice", r.rows().getFirst().values().get("slug"));
    assertEquals(" Alice ", r.rows().getFirst().values().get("fallback"));
    assertEquals(new BigDecimal("7"), r.rows().getFirst().values().get("size"));
    assertEquals(new BigDecimal("1.3"), r.rows().getFirst().values().get("rounded"));
  }

  private QueryResult run(String text, Table data) throws ParseException {
    return new QueryExecutor(new InMemorySourceProvider(Map.of("t", data))).executeResult(MosaicQLParser.parse(text));
  }

  private QueryResult run(String text, Map<String, Table> sources) throws ParseException {
    return new QueryExecutor(new InMemorySourceProvider(sources)).executeResult(MosaicQLParser.parse(text));
  }

  @Test void normalizesNumericKeysAndGroupsNullsInFirstSeenOrder() throws Exception {
    var result = run("t [{ k, \"n\": count(*) }] group by k", table(
      map("k", 1), map("k", 1L), map("k", new BigDecimal("1.00")),
      map("k", null), map("k", null), map("k", 2)));
    assertEquals(List.of(new Row(map("k", 1, "n", 3L)),
      new Row(map("k", null, "n", 2L)), new Row(map("k", 2, "n", 1L))), result.rows());
  }

  @Test void supportsMultipleAndComputedGroupKeysWithoutAggregates() throws Exception {
    var result = run("""
      t as s [{ "country": lower(s.country), city }]
      group by (lower(country), city)
      """, table(map("country", "CN", "city", "A"), map("country", "cn", "city", "A"),
        map("country", "CN", "city", "B")));
    assertEquals(List.of(new Row(map("country", "cn", "city", "A")),
      new Row(map("country", "cn", "city", "B"))), result.rows());
  }

  @Test void explicitGroupsOnEmptyInputReturnNoRowsButGlobalAggregateReturnsOne() throws Exception {
    assertTrue(run("t [{ k, \"n\": count(*) }] group by k", new Table(List.of())).rows().isEmpty());
    var result = run("""
      t { "n": count(*), "c": count(x), "s": sum(x), "a": avg(x), "lo": min(x), "hi": max(x) } where false
      """, table(map("x", 1)));
    assertEquals(List.of(new Row(map("n", 0L, "c", 0L, "s", null, "a", null, "lo", null, "hi", null))), result.rows());
  }

  @Test void ignoresNullsAndSupportsNumericAndStringExtrema() throws Exception {
    var result = run("""
      t { "c": count(x), "lo": min(x), "hi": max(x), "first": min(label), "last": max(label) }
      """, table(map("x", 10, "label", "z"), map("x", null, "label", null), map("x", 20, "label", "a")));
    assertEquals(map("c", 2L, "lo", new BigDecimal("10"), "hi", new BigDecimal("20"),
      "first", "a", "last", "z"), result.rows().getFirst().values());
    var nulls = run("t { \"n\": count(x), \"s\": sum(x) }", table(map("x", null)));
    assertEquals(map("n", 0L, "s", null), nulls.rows().getFirst().values());
    assertThrows(IllegalArgumentException.class, () -> run("t { \"v\": min(x) }", table(map("x", true))));
  }

  @Test void nestedAggregatesDoNotAggregateTheOuterQuery() throws Exception {
    var executor = new QueryExecutor(new InMemorySourceProvider(Map.of(
      "users", table(map("id", 1), map("id", 2)),
      "sales", table(map("owner", 1L, "amount", 4), map("owner", 1, "amount", 6)))));
    var result = executor.execute(MosaicQLParser.parse("""
      users as u [{
        id,
        "totals": sales as s [{ "n": count(*), "sum": sum(amount) }] where s.owner = u.id,
        "marker": true
      }]
      """));
    assertEquals(2, result.rows().size());
    assertEquals(List.of(new Row(map("n", 2L, "sum", new BigDecimal("10")))), result.rows().getFirst().values().get("totals"));
    assertEquals(List.of(new Row(map("n", 0L, "sum", null))), result.rows().getLast().values().get("totals"));
  }

  @Test void nestedGroupKeysStopBeforeNextOuterField() throws Exception {
    var executor = new QueryExecutor(new InMemorySourceProvider(Map.of(
      "users", table(map("country", "CN"), map("country", "CN")),
      "sales", table(map("country", "CN", "kind", "book", "amount", 2),
        map("country", "CN", "kind", "book", "amount", 3)))));
    var result = executor.execute(MosaicQLParser.parse("""
      users as u {
        country,
        "sales": sales as s [{ kind, "total": sum(amount) }]
          where s.country = u.country group by (kind),
        "marker": true
      } group by country
      """));
    assertEquals(List.of(new Row(map("country", "CN",
      "sales", List.of(new Row(map("kind", "book", "total", new BigDecimal("5")))), "marker", true))), result.rows());
    // A single bare key also stops before the next projection field.
    var query = MosaicQLParser.parse("users { \"items\": sales { kind } group by kind, country }");
    assertEquals(2, query.projection().fields().size());
  }

  @Test void appliesJoinAndWhereBeforeGrouping() throws Exception {
    var executor = new QueryExecutor(new InMemorySourceProvider(Map.of(
      "a", table(map("id", 1, "country", "CN"), map("id", 2, "country", "CN")),
      "b", table(map("owner", 1, "amount", 4), map("owner", 1, "amount", 7), map("owner", 2, "amount", 20)))));
    var result = executor.execute(MosaicQLParser.parse("""
      a join b on a.id = b.owner {
        a.country,
        "total": sum(b.amount),
        "n": count(*)
      } where b.amount < 10 group by (a.country)
      """));
    assertEquals(map("country", "CN", "total", new BigDecimal("11"), "n", 2L), result.rows().getFirst().values());
  }

  @Test void rejectsIllegalAggregateUseAndArityBeforeReadingSources() throws Exception {
    var executor = new QueryExecutor(name -> { fail("Validation must precede source access"); return null; });
    for (var text : List.of(
      "t { x, \"n\": count(*) }",
      "t { \"n\": sum(sum(x)) }",
      "t { \"n\": count(*) } where sum(x) > 1",
      "t join s on count(*) > 0 { \"n\": count(*) }",
      "t { \"n\": count(*) } group by (sum(x))",
      "t { \"n\": sum(*) }",
      "t { \"n\": count() }",
      "t { \"n\": count(x, x) }",
      "t { \"n\": lower(x, x) }",
      "t { \"n\": round() }",
      "t { \"n\": coalesce() }",
      "t { \"n\": concat() }",
      "t { \"n\": sum(x), \"items\": s { \"leak\": t.id } }",
      "t { \"items\": s { id } where s.id = t.id } group by (t.country)")) {
      var query = MosaicQLParser.parse(text);
      assertThrows(IllegalArgumentException.class, () -> executor.execute(query), text);
    }
  }

  @Test void scalarBuiltInsComposeWithAggregatesAndCoalesceIsLazy() throws Exception {
    var result = run("""
      t {
        "label": concat(upper(trim(" cn ")), "-", lower("ABC"), null),
        "length": length("汉😀"),
        "nullLength": length(null),
        "value": coalesce(null, round(abs(sum(amount)), 1), 1 / 0),
        "round": round(12.5),
        "empty": coalesce(null, null)
      }
      """, table(map("amount", -1.25), map("amount", -2)));
    assertEquals(map("label", "CN-abc", "length", new BigDecimal("2"), "nullLength", null,
      "value", new BigDecimal("3.3"), "round", new BigDecimal("13"), "empty", null),
      result.rows().getFirst().values());
  }

  @Test void udfWorksInWhereGroupingAndAggregateArgumentsAndAcceptsNull() throws Exception {
    var registry = new FunctionRegistry()
      .register("bucket", 1, args -> ((String) args.getFirst()).toLowerCase(java.util.Locale.ROOT))
      .register("twice", 1, args -> new BigDecimal(args.getFirst().toString()).multiply(new BigDecimal("2")))
      .register("keep", 1, args -> args.getFirst() != null)
      .register("nullable", 1, args -> null);
    var executor = new QueryExecutor(new InMemorySourceProvider(Map.of("t", table(
      map("country", "CN", "amount", 2), map("country", "cn", "amount", 3),
      map("country", "US", "amount", null)))), registry);
    var result = executor.execute(MosaicQLParser.parse("""
      t { "country": BUCKET(country), "sum": sum(twice(amount)), "nil": nullable(null) }
      where keep(amount) group by (bucket(country))
      """));
    assertEquals(map("country", "cn", "sum", new BigDecimal("10"), "nil", null), result.rows().getFirst().values());
    var query = MosaicQLParser.parse("t { \"v\": twice() }");
    assertThrows(IllegalArgumentException.class, () -> executor.execute(query));
  }

  @Test void protectsFunctionRegistrationAndReportsUdfFailures() throws Exception {
    var registry = new FunctionRegistry();
    assertThrows(IllegalArgumentException.class, () -> registry.register("SUM", 1, a -> null));
    assertThrows(IllegalArgumentException.class, () -> registry.register("lower", 1, a -> null));
    assertThrows(IllegalArgumentException.class, () -> registry.register("where", 1, a -> null));
    registry.register("custom", 1, a -> null);
    assertThrows(IllegalArgumentException.class, () -> registry.register("CUSTOM", 1, a -> null));
    registry.register("broken", 0, a -> { throw new IllegalStateException("boom"); });
    var executor = new QueryExecutor(new InMemorySourceProvider(Map.of("t", table(map()))), registry);
    var error = assertThrows(IllegalArgumentException.class, () -> executor.execute(MosaicQLParser.parse("t { \"v\": broken() }")));
    assertTrue(error.getMessage().contains("broken"));
    assertInstanceOf(IllegalStateException.class, error.getCause());
  }

  @Test void rejectsMalformedGroupListsAndUnsupportedGroupValueTypes() {
    for (var text : List.of("t { id } group by", "t { id } group by ()",
      "t { id } group by (id,)", "t { id } group by id, other")) {
      assertThrows(ParseException.class, () -> MosaicQLParser.parse(text), text);
    }
    assertThrows(IllegalArgumentException.class, () -> run("t { x } group by x", table(map("x", List.of(1)))));
  }

  @Test void orderAndLimitAreAppliedAfterProjection() throws Exception {
    var query = MosaicQLParser.parse("t [{ \"name\": name, \"score\": score }] order by score desc, name asc limit 2");
    var result = new QueryExecutor(new InMemorySourceProvider(Map.of("t", table(
      map("name", "b", "score", 2), map("name", "a", "score", 2), map("name", "c", "score", 3), map("name", "d", "score", 1))))).execute(query);
    assertEquals(List.of(new Row(map("name", "c", "score", 3)), new Row(map("name", "a", "score", 2))), result.rows());
  }

  @Test void orderByCanUseAggregateExpressionsAndLimitZero() throws Exception {
    var query = MosaicQLParser.parse("t [{ k, \"n\": count(*) }] group by k order by n desc limit 1");
    var result = new QueryExecutor(new InMemorySourceProvider(Map.of("t", table(
      map("k", "a"), map("k", "a"), map("k", "b"))))).execute(query);
    assertEquals(List.of(new Row(map("k", "a", "n", 2L))), result.rows());
    assertTrue(new QueryExecutor(new InMemorySourceProvider(Map.of("t", table(map("k", "a")))))
      .execute(MosaicQLParser.parse("t [{ k }] order by k limit 0")).rows().isEmpty());
  }

  @Test void orderByNullsSortFirstAscendingAndLastDescending() throws Exception {
    var text = "t [{ k }] order by k";
    var sources = Map.of("t", table(map("k", 2), map("k", null), map("k", 1)));
    assertEquals(java.util.Arrays.asList(null, 1, 2), run(text, sources).rows().stream().map(row -> row.values().get("k")).toList());
    assertEquals(java.util.Arrays.asList(2, 1, null), run("t [{ k }] order by k desc", sources).rows().stream().map(row -> row.values().get("k")).toList());
  }

  @Test void jsonAdapterReadsRootObjectAndArrayAndWritesNestedValues() throws Exception {
    var object = JsonAdapter.read("{\"id\":1,\"profile\":{\"city\":\"Shanghai\"}}");
    assertEquals(1, object.rows().size());
    assertEquals(new BigDecimal("1"), object.rows().getFirst().values().get("id"));
    var array = JsonAdapter.read("[{\"id\":1},{\"id\":2}]");
    assertEquals(2, array.rows().size());
    assertEquals("[{\"id\":1},{\"id\":2}]", JsonAdapter.write(array));
    var scalar = JsonAdapter.read("[1, null, \"x\"]");
    assertEquals(java.util.Arrays.asList(new Row(map("value", new BigDecimal("1"))), new Row(map("value", null)), new Row(map("value", "x"))), scalar.rows());
  }

  @Test void jsonAdapterPreservesArraysObjectsAndNullsThroughQuery() throws Exception {
    var input = JsonAdapter.read("[{\"id\":1,\"tags\":[\"a\",\"b\"],\"meta\":{\"ok\":true},\"empty\":null}]");
    var output = new QueryExecutor(new InMemorySourceProvider(Map.of("input", input))).execute(MosaicQLParser.parse("input [{ id, tags, meta, empty }]"));
    assertEquals("[{\"id\":1,\"tags\":[\"a\",\"b\"],\"meta\":{\"ok\":true},\"empty\":null}]", JsonAdapter.write(output));
  }

  @Test void jsonAdapterRejectsMalformedJson() {
    assertThrows(tools.jackson.core.JacksonException.class, () -> JsonAdapter.read("{bad"));
  }
}
