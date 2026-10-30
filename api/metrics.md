# 主机指标

只读时间序列：CPU 使用率、内存使用率、1 分钟负载。数据来自主机上的 `metrics.read`（读
`/proc/stat`、`meminfo`、`loadavg`、`uptime`），由控制面定时采样，保留 **7 天**。

## 采样怎么来的

没有 push 通道，面板每 30 秒主动取一次快照：

1. `MetricsSampler` 挑出 `status=ONLINE` 且能力里带 `metrics.read`、并且没有在途同类操作的主机；
2. 以 30 秒为窗口生成幂等键下发一次 `metrics.read`（窗口内重试会被去重）；
3. 下一轮扫描已 `SUCCEEDED` 的 `metrics.read` 操作，解析结果写一条 `host_metric_sample`；
   **同一个操作只会采样一次**（`operation_id` 唯一）；
4. 删除 7 天前的样本。

采样失败、主机离线、快照字段自相矛盾（例如 `idleTotal > total`、`availableKib > totalKib`）时
**不写样本**，而不是写 0。

## 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/hosts/{hostId}/metrics?limit=60` | 返回该主机最近的样本，按时间升序 |

- `limit` 取值 2–240（默认 60）；为算第一个点的 CPU 百分比会多取一条更早的样本，但只返回 `limit` 条。
- 权限：`host.read`。
- 响应：`{ hostId, hostname, limit, retentionDays, items: [{ recordedAt, cpuBusyPercent, memoryUsedPercent, load1, uptimeSeconds }] }`。

## CPU 百分比是差值，不是快照值

`cpuBusyPercent = (Δtotal − Δidle) / Δtotal × 100`，其中 `Δ` 是相邻两次采样的原始计数器差：

- **第一条采样返回 `null`**（单次快照不构成百分比），控制台把它画成缺口；
- 计数器倒退（内核重启/回绕）或 `Δtotal` 为 0 时返回 `null`，**不会钳到 0%**；
- 结果保留一位小数并限制在 0–100。

内存使用率 = `(MemTotal − MemAvailable) / MemTotal`；`MemTotal` 为 0 时返回 `null`。
