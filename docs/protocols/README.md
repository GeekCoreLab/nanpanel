# 协议与接口契约

Web → Java Core HTTP API 见 [OpenAPI](../../api/openapi.yaml) 与 [语义说明](../../api/README.md)。当前设计覆盖 Web 登录/会话、平台管理、主机、Agent、systemd 服务及重启任务，除系统信息端点外尚未实现。

Java Core ↔ Rust Agent 的 gRPC/mTLS 协议草案位于 [agent.proto](../../agent/protocol/agent.proto)，当前已有 Agent 主动连接 Rust Gateway 的 mTLS 双向流、心跳及受限更新链路；仍需补充完整授权上下文、结果对账和版本协商。

下一阶段协议要求见 [双向通道与 DNS 故障切换](../architecture/control-connectivity.md)：新增 Core 主动拨号的 Agent 监听服务、Core→Relay 反向注册、对称消息封装、会话代际、任务签名及恢复游标。当前 `Session` RPC 不能直接覆盖所有拨号方向；本次仅修改文档，未扩展协议。
