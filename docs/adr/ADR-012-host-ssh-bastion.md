# ADR-012：资产级 SSH 连接与堡垒终端审计

状态：接受；代码已实现，真实主机验收待完成。

## 背景

现有 SSH 管理只从 Core 主机读取 root 管理的 alias/known_hosts，适用于固定探测、allowlist systemd 运维和 Agent 初始化，但不便于临时纳管或由管理员主动操作一台目标主机。主机资产需要保存自己的 SSH 地址和认证方式，并支持可追溯的交互式 Shell。

## 决策

- 每台主机资产可配置 IPv4、IPv6 或 DNS 地址、端口、用户名，以及密码或私钥/私钥口令。凭据使用 AES-256-GCM 加密后单独存入 `host_ssh_config.encrypted_credential`，关联主机 UUID 作为认证附加数据；GET 接口只返回非秘密元数据。解密密钥由部署侧 `NANPANEL_SSH_CREDENTIAL_KEY` 注入，Kubernetes Secret 名为 `nanpanel-ssh-credentials-key`。数据库与 Secret 必须一并安全备份；替换密钥前要迁移所有密文。
- 首次连接先无凭据探测 SSH host key；探测结果只保留五分钟。管理员通过可信渠道核对 SHA-256 指纹并提供确认原因，才能保存信任。后续所有 SSH 认证都在比较 host key 后发生；地址或端口改变会清除已信任指纹。保留原 root-managed alias 流程用于固定范围的 SSH 操作。
- 已登记但尚未安装 Agent 的资产可省略安装请求的 `alias`，Core 用资产直连配置运行固定、随镜像发布的安装脚本。一次性接入码只经 SSH stdin 发送，并以首次 mTLS 心跳确认安装结果。
- 交互终端是独立的管理员高权限操作，不进入 Agent 或 root Helper 执行路径。管理员先填写操作原因，再创建绑定当前 Web 登录会话的 60 秒一次性 WebSocket ticket；Core 校验同源 Origin，并以已固定 host key 连接目标 SSH PTY。仅开放 PTY shell，不提供 SFTP、SCP 或端口转发。会话要求仍有效的管理员登录会话。
- 堡垒审计与终端数据专用表存储：`bastion_session` 保存操作者、原因、目标快照、状态和退出信息；`bastion_command` 保存提交命令行的密文；`bastion_recording_chunk` 保存逐块输入/输出密文、方向、序号和时间偏移。命令和录像都用 AES-GCM 加密，认证附加数据绑定会话、序号和数据类型；读取接口验证并解密并记录审计事件。默认 8 MiB 且最多 100,000 块/会话，结束会话默认从开始时间起保留 30 天，保留周期可设置为 1–3650 天。
- 操作原因进入普通审计摘要；票据、SSH 凭据、命令文本和原始录像不写入普通摘要/日志。终端输入和输出均可能包含秘密，UI 显示明确提示。命令行历史由提交给 PTY 的输入行提取，可用于追查终端操作；它不是 shell auditd 记录，不保证归因到 shell 内启动的所有子进程行为。

## 后果

加密密钥不可恢复时，资产凭据与堡垒录像/命令密文都会不可读。密钥应受限保存、纳入可恢复的备份流程并为所有 Core 实例保持一致；密钥轮换需要维护窗口和迁移程序。终端录像会快速增加数据库与备份体积，服务端执行固定上限并清理已结束会话。主机 SSH 用户权限是最终命令能力边界，平台只允许管理员有原因地显式开启会话，不将交互式 Shell 暴露给 Agent/Helper 协议或普通匿名用户。

## 实施与验收

实现位于 `HostSshCredentialService`、`HostSshConfigResource`、`BastionSessionService` 和 `BastionTerminalWebSocket`；数据库迁移为 `018-ssh-bastion.sql`。本地 Apache MINA SSHD 协议测试覆盖密码和私钥认证、host key pinning、错误 pin 拒绝及加密数据与主机/会话上下文绑定。发布前仍须在非 K3s Linux 主机验收真实网络、终端交互、会话撤销、录像回放、失败路径、保留清理和 SSH 账号权限边界。
