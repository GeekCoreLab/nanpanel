# P1 真实环境验收

本手册用于补齐 [项目计划](../../PROJECT_PLAN.md) 中 P1 阶段需要真实主机、Relay 和 Cloudflare DNS 的验收。Core、Agent、Helper、SSH 固定操作和 DNS 协调器的本地构建与自动化测试通过，只能证明代码路径；在本手册各项都有现场证据前，P1 仍未通过整体验收。

## 开始前准备

准备两台可回滚的普通 Linux 主机（均非 K3s 节点）、一个专用 Cloudflare 测试 Zone 或其独立子域、两个分属不同故障域的 Relay，以及正在运行的 Core。不要使用生产 DNS 名称或生产服务做重启实验。验收期间记录 Core、Agent、Helper、Relay 和数据库版本/镜像摘要及 UTC 时间。

开始前填好以下值；私钥、一次性接入码、登录 Cookie、CSRF token 和 Cloudflare token 不写入本文件、命令历史或验收输出：

| 输入 | 说明 |
| --- | --- |
| `HOST_A_UUID`、`HOST_B_UUID` | Core 中两台测试主机的资产 UUID |
| `SSH_ALIAS_A`、`SSH_ALIAS_B` | Core 服务端 SSH 配置中的两个不同别名 |
| `SSH_TEST_UNIT_A`、`SSH_TEST_UNIT_B` | 每台主机上可安全重启的测试 unit；优先使用专用测试服务 |
| `CORE_ORIGIN` | Agent 安装使用的 HTTPS Core origin |
| `RELAY_A_ORIGIN`、`RELAY_B_ORIGIN` | 两个 HTTPS Relay origin；必须位于不同故障域 |
| `DNS_ZONE_ID`、`DNS_ZONE_NAME` | 专用测试 Zone 的 Cloudflare ID 和名称 |
| `DNS_MANAGED_SUFFIX` | Zone 内专用、未被其他系统管理的子域 |

Cloudflare API token 仅授予该测试 Zone 的 DNS 编辑能力。先确认 `api.<DNS_MANAGED_SUFFIX>` 和本次两个主机对应的 `h-<host-uuid-without-hyphens>.<DNS_MANAGED_SUFFIX>` 名称没有已有记录；协调器会拒绝接管已有记录或外部修改记录。

在 Core 上核实 SSH 配置和 known_hosts 均由 root 管理，文件为普通文件且不可被组或其他用户写入。按默认拒绝阶段，以下文件保持空白：

- `/etc/nanpanel/ssh-aliases.allowlist`
- `/etc/nanpanel/ssh-units.allowlist`
- 两台 Agent 上 `/etc/nanpanel/managed/systemd-units.allowlist`、`journal-units.allowlist`、`packages.allowlist`、`files.allowlist`、`containers.allowlist`、`network.allowlist` 和 `container-runtime`

Relay 和 Agent 的最小部署配置及证书要求见 [Relay 部署说明](../../deploy/relay/README.md)。Cloudflare 协调器默认关闭；只有确认 Zone、suffix、Token scope 和目标记录均为本次测试专用后，才启用 `NANPANEL_DNS_ENABLED=true`，并设置 `NANPANEL_CLOUDFLARE_DNS_TOKEN`、`NANPANEL_CLOUDFLARE_ZONE_ID`、`NANPANEL_CLOUDFLARE_ZONE_NAME`、`NANPANEL_DNS_MANAGED_SUFFIX`。保存测试前的配置摘要，但不要输出 token 值。

## 主机绑定 SSH 与 Agent 接入

1. 两份 SSH allowlist 都为空时，从管理界面分别对两台主机执行 SSH 探测、systemd 状态和 journal 请求、重启请求。每项必须拒绝；越权请求返回 `SSH_ALIAS_NOT_ALLOWED` 或 `SSH_UNIT_NOT_ALLOWED`，审计中对应资源和动作为 `DENIED`。记录状态码、错误码与脱敏审计行。
2. 仅为本轮测试填入精确绑定：

   ```text
   # /etc/nanpanel/ssh-aliases.allowlist
   HOST_A_UUID SSH_ALIAS_A
   HOST_B_UUID SSH_ALIAS_B
   ```

   再用 A 的 host UUID 配 B 的 alias（以及反向组合）探测，两者都必须拒绝。正确绑定只执行固定 `true` 探测，known_hosts 不匹配时必须失败。
3. 将两个测试 unit 分别写入 `/etc/nanpanel/ssh-units.allowlist`，按 host UUID 精确授权。状态查询只返回固定 systemd 属性；journal 查询只能读取获准 unit 且不得超过请求行数和响应大小上限。尝试查询/重启未授权 unit 必须拒绝并留下 `DENIED` 审计。
4. 对测试 unit 提交 SSH restart。记录幂等键、Operation ID、重启前后状态和审计结果；重复提交同一幂等键必须返回同一操作，不得重复创建副作用。若 SSH 在副作用后断开，操作应为 `UNKNOWN` 并要求对账，不能用新幂等键盲目重试。
5. 通过固定 SSH 安装流程在两台主机初始化 Agent。一次性接入码只能使用一次；Core 必须通过已登记客户端证书确认 mTLS 首次心跳后才报告安装成功。验证资产 UUID、Agent ID、证书身份和主机对应关系均正确，且 token 未进入 Operation、审计或日志。

