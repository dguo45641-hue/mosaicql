package com.mosaicql;

import com.mosaicql.ast.Ast;
import org.junit.jupiter.api.Test;

import com.mosaicql.runtime.InMemorySourceProvider;
import com.mosaicql.runtime.QueryExecutor;
import com.mosaicql.runtime.Row;
import com.mosaicql.runtime.Table;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MosaicQLParserTest {
  @Test void parsesNestedProjectionAndTrailingWhere() throws Exception {
    Ast.Query query = new MosaicQLParser(new java.io.StringReader("""
      users as u {
        id,
        "name": u.name,
        "score": sum(u.amount) + 100,
        "orders": orders as o { id, amount } where o.user_id = u.id
      } where u.age >= 18 and u.enabled = true
      """)).parse();

    assertEquals("users", query.source().name());
    assertEquals("u", query.source().alias());
    assertEquals(4, query.projection().fields().size());
    var orders = assertInstanceOf(Ast.NestedQuery.class, query.projection().fields().get(3).value());
    assertInstanceOf(Ast.Binary.class, orders.query().where());
    assertInstanceOf(Ast.Binary.class, query.where());
  }

  @Test void parsesJoinAndOperatorPrecedence() throws Exception {
    var query = new MosaicQLParser(new java.io.StringReader("""
      users as u
      join accounts as a on u.account_id = a.id
      {
        "id": u.id,
        "ok": u.age >= 18 and (a.active = true or u.vip = true)
      }
      """)).parse();

    assertEquals(1, query.joins().size());
    assertEquals("accounts", query.joins().getFirst().source().name());
    assertEquals("a", query.joins().getFirst().source().alias());
    assertInstanceOf(Ast.Binary.class, query.joins().getFirst().on());
  }

  @Test void supportsShortAliasCommentsTrailingCommaAndEscapedString() throws Exception {
    var query = MosaicQLParser.parse("""
      // select active users
      users(u) {
        id,
        "label": "a\\\"b",
      } where u.active = true
      """);

    assertEquals("u", query.source().alias());
    assertEquals("a\"b", ((Ast.Literal) query.projection().fields().get(1).value()).value());
  }

  @Test void rejectsMissingProjection() {
    assertThrows(ParseException.class, () -> MosaicQLParser.parse("users where active = true"));
  }

  @Test void parsesMultipleNestedLevels() throws Exception {
    var query = MosaicQLParser.parse("""
      companies {
        "departments": departments {
          "teams": teams {
            name
          }
        }
      }
      """);

    var departments = (Ast.NestedQuery) query.projection().fields().getFirst().value();
    var teams = (Ast.NestedQuery) departments.query().projection().fields().getFirst().value();
    assertEquals("teams", teams.query().source().name());
    assertEquals("name", teams.query().projection().fields().getFirst().name());
  }

  @Test void validatesNestedParentScope() throws Exception {
    var query = MosaicQLParser.parse("""
      users as u {
        "orders": orders as o { id } where o.user_id = u.id
      }
      """);

    assertDoesNotThrow(() -> SemanticValidator.validate(query));
  }

  @Test void rejectsUnknownAlias() throws Exception {
    var query = MosaicQLParser.parse("users { id } where x.active = true");
    var error = assertThrows(IllegalArgumentException.class, () -> SemanticValidator.validate(query));
    assertTrue(error.getMessage().contains("x"));
  }

  @Test void preservesNestedFieldPath() throws Exception {
    var query = MosaicQLParser.parse("users as u { \"city\": u.profile.address.city }");
    var reference = (Ast.Reference) query.projection().fields().getFirst().value();
    assertEquals("u", reference.qualifier());
    assertEquals(java.util.List.of("profile", "address", "city"), reference.path());
    assertEquals("city", reference.name());
  }

  @Test void executesFilteringProjectionAndNestedQuery() throws Exception {
    var query = MosaicQLParser.parse("""
      users as u {
        "id": u.id,
        "orders": orders as o [{ "amount": o.amount }] where o.user_id = u.id
      } where u.active = true
      """);
    var provider = new InMemorySourceProvider(Map.of(
        "users", new Table(List.of(
            new Row(Map.of("id", 1L, "active", true)),
            new Row(Map.of("id", 2L, "active", false)))),
        "orders", new Table(List.of(
            new Row(Map.of("user_id", 1L, "amount", 12L)),
            new Row(Map.of("user_id", 2L, "amount", 20L))))));

    var result = new QueryExecutor(provider).execute(query);
    assertEquals(1, result.rows().size());
    assertEquals(1L, result.rows().getFirst().values().get("id"));
    var orders = (List<?>) result.rows().getFirst().values().get("orders");
    assertEquals(1, orders.size());
    assertEquals(12L, ((Row) orders.getFirst()).values().get("amount"));
  }

  @Test void keepsNullValuesAndResolvesNestedBareFieldsLocally() throws Exception {
    var query = MosaicQLParser.parse("""
      users as u {
        "name": u.name,
        "orders": orders as o [{ "id": id }] where user_id = u.id
      }
      """);
    var user = new java.util.LinkedHashMap<String, Object>();
    user.put("id", 1);
    user.put("name", null);
    var provider = new InMemorySourceProvider(Map.of(
        "users", new Table(List.of(new Row(user))),
        "orders", new Table(List.of(new Row(Map.of("id", 9, "user_id", 1))))));

    var result = new QueryExecutor(provider).execute(query);
    assertNull(result.rows().getFirst().values().get("name"));
    var nested = (List<?>) result.rows().getFirst().values().get("orders");
    assertEquals(9, ((Row) nested.getFirst()).values().get("id"));
  }
}
