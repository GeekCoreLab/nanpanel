# Kubernetes 应用管理

NaNPanel 把受管 Kubernetes 工作负载记录为应用。每个应用版本持久化一份标准化 YAML 清单，拓扑节点和 Service → Deployment 连线由这份清单的资源与 selector 生成。拓扑修改先创建 `DRAFT` 版本并返回资源级 YAML diff；只有单独确认部署才会向集群写入。

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

`PACKET_TAP` 当前只把链路两侧的帧镜像到 monitor 端口，不生成抓包文件。未来设计允许管理员从支持的画布链路临时发起有时长和数据量上限的抓包，停止后下载带链路、拓扑 Revision、抓取点和时间等元数据的 PCAPNG；抓包会话、文件记录和保留策略尚未实现。抓包必须保持原转发路径不变；不支持镜像的链路需明确显示不可抓取。Service 业务流只有在路由 Provider 提供真实抓包点时才可抓取，代理计数器不等于抓包；TLS 解密前采到的负载仍是密文。

应用画布的业务流量编排是与上述设备网线分开的设计，详见 [ADR-017](../docs/adr/ADR-017-application-service-traffic-steering.md)。Service→Deployment 连线仍表示 Kubernetes selector；Deployment→设备和设备→设备连线分别表示次级网络接入和 L2/L3 连接。未来的业务流量连线将表示调用方、稳定 Service 身份、协议/端口及可选路由条件；`LOAD_BALANCER` 出口连接逻辑后端 Service 组并保存相对权重，后端 Pod 由 Kubernetes Service/EndpointSlice 动态发现。Deployment 可同时被 Service 访问并发起其他 Service 调用；响应属于原连接，不需要反向连线。当前权重尚未接入实际服务路由，应用网络 Apply 仍拒绝 `LOAD_BALANCER`；生产 Istio/Gateway 路由 Provider 也尚未实现。保存业务流图不会改变 Pod 流量或安装数据面组件。

应用资源画布使用可拖拽节点与贝塞尔曲线连线；空白处右键可新增 Deployment 或 ClusterIP Service，拖动 Service 输出端到 Deployment 输入端修改 selector，表单可编辑 Deployment 副本数（0–20）和镜像，或编辑 Service 首个端口、目标端口与协议。新增和修改都会先生成 YAML diff，确认部署前不写集群。后端不接受任意资源 YAML，不删除已有资源，也不写入 Secret、Namespace、集群级资源或自定义资源。新 Service 必须匹配应用内 Deployment；名称已被 Namespace 中其他资源占用时拒绝部署。部署一个草稿后，其他基于旧版本的草稿会标记为 `FAILED` 并提示已过期。

创建草稿只持久化候选版本，不操作集群。返回版本历史中的 `manifestDiff` 按 Kubernetes kind/name 展示变更资源。部署接口仅接受状态为 `DRAFT` 且 `baseVersion` 仍等于当前版本的草稿；部署前逐资源检查 `nanpanel.io/application-id` 所有权标记和 Kubernetes ResourceVersion。多资源更新不是集群原子事务，失败时会尝试恢复前一份清单，并将最终状态记录为 `FAILED` 或 `PARTIAL`。

应用所需的集群权限限制在被选 Namespace：读取对应资源、创建首版资源和版本新增的 Deployment / Service，以及读取和更新该应用已登记的 Deployment、Service、ServiceAccount。不需要集群级写权限。应用资源同时登记 Kubernetes UID；同名资源被删除并重建后，旧应用不会自动接管新 UID。

启用网络运行时后，还需要读取 Node、列出应用 Pod、创建/读取/更新/删除被应用标签托管的 NetworkAttachmentDefinition 与 EndpointSlice，并更新该应用已登记的 Deployment 和 Service。所需命名空间资源权限不会授予集群级写权限。

## 路由

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/kubernetes/applications` | 列出已登记应用 |
| POST | `/api/v1/kubernetes/applications/preview` | 校验创建输入并返回资源计数和标准化 YAML，不写入集群 |
| POST | `/api/v1/kubernetes/applications` | 创建并部署图形化配置或导入清单 |
| POST | `/api/v1/kubernetes/applications/bookinfo` | 创建并部署 Bookinfo 初始版本 |
| GET | `/api/v1/kubernetes/applications/{applicationId}` | 获取应用拓扑和当前版本 YAML |
| GET/PUT | `/api/v1/kubernetes/applications/{applicationId}/network-topology` | 读取或按 If-Match 保存该应用独立的虚拟设备图 |
| GET | `/api/v1/kubernetes/applications/{applicationId}/network-topology/runtime` | 获取运行时 Apply/Verify/Compensate 状态 |
| POST | `/api/v1/kubernetes/applications/{applicationId}/network-topology/runtime/apply` | 显式接入真实 Pod/Service 流量 |
| POST | `/api/v1/kubernetes/applications/{applicationId}/network-topology/runtime/verify` | 显式验证 Helper、Pod 网卡和 EndpointSlice |
| POST | `/api/v1/kubernetes/applications/{applicationId}/network-topology/runtime/compensate` | 恢复运行前资源与主机网络配置 |
| GET | `/api/v1/kubernetes/applications/{applicationId}/network-topology/revisions` | 查询应用虚拟拓扑 Revision 摘要 |
| GET | `/api/v1/kubernetes/applications/{applicationId}/network-topology/revisions/{revisionId}` | 读取不可变应用虚拟拓扑 Revision |
| GET | `/api/v1/kubernetes/applications/{applicationId}/versions` | 获取版本历史和差异 |
| POST | `/api/v1/kubernetes/applications/{applicationId}/versions` | 生成待确认版本草稿，不写入集群 |
| POST | `/api/v1/kubernetes/applications/{applicationId}/versions/{version}/deploy` | 显式确认部署指定草稿 |

所有路由使用活动管理员 Cookie 会话；写请求需要 CSRF。受限 API Key 不包含这些管理权限。
