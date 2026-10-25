# NaNPanel Web

基于 OpenAPI、Web 平台 API 和项目规划的 React + TypeScript 管理控制台。默认构建使用混合模式：登录、账号、主机资产、拓扑 Revision、Kubernetes 集群及 Istio 服务网络走真实 Core API；尚未接入的业务仍使用演示 Mock。字体使用系统字体。

## 启动

```sh
cd web
npm ci
npm run dev
```

先启动 PostgreSQL 和 Core（见根目录 README），配置 `NANPANEL_ADMIN_PASSWORD` 为 15–128 字符。首次登录用户名为 `admin`，首次登录须改密，改密后所有会话撤销。Core 开发模式默认允许 `http://127.0.0.1:5173`，使用此地址打开 Vite。

纯前端演示可设置 `VITE_API_MODE=mock`，此时演示登录已预填：

- 用户名：`admin`（另一个演示管理员为 `operator`）
- 密码：`nanpanel-demo-2026`

Mock 状态只保存在当前页面内存；刷新会重置用户、密码、任务和设置，并返回登录页。没有向 localStorage 保存认证凭证。Mock 用于交互演示，不构成后端安全实现。

## 页面与交互

- 总览：主机状态统计、主机分布、近期任务与审计。
- 主机资产：服务端状态筛选与 UUID 游标分页，当前页名称搜索、Agent 能力和 Helper 最近观测状态。
- 服务重启：获取详情 ETag、原因确认、If-Match 与 Idempotency-Key；网络错误后保留原键。接受后查看任务，由 PENDING → RUNNING → SUCCEEDED。不会执行真实命令。
- 操作任务：状态筛选、轮询、执行与验证结果；UNKNOWN 与失败分开显示。
- 管理员：创建、改显示名、启用/禁用、重置他人密码；禁止禁用自己，首次登录强制改密。ADMIN 角色只读。
- 平台设置：站点名称、展示时区、离线阈值、已结束任务保留期，使用 ETag 防止覆盖并发更新。
- 账号：本人改密、会话查看与撤销；改密后重新登录。
- 审计：脱敏记录、动作/摘要搜索、结果筛选。
- 网络拓扑分为独立工作区：拓扑编辑、Istio 服务网络、网络接入。拓扑编辑使用 React Flow 管理持久化 Revision，支持多层文件夹、Host/Gateway 资产绑定、按 ETag 保存/重新读取、历史版本差异与回放为未保存草稿，以及基于 Host 真实路由观察生成只读 Router/连接。回放后保存会创建新的 Revision，不会直接回退历史指针；保存期望图不会部署或改写主机路由表。公网 Gateway 的可搜索主机选择器在混合模式读取真实 `/hosts` 资产；纯 Mock 演示使用 fixture。详见 [拓扑语义/API](../api/topology.md)。
- Istio 服务网络工作区仅展示 Core 从 Kubernetes API/EndpointSlice 与 Istio Envoy 遥测采集的数据，包括 Service、端点就绪、sidecar、请求数、HTTP 响应和 mTLS。默认混合模式及 Live 模式请求真实同源 Core API。纯 Mock 模式明确显示 `LIVE_ISTIO_REQUIRED`，不生成服务或流量样例。服务与流量分栏按需展开，并分别标记过期数据。

桌面侧栏与移动端横向导航均可使用。主机列表支持游标翻页，名称搜索仅匹配当前页；其他列表仍展示 API 首页面（默认最多 20 条）。不要把页面数量当成全量统计。仪表盘主机和任务计数来自真实 overview，操作明细接口尚未接入。

## 切换真实 API

创建 `web/.env.local`：

```dotenv
VITE_API_MODE=live
```

重启 Vite。开发服务器将 `/api` 同源代理到 `http://127.0.0.1:8080`；可在 `vite.config.ts` 调整目标。前端使用 `credentials: include`，先获取 CSRF 再登录，写请求发送 `X-CSRF-Token`；刷新通过 `/auth/me` 恢复身份。遇到 401 返回登录，强制改密进入账号页，敏感操作需要重新认证时弹出验证表单，验证后要求手动重新读取并提交，不自动重放写请求。

