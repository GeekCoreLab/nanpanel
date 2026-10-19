# ADR-001：Core 采用 JVM 模式

状态：接受（初始化基线）

Core 使用 Quarkus JVM 承载 PF4J，避免 Native Image 限制动态 JAR 加载。动态插件不得假设自动获得 CDI/REST 注册能力。自研 Agent 与边缘执行组件统一使用 Rust，经版本化协议与 Core 通信，详见 ADR-010。

源码使用 Java 21 release（规划允许的兼容选项），以适配当前开发环境；后续可在 Java 25 上运行并验证。插件 SDK 当前仅为 0.1 草案，不承诺二进制兼容。
