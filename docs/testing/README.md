# 阶段性验收（收敛流程）

一次阶段性验收 = 三层自动化测试全绿 + 文档基线与实测一致 + 工作区干净。任何一层失败都不要继续，
先修再跑——**跑一半的结果不能算通过**。

## 1. 三层命令

```bash
# 后端：多模块构建 + 全部 JUnit/Quarkus 测试（约 12–15 分钟）
# 用 clean：否则已删除测试留下的 surefire 报告会把统计数字撑大
./mvnw clean verify

# 边缘：格式、编译、lint、测试（Rust 工作区）
cd agent
cargo fmt --all -- --check
cargo check --all-targets --all-features
cargo clippy --all-targets --all-features -- -D warnings
cargo test --all-features
cd ..

# 控制台：类型检查 + 生产构建 + Node 测试
cd web
npm run build
npm test
```

只改一个模块时可以跑窄一点，但**提交前必须有一次完整的三层结果**：

```bash
./mvnw -pl backend/panel-core -am test     # 只跑 Core（含所依赖模块）
cargo test -p nanpanel-privileged-helper   # 只跑 Helper
npm test                                   # Node 测试
```

## 2. 三层各证明什么

| 层 | 覆盖 | **不**覆盖 |
| --- | --- | --- |
| `./mvnw verify` | 端点语义、权限、状态派生、迁移、审计、Helper 派发与载荷校验 | 真实主机上的执行结果；真实数据库方言（测试用 H2 + PostgreSQL 模式） |
| `cargo test` | Helper/Daemon 的载荷校验、目标白名单、文件与归档操作的本地行为（含真实 `tar`） | 真实 Docker/Podman/K3s 环境；mTLS 链路 |
| `npm run build` + `npm test` | TypeScript 类型、纯函数（路由编译/校验/对账、画布避让、清单导出、快照对比） | 浏览器交互、真实 API 联调 |

**Mock 通过不等于后端正确，后端通过不等于部署正确。** 真机验收项见 `docs/COMPARISON.md` 的
「真机验收待办」。

## 3. 基线数字（必须与实测一致）

| 层 | 当前基线 |
| --- | --- |
| Rust（`cargo test --all-features`） | **95** |
| Java `panel-core`（`./mvnw verify` 汇总行） | **176** |
| Java `panel-cluster` | **8** |
| Web（`npm test`） | **98** |

统计以 Maven 的汇总行为准；`backend/*/target/surefire-reports/` 里可能残留已删除测试的旧报告
（本次收敛就遇到一份 `OperationsResourceTest.xml` 把 Core 算成 177）。

数字对不上时：先确认是不是自己新增/删除了用例，再确认**全量**跑完了（见下节），不要用「上次是这么多」搪塞。

## 4. 已知坑

- **全量测试内存**：60+ 个 `@TestProfile` 各自在同一 Surefire fork 里启动 Quarkus 应用，内存会耗尽，
  表现为「跑到一半 BUILD FAILURE、已跑的用例全绿」。根 `pom.xml` 已给 fork 固定
  `-Xmx6144m -XX:MaxMetaspaceSize=2g`；**出现 `backend/panel-core/target/hs_err_*.hprof` 说明又变重了**，
  不要只调大内存。
- **测试库共用**：多数集成测试共用同一 H2 内存库，新用例要在开头重置管理员口令并清理自己的数据。
- **测试 changelog**：`auth-test-changelog.xml` 是手写的测试 schema，业务表新增后要同步，否则后台
  调度会每 10 秒刷「表不存在」，并把接口拖到超时。
- **不要并发跑两个 Maven 测试**：会抢 8081 与 59999 端口，产生假失败。

## 5. 收尾检查

```bash
git status --short          # 必须为空
git log --oneline -5
```

然后更新 `docs/COMPARISON.md` 的基线数字与本文件的表格——文档与实测不一致本身就是缺陷。

## 6. 历史记录

- 2026-10-18 初始化验证：Maven 构建与两项 Core API 测试通过；TypeScript 与 Vite 生产构建通过；
  用临时 PostgreSQL 18 启动打包后的 Core，Liquibase 建表成功、`health` 为 UP、匿名 API 401、
  管理员 API 200；验证后关闭临时 Core 与数据库，未使用持久化数据卷。
- 2026-10-30 收敛：`./mvnw verify` BUILD SUCCESS（Core 176、cluster 8）、`cargo test --all-features` 95、
  `cargo fmt --check`/`clippy -D warnings` 干净、`npm run build` 与 `npm test` 98 全绿；
  基线同步至 `docs/COMPARISON.md`。

历史记录只写**实际跑过的**结果；未跑到的（浏览器交互、Agent mTLS、实际网络连通、真机部署）不写进通过项。
