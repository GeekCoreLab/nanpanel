# 告警通知渠道

告警规则只描述「什么情况算告警」，渠道决定「告警发生时通知谁」。目前只有 **Webhook**（POST 一份
JSON），不引入邮件/短信这类需要外部凭据的通道。

## 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/alert-channels` | 列出渠道（含上次投递结果与绑定规则数） |
| POST | `/api/v1/alert-channels` | 创建渠道 `{name, url, enabled?}` |
| PUT | `/api/v1/alert-channels/{id}` | 改名 / 改地址 / 启停（字段可选） |
| DELETE | `/api/v1/alert-channels/{id}` | 删除；**绑定它的规则不删**，只把 `channel_id` 置空 |
| POST | `/api/v1/alert-channels/{id}/test` | 投递一条测试通知；成功 200，失败 502（带 `error`） |
| GET | `/api/v1/alert-events?kind=&limit=` | 通知历史（只读，最新在前，`limit` 1–200，默认 30） |

规则通过 `POST /api/v1/alert-rules` 的 `channelId` 字段绑定渠道（未知渠道返回 `404 CHANNEL_NOT_FOUND`）；
规则列表返回 `channelId` 与 `channelName`。`channelId` 为空表示「仅控制台可见」。

## 投递语义

- 后台每 60 秒求值一次（`AlertNotifier`），只对**新进入告警**的对象投递；处于告警中的对象不会每分钟重复推送。
- 去重键是 `规则 + 主机 + 明细`，记录在 `alert_firing_state`。观测恢复后会清除该键，**同一对象再次告警会重新通知**。
- 投递失败（非 2xx、超时 5 秒、连接错误）**不记录去重键**，下一次求值会重试；失败原因写在渠道的 `lastStatus`/`lastError`。
- **恢复也通知**：观测恢复时投递 `alert.recovered`，并清除去重键。每个渠道可用 `notifyRecovery=false`（`PUT` 的 `notifyRecovery`）关掉恢复通知——状态仍会清除，下一次故障会照常通知。
- 每次投递尝试（含失败）都写入 `alert_event`，可通过 `GET /api/v1/alert-events` 查到「什么时候、通知了谁、成功没有」。
- 生产默认开启定时器；`nanpanel.alerting.dispatch-enabled=false` 可关闭（测试用它把时钟排除在外）。

## 载荷

```json
{
  "event": "alert.firing",
  "ruleId": "…",
  "ruleName": "生产主机离线",
  "severity": "CRITICAL",
  "condition": "HOST_NOT_ONLINE",
  "hostname": "web-01",
  "detail": "主机状态 OFFLINE",
  "firedAt": "2026-10-30T08:40:00Z",
  "source": "nanpanel"
}
```

恢复事件结构相同，`event` 为 `alert.recovered`、时间字段为 `at`。

请求头带 `Content-Type: application/json`、`User-Agent: NaNPanel`、`X-NaNPanel-Event: alert.firing`（测试为 `alert.test`）。

## 边界

- 渠道 URL 由管理员填写，面板只校验 `http(s)` scheme 与长度，不做 SSRF 白名单——它属于管理员级能力。
- 不存储任何凭据：需要鉴权的接收端请把令牌放在 URL 或自建网关。
- 没有退避/限流队列：失败就等下一轮（60 秒），不做过期重放。
