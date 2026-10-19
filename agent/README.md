# Rust 边缘执行组件

`Cargo.toml` 建立了独立 workspace，包含 `daemon`、`relay`、`runtime-drivers`、`privileged-helper`。Agent 使用 Rust gRPC 双向流、mTLS 客户端证书、周期主机清单/systemd 观测和指数退避重连；Core 通过一次性接入码签发客户端证书，Gateway 校验证书并将心跳转发至 Core。独立 Control Relay 接受 Agent 与 Core 出站双向流，Core Gateway 在配置至少两个 Relay 时并行连接；Relay 只在 Core 校验 Agent 证书指纹后开放控制路由。Linux x86_64 静态 Agent 和 Helper 随 Core 镜像发布，安装器在证书指纹校验后部署普通用户 Agent 与 root Helper。Helper 通过 `SO_PEERCRED` 校验 UID，开放状态探测、从固定控制器原子更新 Agent、allowlist systemd unit 重启、独立 allowlist 的有界 journal 读取、受控软件包安装、受保护文件原子写入、allowlist 容器操作，以及带 systemd 定时回滚的 nftables 临时规则。网络变更仅限预设防火墙规则；Core 的固定范围 SSH 操作包括 host-bound alias 探测、allowlist systemd 状态/有界日志读取与救援重启，以及通过固定安装脚本异步初始化 Agent；allowlist 默认为空。真实双主机 SSH 验收与固定范围之外的 agentless 管理能力仍待完成。

Agent 接入、Helper 状态回报、自更新、systemd、软件包、受保护文件、容器及临时防火墙操作已接入 Core；更新只从安装时配置的 HTTPS 控制器取包，所有 Helper 操作均固定能力、固定参数边界且不提供任意 root 命令。软件包、文件目标、容器映射和网络规则配置位于 `/etc/nanpanel/managed/`，安装器创建空 allowlist；只有 root 编辑后对应能力才会被 Agent 上报。容器部署只能使用预设镜像与 loopback 端口，并启用只读根文件系统、资源上限和 `no-new-privileges`。防火墙规则限定接口/协议/端口，在应用前启动独立定时回滚，并由只获得 `CAP_NET_ADMIN` 的瞬时 worker 执行和验证。缺少可信 nft 或 systemd-run 时不报告该能力。非更新操作在执行前持久化幂等 claim，结果在 Core 确认后才清除；重连时重放未确认结果，执行状态不明的记录拒绝重复执行。断网/崩溃边界已有单元及 Core 集成测试，仍需两台非 K3s 宿主机端到端故障验收。固定范围 SSH 代码已包含 alias 探测、allowlist systemd 状态/日志/重启与固定脚本 Agent 初始化；真实双主机 SSH 验收仍待完成。Agent/Core 双向 Relay、签名备用名单和 Cloudflare DNS 滞回/冷却/对账已有代码；公共 DNS 与故障验收仍待完成。证书吊销/续期、在线名单更新和超出固定 allowlist 的 agentless 管理仍未实现。

Core SSH 探测和恢复默认关闭。/etc/nanpanel/ssh-aliases.allowlist 每行写 <host-UUID> <OpenSSH-alias>，同一主机只能绑定一个 alias，alias 也不能重复绑定。/etc/nanpanel/ssh-units.allowlist 每行写 <host-UUID> <systemd-unit.service>，只有 root 显式添加的 unit 才能经 SSH 重启；文件缺失或为空时恢复能力禁用。/etc/nanpanel/ssh_config 与 /etc/nanpanel/ssh_known_hosts 必须是 root 管理且不可被组或其他用户写入的普通文件。Core 不保存私钥或密码；凭据由 Core 服务进程可访问的 IdentityFile 或密钥代理提供。探测只运行固定 true 命令；恢复只运行固定 sudo systemctl restart/is-active 参数，不开放终端、转发或任意命令。

allowlist 格式（默认文件为空，修改后重启 `nanpanel-helper.service` 以刷新 Helper 的能力快照）：

- `packages.allowlist`：每行一个软件包名；仅从主机已配置的 apt/dnf 可信软件源安装。
- `files.allowlist`：每行一个文件名；Helper 仅原子写入 `/etc/nanpanel/managed/files/` 的单层普通文件，最大 32 KiB。
- `container-runtime`：仅写 `docker` 或 `podman`，显式选择固定运行时；空文件表示禁用容器能力。
- `containers.allowlist`：每行 `名称 镜像 主机端口 容器端口`；无端口写 `名称 镜像 - -`。部署固定绑定 `127.0.0.1`，不接受挂载、特权模式或客户端自选镜像。
- `network.allowlist`：每行 `规则名 接口 tcp|udp 端口`，例如 `ssh-test eth0 tcp 2222`；规则仅临时应用且必须配置 30–300 秒回滚，能力默认禁用。

例如，先将目标镜像和端口放入 root 管理的容器清单，再通过 Core 操作；allowlist 内容由主机管理员维护，不由 Web API 修改。`systemd-units.allowlist` 与 `journal-units.allowlist` 继续分别控制服务重启和日志读取。

主控 Core 使用 Java；Agent 与 mTLS/gRPC Gateway 使用 Rust。默认普通用户 Agent + root Helper；每项提权仍须经过 Core 控制消息和 Helper 本地能力白名单。`backend/panel-agent-gateway` 目前尚未承接生产 Gateway 运行时。可复现实验见 [P0 验收说明](../tests/p0/README.md)。详见 [ADR-009](../docs/adr/ADR-009-agent-privilege-boundary.md) 与 [ADR-010](../docs/adr/ADR-010-rust-edge-components.md)。

Agent 可通过 `NANPANEL_AGENT_ENDPOINTS` 配置 1–8 个唯一 HTTPS Gateway 地址，也可通过 `NANPANEL_AGENT_RELAY_ENDPOINTS` 配置 Relay 地址，Core 签名的备用地址写入 `NANPANEL_AGENT_RELAY_FALLBACK_ENDPOINTS` 并由 Agent 使用 Ed25519 公钥验签；两类地址合计最多 8 个。Agent 为 direct Gateway 保留一个连接 worker，为 Relay 地址分成两个独立轮换组并行保持两条 Relay 控制流，断线后分别使用有界退避恢复。Core Gateway 设置 `NANPANEL_GATEWAY_RELAY_ENDPOINTS` 后要求 2–8 个唯一 Relay 地址，并行建立 Core→Relay 长连接；还需配置 `NANPANEL_GATEWAY_CLUSTER_ID`、`NANPANEL_GATEWAY_LEASE_SECONDS`、`NANPANEL_GATEWAY_RELAY_TLS_DOMAIN` 及 Core 客户端证书/私钥/CA。Core Gateway 从数据库获取 45 秒 leader 租约并每 10 秒续期，epoch 随接管单调递增；Relay 将最高 epoch 原子持久化到独立状态卷。Relay 部署变量和最小权限容器示例见 [deploy/relay](../deploy/relay/README.md)。若系统 DNS 连接失败，且目标主机与配置的 TLS 服务名相同、不是保留/内部名称，Agent 会直接向 Cloudflare `1.1.1.1` 和 `1.0.0.1` 查询 A/AAAA；UDP 截断时回退 TCP，每个请求最多 2 秒，最多 3 轮，并拒绝非公网单播结果。TLS 校验仍使用原配置的服务名，DNS 回答不会改变信任身份。Cloudflare 记录协调器已有三次失败滞回、120 秒切换冷却和周期性读回对账代码；真实故障验收仍待完成；详见 [设计文档](../docs/architecture/control-connectivity.md)。
