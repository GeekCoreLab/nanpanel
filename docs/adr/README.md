# 架构决策记录

本索引列出当前已建立的架构决策；仍待验证的议题记录在 PROJECT_PLAN.md，达到决策条件后再新增 ADR。

- [ADR-001：Core 采用 JVM 模式](ADR-001-core-jvm.md)。
- [ADR-003：分离 Host、ClusterNode、Workload 与 Service 身份](ADR-003-cluster-resource-identity.md)。
- [ADR-006：分离 Host Agent 与 KubeEdge 资源归属](ADR-006-kubeedge-host-ownership.md)。
- [ADR-007：注册独立 Kubernetes 集群](ADR-007-independent-kubernetes-clusters.md)。
- [ADR-009：普通用户 Agent + root Helper](ADR-009-agent-privilege-boundary.md)。
- [ADR-010：Java 主控与 Rust 边缘组件](ADR-010-rust-edge-components.md)。
- [ADR-011：双向建连、主控代理与 DNS 故障切换](ADR-011-control-relay-dns-failover.md)（接受，部分实现，待验收）。
- [ADR-012：资产级 SSH 连接与堡垒终端审计](ADR-012-host-ssh-bastion.md)（接受，代码已实现，真实主机验收待完成）。
- [ADR-013：Kubernetes 工作负载的可选 Istio 服务网络](ADR-013-istio-service-network.md)（接受，隔离 K3s 实验已部署，平台 Provider 待实现）。
- [ADR-014：应用渐进式交付](ADR-014-progressive-delivery.md)（接受，代码及独立运行时验收见 P5 文档）。
- [ADR-015：隔离 Linux 拓扑 Provider](ADR-015-isolated-linux-topology-provider.md)（接受，隔离实验完成，生产拓扑 Apply 待验收）。
- [ADR-016：应用虚拟网络数据面](ADR-016-application-virtual-network-data-plane.md)（源码已实现，隔离集群包路径待验收）。
- [ADR-017：拖拽式应用 Service 流量编排](ADR-017-application-service-traffic-steering.md)（设计已接受，尚未实现）。
