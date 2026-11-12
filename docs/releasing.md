# 首次发布

将 MosaicQL 放入单独的公开仓库。首发范围包括根目录 POM、`mosaicql-parser/pom.xml`、
`mosaicql-parser/src`、公开文档、示例和许可证。采用源码清单导出，避免把外层仓库历史、
基准归档、冻结 JAR、IDE 配置和构建输出带入公开仓库。

1. 确认发布内容及贡献者的权属和授权，核对第三方组件的许可。
2. 在干净目录解包，以 JDK 21 执行 `mvn -B clean verify`，运行 [快速开始示例](quickstart.md)。
   检查产物坐标及 JAR 内的许可证。
3. 确定公开仓库地址和维护者，启用私密漏洞报告。发布前同步修改父 POM、子模块父版本、
   README 和变更记录中的版本。
4. 建立独立仓库的发布提交和标签，发布该版本的源码包及校验和。若需要 Maven Central，
   先确认命名空间可用，配置 POM 中的仓库及维护者信息，按 [Central 发布说明](https://central.sonatype.org/publish/requirements/) 准备源码、Javadoc 与签名。
