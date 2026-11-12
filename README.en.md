# MosaicQL

[简体中文](README.md) · [Apache-2.0](LICENSE)

MosaicQL is an embeddable query and data transformation engine for Java. A query
describes how to filter, join, and reshape rows supplied by the host application.

```mosaicql
customers as c [{
  id: c.id,
  name: upper(c.name),
  orders: c.orders [{ id, amount }] where amount > 0
}] where c.active
```

MosaicQL supports nested object and array projections, INNER, LEFT, and RIGHT
joins, sorting, grouping, aggregates, and registered Java functions. Compile
queries for reuse and choose JSON string, tree, or streaming output.

Build with JDK 21 and Maven.

```sh
mvn -B clean verify
```

The module is `com.mosaicql:mosaicql-parser:0.1.0-SNAPSHOT`. Install it locally
with `mvn -B clean install`. The [quickstart](docs/quickstart.md) shows a complete
Java example. The [language reference](docs/MosaicQL语言说明.md) and
[engine guide](docs/engine.md) are available in Chinese.

The host provides rows through `SourceProvider`. Configure input limits, timeouts,
and result limits when running queries on a service request path. The bundled
adapter reads JSON. A host can convert other formats into rows before execution.

See [contributing](CONTRIBUTING.md), [security reporting](SECURITY.md), and
[third-party notices](THIRD_PARTY_NOTICES.md).
