P0 架构可行性实验位于 [p0/](p0/README.md)，涵盖 PF4J 生命周期、Rust Agent/Helper、UDP 受限 NetBird Relay、FRP 事务，以及 React Flow 到真实 K3s Service 的公网路由。生产跨模块契约、集成与故障实验仍需按阶段扩展；模块单测放在各自 src/test。

P1 双主机 SSH、Helper 断线恢复、双 Relay/NAT 与 Cloudflare 公共 DNS 的真实环境验收步骤见 [p1/](p1/README.md)。本地测试和 mock 证据不代表这些现场门槛已完成。
