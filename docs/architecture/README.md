模块职责以 PROJECT_PLAN.md 为基线。Core 仅负责控制面，Provider 与 Agent 执行外部变更。

应用虚拟网络提供显式单节点 K3s 的 Multus/EndpointSlice 运行时生命周期；真实隔离集群包路径尚未验收。跨节点 VXLAN 仍是后续 Provider 范围，见 [ADR-016](../adr/ADR-016-application-virtual-network-data-plane.md)。

主控与边缘按部署边界划分：Core 内部的协调器、Fabric8 Connector、Agent Gateway 逻辑协调和 PF4J 插件保留 Java；部署在被管理主机或公网入口的自研 Agent、控制器、驱动及 Helper 统一使用 Rust，经版本化协议通信。边缘默认普通用户主进程 + root Helper。第三方数据面组件保留各自实现。

服务网络按边界组合 NetBird、Istio 与 Envoy/FRP：[服务网络架构](service-network.md) 和 [ADR-013](../adr/ADR-013-istio-service-network.md) 将 Istio 限定为可选的 Kubernetes 工作负载网络；现有隔离网格、namespace 限定 Service/EndpointSlice 库存和有界 TCP 探测已接入。NetBird 有首个 managed-group PF4J Provider 与 Core 操作生命周期；Istio 和公网 ingress Provider 仍未实现生产协调。

应用画布的 Service 访问与权重分流设计见 [ADR-017](../adr/ADR-017-application-service-traffic-steering.md) 和[服务网络架构](service-network.md#visual-application-traffic-intent)：Service selector 关系、L2/L3 网线、业务调用/路由是不同语义；工作负载可同时提供服务和发起调用。当前权重仍是期望状态，生产流量路由 Provider 尚未实现。

Kubernetes 应用虚拟网络的真实数据面边界见 [ADR-016](../adr/ADR-016-application-virtual-network-data-plane.md)：保留默认 CNI，以 Multus 次级接口接入每应用隔离拓扑，Service 通过次级 Pod IP EndpointSlice 转发；单节点使用隔离 veth/bridge，跨节点规划按应用隔离的 VXLAN VNI。Apply 生命周期已实现；部署控制面不自动 Apply 应用拓扑，真实 Pod/Service 包路径仍需在隔离集群验收。

公网主控代理采用独立 Rust Control Relay，与 Core 内协调职责分开。双向 Relay 流、Gateway/Agent 数据库租约、Agent 安装时签名故障切换端点清单、DNS 三次健康/失败滞回、切换冷却和 API 周期读回对账已有代码；公共 DNS TTL/传播及限制型 NAT/真实故障验收仍待完成。设计边界见 [通信架构设计](control-connectivity.md) 和 [ADR-011](../adr/ADR-011-control-relay-dns-failover.md)。
