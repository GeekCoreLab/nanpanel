# NaNPanel Privileged Helper

Linux Rust Helper 以 root 身份通过 Unix Domain Socket 接收固定类型操作，并用 `SO_PEERCRED` 校验 Agent UID、校验截止时间并记录脱敏审计。它支持从固定 HTTPS 控制器更新 Agent、systemd 重启与日志读取、软件包安装、受保护文件原子写入、主机文件管理、受限容器操作和临时防火墙放行。Helper 对 unit、包名、容器名/镜像/端口、文件路径、接口/协议/端口执行格式和运行条件校验；调用方不能传命令文本。

主机文件管理拒绝路径跳转和符号链接，仅处理普通 UTF-8 文件并限制文件大小、目录项数量和操作输出。容器操作使用固定安全参数和资源限制。网络放行先启动 systemd 定时回滚，再由仅获得 `CAP_NET_ADMIN` 的瞬时工作单元应用并核验规则。安装器安装 root-owned Helper 和 systemd unit，socket 仅对 Agent 组开放；更新不再创建或读取旧版逐项操作名单配置。

Helper 更新路径使用安装时保存的控制器证书验证 TLS，拒绝重定向；更新完成后由 Agent 回报结果并请求重启。`network.topology.reconcile` 只接受 Core Provider 构造的固定 JSON：当前仅支持已保存 Revision 中绑定 `linux-netns` 的 SWITCH 与 L2 Ethernet 网线；Helper 为每个拓扑 Revision 创建独立 Linux network namespace，在 namespace 内创建 bridge/veth，并验证资源归属及零默认路由。它不移动宿主接口，也不添加宿主路由。Compensate 只删除带有对应拓扑 ownership marker 的 namespace。网络能力不接受命令文本或任意接口/路由配置。复现实验见 [P0 验收说明](../../tests/p0/README.md) 与 [P6 Linux 网络实验](../../tests/p6/README.md)。详见 [ADR-009](../../docs/adr/ADR-009-agent-privilege-boundary.md) 和 [ADR-015](../../docs/adr/ADR-015-isolated-linux-topology-provider.md)。
