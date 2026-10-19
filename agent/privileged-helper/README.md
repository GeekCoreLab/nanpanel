# Rust privileged-helper

Linux Rust Helper 以 root 身份通过 Unix Domain Socket 接收类型化操作，并用 `SO_PEERCRED` 校验 Agent UID、校验截止时间并记录脱敏审计。它支持从固定 HTTPS 控制器更新 Agent、allowlist systemd 重启与日志读取、allowlist 软件包安装、受保护文件原子写入、受限容器操作和临时防火墙放行。网络放行限定 root allowlist 中的接口、协议和端口，先启动 systemd 定时回滚，再由仅获得 `CAP_NET_ADMIN` 的瞬时工作单元应用并核验规则；调用方不能传命令或任意网络参数。安装器安装 root-owned Helper 和 systemd unit，socket 仅对 Agent 组开放。

Helper 更新路径使用安装时保存的控制器证书验证 TLS，拒绝重定向；更新完成后由 Agent 回报结果并请求重启。allowlist 默认全部为空。网络能力只提供有界临时防火墙规则，不提供任意路由或接口配置。复现实验见 [P0 验收说明](../../tests/p0/README.md)。详见 [ADR-009](../../docs/adr/ADR-009-agent-privilege-boundary.md)。
