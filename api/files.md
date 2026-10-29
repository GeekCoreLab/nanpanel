# 主机文件管理

控制台「文件」页没有新增 HTTP 端点：它复用既有的类型化 Helper 操作端点 `POST /api/v1/hosts/{hostId}/operations` 与任务查询 `GET /api/v1/operations/{operationId}`，能力为 `file.list` / `file.read` / `file.write` / `file.delete`。

## 交互模型

每次浏览、读取、保存、删除都是一次有审计的 Operation：

1. `POST /api/v1/hosts/{hostId}/operations`，`capability` 与 `target`（绝对路径）见下表，返回 202 与任务。
2. 轮询 `GET /api/v1/operations/{operationId}` 直到 `status` 离开 `PENDING` / `RUNNING` / `UNKNOWN`（控制台每 600 ms 一次，最多 20 秒）。
3. 终态为 `SUCCEEDED` 时读取 `result.output`；`FAILED` / `UNKNOWN` 时展示 `error.message`。

| 能力 | target | 载荷 | 成功时 `result.output` |
| --- | --- | --- | --- |
| `file.list` | 目录绝对路径 | 无 | JSON 文本：`{"path":"…","entries":[{"name","kind","sizeBytes"}],"truncated":false}` |
| `file.read` | 文件绝对路径 | 无 | 文件内容（最多 64 KiB） |
| `file.write` | 文件绝对路径 | `{"content":"…"}` | 写入结果文本 |
| `file.delete` | 文件绝对路径 | 无 | 删除结果文本 |

`kind` 取值为 `directory` / `file` / `symlink` / `other`；`sizeBytes` 仅普通文件有值。

## 边界与限制

- 路径必须是绝对路径，拒绝 `.`、`..` 与符号链接；文件操作不会跟随链接逃逸到宿主目录之外。
- 读取上限 64 KiB、写入上限 32 KiB（超出由 Helper 拒绝），单目录最多返回 200 项并置 `truncated`。
- 只处理普通文件：二进制内容按 UTF-8 解码后可能显示为乱码，不提供下载/上传。
- 控制台会先做一次本地路径规范化（拒绝非绝对路径与 `..`），但这只是体验优化；**安全边界始终在 Helper 侧**。
