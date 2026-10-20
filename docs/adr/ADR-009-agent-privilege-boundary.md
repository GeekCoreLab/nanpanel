# ADR-009：普通用户 Agent + root Helper

状态：接受（已实现）

## 背景

平台需承接传统运维面板的系统管理职责，包括软件包、systemd、防火墙、路由、受保护文件和系统级容器。Agent 必须能发起授权的 root 操作，但不需要让网络通信和所有任务逻辑都持有 root 权限。

## 决策

Agent 与自研边缘控制器使用专用普通用户；Rust `privileged-helper` 以 root 运行，通过 Unix Domain Socket 接收类型化能力请求。两个 systemd unit 直接使用宿主机环境，不设置 systemd 文件系统、临时目录、内核或 syscall 沙箱。Helper 校验调用方身份、操作授权、目标及参数，执行白名单操作，记录脱敏审计并限制超时和并发。完整要求见 PROJECT_PLAN.md 第 6.3 节。

Agent/Helper 协议不提供任意 Shell 执行接口。需要交互式远程终端时，作为独立的管理员高权限功能授权和审计，不借道 Agent 或 root Helper。主机 SSH 堡垒功能按 [ADR-012](ADR-012-host-ssh-bastion.md) 实现资产级 SSH 凭据、host key 确认、会话票据和终端录像。Agent 非 root 与容器 rootless 相互独立：系统支持按需管理 rootful 和 rootless 工作负载。

## 实施与验收

实际特权执行通过独立 Helper 落地。验证授权服务操作成功、非可信调用方与越权参数被拒绝，网络变更可回退，结果有审计。Agent/Helper 不使用 systemd 沙箱；Helper 的固定能力接口、root allowlist、参数校验和审计仍约束远程可请求的操作。各 Helper 能力的逐项 allowlist 默认生效；主机 root 可将 `/etc/nanpanel/managed/allowlist-mode` 设为 `disabled` 临时停用全部 Helper allowlist 匹配。固定参数边界、受保护文件目录、容器运行约束和网络自动回滚继续生效。

## 代价

增加 IPC、协议版本和安装权限管理；换取更小的高权限代码范围。Helper 仍可能因实现缺陷危及宿主机，进程拆分本身不替代授权与输入校验。
