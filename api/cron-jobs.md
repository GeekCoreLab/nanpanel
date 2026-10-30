# 计划任务

面板保存「意图」，显式应用才把受管 cron 文件写到主机；停用则撤回。任务标识由面板固定前缀
`nanpanel-`，Helper 只接受这个前缀，因此**手工维护的 crontab 永远不会被面板读写或删除**。

## 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/cron-jobs` | 列表（含主机名、状态、`operationStatus`） |
| POST | `/api/v1/cron-jobs` | `{hostId, name, schedule, command}` → 201，状态 `DRAFT` |
| PUT | `/api/v1/cron-jobs/{hostId}/{jobId}` | 改计划/命令（`APPLIED`/`UNKNOWN` 时 409，需先停用） |
| POST | `/api/v1/cron-jobs/{hostId}/{jobId}/apply` | 下发 `cron.write`（需 `Idempotency-Key`） |
| POST | `/api/v1/cron-jobs/{hostId}/{jobId}/disable` | 下发 `cron.remove` 撤回受管文件 |
| DELETE | `/api/v1/cron-jobs/{hostId}/{jobId}` | 删记录（`APPLIED`/`UNKNOWN` 时 409） |

权限：`GET` 需 `host.read`，其余需 `host.write`。所有写操作写审计（`cron-job.create|update|apply|disable|delete`）。

## 规则

- `name` 为 2–32 位小写字母/数字/连字符（首字母、尾不为连字符），实际任务标识是 `nanpanel-<name>`；
  同一主机上同名任务重复创建返回 `409 CRON_JOB_EXISTS`。
- 计划与命令用**下发时的同一个校验器**校验（5 段 `[0-9*,-/]`、单行命令 ≤900 字节、只允许 root），
  所以存下来的任务一定能通过下发校验。
- 状态按最近一次关联 Operation 派生：`PENDING`/`RUNNING` 显示「处理中」，`FAILED` → `FAILED`，
  `UNKNOWN` → `UNKNOWN`（此时编辑与删除都被拒绝，必须先停用）。
- 下发失败（如主机离线）不会留下「已生效」的假象：`DRAFT` 会转为 `FAILED`，可重新应用或删除。
- 删除只删面板记录；已生效的任务必须先「停用」才会从主机撤回。

## 主机侧形态

`/etc/cron.d/nanpanel-<name>`（目录由 `NANPANEL_CRON_DIR` 指定，需 root 拥有）：

```
# Managed by NaNPanel; job nanpanel-backup
SHELL=/bin/sh
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
*/15 2 * * * root /usr/local/bin/backup.sh
```

写入走临时文件 + rename，**写后回读比对**；删除前必须读到归属标记，否则拒绝。
