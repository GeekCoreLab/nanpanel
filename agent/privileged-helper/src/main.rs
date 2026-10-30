use nanpanel_privileged_helper::{
    BackupConfig, CronConfig, HelperConfig, MetricsConfig, NginxRouteConfig,
    apply_network_firewall_rule, rollback_network_firewall_rule, serve,
};

#[derive(Debug, PartialEq, Eq)]
struct ServiceArgs {
    socket: String,
    allowed_agent_uid: u32,
    socket_gid: u32,
    allowed_read_root: String,
    controller_url: String,
    controller_cert: String,
    agent_binary: String,
}

fn parse_service_args(
    first: Option<String>,
    mut args: impl Iterator<Item = String>,
) -> anyhow::Result<ServiceArgs> {
    let socket = first.unwrap_or_else(|| "/run/nanpanel/helper.sock".to_owned());
    let allowed_agent_uid = args
        .next()
        .ok_or_else(|| anyhow::anyhow!("missing allowed Agent UID"))?
        .parse::<u32>()?;
    let socket_gid = args
        .next()
        .ok_or_else(|| anyhow::anyhow!("missing socket group ID"))?
        .parse::<u32>()?;
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
    Ok(ServiceArgs {
        socket,
        allowed_agent_uid,
        socket_gid,
        allowed_read_root,
        controller_url,
        controller_cert,
        agent_binary,
    })
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let mut args = std::env::args().skip(1);
    let first = args.next();
    match first.as_deref() {
        Some("rollback") => {
            let operation_id = args
                .next()
                .ok_or_else(|| anyhow::anyhow!("network rollback requires an operation UUID"))?;
            if args.next().is_some() {
                anyhow::bail!("network rollback accepts exactly one operation UUID");
            }
            return rollback_network_firewall_rule(&operation_id).await;
        }
        Some("apply-firewall") => {
            let operation_id = args
                .next()
                .ok_or_else(|| anyhow::anyhow!("network apply requires an operation UUID"))?;
            let interface = args
                .next()
                .ok_or_else(|| anyhow::anyhow!("network apply requires an interface"))?;
            let protocol = args
                .next()
                .ok_or_else(|| anyhow::anyhow!("network apply requires tcp or udp"))?;
            let port = args
                .next()
                .ok_or_else(|| anyhow::anyhow!("network apply requires a port"))?
                .parse::<u16>()?;
            let rollback_seconds = args
                .next()
                .ok_or_else(|| anyhow::anyhow!("network apply requires a rollback timeout"))?
                .parse::<u16>()?;
            if args.next().is_some() {
                anyhow::bail!("network apply received unexpected arguments");
            }
            let result = apply_network_firewall_rule(
                &operation_id,
                &interface,
                &protocol,
                port,
                rollback_seconds,
            )
            .await?;
            println!("{result}");
            return Ok(());
        }
        _ => {}
    }
    let service = parse_service_args(first, args)?;
    let routes = NginxRouteConfig::from_environment()?;
    let cron = CronConfig::from_environment()?;
    let metrics = MetricsConfig::from_environment()?;
    let backup = BackupConfig::from_environment()?;
    serve(
        HelperConfig::new(
            service.socket,
            service.allowed_agent_uid,
            service.socket_gid,
            service.allowed_read_root,
            &service.controller_url,
            service.controller_cert,
            service.agent_binary,
        )?
        .with_nginx_routes(routes)
        .with_cron(cron)
        .with_metrics(metrics)
        .with_backup(backup),
    )
    .await
}

#[cfg(test)]
mod tests {
    use super::{ServiceArgs, parse_service_args};

    #[test]
    fn parses_service_arguments_after_socket_path() -> anyhow::Result<()> {
        let arguments = [
            "999",
            "988",
            "/etc/nanpanel/managed",
            "https://panel.example.test",
            "/etc/nanpanel-agent/controller.pem",
            "/usr/local/bin/nanpanel-agent",
        ]
        .into_iter()
        .map(str::to_owned);

        let service = parse_service_args(Some("/run/nanpanel/helper.sock".to_owned()), arguments)?;

        let expected = ServiceArgs {
            socket: "/run/nanpanel/helper.sock".to_owned(),
            allowed_agent_uid: 999,
            socket_gid: 988,
            allowed_read_root: "/etc/nanpanel/managed".to_owned(),
            controller_url: "https://panel.example.test".to_owned(),
            controller_cert: "/etc/nanpanel-agent/controller.pem".to_owned(),
            agent_binary: "/usr/local/bin/nanpanel-agent".to_owned(),
        };
        assert_eq!(service, expected);
        Ok(())
    }
}
