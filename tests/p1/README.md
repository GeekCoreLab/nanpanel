# P1 真实环境验收

本手册用于补齐 [项目计划](../../PROJECT_PLAN.md) 中 P1 阶段需要真实主机、Relay 和 Cloudflare DNS 的验收。Core、Agent、Helper、SSH 固定操作和 DNS 协调器的本地构建与自动化测试通过，只能证明代码路径；在本手册各项都有现场证据前，P1 仍未通过整体验收。

## 开始前准备

准备两台可回滚的普通 Linux 主机（均非 K3s 节点）、一个专用 Cloudflare 测试 Zone 或其独立子域、两个分属不同故障域的 Relay，以及正在运行的 Core。不要使用生产 DNS 名称或生产服务做重启实验。验收期间记录 Core、Agent、Helper、Relay 和数据库版本/镜像摘要及 UTC 时间。

开始前填好以下值；私钥、密码、一次性接入码、登录 Cookie、CSRF token 和 Cloudflare token 不写入本文件、命令历史或验收输出：

| 输入 | 说明 |
| --- | --- |
| `HOST_A_UUID`、`HOST_B_UUID` | Core 中两台测试主机的资产 UUID |
| `SSH_ADDRESS_A`、`SSH_ADDRESS_B` | 各主机的 IPv4、IPv6 或 DNS 地址 |
| `SSH_TEST_UNIT_A`、`SSH_TEST_UNIT_B` | 每台主机上可安全重启的测试 unit；优先使用专用测试服务 |
| `CORE_ORIGIN` | Agent 安装使用的 HTTPS Core origin |
| `RELAY_A_ORIGIN`、`RELAY_B_ORIGIN` | 两个 HTTPS Relay origin；必须位于不同故障域 |
| `DNS_ZONE_ID`、`DNS_ZONE_NAME` | 专用测试 Zone 的 Cloudflare ID 和名称 |
| `DNS_MANAGED_SUFFIX` | Zone 内专用、未被其他系统管理的子域 |

Cloudflare API token 仅授予该测试 Zone 的 DNS 编辑能力。先确认 `api.<DNS_MANAGED_SUFFIX>` 和本次两个主机对应的 `h-<host-uuid-without-hyphens>.<DNS_MANAGED_SUFFIX>` 名称没有已有记录；协调器会拒绝接管已有记录或外部修改记录。

在 Core 管理界面为每项主机资产配置 SSH 地址、端口、用户名和密码或私钥，然后探测并由管理员确认 host key 指纹。凭据由 Core 加密保存；检查响应、Operation、审计摘要和应用日志中均没有密码或私钥。Agent 与 Helper 不需要逐项操作名单配置。升级或重装后应确认旧 `/etc/nanpanel/managed/*.allowlist` 和 `allowlist-mode` 文件已由安装脚本删除；`container-runtime` 仍用于选择 Docker 或 Podman。Relay 和 Agent 的最小部署配置及证书要求见 [Relay 部署说明](../../deploy/relay/README.md)。Cloudflare 协调器默认关闭；只有确认 Zone、suffix、Token scope 和目标记录均为本次测试专用后，才启用 `NANPANEL_DNS_ENABLED=true`，并设置 `NANPANEL_CLOUDFLARE_DNS_TOKEN`、`NANPANEL_CLOUDFLARE_ZONE_ID`、`NANPANEL_CLOUDFLARE_ZONE_NAME`、`NANPANEL_DNS_MANAGED_SUFFIX`。保存测试前的配置摘要，但不要输出 token 值。

## 主机资产 SSH 与 Agent 接入

1. 为两台测试主机分别保存直连 SSH 配置，确认地址、端口、用户名、认证方式和 host key 指纹与目标一致。密码和私钥只用于加密后的资产凭据；界面读取配置时不回显秘密。使用确认过的指纹执行固定 SSH 探测，记录成功审计和主机资产 ID。
2. 读取专用测试 unit 的 systemd 状态和有界 journal，确认仅返回固定属性/日志内容，操作写入审计且不持久化为可任意执行命令。使用不合法 unit 名称和带 shell 元字符的值验证请求被参数校验拒绝。
3. 对测试 unit 提交 SSH restart。记录幂等键、Operation ID、重启前后状态和审计结果；重复提交同一幂等键必须返回同一操作，不得重复创建副作用。若 SSH 在副作用后断开，操作应为 `UNKNOWN` 并要求对账，不能用新幂等键盲目重试。
4. 通过固定 SSH 安装流程在两台主机初始化 Agent。一次性接入码只能使用一次；Core 必须通过已登记客户端证书确认 mTLS 首次心跳后才报告安装成功。验证资产 UUID、Agent ID、证书身份和主机对应关系均正确，且 token 未进入 Operation、审计或日志。

## Helper 固定操作与断线恢复