## Helper allowlist 与断线恢复

1. 在两台主机所有 Helper allowlist 为空时核对 Agent 能力清单：未配置的软件包、受保护文件、容器、运行时、网络规则、journal unit 不得被上报；Helper 的 systemd restart 能力即使上报，也不得授权任何未列出的 unit。记录清单与 Core 对无能力操作的拒绝结果。
2. 为某一操作类型仅配置一个可撤销测试项，等待 Agent 上报对应能力，再经 Core 请求同类型但未列出的目标。Core 可能按能力接受请求，但 Helper 必须独立返回拒绝；随后对获准目标执行操作并验证成功。这样能区分 Core 能力门控与 Helper 目标 allowlist。按目标主机能力覆盖所需的软件包、文件、容器和网络类型；网络规则测试必须有 30–300 秒自动回滚，并记录规则应用、实时规则核验、systemd 回滚单元和到期后规则消失。容器只使用 loopback 发布端口和预设镜像。
3. 验证 Operation 幂等重放和结果回执：选择可安全重复核对的测试操作，记录操作 ID 与 Helper 审计；在操作完成但 Core 尚未收到确认的窗口中切断 Agent 到 Core 的链路，再恢复链路。结果应从本地持久日志重放并确认，已完成操作不得再次执行；处于不确定状态的记录不得自动重做。
4. 移除本轮临时 allowlist 项并重启 Helper，使其重新读取 root allowlist；确认 Agent 后续心跳不再报告对应能力、Core 不再派发该操作。检查状态、审计和输出中没有密码、token、Cookie、私钥或原始秘密请求体。

## Relay、NAT 与 Cloudflare DNS

1. 配置 Core Gateway 与 Agent 使用两个不同 Relay origin。Core Gateway 使用 `NANPANEL_GATEWAY_RELAY_ENDPOINTS`、`NANPANEL_GATEWAY_RELAY_TLS_DOMAIN`、`NANPANEL_GATEWAY_RELAY_CA`、`NANPANEL_GATEWAY_RELAY_CERT`、`NANPANEL_GATEWAY_RELAY_KEY` 和 `NANPANEL_GATEWAY_CLUSTER_ID`；每个 Relay 使用 `NANPANEL_RELAY_CLUSTER_ID`、`NANPANEL_RELAY_CORE_CERT_FINGERPRINTS` 及其 mTLS 文件；Agent 使用 `NANPANEL_AGENT_RELAY_ENDPOINTS`。核对 Core、Agent 与 Relay 使用相同 cluster ID、Relay Core 指纹 allowlist 和 Core/Agent mTLS 身份。日志不得包含私钥。
2. 在限制型 NAT 主机封锁 UDP 入站/出站或按既定 NAT 场景限制直连，确认 Agent 同时建立两条 Relay 控制流，且经 Relay 下发的受控探测/操作能回到正确主机。停掉其中一个 Relay，操作仍应由另一个 Relay 完成；恢复后确认连接重新建立。保留连接状态、时间戳和对应 Core/Agent/Relay 日志。
3. 等待每个 Relay 连续报告健康并达到协调器的健康门槛后，核对 Cloudflare Zone 中 `api.<DNS_MANAGED_SUFFIX>` 与两台在线主机的 CNAME。Cloudflare API 回读必须显示目标内容匹配、TTL 为 60 且未代理；从至少两个公共递归解析器查询并记录回答、TTL 和时间。
4. 隔离当前首选 Relay，使其连续报告失败；少于三次、间隔不足或状态过期时，DNS 不得提前切换。达到三次有效间隔失败后核对 CNAME 切换到健康目标；在 120 秒冷却期内制造反向候选变化，记录协调器保持当前记录的证据。冷却结束后验证可再次切换。公共解析收敛时间单独记录，不以 Cloudflare API 写入成功代替公共 DNS 验收。
5. 测试 Core Gateway/主 Core 短暂掉线与恢复：Relay 不接受旧 fencing epoch 接管，Agent 保持/重建允许的出站通道，Core 恢复后数据库租约、Relay epoch 和记录对账收敛到当前状态。记录数据库 leader epoch、Relay 持久 epoch、租约和 DNS 读回结果。

## 证据与清理

每项证据都记录 UTC 时间、主机 UUID、脱敏操作 ID、期望结果、实际结果和日志/审计位置。保留 HTTP 状态码与错误码、Core 数据库审计和 Operation 状态、Agent/Helper 持久日志状态、Relay 连接/epoch、Cloudflare API 回读和公共 DNS 查询结果。Mock、单元测试、构建成功或 HTTP 200 都不能替代上述现场证据。

验收后先关闭 DNS 协调器并确认 Core 不再写入，再仅清理本轮在专用测试 suffix 下创建且数据库标记为受管的记录；删除临时测试 allowlist 项，恢复 NAT 规则和服务状态，撤销测试接入证书/token，并记录清理结果。不要删除无关记录、卷、密钥或主机数据。
