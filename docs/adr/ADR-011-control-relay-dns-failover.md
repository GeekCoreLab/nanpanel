# ADR-011：双向建连、主控代理与 DNS 故障切换

状态：接受（目标设计，待实现）。

## 决策

支持 Agent 主动连接 Core、Core 主动连接可达 Agent，以及 Core/Agent 各自主动连接公网 Rust Control Relay。所有模式建立全双工控制通道。Core 保留 Java JVM，公网主控代理采用 Rust，普通用户 Agent/root Helper 边界不变。

Core 预连接至少两个代理；以 Cloudflare 受管节点名及保留 `api.<cluster-domain>` CNAME 发现当前健康代理。DNS 协调单写、有租约、读回验证；候选必须已有有效 Core 反向通道。Agent 疑似网络故障时限次直查 `1.1.1.1`/`1.0.0.1`，并结合可信备用名单重连。DNS 不能代替身份认证、会话恢复或 NAT 通道。

详细协议、安全边界、恢复预算与验收见 [设计文档](../architecture/control-connectivity.md)。

## 代价与限制

增加公网代理运维、跨语言协议、证书轮换、会话去重及 DNS 协调成本。DNS 缓存使切换非即时；代理高可用不能代替 Core/数据库高可用。现有单向拨号心跳链路需逐步迁移，不将此决策描述为已实现功能。
