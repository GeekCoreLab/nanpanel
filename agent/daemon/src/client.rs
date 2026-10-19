use std::{
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};

use anyhow::{Context, Result, anyhow};
use tokio::{
    sync::{Mutex, mpsc},
    time,
};
use tokio_stream::wrappers::ReceiverStream;
use tonic::transport::{Certificate, Channel, ClientTlsConfig, Endpoint, Identity};
use tracing::{info, warn};

use crate::v1::{
    AgentMessage, Heartbeat, OperationResult, agent_gateway_client::AgentGatewayClient,
    agent_message::Payload,
};

use crate::helper;

#[derive(Clone)]
struct HostSnapshot {
    hostname: String,
    os: String,
    arch: String,
}

#[derive(Clone, Debug)]
pub struct AgentConfig {
    pub endpoint: String,
    pub tls_domain: String,
    pub agent_id: String,
    pub ca_pem: PathBuf,
    pub cert_pem: PathBuf,
    pub key_pem: PathBuf,
    pub helper_socket: PathBuf,
    pub heartbeat_interval: Duration,
    pub reconnect_min: Duration,
    pub reconnect_max: Duration,
}

impl AgentConfig {
    pub fn from_env() -> Result<Self> {
        let endpoint = required("NANPANEL_AGENT_ENDPOINT")?;
        let tls_domain =
            std::env::var("NANPANEL_AGENT_TLS_DOMAIN").unwrap_or_else(|_| "localhost".to_owned());
        let agent_id = required("NANPANEL_AGENT_ID")?;
        Ok(Self {
            endpoint,
            tls_domain,
            agent_id,
            ca_pem: required("NANPANEL_AGENT_CA")?.into(),
            cert_pem: required("NANPANEL_AGENT_CERT")?.into(),
            key_pem: required("NANPANEL_AGENT_KEY")?.into(),
            helper_socket: std::env::var("NANPANEL_AGENT_HELPER_SOCKET")
                .unwrap_or_else(|_| "/run/nanpanel/helper.sock".to_owned())
                .into(),
            heartbeat_interval: duration_env("NANPANEL_AGENT_HEARTBEAT_SECS", 5),
            reconnect_min: duration_env("NANPANEL_AGENT_RECONNECT_MIN_MS", 500),
            reconnect_max: duration_env("NANPANEL_AGENT_RECONNECT_MAX_MS", 30_000),
        })
    }
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
    let mut backoff = config.reconnect_min;

    loop {
        match connect_once(&config, tls.clone()).await {
            Ok(()) => warn!(agent_id = %config.agent_id, "gateway stream closed"),
            Err(error) => {
                warn!(agent_id = %config.agent_id, error = %error, "gateway connection failed")
            }
        }
        time::sleep(backoff).await;
        backoff = backoff.saturating_mul(2).min(config.reconnect_max);
    }
}

async fn connect_once(config: &AgentConfig, tls: ClientTlsConfig) -> Result<()> {
    let endpoint = Endpoint::from_shared(config.endpoint.clone())?
        .connect_timeout(Duration::from_secs(10))
        .tcp_keepalive(Some(Duration::from_secs(30)))
        .keep_alive_while_idle(true)
        .tls_config(tls)?;
    let channel: Channel = endpoint.connect().await.context("connect gateway")?;
    let mut client = AgentGatewayClient::new(channel);
    let (sender, receiver) = mpsc::channel(16);
    let mut stream = client
        .session(ReceiverStream::new(receiver))
        .await
        .context("open gateway stream")?
        .into_inner();
    info!(agent_id = %config.agent_id, endpoint = %config.endpoint, "mTLS gateway stream established");

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
    let sequence = Arc::new(Mutex::new(0_u64));
    let heartbeat_sequence = Arc::clone(&sequence);
    let heartbeat_helper_available = Arc::clone(&helper_available);
    let heartbeat_helper_socket = config.helper_socket.clone();
    let heartbeat_sender = sender.clone();
    let heartbeat_task = tokio::spawn(async move {
        let mut ticker = time::interval(interval);
        ticker.set_missed_tick_behavior(time::MissedTickBehavior::Skip);
        loop {
            ticker.tick().await;
            let helper_is_available = helper::status(&heartbeat_helper_socket)
                .await
                .unwrap_or(false);
            heartbeat_helper_available.store(helper_is_available, Ordering::Relaxed);
            let capabilities = if helper_is_available {
                vec![
                    "host.inventory".to_owned(),
                    "agent.heartbeat".to_owned(),
                    "agent.self_update".to_owned(),
                ]
            } else {
                vec!["host.inventory".to_owned(), "agent.heartbeat".to_owned()]
            };
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
                    version: env!("CARGO_PKG_VERSION").to_owned(),
                    hostname: host.hostname.clone(),
                    os: host.os.clone(),
                    arch: host.arch.clone(),
                    capabilities,
                    helper_status: if helper_is_available {
                        "AVAILABLE".to_owned()
                    } else {
                        "UNAVAILABLE".to_owned()
                    },
                })),
            };
            if heartbeat_sender.send(message).await.is_err() {
                break;
            }
        }
    });

    while let Some(control) = stream.message().await.context("read gateway stream")? {
        if control.operation_id.is_empty()
            || control.idempotency_key.is_empty()
            || control.capability.is_empty()
            || control.deadline_epoch_ms <= 0
        {
            return Err(anyhow!("gateway sent an invalid control envelope"));
        }
        if control.capability != "agent.update" || !control.target.is_empty() {
            send_operation_result(
                &sender,
                &config.agent_id,
                &sequence,
                control.operation_id,
                control.idempotency_key,
                "FAILED",
                "Agent only accepts the typed agent.update operation with an empty target",
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
                "root Helper is unavailable",
            )
            .await?;
            continue;
        }

        let operation_id = control.operation_id;
        let idempotency_key = control.idempotency_key;
        let deadline = control.deadline_epoch_ms;
        let agent_id = config.agent_id.clone();
        let helper_socket = config.helper_socket.clone();
        let operation_sender = sender.clone();
        let operation_sequence = Arc::clone(&sequence);
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
