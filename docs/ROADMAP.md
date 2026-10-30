# NaNPanel 路线图与竞品对标

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
- [x] 站点启停：`disable` 经 `nginx.route.remove` 撤回受管路由（失败自动还原、幂等、失败不改状态），`apply` 即启用；与 P7 一键绑定域名（部署元数据记录发布端口，网站表单可「从已部署容器带入」主机与上游端口）。
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