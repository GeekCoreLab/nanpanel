# Rust Agent daemon

Agent 使用 tonic 双向流与 mTLS Gateway 或独立 Control Relay 建立认证连接，发送带单调序列号的周期心跳、Helper 可用性及受限能力清单，并在连接断开后指数退避重连。Core 的一次性接入流程验证本机 CSR 身份并签发客户端证书；Gateway 将证书指纹和心跳回传 Core，只有与已登记证书匹配的 Agent 才能更新主机资产。经 Relay 时，Core 心跳校验通过后才授权其控制路由。Linux x86_64 安装器以专用普通用户运行，并在发送接入码前验证控制器 TLS 指纹。

Agent 的 systemd、journal、软件包、受保护文件、容器及临时防火墙操作都须匹配本地 typed validator 和 root Helper 空白默认 allowlist；操作结果跨重启持久化并在 Core 确认后清除。Relay 限定 64 KiB 帧、每流 8 项有界队列、最多 256 个 Agent 路由和 Core 证书指纹 allowlist。数据库单调 fencing epoch、45 秒 Core Gateway leader 租约续期、Agent 双 Relay 并行连接与 Relay 持久 epoch 已接入；Core 在 Agent 安装响应中签发 Ed25519 Relay 备用名单，Agent 验签后才接入；证书吊销/续期、在线名单更新和真实 DNS 故障验收仍未实现。传输隔离实验见 [P0 验收说明](../../tests/p0/README.md)。
