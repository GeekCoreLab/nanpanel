# NaNPanel 路线图与竞品对标

> **功能覆盖自检**：见 [`docs/COMPARISON.md`](./COMPARISON.md) —— 逐项对照 KubeSphere / 1Panel / 宝塔，标注 ✅ 已实现且有离线测试 / 🟡 已实现但需真机验收 / ⛔ 未实现，并列出「明确不做」与真机验收待办。

> **阶段性验收**：三层测试命令、各层边界与已知坑见 [`docs/testing/README.md`](testing/README.md)。
>
> **文档版本**：v0.1
> **编制日期**：2026-10-29
> **定位基线**：PROJECT_PLAN.md（P0–P6 已实现/验收状态见该文档第 15 节）
> **目标**：把 NaNPanel 做成 KubeSphere（K8s 管理）+ 1Panel / 宝塔（Linux 服务器面板）的竞争产品，覆盖它们的核心能力并更优，以 **K8s 与 Docker/Podman 主机混合管理 + 图形化管理** 为主干。

## 1. 产品定位

NaNPanel 是 **Java 主控 + Rust 边缘** 的混合云基础设施控制面。与三个竞品相比，它的差异化不是「再做一块 K8s 面板」或「再做一块单机服务器面板」，而是：

1. **异构统一**：K8s 集群、Docker/Podman 容器主机、纯裸机 Linux、跨 NAT 节点，进同一控制面、同一张图。三家各自只覆盖一半：KubeSphere 只管 K8s；1Panel/宝塔以单机服务器为主（多节点在 Pro/企业版）。
2. **真图形化**：React Flow 拖拽连线的拓扑、虚拟网线、L2/L3 设备（Router/VRF/Firewall/Packet Tap/OVS/Load Balancer）与 Service→流量分配器加权路由。这是三者都没有的设备级可视化与执行。资源清单还会按 Service `selector` 只读呈现「Service → 工作负载」选择关系，并提供只读 React Flow「关系图」视图（三列：Ingress → Service → 工作负载，边为 Ingress 规则与 selector 关系），点击节点可查看摘要/标签，受管应用可直接「在画布中编排」，外部资源保持只读，无需读 YAML 即可看懂并接管流量的归属。
3. **边缘安全边界**：非 root Rust Agent + 受限 root Helper + 本地 IPC 身份校验 + mTLS + 审计，替代宝塔式面板 root 直管模型。
4. **自组织发现**：Agent 自动发现主机/容器运行时/K3s，自动纳管集群、自动映射 Node→Host，构成「自组织面板」的发现输入；期望状态图 + Provider 协调作为「自我」收敛既有数据面。

不追求的范围：不自研完整 CI/CD 流水线、不自研完整监控/日志引擎（复用现有组件按需接入）、不以 Windows 主机为首发目标。

## 2. 竞品能力基线（核实于 2026-10-29）

| 维度 | KubeSphere | 1Panel | 宝塔(BT) |
| --- | --- | --- | --- |
| 定位 | K8s 内核分布式 PaaS | Linux 服务器面板 | Linux/Windows 运维面板 |
| 容器 | 仅 K8s 负载 | Docker/Compose | Docker/Compose |
| 裸机/普通主机 | 弱（需先上 K8s） | 单机典型，多节点 Pro+ | 本机，多面板统一在「企业版」 |
| 应用商店/一键部署 | Helm 应用 | ✅ App Store 一键安装/升级 | ✅ 软件商店（LNMP/LAMP/PHP/Node/Tomcat） |
| 网站/域名/SSL/反代 | 网关/Ingress | ✅ 域名+反代+SSL(acme) | ✅ 网站+反代+SSL申请+伪静态+防盗链 |
| 数据库管理 | — | MySQL/PG/Redis | MySQL/SQLServer/MongoDB/Redis/SQLite + 备份 + 用户权限 |
| 监控/告警 | Prometheus + 日志 + 告警 | 主机监控 | 实时监控 + 日报 + 安全扫描(16 项) |
| 计划任务/cron | — | — | ✅ 计划任务 + 备份 + 日志切割 |
| 备份/迁移 | — | ✅ 一键备份 + 云存储 | ✅ 备份（企业级） |
| 多租户/RBAC | ✅ 项目/工作区 + 自定义角色 | 多节点（Pro+） | 多用户（企业版） |
| DevOps CI/CD | ✅ 流水线 | — | — |

