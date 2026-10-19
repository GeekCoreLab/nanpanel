# Rust Agent daemon

规划使用 Rust，以专用普通用户运行，负责 mTLS 通信、心跳重连、任务与已验证配置持久化。系统级操作通过本地 root Rust Helper 执行。当前尚未建立 Cargo 工程或实现守护进程。
