# Rust 边缘执行组件

`Cargo.toml` 建立了独立 workspace，包含 `daemon`、`relay`、`runtime-drivers`、`privileged-helper`。Agent 使用 Rust gRPC 双向流、mTLS 客户端证书、周期主机清单和 systemd 观测；Core 通过一次性接入码签发客户端证书，Gateway 校验证书并将心跳转发至 Core。Linux x86_64 静态 Agent 和 Helper 随 Core 镜像发布，安装器在证书指纹校验后部署普通用户 Agent 与 root Helper。两个 systemd unit 直接使用宿主机环境，不配置 systemd 文件系统、临时目录、内核或 syscall 沙箱；Agent 仍是普通用户，只有 Helper 以 root 运行。

Helper 使用 `SO_PEERCRED` 校验 Agent UID，只接收固定类型操作，不接受任意 Shell。支持 systemd unit 重启和有界 journal、软件包安装、受保护文件原子写入、主机文件浏览/读写/删除、容器管理、临时防火墙规则和 Agent 更新。操作目标使用结构化参数与固定命令；主机文件路径拒绝 `.`、`..` 和符号链接，限制文件类型、读写大小和目录项数量；容器使用只读根文件系统、资源上限、loopback 端口与 `no-new-privileges`；防火墙规则按接口、协议、端口校验，先启动 systemd 定时回滚，再由只获得 `CAP_NET_ADMIN` 的瞬时 worker 执行和验证。缺少可信 nft 或 systemd-run 时不报告该能力。`container-runtime` 可选择固定的 Docker 或 Podman CLI。安装器和维护命令会删除旧版逐项操作名单及模式文件。

SSH 固定探测、systemd 状态/日志/重启和 Agent 初始化都使用主机资产中加密保存的凭据与已确认的 host key；不再读取主控 Core 上的 alias/unit 名单或 OpenSSH alias 配置。Agent 安装使用固定脚本和一次性接入码，token 仅经 SSH stdin 传递，并等待首次 mTLS 心跳确认。管理员 PTY 堡垒 Shell 由 Core 独立建立，不经 Agent/Helper 提权通道；命令与输入/输出录像写入专用加密表，并支持强制阻断和保留清理。

### NetBird Overlay 观测（P2 实现切片）

NetBird 客户端可选择启用本机 metrics：`sudo netbird down && sudo netbird up --enable-local-metrics`。确认服务仅监听 `127.0.0.1:9191`，再为 Agent 添加 systemd drop-in：

```ini
[Service]
Environment=NANPANEL_AGENT_NETBIRD_METRICS_URL=http://127.0.0.1:9191/metrics
```

应用后运行 `sudo systemctl daemon-reload && sudo systemctl restart nanpanel-agent.service`。Agent 每 30 秒从 loopback 拉取有大小上限的 Prometheus 文本，只接受字面 loopback 地址、固定 `/metrics` 路径和无重定向 HTTP 请求；读取失败时该心跳不携带 Overlay 快照。Core 将 peer 总数、连接总数、P2P 数、Relay 数和采集时间保存到主机网络清单，并拒绝过期、越界或数量不一致的数据。网络信息抽屉的 Overlay 标签展示这些本机汇总值。

NetBird 本机 metrics 默认关闭且无认证，禁止绑定非 loopback 地址；该接口可能暴露 peer 名称、连通状态和延迟。它只用于总量汇总，不包含逐 peer 的直连/Relay 类型。

若系统安装了 root-owned、不可被组或其他用户写入的 `/usr/bin/netbird` 或 `/usr/local/bin/netbird`，Helper 会增加固定只读能力 `network.overlay.peer_paths.read`，并在授权 Agent 请求时运行 `netbird status --json`。Helper 只返回 peer FQDN、连接状态和 P2P/Relay 类型；NetBird IP、公钥、Relay 地址、候选端点及其他状态字段会在 Root Helper 内丢弃。Agent 将该快照及观察时间经 mTLS 上报，Core 对字段、数量、时效和能力声明校验后持久化；网络信息抽屉可按 peer 展示。二进制不可信、Daemon socket 不可访问、数据超出上限或输出无效时，不上报本次快照。该信息仅证明客户端路径状态，不证明业务服务探测成功。

Metrics 汇总和逐 peer 路径快照是两种独立来源：前者仍需按上方步骤开启；后者需要 NetBird 客户端与 NaNPanel Helper。NetBird CLI 的结构化状态格式见 [CLI status 文档](https://docs.netbird.io/get-started/cli#status) 和 [本机 Metrics 文档](https://docs.netbird.io/client/local-metrics)。

非更新操作在执行前持久化幂等 claim，结果在 Core 确认后才清除；重连时重放未确认结果，执行状态不明的记录拒绝重复执行。断网/崩溃边界已有单元及 Core 集成测试，仍需两台非 K3s 宿主机端到端故障验收。主控 Core 使用 Java；Agent 与 mTLS/gRPC Gateway 使用 Rust。详见 [P0 验收说明](../tests/p0/README.md)、[ADR-009](../docs/adr/ADR-009-agent-privilege-boundary.md)、[ADR-010](../docs/adr/ADR-010-rust-edge-components.md) 与 [ADR-012](../docs/adr/ADR-012-host-ssh-bastion.md)。

Agent 可通过 `NANPANEL_AGENT_ENDPOINTS` 配置 1–8 个唯一 HTTPS Gateway 地址，也可通过 `NANPANEL_AGENT_RELAY_ENDPOINTS` 配置 Relay 地址，Core 签名的备用地址写入 `NANPANEL_AGENT_RELAY_FALLBACK_ENDPOINTS` 并由 Agent 使用 Ed25519 公钥验签；两类地址合计最多 8 个。Agent 为 direct Gateway 保留一个连接 worker，为 Relay 地址分成两个独立轮换组并行保持两条 Relay 控制流，断线后分别使用有界退避恢复。Core Gateway 设置 `NANPANEL_GATEWAY_RELAY_ENDPOINTS` 后要求 2–8 个唯一 Relay 地址，并行建立 Core→Relay 长连接；还需配置 `NANPANEL_GATEWAY_CLUSTER_ID`、`NANPANEL_GATEWAY_LEASE_SECONDS`、`NANPANEL_GATEWAY_RELAY_TLS_DOMAIN` 及 Core 客户端证书/私钥/CA。Core Gateway 从数据库获取 45 秒 leader 租约并每 10 秒续期，epoch 随接管单调递增；Relay 将最高 epoch 原子持久化到独立状态卷。Relay 部署变量和最小权限容器示例见 [deploy/relay](../deploy/relay/README.md)。若系统 DNS 连接失败，且目标主机与配置的 TLS 服务名相同、不是保留/内部名称，Agent 会直接向 Cloudflare `1.1.1.1` 和 `1.0.0.1` 查询 A/AAAA；UDP 截断时回退 TCP，每个请求最多 2 秒，最多 3 轮，并拒绝非公网单播结果。TLS 校验仍使用原配置的服务名，DNS 回答不会改变信任身份。Cloudflare 记录协调器已有三次失败滞回、120 秒切换冷却和周期性读回对账代码；真实故障验收仍待完成；详见 [设计文档](../docs/architecture/control-connectivity.md)。
