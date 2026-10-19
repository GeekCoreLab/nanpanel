所有 /api/* 路由要求认证。基础阶段使用显式提供的管理员密码，仅监听回环地址；生产身份认证、密钥加密、审计及 Agent mTLS 尚待实现。

边缘权限基线为普通用户 Rust Agent + root Rust Helper。Helper 仅开放本地受控 IPC，验证调用方身份、操作级授权、能力白名单、参数与路径，记录审计并限制超时；不默认接收任意 Shell。rootful/rootless 容器按工作负载配置，与 Agent 身份独立。当前 Helper 和相关授权机制尚未实现，详见 [ADR-009](../adr/ADR-009-agent-privilege-boundary.md)。

Web 目标认证已在 [Web 平台 API](../../api/web-platform.md) 定义为服务端 Cookie 会话 + CSRF 校验，并补充强制改密、会话撤销、重新认证、平台管理授权与审计。登录已迁移为数据库账号与 Cookie 会话，生产默认 Secure/HttpOnly/SameSite=Lax；完整审计和多实例共享限流仍待实现；Agent mTLS 和 root Helper 的权限边界保持独立。