## 3. 现状缺口（按「图形化管理为主 + 补齐三家长板」排序）

| 缺口 | NaNPanel 现状 | 优先级 |
| --- | --- | --- |
| 应用商店 / 一键部署 | 仅 Bookinfo 演示模板 | 🔴 最高 |
| 网站/域名/SSL/反代 | P5 有受管 Nginx 路由，无站点模块、无 acme 证书自动化 | 🔴 高 |
| 数据库管理（安装/备份/WebSQL/用户权限） | 无 | 🔴 高 |
| 计划任务/cron（宿主机） | 仅防火墙定时回滚 | 🟠 中 |
| 备份/迁移/云存储 | 无 | 🟠 中 |
| 多租户/自定义 RBAC | 仅 ADMIN | 🟠 中（横切） |
| 监控深度/安全扫描/日报 | 基础主机指标 + Istio 遥测 | 🟡 中低 |
| StatefulSet/DaemonSet/Job/CronJob | 应用管理仅 Deployment/Service/SA | 🟡 中低 |
| ConfigMap/Secret/PV 管理 | 只读清单，不展示 Secret | 🟡 低 |

已形成「更优」的能力（保持并继续强化）：异构统一、L2/L3 虚拟网络与流量转向、边缘安全边界、自组织发现、渐进式发布（Argo Rollouts + 宿主 Docker/Podman 蓝绿/金丝雀）、跨 NAT 组网与公网入口。

## 4. 阶段路线（接现有 P0–P6）

每阶段都要求「可复现的验收门槛」，沿用 PROJECT_PLAN 的验收纪律；不预设完成日期。

- [x] 容器页（2026-10-30）：控制台新增「容器」，复用既有 `container.list/logs/start/stop/remove` 能力列容器、启停、看日志、移除（移除二次确认，只 `rm --force`、保留命名数据卷）；列表由 `docker ps --all` 输出解析，纯函数 + 4 项单测覆盖空行/CRLF、暂停态不误判为运行、Exited/Created/Restarting 分类。**运行时验收需真实主机**。

- [x] 告警闭环（2026-10-30）：恢复也通知（`alert.recovered`，渠道可用 `notifyRecovery=false` 关闭）、每次投递尝试（含失败）写入 `alert_event`，新增只读 `GET /api/v1/alert-events` 与控制台「通知历史」表。集成测试覆盖两种边沿、失败记录与事件筛选。
- [x] 告警通知（2026-10-30）：新增 Webhook 通知渠道（迁移 049：`alert_channel` + `alert_rule.channel_id` + `alert_firing_state`），规则可绑定渠道；`AlertNotifier` 每 60s 求值一次，按「规则+主机+明细」去重，只对新进入告警的对象投递，失败不记去重键（下一轮重试），恢复后清除状态（再次告警会重新通知）。渠道 CRUD + `POST /{id}/test` 连通性测试，投递结果写回 `lastStatus`/`lastError`。控制台告警页新增渠道卡片与规则绑定下拉。集成测试用内嵌 HTTP 服务验证载荷、去重、恢复后重发、失败重试与 502 报告。

- [x] 一键生成路由草稿（2026-10-30）：画布新增「生成路由」，选择入口与 2–3 个后端 Service 即生成 LOAD_BALANCER + ROUTE_ENTRY + ROUTE_BACKEND 连线与均分权重（余数给前几项，总和恒 100），handle 命名与手工拖拽完全一致。纯函数 `planRouteDraft` + 4 项单测（均分、奇数取整、五种拒绝、生成的草稿能通过 `validateRoutePlan` 且编译成 50/50 计划）。草稿仍需保存并显式 Apply。

