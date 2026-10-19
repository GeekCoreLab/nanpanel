# Rust privileged-helper

规划使用 Rust，以 root 身份运行，仅通过受控本地 Unix Domain Socket 接受类型化操作。校验调用方、操作授权、能力白名单、目标及参数，支持系统服务、软件包、受保护文件与网络配置的受限管理；不默认提供任意 Shell。需实现审计、超时及网络变更回退。当前未实现。详见 [ADR-009](../../docs/adr/ADR-009-agent-privilege-boundary.md)。
