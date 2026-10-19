# netbird Provider

预留模块，尚未实现，不会自动安装或修改外部组件。遵循 panel-plugin-api 的 Plan / Validate / Apply / Verify / Compensate 协议。

主控侧 Provider 可作为 Java PF4J 插件负责计划与协调；部署到边缘的自研执行进程统一使用 Rust，特权操作经本地 root Helper。第三方组件本身不受本项目实现语言约束。
