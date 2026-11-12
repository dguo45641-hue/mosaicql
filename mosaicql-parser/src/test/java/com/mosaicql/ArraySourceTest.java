package com.mosaicql;

import com.mosaicql.runtime.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ArraySourceTest {
    @Test void queriesArrayPathFromCurrentRow() throws Exception {
        var orders = List.of(Map.of("id", 1, "amount", 10), Map.of("id", 2, "amount", 20));
        var user = new LinkedHashMap<String,Object>(); user.put("id", 7); user.put("orders", orders);
        var query = MosaicQLParser.parse("users as u { orders: u.orders as o [{ o.id, o.amount }] }");
        var result = new QueryExecutor(new InMemorySourceProvider(Map.of("users", new Table(List.of(new Row(user)))))).execute(query);
        assertEquals(2, ((List<?>) result.rows().getFirst().values().get("orders")).size());
    }
}
