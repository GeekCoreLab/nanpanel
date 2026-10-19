# 协议与接口契约

Web → Java Core HTTP API 见 [OpenAPI](../../api/openapi.yaml) 与 [语义说明](../../api/README.md)。当前已实现登录/会话、平台管理、主机和 Agent 管理、systemd 服务观测、allowlist 限制的重启操作、有界 journal 查询和带定时回滚的临时防火墙规则，以及 host UUID 绑定的 SSH 固定探测、显式 unit allowlist 的 systemd 状态/有界日志读取/恢复和固定 Agent 安装脚本初始化；其他 agentless 管理和拓扑部署仍待实现。

Java Core ↔ Rust Agent 的 gRPC/mTLS 协议位于 [agent.proto](../../agent/protocol/agent.proto)，当前已有 Agent 主动连接 Rust Gateway 的 mTLS 双向流、主机/systemd 心跳、受限 Agent 更新、systemd 操作及类型化 Helper 操作（软件包、受保护文件、受 allowlist 容器和自动回滚防火墙规则）的结果回报和 Core 确认。Helper 操作参数经有界 JSON 字段传递，Agent 将非更新操作写入本地持久化日志并在重连后重放；该恢复路径仍需两台真实主机的故障验收。

Relay 协议新增 Core 与 Agent 各自出站的双向流。Rust Gateway 可并行连接多个配置的 Relay，Agent 可轮换 Relay 地址；Relay 使用 Core 证书指纹 allowlist、cluster ID、数据库分配的 epoch、持久 fencing、有界帧和队列，并要求 Core 按转发的 Agent 证书指纹完成心跳身份校验后再授权控制路由。已实现数据库时间租约、Agent 双 Relay 同时预连接、安装时签名备用名单，以及 Cloudflare DNS 单写、状态滞回、切换冷却与周期读回；公共 DNS 传播和限制型 NAT 故障仍需真实网络验收。任务签名与恢复游标仍待实现，详见 [设计文档](../architecture/control-connectivity.md)。
