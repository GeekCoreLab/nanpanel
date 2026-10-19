# 协议与接口契约

Web → Java Core HTTP API 见 [OpenAPI](../../api/openapi.yaml) 与 [语义说明](../../api/README.md)。当前设计覆盖 Web 登录/会话、平台管理、主机、Agent、systemd 服务及重启任务，除系统信息端点外尚未实现。

Java Core ↔ Rust Agent 的 gRPC/mTLS 协议草案位于 [agent.proto](../../agent/protocol/agent.proto)，当前未生成代码、未实现服务。该草案仍需补充任务消息、授权上下文、结果对账和版本协商，以满足 HTTP 操作契约。