- [x] 画布避让（2026-10-30）：新增 `web/src/lib/canvas-layout.ts`（矩形重叠判定 + 网格空位查找），手工添加/拖入的虚拟设备与「生成路由」草稿都落在第一个空位，生成的分配器从入口 Service 右侧开始扫描；已有节点从不被移动，位置对同一输入确定。5 项单测覆盖严格边界、空画布、右移、整行换行与确定性。

- [x] 计划行高亮（2026-10-30）：`RoutePlanRow` 增补 `serviceId`/`balancerId`/后端 `id`+`linkId`，新增纯函数 `routePlanTargets` 把计划行映射回画布节点与连线；点击计划行即在画布上高亮入口、分配器、后端与连线（再点取消），纯样式、无写入。2 项单测覆盖映射与去重，既有编译测试同步更新。

- [x] 自定义角色（2026-10-30）：`POST/PUT/DELETE /api/v1/admin/roles`（权限词表校验、整组替换、内置 ADMIN 不可改不可删、仍被账号使用时拒绝删除），`GET` 附带 `vocabulary`；控制台「管理员」页可新建/编辑/删除角色并按模块勾选权限。集成测试覆盖校验、重名、内置保护、在用保护、删除，以及**自定义角色真的能授权**（hosts 200 / sites 403 / roles 403）。
- [x] 配置迁移增强（2026-10-30）：导入支持 `dryRun=true` 预演（同样校验、同样冲突判定、不写库，且能解析同一文档内新建的渠道）；平台设置（站点名/时区/离线阈值/任务保留天数）纳入导出与导入（`skip` 保留本机、本机为空时仍应用）。集成测试覆盖预演零写入、预演与真实导入结果一致、设置项应用。
- [x] 配置迁移（2026-10-30）：`GET /api/v1/config/export` 导出站点/告警规则/通知渠道（主机与渠道按名字引用、不含证书与运行时状态），文档带版本号与对「键名排序后的载荷」计算的 SHA-256；`POST /api/v1/config/import?strategy=skip|overwrite` 先验版本与摘要再逐条落地，返回成功/跳过/失败报告，站点一律以 DRAFT 导入、主机缺失或渠道不存在时该条失败但不中断。控制台「平台设置」新增配置迁移卡片（导出下载、策略选择、文件导入、结果报告）。集成测试覆盖往返、篡改校验和、版本门禁、skip 幂等、overwrite 生效与主机缺失报告。

- [x] 计划任务能力层（2026-10-30）：Rust Helper 新增 `cron.list / cron.write / cron.remove`——每个任务一个 `nanpanel-<id>` 受管文件（带归属标记，只读写自己的文件）、5 段计划表达式与单行命令校验、仅允许 root、写后回读校验、删除幂等、拒绝无标记文件；Agent 两层（client/gateway）与控制面 `HostHelperOperationResource` 均加了同规则校验。Rust 82 项（+7）、Java 153 项（+1）全绿；控制台页面与 Core 任务资源为下一段。

- [x] 计划任务（2026-10-30）：Core 新增 `cron_job` 表与 `/api/v1/cron-jobs`（列表/创建/编辑/应用/停用/删除，状态按关联 Operation 派生，`APPLIED`/`UNKNOWN` 禁止改删），控制台新增「计划任务」页（主机选择、计划预设 + 自定义、应用/停用/删除、处理中徽章）。Java 155 项（+2）、Web 构建通过。真机上的 cron.d 加载仍未验收。

- [x] 主机指标能力层（2026-10-30）：Rust Helper 新增 `metrics.read`——只读 `/proc/stat`、`meminfo`、`loadavg`、`uptime`，返回**原始计数器**（CPU 百分比由控制面按两次采样差值计算，单次采样不会被误当百分比），老内核缺 `MemAvailable`/`iowait` 等字段时回退而不是填 0 造假；缺失文件直接拒绝而非零填充。Agent 两层与控制面均加了「无 target、无载荷」校验。Rust 89 项（+7）、Java 156 项（+1）全绿；采样表与图表为下一段。