生产 Web 镜像在 `deploy/docker/Dockerfile.web` 构建时不设置 `VITE_API_MODE`，因此使用默认混合模式：拓扑和 Istio 服务网络仍走真实 Core API；其他未接入业务可以使用演示 Mock。服务网络调用显式要求真实 API；即使开发者切换到 `VITE_API_MODE=mock`，它也只显示未连接提示，不会调用 Mock 服务生成数据。

**后端已实现登录会话、管理员账号与角色、平台设置、审计查询、受保护的系统信息、主机/Agent 查询、拓扑 Revision、Kubernetes 集群/资源浏览、Istio 服务库存与遥测。**生产需 HTTPS，同源部署静态资源和 `/api`，配置 SPA 回退到 `index.html`；Vite 代理仅适用于开发。

Mock 只实现用于演示的内存会话、CSRF 校验、强制改密、五分钟重新认证、ETag、服务互斥与幂等重放。它不模拟真实 HttpOnly Cookie、持久化、密码哈希、限流、泄露密码检查、完整审计与 Agent 对账，也不是安全测试替代品。

## 验证与 DTO 生成

```sh
npm run build
npm test
```

Mock 集成测试要求 Node.js 22.18+（原生 TypeScript 类型擦除支持）；前端构建环境沿用项目 Node.js 22.12+ 基线。

DTO 直接从 OpenAPI schemas 生成：

```sh
# 在仓库根目录，Python 环境需安装 PyYAML（可通过 `python -m pip install PyYAML` 安装）
python web/scripts/generate-types.py
```

生成内容为静态类型；长度、范围等约束仍需前端表单与真实服务端共同验证。打包输出位于 `web/dist/`，运行 `npm run preview` 可检查生产产物。

浏览器完整验收（开发服务器需已启动）：

```sh
npx playwright install chromium
npm run test:browser
```

覆盖登录、主机详情、重启任务、管理员创建/编辑/重置密码/禁用、设置、审计、拓扑与手机布局，截图保存到 `web/artifacts/`。可通过 `NANPANEL_TEST_URL` 指向其他开发地址。

拓扑专项浏览器验收：`npm run test:topology`（需先启动开发服务器）。覆盖实际拖线、非法方向拒绝、右键新增交换机/防火墙/Packet Tap 与转发节点、端口扩展、草稿保存与重新读取，以及历史版本比较和未保存回放。防火墙与 Packet Tap 目前是带端口能力校验的期望节点；保存它们不会安装规则或复制实时流量，部署状态须由对应 Provider 的后续验收证明。

`node web/scripts/k3s-route-live-browser.mjs` 是目标 `k3s-1` 的只读 UI 验收：需要忽略文件 `.env` 中的 P2 Web HTTPS 地址和限域只读 Kubernetes inventory API Key，并需要 `ssh k3s-1` 访问 Host 路由。脚本从目标数据库读取 Host 路由，经目标 Web nginx `/api` 代理读取真实 Service inventory/探测数据，检查 Istio UI，并拦截拓扑写入。它不替代管理员登录后的生产拓扑保存/重新读取验收。

登录后端验证：`./mvnw -pl backend/panel-core -am test`（从仓库根目录运行，使用 H2 独立认证表）。完整 PostgreSQL 迁移验证可通过 Maven 系统属性覆盖 `quarkus.datasource.*` 和 `quarkus.liquibase.change-log=db/changelog/db.changelog-master.xml`，请使用独立测试数据库。

现有 `test:browser` / `test:topology` 是纯演示验收，运行前用 `VITE_API_MODE=mock npm run dev` 启动前端。

真实登录浏览器验收：启动 Core 和默认前端后，设置 `NANPANEL_TEST_PASSWORD`（已完成首次改密的测试账号）运行 `npm run test:auth`，可用 `NANPANEL_TEST_URL` / `NANPANEL_TEST_USERNAME` 指定地址和账号。脚本会登录、刷新恢复身份并撤销本次会话。
