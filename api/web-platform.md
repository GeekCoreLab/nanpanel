# Web 平台登录与管理 API

版本：`0.1.0-draft.2`。接口和 DTO 统一定义在 [openapi.yaml](openapi.yaml)，本文件补充交互与安全语义。`/auth/*` 已实现持久化账号、Cookie 会话、CSRF、本人改密与会话撤销；管理员账号、内置角色、平台设置、认证/管理审计查询、主机和 Agent 只读查询、systemd unit 观测及仪表盘统计也已实现；Agent mTLS 接入、systemd 重启任务及有界 journal 查询已实现；证书吊销/续期仍待实现。密码采用随机盐 PBKDF2-HMAC-SHA256（600000 次）；常见密码当前使用本地基础拒绝列表，尚未接入完整泄露密码库。登录限流为单实例内存窗口，每来源地址及规范化账号每分钟 20 次。生产配置 `NANPANEL_ORIGIN` 为对外同源 HTTPS 地址；本地开发仅 dev profile 关闭 Secure。

## 范围

“平台管理”包括管理员账号、本人会话、内置权限、仪表盘、非机密设置和审计；主机运维沿用已有 Hosts / Agents / SystemServices / Operations 接口。平台账号与 Linux 用户独立。

本版仅内置 `ADMIN`，允许管理多个管理员账号；不开放公开注册、自定义角色、租户、忘记密码邮件、MFA、OIDC 或 API Token。首次管理员由部署侧显式 bootstrap，禁止默认密码和匿名安装接口；落地账号持久化后，部署密码不得在每次重启时覆盖已修改密码。需要另行设计部署侧恢复流程。

## 登录与会话

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/auth/csrf` | 获取与预认证/已认证会话绑定的 CSRF token |
| POST | `/api/v1/auth/login` | 用户名密码登录或同账号重新验证 |
| POST | `/api/v1/auth/logout` | 退出当前浏览器会话 |
| GET | `/api/v1/auth/me` | 获取当前用户、权限、会话和 CSRF token |
| PUT | `/api/v1/auth/password` | 验证旧密码并修改本人密码 |
| GET | `/api/v1/auth/sessions` | 列出本人有效会话 |
| DELETE | `/api/v1/auth/sessions/{sessionId}` | 撤销本人指定会话 |

浏览器首次登录流程：

1. `GET /auth/csrf`：设置短期预认证 Cookie，返回 CSRF token；该 Cookie 没有任何管理权限。预认证上下文有效期 10 分钟。
2. `POST /auth/login`：提交 JSON 用户名/密码，同时携带 Cookie 和 `X-CSRF-Token`。服务端校验来源与 token，验证凭据。
3. 成功返回 `SessionContext`，销毁旧预认证/当前浏览器旧会话，轮换 Cookie 和 CSRF token。会话凭证不进入 JSON，JSON 中的 `session.id` 仅供展示/撤销，不能用于认证。
4. 前端将返回的 CSRF token 和身份保存在内存。后续请求由浏览器发送 Cookie；POST/PUT/PATCH/DELETE 均携带 CSRF 头，已有服务重启接口也适用。
5. 页面刷新后调用 `/auth/me` 恢复身份；401 返回登录界面，403 根据 Problem.code 展示权限不足、强制改密或重新验证流程。前端权限仅控制展示，后端始终独立授权。

目标使用服务端随机不透明会话，不向 Web 发放 JWT/refresh token，不在 localStorage 保存密码或认证凭证。Cookie 名 `nanpanel_session`，生产属性为 `Secure; HttpOnly; SameSite=Lax; Path=/`，无 Domain。响应使用 `Cache-Control: no-store`。服务端设置 30 分钟空闲超时及 12 小时绝对超时，客户端请求只能续空闲时间，不能延长绝对期限；读取会话返回当前有效的到期时间。预认证会话不能访问 `/auth/me` 或管理端点。

正式部署采用 HTTPS，同源 Web/API 为默认方式。OpenAPI 中的 HTTP 回环地址供当前脚手架查看；未来本地 Cookie 测试优先使用本地 HTTPS，仅显式本地开发配置可关闭 Secure。前后端跨端口开发需要同源代理或明确配置的可信 Origin、凭据化请求和 CORS，不能使用通配来源。写请求验证 Origin，CSRF bootstrap 验证可信 Origin 或同源 Fetch Metadata/Referer；不允许仅凭 Cookie 放行跨站请求。CSRF token 校验不能被 SameSite 取代。

未登录/过期登录统一 401；用户名不存在、禁用或密码错误的登录响应均为 `INVALID_CREDENTIALS`。会话端点不返回 Basic WWW-Authenticate challenge，以免触发浏览器内置密码框。登录按可信来源地址和规范化账号分别限流，重置密码、改密等敏感操作也限流，429 附 Retry-After；不能无条件信任客户端提交的转发 IP。

已登录浏览器可通过 `/auth/login` 使用同账号密码重新验证，轮换当前会话并更新 `authenticatedAt`，其他浏览器不受影响；不允许静默改为另一账号。有效会话最多 10 个，新登录超出限制时撤销最早创建的非当前会话。

## 平台管理

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/admin/overview` | 仪表盘主机、操作及管理员统计 |
| GET / POST | `/api/v1/admin/users` | 列表 / 创建管理员 |
| GET / PATCH | `/api/v1/admin/users/{userId}` | 详情 / 更新显示名、启用状态 |
| PUT | `/api/v1/admin/users/{userId}/password` | 重置其他管理员密码 |
| GET | `/api/v1/admin/roles` | 只读内置 ADMIN 及权限列表 |
| GET / PATCH | `/api/v1/admin/settings` | 读取 / 更新非机密平台设置 |
| GET | `/api/v1/admin/audit-events` | 按主体、动作、结果、时间筛选审计 |
| GET | `/api/v1/admin/audit-events/{eventId}` | 查询单条脱敏审计 |