- [x] 主机指标采样与图表（2026-10-30）：迁移 052 `host_metric_sample`（原始计数器 + `operation_id` 唯一），`MetricsSampler` 每 30 秒按能力挑主机、按窗口幂等下发 `metrics.read`、把已成功的操作各采样一次、裁剪 7 天；`GET /api/v1/hosts/{id}/metrics` 用相邻样本差值算 CPU 百分比（首点/倒退/零增量一律 `null` 而不是 0），控制台新增「监控」页（CPU/内存/负载三张 sparkline，缺口如实断开）。Java 164 项（+8）、Web 82 项（+9）全绿。真机采样未验收；无磁盘时间序列。

- [x] 备份能力层（2026-10-30）：Rust Helper 新增 `backup.list / backup.create / backup.delete`——在受管目录内生成 `<名称>-<UTC 时间戳>.tar.gz`，返回文件名/大小/SHA-256，同名文件拒绝覆盖，拒绝备份 `/`、拒绝来源与备份目录重叠（防递归）、删除只接受严格匹配的受管文件名且幂等；Agent 两层与控制面同规则校验。Rust 93 项（+4）、Java 165 项（+1）全绿。**恢复与控制台页面尚未接入**。

- [x] 备份恢复（2026-10-30）：Rust Helper 新增 `backup.restore`——只恢复受管归档、目标必须是**已存在且为空**的绝对目录、可选校验 SHA-256（不符即拒）、先解到同级临时目录再逐一移入、失败不留半成品；拒绝目标与备份目录重叠。Agent 两层与控制面同规则校验。Rust 93 项、Java 165 项全绿。控制台页面仍未接。

- [x] 备份记录与控制台（2026-10-30）：迁移 053 `backup_record`，`/api/v1/backups` 提供创建/列出/恢复/删除，文件、大小与 SHA-256 **一律取自主机返回的结果**（读取时补齐，不猜文件名），状态按关联操作派生；主机离线时删除返回 503 且保留记录（不谎报已删），尚未产出文件的记录可直接移除。控制台新增「备份」页。Java 168 项（+3）、Web 构建通过。

- [x] 路由主体预览（2026-10-30）：新增纯函数 `buildRouteBody`（service→FQDN、加权后端的 host/port/weight，取整余数补给首个后端以保证合计 100、0% 后端不出现），画布路由计划每行可查看即将写入的 VirtualService 主体（并明确标注名称/标签由服务端生成、不可直接 apply）。Web 87 项（+5）。

- [x] 应用清单导出（2026-10-30）：纯函数 `buildApplicationBundle` 组装多文档 YAML（资源源码 + 画布编译的路由），无源码的待添加对象跳过并列出，头部写明导出范围与**不含**的内容；画布工具栏一键下载。Web 92 项（+5）。

- [x] 托管数据库实例（2026-10-30）：迁移 054 `database_instance`，`/api/v1/database-instances` 支持 MySQL 8.4 / MariaDB 11.4 / PostgreSQL 16 一键部署（固定镜像、发布到 127.0.0.1、命名卷、同主机端口唯一），口令由面板生成、**只随创建响应返回一次且不入库**，连接信息明确 `passwordStored:false`；主机离线时不留下记录、删除时保留记录。控制台「数据库」页新增「托管实例」。Java 174 项（+6）。

- [x] 命名卷挂载点（2026-10-30）：Helper 新增 `container.volume.path`（`volume inspect` 取 Mountpoint，拒绝非绝对/含 `..`/不存在/非目录的结果），daemon 两层与 `POST /api/v1/database-instances/{id}/volume` 接线。数据库备份当时尚未接上（控制台串联在「数据卷备份」与「实例备份历史」两步完成）。Rust 95 项（+2）。

- [x] 实例备份历史（2026-10-30）：迁移 055 给 `backup_record` 加 `instance_id`，`GET /api/v1/backups?instanceId=` 过滤、创建时校验「实例属于同一主机」，控制台实例行新增「备份」历史（就地恢复/删除）。Java 176 项（+1）。

- [x] 路由快照对比（2026-10-30）：纯函数 `buildRouteSnapshot`/`diffRouteSnapshots`（入口端口、后端增删、权重变化，按名字排序保证稳定），画布「存快照」存到 localStorage 并显示「N 处与快照不同」与逐条说明。Web 98 项（+6）。

