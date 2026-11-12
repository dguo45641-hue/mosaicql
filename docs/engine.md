# 执行引擎与 Java API

当前语言规范版本：`0.1`，对应 `MosaicQL.VERSION`。

MosaicQL 是面向 JSON、接口返回值、数据库行和嵌套对象的查询语言。当前模块包含 JavaCC 解析器、AST、类型推断、编译绑定、解释器和内存执行器。

## 结果形态

查询块决定结果是对象还是数组：

```mosaicql
users {
  *
}
```

`{}` 是对象结果。执行多行时，经过 where、group by、order by、limit 后取第一条；没有结果返回 `null`。

```mosaicql
users [{
  *
}]
```

`[{}]` 是数组结果，保留全部结果；没有结果返回 `[]`。这个规则对每一层嵌套查询独立生效：

```mosaicql
users as u [{
  profile: u.profile { * },
  orders: u.orders [{ id, amount }]
}]
```

Java API：

```java
QueryResult result = new QueryExecutor(provider)
    .executeResult(MosaicQLParser.parse("users [{ id }]"));
Object value = result.value(); // Row、List<Row> 或 null
String json = JsonAdapter.write(result);
```

大结果可以直接写入调用方管理的输出流，避免额外构造 JSON 树和完整输出字符串：

```java
JsonAdapter.write(result, outputStream); // 不关闭调用方的流；Writer 也有对应重载
```

`write()` 使用 Jackson 流式生成器；需要 JSON 树时仍可显式调用 `toJson()`。结果中的行仍然是已物化的，流式输出只消除序列化阶段的第二份 JSON 树。输出包含不支持的值时会抛出错误，直接写外部流可能已经写出部分内容。

执行器也提供输出端流式执行，适合预编译的网关响应转换：

```java
CompiledQuery plan = MosaicQL.compile("users [{ id, name: upper(name) }]", functions);
executor.executeTo(plan, outputStream); // Writer 也支持；不关闭调用方目标
long emitted = executor.forEach(plan, row -> consume(row));
System.out.println(plan.explain()); // 查看绑定计划和各优化的结构适用条件
```

默认解释器的纯投影、过滤通过同一循环逐行计算并写入 JSON，不建立完整输出行列表或输出字符串。输入 `Table` 仍由数据源持有；排序、聚合保留必要的算子状态。未知副作用函数、自定义解释器或嵌套查询的 JSON 输出保留物化路径，避免改变可变结果在序列化时的值。`forEach()` 明确定义为逐行回调，回调失败会立即停止。流式输出已写入的数据不能回滚，调用方需要处理写出后的异常和取消。

当前依赖 Jackson **3.3.0**。`JsonAdapter.read(String)` 与 `read(byte[])` 直接解析到行模型，不建立中间 JSON 树；所有输入数字仍保持 `BigDecimal` 精度，拒绝重复字段与尾随 JSON 值。`write()` 的字符串输出使用 Jackson 管理的分段缓冲。显式树 API 使用 `tools.jackson.databind.JsonNode`，从 Jackson 2 升级的调用方需要调整节点类型和异常捕获：`JacksonException` 位于 `tools.jackson.core`，并继承 `RuntimeException`。`String`、`Row`、`Table`、`QueryResult` 的调用形态保留；Jackson 2 的节点类型不能传入新树 API。

`result.rows()` 保留执行后的行，便于测试和内部处理；对象结果最多包含一行。

没有排序、分组和聚合时，`executeResult()` 的对象结果在首条匹配后停止，数组结果在满足 `limit` 后停止。未扫描的后续行不会执行过滤、投影或 UDF，也不会触发其中的类型错误；嵌套数组只转换实际读取的元素。`limit 0` 在编译校验完成后直接返回空结果，不读取数据源。

默认解释器下，单数据源的有限 Top-K 查询，如果投影和排序只包含安全的顶层字段引用或常量，会先保留排序键与行引用，再投影获胜行；同键按输入顺序返回，行预算仍统计全部候选。其他排序查询执行全部投影，继续使用 JDK `PriorityQueue` 保留最佳候选。比较错误在扫描完成后抛出，后续过滤或预算错误仍保持原优先级。分组和聚合读取全部匹配输入，没有排序时只输出需要返回的分组。旧 API `execute()` 返回 `Table`，根查询的 `{}` 不隐含单行限制，显式 `limit` 仍生效；子查询独立遵守自己的结果形态。

