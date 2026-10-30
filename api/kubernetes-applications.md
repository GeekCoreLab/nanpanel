# Kubernetes 应用管理

NaNPanel 把受管 Kubernetes 工作负载记录为应用。每个应用版本持久化一份标准化 YAML 清单，拓扑节点和 Service → Deployment 连线由这份清单的资源与 selector 生成。拓扑修改先创建 `DRAFT` 版本并返回资源级 YAML diff；只有单独确认部署才会向集群写入。

应用管理同时提供按已登记集群和 Namespace 浏览资源的只读清单，包含通过 NaNPanel 创建和由其他工具创建的资源。清单覆盖常见工作负载、Service、配置/RBAC/存储资源、Ingress/网络策略、EndpointSlice 与 Istio 路由和安全策略；每种资源最多返回 512 项。它只提供资源摘要，不会自动登记、接管或修改外部资源。ConfigMap 仅展示键名，不返回值；Secret 不读取也不展示。没有已登记应用时，页面默认进入资源清单；有应用时可从应用工具栏打开。清单额外返回 Service 的 `selector`、工作负载的 Pod 模板标签（`podLabels`）以及 Ingress 的结构化路由（`ingressRoutes`：host、path、目标 Service 与端口，最多 32 条），控制台据此只读展示「Ingress → Service → 工作负载」的完整关系（selector 为 Pod 标签子集即视为选中），并在「列表 / 关系图」间切换：关系图以只读 React Flow 画布呈现该关系（不可拖拽、不可连接），点击节点可查看资源摘要与标签；仅当节点属于同一集群/Namespace 下由 NaNPanel 管理的应用时提供「在画布中编排」跳转到可编辑拓扑，外部资源保持只读、不接管、不修改，不新增任何写操作。

## 创建方式

页面提供 Bookinfo 模板、图形化单容器配置、`docker run`、Docker Compose 和 Kubernetes YAML 五种入口。每种入口先调用 `POST /api/v1/kubernetes/applications/preview`：后端解析并标准化输入，返回资源计数、注意事项和 YAML；管理员检查后再调用 `POST /api/v1/kubernetes/applications` 创建并部署初始版本。预览阶段不会连接或写入 Kubernetes。

图形化向导生成 Deployment 和可选的 ClusterIP Service。环境变量直接进入 Deployment YAML，不要填写密码或令牌。

`docker run` 支持镜像、明确端口映射、环境变量、entrypoint 和容器参数；`-p` 会映射为 ClusterIP Service，不会创建 NodePort 或 Ingress。Docker 主机目录、Volume、`--env-file`、特权模式、网络模式和随机发布端口会被拒绝。

Compose 支持基于已构建镜像的服务、端口、非敏感环境变量、command、entrypoint、working directory 和 `deploy.replicas`。Compose 环境变量插值需要先在导入前解析；`depends_on` 会显示为注意事项；build、Volume、Secret、Config、env_file、自定义网络、devices、capabilities 和未支持字段会被拒绝，避免静默丢失语义。

Kubernetes YAML 仅接收 Deployment、Service 和 ServiceAccount，至少包含一个 Deployment，资源总数最多 64。后端统一设置目标 Namespace 和 NaNPanel 所有权标签，拒绝 Secret、ConfigMap、Ingress、RBAC、自定义资源、NodePort、hostNetwork、hostPath、Secret/ConfigMap Volume 和环境变量引用、hostPort、特权容器及额外 Linux capabilities。Deployment 可以引用目标 Namespace 中已存在的外部 ServiceAccount，创建时会检查其存在，但不会修改它。导入资源名必须在目标 Namespace 未被占用。

## Bookinfo 示例

`POST /api/v1/kubernetes/applications/bookinfo` 仍保留为兼容入口。新建流程通过通用预览/创建接口创建 Istio Bookinfo 示例：4 个 ServiceAccount、4 个 Service 和 6 个 Deployment（`reviews` 的 v1/v2/v3 都保留），共 14 个命名空间资源。选择已登记集群和其中现有 Namespace；同名资源已存在时拒绝创建，不接管已有资源。创建失败时控制面会清理本次创建的资源，清理结果记录为 `FAILED` 或 `PARTIAL`。

