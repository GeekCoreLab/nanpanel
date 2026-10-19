模块职责以 PROJECT_PLAN.md 为基线。Core 仅负责控制面，Provider 与 Agent 执行外部变更。

主控与边缘按部署边界划分：Core 内部的协调器、Fabric8 Connector、Agent Gateway 和 PF4J 插件保留 Java；部署在被管理主机或公网入口的自研 Agent、控制器、驱动及 Helper 统一使用 Rust，经版本化协议通信。边缘默认普通用户主进程 + root Helper。第三方数据面组件保留各自实现。
