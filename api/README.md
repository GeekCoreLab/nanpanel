# NaNPanel API 契约

[openapi.yaml](openapi.yaml) 是 Web → Core HTTP API 的设计基线，格式为 [OpenAPI 3.1.1](https://spec.openapis.org/oas/v3.1.1.html)。可导入支持该版本的 API 编辑器、文档或 Mock 工具。

版本：`0.1.0-draft.2`。当前已实现 `/auth/*`、管理员与审计 API、主机和 Agent 查询、受控 Agent 接入、Helper 状态心跳、systemd 服务观测和重启/有界 journal 查询、类型化 Helper 操作、限时防火墙放行、资产 SSH 固定探测与 systemd 状态/日志/重启、固定脚本 Agent 初始化及操作任务查询；Relay 双向流、安装时签名故障切换端点清单和 DNS 故障滞回/冷却/恢复对账已有代码实现，Cloudflare 公共 DNS 传播与真实故障验收、其他 agentless 管理和拓扑部署仍待完成。操作级 `x-implementation-status` 区分已有端点与规划端点。Agent 接入不授予任意远程命令或 root shell。

## 接口范围

新增 Web 登录、会话、管理员账号、内置角色、仪表盘、平台设置和审计 API，端点表与语义见 [Web 平台 API](web-platform.md)。下表为原有主机运维功能链。

Kubernetes 工作负载应用、YAML 派生拓扑、版本草稿、资源级 diff 与显式部署见 [Kubernetes 应用 API](kubernetes-applications.md)。

网站（域名反向代理）站点记录与受管 Nginx 下发见 [网站 API](reverse-proxy-sites.md)。

基于既有观测的告警规则与实时求值见 [告警 API](alerts.md)。

已部署数据库容器的实例视图与连接地址见 [数据库实例 API](databases.md)。

控制台文件管理复用既有 Helper 能力（无新增端点），交互与结果结构见 [主机文件管理](files.md)。

## 主机运维功能链

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/system` | 查询主控信息 |
| GET | `/api/v1/hosts` | 主机列表，支持状态筛选及游标分页；能力明细放在主机详情 |
| GET | `/api/v1/hosts/{hostId}` | 主机详情及 Agent 绑定 |
| GET | `/api/v1/hosts/{hostId}/network-inventory` | 最近一次网卡、IP 地址和路由表快照 |
| GET | `/api/v1/agents/{agentId}` | Agent 在线状态、能力及 Helper 可用性 |
| POST | `/api/v1/agent-enrollments` | 创建主机资产和一次性 Agent 接入码（需 Cookie + CSRF） |
| POST | `/api/v1/agent-enrollments/redeem` | 用一次性 Bearer 接入码兑换 Agent 客户端证书 |
| GET | `/api/v1/agent-installation` | 查询 Gateway 端口和控制器 TLS 指纹 |
| GET | `/api/v1/agent-controller-cert` | 下载控制器 TLS PEM 证书 |
| GET | `/api/v1/agent-download/linux-amd64` | 下载随 Core 镜像发布的 Linux x86_64 Agent |
| GET | `/api/v1/helper-download/linux-amd64` | 下载随 Core 镜像发布的 Linux x86_64 root Helper |
| POST | `/api/v1/agents/{agentId}/updates` | 经本地 Gateway 请求在线 Agent 异步更新 |
| GET | `/api/v1/agents/{agentId}/updates/{operationId}` | 查询 Agent 更新任务结果 |
| GET | `/api/v1/hosts/{hostId}/services` | 主机 systemd 服务列表 |
| GET | `/api/v1/hosts/{hostId}/services/{serviceId}` | 服务观测、可用动作及 ETag |
| POST | `/api/v1/hosts/{hostId}/services/{serviceId}/restart` | 接受服务重启任务 |
| POST | `/api/v1/hosts/{hostId}/services/{serviceId}/journal` | 查询服务最近 12 行 journal |
| POST | `/api/v1/hosts/{hostId}/operations` | 提交类型化 Helper 操作，包括文件管理、受限容器、临时防火墙操作、受管 Nginx 路由写入/撤回/证书安装和 K3s 只读安装前置校验 |
| GET/POST | `/api/v1/cluster-providers/{providerId}/clusters/{clusterId}/credentials` | 查询凭据元数据或加密托管 KubeEdge 凭据（创建需近期重新验证；秘密不会再次返回） |
| DELETE | `/api/v1/cluster-providers/{providerId}/clusters/{clusterId}/credentials/{credentialId}` | 撤销 KubeEdge 凭据（需近期重新验证） |
| POST | `/api/v1/hosts/{hostId}/ssh/probe` | 使用主机资产 SSH 凭据和已确认 host key 执行只读可达性探测 |
| POST | `/api/v1/hosts/{hostId}/ssh/systemd/status` | 使用主机资产 SSH 配置执行固定 `systemctl show`，返回固定状态字段 |
| POST | `/api/v1/hosts/{hostId}/ssh/systemd/journal` | 使用主机资产 SSH 配置执行固定、有行数和字节上限的 journal 查询 |
| POST | `/api/v1/hosts/{hostId}/ssh/systemd/restart` | 使用主机资产 SSH 配置执行固定 systemd 重启及 active 校验 |
| POST | `/api/v1/hosts/{hostId}/ssh/agent/install` | 使用资产 SSH 配置、一次性 enrollment token 和镜像内固定安装脚本初始化 Agent |
| GET | `/api/v1/operations` | 平台级任务列表（**按创建时间倒序**，键集分页）。查询参数：`status`、`capability`、`hostId`、`limit`（1–100，默认 20）、`cursor`。响应含 `nextCursor`、`limit`、`statuses`、`capabilities`（当前库中实际出现过的能力，供控制台做筛选项），每条任务带 `hostname`。游标绑定筛选条件，条件变化返回 400 `INVALID_CURSOR` |
| GET | `/api/v1/operations/{operationId}` | 查询执行与验证结果 |

这里的 `SystemService` 是主机 systemd unit，与平台逻辑服务 `Service` 分开。URL 使用平台 UUID，服务端解析受管 unit，不接受任意命令或客户端指定的主机身份。

Agent 接入码由数据库保存 SHA-256 哈希，明文仅在创建响应中出现一次，15 分钟到期且只能兑换一次。安装器在发出接入码前先用 UI 展示的证书 SHA-256 指纹校验控制器证书；Agent 在本机生成私钥和 CSR，Core 只签发 CN 与预分配 Agent UUID 一致的客户端证书。mTLS Gateway 校验证书链，并将证书指纹随心跳交给 Core 再次匹配登记记录。安装响应会携带可选的 2–8 个 Relay HTTPS 源站和由 Core Ed25519 签名的故障切换端点清单，安装器将其写入 Agent 环境配置；Agent 启动时验签，清单篡改或签名无效时拒绝启动；Relay 不授权身份，Core 心跳校验后才开放控制路由。Agent 上报 Helper 状态、主机清单和 systemd 服务观测，并每 30 秒通过 `ip -j` 采集网卡、IP 地址与 IPv4/IPv6 路由快照。网络快照按主机保存最近一次结果，读取接口不会触发远程执行。更新任务经 Core 持久化并由本机 root Helper 从固定控制器下载，原子替换 Agent。Helper 只暴露固定类型操作：systemd unit 使用格式校验，软件包使用安全名称校验，受保护文件只写入固定目录，容器部署使用校验过的镜像和 loopback 端口，临时防火墙参数通过接口/协议/端口校验并要求 30–300 秒自动回滚；主机文件管理可浏览绝对路径并读写或删除普通文件，拒绝 `.`、`..` 和符号链接，读取最多 64 KiB、写入最多 32 KiB、目录最多返回 200 项。读取内容会留在操作任务结果中。Helper 不依赖逐项权限配置，也不提供任意 Shell。`k3s.preflight` 仅按 server/agent 角色读取主机资源、发行版、systemd、cgroup 和已有 K3s 安装状态，并返回阻塞项及警告；该操作不安装或修改主机。容器操作限于列表、100 行日志、启停和固定安全参数部署。journal 最多返回 12 行；操作结果经 Agent 本地持久化，Core 确认后删除。SSH 的固定探测、systemd 状态/有界日志/重启及 Agent 初始化使用主机资产内加密保存且已确认 host key 的 SSH 配置；一次性接入码经 SSH stdin 传递，不进入操作记录或审计。安装任务持久化、幂等并等待 mTLS 心跳；SSH 断线或 token 已兑换但未见心跳时标记 UNKNOWN。其他 agentless 管理能力、证书吊销/续期和任意指令式路由配置仍未实现；受管 Nginx 路由可通过 `nginx.route.set` 写入、`nginx.route.remove` 撤回（仅操作带 NaNPanel 归属标记的固定目录文件）；TLS 证书经 `nginx.tls.install` 写入受管证书目录，私钥不进入 Core 存储。Agent gRPC 协议见 [agent.proto](../agent/protocol/agent.proto)。

## 认证、授权与读取

Web 使用服务端不透明 Cookie 会话与 CSRF 校验，已替换脚手架 Basic 认证；详见 [Web 平台 API](web-platform.md)。管理员可在“账号与会话”创建短期、可撤销且带范围的 API 密钥；Bearer 密钥只开放明确授权的 Kubernetes 服务清单和探测读取端点。管理员创建接入码需已完成首次改密；安装器兑换端点只接受无浏览器 Cookie 的一次性 Bearer 接入码，不能访问 Web 管理资源。Agent 使用独立 mTLS 身份。

Core 根据已认证主体执行管理员访问控制，Agent/root Helper 再验证 mTLS 身份、IPC 调用方和固定操作参数。网络操作要求先创建 30–300 秒定时回滚，再由仅获 `CAP_NET_ADMIN` 的瞬时 worker 应用并核验；不接受命令文本。SSH 固定操作必须使用主机资产的加密凭据及已确认 host key，unit 名称通过格式校验；日志查询最多 100 行、24 KiB，审计记录原因但不保存日志正文。`supportedActions` 根据 Agent 主机级能力与观测新鲜度生成，只用于界面提示；操作执行时再次校验 Agent、Helper 和所需主机级能力。操作记录的 `requestedBy` 由 Core 填充，不接受客户端提供。

主机状态描述管理通道，不能推断已有业务链路已中断或已恢复。服务 GET 返回 Core 的最近观测，不触发远程命令；离线或过期观测标记 `observationStale=true`。空列表只表示没有可返回的已发现资源。UUID 不存在或服务不属于路径指定主机返回 404。

分页默认 20、最大 100，资源 UUID 升序；`nextCursor=null` 表示结束。游标与授权范围、筛选条件绑定，不允许解析或手工修改。分页不承诺跨请求快照一致性。

## 提交重启与防重复执行

1. 读取服务详情，取得完整表示对应的不透明强 `ETag`。`generation` 是管理配置/策略版本；ETag 还涵盖观测和调用者授权视图，不能由前端自行用 generation 拼接。
2. 提交 JSON 请求体 `reason` 和可选 `timeoutSeconds`，必须携带 `Idempotency-Key` 与 `If-Match`（原样保留 ETag 的双引号）。本版不支持 `If-Match: *` 或多个 ETag。
3. Core 先认证授权，再在原主体、方法及路径范围内检查幂等记录。相同键、语义一致的规范化请求体（含默认值）和完全相同的 If-Match 返回原任务，不重复执行。幂等冲突返回 409 `IDEMPOTENCY_KEY_REUSED`。
4. 新请求校验版本、systemd 观测新鲜度、Agent 会话、已报告能力、已知 Helper 状态及服务在途操作锁。未提供 If-Match 返回 428，过期返回 412；观测已过期或 Agent/Helper 已知不可用返回 503，能力不支持返回 422。这些前置拒绝不新建任务。连接中断时客户端不能据此推断请求未接受，应使用原键重试。
5. Core 原子持久化任务和幂等记录后才允许下发；同一服务的在途或 UNKNOWN 任务阻止另一个重启，返回 409 `OPERATION_IN_PROGRESS`。Core 对相同 HTTP 请求及结果回调做持久化去重。Agent 在执行前持久化 claim、执行后持久化结果，只有 Core 确认回调后才删除结果；重连会重放未确认结果，未完成的旧 claim 返回 UNKNOWN 且不重复执行。此机制已有测试，仍待两台真实主机的故障验收。
6. 新接受任务返回 202，`Location` 指向任务，`Retry-After` 建议轮询间隔。重放未结束或 UNKNOWN 任务也返回 202；重放 SUCCEEDED/FAILED/CANCELLED 任务返回 200。重放响应 `Idempotency-Replayed=true`，首次接受为 false。无论重放时主机是否离线或 ETag 是否变化，都先返回匹配原记录，前提是调用者仍有权限。

任务与幂等记录保留至少 7 天，非终态不能提前清除。相同键超出记录保留窗口不能保证去重，客户端不得依赖无限期幂等。希望在失败后再次执行时，应先确认原任务结果、刷新服务详情，再使用新键；结果未知时不得盲目换键。

## 状态、超时与失败

- `PENDING`：已持久化，尚未执行。
- `RUNNING`：执行或验证进行中，`phase` 指示 VALIDATE/PREPARE/APPLY/VERIFY/COMMIT 等阶段。
- `SUCCEEDED`：执行成功且重新观测 systemd 为 ACTIVE，`result` 非空、`error=null`、`finishedAt` 非空。此结果不证明 HTTP 业务健康。
- `FAILED`：已有可靠失败结论，`error` 非空、`result=null`、`finishedAt` 非空。失败不保证没有副作用，需结合 `executionOutcome`。
- `CANCELLED`：系统在下发前取消的预留终态，`result=null`、`error=null`、`finishedAt` 非空；本版无客户端取消接口，不能用于表示已发出的命令被撤销。
- `UNKNOWN`：可能已执行但缺少可靠结果，`error.executionOutcome=UNCERTAIN`、`result=null`、`finishedAt=null`；保留目标互斥锁，等待 Agent/Helper 持久化执行记录与后续观测对账，可转为 SUCCEEDED 或 FAILED。仅看到服务 ACTIVE 不能证明指定重启任务确实执行过。

PENDING/RUNNING 的 `result` 和 `error` 均为 null，`finishedAt=null`。SUCCEEDED/FAILED/CANCELLED 为终态，不再变化。当前 systemd 重启在 Core 写入时直接进入 RUNNING；网络中断不得自动改成 FAILED。重启本身通常没有可撤销的补偿步骤，不能承诺恢复旧进程；`COMPENSATE` 仅为通用任务阶段预留。

`timeoutSeconds` 默认 60 秒，可选 5–300 秒，包含排队、执行和验证；第一次接受时计算 `deadlineAt`，重试不延长。到期前确定未下发可记 FAILED/DEADLINE_EXCEEDED；已下发但执行结果无法确定则记 UNKNOWN，而非宣称失败或自动重新执行。Helper 也须执行截止时间约束。

HTTP 200 的任务查询可能返回 FAILED 或 UNKNOWN。HTTP 错误统一使用 `application/problem+json`，包含 `code`、`traceId` 及可选字段错误；已接受任务的执行失败放入 `Operation.error`。`retryable` 仅描述故障是否具有暂时性，不授权自动新建 root 操作。

## 示例与校验

OpenAPI 内置登录/强制改密、管理员、会话、设置、审计，以及空列表、在线/离线主机、过期服务观测、Helper 不可用、任务接受、成功、权限拒绝和结果未知等命名示例，供前端 Mock 使用。Mock 不等于后端实现；认证和权限行为仍需真实后端验证。

在仓库根目录执行规范校验：

```sh
python -m venv /tmp/nanpanel-api-check
/tmp/nanpanel-api-check/bin/pip install -r api/requirements-dev.txt
/tmp/nanpanel-api-check/bin/python -m openapi_spec_validator api/openapi.yaml
```

主机列表、主机详情、Agent 详情、systemd 服务观测及操作任务从数据库读取；Agent 注册接入和心跳写入已实现。服务观测不代表业务健康。systemd 受限重启和 journal 查询已接通 Core、Gateway、Agent 和 Helper，但尚未完成两台独立主机的部署验收。

## 拓扑期望草稿

新增 `GET /api/v1/topology/draft` 与 `PUT /api/v1/topology/draft` 规划接口，支持带方向的端口、多合一/一分多/交换机/多进多出逻辑节点、ETag 校验与图结构约束，详见 [拓扑 API](topology.md)。它们只保存期望草稿，不包含拓扑部署。
