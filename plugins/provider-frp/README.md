# frp Provider

Provider 插件本身尚未实现，不会自动安装或修改外部组件。`tests/p0/` 提供独立的 FRP 配置事务实验：导入受支持的 TOML 配置、审阅差异、调用官方 `frpc verify`、应用配置、重启隔离客户端、探测实际 HTTP 路由，并在补偿后重验原路由。该实验不是通用 FRP 配置导入器或生产 Provider。

目标仍遵循 panel-plugin-api 的 Plan / Validate / Apply / Verify / Compensate 协议。主控侧 Provider 可作为 Java PF4J 插件负责计划与协调；部署到边缘的自研执行进程统一使用 Rust，特权操作经本地 root Helper。第三方组件本身不受本项目实现语言约束。
