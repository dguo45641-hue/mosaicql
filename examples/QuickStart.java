import com.mosaicql.MosaicQL;
import com.mosaicql.runtime.ExecutionOptions;
import com.mosaicql.runtime.FunctionRegistry;
import com.mosaicql.runtime.InMemorySourceProvider;
import com.mosaicql.runtime.JsonAdapter;

import java.util.Map;

public class QuickStart {
    public static void main(String[] args) {
        var orders = JsonAdapter.read("""
                [
                  {"id":1,"amount":18,"active":true},
                  {"id":2,"amount":39.5,"active":true},
                  {"id":3,"amount":99,"active":false}
                ]
                """);
        var sources = new InMemorySourceProvider(Map.of("orders", orders));
        var result = MosaicQL.execute(
                "orders [{ id, amount }] where active order by amount desc",
                sources, new FunctionRegistry(), new ExecutionOptions(1000, 100, 16));
        System.out.println(JsonAdapter.write(result));
    }
}
