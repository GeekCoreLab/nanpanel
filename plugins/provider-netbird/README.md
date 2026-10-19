# netbird Provider

Provider 插件尚未实现，不会自动安装或修改外部组件。NetBird 官方端口说明记载了 UDP/443 不可用时客户端可回退到 TCP/443；`tests/p0/netbird-tcp443-relay.sh` 和 `tests/p0/netbird-cross-host-udp-blocked.sh` 已分别验证隔离实验及 NAT Docker 主机到 K3s 工作负载的中继路径。该证据完成 P0 可行性验收，但不代表生产 Provider、控制面配置或任意客户防火墙环境已经实现或验证。

目标仍遵循 panel-plugin-api 的 Plan / Validate / Apply / Verify / Compensate 协议。主控侧 Provider 可作为 Java PF4J 插件负责计划与协调；部署到边缘的自研执行进程统一使用 Rust，特权操作经本地 root Helper。第三方组件本身不受本项目实现语言约束。
