# Rust privileged-helper

Linux Rust Helper 以 root 身份通过 Unix Domain Socket 接收类型化操作，并用 `SO_PEERCRED` 校验 Agent UID、校验截止时间并记录脱敏审计。除受限的 `file.read.allowed` 外，只允许从 root 配置的 HTTPS 控制器下载 Agent、检查 ELF 格式并原子替换固定二进制，以及重启唯一允许的 `nanpanel-agent.service` unit；不接受 URL、服务名或 Shell 参数作为调用方输入。安装器安装 root-owned Helper 和 systemd unit，socket 仅对 Agent 组开放。

Helper 更新路径使用安装时保存的控制器证书验证 TLS，拒绝重定向；更新完成后由 Agent 回报结果并请求重启。通用 systemd、软件包和网络管理能力仍未实现。复现实验见 [P0 验收说明](../../tests/p0/README.md)。详见 [ADR-009](../../docs/adr/ADR-009-agent-privilege-boundary.md)。
