# 协议实现状态

Web → Java Core HTTP API 见 [OpenAPI](../../api/openapi.yaml) 与 [语义说明](../../api/README.md)。当前已实现登录/会话、平台管理、主机和 Agent 管理、systemd 服务观测、网卡/IP/路由快照、Helper 类型化操作与有界日志查询。Helper 接受固定操作类型，校验结构化参数、文件路径和运行条件，不读取逐项名单配置，也不执行任意 Shell。主机绝对路径文件管理仍拒绝路径跳转和符号链接，读写大小受限并记录为持久化操作。SSH 使用主机资产内加密保存且已确认 host key 的连接配置，支持固定 systemd 操作、Agent 初始化、一次性 WebSocket 票据和 PTY 堡垒 Shell；堡垒终端单独持久化会话、提交命令和有界输入/输出录像。其他 agentless 管理和拓扑部署仍待实现。

Java Core ↔ Rust Agent 的 gRPC/mTLS 协议位于 [agent.proto](../../agent/protocol/agent.proto)，当前已有 Agent 主动连接 Rust Gateway 的 mTLS 双向流、主机/systemd/网络心跳、受限 Agent 更新、systemd 操作及类型化 Helper 操作（软件包、受保护文件、主机文件管理、受限容器和自动回滚防火墙规则）的结果回报和 Core 确认。网络接口/IP/路由每 30 秒只读采集并保存最新快照。Helper 操作参数经有界 JSON 字段传递，Agent 将非更新操作写入本地持久化日志并在重连后重放；该恢复路径仍需两台真实主机的故障验收。

Relay 协议使用 Core 客户端 CA 与受信 Core 证书指纹配置、cluster ID、数据库分配的 epoch、持久 fencing、有界帧和队列，并要求 Core 按转发的 Agent 证书指纹完成心跳身份校验后再授权控制路由。已实现数据库时间租约、Agent 双 Relay 同时预连接、安装时签名备用地址，以及 Cloudflare DNS 单写、状态滞回、切换冷却与周期读回；公共 DNS 传播和限制型 NAT 故障仍需真实网络验收。任务签名与恢复游标仍待实现，详见[设计文档](../architecture/control-connectivity.md)。