所有管理操作及主机运维接口要求有效 ACTIVE 管理员会话。`mustChangePassword=true` 时只允许 `/auth/*`，`permissions=[]`，其他接口返回 403 `PASSWORD_CHANGE_REQUIRED`。首个管理员角色固定，不以只读角色接口暗示已支持动态 RBAC。

创建账号使用初始密码，服务端设定 ADMIN、ACTIVE、mustChangePassword=true；首次登录后必须通过 `/auth/password` 修改。新密码采用 15–128 个 Unicode 字符，不截断，不要求固定字符组合，拒绝常见/泄露密码和与现有密码相同的值；检查方案在实现时确定，不能将原始密码发送给第三方。登录允许已有密码格式，不能因新密码长度规则绕过迁移或使 bootstrap 密码无法提交。密码只存安全的加盐密码哈希，不能持久化明文或回传哈希。

本人改密要求旧密码和 CSRF，成功时撤销全部本人会话并清 Cookie，重新走登录流程。管理员重置其他人的密码要求 5 分钟内的密码认证、CSRF 和目标用户 If-Match；成功强制目标下次改密，并撤销其全部会话。不得用管理员重置接口修改本人密码。

创建账号、修改账号状态/显示名、重置他人密码及修改设置要求最近 5 分钟内的密码认证，否则 403 `REAUTHENTICATION_REQUIRED`。重新登录后刷新资源详情和 ETag，再确认提交；前端不自动重放密码等敏感写入。

用户详情与设置详情返回不透明强 ETag，PATCH 和重置密码通过 If-Match 检查版本，缺失 428、过期 412。PATCH 仅合并提供字段，至少一个且不接受 null，不允许修改 username、roles、密码哈希等未列字段。用户名规范化为小写后唯一，创建后不可改。同步管理写入不复用服务任务的 Idempotency-Key 机制；密码重置等请求结果未知时需要重新读取并人工确认，不能自动换值重试。

禁止禁用自己或最后一个 ACTIVE 管理员，事务内执行并发约束；冲突 409。禁用时立即撤销目标全部会话，已撤销会话不因重新启用而恢复。未下发的 root 操作重新授权，不再下发；已经执行的操作不会因撤销会话自动回滚，继续按任务状态对账。会话绑定账号授权版本，禁用、改密后下个请求即失效，不依赖浏览器主动退出。初版不删除用户以保留审计关联。

可编辑设置仅包含站点名称、默认展示时区、Host 离线阈值、已结束任务保留天数。设置不能读写数据库密码、CA、私钥、管理员密码、任意环境变量或主机配置文件。任务保留天数最小 7 天，与幂等窗口一致；非终态及 UNKNOWN 不清理，降低保留期也不能提前删除新接受任务对应的承诺去重记录。

审计只读，记录主体、动作、资源、结果、时间、来源及 traceId。当前持久记录登录成功/拒绝、注销、本人改密、会话撤销、账号和设置变更，以及来源/CSRF、未认证、强制改密、停用账号和重新认证拒绝；设置变更和账号变更与审计写入同一事务。systemd 重启/journal、SSH 探测/恢复/初始化和 Helper 操作均记录操作及结果状态；日志正文和秘密凭据不进入审计。不得记录密码、Cookie、CSRF token、注册码、私钥、密码哈希和原始请求体。审计保留与任务保留分开设计。

## 现有实现与参考

当前 `/auth/*` 和 `/system` 使用数据库账号及 Cookie 会话，Basic 不再接受；管理员账号/角色、平台设置、认证/管理审计查询、主机/Agent 只读查询及仪表盘统计也已接入持久化数据库。systemd 服务观测和重启/journal 任务端点已接入数据库、Gateway、Agent 与 Helper；观测过期时明确返回 UNKNOWN。密码泄露库与多实例共享限流尚未接入，证书吊销/续期也仍待实现。

Cookie 生命周期与轮换设计参考 [OWASP Session Management](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)，CSRF 同步 token 与来源校验参考 [OWASP CSRF Prevention](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)。本文的具体超时、角色与端点是本项目设计选择。
