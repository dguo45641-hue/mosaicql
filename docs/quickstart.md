# 快速开始

准备 JDK 21 和 Maven。从项目根目录安装构建产物：

```sh
mvn -B clean install
```

在同一 Maven reactor 内声明依赖，或让独立项目从本地 Maven 仓库解析：

```xml
<dependency>
  <groupId>com.mosaicql</groupId>
  <artifactId>mosaicql-parser</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

[QuickStart.java](../examples/QuickStart.java) 将 JSON 数组作为 `orders` 数据源，
筛选有效订单并按金额降序生成 JSON。可从项目根目录直接运行：

```sh
mvn -q -pl mosaicql-parser dependency:build-classpath -Dmdep.outputFile=target/example-classpath.txt
java --class-path "mosaicql-parser/target/classes:$(cat mosaicql-parser/target/example-classpath.txt)" examples/QuickStart.java
```

Windows 上将 classpath 的冒号分隔符换成分号，并用当前 shell 的文件读取命令替代 `cat`。

输出：

```json
[{"id":2,"amount":39.5},{"id":1,"amount":18}]
```

`orders [{ ... }]` 返回数组。改用 `orders { ... }` 会得到按条件选出的第一个对象，没有匹配时返回 `null`。
对重复请求，可以用 `MosaicQL.compile(query, functions)` 预编译，随后传给 `QueryExecutor.executeResult(plan)`。
函数注册完成后，再编译并复用计划。更多形态、JOIN 和资源限制见 [语言说明](MosaicQL语言说明.md) 和 [执行引擎](engine.md)。
