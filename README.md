# NaNPanel

Java 主控 + Rust 边缘执行的混合云基础设施控制面。设计基线见 [PROJECT_PLAN.md](PROJECT_PLAN.md)。当前为源码初始化骨架，尚未完成 P0 网络闭环。

## 目录

- `backend/`：Maven 多模块；Core、Domain、Plugin API/Runtime、Agent Gateway、Network、Cluster、Persistence。
- `web/`：React + TypeScript 管理控制台，内置独立 Mock 与 React Flow 拓扑编辑器，详见 [Web 使用说明](web/README.md)。
- `agent/`：Rust Agent mTLS/Gateway PoC、受限 root Helper PoC、运行时驱动 workspace 和 Protobuf 协议。
- `plugins/`：五类 Provider 预留目录，尚无实际设备管理实现。
- `api/`、`docs/`、`tests/`、`deploy/`：接口、架构记录、跨模块验证与本地数据库配置。

## 环境与构建

Java 21+、Node.js 22.12+（推荐当前 LTS）、容器运行时。Core 保持 JVM 模式。

```sh
./mvnw verify
cd web
npm ci
npm run build
```

## 本地启动

复制 `.env.example` 为 `.env`，填写 `DB_PASSWORD`、`NANPANEL_ADMIN_PASSWORD`，在当前终端加载环境变量（示例值不包含空格或 Shell 特殊字符）：

```sh
set -a
. ./.env
set +a
podman compose --env-file .env -f deploy/compose.yaml up -d
./mvnw package -DskipTests
java -Dnanpanel.auth.secure-cookie=false -Dnanpanel.auth.origin=http://127.0.0.1:5173 -jar backend/panel-core/target/quarkus-app/quarkus-run.jar
```

新终端中执行 `cd web && npm ci && npm run dev`。网页在 Vite 输出的本地地址提供；Core 在 `127.0.0.1:8080`。

```sh
curl http://127.0.0.1:8080/q/health
curl http://127.0.0.1:8080/api/v1/system # 未登录返回 401
```

在网页中使用 `admin` 和初始管理员密码登录，首次登录须改密并重新登录。上述关闭 Secure 的参数仅供本地 HTTP 开发；生产通过 HTTPS 访问并保留默认 Secure Cookie。健康端点允许本地监测，业务 `/api/*` 要求认证。数据库迁移在 Core 启动时应用。Compose 仅启动数据库，不安装数据面组件。

## 当前实现边界

Core 提供认证后的系统信息、管理员账号/角色、平台设置、审计查询、主机和 Agent 查询、一次性 Agent 接入/证书签发/心跳回写及仪表盘统计。`deploy/k3s` 将 Agent Gateway 与 Core 镜像一起发布，添加主机向导生成带证书指纹校验的一次性 Linux x86_64 安装命令。当前 Agent 只报告主机清单和心跳，不执行 systemd 或 root Helper 操作；证书吊销/续期也尚未实现。PF4J 生命周期 PoC 已在 Quarkus 测试进程验证动态加载、卸载、任务停止和 ClassLoader 回收，仍未接入 Core 自动加载或插件签名权限校验。root Helper IPC 仍为隔离容器 PoC。单独的 P0 实验页已通过 React Flow 图连接实际更新隔离 FRP 路由并验证 HTTP，详见 [P0 验收说明](tests/p0/README.md)。前端默认真实登录、改密、会话、管理员账号、设置/审计、主机/Agent 查询和仪表盘统计；服务操作、任务与拓扑部署 API 尚未接入常规控制台，常规拓扑页只编辑期望连接。P0 可行性验收已完成，包括公网 VPS → NAT Docker 主机 → K3s Service 的 HTTPS/FRP 拓扑闭环，以及 UDP 受限时跨主机 NetBird TCP/443 Relay；验收使用临时主机映射，未验证 DNS 传播或任意客户防火墙环境。

## 边缘实现与运维权限

自研边缘 Agent、控制器、运行时驱动和 Helper 统一使用 Rust；主控及主控侧 PF4J 插件保留 Java。默认使用普通用户 Agent + root Helper，支持软件包、系统服务、网络与受保护文件的受控管理。容器是否 rootless 按工作负载决定。详见 [ADR-009](docs/adr/ADR-009-agent-privilege-boundary.md) 和 [ADR-010](docs/adr/ADR-010-rust-edge-components.md)。

## API 契约

首条主机运维功能链的 [OpenAPI 文档](api/openapi.yaml) 与 [接口语义说明](api/README.md) 已建立，覆盖 Web 登录与会话、管理员账号、内置角色、仪表盘、设置、审计、Agent 接入与心跳，以及尚未实现的 systemd 服务和异步重启任务。登录、本人改密、会话管理、管理员账号 CRUD、内置角色、设置、认证/管理员审计查询、主机/Agent 查询、Agent 接入及仪表盘统计已接入数据库；systemd 服务/操作任务端点仍待实现。前端默认真实认证配合部分业务 Mock，纯演示设置 `VITE_API_MODE=mock`。首次管理员密码须 15–128 字符，首次登录强制改密。部署设置 `NANPANEL_ORIGIN` 为对外 HTTPS 来源，开发使用 `http://127.0.0.1:5173`。登录语义见 [Web 平台 API](api/web-platform.md)。

目标通信架构新增 Agent/Core 双向拨号、Rust 主控代理反向长连接、Cloudflare 每节点 DNS 与保留入口 CNAME 故障切换，以及 Agent 直查公共 DNS 恢复；详见 [设计文档](docs/architecture/control-connectivity.md)。这些扩展尚未实现。
