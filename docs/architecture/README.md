模块职责以 PROJECT_PLAN.md 为基线。Core 仅负责控制面，Provider 与 Agent 执行外部变更。

主控与边缘按部署边界划分：Core 内部的协调器、Fabric8 Connector、Agent Gateway 逻辑协调和 PF4J 插件保留 Java；部署在被管理主机或公网入口的自研 Agent、控制器、驱动及 Helper 统一使用 Rust，经版本化协议通信。边缘默认普通用户主进程 + root Helper。第三方数据面组件保留各自实现。

公网主控代理采用独立 Rust Control Relay，与 Core 内 Java Agent Gateway 协调职责分开。双向拨号、限制型 NAT 主控出站接入、节点 DNS 与 CNAME 故障切换见 [通信架构设计](control-connectivity.md) 和 [ADR-011](../adr/ADR-011-control-relay-dns-failover.md)，均为待实现设计。
