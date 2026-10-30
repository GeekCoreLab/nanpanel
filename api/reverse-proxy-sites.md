# 网站（反向代理）API

站点是「域名 → 主机本机 `127.0.0.1:端口`」的可管理记录。应用站点时，Core 把它编译成既有的受管 Nginx 路由能力 `nginx.route.set` 下发到主机上的 root Helper；不引入新的 API 网关，也不接受任意 Nginx 指令或命令文本。

TLS 终止已支持（手工证书）：`POST /{siteId}/tls` 经 `nginx.tls.install` 把证书/私钥写入主机侧受管目录，`apply` 时路由载荷带 `tls` 指向该目录内的名字，渲染 `listen …:443 ssl`。控制台「网站」页有「HTTPS」按钮（粘贴证书与私钥，提交后自动重新下发）。ACME/Let's Encrypt 的申请与自动续期尚未实现。

## 端点

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/reverse-proxy-sites` | 列出站点（含主机名、状态），按创建时间倒序，最多 200 条 |
| POST | `/api/v1/reverse-proxy-sites` | 登记站点（Cookie + CSRF），返回 201 与站点详情 |
| PUT | `/api/v1/reverse-proxy-sites/{siteId}` | 修改未生效站点的域名、监听地址/端口、上游端口或健康检查路径 |
| POST | `/api/v1/reverse-proxy-sites/{siteId}/apply` | 编译并下发受管 Nginx 路由（需 `Idempotency-Key`）；也用于「启用」已停用站点 |
| POST | `/api/v1/reverse-proxy-sites/{siteId}/disable` | 撤回该站点的受管 Nginx 路由文件（需 `Idempotency-Key`） |
| POST | `/api/v1/reverse-proxy-sites/{siteId}/tls` | 安装证书/私钥并记录受管 TLS 名（需 `Idempotency-Key`） |
| DELETE | `/api/v1/reverse-proxy-sites/{siteId}` | 删除站点记录；`APPLIED` 状态拒绝删除 |

## 请求体

`POST /api/v1/reverse-proxy-sites`：

```json
{
  "hostId": "0f7c…",
  "domain": "shop.example.com",
  "listenAddress": "192.0.2.10",
  "listenPort": 80,
  "upstreamPort": 8080,
  "healthPath": "/"
}
```

- `domain` 会去除首尾空白并小写；必须是合法 DNS 主机名（标签 `[A-Za-z0-9](…)?`，总长 ≤253）。
- `listenAddress` 必须是主机上一个具体 IPv4/IPv6 地址；拒绝 `0.0.0.0`、组播和带 `%` 的地址。
- `listenPort` / `upstreamPort` 均为 1–65535；`healthPath` 默认 `/`，须以 `/` 开头、不含 `..`、长度 ≤256。
- 上游固定为 `127.0.0.1:<upstreamPort>`；可直接指向容器一键部署时发布在 loopback 的端口。

## 校验与错误

登记时直接调用下发时的同一校验函数（`HostHelperOperationResource.validateNginxRoute`），保证「已登记即可下发」，避免两套规则漂移。

| 状态 | code | 说明 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` | 未登录 |
| 403 | `PASSWORD_CHANGE_REQUIRED` | 首登未改密 |
| 404 | `HOST_NOT_FOUND` | 主机未登记 |
| 404 | `SITE_NOT_FOUND` | 站点不存在 |
| 409 | `SITE_CONFLICT` | 同一主机上 `(domain, listenPort)` 已存在 |
| 409 | `SITE_APPLIED` | 已生效站点拒绝删除（需先 `disable`） |
| 422 | `VALIDATION_FAILED` | 域名、地址、端口或健康路径非法；或幂等键格式错误 |
| 503 | `AGENT_OFFLINE` | 主机未接入 Agent / Agent 或 Helper 不可用 |

## 下发语义

`apply` 编译出的 `nginx.route.set` 载荷与受管路由一致：

```json
{
  "listenAddress": "192.0.2.10",
  "listenPort": 80,
  "serverName": "shop.example.com",
  "healthPath": "/",
  "backends": [{ "name": "shop-example-com", "port": 8080, "weight": 100 }]
}
```

- `target` 由站点 UUID 派生（`nanpanel-site-s<12 hex>`），重复应用覆盖同一受管路由文件，不会产生多份配置。
- 下发成功：站点置 `APPLIED` 并记录 `operationId`；下发抛错（如主机离线）：站点置 `FAILED` 后原样抛回错误，便于前端提示。
- 删除只删记录，不改动主机上的 Nginx 配置；已生效（`APPLIED`）站点需先 `disable` 撤回路由再删除。
- `disable` 通过类型化能力 `nginx.route.remove` 删除该 target 的受管路由文件：只删带 NaNPanel 归属标记的文件，删除后执行 `nginx -t` 与 reload，失败时自动还原原文件；路由本就不存在时幂等成功（`removed=false`）。
- `disable` 失败（如主机离线）**不改变站点状态**——路由可能仍在提供服务，不会谎报已停用。

