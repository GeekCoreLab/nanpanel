# 备份

对主机上的路径打包为受管 `tar.gz`，并把「谁在什么时候对哪台主机的哪个路径做了什么备份」记进面板。

## 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/backups` | 记录列表（含主机名、状态、`operationStatus`）；`?instanceId=` 只看某个数据库实例 |
| POST | `/api/v1/backups` | `{hostId, source, name, instanceId?}` → 202，下发 `backup.create` |
| POST | `/api/v1/backups/{id}/restore` | `{target, sha256?}` → 下发 `backup.restore` |
| DELETE | `/api/v1/backups/{id}` | 下发 `backup.delete` 后删除记录 |

写操作需 `Idempotency-Key`；`GET` 需 `host.read`，其余需 `host.write`；全部写审计。

## 记录来自主机，不是猜的

创建时记录只有 `pending-<uuid>` 占位名；读取列表时会补齐**主机实际产出的**文件名、大小与
SHA-256（来自 `backup.create` 操作的结果）。因此：

- 备份失败或主机离线时，记录不会出现「看起来成功」的假文件；
- 同一个操作只会生成一条记录（按 `operation_id` 去重）；
- 状态按关联操作派生：`PENDING`/`RUNNING` 显示创建中，`FAILED` → 失败，`UNKNOWN` → 状态未定。

## 恢复的硬性约束

- 目标必须是**已存在且为空**的绝对目录；面板不合并、不覆盖。
- 默认使用记录里的 SHA-256 校验归档；不匹配则一个字节都不写。
- 解包先落在同级临时目录，成功后才移入目标；失败不留半成品。
- 记录还是 `pending-…`（文件尚未生成）时恢复返回 `409 BACKUP_NOT_READY`。

## 删除的语义

- 主机会话正常：先下发 `backup.delete`，再删除记录。
- **主机离线：返回 503 且保留记录**——文件还在主机上，不能说已删除。
- 尚未产出文件（`pending-…`）的记录没有可删的归档，直接删除记录并返回 204。

## 与数据库实例的关联

`instanceId` 是可选的：带上它，这条备份就计入该实例的备份历史（`GET /api/v1/backups?instanceId=…`），
控制台的实例行可以直接看到自己的备份并在原处恢复/删除。

约束只有一条但很重要：**实例必须属于同一台主机**。跨主机的 `instanceId` 与不存在的实例都返回
`422 VALIDATION_FAILED`，不会存成悬空引用——否则一份备份会挂在一台根本没跑那个数据库的主机上。
