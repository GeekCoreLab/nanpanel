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

拓扑版本草稿请求必须包含当前 `baseVersion` 和修改项；每个应用同时只允许一个待确认草稿。应用页和网络拓扑页使用共享可拖拽画布与贝塞尔曲线连线；拖动 Service 输出端到 Deployment 输入端修改 selector，右键卡片可编辑 Deployment 副本数（0–20）和镜像，或编辑 Service 首个端口、目标端口与协议。所有资源修改都会先生成 YAML diff，确认部署前不写集群。后端从存储的清单解析资源并生成新 YAML，不接受任意更新 YAML，不增删资源，不写入 Secret、Namespace、集群级资源或自定义资源。部署一个草稿后，其他基于旧版本的草稿会标记为 `FAILED` 并提示已过期。

创建草稿只持久化候选版本，不操作集群。返回版本历史中的 `manifestDiff` 按 Kubernetes kind/name 展示变更资源。部署接口仅接受状态为 `DRAFT` 且 `baseVersion` 仍等于当前版本的草稿；部署前逐资源检查 `nanpanel.io/application-id` 所有权标记和 Kubernetes ResourceVersion。多资源更新不是集群原子事务，失败时会尝试恢复前一份清单，并将最终状态记录为 `FAILED` 或 `PARTIAL`。

应用所需的集群权限限制在被选 Namespace：读取对应资源、创建首版资源，以及读取和更新该应用已登记的 Deployment、Service、ServiceAccount。不需要集群级写权限。应用资源同时登记 Kubernetes UID；同名资源被删除并重建后，旧应用不会自动接管新 UID。

## 路由

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/kubernetes/applications` | 列出已登记应用 |
| POST | `/api/v1/kubernetes/applications/preview` | 校验创建输入并返回资源计数和标准化 YAML，不写入集群 |
| POST | `/api/v1/kubernetes/applications` | 创建并部署图形化配置或导入清单 |
| POST | `/api/v1/kubernetes/applications/bookinfo` | 创建并部署 Bookinfo 初始版本 |
| GET | `/api/v1/kubernetes/applications/{applicationId}` | 获取应用拓扑和当前版本 YAML |
| GET | `/api/v1/kubernetes/applications/{applicationId}/versions` | 获取版本历史和差异 |
| POST | `/api/v1/kubernetes/applications/{applicationId}/versions` | 生成待确认版本草稿，不写入集群 |
| POST | `/api/v1/kubernetes/applications/{applicationId}/versions/{version}/deploy` | 显式确认部署指定草稿 |

所有路由使用活动管理员 Cookie 会话；写请求需要 CSRF。受限 API Key 不包含这些管理权限。