对于默认解释器，分组键、聚合参数、过滤、连接条件、投影和排序满足纯表达式条件时，查询采用在线累加器，支持 `where`、JOIN、计算参数及组合聚合表达式。相同参数的 SUM/AVG/COUNT/MIN/MAX 共享一次求值和累计状态。分组键错误延后到扫描完成；聚合错误按原分组和字段位置暴露，未返回分组或未选择 CASE/COALESCE 分支中的错误不提前抛出。未知自定义函数或不安全表达式保留通用路径，避免投影函数修改后续分组数据时改变结果。

基本的独立字段聚合保留原专用快速循环；存在重复参数、计算表达式、过滤或 JOIN 时再使用共享状态管线，避免通用算子额外增加简单查询的成本。

纯等值 INNER/LEFT JOIN 后仅 `count(*)` 时使用频次表，支持复合等值键和附加 `and true`。纯查询的普通 JOIN 也可用安全的连续前缀等值项建立复合键索引，完整 ON 仍执行；未知副作用函数存在时保留原单键路径，避免其修改后续嵌套键时漏掉匹配。单列整数键自适应使用 fastutil `Long2LongOpenHashMap` / `Long2ObjectOpenHashMap`；混合键、分数或超出 long 范围的数值使用对象索引，保持精确比较以及 null 匹配规则。

## 绑定计划与固定 Schema

编译结果包含只读的绑定求值程序、函数定义快照、别名位置、输出列布局和算法适用条件。扫描、过滤、简单投影可以融合执行；每次请求独立持有输入行、JOIN 索引、聚合状态及资源预算。当前实现是预绑定算子程序，没有动态生成 Java 源码或字节码。普通 `CompiledQuery` 构造器和自定义 `ExpressionInterpreter` 继续支持通用执行路径。

函数注册表应在发布计划前准备好。执行器检查注册表身份与版本；其他注册表或新增重载触发重新绑定，缓存属于执行器且有容量限制。默认函数中依赖进程 Locale 的 `format_date` 不视为纯函数。扩展函数可以显式声明纯契约：

```java
functions.registerPure("twice", 1, args -> args.getFirst() == null ? null
    : new BigDecimal(args.getFirst().toString()).multiply(BigDecimal.valueOf(2)));
```

`registerPure` 要求相同输入得到确定结果、不修改外部或输入数据、不依赖可变外部状态；允许抛异常。这个声明允许共享聚合参数求值及安全的流式 JSON 执行。普通 `register` 的函数继续使用保守规则。

可显式提供 Schema 和共享行布局，减少宽表字段查找：

```java
TableDefinition schema = new TableDefinition("users", List.of(
    new ColumnDefinition("id", MType.NUMBER, true),
    new ColumnDefinition("name", MType.STRING, true)));
RowLayout layout = RowLayout.of(schema);
Table input = JsonAdapter.read(json, layout);
CompiledQuery plan = MosaicQL.compile("users [{ id, name: upper(name) }]",
    functions, Map.of("users", schema));
Row row = Row.of(layout, 1, "alice"); // 复制调用方值数组，共享不可变布局
```

固定布局的 JSON 读取按字段名填槽，接受任意字段顺序，缺失字段为 null，未声明字段和重复字段报错。布局不提前验证运行时值类型；执行时保留原类型检查。编译槽位有字段名校验，遇到其他布局或普通 Map 时按名称回退。普通 `JsonAdapter.read(json)` 仍保留原有动态字段与缺失字段行为。

## 常用语法

```mosaicql
sales [{
  country,
  total: sum(amount),
  rows: count(*)
}] where active
  group by country
  order by total desc
  limit 10
```

输出字段别名既可以使用标识符，也可以使用字符串：

```mosaicql
users [{
  display_name: upper(name),
  "Display Name": upper(name)
}]
```

`*` 展开当前查询的本地 source 和 join 字段；同名字段会报错。

## 嵌套 JSON

点路径可以作为字段引用，也可以作为查询数据源：