### P7｜应用商店与一键部署

> **进度（2026-10-30）**：应用目录只读后端已落地（`AppStoreCatalog` + `GET /api/v1/app-store/catalog` 与 `.../{appId}`）。为打通真实一键部署，已把 `container.deploy` 从「image+单 loopback 端口」扩展为支持 **环境变量、命名数据卷、内存上限与有状态安全profile**：后端 `HostHelperOperationResource` 校验、Agent daemon（client/gateway）校验、Rust `privileged-helper` 解析与 `docker/podman run` 参数（`--env`/`--volume`/`--memory`，有状态时去掉 `--read-only` 并授予 CHOWN/DAC_OVERRIDE/FOWNER/SETUID/SETGID 最小初始化能力）均已实现，附后端 4 项定向测试 + Rust 40 项 helper 测试（含新增 payload 约束测试）；控制台新增「应用商店」页（读目录 + 选主机 + 端口/环境变量/数据卷/内存/有状态表单 → 一键提交 `container.deploy`）。应用画布左下角新增实时「路由计划」摘要：把拖拽出的 Service→流量分配器→后端 Service 与归一化权重编译成可读视图（编译逻辑抽出为纯函数 `web/src/lib/application-routing.ts`，含 7 项单测），并支持在画布内直接「写入 Istio 路由 / Verify / 恢复」，强化「拖拽即路由、不手写 Istio/不另建 API 网关」的差异点。另新增 `container.remove`（容器卸载）类型化 Helper 能力，贯通后端/Agent/Helper 与主机详情面板；仅执行 `rm --force`，命名数据卷默认保留（数据安全优先）。新增只读 `GET /api/v1/app-store/deployments`，从成功的 `container.deploy` 操作派生「已部署容器」清单（排除被再次部署或成功 `container.remove` 取代的记录），应用商店页据此显示「已部署容器」并支持一键卸载（复用已测试的容器卸载链路）。剩余：真实 Docker/Podman 主机上一键装/升/卸验收、部署与卸载后的状态自动对账、卷保留策略的显式删除。

- [x] 版本化应用目录（仓库内清单 + 只读 API），覆盖 MySQL、PostgreSQL、Redis、Nginx、WordPress、MinIO、Prometheus 等首批应用；条目声明镜像/默认端口、数据卷、环境变量与健康门槛。
- [ ] 目录查询、按已登记 Host 能力（Docker/Podman runtime 驱动）过滤可安装目标。
- [ ] 一键部署：解析 Compose → 类型化 Helper 容器操作 → 幂等 Operation → 健康验证门槛 → 注册为运行时应用（workload）纳入既有应用/拓扑模型。
- [ ] 应用升级与卸载（保留数据卷策略：显式选择保留/删除）。
- [ ] 复用 RuntimeDriver、Compose 解析、Helper 容器操作、应用/版本/幂等部署框架；不新起数据面架构。
- [ ] 验收：隔离 Docker 与 Podman 主机各一键装/卸一门应用，容器与路由实际可达；镜像升级尚未实现。

### P8｜网站 / 域名 / SSL 模块

