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
    let controller_url = args.next().unwrap_or_default();
    let controller_cert = args.next().unwrap_or_default();
    let agent_binary = args.next().unwrap_or_default();
    if allowed_agent_uid == 0
        || socket_gid == 0
        || controller_url.is_empty()
        || controller_cert.is_empty()
        || agent_binary.is_empty()
        || args.next().is_some()
    {
        anyhow::bail!(
            "usage: nanpanel-helper <socket> <allowed-agent-uid> <socket-gid> <allowed-read-root> <controller-https-origin> <controller-cert> <agent-binary>"
        );
    }
    serve(HelperConfig::new(
        socket,
        allowed_agent_uid,
        socket_gid,
        allowed_read_root,
        &controller_url,
        controller_cert,
        agent_binary,
    )?)
    .await
}
