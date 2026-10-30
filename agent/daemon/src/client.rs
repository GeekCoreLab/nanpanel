use std::{
    collections::HashSet,
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};

use anyhow::{Context, Result, anyhow};
use hyper_util::rt::TokioIo;
use reqwest::Url;
use serde::Deserialize;
use tokio::{
    sync::{Mutex, mpsc},
    time,
};
use tokio_stream::wrappers::ReceiverStream;
use tonic::Streaming;
use tonic::transport::{Certificate, Channel, ClientTlsConfig, Endpoint, Identity};
use tracing::{info, warn};

use crate::gateway::{valid_topology_capture_dispatch, valid_topology_dispatch};
use crate::v1::{
    AgentMessage, ControlMessage, Heartbeat, KubernetesRuntimeDiscovery, NetbirdOverlaySummary,
    NetbirdPeerPath as NetbirdPeerPathMessage, NetworkAddress, NetworkInterface, NetworkRoute,
    OperationResult, RelayMessage, SystemdService, agent_gateway_client::AgentGatewayClient,
    agent_message::Payload, control_relay_client::ControlRelayClient,
    relay_message::Payload as RelayPayload,
};

use crate::{
    direct_dns, helper,
    operation_journal::{Claim, OperationJournal},
};

const MAX_CAPTURE_RESULT_BYTES: usize = 56 * 1024;

#[derive(Clone)]
struct HostSnapshot {
    hostname: String,
    os: String,
    arch: String,
}

#[derive(Clone, Debug, Deserialize)]
struct IpAddressInfo {
    family: Option<String>,
    local: Option<String>,
    prefixlen: Option<u32>,
    scope: Option<String>,
}

#[derive(Clone, Debug, Deserialize)]
struct IpInterfaceInfo {
    ifname: Option<String>,
    address: Option<String>,
    mtu: Option<u32>,
    operstate: Option<String>,
    addr_info: Option<Vec<IpAddressInfo>>,
}

#[derive(Clone, Debug, Deserialize)]
struct IpRouteInfo {
    dst: Option<String>,
    gateway: Option<String>,
    dev: Option<String>,
    protocol: Option<String>,
    scope: Option<String>,
    #[serde(rename = "type")]
    route_type: Option<String>,
    table: Option<serde_json::Value>,
    metric: Option<u32>,
}

#[derive(Clone, Debug)]
struct NetworkInventory {
    interfaces: Vec<NetworkInterface>,
    routes: Vec<NetworkRoute>,
    observed_at_epoch_ms: i64,
}

#[derive(Clone, Debug)]
struct NetbirdOverlayObservation {
    peer_count: u32,
    connected_peer_count: u32,
    p2p_peer_count: u32,
    relayed_peer_count: u32,
    observed_at_epoch_ms: i64,
}

#[derive(Clone, Debug)]
struct NetbirdPeerPathObservation {
    peers: Vec<helper::NetbirdPeerPath>,
    observed_at_epoch_ms: i64,
}

