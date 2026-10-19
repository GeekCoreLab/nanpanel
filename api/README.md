# NaNPanel API 契约

[openapi.yaml](openapi.yaml) 是 Web → Core HTTP API 的设计基线，格式为 [OpenAPI 3.1.1](https://spec.openapis.org/oas/v3.1.1.html)。可导入支持该版本的 API 编辑器、文档或 Mock 工具。

版本：`0.1.0-draft.2`。当前已实现 `/auth/*`、Cookie 保护的 `/system`、管理员账号/角色/设置、审计查询、主机和 Agent 只读查询，以及仪表盘统计；systemd 服务、操作任务和拓扑接口仍待后端实现。操作级 `x-implementation-status` 区分已有端点与规划端点。本文不引入真实注册、远程执行或 root 操作。

## 接口范围

新增 Web 登录、会话、管理员账号、内置角色、仪表盘、平台设置和审计 API，端点表与语义见 [Web 平台 API](web-platform.md)。下表为原有主机运维功能链。

## 主机运维功能链

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/system` | 查询主控信息 |
| GET | `/api/v1/hosts` | 主机列表，支持状态筛选及游标分页 |
| GET | `/api/v1/hosts/{hostId}` | 主机详情及 Agent 绑定 |
| GET | `/api/v1/agents/{agentId}` | Agent 在线状态、能力及 Helper 可用性 |
| GET | `/api/v1/hosts/{hostId}/services` | 主机 systemd 服务列表 |
| GET | `/api/v1/hosts/{hostId}/services/{serviceId}` | 服务观测、可用动作及 ETag |
| POST | `/api/v1/hosts/{hostId}/services/{serviceId}/restart` | 接受服务重启任务 |
| GET | `/api/v1/operations` | 操作列表，支持主机、状态筛选 |
| GET | `/api/v1/operations/{operationId}` | 查询执行与验证结果 |

这里的 `SystemService` 是主机 systemd unit，与平台逻辑服务 `Service` 分开。URL 使用平台 UUID，服务端解析受管 unit，不接受任意命令或客户端指定的主机身份。

暂不包含主机/Agent 注册、证书签发、任意终端、软件包管理、拓扑部署和取消操作。Agent gRPC 草案的权威位置为 [agent.proto](../agent/protocol/agent.proto)，本文件不替代该协议。

## 认证、授权与读取

Web 使用服务端不透明 Cookie 会话与 CSRF 校验，已替换脚手架 Basic 认证；详见 [Web 平台 API](web-platform.md)。Agent 继续使用独立 mTLS 身份。

Core 根据已认证主体执行 `subject/action/resource` 授权，Agent/root Helper 再验证本地操作范围。`allowedActions` 是 UI 提示，不能作为执行授权凭证。操作记录的 `requestedBy` 由 Core 填充，不接受客户端提供。

主机状态描述管理通道，不能推断已有业务链路已中断或已恢复。服务 GET 返回 Core 的最近观测，不触发远程命令；离线或过期观测标记 `observationStale=true`。空列表只表示没有可返回的已发现资源。UUID 不存在或服务不属于路径指定主机返回 404。

分页默认 20、最大 100，资源 UUID 升序；`nextCursor=null` 表示结束。游标与授权范围、筛选条件绑定，不允许解析或手工修改。分页不承诺跨请求快照一致性。

## 提交重启与防重复执行

1. 读取服务详情，取得完整表示对应的不透明强 `ETag`。`generation` 是管理配置/策略版本；ETag 还涵盖观测和调用者授权视图，不能由前端自行用 generation 拼接。
2. 提交 JSON 请求体 `reason` 和可选 `timeoutSeconds`，必须携带 `Idempotency-Key` 与 `If-Match`（原样保留 ETag 的双引号）。本版不支持 `If-Match: *` 或多个 ETag。
3. Core 先认证授权，再在原主体、方法及路径范围内检查幂等记录。相同键、语义一致的规范化请求体（含默认值）和完全相同的 If-Match 返回原任务，不重复执行。幂等冲突返回 409 `IDEMPOTENCY_KEY_REUSED`。
4. 新请求校验版本、Agent 会话、已报告能力、已知 Helper 状态及服务在途操作锁。未提供 If-Match 返回 428，过期返回 412；Agent/Helper 已知不可用返回 503，能力不支持返回 422。这些前置拒绝不新建任务。连接中断时客户端不能据此推断请求未接受，应使用原键重试。
5. Core 原子持久化任务、幂等记录及目标互斥状态后才允许下发；同一服务的在途或 UNKNOWN 任务阻止另一个重启，返回 409 `OPERATION_IN_PROGRESS`。Agent/Helper 使用持久化操作 ID 和幂等记录去重，不能仅依赖 HTTP 层去重。
6. 新接受任务返回 202，`Location` 指向任务，`Retry-After` 建议轮询间隔。重放未结束或 UNKNOWN 任务也返回 202；重放 SUCCEEDED/FAILED/CANCELLED 任务返回 200。重放响应 `Idempotency-Replayed=true`，首次接受为 false。无论重放时主机是否离线或 ETag 是否变化，都先返回匹配原记录，前提是调用者仍有权限。

任务与幂等记录保留至少 7 天，非终态不能提前清除。相同键超出记录保留窗口不能保证去重，客户端不得依赖无限期幂等。希望在失败后再次执行时，应先确认原任务结果、刷新服务详情，再使用新键；结果未知时不得盲目换键。

## 状态、超时与失败

- `PENDING`：已持久化，尚未执行。
- `RUNNING`：执行或验证进行中，`phase` 指示 VALIDATE/PREPARE/APPLY/VERIFY/COMMIT 等阶段。
- `SUCCEEDED`：执行成功且重新观测 systemd 为 ACTIVE，`result` 非空、`error=null`、`finishedAt` 非空。此结果不证明 HTTP 业务健康。
- `FAILED`：已有可靠失败结论，`error` 非空、`result=null`、`finishedAt` 非空。失败不保证没有副作用，需结合 `executionOutcome`。
- `CANCELLED`：系统在下发前取消的预留终态，`result=null`、`error=null`、`finishedAt` 非空；本版无客户端取消接口，不能用于表示已发出的命令被撤销。
- `UNKNOWN`：可能已执行但缺少可靠结果，`error.executionOutcome=UNCERTAIN`、`result=null`、`finishedAt=null`；保留目标互斥锁，等待 Agent/Helper 持久化执行记录与后续观测对账，可转为 SUCCEEDED 或 FAILED。仅看到服务 ACTIVE 不能证明指定重启任务确实执行过。

PENDING/RUNNING 的 `result` 和 `error` 均为 null，`finishedAt=null`。SUCCEEDED/FAILED/CANCELLED 为终态，不再变化。初次接受为 PENDING，运行后更新 RUNNING；网络中断不得自动改成 FAILED。重启本身通常没有可撤销的补偿步骤，不能承诺恢复旧进程；`COMPENSATE` 仅为通用任务阶段预留。

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

主机列表、主机详情和 Agent 详情从持久化 inventory 表读取；注册接入、心跳写入、Helper 观测、systemd 服务与操作任务仍按上述契约逐步实现。

## 拓扑期望草稿

新增 `GET /api/v1/topology/draft` 与 `PUT /api/v1/topology/draft` 规划接口，支持带方向的端口、多合一/一分多/交换机/多进多出逻辑节点、ETag 校验与图结构约束，详见 [拓扑 API](topology.md)。它们只保存期望草稿，不包含拓扑部署。
