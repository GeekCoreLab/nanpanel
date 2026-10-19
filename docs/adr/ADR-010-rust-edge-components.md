# ADR-010：Java 主控与 Rust 边缘组件

状态：接受（实现待完成）

## 决策

主控保留 Java + Quarkus JVM、PF4J 和 Fabric8。自研边缘 Agent、边缘控制器、运行时驱动、Provider 本地执行进程及 privileged-helper 统一使用 Rust，不采用 Go 或 Java Native 作为边缘实现方案。

Core 内部的 Java Network Reconciler、Cluster Connector 和 Agent Gateway 属于主控。部署在主控侧的 PF4J 插件可生成计划并协调 Rust 边缘执行端；边缘不加载 JVM 插件。双方经版本化 Protobuf/gRPC 通信，Helper 使用受控本地 IPC。

约束不适用于第三方 Envoy、FRP、NetBird、K3s、KubeEdge，也不限制被管理应用语言。

## 实施与验收

后续在 agent/ 建立 Cargo workspace，涵盖 daemon、runtime-drivers、privileged-helper；当前未建立 Rust 工程，不宣称已有 Rust Agent。验证 x86_64 / ARM64 构建和运行、资源占用、协议兼容、mTLS 重连、升级恢复及权限隔离。

## 代价

维护 Java / Rust 两套工具链及跨语言协议；可复用的 JVM 库不能直接用于边缘执行端。Rust 不自动保证低占用、无漏洞或插件隔离，仍需实测与授权设计。
