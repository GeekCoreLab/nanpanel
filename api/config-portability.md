# 配置导出 / 导入

把面板的**定义**搬到另一台机器：站点、告警规则、通知渠道。不涉及任何运行时状态、证书或凭据。

## 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/config/export` | 导出 JSON 文档（`Content-Disposition: attachment`） |
| POST | `/api/v1/config/import?strategy=skip\|overwrite&dryRun=false` | 导入文档，返回逐条结果报告；`dryRun=true` 只预演不写入 |

权限：`admin.settings.manage`（导出与导入都算平台设置操作），两个动作都写审计（`config.export` / `config.import`）。

## 文档格式

```json
{
  "version": 1,
  "exportedAt": "2026-10-30T09:00:00Z",
  "settings": { "siteName": "生产面板", "defaultTimezone": "Asia/Shanghai",
                "hostOfflineAfterSeconds": 90, "operationRetentionDays": 30 },
  "alertChannels": [{ "name": "值班群", "url": "https://…", "enabled": true, "notifyRecovery": false }],
  "alertRules": [{ "name": "主机离线", "condition": "HOST_NOT_ONLINE", "severity": "CRITICAL",
                   "enabled": true, "hostname": "web-01", "unitName": null, "channelName": "值班群" }],
  "sites": [{ "hostname": "web-01", "domain": "shop.example.com", "listenAddress": "192.0.2.10",
              "listenPort": 80, "upstreamPort": 8080, "healthPath": "/healthz" }],
  "checksum": "sha256…"
}
```

- **引用用名字不用 UUID**：主机是 `hostname`、渠道是 `name`，所以同一份文档能在另一台面板上落地。
- **校验和**：`checksum` 是对「去掉 `checksum` 之后、键名递归排序」的载荷做 SHA-256。导入先验版本再验摘要，
  不匹配返回 `422 EXPORT_CHECKSUM_MISMATCH`，版本不符返回 `422 EXPORT_VERSION_UNSUPPORTED`，
  绝不部分应用。
- **不含**：站点状态、TLS 材料、`operation_id`、渠道的历史投递结果、任何密钥。

## 导入语义

先平台设置、再渠道、再站点、最后规则（规则按名字引用渠道，同一文档里新建的渠道也算）。

| 对象 | 自然键 | 已存在时 |
| --- | --- | --- |
| 平台设置 | 单值 | `skip` 保留本机设置（本机尚无设置时仍然应用） / `overwrite` 替换站点名、时区、离线阈值、保留天数 |
| 通知渠道 | `name` | `skip` 跳过 / `overwrite` 更新 url、enabled、notifyRecovery |
| 站点 | `(host_id, domain, listen_port)` | `skip` 跳过 / `overwrite` 更新地址、上游端口、健康路径 |
| 告警规则 | `name` | `skip` 跳过 / `overwrite` 更新条件、主机、单元、级别、启停、渠道 |

- 站点一律以 **`DRAFT`** 导入：导入不会向主机下发路由，需要在站点页显式 `apply`。
- 主机在本面板未登记、通知渠道不存在、字段非法时，该条**记入报告的 `failed`**（含 `kind`/`name`/`reason`），其余条目继续。
- 报告：`{ "strategy": "skip", "dryRun": false, "imported": 4, "skipped": 0, "failed": [] }`。
- **`dryRun=true`** 走完全相同的校验与冲突判定，只把结果算出来返回，不写库；因此可以在真正导入前先看清会成功/跳过/失败哪些条目。预演同样会把「同一文档里先创建的渠道」算作可解析，不会误报渠道不存在。

## 控制台

「平台设置」页新增「配置迁移」卡片：导出下载 JSON、选择冲突策略、可勾选「仅预演（不写入）」、选择文件导入，并展示成功/跳过/失败与失败原因（预演结果会标注「未写入」）。
