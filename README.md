# NaNPanel

Java 主控 + Rust 边缘执行的混合云基础设施控制面。设计基线见 [PROJECT_PLAN.md](PROJECT_PLAN.md)。当前为源码初始化骨架，尚未完成 P0 网络闭环。

## 目录

- `backend/`：Maven 多模块；Core、Domain、Plugin API/Runtime、Agent Gateway、Network、Cluster、Persistence。
- `web/`：React + TypeScript 管理控制台，内置独立 Mock 与 React Flow 拓扑编辑器，详见 [Web 使用说明](web/README.md)。
- `agent/`：Rust 守护进程、驱动、root Helper 的规划目录及协议草案（尚无 Rust 工程）。
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

Core 提供认证后的系统信息、管理员账号/角色、平台设置、审计查询、主机和 Agent 只读查询及仪表盘统计；数据库包含 Host / Agent / Operation 初始表。PF4J 提供生命周期封装，但未接入 Core 自动加载、签名权限校验与卸载泄漏验证。前端默认真实登录、改密、会话、管理员账号、设置/审计、主机/Agent 查询和仪表盘统计；服务操作、任务与拓扑后端尚未接入，纯 Mock 模式提供模拟交互。拓扑只编辑期望连接，不声称链路已部署或已验证。Agent mTLS 接入、Helper 观测、服务操作和状态协调仍待实现。

## 边缘实现与运维权限

自研边缘 Agent、控制器、运行时驱动和 Helper 统一使用 Rust；主控及主控侧 PF4J 插件保留 Java。默认使用普通用户 Agent + root Helper，支持软件包、系统服务、网络与受保护文件的受控管理。容器是否 rootless 按工作负载决定。详见 [ADR-009](docs/adr/ADR-009-agent-privilege-boundary.md) 和 [ADR-010](docs/adr/ADR-010-rust-edge-components.md)。

## API 契约

首条主机运维功能链的 [OpenAPI 文档](api/openapi.yaml) 与 [接口语义说明](api/README.md) 已建立，覆盖 Web 登录与会话、管理员账号、内置角色、仪表盘、设置、审计，以及主机、Agent、systemd 服务和异步重启任务。登录、本人改密、会话管理、管理员账号 CRUD、内置角色、设置、认证/管理员审计查询、主机/Agent 只读查询和仪表盘统计已接入数据库；Agent 接入及 systemd 服务/操作任务端点仍待实现。前端默认真实认证配合部分业务 Mock，纯演示设置 `VITE_API_MODE=mock`。首次管理员密码须 15–128 字符，首次登录强制改密。部署设置 `NANPANEL_ORIGIN` 为对外 HTTPS 来源，开发使用 `http://127.0.0.1:5173`。登录语义见 [Web 平台 API](api/web-platform.md)。