- [x] 站点实体：域名、上游（本机 loopback 端口）、监听地址/端口、健康检查路径，编译为受管 Nginx 路由（复用 P5 `nginx.route.set`）。控制台「网站」页支持登记、下发（应用）、删除记录。
- [x] 手工证书 TLS 终止：`nginx.tls.install` 写入主机受管证书目录（0600、root 拥有，Core 不存私钥），`nginx.route.set` 支持 `tls` 渲染 `listen …:443 ssl`、探测走 HTTPS；站点已接入：`POST /{siteId}/tls` + 网站页「HTTPS」按钮（粘贴证书/私钥后自动重新下发）。
- [ ] acme/Let's Encrypt 证书申请与自动续期。
- [x] 路由对账：`traffic-runtime` 返回从快照读回的已下发加权路由（`routes`），画布用纯函数 `compareRoutePlan` 与期望状态比较，逐行标注「已同步 / 与集群不一致 / 未下发」，并单列「集群多余」路由；权重容差 ±1 个百分点。后端 `appliedRoutes` 静态解析 + 单测，前端 6 项单测。
- [x] 任务中心：`GET /api/v1/operations` 改为**按创建时间倒序**的键集分页（原先按 UUID 排序，列表顺序与时间无关），新增 `capability` 筛选、`hostname` 关联、以及 `statuses`/`capabilities` 筛选项词表；控制台「操作任务」页可按能力过滤、显示主机名与失败原因。键集游标绑定筛选条件，条件变更返回 400。集成测试覆盖倒序、三种筛选、翻页不重不漏、游标与筛选绑定。
- [x] 站点状态诚实化：列表/详情按关联 Operation 终态派生 `FAILED`/`UNKNOWN`（不再在主机执行失败时谎报 `APPLIED`），并返回 `operationStatus` 供控制台显示「处理中」；`FAILED` 站点可重新应用/编辑/删除，`UNKNOWN` 仍需先停用。集成测试用真实 Operation 行验证三种派生。
- [x] 站点启停：`disable` 经 `nginx.route.remove` 撤回受管路由（失败自动还原、幂等、失败不改状态），`apply` 即启用；未生效站点可 `PUT` 修改后重新应用（`APPLIED` 需先停用、不可更换主机）；与 P7 一键绑定域名（部署元数据记录发布端口，网站表单可「从已部署容器带入」主机与上游端口）。
- [ ] 站点健康探测与配置回滚（多次应用的历史版本）。
- [ ] 验收：真实域名 + 免费证书 + 反代到容器应用，续期与回滚验证。
- 已落地（2026-10-30）：`reverse_proxy_site` 表（迁移 044，含 `tls_name`）+ `/api/v1/reverse-proxy-sites`（列表 / 登记 / 下发 / 停用 / 删除记录 / 安装证书），登记时复用下发校验保证「已登记即可下发」；`apply` 编译 `nginx.route.set`（有证书时带 `tls`），`disable` 调用 `nginx.route.remove` 撤回受管路由（Helper 侧带归属校验、`nginx -t` 校验、失败还原），状态机 `DRAFT → APPLIED ⇄ DISABLED`。剩余：ACME 自动签发与续期、健康探测、配置回滚、真实主机验收。

### P9｜数据库管理 · 计划任务 · 备份

- [x] 数据库实例视图：从已部署容器派生 MySQL/MariaDB/PostgreSQL/Redis/MongoDB 实例，给出引擎、端口与连接地址（`GET /api/v1/databases` + 控制台「数据库」页，纯函数引擎识别 5 项单测 + 资源测试）；创建走应用商店部署、卸载走 `container.remove`。
- [ ] 数据库执行面：SQL/WebSQL、建库建用户与权限、备份与恢复（需要真实数据库主机验收，尚未实现）。
- [ ] 宿主机计划任务（cron）：类型化 Helper 能力，shell 脚本 / 数据库备份 / 日志切割，幂等与审计沿用 Operation 框架。
- [x] 主机文件管理器：控制台「文件」页复用既有类型化能力 `file.list/read/write/delete`（提交 Operation + 轮询终态渲染），无新增端点或数据表；路径校验与 32/64 KiB 上限由 Helper 负责。
- [ ] 备份到 S3 兼容对象存储与本地快照；恢复演练门槛。
- [ ] 验收：数据库备份→异地存储→恢复闭环；cron 任务按周期执行且记录审计。

### P10｜多租户 / RBAC · 监控告警 · 负载补齐

