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
use serde::Deserialize;
use tokio::{
    sync::{Mutex, mpsc},
    time,
};
use tokio_stream::wrappers::ReceiverStream;
use tonic::Streaming;
use tonic::transport::{Certificate, Channel, ClientTlsConfig, Endpoint, Identity};
use tracing::{info, warn};

use crate::v1::{
    AgentMessage, ControlMessage, Heartbeat, OperationResult, RelayMessage, SystemdService,
    agent_gateway_client::AgentGatewayClient, agent_message::Payload,
    control_relay_client::ControlRelayClient, relay_message::Payload as RelayPayload,
};

use crate::{
    direct_dns, helper,
    operation_journal::{Claim, OperationJournal},
};

#[derive(Clone)]
struct HostSnapshot {
    hostname: String,
    os: String,
    arch: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct NetworkFirewallPayload {
    rollback_seconds: u16,
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
        })
    }
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
    let heartbeat_sender = sender.clone();
    let restart_allowlist = allowed_systemd_restart_units();
    let journal_allowlist = allowed_systemd_journal_units();
    let heartbeat_task = tokio::spawn(async move {
        let mut ticker = time::interval(interval);
        ticker.set_missed_tick_behavior(time::MissedTickBehavior::Skip);
        let mut systemd_services =
            collect_systemd_services(&restart_allowlist, &journal_allowlist).await;
        let mut systemd_observed_at = systemd_services.as_ref().and_then(|_| epoch_ms().ok());
        let mut systemd_refresh_at = time::Instant::now();
        loop {
            ticker.tick().await;
            if systemd_refresh_at.elapsed() >= Duration::from_secs(30) {
                systemd_services =
                    collect_systemd_services(&restart_allowlist, &journal_allowlist).await;
                systemd_observed_at = if systemd_services.is_some() {
                    epoch_ms().ok()
                } else {
                    None
                };
                systemd_refresh_at = time::Instant::now();
            }
            let helper_is_available = helper::status(&heartbeat_helper_socket)
                .await
                .unwrap_or(false);
            heartbeat_helper_available.store(helper_is_available, Ordering::Relaxed);
            let mut capabilities = vec!["host.inventory".to_owned(), "agent.heartbeat".to_owned()];
            if helper_is_available {
                capabilities.push("agent.self_update".to_owned());
                if let Ok(helper_capabilities) =
                    helper::capabilities(&heartbeat_helper_socket).await
                {
                    for capability in helper_capabilities {
                        if !capabilities.contains(&capability)
                            && matches!(
                                capability.as_str(),
                                "systemd.restart"
                                    | "systemd.journal.read"
                                    | "package.install"
                                    | "file.write.protected"
                                    | "container.list"
                                    | "container.logs"
                                    | "container.start"
                                    | "container.stop"
                                    | "container.deploy"
                                    | "network.firewall.expose"
                            )
                        {
                            capabilities.push(capability);
                        }
                    }
                }
            }
            if systemd_services.is_some() {
                capabilities.push("systemd.read".to_owned());
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
                payload: Some(Payload::Heartbeat(Heartbeat {
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
                                    restart_allowed: helper_is_available && service.restart_allowed,
                                    journal_read_allowed: helper_is_available
                                        && service.journal_read_allowed,
                                })
                                .collect()
                        })
                        .unwrap_or_default(),
                    systemd_observed_at_epoch_ms: systemd_observed_at.unwrap_or_default(),
                })),
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
                let detail = bounded_detail(&detail);
                if let Err(error) = operation_journal
                    .lock()
                    .await
                    .complete(&operation_id, &idempotency_key, status, &detail)
                    .await
                {
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

async fn collect_systemd_services(
    restart_allowlist: &HashSet<String>,
    journal_allowlist: &HashSet<String>,
) -> Option<Vec<SystemdService>> {
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
    parse_systemd_services(&output.stdout, restart_allowlist, journal_allowlist)
}

fn parse_systemd_services(
    output: &[u8],
    restart_allowlist: &HashSet<String>,
    journal_allowlist: &HashSet<String>,
) -> Option<Vec<SystemdService>> {
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
                restart_allowed: unit_name == "nanpanel-agent.service"
                    || restart_allowlist.contains(unit_name),
                journal_read_allowed: journal_allowlist.contains(unit_name),
            })
        })
        .take(512)
        .collect();
    Some(services)
}

fn valid_service_unit(unit_name: &str) -> bool {
    unit_name.len() <= 255
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
        "container.logs" | "container.start" | "container.stop" | "container.deploy" => {
            valid_container_target(target) && !has_payload
        }
        "network.firewall.expose" => {
            valid_container_target(target)
                && has_payload
                && serde_json::from_str::<NetworkFirewallPayload>(payload_json)
                    .is_ok_and(|payload| (30..=300).contains(&payload.rollback_seconds))
        }
        _ => false,
    }
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

fn allowed_systemd_restart_units() -> HashSet<String> {
    let Ok(contents) = std::fs::read_to_string("/etc/nanpanel/managed/systemd-units.allowlist")
    else {
        return HashSet::new();
    };
    contents
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .filter(|unit| valid_service_unit(unit))
        .take(128)
        .map(str::to_owned)
        .collect()
}

fn allowed_systemd_journal_units() -> HashSet<String> {
    let Ok(contents) = std::fs::read_to_string("/etc/nanpanel/managed/journal-units.allowlist")
    else {
        return HashSet::new();
    };
    contents
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .filter(|unit| valid_service_unit(unit))
        .take(128)
        .map(str::to_owned)
        .collect()
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
        payload: Some(Payload::OperationResult(OperationResult {
            operation_id,
            idempotency_key,
            status: status.to_owned(),
            detail: detail.to_owned(),
        })),
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
        let services = parse_systemd_services(
            input,
            &HashSet::from(["ssh.service".to_owned()]),
            &HashSet::from(["failed.service".to_owned()]),
        )
        .ok_or_else(|| anyhow!("valid systemd JSON should parse"))?;
        assert_eq!(services.len(), 2);
        assert_eq!(services[0].unit_name, "ssh.service");
        assert_eq!(services[0].active_state, "ACTIVE");
        assert_eq!(services[0].sub_state, "running");
        assert_eq!(services[1].active_state, "FAILED");
        assert!(services[0].restart_allowed);
        assert!(!services[0].journal_read_allowed);
        assert!(services[1].journal_read_allowed);
        Ok(())
    }

    #[test]
    fn rejects_invalid_systemd_json() {
        let none = HashSet::new();
        assert!(parse_systemd_services(b"not-json", &none, &none).is_none());
        assert!(parse_systemd_services(br#"{"unit":"ssh.service"}"#, &none, &none).is_none());
    }

    #[test]
    fn validates_temporary_firewall_control_payload_and_allowlisted_target_shape() {
        assert!(valid_control(
            "network.firewall.expose",
            "ssh-test",
            r#"{"rollbackSeconds":90}"#,
        ));
        assert!(!valid_control(
            "network.firewall.expose",
            "ssh-test",
            r#"{"rollbackSeconds":301}"#,
        ));
        assert!(!valid_control(
            "network.firewall.expose",
            "ssh-test;id",
            r#"{"rollbackSeconds":90}"#,
        ));
        assert!(!valid_control(
            "network.firewall.expose",
            "ssh-test",
            r#"{"rollbackSeconds":90,"command":"id"}"#,
        ));
    }
}
