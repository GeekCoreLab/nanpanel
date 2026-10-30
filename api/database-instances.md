# 托管数据库实例

面板在受管主机上部署并负责 MySQL / MariaDB / PostgreSQL 容器。与 `GET /api/v1/databases`
（从已部署容器里**识别**出来的只读视图）不同：这里每个实例都是面板创建的，面板知道它的端口、
数据卷、库名与部署状态。

## 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/database-instances/engines` | 支持的引擎与**固定镜像版本** |
| GET | `/api/v1/database-instances` | 实例列表（主机名、端口、数据卷、状态、`operationStatus`） |
| POST | `/api/v1/database-instances` | `{hostId, engine, port?, memoryMb?}` → 202，**响应里含一次性口令** |
| GET | `/api/v1/database-instances/{id}/connection` | 连接信息；**永不返回口令** |
| DELETE | `/api/v1/database-instances/{id}` | 下发 `container.remove` 后删除记录 |

`GET` 需 `host.read`，其余需 `host.write`；写操作需 `Idempotency-Key`，全部写审计。

## 引擎（镜像固定）

| id | 镜像 | 容器端口 | 数据目录 | 默认主机端口 |
| --- | --- | --- | --- | --- |
| `mysql` | `mysql:8.4` | 3306 | `/var/lib/mysql` | 13306 |
| `mariadb` | `mariadb:11.4` | 3306 | `/var/lib/mysql` | 13307 |
| `postgres` | `postgres:16` | 5432 | `/var/lib/postgresql/data` | 15432 |

镜像不带浮动 tag：运行中的实例不会因为 `latest` 变更而悄悄换版本。

## 口令：只出现一次

创建时面板生成 24 位口令（去掉 `l/I/1/O/0` 等易混字符），通过环境变量交给容器
（`MYSQL_ROOT_PASSWORD` / `POSTGRES_PASSWORD`），并**只在该次 202 响应体的 `credentials` 里返回**：

- 数据库里**没有口令列**，审计与日志只记录「创建了哪个实例、端口多少」；
- 响应丢失 = 口令丢失，只能到主机上重置或删除实例用新口令重建；
- `GET /{id}/connection` 明确返回 `passwordStored: false`。

## 网络与数据

- 容器端口**只发布到 `127.0.0.1`**（Agent 侧固定），默认不对公网开放；
- 数据在命名卷里（`npdb-<8hex>`），`stateful: true`；**删除实例不删除数据卷**，
  审计里会写下保留了哪个卷；
- 同一主机上端口唯一（`UNIQUE(host_id, host_port)`），重复申请返回 `409 PORT_IN_USE`。

## 失败语义

- 主机没有 Agent：返回 `503 AGENT_OFFLINE`，**不留下记录**（没有部署就没有实例）；
- 删除时主机不可达：返回 `503` 且**保留记录**——容器可能还在跑，面板不能假装删掉了；
- 状态按关联操作派生：`PENDING`/`RUNNING` 显示部署中，`FAILED` → 失败，`UNKNOWN` → 状态未定。