#[derive(Clone, Debug)]
struct KubernetesRuntimeObservation {
    detected: bool,
    kubelet_running: bool,
    api_server_local: bool,
    runtime_kind: String,
    observed_at_epoch_ms: i64,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct NetworkFirewallPayload {
    interface_name: String,
    protocol: String,
    port: u16,
    rollback_seconds: u16,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct NginxRoutePayload {
    listen_address: String,
    listen_port: u16,
    server_name: String,
    health_path: String,
    backends: Vec<NginxRouteBackend>,
    #[serde(default)]
    tls: Option<NginxRouteTls>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct BackupPayload {
    source: String,
    name: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct CronJobPayload {
    schedule: String,
    command: String,
    #[serde(default)]
    user: Option<String>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct NginxRouteTls {
    certificate: String,
    private_key: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct NginxTlsPayload {
    certificate_pem: String,
    private_key_pem: String,
}

fn valid_tls_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 32
        && name.as_bytes()[0].is_ascii_lowercase()
        && name
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
}

fn valid_pem(pem: &str, label: &str) -> bool {
    let trimmed = pem.trim();
    if trimmed.len() > 64 * 1024 {
        return false;
    }
    let begin = format!("-----BEGIN {label}-----");
    let end = format!("-----END {label}-----");
    trimmed.starts_with(&begin)
        && trimmed.ends_with(&end)
        && trimmed
            .bytes()
            .all(|byte| byte.is_ascii_graphic() || byte == b'\n' || byte == b'\r' || byte == b' ')
}

fn valid_nginx_tls_payload(payload: &NginxTlsPayload) -> bool {
    valid_pem(&payload.certificate_pem, "CERTIFICATE")
        && valid_pem(&payload.private_key_pem, "PRIVATE KEY")
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct NginxRouteBackend {
    name: String,
    port: u16,
    weight: u8,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct ContainerVolumePayload {
    name: String,
    mount_path: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct ContainerDeployPayload {
    image: String,
    #[serde(default)]
    host_port: Option<u16>,
    #[serde(default)]
    container_port: Option<u16>,
    #[serde(default)]
    environment: Option<Vec<String>>,
    #[serde(default)]
    volumes: Option<Vec<ContainerVolumePayload>>,
    #[serde(default)]
    memory_mb: Option<u32>,
    #[serde(default)]
    stateful: Option<bool>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct K3sUpgradePayload {
    role: String,
    version: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct KubeEdgeJoinPayload {
    version: String,
    cloud_core_endpoint: String,
    token: String,
}

#[derive(Clone, Debug)]
pub struct AgentConfig {
    pub endpoints: Vec<String>,
    pub relay_endpoints: Vec<String>,
    pub tls_domain: String,
    pub agent_id: String,
    pub ca_pem: PathBuf,
    pub cert_pem: PathBuf,
    pub key_pem: PathBuf,
    pub helper_socket: PathBuf,
    pub state_dir: PathBuf,
    pub heartbeat_interval: Duration,
    pub reconnect_min: Duration,
    pub reconnect_max: Duration,
    pub netbird_metrics_url: Option<Url>,
}

impl AgentConfig {
    pub fn from_env() -> Result<Self> {
        let endpoints = match std::env::var("NANPANEL_AGENT_ENDPOINTS") {
            Ok(value) => parse_optional_endpoints(&value)?,
            Err(std::env::VarError::NotPresent) => match std::env::var("NANPANEL_AGENT_ENDPOINT") {
                Ok(value) => parse_optional_endpoints(&value)?,
                Err(std::env::VarError::NotPresent) => Vec::new(),
                Err(error) => return Err(error.into()),
            },
            Err(error) => return Err(error.into()),
        };
        let relay_endpoints = std::env::var("NANPANEL_AGENT_RELAY_ENDPOINTS")
            .ok()
            .map(|value| parse_optional_endpoints(&value))
            .transpose()?
            .unwrap_or_default();
        let relay_fallback_endpoints = verify_relay_fallback_manifest(
            &std::env::var("NANPANEL_AGENT_RELAY_FALLBACK_ENDPOINTS").unwrap_or_default(),
            &std::env::var("NANPANEL_AGENT_RELAY_FALLBACK_SIGNATURE").unwrap_or_default(),
            &std::env::var("NANPANEL_AGENT_RELAY_FALLBACK_PUBLIC_KEY").unwrap_or_default(),
        )?;
        let relay_endpoints = relay_endpoints
            .into_iter()
            .chain(relay_fallback_endpoints)
            .collect::<Vec<_>>();
        if endpoints.len() + relay_endpoints.len() > 8 {
            anyhow::bail!("combined Agent and Relay endpoint list must contain at most eight URLs")
        }
        if endpoints.is_empty() && relay_endpoints.is_empty() {
            anyhow::bail!("at least one Agent or Relay endpoint is required")
        }
        let tls_domain =
            std::env::var("NANPANEL_AGENT_TLS_DOMAIN").unwrap_or_else(|_| "localhost".to_owned());
        let agent_id = required("NANPANEL_AGENT_ID")?;
        let netbird_metrics_url = match std::env::var("NANPANEL_AGENT_NETBIRD_METRICS_URL") {
            Ok(value) => Some(parse_netbird_metrics_url(&value)?),
            Err(std::env::VarError::NotPresent) => None,
            Err(error) => return Err(error.into()),
        };
        Ok(Self {
            endpoints,
            relay_endpoints,
            tls_domain,
            agent_id,
            ca_pem: required("NANPANEL_AGENT_CA")?.into(),
            cert_pem: required("NANPANEL_AGENT_CERT")?.into(),
            key_pem: required("NANPANEL_AGENT_KEY")?.into(),
            helper_socket: std::env::var("NANPANEL_AGENT_HELPER_SOCKET")
                .unwrap_or_else(|_| "/run/nanpanel/helper.sock".to_owned())
                .into(),
            state_dir: std::env::var("NANPANEL_AGENT_STATE_DIR")
                .unwrap_or_else(|_| "/var/lib/nanpanel-agent".to_owned())
                .into(),
            heartbeat_interval: duration_env("NANPANEL_AGENT_HEARTBEAT_SECS", 5),
            reconnect_min: duration_env("NANPANEL_AGENT_RECONNECT_MIN_MS", 500),
            reconnect_max: duration_env("NANPANEL_AGENT_RECONNECT_MAX_MS", 30_000),
            netbird_metrics_url,
        })
    }
}

fn parse_netbird_metrics_url(value: &str) -> Result<Url> {
    let url = Url::parse(value).context("parse NetBird local metrics URL")?;
    if url.scheme() != "http"
        || !url.username().is_empty()
        || url.password().is_some()
        || url.path() != "/metrics"
        || url.query().is_some()
        || url.fragment().is_some()
        || url
            .host_str()
            .and_then(|host| host.parse::<std::net::IpAddr>().ok())
            .is_none_or(|address| !address.is_loopback())
        || url.port_or_known_default().is_none_or(|port| port == 0)
    {
        anyhow::bail!("NetBird local metrics URL must be http://<loopback>:<port>/metrics")
    }
    Ok(url)
}

fn verify_relay_fallback_manifest(
    endpoint_value: &str,
    signature_value: &str,
    public_key_value: &str,
) -> Result<Vec<String>> {
    if endpoint_value.trim().is_empty() {
        if signature_value.is_empty() && public_key_value.is_empty() {
            return Ok(Vec::new());
        }
        anyhow::bail!("Relay fallback signature must not exist without endpoints")
    }
    let endpoints = parse_endpoints(endpoint_value)?;
    if endpoints.len() < 2 {
        anyhow::bail!("signed Relay fallback list must contain at least two origins")
    }
    let signature = hex::decode(signature_value).context("decode Relay fallback signature")?;
    let public_key = hex::decode(public_key_value).context("decode Relay fallback public key")?;
    if signature.len() != 64 || public_key.len() != 32 {
        anyhow::bail!("Relay fallback signature or public key has an invalid length")
    }
    let payload = format!("nanpanel-relay-fallback-v1\n{}", endpoints.join("\n"));
    ring::signature::UnparsedPublicKey::new(&ring::signature::ED25519, public_key)
        .verify(payload.as_bytes(), &signature)
        .map_err(|_| anyhow!("Relay fallback signature verification failed"))?;
    Ok(endpoints)
}

fn parse_endpoints(value: &str) -> Result<Vec<String>> {
    let raw_endpoints = value
        .split(',')
        .map(str::trim)
        .filter(|endpoint| !endpoint.is_empty())
        .map(str::to_owned)
        .collect::<Vec<_>>();
    if raw_endpoints.is_empty() || raw_endpoints.len() > 8 {
        anyhow::bail!("Agent endpoint list must contain between one and eight URLs")
    }
    let mut unique = HashSet::new();
    let mut endpoints = Vec::with_capacity(raw_endpoints.len());
    for endpoint in raw_endpoints {
        let parsed = reqwest::Url::parse(&endpoint)
            .with_context(|| format!("invalid Agent endpoint URL: {endpoint}"))?;
        if parsed.scheme() != "https"
            || parsed.host_str().is_none()
            || !parsed.username().is_empty()
            || parsed.password().is_some()
            || parsed.path() != "/"
            || parsed.query().is_some()
            || parsed.fragment().is_some()
        {
            anyhow::bail!(
                "Agent endpoints must be unique HTTPS origins without credentials or paths"
            )
        }
        let origin = parsed.origin().ascii_serialization();
        if !unique.insert(origin.clone()) {
            anyhow::bail!(
                "Agent endpoints must be unique HTTPS origins without credentials or paths"
            )
        }
        endpoints.push(origin);
    }
    Ok(endpoints)
}

fn parse_optional_endpoints(value: &str) -> Result<Vec<String>> {
    if value.trim().is_empty() {
        return Ok(Vec::new());
    }
    parse_endpoints(value)
}

fn required(name: &str) -> Result<String> {
    std::env::var(name).with_context(|| format!("missing required environment variable {name}"))
}

fn duration_env(name: &str, default: u64) -> Duration {
    std::env::var(name)
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .map(Duration::from_millis)
        .filter(|_| name.ends_with("_MS"))
        .or_else(|| {
            std::env::var(name)
                .ok()
                .and_then(|v| v.parse::<u64>().ok())
                .filter(|_| name.ends_with("_SECS"))
                .map(Duration::from_secs)
        })
        .unwrap_or_else(|| {
            if name.ends_with("_MS") {
                Duration::from_millis(default)
            } else {
                Duration::from_secs(default)
            }
        })
}

pub async fn run(config: AgentConfig) -> Result<()> {
    let endpoints = parse_optional_endpoints(&config.endpoints.join(","))?;
    let relay_endpoints = parse_optional_endpoints(&config.relay_endpoints.join(","))?;
    let mut candidates = endpoints
        .iter()
        .map(|endpoint| (endpoint.as_str(), false))
        .chain(
            relay_endpoints
                .iter()
                .map(|endpoint| (endpoint.as_str(), true)),
        )
        .collect::<Vec<_>>();
    if candidates.len() > 8 {
        anyhow::bail!("combined Agent and Relay endpoint list must contain at most eight URLs")
    }
    if candidates.is_empty() {
        anyhow::bail!("at least one Agent or Relay endpoint is required")
    }
    let mut unique = HashSet::new();
    candidates.retain(|(endpoint, _)| unique.insert(endpoint.to_ascii_lowercase()));
    let ca = tokio::fs::read(&config.ca_pem)
        .await
        .context("read agent CA")?;
    let cert = tokio::fs::read(&config.cert_pem)
        .await
        .context("read agent certificate")?;
    let key = tokio::fs::read(&config.key_pem)
        .await
        .context("read agent private key")?;
    let tls = ClientTlsConfig::new()
        .domain_name(config.tls_domain.clone())
        .ca_certificate(Certificate::from_pem(ca))
        .identity(Identity::from_pem(cert, key));
    let journal = Arc::new(Mutex::new(OperationJournal::open(&config.state_dir).await?));
    let direct = candidates
        .iter()
        .filter(|(_, use_relay)| !use_relay)
        .map(|(endpoint, _)| ((*endpoint).to_owned(), false))
        .collect::<Vec<_>>();
    let relay_endpoints = candidates
        .iter()
        .filter(|(_, use_relay)| *use_relay)
        .map(|(endpoint, _)| (*endpoint).to_owned())
        .collect::<Vec<_>>();
    let relay_groups = relay_endpoint_groups(&relay_endpoints);
    let sequence = Arc::new(Mutex::new(0_u64));
    if !direct.is_empty() {
        let worker_config = config.clone();
        let worker_tls = tls.clone();
        let worker_journal = Arc::clone(&journal);
        let worker_sequence = Arc::clone(&sequence);
        tokio::spawn(async move {
            run_candidate_group(
                worker_config,
                direct,
                worker_tls,
                worker_journal,
                worker_sequence,
            )
            .await;
        });
    }

    for relay_group in relay_groups {
        let relay_group = relay_group
            .into_iter()
            .map(|endpoint| (endpoint, true))
            .collect::<Vec<_>>();
        let worker_config = config.clone();
        let worker_tls = tls.clone();
        let worker_journal = Arc::clone(&journal);
        let worker_sequence = Arc::clone(&sequence);
        tokio::spawn(async move {
            run_candidate_group(
                worker_config,
                relay_group,
                worker_tls,
                worker_journal,
                worker_sequence,
            )
            .await;
        });
    }

    std::future::pending::<()>().await;
    Ok(())
}

fn relay_endpoint_groups(endpoints: &[String]) -> Vec<Vec<String>> {
    let slots = endpoints.len().min(2);
    let mut groups = vec![Vec::new(); slots];
    if slots == 0 {
        return groups;
    }
    for (index, endpoint) in endpoints.iter().enumerate() {
        groups[index % slots].push(endpoint.clone());
    }
    groups
}

async fn run_candidate_group(
    config: AgentConfig,
    candidates: Vec<(String, bool)>,
    tls: ClientTlsConfig,
    journal: Arc<Mutex<OperationJournal>>,
    sequence: Arc<Mutex<u64>>,
) {
    if candidates.is_empty() {
        return;
    }
    let mut backoff = config.reconnect_min;
    let mut endpoint_index = 0;
    loop {
        let mut connected = false;
        let start_index = endpoint_index;
        for offset in 0..candidates.len() {
            let current_index = (start_index + offset) % candidates.len();
            let (endpoint, use_relay) = &candidates[current_index];
            match connect_once(
                &config,
                endpoint,
                *use_relay,
                tls.clone(),
                Arc::clone(&journal),
                Arc::clone(&sequence),
            )
            .await
            {
                Ok(()) => {
                    connected = true;
                    warn!(agent_id = %config.agent_id, endpoint, use_relay, "control stream closed")
                }
                Err(error) => {
                    warn!(agent_id = %config.agent_id, endpoint, error = %error, "gateway connection failed")
                }
            }
        }
        endpoint_index = (start_index + 1) % candidates.len();
        if connected {
            backoff = config.reconnect_min;
        }
        time::sleep(backoff).await;
        if !connected {
            backoff = backoff.saturating_mul(2).min(config.reconnect_max);
        }
    }
}

async fn connect_once(
    config: &AgentConfig,
    endpoint_url: &str,
    use_relay: bool,
    tls: ClientTlsConfig,
    journal: Arc<Mutex<OperationJournal>>,
    sequence: Arc<Mutex<u64>>,
) -> Result<()> {
    let channel = connect_gateway(endpoint_url, &config.tls_domain, tls)
        .await
        .context("connect configured control endpoint")?;
    let (sender, receiver) = mpsc::channel(16);
    let mut stream = if use_relay {
        let mut client = ControlRelayClient::new(channel);
        ControlStream::Relay(
            client
                .agent_session(ReceiverStream::new(receiver))
                .await
                .context("open Control Relay stream")?
                .into_inner(),
        )
    } else {
        let mut client = AgentGatewayClient::new(channel);
        ControlStream::Gateway(
            client
                .session(ReceiverStream::new(receiver))
                .await
                .context("open gateway stream")?
                .into_inner(),
        )
    };
    info!(agent_id = %config.agent_id, endpoint = endpoint_url, use_relay, "mTLS control stream established");

    let helper_available = Arc::new(AtomicBool::new(
        helper::status(&config.helper_socket).await?,
    ));
    let initial_helper_capabilities = if helper_available.load(Ordering::Relaxed) {
        helper::capabilities(&config.helper_socket)
            .await
            .unwrap_or_default()
    } else {
        Vec::new()
    };
    let initial_netbird_peer_paths = if initial_helper_capabilities
        .iter()
        .any(|capability| capability == "network.overlay.peer_paths.read")
    {
        collect_netbird_peer_paths(&config.helper_socket).await
    } else {
        None
    };
    info!(
        agent_id = %config.agent_id,
        helper_status = if helper_available.load(Ordering::Relaxed) { "AVAILABLE" } else { "UNAVAILABLE" },
        "Agent Helper probe completed"
    );
    let agent_id = config.agent_id.clone();
    let interval = config.heartbeat_interval;
    let host = host_snapshot();
    let heartbeat_sequence = Arc::clone(&sequence);
    let heartbeat_helper_available = Arc::clone(&helper_available);
    let heartbeat_helper_socket = config.helper_socket.clone();
    let heartbeat_initial_peer_paths = initial_netbird_peer_paths;
    let netbird_metrics_url = config.netbird_metrics_url.clone();
    let netbird_metrics_client = if netbird_metrics_url.is_some() {
        reqwest::Client::builder()
            .timeout(Duration::from_secs(2))
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .ok()
    } else {
        None
    };
    let heartbeat_sender = sender.clone();
    let heartbeat_task = tokio::spawn(async move {
        let mut ticker = time::interval(interval);
        ticker.set_missed_tick_behavior(time::MissedTickBehavior::Skip);
        let mut netbird_peer_paths = heartbeat_initial_peer_paths;
        let mut peer_paths_refresh_at = time::Instant::now();
        let mut systemd_services = collect_systemd_services().await;
        let mut systemd_observed_at = systemd_services.as_ref().and_then(|_| epoch_ms().ok());
        let mut systemd_refresh_at = time::Instant::now();
        let mut kubernetes_runtime = collect_kubernetes_runtime(systemd_services.as_deref()).await;
        let mut kubernetes_runtime_refresh_at = time::Instant::now();
        let mut network_inventory = collect_network_inventory().await;
        let mut network_refresh_at = time::Instant::now();
        let mut netbird_overlay = collect_netbird_overlay(
            netbird_metrics_client.as_ref(),
            netbird_metrics_url.as_ref(),
        )
        .await;
        let mut overlay_refresh_at = time::Instant::now();
        loop {
            ticker.tick().await;
            if systemd_refresh_at.elapsed() >= Duration::from_secs(30) {
                systemd_services = collect_systemd_services().await;
                systemd_observed_at = if systemd_services.is_some() {
                    epoch_ms().ok()
                } else {
                    None
                };
                systemd_refresh_at = time::Instant::now();
            }
            if kubernetes_runtime_refresh_at.elapsed() >= Duration::from_secs(30) {
                kubernetes_runtime = collect_kubernetes_runtime(systemd_services.as_deref()).await;
                kubernetes_runtime_refresh_at = time::Instant::now();
            }
            if network_refresh_at.elapsed() >= Duration::from_secs(30) {
                network_inventory = collect_network_inventory().await;
                network_refresh_at = time::Instant::now();
            }
            if overlay_refresh_at.elapsed() >= Duration::from_secs(30) {
                netbird_overlay = collect_netbird_overlay(
                    netbird_metrics_client.as_ref(),
                    netbird_metrics_url.as_ref(),
                )
                .await;
                overlay_refresh_at = time::Instant::now();
            }
            let helper_is_available = helper::status(&heartbeat_helper_socket)
                .await
                .unwrap_or(false);
            heartbeat_helper_available.store(helper_is_available, Ordering::Relaxed);
            let helper_capabilities = if helper_is_available {
                helper::capabilities(&heartbeat_helper_socket)
                    .await
                    .unwrap_or_default()
            } else {
                Vec::new()
            };
            if peer_paths_refresh_at.elapsed() >= Duration::from_secs(30) {
                if helper_capabilities
                    .iter()
                    .any(|capability| capability == "network.overlay.peer_paths.read")
                {
                    netbird_peer_paths = collect_netbird_peer_paths(&heartbeat_helper_socket).await;
                } else {
                    netbird_peer_paths = None;
                }
                peer_paths_refresh_at = time::Instant::now();
            }
            let mut capabilities = vec!["host.inventory".to_owned(), "agent.heartbeat".to_owned()];
            if kubernetes_runtime.is_some() {
                capabilities.push("kubernetes.runtime.detect".to_owned());
            }
            if helper_is_available {
                capabilities.push("agent.self_update".to_owned());
                for capability in helper_capabilities {
                    if !capabilities.contains(&capability)
                        && matches!(
                            capability.as_str(),
                            "systemd.restart"
                                | "systemd.journal.read"
                                | "package.install"
                                | "file.write.protected"
                                | "file.list"
                                | "file.read"
                                | "file.write"
                                | "file.delete"
                                | "container.list"
                                | "container.logs"
                                | "container.start"
                                | "container.stop"
                                | "container.deploy"
                                | "container.remove"
                                | "network.firewall.expose"
                                | "network.topology.reconcile"
                                | "network.topology.capture"
                                | "k3s.preflight"
                                | "k3s.install"
                                | "k3s.upgrade"
                                | "kubeedge.edgecore.join"
                                | "nginx.route.set"
                                | "nginx.route.remove"
                                | "nginx.tls.install"
                                | "cron.list"
                                | "cron.write"
                                | "cron.remove"
                                | "metrics.read"
                                | "backup.list"
                                | "backup.create"
                                | "backup.delete"
                        )
                    {
                        capabilities.push(capability);
                    }
                }
            }
            if systemd_services.is_some() {
                capabilities.push("systemd.read".to_owned());
            }
            if network_inventory.is_some() {
                capabilities.push("network.inventory".to_owned());
            }
            if netbird_overlay.is_some() {
                capabilities.push("network.overlay.observe".to_owned());
            }
            if netbird_peer_paths.is_some() {
                capabilities.push("network.overlay.peer_paths.observe".to_owned());
            }
            let mut sequence_guard = heartbeat_sequence.lock().await;
            *sequence_guard = sequence_guard.saturating_add(1);
            let sequence = *sequence_guard;
            let sent_at_epoch_ms = match epoch_ms() {
                Ok(value) => value,
                Err(error) => {
                    warn!(%error, "unable to read system time for heartbeat");
                    continue;
                }
            };
            let message = AgentMessage {
                agent_id: agent_id.clone(),
                sequence,
                sent_at_epoch_ms,
                payload: Some(Payload::Heartbeat(Box::new(Heartbeat {
                    version: env!("NANPANEL_AGENT_VERSION").to_owned(),
                    hostname: host.hostname.clone(),
                    os: host.os.clone(),
                    arch: host.arch.clone(),
                    capabilities,
                    helper_status: if helper_is_available {
                        "AVAILABLE".to_owned()
                    } else {
                        "UNAVAILABLE".to_owned()
                    },
                    systemd_services: systemd_services
                        .as_ref()
                        .map(|services| {
                            services
                                .iter()
                                .map(|service| SystemdService {
                                    unit_name: service.unit_name.clone(),
                                    display_name: service.display_name.clone(),
                                    active_state: service.active_state.clone(),
                                    sub_state: service.sub_state.clone(),
                                })
                                .collect()
                        })
                        .unwrap_or_default(),
                    systemd_observed_at_epoch_ms: systemd_observed_at.unwrap_or_default(),
                    network_interfaces: network_inventory
                        .as_ref()
                        .map(|inventory| inventory.interfaces.clone())
                        .unwrap_or_default(),
                    network_routes: network_inventory
                        .as_ref()
                        .map(|inventory| inventory.routes.clone())
                        .unwrap_or_default(),
                    network_observed_at_epoch_ms: network_inventory
                        .as_ref()
                        .map(|inventory| inventory.observed_at_epoch_ms)
                        .unwrap_or_default(),
                    netbird_overlay: netbird_overlay.as_ref().map(|overlay| {
                        NetbirdOverlaySummary {
                            peer_count: overlay.peer_count,
                            connected_peer_count: overlay.connected_peer_count,
                            p2p_peer_count: overlay.p2p_peer_count,
                            relayed_peer_count: overlay.relayed_peer_count,
                        }
                    }),
                    netbird_overlay_observed_at_epoch_ms: netbird_overlay
                        .as_ref()
                        .map(|overlay| overlay.observed_at_epoch_ms)
                        .unwrap_or_default(),
                    netbird_peer_paths: netbird_peer_paths
                        .as_ref()
                        .map(|snapshot| {
                            snapshot
                                .peers
                                .iter()
                                .map(|peer| NetbirdPeerPathMessage {
                                    peer_name: peer.peer_name.clone(),
                                    status: peer.status.clone(),
                                    connection_type: peer.connection_type.clone(),
                                })
                                .collect()
                        })
                        .unwrap_or_default(),
                    netbird_peer_paths_observed_at_epoch_ms: netbird_peer_paths
                        .as_ref()
                        .map(|snapshot| snapshot.observed_at_epoch_ms)
                        .unwrap_or_default(),
                    kubernetes_runtime: kubernetes_runtime.as_ref().map(|runtime| {
                        KubernetesRuntimeDiscovery {
                            detected: runtime.detected,
                            kubelet_running: runtime.kubelet_running,
                            api_server_local: runtime.api_server_local,
                            runtime_kind: runtime.runtime_kind.clone(),
                            observed_at_epoch_ms: runtime.observed_at_epoch_ms,
                        }
                    }),
                }))),
            };
            if heartbeat_sender.send(message).await.is_err() {
                break;
            }
        }
    });

    let recovered = journal.lock().await.recovery_results();
    for (operation_id, idempotency_key, status, detail) in recovered {
        if let Err(error) = send_operation_result(
            &sender,
            &config.agent_id,
            &sequence,
            operation_id,
            idempotency_key,
            &status,
            &detail,
        )
        .await
        {
            warn!(%error, "unable to replay durable Agent operation result");
            break;
        }
    }

    while let Some(control) = stream.next_control().await.context("read control stream")? {
        if control.operation_result_ack {
            if control.operation_id.is_empty()
                || control.operation_id.len() > 128
                || control.idempotency_key.is_empty()
                || control.idempotency_key.len() > 128
            {
                return Err(anyhow!(
                    "gateway sent an invalid operation result acknowledgement"
                ));
            }
            journal
                .lock()
                .await
                .acknowledge(&control.operation_id, &control.idempotency_key)
                .await?;
            continue;
        }
        if control.operation_id.is_empty()
            || control.idempotency_key.is_empty()
            || control.capability.is_empty()
            || control.deadline_epoch_ms <= 0
        {
            return Err(anyhow!("gateway sent an invalid control envelope"));
        }
        let valid_control =
            valid_control(&control.capability, &control.target, &control.payload_json);
        if !valid_control {
            send_operation_result(
                &sender,
                &config.agent_id,
                &sequence,
                control.operation_id,
                control.idempotency_key,
                "FAILED",
                "DENIED: Agent rejected an invalid control capability or target",
            )
            .await?;
            continue;
        }
        if !helper_available.load(Ordering::Relaxed) {
            send_operation_result(
                &sender,
                &config.agent_id,
                &sequence,
                control.operation_id,
                control.idempotency_key,
                "FAILED",
                "DENIED: root Helper is unavailable before operation dispatch",
            )
            .await?;
            continue;
        }

        let operation_id = control.operation_id;
        let idempotency_key = control.idempotency_key;
        let deadline = control.deadline_epoch_ms;
        let agent_id = config.agent_id.clone();
        let helper_socket = config.helper_socket.clone();
        let capability = control.capability;
        let target = control.target;
        let payload_json = (!control.payload_json.is_empty()).then_some(control.payload_json);
        let operation_sender = sender.clone();
        let operation_sequence = Arc::clone(&sequence);
        if capability != "agent.update" {
            let claim = journal
                .lock()
                .await
                .claim(&operation_id, &idempotency_key)
                .await;
            match claim {
                Ok(Claim::Replay { status, detail }) => {
                    send_operation_result(
                        &operation_sender,
                        &agent_id,
                        &operation_sequence,
                        operation_id,
                        idempotency_key,
                        &status,
                        &detail,
                    )
                    .await?;
                    continue;
                }
                Ok(Claim::InProgress) => {
                    send_operation_result(
                        &operation_sender,
                        &agent_id,
                        &operation_sequence,
                        operation_id,
                        idempotency_key,
                        "FAILED",
                        "UNCERTAIN: an earlier delivery was durably claimed; refusing to execute it again",
                    )
                    .await?;
                    continue;
                }
                Ok(Claim::New) => {}
                Err(error) => {
                    warn!(operation_id = %operation_id, %error, "Agent operation journal refused control message");
                    continue;
                }
            }
            let operation_journal = Arc::clone(&journal);
            tokio::spawn(async move {
                let result = helper::update_with_payload(
                    &helper_socket,
                    &operation_id,
                    &idempotency_key,
                    &capability,
                    &target,
                    deadline,
                    payload_json.as_deref(),
                )
                .await;
                let (status, detail) = match result {
                    Ok(detail) => ("SUCCEEDED", detail),
                    Err(error) => {
                        let message = error.to_string();
                        let detail = if message.contains("Helper rejected operation: DENIED:") {
                            format!(
                                "DENIED: {}",
                                message.trim_start_matches("Helper rejected operation: ")
                            )
                        } else if message.contains("Helper rejected operation: FAILED:") {
                            format!(
                                "FAILED: {}",
                                message.trim_start_matches("Helper rejected operation: ")
                            )
                        } else {
                            format!("UNCERTAIN: {message}")
                        };
                        ("FAILED", detail)
                    }
                };
                let is_capture = capability == "network.topology.capture";
                let (status, detail) = if is_capture && detail.len() > MAX_CAPTURE_RESULT_BYTES {
                    (
                        "FAILED",
                        "FAILED: packet capture result exceeded the reporting limit".to_owned(),
                    )
                } else if is_capture {
                    (status, detail)
                } else {
                    (status, bounded_detail(&detail))
                };
                let mut journal = operation_journal.lock().await;
                let persisted = if is_capture {
                    journal
                        .complete_capture(&operation_id, &idempotency_key, status, &detail)
                        .await
                } else {
                    journal
                        .complete(&operation_id, &idempotency_key, status, &detail)
                        .await
                };
                if let Err(error) = persisted {
                    warn!(operation_id = %operation_id, %error, "unable to persist Agent operation result");
                }
                if let Err(error) = send_operation_result(
                    &operation_sender,
                    &agent_id,
                    &operation_sequence,
                    operation_id.clone(),
                    idempotency_key.clone(),
                    status,
                    &detail,
                )
                .await
                {
                    warn!(operation_id = %operation_id, %error, "unable to report Helper operation result");
                }
            });
            continue;
        }
        tokio::spawn(async move {
            let update_result = helper::update(
                &helper_socket,
                &operation_id,
                &idempotency_key,
                "agent.update",
                "",
                deadline,
            )
            .await;
            match update_result {
                Ok(detail) => {
                    if let Err(error) = send_operation_result(
                        &operation_sender,
                        &agent_id,
                        &operation_sequence,
                        operation_id.clone(),
                        idempotency_key.clone(),
                        "SUCCEEDED",
                        &detail,
                    )
                    .await
                    {
                        warn!(operation_id = %operation_id, %error, "unable to report Agent update result");
                        return;
                    }
                    time::sleep(Duration::from_millis(750)).await;
                    let restart_id = format!("{operation_id}:restart");
                    let restart_key = format!("{idempotency_key}:restart");
                    if let Err(error) = helper::update(
                        &helper_socket,
                        &restart_id,
                        &restart_key,
                        "systemd.restart",
                        "nanpanel-agent.service",
                        deadline,
                    )
                    .await
                    {
                        warn!(operation_id = %operation_id, %error, "Agent binary installed but service restart failed");
                    }
                }
                Err(error) => {
                    let detail = error.to_string();
                    if let Err(report_error) = send_operation_result(
                        &operation_sender,
                        &agent_id,
                        &operation_sequence,
                        operation_id,
                        idempotency_key,
                        "FAILED",
                        &detail,
                    )
                    .await
                    {
                        warn!(%report_error, "unable to report failed Agent update");
                    }
                }
            }
        });
    }
    heartbeat_task.abort();
    Ok(())
}

async fn collect_kubernetes_runtime(
    services: Option<&[SystemdService]>,
) -> Option<KubernetesRuntimeObservation> {
    let services = services.unwrap_or_default();
    let active_units: Vec<_> = services
        .iter()
        .filter(|service| service.active_state == "ACTIVE")
        .map(|service| service.unit_name.as_str())
        .collect();
    let (kubelet_process, k3s_process) = tokio::task::spawn_blocking(kubernetes_processes_running)
        .await
        .ok()
        .unwrap_or_default();
    let (detected, kubelet_running, runtime_kind) =
        runtime_from_evidence(kubelet_process, k3s_process, &active_units);
    let api_server_local = tokio::time::timeout(
        Duration::from_millis(500),
        tokio::net::TcpStream::connect(("127.0.0.1", 6443)),
    )
    .await
    .is_ok_and(|result| result.is_ok());
    Some(KubernetesRuntimeObservation {
        detected,
        kubelet_running,
        api_server_local,
        runtime_kind: runtime_kind.to_owned(),
        observed_at_epoch_ms: epoch_ms().ok()?,
    })
}

fn kubernetes_processes_running() -> (bool, bool) {
    let Ok(entries) = std::fs::read_dir("/proc") else {
        return (false, false);
    };
    let mut kubelet = false;
    let mut k3s = false;
    for entry in entries.flatten() {
        if !entry
            .file_name()
            .to_string_lossy()
            .bytes()
            .all(|byte| byte.is_ascii_digit())
        {
            continue;
        }
        let Ok(name) = std::fs::read_to_string(entry.path().join("comm")) else {
            continue;
        };
        match name.trim() {
            "kubelet" => kubelet = true,
            "k3s-server" | "k3s-agent" | "k3s" => k3s = true,
            _ => {}
        }
    }
    (kubelet, k3s)
}

fn runtime_from_evidence(
    kubelet_process: bool,
    k3s_process: bool,
    active_units: &[&str],
) -> (bool, bool, &'static str) {
    let k3s = k3s_process
        || active_units
            .iter()
            .any(|unit| matches!(*unit, "k3s.service" | "k3s-agent.service"));
    let kubelet = kubelet_process
        || active_units.iter().any(|unit| {
            matches!(
                *unit,
                "kubelet.service"
                    | "rke2-server.service"
                    | "rke2-agent.service"
                    | "microk8s.daemon-kubelet.service"
            )
        });
    let detected = k3s || kubelet;
    (
        detected,
        detected,
        if k3s {
            "K3S"
        } else if kubelet {
            "KUBERNETES"
        } else {
            "NONE"
        },
    )
}

enum ControlStream {
    Gateway(Streaming<ControlMessage>),
    Relay(Streaming<RelayMessage>),
}

impl ControlStream {
    async fn next_control(&mut self) -> Result<Option<ControlMessage>> {
        match self {
            Self::Gateway(stream) => stream.message().await.context("read Agent Gateway stream"),
            Self::Relay(stream) => match stream
                .message()
                .await
                .context("read Control Relay stream")?
            {
                Some(message) => match message.payload {
                    Some(RelayPayload::Control(control)) => Ok(Some(control)),
                    Some(RelayPayload::HelloAck(_)) => {
                        anyhow::bail!("Control Relay sent a Core-only hello acknowledgement")
                    }
                    Some(RelayPayload::AgentMessage(_)) | None => {
                        anyhow::bail!("Control Relay sent an invalid Agent stream message")
                    }
                },
                None => Ok(None),
            },
        }
    }
}

async fn connect_gateway(
    endpoint_url: &str,
    tls_domain: &str,
    tls: ClientTlsConfig,
) -> Result<Channel> {
    let endpoint = Endpoint::from_shared(endpoint_url.to_owned())?
        .connect_timeout(Duration::from_secs(10))
        .tcp_keepalive(Some(Duration::from_secs(30)))
        .keep_alive_while_idle(true)
        .tls_config(tls.clone())?;
    match endpoint.connect().await {
        Ok(channel) => return Ok(channel),
        Err(error) => {
            warn!(endpoint = endpoint_url, %error, "system DNS gateway connection failed; attempting bounded direct DNS recovery")
        }
    }

    let parsed = reqwest::Url::parse(endpoint_url)?;
    let host = parsed
        .host_str()
        .context("Agent endpoint URL has no host")?;
    let port = parsed
        .port_or_known_default()
        .context("Agent endpoint URL has no port")?;
    if !is_direct_dns_fallback_allowed(host, tls_domain) {
        anyhow::bail!("Agent endpoint cannot use public DNS fallback")
    }
    let addresses = direct_dns::resolve_cloudflare(host)
        .await
        .context("resolve Agent endpoint through bounded Cloudflare DNS fallback")?;
    let mut last_error = None;
    for address in addresses.into_iter().take(16) {
        let socket = std::net::SocketAddr::new(address, port);
        let endpoint = Endpoint::from_shared(endpoint_url.to_owned())?
            .connect_timeout(Duration::from_secs(10))
            .tcp_keepalive(Some(Duration::from_secs(30)))
            .keep_alive_while_idle(true)
            .tls_config(tls.clone())?;
        let connector = tower::service_fn(move |_uri| async move {
            tokio::net::TcpStream::connect(socket)
                .await
                .map(TokioIo::new)
        });
        match endpoint.connect_with_connector(connector).await {
            Ok(channel) => {
                info!(endpoint = endpoint_url, %socket, "mTLS gateway connected using a direct DNS answer");
                return Ok(channel);
            }
            Err(error) => last_error = Some(error),
        }
    }
    match last_error {
        Some(error) => Err(error).context("connect to all bounded direct DNS addresses"),
        None => anyhow::bail!("direct DNS returned no usable gateway addresses"),
    }
}

fn is_direct_dns_fallback_allowed(host: &str, tls_domain: &str) -> bool {
    let host = host.trim_end_matches('.');
    let tls_domain = tls_domain.trim_end_matches('.');
    host.eq_ignore_ascii_case(tls_domain)
        && host.contains('.')
        && host.parse::<std::net::IpAddr>().is_err()
        && ![
            "localhost",
            "local",
            "internal",
            "test",
            "invalid",
            "example",
            "onion",
            "home.arpa",
        ]
        .iter()
        .any(|suffix| {
            host.eq_ignore_ascii_case(suffix)
                || host.to_ascii_lowercase().ends_with(&format!(".{suffix}"))
        })
}

async fn collect_systemd_services() -> Option<Vec<SystemdService>> {
    let output = time::timeout(
        Duration::from_secs(3),
        tokio::process::Command::new("systemctl")
            .args([
                "list-units",
                "--type=service",
                "--all",
                "--no-pager",
                "--output=json",
            ])
            .kill_on_drop(true)
            .output(),
    )
    .await
    .ok()?
    .ok()?;
    if !output.status.success() {
        return None;
    }
    parse_systemd_services(&output.stdout)
}

async fn collect_network_inventory() -> Option<NetworkInventory> {
    let addresses = run_ip_json(&["-j", "address", "show"]).await?;
    let ipv4_routes = run_ip_json(&["-j", "route", "show", "table", "all"]).await?;
    let ipv6_routes = run_ip_json(&["-j", "-6", "route", "show", "table", "all"]).await?;
    let interfaces = parse_network_interfaces(&addresses)?;
    let mut routes = parse_network_routes(&ipv4_routes)?;
    routes.extend(parse_network_routes(&ipv6_routes)?);
    Some(NetworkInventory {
        interfaces,
        routes: routes.into_iter().take(2048).collect(),
        observed_at_epoch_ms: epoch_ms().ok()?,
    })
}

async fn collect_netbird_overlay(
    client: Option<&reqwest::Client>,
    url: Option<&Url>,
) -> Option<NetbirdOverlayObservation> {
    const MAX_METRICS_BYTES: usize = 256 * 1024;
    let client = client?;
    let url = url?;
    let mut response = client.get(url.clone()).send().await.ok()?;
    if !response.status().is_success()
        || response
            .content_length()
            .is_some_and(|length| length > MAX_METRICS_BYTES as u64)
    {
        return None;
    }
    let mut body = Vec::new();
    while let Some(chunk) = response.chunk().await.ok()? {
        if body.len().saturating_add(chunk.len()) > MAX_METRICS_BYTES {
            return None;
        }
        body.extend_from_slice(&chunk);
    }
    let metrics = std::str::from_utf8(&body).ok()?;
    let (peer_count, p2p_peer_count, relayed_peer_count) = parse_netbird_overlay_metrics(metrics)?;
    let connected_peer_count = p2p_peer_count.saturating_add(relayed_peer_count);
    Some(NetbirdOverlayObservation {
        peer_count,
        connected_peer_count,
        p2p_peer_count,
        relayed_peer_count,
        observed_at_epoch_ms: epoch_ms().ok()?,
    })
}

async fn collect_netbird_peer_paths(
    helper_socket: &std::path::Path,
) -> Option<NetbirdPeerPathObservation> {
    const MAX_PEERS: usize = 256;
    let peers = helper::netbird_peer_paths(helper_socket).await.ok()?;
    if peers.len() > MAX_PEERS {
        return None;
    }
    let mut names = HashSet::with_capacity(peers.len());
    if peers.iter().any(|peer| {
        peer.peer_name.is_empty()
            || peer.peer_name.len() > 253
            || peer.peer_name.parse::<std::net::IpAddr>().is_ok()
            || !peer
                .peer_name
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
            || !names.insert(peer.peer_name.to_ascii_lowercase())
            || !matches!(
                peer.status.as_str(),
                "CONNECTED" | "DISCONNECTED" | "CONNECTING" | "UNKNOWN"
            )
            || !matches!(peer.connection_type.as_str(), "P2P" | "RELAY" | "UNKNOWN")
    }) {
        return None;
    }
    Some(NetbirdPeerPathObservation {
        peers,
        observed_at_epoch_ms: epoch_ms().ok()?,
    })
}

fn parse_netbird_overlay_metrics(metrics: &str) -> Option<(u32, u32, u32)> {
    const MAX_PEERS: u32 = 100_000;
    let mut peer_count = None;
    let mut p2p_peer_count = None;
    let mut relayed_peer_count = None;
    for line in metrics.lines().map(str::trim) {
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let mut fields = line.split_whitespace();
        let metric = fields.next()?;
        let Some(raw_value) = fields.next() else {
            continue;
        };
        if fields.next().is_some() {
            continue;
        }
        let kind = if metric == "netbird_peers" {
            Some("total")
        } else if metric.starts_with("netbird_peers_connected{")
            && metric.ends_with('}')
            && metric.contains("connection_type=\"p2p\"")
        {
            Some("p2p")
        } else if metric.starts_with("netbird_peers_connected{")
            && metric.ends_with('}')
            && metric.contains("connection_type=\"relay\"")
        {
            Some("relay")
        } else if metric.starts_with("netbird_peers_connected{") && metric.ends_with('}') {
            return None;
        } else {
            None
        };
        let Some(kind) = kind else {
            continue;
        };
        let value = raw_value.parse::<u32>().ok()?;
        if value > MAX_PEERS {
            return None;
        }
        let slot = match kind {
            "total" => &mut peer_count,
            "p2p" => &mut p2p_peer_count,
            "relay" => &mut relayed_peer_count,
            _ => return None,
        };
        if slot.replace(value).is_some() {
            return None;
        }
    }
    let peer_count = peer_count?;
    let p2p_peer_count = p2p_peer_count?;
    let relayed_peer_count = relayed_peer_count?;
    if p2p_peer_count.saturating_add(relayed_peer_count) > peer_count {
        return None;
    }
    Some((peer_count, p2p_peer_count, relayed_peer_count))
}

async fn run_ip_json(arguments: &[&str]) -> Option<Vec<u8>> {
    let output = time::timeout(
        Duration::from_secs(3),
        tokio::process::Command::new("ip")
            .args(arguments)
            .kill_on_drop(true)
            .output(),
    )
    .await
    .ok()?
    .ok()?;
    if !output.status.success() || output.stdout.len() > 256 * 1024 {
        return None;
    }
    Some(output.stdout)
}

fn parse_network_interfaces(output: &[u8]) -> Option<Vec<NetworkInterface>> {
    let parsed: Vec<IpInterfaceInfo> = serde_json::from_slice(output).ok()?;
    if parsed.len() > 128 {
        return None;
    }
    Some(
        parsed
            .into_iter()
            .filter_map(|interface| {
                let name = interface.ifname?;
                if !valid_network_interface(&name) {
                    return None;
                }
                let addresses = interface
                    .addr_info
                    .unwrap_or_default()
                    .into_iter()
                    .filter_map(|address| {
                        let family = address.family?;
                        let local = address.local?;
                        let parsed_address = local.parse::<std::net::IpAddr>().ok()?;
                        let prefix_length = address.prefixlen?;
                        let family_matches = matches!(
                            (family.as_str(), parsed_address),
                            ("inet", std::net::IpAddr::V4(_)) | ("inet6", std::net::IpAddr::V6(_))
                        );
                        if !family_matches
                            || prefix_length > if family == "inet" { 32 } else { 128 }
                            || !valid_short_text(address.scope.as_deref().unwrap_or(""), 32)
                        {
                            return None;
                        }
                        Some(NetworkAddress {
                            family,
                            address: local,
                            prefix_length,
                            scope: address.scope.unwrap_or_default(),
                        })
                    })
                    .take(32)
                    .collect();
                let mac_address = interface
                    .address
                    .filter(|value| value.len() <= 32 && valid_short_text(value, 32))
                    .unwrap_or_default();
                let state = interface
                    .operstate
                    .filter(|value| valid_short_text(value, 32))
                    .unwrap_or_default();
                Some(NetworkInterface {
                    name,
                    mac_address,
                    mtu: interface.mtu.unwrap_or_default(),
                    state,
                    addresses,
                })
            })
            .collect(),
    )
}

fn parse_network_routes(output: &[u8]) -> Option<Vec<NetworkRoute>> {
    let parsed: Vec<IpRouteInfo> = serde_json::from_slice(output).ok()?;
    if parsed.len() > 2048 {
        return None;
    }
    Some(
        parsed
            .into_iter()
            .filter_map(|route| {
                let destination = route.dst.unwrap_or_else(|| "default".to_owned());
                if !valid_route_destination(&destination) {
                    return None;
                }
                let gateway = route.gateway.unwrap_or_default();
                if !gateway.is_empty() && gateway.parse::<std::net::IpAddr>().is_err() {
                    return None;
                }
                let interface_name = route.dev.unwrap_or_default();
                if !interface_name.is_empty() && !valid_network_interface(&interface_name) {
                    return None;
                }
                let table = route
                    .table
                    .and_then(|value| match value {
                        serde_json::Value::String(value) => Some(value),
                        serde_json::Value::Number(value) => Some(value.to_string()),
                        _ => None,
                    })
                    .filter(|value| valid_short_text(value, 64))
                    .unwrap_or_default();
                let protocol = route
                    .protocol
                    .filter(|value| valid_short_text(value, 32))
                    .unwrap_or_default();
                let scope = route
                    .scope
                    .filter(|value| valid_short_text(value, 32))
                    .unwrap_or_default();
                let route_type = route
                    .route_type
                    .filter(|value| valid_short_text(value, 32))
                    .unwrap_or_default();
                Some(NetworkRoute {
                    destination,
                    gateway,
                    interface_name,
                    protocol,
                    scope,
                    route_type,
                    table,
                    metric: route.metric.unwrap_or_default(),
                })
            })
            .collect(),
    )
}

fn valid_short_text(value: &str, max_bytes: usize) -> bool {
    value.len() <= max_bytes && !value.chars().any(char::is_control)
}

fn valid_route_destination(value: &str) -> bool {
    if value == "default" {
        return true;
    }
    let (address, prefix) = value.split_once('/').unwrap_or((value, ""));
    address.parse::<std::net::IpAddr>().is_ok()
        && (prefix.is_empty()
            || prefix
                .parse::<u8>()
                .is_ok_and(|bits| bits <= if address.contains(':') { 128 } else { 32 }))
}

fn parse_systemd_services(output: &[u8]) -> Option<Vec<SystemdService>> {
    let units: serde_json::Value = serde_json::from_slice(output).ok()?;
    let units = units.as_array()?;
    let services = units
        .iter()
        .filter_map(|unit| {
            let unit_name = unit.get("unit")?.as_str()?;
            let display_name = unit.get("description")?.as_str()?;
            let active = unit.get("active")?.as_str()?;
            let sub_state = unit.get("sub")?.as_str()?;
            if !valid_service_unit(unit_name)
                || display_name.is_empty()
                || display_name.len() > 255
                || sub_state.len() > 64
            {
                return None;
            }
            Some(SystemdService {
                unit_name: unit_name.to_owned(),
                display_name: display_name.to_owned(),
                active_state: match active {
                    "active" => "ACTIVE",
                    "inactive" => "INACTIVE",
                    "activating" => "ACTIVATING",
                    "deactivating" => "DEACTIVATING",
                    "reloading" => "RELOADING",
                    "failed" => "FAILED",
                    _ => "UNKNOWN",
                }
                .to_owned(),
                sub_state: sub_state.to_owned(),
            })
        })
        .take(512)
        .collect();
    Some(services)
}

fn valid_service_unit(unit_name: &str) -> bool {
    unit_name.len() <= 255
        && unit_name
            .as_bytes()
            .first()
            .is_some_and(|byte| byte.is_ascii_alphanumeric() || *byte == b'_')
        && unit_name.ends_with(".service")
        && unit_name
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"_.@:-".contains(&byte))
}

fn valid_control(capability: &str, target: &str, payload_json: &str) -> bool {
    if payload_json.len() > 48 * 1024 {
        return false;
    }
    let has_payload = !payload_json.is_empty();
    match capability {
        "agent.update" | "container.list" => target.is_empty() && !has_payload,
        "systemd.restart" | "systemd.journal.read" => valid_service_unit(target) && !has_payload,
        "package.install" => {
            target.is_empty()
                && has_payload
                && serde_json::from_str::<serde_json::Value>(payload_json)
                    .is_ok_and(|value| value.is_object())
        }
        "file.write.protected" => {
            valid_file_target(target)
                && has_payload
                && serde_json::from_str::<serde_json::Value>(payload_json)
                    .is_ok_and(|value| value.is_object())
        }
        "container.logs" | "container.start" | "container.stop" | "container.remove" => {
            valid_container_target(target) && !has_payload
        }
        "container.deploy" => {
            valid_container_target(target)
                && (!has_payload
                    || serde_json::from_str::<ContainerDeployPayload>(payload_json).is_ok_and(
                        |payload| {
                            // `stateful` only relaxes the runtime sandbox and needs no
                            // first-pass bound; the privileged-helper validates it strictly.
                            let _ = payload.stateful;
                            valid_container_image(&payload.image)
                                && payload.host_port.is_some() == payload.container_port.is_some()
                                && payload.host_port.is_none_or(|port| port > 0)
                                && payload.container_port.is_none_or(|port| port > 0)
                                && payload
                                    .environment
                                    .as_ref()
                                    .is_none_or(|variables| variables.len() <= 32)
                                && payload.volumes.as_ref().is_none_or(|volumes| {
                                    volumes.len() <= 16
                                        && volumes.iter().all(|volume| {
                                            !volume.name.is_empty()
                                                && volume.mount_path.starts_with('/')
                                        })
                                })
                                && payload
                                    .memory_mb
                                    .is_none_or(|memory| (64..=2048).contains(&memory))
                        },
                    ))
        }
        "network.firewall.expose" => {
            valid_container_target(target)
                && has_payload
                && serde_json::from_str::<NetworkFirewallPayload>(payload_json).is_ok_and(
                    |payload| {
                        valid_network_interface(&payload.interface_name)
                            && matches!(payload.protocol.as_str(), "tcp" | "udp")
                            && payload.port > 0
                            && (30..=300).contains(&payload.rollback_seconds)
                    },
                )
        }
        "network.topology.reconcile" => valid_topology_dispatch(target, payload_json),
        "network.topology.capture" => valid_topology_capture_dispatch(target, payload_json),
        "k3s.preflight" => {
            target.is_empty()
                && has_payload
                && serde_json::from_str::<serde_json::Value>(payload_json).is_ok_and(|value| {
                    value.as_object().is_some_and(|payload| {
                        payload.len() == 1
                            && matches!(
                                payload.get("role").and_then(serde_json::Value::as_str),
                                Some("server" | "agent")
                            )
                    })
                })
        }
        "k3s.install" => {
            target.is_empty()
                && has_payload
                && serde_json::from_str::<serde_json::Value>(payload_json).is_ok_and(|value| {
                    let Some(payload) = value.as_object() else {
                        return false;
                    };
                    let role = payload.get("role").and_then(serde_json::Value::as_str);
                    let version = payload.get("version").and_then(serde_json::Value::as_str);
                    let token = payload.get("token").and_then(serde_json::Value::as_str);
                    let server_url = payload.get("serverUrl");
                    let valid_version = version.is_some_and(valid_k3s_version);
                    let valid_token = token.is_some_and(valid_k3s_token);
                    match (role, server_url) {
                        (Some("server"), Some(value)) => {
                            payload.len() == 4
                                && value.is_null()
                                && valid_version
                                && valid_token
                                && !token.is_some_and(|token| token.starts_with("K10"))
                        }
                        (Some("agent"), Some(value)) => {
                            payload.len() == 4
                                && value.as_str().is_some_and(valid_k3s_server_url)
                                && valid_version
                                && valid_token
                        }
                        _ => false,
                    }
                })
        }
        "k3s.upgrade" => {
            target.is_empty()
                && has_payload
                && serde_json::from_str::<K3sUpgradePayload>(payload_json).is_ok_and(|payload| {
                    matches!(payload.role.as_str(), "server" | "agent")
                        && valid_k3s_version(&payload.version)
                })
        }
        "kubeedge.edgecore.join" => {
            target.is_empty()
                && has_payload
                && serde_json::from_str::<KubeEdgeJoinPayload>(payload_json).is_ok_and(|payload| {
                    valid_kubeedge_version(&payload.version)
                        && valid_cloud_core_endpoint(&payload.cloud_core_endpoint)
                        && valid_kubeedge_token(&payload.token)
                })
        }
        "nginx.route.set" => {
            valid_nginx_route_target(target)
                && has_payload
                && serde_json::from_str::<NginxRoutePayload>(payload_json)
                    .is_ok_and(|payload| valid_nginx_route_payload(&payload))
        }
        "nginx.route.remove" => valid_nginx_route_target(target) && !has_payload,
        "cron.list" => target.is_empty() && !has_payload,
        "metrics.read" => target.is_empty() && !has_payload,
        "backup.list" => target.is_empty() && !has_payload,
        "backup.create" => {
            target.is_empty()
                && has_payload
                && serde_json::from_str::<BackupPayload>(payload_json)
                    .is_ok_and(|payload| valid_backup_payload(&payload))
        }
        "backup.delete" => valid_backup_file(target) && !has_payload,
        "cron.write" => {
            valid_cron_target(target)
                && has_payload
                && serde_json::from_str::<CronJobPayload>(payload_json)
                    .is_ok_and(|payload| valid_cron_job_payload(&payload))
        }
        "cron.remove" => valid_cron_target(target) && !has_payload,
        "nginx.tls.install" => {
            valid_tls_name(target)
                && has_payload
                && serde_json::from_str::<NginxTlsPayload>(payload_json)
                    .is_ok_and(|payload| valid_nginx_tls_payload(&payload))
        }
        _ => false,
    }
}

fn valid_kubeedge_version(version: &str) -> bool {
    let Some(version) = version.strip_prefix('v') else {
        return false;
    };
    let components = version.split('.').collect::<Vec<_>>();
    components.len() == 3
        && components
            .iter()
            .all(|part| !part.is_empty() && part.bytes().all(|byte| byte.is_ascii_digit()))
}

fn valid_cloud_core_endpoint(endpoint: &str) -> bool {
    endpoint.len() <= 260
        && endpoint.rsplit_once(':').is_some_and(|(host, port)| {
            let host = if host.starts_with('[') && host.ends_with(']') {
                &host[1..host.len() - 1]
            } else {
                host
            };
            let valid_host = host.parse::<std::net::IpAddr>().is_ok()
                || (host.len() <= 253
                    && host.split('.').all(|label| {
                        !label.is_empty()
                            && label.len() <= 63
                            && label.as_bytes()[0].is_ascii_alphanumeric()
                            && label.as_bytes()[label.len() - 1].is_ascii_alphanumeric()
                            && label
                                .bytes()
                                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
                    }));
            valid_host
                && port.parse::<u16>().is_ok_and(|value| value > 0)
                && (host.contains(':') == endpoint.starts_with('['))
        })
}

fn valid_kubeedge_token(token: &str) -> bool {
    (32..=4096).contains(&token.len())
        && token
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
        && token.split('.').count() == 3
        && token.split('.').all(|part| !part.is_empty())
}

fn valid_k3s_version(version: &str) -> bool {
    let Some(release) = version.strip_prefix('v') else {
        return false;
    };
    let Some((kubernetes, revision)) = release.split_once("+k3s") else {
        return false;
    };
    let components = kubernetes.split('.').collect::<Vec<_>>();
    components.len() == 3
        && components
            .iter()
            .all(|component| !component.is_empty() && component.bytes().all(|b| b.is_ascii_digit()))
        && !revision.is_empty()
        && revision.bytes().all(|b| b.is_ascii_digit())
}

fn valid_k3s_server_url(server_url: &str) -> bool {
    let Ok(url) = reqwest::Url::parse(server_url) else {
        return false;
    };
    if url.scheme() != "https"
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || !matches!(url.path(), "" | "/")
        || url.query().is_some()
        || url.fragment().is_some()
        || url.port().is_some_and(|port| port != 6443)
        || server_url.len() > 512
    {
        return false;
    }
    match url.host_str() {
        Some(host) if host.eq_ignore_ascii_case("localhost") || host.ends_with(".localhost") => {
            false
        }
        Some(host) => host
            .parse::<std::net::IpAddr>()
            .map_or(true, |address| match address {
                std::net::IpAddr::V4(address) => {
                    !address.is_unspecified()
                        && !address.is_loopback()
                        && !address.is_link_local()
                        && !address.is_multicast()
                }
                std::net::IpAddr::V6(address) => {
                    !address.is_unspecified()
                        && !address.is_loopback()
                        && !address.is_multicast()
                        && !address.is_unicast_link_local()
                }
            }),
        None => false,
    }
}

fn valid_k3s_token(token: &str) -> bool {
    (16..=512).contains(&token.len())
        && token
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.' | b':'))
}

fn valid_backup_file(target: &str) -> bool {
    let Some(stem) = target.strip_suffix(".tar.gz") else {
        return false;
    };
    let Some((name, stamp)) = stem.rsplit_once('-') else {
        return false;
    };
    !name.is_empty()
        && name.len() <= 32
        && name.as_bytes()[0].is_ascii_lowercase()
        && name
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
        && stamp.len() == 16
        && stamp.as_bytes()[8] == b'T'
        && stamp.as_bytes()[15] == b'Z'
        && stamp
            .bytes()
            .enumerate()
            .all(|(index, byte)| index == 8 || index == 15 || byte.is_ascii_digit())
}

fn valid_backup_payload(payload: &BackupPayload) -> bool {
    payload.source.starts_with('/')
        && payload.source.len() <= 1024
        && !payload.source.contains("..")
        && payload.name.len() >= 2
        && payload.name.len() <= 32
        && payload.name.as_bytes()[0].is_ascii_lowercase()
        && payload
            .name
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
        && !payload.name.ends_with('-')
}

fn valid_cron_target(target: &str) -> bool {
    target.starts_with("nanpanel-")
        && target.len() <= 42
        && target.as_bytes().get(9).is_some_and(u8::is_ascii_lowercase)
        && target
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
        && !target.ends_with('-')
}

/// Mirrors the helper's rules: five fields of `[0-9*,-/]`, a single-line command, root only.
fn valid_cron_job_payload(payload: &CronJobPayload) -> bool {
    let schedule = payload.schedule.trim();
    let fields = schedule.split_whitespace().collect::<Vec<_>>();
    let command = payload.command.trim();
    fields.len() == 5
        && !schedule.is_empty()
        && schedule.len() <= 128
        && fields.iter().all(|field| {
            !field.is_empty()
                && field.len() <= 32
                && field
                    .bytes()
                    .all(|byte| byte.is_ascii_digit() || matches!(byte, b'*' | b'/' | b',' | b'-'))
        })
        && !command.is_empty()
        && command.len() <= 900
        && !command
            .chars()
            .any(|value| value == '\n' || value == '\r' || value == '\0')
        && payload.user.as_deref().unwrap_or("root") == "root"
}

fn valid_nginx_route_target(target: &str) -> bool {
    target.starts_with("nanpanel-")
        && target.len() <= 62
        && target
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
        && target.as_bytes().get(9).is_some_and(u8::is_ascii_lowercase)
}

fn valid_nginx_route_payload(payload: &NginxRoutePayload) -> bool {
    let valid_address = payload
        .listen_address
        .parse::<std::net::IpAddr>()
        .is_ok_and(|address| !address.is_unspecified() && !address.is_multicast());
    let valid_host = payload.server_name.len() <= 253
        && payload.server_name.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && label.as_bytes()[0].is_ascii_alphanumeric()
                && label.as_bytes()[label.len() - 1].is_ascii_alphanumeric()
                && label
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
        });
    let valid_path = payload.health_path.starts_with('/')
        && payload.health_path.len() <= 256
        && !payload.health_path.contains("..")
        && payload
            .health_path
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"/._~-".contains(&byte));
    let valid_tls = payload
        .tls
        .as_ref()
        .is_none_or(|tls| valid_tls_name(&tls.certificate) && valid_tls_name(&tls.private_key));
    if !valid_address
        || payload.listen_port == 0
        || !valid_host
        || !valid_path
        || !valid_tls
        || payload.backends.is_empty()
        || payload.backends.len() > 2
    {
        return false;
    }
    let mut names = HashSet::new();
    let mut ports = HashSet::new();
    let mut weight_sum = 0_u16;
    for backend in &payload.backends {
        if backend.name.is_empty()
            || backend.name.len() > 32
            || !backend
                .name
                .bytes()
                .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
            || backend.port == 0
            || backend.weight > 100
            || !names.insert(backend.name.as_str())
            || !ports.insert(backend.port)
        {
            return false;
        }
        weight_sum += u16::from(backend.weight);
    }
    weight_sum == 100 && payload.backends.iter().any(|backend| backend.weight > 0)
}

fn valid_file_target(target: &str) -> bool {
    !target.is_empty()
        && target.len() <= 128
        && target
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"_.-".contains(&byte))
}

fn valid_container_target(target: &str) -> bool {
    !target.is_empty()
        && target.len() <= 63
        && target.as_bytes()[0].is_ascii_alphanumeric()
        && target
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"_.-".contains(&byte))
}

fn valid_network_interface(interface: &str) -> bool {
    !interface.is_empty()
        && interface.len() <= 15
        && interface
            .as_bytes()
            .first()
            .is_some_and(|byte| byte.is_ascii_alphanumeric() || *byte == b'_')
        && interface
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"_.-".contains(&byte))
}

fn valid_container_image(image: &str) -> bool {
    !image.is_empty()
        && image.len() <= 255
        && image
            .as_bytes()
            .first()
            .is_some_and(u8::is_ascii_alphanumeric)
        && image
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"._:/@-".contains(&byte))
}

async fn send_operation_result(
    sender: &mpsc::Sender<AgentMessage>,
    agent_id: &str,
    sequence: &Mutex<u64>,
    operation_id: String,
    idempotency_key: String,
    status: &str,
    detail: &str,
) -> Result<()> {
    let mut sequence_guard = sequence.lock().await;
    *sequence_guard = sequence_guard.saturating_add(1);
    let message = AgentMessage {
        agent_id: agent_id.to_owned(),
        sequence: *sequence_guard,
        sent_at_epoch_ms: epoch_ms()?,
        payload: Some(Payload::OperationResult(Box::new(OperationResult {
            operation_id,
            idempotency_key,
            status: status.to_owned(),
            detail: detail.to_owned(),
        }))),
    };
    sender.send(message).await.context("send operation result")
}

fn bounded_detail(value: &str) -> String {
    if value.len() <= 2000 {
        return value.to_owned();
    }
    let mut end = 0;
    for (index, character) in value.char_indices() {
        if index + character.len_utf8() > 1997 {
            break;
        }
        end = index + character.len_utf8();
    }
    format!("{}...", &value[..end])
}

fn epoch_ms() -> Result<i64> {
    let value = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .context("system clock is before Unix epoch")?
        .as_millis();
    i64::try_from(value).context("system clock value exceeds i64")
}

fn host_snapshot() -> HostSnapshot {
    let hostname = std::fs::read_to_string("/etc/hostname")
        .ok()
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
        .or_else(|| {
            std::env::var("HOSTNAME")
                .ok()
                .filter(|value| !value.is_empty())
        })
        .unwrap_or_else(|| "unknown-host".to_owned());
    let os_release = std::fs::read_to_string("/etc/os-release").unwrap_or_default();
    let os = os_release
        .lines()
        .find_map(|line| line.strip_prefix("PRETTY_NAME="))
        .map(|value| value.trim_matches('"').trim_matches('\'').to_owned())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| std::env::consts::OS.to_owned());
    HostSnapshot {
        hostname,
        os,
        arch: std::env::consts::ARCH.to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ring::signature::KeyPair;

    #[test]
    fn detects_active_kubernetes_and_distinguishes_k3s() {
        assert_eq!(
            runtime_from_evidence(false, false, &["kubelet.service"]),
            (true, true, "KUBERNETES")
        );
        assert_eq!(
            runtime_from_evidence(false, false, &["k3s.service"]),
            (true, true, "K3S")
        );
        assert_eq!(
            runtime_from_evidence(false, false, &[]),
            (false, false, "NONE")
        );
        assert_eq!(
            runtime_from_evidence(true, false, &[]),
            (true, true, "KUBERNETES")
        );
        assert_eq!(runtime_from_evidence(false, true, &[]), (true, true, "K3S"));
    }

    #[test]
    fn parses_bounded_network_interface_addresses_and_routes() -> Result<()> {
        let interfaces = parse_network_interfaces(
            br#"[{"ifname":"eth0","address":"02:00:00:00:00:01","mtu":1500,"operstate":"UP","addr_info":[{"family":"inet","local":"192.0.2.10","prefixlen":24,"scope":"global"},{"family":"inet6","local":"2001:db8::10","prefixlen":64,"scope":"global"}]}]"#,
        )
        .context("parse interface fixture")?;
        assert_eq!(interfaces.len(), 1);
        assert_eq!(interfaces[0].name, "eth0");
        assert_eq!(interfaces[0].addresses.len(), 2);
        assert_eq!(interfaces[0].addresses[0].address, "192.0.2.10");
        assert_eq!(interfaces[0].addresses[1].family, "inet6");

        let routes = parse_network_routes(
            br#"[{"dst":"default","gateway":"192.0.2.1","dev":"eth0","protocol":"static","scope":"global","type":"unicast","table":254,"metric":100}]"#,
        )
        .context("parse route fixture")?;
        assert_eq!(routes.len(), 1);
        assert_eq!(routes[0].destination, "default");
        assert_eq!(routes[0].gateway, "192.0.2.1");
        assert_eq!(routes[0].table, "254");
        Ok(())
    }

    #[test]
    fn drops_invalid_network_entries_and_rejects_malformed_snapshots() -> Result<()> {
        let interfaces = parse_network_interfaces(
            br#"[{"ifname":"bad/name","addr_info":[]},{"ifname":"eth0","addr_info":[{"family":"inet","local":"not-an-ip","prefixlen":24,"scope":"global"}]}]"#,
        )
        .context("parse mixed validity fixture")?;
        assert_eq!(interfaces.len(), 1);
        assert!(interfaces[0].addresses.is_empty());
        assert!(parse_network_routes(b"not-json").is_none());
        assert!(!valid_route_destination("../../etc/passwd"));
        Ok(())
    }

    #[test]
    fn accepts_only_loopback_netbird_metrics_urls() -> Result<()> {
        assert_eq!(
            parse_netbird_metrics_url("http://127.0.0.1:9191/metrics")?.as_str(),
            "http://127.0.0.1:9191/metrics"
        );
        assert!(parse_netbird_metrics_url("https://127.0.0.1:9191/metrics").is_err());
        assert!(parse_netbird_metrics_url("http://localhost:9191/metrics").is_err());
        assert!(parse_netbird_metrics_url("http://192.0.2.1:9191/metrics").is_err());
        assert!(parse_netbird_metrics_url("http://127.0.0.1:9191/other").is_err());
        assert!(parse_netbird_metrics_url("http://127.0.0.1:9191/metrics?debug=1").is_err());
        assert!(parse_netbird_metrics_url("http://user@127.0.0.1:9191/metrics").is_err());
        Ok(())
    }

    #[test]
    fn parses_bounded_netbird_overlay_connection_metrics() {
        let metrics = "# HELP netbird_peers_known Known peers\nnetbird_peers 8\nnetbird_peers_connected{connection_type=\"p2p\"} 3\nnetbird_peers_connected{connection_type=\"relay\"} 2\nnetbird_peer_latency_seconds{peer=\"host.example\"} 0.012\n";
        assert_eq!(parse_netbird_overlay_metrics(metrics), Some((8, 3, 2)));
    }

    #[test]
    fn rejects_inconsistent_or_ambiguous_netbird_overlay_metrics() {
        let missing_path = "netbird_peers 8\nnetbird_peers_connected{connection_type=\"p2p\"} 3\n";
        let duplicate = "netbird_peers 8\nnetbird_peers 8\nnetbird_peers_connected{connection_type=\"p2p\"} 3\nnetbird_peers_connected{connection_type=\"relay\"} 2\n";
        let unknown = "netbird_peers 8\nnetbird_peers_connected{connection_type=\"other\"} 1\nnetbird_peers_connected{connection_type=\"p2p\"} 3\nnetbird_peers_connected{connection_type=\"relay\"} 2\n";
        let inconsistent = "netbird_peers 4\nnetbird_peers_connected{connection_type=\"p2p\"} 3\nnetbird_peers_connected{connection_type=\"relay\"} 2\n";
        let fractional = "netbird_peers 4.5\nnetbird_peers_connected{connection_type=\"p2p\"} 1\nnetbird_peers_connected{connection_type=\"relay\"} 1\n";
        let over_limit = "netbird_peers 100001\nnetbird_peers_connected{connection_type=\"p2p\"} 1\nnetbird_peers_connected{connection_type=\"relay\"} 1\n";
        for metrics in [
            missing_path,
            duplicate,
            unknown,
            inconsistent,
            fractional,
            over_limit,
        ] {
            assert_eq!(parse_netbird_overlay_metrics(metrics), None);
        }
    }

    #[test]
    fn normalizes_and_bounds_gateway_endpoint_origins() -> Result<()> {
        assert_eq!(
            parse_endpoints(" HTTPS://One.Example:443/ , https://two.example:9443 ")?,
            vec!["https://one.example", "https://two.example:9443"]
        );
        assert!(parse_endpoints("").is_err());
        assert!(parse_endpoints("http://gateway.example").is_err());
        assert!(parse_endpoints("https://user@gateway.example").is_err());
        assert!(parse_endpoints("https://gateway.example/control").is_err());
        assert!(parse_endpoints("https://gateway.example?debug=true").is_err());
        assert!(parse_endpoints("https://gateway.example,https://GATEWAY.example:443/").is_err());
        assert!(parse_endpoints(
            "https://a.example,https://b.example,https://c.example,https://d.example,https://e.example,https://f.example,https://g.example,https://h.example,https://i.example"
        )
        .is_err());
        assert!(parse_optional_endpoints(" \t ")?.is_empty());
        assert_eq!(
            parse_optional_endpoints("https://relay-a.example,https://relay-b.example")?,
            vec!["https://relay-a.example", "https://relay-b.example"]
        );
        Ok(())
    }

    #[test]
    fn verifies_signed_relay_fallback_manifest_before_accepting_endpoints() -> Result<()> {
        let rng = ring::rand::SystemRandom::new();
        let pkcs8 = ring::signature::Ed25519KeyPair::generate_pkcs8(&rng)
            .map_err(|_| anyhow!("generate Ed25519 test key"))?;
        let key = ring::signature::Ed25519KeyPair::from_pkcs8(pkcs8.as_ref())
            .map_err(|_| anyhow!("load Ed25519 test key"))?;
        let endpoints = vec![
            "https://fallback-a.example".to_owned(),
            "https://fallback-b.example:9443".to_owned(),
        ];
        let payload = format!("nanpanel-relay-fallback-v1\n{}", endpoints.join("\n"));
        let signature = hex::encode(key.sign(payload.as_bytes()).as_ref());
        let public_key = hex::encode(key.public_key().as_ref());

        assert_eq!(
            verify_relay_fallback_manifest(&endpoints.join(","), &signature, &public_key)?,
            endpoints
        );
        assert!(
            verify_relay_fallback_manifest(
                "https://attacker.example,https://fallback-b.example:9443",
                &signature,
                &public_key
            )
            .is_err()
        );
        assert!(verify_relay_fallback_manifest(&endpoints.join(","), "", &public_key).is_err());
        assert!(verify_relay_fallback_manifest("", "", "")?.is_empty());
        Ok(())
    }

    #[test]
    fn relay_endpoints_are_split_into_two_simultaneous_failover_groups() {
        let endpoints = vec![
            "https://relay-a.example".to_owned(),
            "https://relay-b.example".to_owned(),
            "https://relay-c.example".to_owned(),
            "https://relay-d.example".to_owned(),
        ];
        assert_eq!(
            relay_endpoint_groups(&endpoints),
            vec![
                vec![
                    "https://relay-a.example".to_owned(),
                    "https://relay-c.example".to_owned()
                ],
                vec![
                    "https://relay-b.example".to_owned(),
                    "https://relay-d.example".to_owned()
                ]
            ]
        );
    }

    #[test]
    fn direct_dns_recovery_is_limited_to_the_configured_public_tls_name() {
        assert!(is_direct_dns_fallback_allowed(
            "api.example.com",
            "API.EXAMPLE.COM."
        ));
        assert!(!is_direct_dns_fallback_allowed(
            "relay-a.example.com",
            "api.example.com"
        ));
        assert!(!is_direct_dns_fallback_allowed("localhost", "localhost"));
        assert!(!is_direct_dns_fallback_allowed(
            "agent.internal",
            "agent.internal"
        ));
        assert!(!is_direct_dns_fallback_allowed("192.0.2.1", "192.0.2.1"));
    }

    #[test]
    fn parses_and_bounds_systemd_service_inventory() -> Result<()> {
        let input = br#"[
          {"unit":"ssh.service","description":"OpenBSD Secure Shell server","active":"active","sub":"running"},
          {"unit":"bad.service;touch /tmp/pwned","description":"bad","active":"active","sub":"running"},
          {"unit":"failed.service","description":"Failed fixture","active":"failed","sub":"failed"}
        ]"#;
        let services = parse_systemd_services(input)
            .ok_or_else(|| anyhow!("valid systemd JSON should parse"))?;
        assert_eq!(services.len(), 2);
        assert_eq!(services[0].unit_name, "ssh.service");
        assert_eq!(services[0].active_state, "ACTIVE");
        assert_eq!(services[0].sub_state, "running");
        assert_eq!(services[1].active_state, "FAILED");
        assert_eq!(services[1].unit_name, "failed.service");
        Ok(())
    }

    #[test]
    fn rejects_invalid_systemd_json() {
        assert!(parse_systemd_services(b"not-json").is_none());
        assert!(parse_systemd_services(br#"{"unit":"ssh.service"}"#).is_none());
        assert!(!valid_service_unit("--help.service"));
    }

    #[test]
    fn validates_temporary_firewall_control_payload_and_target_shape() {
        assert!(valid_control("k3s.preflight", "", r#"{"role":"server"}"#));
        assert!(valid_control("k3s.preflight", "", r#"{"role":"agent"}"#));
        assert!(!valid_control(
            "k3s.preflight",
            "",
            r#"{"role":"server","command":"id"}"#
        ));
        assert!(!valid_control(
            "k3s.preflight",
            "k3s.service",
            r#"{"role":"server"}"#
        ));
        assert!(!valid_control("k3s.preflight", "", r#"{"role":"worker"}"#));
        assert!(valid_control(
            "network.firewall.expose",
            "ssh-test",
            r#"{"interfaceName":"eth0","protocol":"tcp","port":2222,"rollbackSeconds":90}"#,
        ));
        assert!(!valid_control(
            "network.firewall.expose",
            "ssh-test",
            r#"{"interfaceName":"eth0","protocol":"tcp","port":2222,"rollbackSeconds":301}"#,
        ));
        assert!(!valid_control(
            "network.firewall.expose",
            "ssh-test;id",
            r#"{"interfaceName":"eth0","protocol":"tcp","port":2222,"rollbackSeconds":90}"#,
        ));
        assert!(!valid_control(
            "network.firewall.expose",
            "ssh-test",
            r#"{"interfaceName":"-eth0","protocol":"tcp","port":2222,"rollbackSeconds":90}"#,
        ));
        assert!(!valid_control(
            "network.firewall.expose",
            "ssh-test",
            r#"{"interfaceName":"eth0","protocol":"tcp","port":2222,"rollbackSeconds":90,"command":"id"}"#,
        ));
        assert!(valid_control(
            "nginx.route.set",
            "nanpanel-route0123456789abcdef0123456789abcdef",
            r#"{"listenAddress":"127.0.0.1","listenPort":18080,"serverName":"app.example.test","healthPath":"/health","backends":[{"name":"stable","port":20080,"weight":100},{"name":"canary","port":20081,"weight":0}]}"#,
        ));
        assert!(!valid_control(
            "nginx.route.set",
            "customer-site",
            r#"{"listenAddress":"127.0.0.1","listenPort":18080,"serverName":"app.example.test","healthPath":"/health","backends":[{"name":"stable","port":20080,"weight":100}]}"#,
        ));
        assert!(!valid_control(
            "nginx.route.set",
            "nanpanel-route0123456789abcdef0123456789abcdef",
            r#"{"listenAddress":"0.0.0.0","listenPort":18080,"serverName":"app.example.test","healthPath":"/health","backends":[{"name":"stable","port":20080,"weight":100}]}"#,
        ));
        assert!(valid_control(
            "nginx.route.remove",
            "nanpanel-route0123456789abcdef0123456789abcdef",
            "",
        ));
        assert!(!valid_control("nginx.route.remove", "customer-site", "",));
        assert!(!valid_control(
            "nginx.route.remove",
            "nanpanel-route0123456789abcdef0123456789abcdef",
            r#"{"serverName":"app.example.test"}"#,
        ));
        assert!(valid_control(
            "container.deploy",
            "web-app",
            r#"{"image":"nginx:1.27","hostPort":8080,"containerPort":80}"#,
        ));
        assert!(!valid_control(
            "container.deploy",
            "web-app",
            r#"{"image":"-v","hostPort":8080,"containerPort":80}"#,
        ));
        assert!(!valid_control(
            "container.deploy",
            "web-app",
            r#"{"image":"./nginx","hostPort":8080,"containerPort":80}"#,
        ));
        assert!(valid_control(
            "k3s.install",
            "",
            r#"{"role":"agent","version":"v1.36.5+k3s1","serverUrl":"https://k3s.example.net:6443/","token":"K10aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa::server:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}"#,
        ));
        assert!(!valid_control(
            "k3s.install",
            "",
            r#"{"role":"agent","version":"v1.36.5+k3s1","serverUrl":"https://127.0.0.1:6443","token":"K10aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa::server:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}"#,
        ));
        assert!(!valid_control(
            "k3s.install",
            "",
            r#"{"role":"server","version":"v1.36.5+k3s1","serverUrl":null,"token":"aaaaaaaaaaaaaaaa\nK3S_URL=evil"}"#,
        ));
        assert!(valid_control(
            "k3s.upgrade",
            "",
            r#"{"role":"agent","version":"v1.37.1+k3s1"}"#,
        ));
        assert!(!valid_control(
            "k3s.upgrade",
            "",
            r#"{"role":"agent","version":"v1.37.1+k3s1","shell":"id"}"#,
        ));
        assert!(valid_control(
            "kubeedge.edgecore.join",
            "",
            r#"{"version":"v1.23.1","cloudCoreEndpoint":"cloud.example.test:10000","token":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.cccccccccccccccccccccccccccccccccccccccc"}"#,
        ));
        assert!(!valid_control(
            "kubeedge.edgecore.join",
            "",
            r#"{"version":"v1.23.1","cloudCoreEndpoint":"cloud.example.test:10000","token":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.cccccccccccccccccccccccccccccccccccccccc","command":"id"}"#,
        ));
        assert!(!valid_control(
            "kubeedge.edgecore.join",
            "",
            r#"{"version":"v1.23.1-rc1","cloudCoreEndpoint":"cloud.example.test:10000","token":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.cccccccccccccccccccccccccccccccccccccccc"}"#,
        ));
        assert!(!valid_control(
            "kubeedge.edgecore.join",
            "",
            r#"{"version":"v1.23.1","cloudCoreEndpoint":"cloud.example.test:10000;id","token":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.cccccccccccccccccccccccccccccccccccccccc"}"#,
        ));
    }
}
