# Rust 边缘执行组件

`Cargo.toml` 建立了独立 workspace，包含 `daemon`、`runtime-drivers`、`privileged-helper`。Agent 使用 Rust gRPC 双向流、mTLS 客户端证书、周期主机清单/心跳和指数退避重连；Core 通过一次性接入码签发客户端证书，Gateway 校验证书并将心跳转发至 Core。Linux x86_64 静态 Agent 和 Helper 随 Core 镜像发布，安装器在证书指纹校验后部署普通用户 Agent 与 root Helper。Helper 通过 `SO_PEERCRED` 校验 UID，仅开放状态探测、从固定控制器原子更新 Agent、重启 `nanpanel-agent.service` 和原有受限 `file.read.allowed` 操作。

Agent 接入、Helper 状态回报和 Agent 自更新任务已接入 Core；更新只从安装时配置的 HTTPS 控制器取包，Helper 限定目标路径与 systemd unit，不提供任意 root 命令。P0 隔离实验不代表宿主机 root 部署；证书吊销/续期、通用 systemd 管理、软件包/网络驱动仍未实现。

主控 Core 使用 Java；Agent 与 mTLS/gRPC Gateway 使用 Rust。默认普通用户 Agent + root Helper；每项提权仍须经过 Core 控制消息和 Helper 本地能力白名单。`backend/panel-agent-gateway` 目前尚未承接生产 Gateway 运行时。可复现实验见 [P0 验收说明](../tests/p0/README.md)。详见 [ADR-009](../docs/adr/ADR-009-agent-privilege-boundary.md) 与 [ADR-010](../docs/adr/ADR-010-rust-edge-components.md)。
