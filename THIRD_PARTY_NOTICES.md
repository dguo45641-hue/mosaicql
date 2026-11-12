# 第三方组件

MosaicQL 源码和普通 Maven JAR 使用 [Apache-2.0](LICENSE)。Maven 单独解析下列依赖，
它们采用各自的上游许可证。版本以本项目 POM 为准。

| 组件 | 用途 | 上游许可证 |
| --- | --- | --- |
| fastutil-core 8.5.19 | 查询执行中的原生类型集合 | [Apache-2.0](https://github.com/vigna/fastutil/blob/master/LICENSE-2.0) |
| Jackson Databind 3.3.0 | JSON 解析和序列化 | [Apache-2.0](https://github.com/FasterXML/jackson-databind/blob/3.x/LICENSE) |
| JavaCC 7.0.13 | 在构建时生成解析器 | [BSD，见上游许可证](https://github.com/javacc/javacc/blob/master/LICENSE) |
| JUnit Jupiter 5.11.0 | 测试 | [EPL-2.0](https://junit.org/junit5/docs/5.11.0/user-guide/#license) |

运行 `mvn -B -pl mosaicql-parser dependency:tree` 查看解析后的依赖。
若发布合并依赖的 JAR 或镜像，按实际分发的组件和版本保留其许可证与通知。