模板遵循 [Istio Bookinfo 示例](https://istio.io/latest/docs/examples/bookinfo/) 的基础 Kubernetes 工作负载结构。该向导不安装 Istio、不创建 Ingress、不读取 Secret，也不代表应用内部调用链已由 YAML 验证。

## 拓扑编辑和版本部署

`GET /api/v1/kubernetes/applications/{applicationId}` 返回当前应用、由当前版本清单生成的拓扑和清单。`GET .../versions` 返回不可变版本历史。

拓扑版本草稿请求必须包含当前 `baseVersion` 和修改项或新增资源；每个应用同时只允许一个待确认草稿。应用拓扑卡片完整显示资源名和 Service 全部端口信息，长名称和详情自动换行。Service、Deployment 与 ServiceAccount 卡片都可打开独立源 YAML 编辑器；`yamlEdits` 仅允许替换同 kind/name 的现有受支持资源，`yamlAdditions` 只允许新增 Deployment 或 ClusterIP Service。单项 YAML 最多 256 KiB，本次源 YAML 总量最多 256 KiB。后端解析并重新校验资源，强制恢复目标 Namespace 和应用所有权标签，拒绝更换资源身份或导入不支持的 kind。

每个应用另有独立的“虚拟网络设备”工作区，不再复用共享 NetworkGraph。它将应用 Deployment、Service、ServiceAccount 与虚拟设备显示在同一画布；Deployment 网卡可连接设备端口，虚拟设备支持 SWITCH、MERGE、SPLIT、FORWARDER、LOAD_BALANCER、MIRROR、FIREWALL、ROUTER、PACKET_TAP 与 CUSTOM，每个设备卡均可通过弹窗编辑基本设置或编辑源 YAML。流量分配器的每个出口可编辑 0–100 相对权重，并显示按权重总和归一化的分配比例；权重只表示期望分配意图。应用设备图使用独立 ETag 与不可变 Revision；保存会校验设备图、链路、设备端口和当前应用 Deployment 接入关系。

保存拓扑仍只表示期望状态。运行时现已提供单节点 K3s 的显式 Apply、Verify 和 Compensate：保留默认 CNI，通过 Multus bridge 次级网卡、隔离 netns Helper、专属 bridge/veth、精确 CIDR 路由和宿主输出过滤接入虚拟拓扑；选中的 ClusterIP Service 使用 NaNPanel 管理的 EndpointSlice 指向次级 Pod IP。Apply 前会校验近期 Node/Agent/Helper 健康状态、Resource UID、Multus NAD API、Service 选择范围和目标端口；Apply 需要近期重新验证身份，操作保留 Kubernetes 原配置和 CIDR 预留，补偿时先恢复 Deployment/Service，再等待 Pod 离开次级网络后清理宿主资源。集群必须预先安装 Multus 并授予访问 Node、Pod、Deployment、Service、NetworkAttachmentDefinition 和 EndpointSlice 所需权限，NaNPanel 不会安装或修改 CNI。当前 Apply 只支持一个已映射且 Ready 的 K3s Node；仅执行隔离 netns Provider 支持的 SWITCH、ROUTER、FIREWALL、PACKET_TAP 和 L2 Ethernet 链路，Pod attachment 必须连接 Linux Bridge SWITCH，Service targetPort 必须为数字。其余虚拟设备仍可编辑并保存，但 Apply 会拒绝不支持的设备。跨节点优先按应用分配 VNI 使用 VXLAN；多节点/VXLAN 尚未实现。控制面发布不会自动 Apply 应用网络拓扑；真实 Pod/Service 包路径、默认 CNI 健康和宿主隔离仍需在隔离集群验收。详见 [ADR-016](../docs/adr/ADR-016-application-virtual-network-data-plane.md)。

`PACKET_TAP` 把链路两侧的帧镜像到 monitor 端口；此外，已 Apply 的 Linux netns L2 Ethernet 网线支持从边的右键菜单启动有界抓包。Helper 使用受信任的 root-owned `tcpdump`，最多运行 5 秒或收集 128 帧，每帧最多保留 128 字节；文件最大 64 KiB，在 PostgreSQL 中保留 24 小时。下载文件为 PCAPNG，包含应用、拓扑 Revision/generation、链路、源端接口、双向观测、无额外过滤器和起止时间元数据。抓包不改路由或转发；不支持的链路、未 Apply 的链路、缺少 Helper `network.topology.capture` 能力的主机以及业务流量边都会显示为不可用。当前没有手动停止操作，抓取会自动在上述边界结束。TLS 解密前采到的负载仍是密文。

应用画布的业务流量连线和设备网线是不同语义，详见 [ADR-017](../docs/adr/ADR-017-application-service-traffic-steering.md)。Service→Deployment 连线表示 Kubernetes selector；Deployment→Service 表示调用方关系；Service→`LOAD_BALANCER`→后端 Service 表示 HTTP 加权路由；Deployment→设备和设备→设备连线分别表示次级网络接入和 L2/L3 网线。一个 Deployment 可以同时提供 Service 并发起其他 Service 调用，响应复用原连接，不需要反向连线。

首个路由 Provider 使用 Istio `networking.istio.io/v1` VirtualService。它只支持 TCP ServicePort 中名称以 `http` 开头或端口为 80/8080 的 HTTP 流量。Stable Service 作为调用方访问的固定身份，后端连接到不同逻辑 Service；Istio 会按当前 Service 端点转发，不需逐个指定 Pod。若画布为该 Stable Service/端口配置了 Deployment→Service 调用边，Provider 会以应用保留的 Pod 标签和 Deployment selector 限定来源；未配置调用边时策略作用于所有符合条件的网格来源。各出口权重会归一化为百分比，零权重后端不接收新请求，全部为零时 Apply 被拒绝。只有 namespace 已启用 Istio sidecar 注入、调用方 Pod Ready 且含 `istio-proxy` 时才可 Apply；NaNPanel 不安装 Istio、不启用注入、不重启 Pod。保存流量图不会改变 Pod 流量，只有单独确认 Apply 才创建 VirtualService；Verify 检查 UID、所有权和规则，Compensate 仅删除 UID 与所有权匹配的 NaNPanel 路由。请求路由和抓包仍是两套独立功能，业务流量边目前没有真实抓包点。控制面配置成功不等于真实流量比例已验收；隔离集群中的流量分配、端点变更和补偿验收仍待执行。

画布左下角实时呈现「路由计划」：把拖拽出的 Service → 流量分配器 → 后端 Service 及其归一化权重编译成可读摘要（含调用方），所见即所得，无需手写 Istio 或单独 API 网关；展开后可直接在画布上一键「写入 Istio 路由 / Verify / 恢复」，也可在「拓扑设置」抽屉内执行同样操作。写入前会对计划做冲突校验，规则与后端流量协调器完全一致，因此在画布里就不会给出会被服务端拒绝的应用：同一 `Service:端口` 被多个路由入口重复定义、同一个流量分配器被多个 Service 同时驱动、单个入口的后端不足两个、后端权重全为 0，都会逐条列出并阻止写入（校验是纯函数 `validateRoutePlan`，有独立单测）。后端对应地拒绝「一个流量分配器被两个不同 Service 驱动」——此前这种情况会在索引时被后者覆盖而静默丢一条路由。应用资源画布使用可拖拽节点与贝塞尔曲线连线；空白处右键可新增 Deployment 或 ClusterIP Service，拖动 Service 输出端到 Deployment 输入端修改 selector，表单可编辑 Deployment 副本数（0–20）和镜像，或编辑 Service 首个端口、目标端口与协议。画布左侧的“节点库”面板支持直接把 Deployment、ClusterIP Service 和各类虚拟网络设备拖拽到画布指定位置创建节点，也可点击条目在画布中心添加；存在待确认草稿时应用资源条目会锁定，只能修改或新增虚拟设备。新增和修改都会先生成 YAML diff，确认部署前不写集群。后端不接受任意资源 YAML，不删除已有资源，也不写入 Secret、Namespace、集群级资源或自定义资源。新 Service 必须匹配应用内 Deployment；名称已被 Namespace 中其他资源占用时拒绝部署。部署一个草稿后，其他基于旧版本的草稿会标记为 `FAILED` 并提示已过期。

创建草稿只持久化候选版本，不操作集群。返回版本历史中的 `manifestDiff` 按 Kubernetes kind/name 展示变更资源。部署接口仅接受状态为 `DRAFT` 且 `baseVersion` 仍等于当前版本的草稿；部署前逐资源检查 `nanpanel.io/application-id` 所有权标记和 Kubernetes ResourceVersion。多资源更新不是集群原子事务，失败时会尝试恢复前一份清单，并将最终状态记录为 `FAILED` 或 `PARTIAL`。

应用所需的集群权限限制在被选 Namespace：读取对应资源、创建首版资源和版本新增的 Deployment / Service，以及读取和更新该应用已登记的 Deployment、Service、ServiceAccount。不需要集群级写权限。应用资源同时登记 Kubernetes UID；同名资源被删除并重建后，旧应用不会自动接管新 UID。

应用管理资源清单使用只读 `get/list` 权限浏览 Namespace 中的常见资源。部署脚本中的 `nanpanel-topology-inventory` ClusterRole 只授予资源读取权限，不包含 Secret 或写入权限；新加资源类型需要同步更新该角色。

启用网络运行时后，还需要读取 Node、列出应用 Pod、创建/读取/更新/删除被应用标签托管的 NetworkAttachmentDefinition 与 EndpointSlice，并更新该应用已登记的 Deployment 和 Service。所需命名空间资源权限不会授予集群级写权限。

启用 Service 流量路由还需要在应用 Namespace 读取 Namespace、Pod、Deployment 和 Service，并创建、读取和删除 Istio VirtualService。主机抓包需要已安装的 Linux netns Provider、root-owned `tcpdump` 和 Helper 报告 `network.topology.capture` 能力。抓包文件由 Core 单独保存并按 24 小时策略清理，不会写入普通 Helper 操作结果。

## 路由

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/kubernetes/applications` | 列出已登记应用 |
| GET | `/api/v1/kubernetes/clusters/{clusterId}/namespaces/{namespace}/resources` | 浏览指定 Namespace 的 Kubernetes 与 Istio 资源摘要 |
| POST | `/api/v1/kubernetes/applications/preview` | 校验创建输入并返回资源计数和标准化 YAML，不写入集群 |
| POST | `/api/v1/kubernetes/applications` | 创建并部署图形化配置或导入清单 |
| POST | `/api/v1/kubernetes/applications/bookinfo` | 创建并部署 Bookinfo 初始版本 |
| GET | `/api/v1/kubernetes/applications/{applicationId}` | 获取应用拓扑和当前版本 YAML |
| GET/PUT | `/api/v1/kubernetes/applications/{applicationId}/network-topology` | 读取或按 If-Match 保存该应用独立的虚拟设备图 |
| GET | `/api/v1/kubernetes/applications/{applicationId}/network-topology/runtime` | 获取运行时 Apply/Verify/Compensate 状态 |
| POST | `/api/v1/kubernetes/applications/{applicationId}/network-topology/runtime/apply` | 显式接入真实 Pod/Service 流量 |
| POST | `/api/v1/kubernetes/applications/{applicationId}/network-topology/runtime/verify` | 显式验证 Helper、Pod 网卡和 EndpointSlice |
| POST | `/api/v1/kubernetes/applications/{applicationId}/network-topology/runtime/compensate` | 恢复运行前资源与主机网络配置 |
| GET | `/api/v1/kubernetes/applications/{applicationId}/network-topology/traffic-runtime` | 查询 Istio Service 流量路由状态 |
| POST | `/api/v1/kubernetes/applications/{applicationId}/network-topology/traffic-runtime/{action}` | 显式创建、验证或补偿 Istio HTTP 路由（`apply`、`verify`、`compensate`） |
| GET | `/api/v1/kubernetes/applications/{applicationId}/network-topology/captures` | 查询抓包支持状态和最近记录 |
| POST | `/api/v1/kubernetes/applications/{applicationId}/network-topology/captures` | 对已 Apply 的 L2 Ethernet 网线启动限时抓包（需要近期重新验证身份） |
| GET | `/api/v1/kubernetes/applications/{applicationId}/network-topology/captures/{captureId}` | 查询抓包状态和元数据 |
| GET | `/api/v1/kubernetes/applications/{applicationId}/network-topology/captures/{captureId}/download` | 下载 24 小时内有效的 PCAPNG 文件 |
| GET | `/api/v1/kubernetes/applications/{applicationId}/network-topology/revisions` | 查询应用虚拟拓扑 Revision 摘要 |
| GET | `/api/v1/kubernetes/applications/{applicationId}/network-topology/revisions/{revisionId}` | 读取不可变应用虚拟拓扑 Revision |
| GET | `/api/v1/kubernetes/applications/{applicationId}/versions` | 获取版本历史和差异 |
| POST | `/api/v1/kubernetes/applications/{applicationId}/versions` | 生成待确认版本草稿，不写入集群 |
| POST | `/api/v1/kubernetes/applications/{applicationId}/versions/{version}/deploy` | 显式确认部署指定草稿 |

所有路由使用活动管理员 Cookie 会话；写请求需要 CSRF。受限 API Key 不包含这些管理权限。

## 已下发路由对账

`GET /api/v1/kubernetes/applications/{applicationId}/network-topology/traffic-runtime` 除状态外还返回 `routes`——**从保存的快照里读回**的加权路由：

```json
{"routes":[{"name":"np-route-…","service":"shop","port":8080,
            "backends":[{"service":"api","port":8080,"weight":90},
                        {"service":"web","port":8081,"weight":10}]}]}
```

这是「集群被告知了什么」，与画布上的期望状态对比即可发现两类漂移：

- **画布改了权重但没下发**（`DRIFT`）——面板显示双方权重，例如「画布为 api 90% / web 10%，已下发为 api 50% / web 50%」。
- **集群仍有画布已删除的路由**（`STALE`）——画布上没有任何行，说明需要在集群侧清理（用「恢复」撤销或重新 Apply）。
- 画布有、集群没有为 `NOT_APPLIED`；完全一致为 `MATCH`。

比较由纯函数 `compareRoutePlan`（`web/src/lib/application-routing.ts`）完成，权重允许 ±1 个百分点的取整误差。对账**只读**：不主动改集群，也不隐藏未知路由。

## 一键生成路由草稿

画布右下角「生成路由」把已有 Service 直接变成一条可编辑的加权路由，省掉「放三个节点 + 连三条线」：

1. 选择入口 Service（只列出带 HTTP 端口的 Service）与 2–3 个后端 Service；
2. 面板生成一个 `LOAD_BALANCER` 节点、一条 `ROUTE_ENTRY`（`route-output-<端口>` → `route-input`）与 N 条 `ROUTE_BACKEND`（`route-out-<n>` → `backend-input-<端口>`）；
3. 权重按均分填好（100 除以 N，余数给前几个，总和恒为 100），随后可用画布滑杆微调。

生成的分配器放在入口 Service 右侧的**第一个空位**（`findFreePosition` 网格扫描，已有节点不会被移动），
手工拖入/添加的虚拟设备同样走这套避让，不再互相叠压。

草稿不直接下发：仍是 `DRAFT`，需要保存后再走 `Apply Service 分流`。生成逻辑是纯函数
`planRouteDraft`（`web/src/lib/application-routing.ts`），与拖拽生成连线的 handle 命名完全一致
（因此生成的图与手工拖出来的图无法区分）。拒绝的情况与后端协调器一致：无后端、后端超过 3 个、
重复后端、入口同时作为后端、端口非法。生成的草稿经 `compileApplicationRoutePlan` 与
`validateRoutePlan` 校验无问题（有单测锁定）。

## 计划行 ↔ 画布高亮

路由计划里的每一行都可以点击：面板会用 `routePlanTargets(row)` 把该行映射回它来自的画布对象
（入口 Service、分配器、后端 Service 与三条连线）并高亮，再点一次取消。

- 映射是纯函数，`RoutePlanRow` 现在携带 `serviceId`、`balancerId`，`backends[]` 携带 `id` 与 `linkId`，
  因此不需要按名字猜节点（同名跨 Kind 也不会错配）。
- 只改变样式，不改模型、不发请求：高亮态存在前端，刷新即消失。

## 路由主体预览

路由计划的每一行都有 `YAML` 按钮，打开该路由将要写入的 **VirtualService 主体**：

- `hosts`：`<service>.<namespace>.svc.cluster.local`（namespace 为空时用裸服务名）；
- `gateways: ["mesh"]`、`http[0].match[0].port` 为入口端口；
- `http[0].route[]`：每个后端的 `destination.host`/`port.number` 与 `weight`，**权重保证合计 100**
  （画布百分比取整的余数补给第一个后端），`share` 为 0 的后端不会出现。

资源名称、标签与拓扑注解由服务端按应用与版本生成，预览里显示为 `<server-generated>`——这段内容
**不是**可以直接 `kubectl apply` 的文件，它是「面板会写什么」的说明。
