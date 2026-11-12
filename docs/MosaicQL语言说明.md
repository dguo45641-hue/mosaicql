# MosaicQL 语言说明

当前语言规范版本：`0.1`，对应 Java API 常量 `MosaicQL.VERSION`。

MosaicQL 是面向异构数据的嵌套查询语言。JSON、接口返回值、数据库行、XML/HTML 提取结果都可统一看作 `Table = List<Row>`，其中 `Row = Map<String,Object>`。每个查询块还显式声明自己的结果形态。

## 查询结构

```mosaicql
source [join_clause]* { projection }
source [join_clause]* [{ projection }]
  [where expression]
  [group by expression]
  [order by expression [asc|desc]]
  [limit integer]

join_clause := [inner | left [outer] | right [outer]] join source on expression
```

示例：

```mosaicql
sales {
  country,
  "total": sum(amount),
  "rows": count(*)
} where active group by country order by total desc limit 10
```

支持 source alias、点路径、对象/数组结果形态、嵌套查询和相关引用：

```mosaicql
users as u [{
  "profile": u.profile { name },
  "orders": u.orders as o [{ o.id, "owner": u.id }]
    where o.user_id = u.id
}]
```

JOIN 支持 INNER、LEFT、RIGHT，按书写顺序执行；省略类型的 `join` 等同于 `inner join`。`{}` 表示对象结果，多条只取经过过滤、分组、排序和 limit 后的第一条；`[{}]` 表示数组结果，保留全部行；无结果分别为 `null` 和 `[]`。对象路径作为数据源产生单行，数组对象路径产生多行，null 产生空表，标量报错。

### 按需求值

没有排序、分组和聚合时，对象结果在得到第一条匹配后停止，数组结果在满足 `limit` 后停止。扫描、JOIN、过滤和投影交替逐行执行；停止后不会再执行后续行的表达式或 UDF，其中的类型错误也不会暴露。上下文数组逐个转换元素，未读取的元素不会校验。`limit 0` 仍执行编译校验，但不访问数据源或调用运行时函数。

```mosaicql
users { score: amount * 2 }
users [{ score: amount * 2 }] limit 1
```

若第一行 `amount` 为数字、第二行为非法字符串，上述两种查询均只求值第一行。不写 `limit` 的数组查询会读取第二行并报错。

排序查询仍对全部匹配行执行投影和排序，然后选取所需结果。分组和聚合仍读取全部匹配输入；没有排序时，分组完成后只投影需要返回的分组。例如 `sum(amount)` 不会因为外层 `{}` 或 `limit 1` 而只聚合第一行。

`QueryExecutor.executeResult()` 和嵌套查询遵守对象/数组形态。旧的 `QueryExecutor.execute()` 返回 `Table`，只使用显式 `limit` 限制根行数，根 `{}` 不隐含单行限制。

### JOIN 执行

```mosaicql
users as u left join orders as o on u.id = o.user_id [{
  user_id: u.id,
  order_id: o.id
}]

users as u right outer join orders as o on u.id = o.user_id [{
  user_id: u.id,
  order_id: o.id
}]
```

- INNER JOIN 只返回匹配行；LEFT JOIN 还保留未匹配的左行；RIGHT JOIN 还保留未匹配的右行。多次匹配保留全部组合。
- 未匹配的一侧绑定为 null，`o.id`、`o.profile.name` 等显式字段引用返回 null。`*` 和裸字段只从实际存在的行解析，不推断缺失一侧的字段名；需要固定输出字段时应显式投影。
- `on` 先判断匹配，全部连接完成后才执行 `where`。匹配行被后续连接或 `where` 移除，不会被重新视为未匹配行。
- LEFT JOIN 支持上下文对象和数组路径，例如 `left join u.orders as o on true`。从补 null 的别名继续取路径得到空表；嵌套查询仍按 `{}` / `[{}]` 返回 null / `[]`。RIGHT JOIN 的右侧须为独立数据源，右侧上下文路径在编译时拒绝。
- INNER/LEFT JOIN 保留左侧输入顺序及每条左行的右侧匹配顺序。RIGHT JOIN 的匹配行沿用此顺序，完整左侧输入结束后再按右侧输入顺序补出未匹配行；多次 RIGHT JOIN 依书写顺序完成补行。需要按右侧字段排列时使用 `order by`。

默认解释器下，独立数据源的 INNER/LEFT JOIN 简单字段等值连接（如 `a.id = b.owner` 或 `b.owner = a.id`）使用右侧哈希索引，两侧字段须明确带别名。索引在每次执行内建立，不跨执行共享。

数值键按数值相等规则归一化，字符串保持字符串，null 和缺失值保持现有相等规则（null 与 null 匹配）。RIGHT JOIN、复杂条件、裸字段、上下文路径连接、自定义解释器和非有限数值等不适合索引的情况逐行判断。提前停止可以减少后续匹配和投影，但哈希连接仍需读取右侧数据建立索引；RIGHT JOIN 在输出未匹配行前必须扫描全部左侧输入以确认其未匹配，匹配行满足对象形态或 `limit` 时可直接停止。

