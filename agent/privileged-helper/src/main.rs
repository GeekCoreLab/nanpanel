use nanpanel_privileged_helper::{HelperConfig, serve};

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let mut args = std::env::args().skip(1);
    let socket = args
        .next()
        .unwrap_or_else(|| "/run/nanpanel/helper.sock".to_owned());
    let allowed_agent_uid: u32 = args.next().unwrap_or_default().parse()?;
    let socket_gid: u32 = args.next().unwrap_or_default().parse()?;
    let allowed_read_root = args
        .next()
        .unwrap_or_else(|| "/etc/nanpanel/managed".to_owned());
    if allowed_agent_uid == 0 || socket_gid == 0 || args.next().is_some() {
        anyhow::bail!(
            "usage: nanpanel-helper <socket> <allowed-agent-uid> <socket-gid> <allowed-read-root>"
        );
    }
    serve(HelperConfig::new(
        socket,
        allowed_agent_uid,
        socket_gid,
        allowed_read_root,
    )?)
    .await
}
