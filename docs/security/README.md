# 安全边界

边缘权限基线为普通用户 Rust Agent + root Rust Helper。两者直接使用宿主机环境运行，systemd unit 不启用文件系统、临时目录、内核或 syscall 沙箱；Agent 仍以 `nanpanel-agent` 普通用户运行，Helper 仍以 root 运行。Helper 仅开放本地受控 IPC，校验调用方 UID、操作类型、参数、目标路径、截止时间和可用系统工具，记录操作结果并限制输出、超时与并发；不接收任意 Shell。软件包、systemd、受保护文件、容器、主机文件和防火墙操作没有逐项名单配置。Core 管理 API 通过已认证的管理员会话下发固定操作；Agent mTLS 身份、Helper IPC 身份、结构化参数验证和审计共同构成执行边界。

主机文件管理仅处理普通 UTF-8 文件，拒绝 `.`、`..` 与符号链接路径组件，限制读写大小和目录项数量；读取内容会留在受保护的 Operation 结果中。容器操作固定 CLI 和资源参数，端口仅绑定 loopback。临时防火墙规则须先启动 systemd 回滚定时器，再由只获得 `CAP_NET_ADMIN` 的瞬时 worker 执行。rootful/rootless 容器按工作负载配置，与 Agent 身份独立，详见 [ADR-009](../adr/ADR-009-agent-privilege-boundary.md)。

SSH 管理使用主机资产内 AES-256-GCM 加密保存的密码或私钥，并校验管理员已确认的 host key。固定探测、systemd 状态/日志/重启和 Agent 初始化都使用这份资产连接配置；Core 不读取独立 alias/unit 名单或主控侧 OpenSSH alias 配置。管理员可另行开启 PTY 堡垒 Shell。堡垒票据一次使用、短时有效并绑定当前 Web 会话；会话元数据单独保存，命令行和终端输入/输出录像分别加密后写入专用表，默认 30 天清理且每会话最多 8 MiB。管理员可按原因强制阻断待连接或运行中会话，并生成独立审计事件。界面提醒终端输入可能包含会被录像的秘密；凭据、ticket、Cookie、CSRF token 不写入审计摘要或应用日志。Shell 允许目标 SSH 账号本身拥有的命令权限；功能不开放 SFTP 或端口转发。细节见 [SSH 堡垒 ADR](../adr/ADR-012-host-ssh-bastion.md)。
