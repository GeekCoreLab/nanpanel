管理型 `/api/v1` 路由要求 Cookie 会话和写请求 CSRF；一次性 Agent 接入使用独立 Bearer 接入码，内部 Agent/Gateway 路由要求 Gateway 凭据，Agent 控制连接使用 mTLS。Core 使用数据库账号与会话，不再接受 Basic 认证；生产 Cookie 默认 Secure/HttpOnly/SameSite=Lax。认证、管理与主机操作审计已持久化。

边缘权限基线为普通用户 Rust Agent + root Rust Helper。Helper 仅开放本地受控 IPC，验证调用方身份、操作级授权、能力白名单、参数与路径，记录操作结果并限制超时；不接收任意 Shell。软件包、受保护文件、容器和网络规则使用独立 root allowlist，安装器创建的 allowlist 默认为空。临时防火墙规则需先启动 systemd 回滚定时器，并由只获得 `CAP_NET_ADMIN` 的瞬时 worker 执行。rootful/rootless 容器按工作负载配置，与 Agent 身份独立，详见 [ADR-009](../adr/ADR-009-agent-privilege-boundary.md)。

SSH 管理保留 host-bound alias 下的固定探测、显式 allowlist systemd 状态/日志读取与重启；另提供资产级直连 SSH 配置，凭据使用 AES-256-GCM 加密并由 Kubernetes Secret 中的 `NANPANEL_SSH_CREDENTIAL_KEY` 解密。首次连接只无认证探测 host key，要求管理员核对指纹并显式信任；凭据只在指纹匹配后用于 SSH 认证。直连地址可用于固定脚本 Agent 初始化，也可由管理员开启 PTY 堡垒 Shell。堡垒票据一次使用、短时有效并绑定当前 Web 会话；会话元数据单独保存，命令行和终端输入/输出录像分别加密后写入专用表，默认 30 天清理且每会话最多 8 MiB。管理员可按原因强制阻断待连接或运行中会话，并生成独立审计事件。界面提醒终端输入可能包含会被录像的秘密；凭据、ticket、Cookie、CSRF token 不写入审计摘要或应用日志。Shell 允许目标 SSH 账号本身拥有的命令权限，主机侧仍须配置最小权限账号；功能不开放 SFTP 或端口转发。细节见 [SSH 堡垒 ADR](../adr/ADR-012-host-ssh-bastion.md)。

剩余安全工作包括 Agent 证书吊销/续期、跨实例共享登录限流、完整泄露密码检查，以及直连 SSH / 堡垒终端的真实双主机和故障场景验收。具体接口和状态见 [Web 平台 API](../../api/web-platform.md) 与 [项目计划](../../PROJECT_PLAN.md)。
