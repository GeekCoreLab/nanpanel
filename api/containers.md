# 容器

控制台「容器」页：在具备 `container.list` 能力的主机上查看与操作 Docker / Podman 容器。

**没有新增 HTTP 端点**，也没有新的 Helper 能力——页面复用既有的类型化能力，每个动作都是一次
有审计的 Operation：

| 动作 | 能力 | `target` | 载荷 |
| --- | --- | --- | --- |
| 列出容器 | `container.list` | 空 | 无 |
| 查看日志 | `container.logs` | 容器名 | 无 |
| 启动 | `container.start` | 容器名 | 无 |
| 停止 | `container.stop` | 容器名 | 无 |
| 移除 | `container.remove` | 容器名 | 无 |

流程：`POST /api/v1/hosts/{hostId}/operations`（带 `Idempotency-Key`）→ 轮询
`GET /api/v1/operations/{operationId}` 直到离开 `PENDING`/`RUNNING`/`UNKNOWN`（600 ms 间隔，
上限 20 秒）→ 只有 `SUCCEEDED` 才渲染结果，其余显示 `error.message`。

## 列表解析

Helper 执行 `docker ps --all --format "{{.Names}} {{.Status}}"` 并把 stdout 作为操作输出返回。
前端把它解析为行（纯函数 `parseContainerList`，`web/src/lib/containers.ts`）：

- 按**首个空格**切分：容器名不含空格，状态可以含空格；
- 空行、只有空白的行、CRLF 都安全忽略；
- 状态以 `Up` 开头视为运行中；含 `(Paused)` 单独判为**已暂停**，不会显示成健康运行；
- `Exited` / `Dead` / `Removal` 为已停止，`Created` 为已创建，`Restarting` 为重启中，其余为未知。

## 安全与边界

- 面板**不直接执行 docker**：一律经由非 root Agent + 受限 root Helper，端口发布仍只绑定 `127.0.0.1`。
- 移除只执行 `rm --force`，**命名数据卷保留**，不做隐式删除。
- 不提供 exec、compose、镜像管理；这些能力 Helper 目前没有，面板不会假装有。
- 运行时验收（真实 docker/podman、真实容器启停）需要一台已安装 Helper 的主机，本仓库的自动化测试只覆盖到解析与下发契约。
