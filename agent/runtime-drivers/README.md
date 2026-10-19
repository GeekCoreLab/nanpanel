# Rust runtime drivers

规划使用 Rust 实现 Docker / Podman 驱动和版本能力协商。Agent 的普通用户身份不限制管理 rootful 工作负载；特权调用经受控 Helper，rootless 运行时按工作负载所属用户上下文访问。当前未实现。
