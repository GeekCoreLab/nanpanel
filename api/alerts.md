# 告警 API

告警规则声明在 NaNPanel **已经采集的观测**之上：主机状态（`host.status`）、最近一次 Helper 状态（`agent.helper_status`）与 systemd 单元状态（`system_service_observation`）。求值是**实时**的——只在读取时计算，不保存告警实例，因此观测恢复后告警立即消失，不引入额外的告警状态机或通知噪声。

## 端点

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/alert-rules` | 规则列表（含主机名），按创建时间倒序，最多 200 条 |
| POST | `/api/v1/alert-rules` | 创建规则（Cookie + CSRF），返回 201 |
| DELETE | `/api/v1/alert-rules/{ruleId}` | 删除规则 |
| GET | `/api/v1/alerts` | 用启用中的规则实时求值，返回当前触发中的告警 |

## 规则请求体

```json
{
  "name": "生产主机离线",
  "condition": "HOST_NOT_ONLINE",
  "hostId": null,
  "unitName": null,
  "severity": "CRITICAL"
}
```

- `condition` 取值：`HOST_NOT_ONLINE`（主机状态不是 `ONLINE`）、`HELPER_UNAVAILABLE`（Helper 不是 `AVAILABLE` 或未知）、`SERVICE_INACTIVE`（systemd 单元不是 `active`）。
- `hostId` 为空表示适用于所有主机；`unitName` **仅** `SERVICE_INACTIVE` 使用，且必填（`[A-Za-z0-9:_.@-]{1,255}`）；其它条件必须不带 `unitName`。
- `severity`：`WARNING` / `CRITICAL`。
- 同一 `(condition, hostId, unitName)` 重复创建返回 409 `ALERT_RULE_DUPLICATE`。

## 求值结果

```json
{
  "generatedAt": "2026-10-30T10:00:00Z",
  "evaluatedRules": 3,
  "alerts": [
    { "ruleId": "…", "ruleName": "生产主机离线", "severity": "CRITICAL",
      "condition": "HOST_NOT_ONLINE", "hostname": "host-a", "detail": "status=OFFLINE" }
  ]
}
```

排序：`CRITICAL` 优先，其次规则名与主机名升序。`detail` 说明触发原因（`status=…` / `helper=…` / `<unit> · <state>`）。

## 错误

| 状态 | code | 说明 |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` | 未登录 |
| 403 | `PASSWORD_CHANGE_REQUIRED` | 首登未改密 |
| 404 | `HOST_NOT_FOUND` | 绑定的主机未登记 |
| 404 | `ALERT_RULE_NOT_FOUND` | 规则不存在 |
| 409 | `ALERT_RULE_DUPLICATE` | 相同条件/主机/单元已存在 |
| 422 | `VALIDATION_FAILED` | 名称、条件、级别、unit 或 UUID 非法 |

## 边界

- 目前只覆盖「状态类」告警（离线、Helper 不可用、单元未运行）。CPU/内存/磁盘等**指标类**告警需要 Prometheus 指标接入，尚未实现。
- 没有通知通道（邮件/Webhook）与历史告警留存；控制台只显示当前触发中的告警。
