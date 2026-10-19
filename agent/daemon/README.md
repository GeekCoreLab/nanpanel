# Rust Agent daemon

Agent 使用 tonic 双向流与 mTLS Gateway 建立认证连接，发送带单调序列号的周期心跳、Helper 可用性及受限能力清单，并在连接断开后指数退避重连。Core 的一次性接入流程验证本机 CSR 身份并签发客户端证书；Gateway 将证书指纹和心跳回传 Core，只有与已登记证书匹配的 Agent 才能更新主机资产。Linux x86_64 安装器以专用普通用户运行，并在发送接入码前验证控制器 TLS 指纹。

Agent 只接受固定的 `agent.update` 控制指令：它要求本地 root Helper 从配置的 HTTPS 控制器更新 Agent，并重启自身 systemd unit；其他控制能力均拒绝。身份吊销/续期、离线任务派发、通用 systemd 观测与操作仍未实现。传输隔离实验见 [P0 验收说明](../../tests/p0/README.md)。
