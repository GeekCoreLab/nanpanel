# 初始化验证

执行 `./mvnw verify` 与 `cd web && npm ci && npm run build`。

2026-10-18 本地验证记录：

- Maven 多模块构建及两项 Core API 测试通过：匿名请求被拒绝、管理员取得系统信息。
- TypeScript 检查及 Vite 生产构建通过。
- 使用临时 PostgreSQL 18 实例启动打包后的 Core：Liquibase 创建 Host / Agent / Operation 表，数据库健康为 UP，匿名 API 返回 401，管理员 API 返回 200。
- 验证后关闭临时 Core 和数据库，未使用持久化数据卷。

以上不代表浏览器交互验收、Provider 热卸载、Agent mTLS、实际网络连通或生产部署验收。相关实验按 PROJECT_PLAN.md 的 P0 门槛继续推进。