```mosaicql
users as u {
  profile: u.profile { name },
  orders: u.orders [{ id, amount }]
}
```

路径源规则：

```text
Object       → 单行 Table
Array<Object>→ 多行 Table
null         → 空 Table
标量         → 类型错误
```

路径也可以作为 join 源，并且会对每一条左侧行重新读取：

```mosaicql
users as u
join u.orders as o on o.enabled [{
  user_id: u.id,
  order_id: o.id
}]
```

## 语言能力

- source alias、点路径、相关嵌套查询；
- `join` / `inner join`、`left [outer] join`、`right [outer] join`；
- where、group by、order by、limit；
- 聚合函数和类型化 UDF；
- `CASE WHEN ... THEN ... ELSE ... END`；
- `if ... then ... else if ... then ... else ... fi`；
- `NULL`、`UNKNOWN`、`BOOLEAN`、`NUMBER`、`STRING`、`DATE`、`TIMESTAMP`、数组和对象类型；
- `date`、`timestamp`、`year`、`month`、`day`、`date_add`、`date_diff`、`format_date`；
- JSON 输入输出适配。

## 执行架构

```text
文本 → AST → QueryCompiler
     → 语义校验 / 函数签名 / 类型推断 / 符号绑定
     → CompiledQuery / TypedExpression
     → QueryExecutor
     → Scan / Join / Filter / Group / Project / Sort / Limit
     → ExpressionInterpreter
```

`QueryCompiler` 同时编译嵌套查询。重复执行时可以复用同一个编译结果，子查询仍使用当前父行计算自己的结果：

```java
var compiled = MosaicQL.compile("users as u [{ id, orders: u.orders [{ id, amount }] }]", functions);
var executor = new QueryExecutor(provider, functions);
QueryResult result = executor.executeResult(compiled);
```

普通投影和过滤逐行执行，JOIN 不再物化全部中间组合。使用默认解释器时，独立数据源之间的 INNER/LEFT JOIN 简单等值条件（例如 `u.id = o.user_id`，两侧都明确带别名），以及以该等值比较开头的 AND 条件（例如 `u.id = o.user_id and o.enabled`），使用哈希连接。索引只缩小候选范围，完整 `on` 仍会执行；左侧输入顺序、右侧匹配顺序、重复行和短路行为保持不变。数字按现有数值相等规则匹配，字符串不会转换为数字，null 与 null 仍可匹配。函数前缀、OR、RIGHT JOIN、上下文路径 JOIN、自定义解释器和不适合索引的数值继续逐行判断，避免重排条件改变错误或函数执行顺序。哈希连接需要读取右侧数据并建立索引，即使最终只需要一条结果。

索引和分组使用 `fastutil-core` 的开放寻址 Map，分组保持首次出现顺序。没有通配符的投影使用共享字段名数组和数组式 Map，外部 `Row` 构造仍防御性复制，投影结果的 Map、Entry、key/value 视图保持只读。函数参数使用支持 null 的 fastutil 不可变 List。

LEFT JOIN 保留未匹配的左行，RIGHT JOIN 保留未匹配的右行，缺失一侧的显式字段引用返回 null。`on` 决定是否匹配，`where` 在连接后过滤；`left join u.orders` 支持上下文对象和数组路径，`right join` 的右侧必须是独立数据源。RIGHT JOIN 先输出匹配行，再按右侧输入顺序补出未匹配行；补行前必须扫描完整左侧输入。`*` 只展开实际存在的行字段，不为缺失一侧推断字段名。

```mosaicql
users as u left join orders as o on u.id = o.user_id [{
  user_id: u.id,
  order_id: o.id
}]
```

## 当前边界

暂不支持 full join、distinct、window function、having、union/intersect/except、simple case、JDBC/XML/HTML 连接器、查询优化器和完整 SQL 隐式转换体系。

执行限制通过 `ExecutionOptions` 设置：超时、最大产生行数、最大嵌套深度；执行线程被中断时查询会抛出取消错误。`maxRows` 统计本次执行实际投影产生的行，包含嵌套结果和排序后被丢弃的候选行；不计未投影的输入、JOIN 候选或提前停止后的行。默认超时和最大行数关闭，最大嵌套深度为 64。

## 验证

```shell
mvn clean test
```