- [x] 授权判定内核：`subject/action/resource` 语义（默认拒绝、拒绝优先、`*` 与 `admin.user.*` 通配、语句语法校验），纯函数 `AuthorizationPolicy` + 10 项单测。
- [x] 内置角色目录：`RoleCatalog` 成为 `ADMIN` 角色权限的唯一来源，`permits(role, action, resource)` 复用判定内核，+5 项单测；权限表补齐应用商店/站点/告警/数据库/文件等新模块动作（共 23 项）。
- [x] 角色持久化（迁移 047 `panel_role` / `panel_role_permission`）：`RoleRepository` 首次读取时按目录幂等播种，`GET /api/v1/admin/roles` 改读数据库且响应结构不变（+1 项含 HTTP 的集成测试，含重复读取不重复写入的断言）；**仍未接入任何端点的鉴权**。
- [x] 端点 → 权限映射 `EndpointPermissions`（方法特异规则优先、`PUBLIC` 白名单）+ 反射式覆盖测试：任何缺少映射的新端点都会测试失败（本轮借此补上 4 个遗漏的 bastion 端点），并断言映射中的权限都已声明给 `ADMIN`（36 项）。**仍未强制鉴权**。
- [x] 过滤器接线：`SessionFilter` 按 `EndpointPermissions` 判定权限，`ADMIN` 恒通过、`PUBLIC` 与 `/auth/*` 自助端点放行、API 密钥沿用作用域校验，其余角色按库中 `panel_role_permission` 判定，拒绝返回 403 `PERMISSION_DENIED` 并审计（迁移 048 给账号加 `role_id` 并回填 `ADMIN`）。集成测试用受限角色证明「授权端点 200 / 其它端点 403 / ADMIN 不受影响」，全量 139 项通过。
- [x] 账号角色分配：`PATCH /admin/users/{id}` 支持 `roleId`（校验角色存在、禁止移除最后一位 ACTIVE ADMIN），控制台「管理员」页可在编辑表单中改角色；集成测试覆盖分配、未知角色 422、自我降级 409、列表回显。
- [ ] 工作区/项目隔离、自定义角色创建入口（PROJECT_PLAN §12 预留接口落地）。
- [x] 告警：基于已采集观测的规则引擎（主机离线 / Helper 不可用 / systemd 单元未运行）+ 实时求值 API 与控制台「告警」页（迁移 046，纯函数 `AlertEvaluator` 7 项单测 + 资源测试）。
- [ ] 监控/告警增强：接入 Prometheus 指标做 CPU/内存/磁盘类告警，通知通道（邮件/Webhook）与历史留存、日报推送。
- [ ] 补齐 StatefulSet/DaemonSet/Job/CronJob 工作负载、ConfigMap/Secret/PV 管理。

## 5. 复用与边界

- 每个新模块都通过既有 Provider/Operation/审计通道落地，不绕过能力边界：普通用户 Agent + root Helper，固定类型与参数校验，不接受任意 Shell。
- 一键部署、数据库、计划任务均复用 现有 幂等 Operation + Helper 类型化能力 + mTLS Agent；业务数据面与 Core 控制面分离。
- Secret/证书/令牌沿用 Kubernetes credential key 的 AES-GCM 加密与用途绑定，不新增明文存储。
## 已知问题

- **全量测试中断：已定位为 forked JVM 内存耗尽（已修）**。原因是 60+ 个 `@TestProfile` 各自在同一 Surefire fork 里
  启动一套 Quarkus 应用，类加载器越积越多：先是 `java.lang.OutOfMemoryError: Java heap space`（表现为「跑到一半
  BUILD FAILURE、已跑的用例全绿」），抬高堆之后又变成 `OutOfMemoryError: Metaspace`。现在根 `pom.xml` 给 surefire
  固定 `-Xmx6144m -XX:MaxMetaspaceSize=2g -XX:+HeapDumpOnOutOfMemoryError`，OOM 时会把堆转储留在
  `backend/panel-core/target/`。**看到堆转储就说明某个 profile 又变重了，不要只调大内存。**
- **测试库缺表会让后台调度狂刷错误（已修）**：`KubernetesApplicationNetworkRuntimeService` 每 10 秒跑一次
  reconcile，而测试 changelog 里没有 `kubernetes_application_network_runtime`，于是每个测试类都在刷
  `Table ... not found`，并连带把接口拖到 30 秒超时。`auth-test-changelog.xml` 已补上该表与两张关联表。
- **测试库共享**：多个集成测试共用同一个 H2 内存库，因此每个用例在开头都会重置管理员口令并清理自己的数据；
  新增测试时请沿用 `resetAdmin()` + 清理自身数据的方式，否则会互相干扰。