`left`、`right`、`inner`、`outer` 在标识符位置仍可用于源名、别名、字段名和函数名。

## 表达式

支持字符串、数字、`true`、`false`、`null`，字段引用，`+ - * /`，`= != < <= > >=`，`and/or`，一元负号，函数调用和嵌套查询。逻辑表达式短路执行；`null` 条件按 false 处理，非布尔条件报错。

## CASE 和 IF

```mosaicql
case when score >= 90 then "A" when score >= 60 then "B" else "C" end
```

也支持：

```mosaicql
if score >= 90 then "A" else if score >= 60 then "B" else "C" fi
```

`if` 在 AST 中统一表示为 `CaseWhen`。分支按顺序短路，没有命中且没有 `else` 时返回 `null`。当前不支持 simple case。

## 类型

```text
NULL  UNKNOWN  BOOLEAN  NUMBER  STRING  DATE  TIMESTAMP  ARRAY<T>  OBJECT<...>
```

运行时 Map/List/JSON 值可推断类型。数值统一为 `NUMBER`；`NULL` 可赋给任意类型；未知字段保守放行。不自动执行字符串到数字/布尔、对象到字符串等转换。

`DATE` 对应 `java.time.LocalDate`，`TIMESTAMP` 对应 `java.time.Instant`，二者不自动互转。对象路径作为数据源产生单行，数组对象路径产生多行，null 产生空表，标量报错。

## 聚合和函数

聚合函数：`sum`、`count`、`count(*)`、`min`、`max`、`avg`。标量函数包括 `coalesce`、`concat`、`lower`、`upper`、`trim`、`length`、`abs`、`round`。

UDF 定义由 `FunctionSignature`、`ReturnTypeInference` 和 `Udf` 组成，支持参数数量范围、参数类型、返回类型、重载解析和自定义返回类型推断。旧的 `register(name, arity, udf)` 仍可用。

## 日期函数

```text
date(string) -> DATE
timestamp(string) -> TIMESTAMP
year(date) -> NUMBER
month(date) -> NUMBER
day(date) -> NUMBER
date_add(date, days) -> DATE
date_diff(later, earlier) -> NUMBER
format_date(date, pattern) -> STRING
```

示例：

```mosaicql
events {
  "day": date(created_at),
  "year": year(date(created_at)),
  "next": date_add(date(created_at), 7),
  "formatted": format_date(date(created_at), "yyyy/MM/dd")
}
```

## 编译与解释执行

```text
文本 → AST → QueryCompiler → 语义/函数/分组校验
     → 类型推断 → 符号绑定 → TypedExpression
     → CompiledQuery → QueryExecutor
     → Scan/Join/Filter/Group/Project/Sort/Limit
     → ExpressionInterpreter
```

`TypedExpression` 保存原始 AST、`MType`、子表达式和 `ExpressionBinding`。普通表达式使用 `ExpressionContext`，聚合表达式使用 `AggregateExpressionContext`。

`CompiledQuery.nestedQueries()` 保存递归编译后的子查询。在 `QueryExecutor` 执行编译结果时，嵌套查询复用编译计划，并按当前父行建立执行上下文；不会共享父行或查询结果。普通查询不再生成每行的单元素分组集合，聚合参数复用编译后的表达式树并逐行累计，不生成完整的聚合参数值列表。

## 当前边界

暂不支持 full join、distinct、window function、having、union/intersect/except、simple case、JDBC/XML/HTML 连接器、查询优化器和完整 SQL 隐式转换体系。

## 稳定 API 和执行限制

推荐通过 `MosaicQL` 门面使用解析、编译和执行：

```java
var result = MosaicQL.execute(
    "users [{ id }]",
    sourceProvider,
    functions,
    new ExecutionOptions(5_000, 100_000, 64));
String json = JsonAdapter.write(result);
```

`MosaicQLException` 包含 `code`、`phase` 和结构化 `details`。公开门面会统一包装解析、编译、执行和输出错误。

`ExecutionOptions` 提供：

- `timeoutMillis`：超时毫秒数，0 表示不限制；
- `maxRows`：一次执行实际投影产生的最大行数，包含嵌套结果和排序后被丢弃的候选行；不计未投影的输入、JOIN 候选及提前停止后的行，0 表示不限制；
- `maxNestingDepth`：嵌套查询最大深度，默认 64。

执行线程被中断时会产生取消错误。限制按每次执行创建，同一个 executor 可以并发复用。

超时和取消在扫描、JOIN 索引建立与匹配、分组、聚合、投影以及排序比较过程中检查；数据源读取和 UDF 本身仍为同步调用，无法强制中断一个阻塞的调用，调用返回后继续检查。

验证命令：

```shell
mvn test
```