1. 核对 Agent 能力报告与目标主机的实际工具/运行时：systemd、软件包、受保护文件、主机文件、容器和防火墙能力按固定实现和运行条件报告，不依赖逐项权限名单。升级后确认旧名单配置被清理，Core、Agent 和 Helper 不再读取或匹配这些文件。
2. 在可回滚的测试主机执行一项有效操作并确认成功，例如安装已知安全名称的软件包、读写测试文件、列出/操作专用测试容器，或开放仅用于本次测试的临时防火墙端口。再提交带 shell 元字符的包名/unit/容器名、路径跳转或符号链接路径，确认固定参数校验拒绝这些请求。不要用生产服务、数据或端口测试。
3. 防火墙规则测试必须有 30–300 秒自动回滚，并记录规则应用、实时规则核验、systemd 回滚单元和到期后规则消失。容器只使用经确认的镜像、资源限制和 loopback 发布端口；不把测试端口暴露到公网。
4. 验证 Operation 幂等重放和结果回执：选择可安全重复核对的测试操作，记录操作 ID 与 Helper 审计；在操作完成但 Core 尚未收到确认的窗口中切断 Agent 到 Core 的链路，再恢复链路。结果应从本地持久日志重放并确认，已完成操作不得再次执行；处于不确定状态的记录不得自动重做。
5. 检查状态、审计和输出中没有密码、token、Cookie、私钥或原始秘密请求体。清理本轮产生的临时文件、容器、防火墙规则和测试服务；无需创建或维护任何操作名单。

## Relay、NAT 与 Cloudflare DNS

1. 配置 Core Gateway 与 Agent 使用两个不同 Relay origin。Core Gateway 使用 `NANPANEL_GATEWAY_RELAY_ENDPOINTS`、`NANPANEL_GATEWAY_RELAY_TLS_DOMAIN`、`NANPANEL_GATEWAY_RELAY_CA`、`NANPANEL_GATEWAY_RELAY_CERT`、`NANPANEL_GATEWAY_RELAY_KEY` 和 `NANPANEL_GATEWAY_CLUSTER_ID`；每个 Relay 使用 `NANPANEL_RELAY_CLUSTER_ID`、`NANPANEL_RELAY_CORE_CERT_FINGERPRINTS` 及其 mTLS 文件；Agent 使用 `NANPANEL_AGENT_RELAY_ENDPOINTS`。核对 Core、Agent 与 Relay 使用相同 cluster ID、受信 Core 证书指纹和 Core/Agent mTLS 身份。日志不得包含私钥。
2. 在限制型 NAT 主机封锁 UDP 入站/出站或按既定 NAT 场景限制直连，确认 Agent 同时建立两条 Relay 控制流，且经 Relay 下发的受控探测/操作能回到正确主机。停掉其中一个 Relay，操作仍应由另一个 Relay 完成；恢复后确认连接重新建立。保留连接状态、时间戳和对应 Core/Agent/Relay 日志。
3. 等待每个 Relay 连续报告健康并达到协调器的健康门槛后，核对 Cloudflare Zone 中 `api.<DNS_MANAGED_SUFFIX>` 与两台在线主机的 CNAME。Cloudflare API 回读必须显示目标内容匹配、TTL 为 60 且未代理；从至少两个公共递归解析器查询并记录回答、TTL 和时间。
4. 隔离当前首选 Relay，使其连续报告失败；少于三次、间隔不足或状态过期时，DNS 不得提前切换。达到三次有效间隔失败后核对 CNAME 切换到健康目标；在 120 秒冷却期内制造反向候选变化，记录协调器保持当前记录的证据。冷却结束后验证可再次切换。公共解析收敛时间单独记录，不以 Cloudflare API 写入成功代替公共 DNS 验收。
5. 测试 Core Gateway/主 Core 短暂掉线与恢复：Relay 不接受旧 fencing epoch 接管，Agent 保持/重建允许的出站通道，Core 恢复后数据库租约、Relay epoch 和记录对账收敛到当前状态。记录数据库 leader epoch、Relay 持久 epoch、租约和 DNS 读回结果。

## 证据与清理

每项证据都记录 UTC 时间、主机 UUID、脱敏操作 ID、期望结果、实际结果和日志/审计位置。保留 HTTP 状态码与错误码、Core 数据库审计和 Operation 状态、Agent/Helper 持久日志状态、Relay 连接/epoch、Cloudflare API 回读和公共 DNS 查询结果。Mock、单元测试、构建成功或 HTTP 200 都不能替代上述现场证据。

验收后先关闭 DNS 协调器并确认 Core 不再写入，再仅清理本轮在专用测试 suffix 下创建且数据库标记为受管的记录；恢复 NAT 规则和服务状态，撤销测试接入证书/token，并记录清理结果。不要删除无关记录、卷、密钥或主机数据。
