# 数据库实例 API

把 NaNPanel 已经部署的数据库容器呈现为「数据库实例」，并给出可直接使用的连接地址。这是**只读派生视图**：数据来自已成功的 `container.deploy` 记录（排除被重部署或成功移除取代的容器）与部署元数据中的镜像/端口，不建立任何数据库连接，也不执行 SQL。

## 端点

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/databases` | 数据库实例列表 + 支持的引擎清单 |

响应：

```json
{
  "engines": [
    { "id": "mysql", "label": "MySQL", "defaultPort": 3306, "scheme": "jdbc:mysql://" }
  ],
  "items": [
    {
      "hostId": "…", "hostname": "db-host-1", "containerName": "nanpanel-mysql",
      "image": "mysql:8.0",
      "engine": { "id": "mysql", "label": "MySQL", "defaultPort": 3306, "scheme": "jdbc:mysql://" },
      "hostPort": 13306, "containerPort": 3306,
      "connectionUrl": "jdbc:mysql://db-host-1:13306",
      "note": "", "deployedAt": "2026-10-30T…"
    }
  ]
}
```

## 引擎识别

按镜像仓库名（去掉 registry、tag、digest 与路径前缀）匹配，别名不区分大小写：

| 引擎 | 别名 | 默认端口 | 连接前缀 |
| --- | --- | --- | --- |
| MySQL | `mysql`、`percona` | 3306 | `jdbc:mysql://` |
| MariaDB | `mariadb` | 3306 | `jdbc:mariadb://` |
| PostgreSQL | `postgres`、`postgresql` | 5432 | `jdbc:postgresql://` |
| Redis | `redis`、`valkey` | 6379 | `redis://` |
| MongoDB | `mongo`、`mongodb` | 27017 | `mongodb://` |

匹配规则是「仓库名等于别名，或以 `<别名>-` / `<别名>_` 开头」，因此 `mysql-exporter` 这类镜像不会被误认成数据库。未识别出引擎的容器不出现在该列表中。

## 连接地址

- `connectionUrl = scheme + hostname + ":" + hostPort`，其中 `hostPort` 是容器发布在主机 `127.0.0.1` 的端口，`hostname` 为登记的主机名。
- 容器未发布端口时 `hostPort` 与 `connectionUrl` 为 `null`，`note` 说明原因（「容器未发布端口，无法从主机外部连接」）。

## 生命周期

- **创建**：走应用商店一键部署（`container.deploy`），成功后自动出现在此列表。
- **卸载**：走 `container.remove`（`rm --force`，命名数据卷保留）。

## 边界（未实现）

- 不执行 SQL，因此没有 WebSQL、建库/建用户/权限管理。
- 没有备份/恢复与异地存储。
- 不探测实例是否可连接（没有到数据库协议的健康检查）。

以上都需要真实数据库主机才能验证，未写不可验证的实现。
