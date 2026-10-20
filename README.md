# NaNPanel

Java 主控 + Rust 边缘执行的混合云基础设施控制面。设计基线见 [PROJECT_PLAN.md](PROJECT_PLAN.md)。P0 网络可行性验收已完成；P1 基础管理功能已实现较大部分，真实双主机、Relay 故障和 DNS 协调验收仍在进行。

## 目录

- `backend/`：Maven 多模块；Core、Domain、Plugin API/Runtime、Agent Gateway、Network、Cluster、Persistence。
- `web/`：React + TypeScript 管理控制台，内置独立 Mock 与 React Flow 拓扑编辑器，详见 [Web 使用说明](web/README.md)。
- `agent/`：Rust Agent、独立 mTLS Control Relay、受限 root Helper、运行时驱动和 Protobuf 协议。
- `plugins/`：PF4J NetBird Overlay 与 FRP TCP Route Provider；Envoy、Nginx、Traefik、OVS 等目录仍为预留。
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

Cloudflare DNS 写入默认关闭。启用时设置 `NANPANEL_DNS_ENABLED=true`、Zone ID/名称、Zone 内受管后缀和 `NANPANEL_CLOUDFLARE_DNS_TOKEN`；Token 应限制为目标 Zone 的 DNS 编辑权限，且只注入 Core，不放进 Web、Agent 或 Relay。协调器保留冲突记录并要求 API 写后读回；公共 DNS 传播和真实 Relay 故障切换仍需单独验收。

## 当前实现边界

Core 提供认证后的系统信息、管理员账号/角色、平台设置、审计查询、主机和 Agent 查询、一次性 Agent 接入/证书签发/心跳回写及仪表盘统计。Agent 上报主机清单、systemd 服务和每 30 秒采集的网卡、IP 地址与 IPv4/IPv6 路由快照；Core 可向在线 Agent 提交固定类型的 systemd、软件包、受保护文件、容器、主机文件和临时 nftables 操作，并保存操作结果及审计。Helper 不依赖逐项权限名单或配置文件，也不接受任意命令；systemd unit、软件包、容器、文件路径和网络规则经固定参数校验。主机文件管理支持绝对路径浏览、UTF-8 文件读写和普通文件删除；路径拒绝 `.`、`..` 与符号链接，读取最多 64 KiB，写入最多 32 KiB，读取结果保存在操作记录中。受保护文件目录、容器资源限制、loopback 端口和防火墙自动回滚仍生效；防火墙变更必须有 30–300 秒 systemd 自动回滚。Core 使用主机资产中加密保存且已确认 host key 的 SSH 配置执行固定探测、systemd 状态/重启、有界 journal 读取和 Agent 初始化；不提供任意 Helper shell，其他 agentless 管理能力仍未实现。Agent 支持多 HTTPS 端点轮换及受限 Cloudflare 公共 DNS 直查；Rust Gateway 和独立 Relay 已实现双向 mTLS 流的代码切片，可配置双 Relay 出站连接，Agent 会并行保持两条 Relay 通道并独立轮换。数据库单调 fencing epoch 与 Relay 持久化已接入；Core 有数据库 leader 租约、Agent 双 Relay 同时连接、默认关闭的 Cloudflare 受管 CNAME/单写租约/冲突保护/写后读回代码；Agent 安装响应携带 Core Ed25519 签名的 Relay 故障切换端点清单并在本地验签。DNS 三次失败滞回、120 秒切换冷却和周期性读回对账已有代码，真实 DNS/故障验收仍未完成。Helper/容器/systemd 与真实 SSH 通道仍待两台独立非 K3s 主机验收。Agent 操作结果由本地持久化并在重连后重放。Core 现在以通用 Provider ID 分发网络操作，PF4J API 提供 Provider/Capability/Translator 发现与启停；FRP 插件可在同机配置下管理受管 TCP 路由，完整 P3 范围与边界见 [P3 验收记录](tests/p3/README.md)。单独的 P0 实验页已通过 React Flow 图连接实际更新隔离 FRP 路由并验证 HTTP，详见 [P0 验收说明](tests/p0/README.md)。前端默认真实登录、改密、会话、管理员账号、设置/审计、主机/Agent 查询和仪表盘统计；主机资产列表隐藏能力明细，主机详情提供网卡/IP/路由快照、文件管理、systemd、Helper 操作和 SSH 通道入口，操作任务和审计页面读取真实 API；常规拓扑页保存类型化期望连接和持久化 Revision，链路状态在对应 Provider 证据接入前保持“待部署”。P0 可行性验收已完成，包括公网 VPS → NAT Docker 主机 → K3s Service 的 HTTPS/FRP 拓扑闭环，以及 UDP 受限时跨主机 NetBird TCP/443 Relay；验收使用临时主机映射，未验证 DNS 传播或任意客户防火墙环境。

## 边缘实现与运维权限

自研边缘 Agent、控制器、运行时驱动和 Helper 统一使用 Rust；主控及主控侧 PF4J 插件保留 Java。默认使用普通用户 Agent + root Helper，支持软件包、系统服务、网络与受保护文件的受控管理。容器是否 rootless 按工作负载决定。详见 [ADR-009](docs/adr/ADR-009-agent-privilege-boundary.md) 和 [ADR-010](docs/adr/ADR-010-rust-edge-components.md)。

## API 契约

首条主机运维功能链的 [OpenAPI 文档](api/openapi.yaml) 与 [接口语义说明](api/README.md) 覆盖 Web 登录与会话、管理员账号、内置角色、仪表盘、设置、审计、Agent 接入与心跳、systemd 服务观测、异步操作、受限 journal 查询及类型化 Helper 操作。SSH 支持通过资产连接配置执行固定探测、systemd 状态/有界日志读取/救援，以及经固定安装脚本初始化 Agent；P1 固定范围代码已实现，两台真实非 K3s 主机的 SSH 验收仍待完成，其他 agentless 管理能力不在当前 MVP 范围。双向 Relay 和 Cloudflare 故障切换已有代码，真实 DNS、限制型 NAT 与 Relay/Core 故障验收仍待完成。临时防火墙变更已有自动回滚。前端默认真实认证配合部分业务 Mock，纯演示设置 `VITE_API_MODE=mock`。首次管理员密码须 15–128 字符，首次登录强制改密。部署设置 `NANPANEL_ORIGIN` 为对外 HTTPS 来源，开发使用 `http://127.0.0.1:5173`。登录语义见 [Web 平台 API](api/web-platform.md)。

目标通信架构新增 Agent/Core 双向拨号、Rust 主控代理反向长连接、Cloudflare 每节点 DNS 与保留入口 CNAME 故障切换，以及 Agent 直查公共 DNS 恢复；Relay 长连接已有代码切片，DNS 协调和数据库租约已有代码，签名故障切换端点清单已有安装时签发与 Agent 验签；真实 DNS、限制型 NAT/故障验收仍待完成；详见 [设计文档](docs/architecture/control-connectivity.md)。
