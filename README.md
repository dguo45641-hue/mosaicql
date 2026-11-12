# MosaicQL

[English](README.en.md) · [Apache-2.0](LICENSE)

MosaicQL 是一个可嵌入 Java 应用的查询与数据转换引擎。脚本描述如何筛选、连接和重组数据，
宿主负责提供数据。适合从 JSON、业务对象或宿主已读入的数据行生成下游需要的结构。

```mosaicql
customers as c [{
  id: c.id,
  name: upper(c.name),
  orders: c.orders [{ id, amount }] where amount > 0
}] where c.active
```

- 支持嵌套对象和数组、条件表达式、筛选、排序、分组和聚合。
- 支持 INNER、LEFT 和 RIGHT JOIN。可通过 Java 注册自定义函数。
- 提供解析、编译和执行 API。可复用编译计划，并选择 JSON 字符串、树或流式输出。
- 使用 JDK 21 构建。Maven 模块坐标为 `com.mosaicql:mosaicql-parser:0.1.0-SNAPSHOT`，可从源码安装到本地。

```sh
mvn -B clean verify
```

从 [快速开始](docs/quickstart.md) 运行一个完整 Java 示例。[语言说明](docs/MosaicQL语言说明.md) 描述语法和结果形态，
[执行引擎与 Java API](docs/engine.md) 解释编译计划、流式输出和执行限制。

宿主通过 `SourceProvider` 提供表。把输入体积、执行超时和结果量纳入宿主限制后，再把查询放到服务请求路径。
当前的独立版本提供 JSON 适配器，其他格式可先由宿主转换为数据行。

参与开发请见 [贡献指南](CONTRIBUTING.md)，安全问题请走 [安全报告流程](SECURITY.md)。
第三方依赖和许可见 [第三方组件](THIRD_PARTY_NOTICES.md)。
