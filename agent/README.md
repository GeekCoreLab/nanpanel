# Rust 边缘执行组件

后续建立独立 Cargo workspace：daemon、runtime-drivers、privileged-helper。protocol/ 保存跨语言 Protobuf 草案。当前只有目录和协议预留，尚未建立 Rust 源码或构建链。

自研边缘控制器统一使用 Rust；Java Agent Gateway 留在主控 backend/ 中。默认普通用户 Agent + root Helper，支持受控系统级运维。详见 [ADR-009](../docs/adr/ADR-009-agent-privilege-boundary.md) 与 [ADR-010](../docs/adr/ADR-010-rust-edge-components.md)。
