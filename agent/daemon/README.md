# NaNPanel Agent

Agent 以 `nanpanel-agent` 普通用户运行，负责 mTLS/gRPC 控制流、主机/systemd/网络清单、自更新协调和 Helper 操作结果持久化。Agent 的 systemd、journal、软件包、受保护文件、主机文件、容器及临时防火墙操作都先经过本地类型校验，再由 root Helper 执行；结果跨重启保存，并在 Core 确认后清除。Agent 不开放任意 root 命令。

Relay 限定 64 KiB 帧、每流 8 项有界队列、最多 256 个 Agent 路由，并校验受信 Core 客户端证书。数据库单调 fencing epoch、45 秒 Core Gateway leader 租约续期、Agent 双 Relay 并行连接与 Relay 持久 epoch 已接入；Core 在 Agent 安装响应中签发 Ed25519 Relay 备用地址清单，Agent 验签后才接入。证书吊销/续期、备用地址在线更新和真实 DNS 故障验收仍未实现。传输隔离实验见 [P0 验收说明](../../tests/p0/README.md)。
