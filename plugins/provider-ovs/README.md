# OVS Provider boundary

可执行的 OVS Switch 后端当前集成在 Core 内建 `linux-netns` Provider 中，
能力 ID 为 `linux.ovs`，通过现有 Plan / Validate / Apply / Verify /
Compensate 生命周期执行。此目录仍是独立 PF4J 插件封装的预留位置。

每个拓扑 Revision 使用独立 OVSDB、`ovs-vswitchd` 和 Linux netns，采用
`netdev` 用户态 datapath。Apply 要求执行主机预装可信 OVS 工具、匹配的
`vswitch.ovsschema` 和 `/dev/net/tun`；Helper 不会安装包、接入宿主 OVS
daemon 或改变宿主路由。当前 OVS 图不能包含 Firewall 或 Packet Tap。

部署本身不会安装或修改外部组件。Edge 执行仍由 Rust Agent 与本地 root
Helper 完成。