## 状态

`state` 是**报告值**，由记录保存的意图状态加上最近一次关联 Operation 的终态派生——避免「主机执行失败、面板却显示已生效」。

- `DRAFT`：已登记，未下发。
- `APPLIED`：已下发且被 Helper 接受（不保证已通过健康探测）。
- `DISABLED`：已撤回受管路由文件；可再次 `apply` 启用。
- `FAILED`：最近一次 `apply`/`disable` 在主机上**执行失败**（记录仍存 `APPLIED`，关联 Operation 为 `FAILED`）。此时路由并未生效，可重新 `apply`、编辑或删除。
- `UNKNOWN`：Helper 无法确认结果（reload 与还原双双失败），路由可能仍在服务；必须 `disable` 后才能编辑或删除。

响应同时返回 `operationStatus`（`PENDING` / `RUNNING` / `SUCCEEDED` / `FAILED` / `UNKNOWN` / `null`）与 `operationId`；`PENDING`/`RUNNING` 表示指令已下发、主机尚未回报，控制台显示「处理中」徽章而不是谎报完成。

阻止编辑/删除的状态是 `APPLIED` 与 `UNKNOWN`（需先 `disable`）。

TLS 状态不在 `state` 里：`tlsName` 非空表示已在该主机安装证书材料（路由是否已按 HTTPS 下发仍看 `state` 与 `apply` 结果）。

站点记录保存在 `reverse_proxy_site` 表（迁移 `044-reverse-proxy-sites.sql`），同一 `(host_id, domain, listen_port)` 唯一。

## 修改站点

`PUT /api/v1/reverse-proxy-sites/{siteId}` 接受与创建相同的字段：

- 仅 `DRAFT` / `FAILED` / `DISABLED` 状态可改；`APPLIED` / `UNKNOWN` 返回 `409 SITE_APPLIED`（先 `disable`，避免改动与主机上正在服务的路由不一致）。
- **不能更换主机**：`hostId` 与现记录不同时返回 422。
- 修改后仍用下发时的同一校验器校验；若站点已安装证书，校验载荷会带上现有的 `tls` 名称。
- 与其它站点域名/端口冲突返回 `409 SITE_CONFLICT`。
- 修改只改记录，需要再次 `apply` 才生效（控制台会提示）。

## 站点 HTTPS

```json
{ "certificatePem": "-----BEGIN CERTIFICATE-----…", "privateKeyPem": "-----BEGIN PRIVATE KEY-----…", "listenPort": 443 }
```

- 载荷必须是带对应 `BEGIN/END` 标记的 PEM，各 ≤64 KiB；`listenPort` 默认 443。
- 受管 TLS 名由站点 UUID 派生（`site-<12 hex>`），重复安装覆盖同一对文件。
- 成功后写入 `tls_name` 并把 `listen_port` 切到给定端口；若该端口与同主机其它站点冲突返回 409 `SITE_CONFLICT`。
- **Core 不保存私钥**：私钥只写入主机侧 root 拥有的受管目录（0600）。主机重建后需重新安装证书。
- 安装材料本身不改变路由；需要 `apply` 才会以 `listen …:443 ssl` 重新渲染（控制台自动连续执行这两步）。

| 状态 | code | 说明 |
| --- | --- | --- |
| 422 | `VALIDATION_FAILED` | 证书/私钥缺失或不是合法 PEM；端口非法 |
| 409 | `SITE_CONFLICT` | 切换到该 HTTPS 端口会与同主机站点冲突 |
| 503 | `AGENT_OFFLINE` | 主机未接入 Agent / Helper 不可用 |

## 与一键部署联动

「应用商店」页一键部署容器时，控制台在提交 `container.deploy` 后会把部署元数据登记到 `POST /api/v1/app-store/deployments`，用于网站表单自动带入上游端口：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/app-store/deployments` | 已部署容器列表（由成功的 `container.deploy` 派生，排除被重部署或成功 `container.remove` 取代的记录），附带 `image` / `hostPort` / `containerPort` |
| POST | `/api/v1/app-store/deployments` | 登记/更新容器的部署元数据（`hostId`、`containerName`、`image`、`hostPort`、`containerPort`） |

- 元数据按 `(host_id, container_name)` 幂等 upsert，只作提示用途；**容器是否存在仍以操作日志派生为准**，过期元数据不会出现在列表里。
- `hostPort` 即容器发布在 `127.0.0.1` 的端口，正好可作为站点的 `upstreamPort`；网站表单「从已部署容器带入」会填好主机与上游端口。
