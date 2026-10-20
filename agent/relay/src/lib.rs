use std::{collections::HashMap, path::PathBuf, pin::Pin, sync::Arc};

use anyhow::Context;
use futures_core::Stream;
use prost::Message;
use sha2::{Digest, Sha256};
use tokio::io::AsyncWriteExt;
use tokio::sync::{Mutex, mpsc};
use tokio_stream::wrappers::ReceiverStream;
use tonic::{Request, Response, Status};
use tracing::{info, warn};

pub mod v1 {
    tonic::include_proto!("nanpanel.agent.v1");
}

use v1::{
    AgentMessage, ControlMessage, CoreMessage, RelayAgentAuthorization, RelayMessage,
    control_relay_server::{ControlRelay, ControlRelayServer},
    core_message::Payload as CorePayload,
    relay_message::Payload as RelayPayload,
};

const QUEUE_CAPACITY: usize = 8;
const MAX_FRAME_BYTES: usize = 64 * 1024;
const MAX_AGENT_SESSIONS: usize = 256;

type RelayStream = Pin<Box<dyn Stream<Item = Result<RelayMessage, Status>> + Send + 'static>>;

#[derive(Clone)]
pub struct Relay {
    state: Arc<Mutex<RelayState>>,
    relay_id: Arc<str>,
    cluster_id: Arc<str>,
    core_fingerprints: Arc<Vec<String>>,
    epoch_file: Option<Arc<PathBuf>>,
}

struct RelayState {
    latest_core_epoch: u64,
    core: Option<CoreRoute>,
    agents: HashMap<String, AgentRoute>,
    active_agents: HashMap<String, String>,
}

struct CoreRoute {
    session_id: String,
    sender: mpsc::Sender<Result<RelayMessage, Status>>,
}

struct AgentRoute {
    agent_id: String,
    fingerprint: String,
    authorized: bool,
    sender: mpsc::Sender<Result<RelayMessage, Status>>,
}

impl Relay {
    pub fn new(
        relay_id: String,
        cluster_id: String,
        core_fingerprints: Vec<String>,
    ) -> Result<Self, String> {
        if relay_id.trim().is_empty() || cluster_id.trim().is_empty() {
            return Err("relay and cluster identifiers must be non-empty".to_owned());
        }
        if core_fingerprints.is_empty()
            || core_fingerprints
                .iter()
                .any(|fingerprint| !valid_fingerprint(fingerprint))
        {
            return Err("at least one valid Core certificate fingerprint is required".to_owned());
        }
        Ok(Self {
            state: Arc::new(Mutex::new(RelayState {
                latest_core_epoch: 0,
                core: None,
                agents: HashMap::new(),
                active_agents: HashMap::new(),
            })),
            relay_id: Arc::from(relay_id),
            cluster_id: Arc::from(cluster_id),
            core_fingerprints: Arc::new(core_fingerprints),
            epoch_file: None,
        })
    }

    async fn with_epoch_file(mut self, epoch_file: PathBuf) -> anyhow::Result<Self> {
        if let Some(parent) = epoch_file.parent() {
            ensure_private_state_directory(parent).await?;
        }
        let latest_epoch = match tokio::fs::read_to_string(&epoch_file).await {
            Ok(contents) => contents
                .trim()
                .parse::<u64>()
                .context("parse persisted Core epoch")?,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => 0,
            Err(error) => return Err(error).context("read persisted Core epoch"),
        };
        self.state.lock().await.latest_core_epoch = latest_epoch;
        self.epoch_file = Some(Arc::new(epoch_file));
        Ok(self)
    }

    fn check_core_certificate(
        &self,
        request: &Request<tonic::Streaming<CoreMessage>>,
    ) -> Result<(), Status> {
        let fingerprint = peer_fingerprint(request)?;
        if self
            .core_fingerprints
            .iter()
            .any(|expected| expected == &fingerprint)
        {
            Ok(())
        } else {
            Err(Status::unauthenticated(
                "Core certificate is not authorized",
            ))
        }
    }
}

#[tonic::async_trait]
impl ControlRelay for Relay {
    type AgentSessionStream = RelayStream;
    type CoreSessionStream = RelayStream;

    async fn agent_session(
        &self,
        request: Request<tonic::Streaming<AgentMessage>>,
    ) -> Result<Response<Self::AgentSessionStream>, Status> {
        let fingerprint = peer_fingerprint(&request)?;
        let mut inbound = request.into_inner();
        let (sender, receiver) = mpsc::channel(QUEUE_CAPACITY);
        let state = Arc::clone(&self.state);
        let session_id = uuid_session_id();

        tokio::spawn(async move {
            let mut registered_agent_id: Option<String> = None;
            let mut last_sequence = 0_u64;
            loop {
                let message = match inbound.message().await {
                    Ok(Some(message)) => message,
                    Ok(None) => break,
                    Err(error) => {
                        warn!(%error, "Agent relay stream ended with an error");
                        break;
                    }
                };
                if message.encoded_len() > MAX_FRAME_BYTES
                    || message.agent_id.is_empty()
                    || message.agent_id.len() > 64
                    || message.sequence <= last_sequence
                    || message.payload.is_none()
                {
                    let _ = sender
                        .send(Err(Status::invalid_argument(
                            "invalid or oversized Agent message",
                        )))
                        .await;
                    break;
                }
                if registered_agent_id
                    .as_ref()
                    .is_some_and(|agent_id| agent_id != &message.agent_id)
                {
                    let _ = sender
                        .send(Err(Status::invalid_argument(
                            "Agent identity changed within stream",
                        )))
                        .await;
                    break;
                }
                last_sequence = message.sequence;
                let agent_id = message.agent_id.clone();
                let relayed = RelayMessage {
                    payload: Some(RelayPayload::AgentMessage(v1::RelayedAgentMessage {
                        agent_id: agent_id.clone(),
                        certificate_fingerprint: fingerprint.clone(),
                        message: Some(Box::new(message)),
                    })),
                };
                if relayed.encoded_len() > MAX_FRAME_BYTES {
                    let _ = sender
                        .send(Err(Status::resource_exhausted(
                            "relayed Agent message exceeds the frame limit",
                        )))
                        .await;
                    break;
                }
                let mut current = state.lock().await;
                let Some(core) = current.core.as_ref() else {
                    drop(current);
                    let _ = sender
                        .send(Err(Status::unavailable("no active Core relay session")))
                        .await;
                    break;
                };
                let core_sender = core.sender.clone();
                if !current.agents.contains_key(&session_id) {
                    if current.agents.len() >= MAX_AGENT_SESSIONS {
                        drop(current);
                        let _ = sender
                            .send(Err(Status::resource_exhausted(
                                "Relay Agent session limit has been reached",
                            )))
                            .await;
                        break;
                    }
                    if current
                        .agents
                        .values()
                        .any(|route| route.agent_id == agent_id && route.fingerprint == fingerprint)
                    {
                        drop(current);
                        let _ = sender
                            .send(Err(Status::already_exists(
                                "an Agent session with this identity is already connected",
                            )))
                            .await;
                        break;
                    }
                    if current.agents.values().any(|route| {
                        route.agent_id == agent_id
                            && route.authorized
                            && route.fingerprint != fingerprint
                    }) {
                        drop(current);
                        let _ = sender
                            .send(Err(Status::permission_denied(
                                "Agent identity is already active with another certificate",
                            )))
                            .await;
                        break;
                    }
                    current.agents.insert(
                        session_id.clone(),
                        AgentRoute {
                            agent_id: agent_id.clone(),
                            fingerprint: fingerprint.clone(),
                            authorized: false,
                            sender: sender.clone(),
                        },
                    );
                }
                drop(current);
                registered_agent_id = Some(agent_id);
                match core_sender.try_send(Ok(relayed)) {
                    Ok(()) => {}
                    Err(mpsc::error::TrySendError::Full(_)) => {
                        let _ = sender
                            .send(Err(Status::resource_exhausted("Core relay queue is full")))
                            .await;
                        break;
                    }
                    Err(mpsc::error::TrySendError::Closed(_)) => break,
                }
            }

            if let Some(agent_id) = registered_agent_id {
                let mut current = state.lock().await;
                current.agents.remove(&session_id);
                if current.active_agents.get(&agent_id) == Some(&session_id) {
                    current.active_agents.remove(&agent_id);
                }
            }
        });

        Ok(Response::new(Box::pin(ReceiverStream::new(receiver))))
    }

    async fn core_session(
        &self,
        request: Request<tonic::Streaming<CoreMessage>>,
    ) -> Result<Response<Self::CoreSessionStream>, Status> {
        self.check_core_certificate(&request)?;
        let mut inbound = request.into_inner();
        let hello = inbound
            .message()
            .await?
            .ok_or_else(|| Status::invalid_argument("Core must begin with a relay hello"))?;
        let Some(CorePayload::Hello(hello)) = hello.payload else {
            return Err(Status::invalid_argument(
                "Core must begin with a relay hello",
            ));
        };
        if hello.cluster_id != self.cluster_id.as_ref() || hello.epoch == 0 {
            return Err(Status::failed_precondition(
                "Core cluster identity is invalid or mismatched",
            ));
        }

        let session_id = uuid_session_id();
        let (sender, receiver) = mpsc::channel(QUEUE_CAPACITY);
        let mut state = self.state.lock().await;
        if hello.epoch < state.latest_core_epoch {
            return Err(Status::failed_precondition("stale Core epoch"));
        }
        if hello.epoch > state.latest_core_epoch {
            if let Some(epoch_file) = &self.epoch_file {
                persist_epoch(epoch_file, hello.epoch)
                    .await
                    .map_err(|_| Status::unavailable("unable to persist Core fencing epoch"))?;
            }
            state.latest_core_epoch = hello.epoch;
        }
        for agent in state.agents.values_mut() {
            agent.authorized = false;
        }
        state.active_agents.clear();
        if let Some(previous) = state.core.replace(CoreRoute {
            session_id: session_id.clone(),
            sender: sender.clone(),
        }) {
            let _ = previous.sender.try_send(Err(Status::unavailable(
                "Core relay session was fenced by a newer epoch",
            )));
        }
        sender
            .try_send(Ok(RelayMessage {
                payload: Some(RelayPayload::HelloAck(v1::RelayHelloAck {
                    relay_id: self.relay_id.to_string(),
                    cluster_id: self.cluster_id.to_string(),
                    core_epoch: hello.epoch,
                    max_frame_bytes: MAX_FRAME_BYTES as u32,
                })),
            }))
            .map_err(|_| Status::internal("unable to initialize Core relay stream"))?;
        drop(state);

        let state = Arc::clone(&self.state);
        tokio::spawn(async move {
            loop {
                let message = match inbound.message().await {
                    Ok(Some(message)) => message,
                    Ok(None) => break,
                    Err(error) => {
                        warn!(%error, "Core relay stream ended with an error");
                        break;
                    }
                };
                let result = handle_core_message(&state, &session_id, message).await;
                if let Err(error) = result {
                    let _ = sender.send(Err(error)).await;
                    break;
                }
            }
            let mut state = state.lock().await;
            if state
                .core
                .as_ref()
                .is_some_and(|route| route.session_id == session_id)
            {
                state.core = None;
                for agent in state.agents.values_mut() {
                    agent.authorized = false;
                }
                state.active_agents.clear();
                info!("active Core relay session disconnected");
            }
        });

        Ok(Response::new(Box::pin(ReceiverStream::new(receiver))))
    }
}

async fn handle_core_message(
    state: &Arc<Mutex<RelayState>>,
    session_id: &str,
    message: CoreMessage,
) -> Result<(), Status> {
    match message.payload {
        Some(CorePayload::Control(route)) => {
            let control = route
                .control
                .ok_or_else(|| Status::invalid_argument("control payload is required"))?;
            validate_control(&route.agent_id, &control)?;
            let state = state.lock().await;
            let _core = state
                .core
                .as_ref()
                .filter(|core| core.session_id == session_id)
                .ok_or_else(|| Status::failed_precondition("Core session was fenced"))?;
            let active_session = state
                .active_agents
                .get(&route.agent_id)
                .ok_or_else(|| Status::not_found("authorized Agent route is unavailable"))?;
            let agent = state
                .agents
                .get(active_session)
                .filter(|agent| agent.authorized)
                .ok_or_else(|| Status::not_found("authorized Agent route is unavailable"))?;
            agent
                .sender
                .try_send(Ok(RelayMessage {
                    payload: Some(RelayPayload::Control(control)),
                }))
                .map_err(|_| Status::resource_exhausted("Agent relay queue is full"))
        }
        Some(CorePayload::Acknowledge(ack)) => {
            if ack.agent_id.is_empty()
                || ack.operation_id.is_empty()
                || ack.idempotency_key.is_empty()
                || ack.operation_id.len() > 128
                || ack.idempotency_key.len() > 128
            {
                return Err(Status::invalid_argument(
                    "invalid operation acknowledgement",
                ));
            }
            let state = state.lock().await;
            let _core = state
                .core
                .as_ref()
                .filter(|core| core.session_id == session_id)
                .ok_or_else(|| Status::failed_precondition("Core session was fenced"))?;
            let active_session = state
                .active_agents
                .get(&ack.agent_id)
                .ok_or_else(|| Status::not_found("authorized Agent route is unavailable"))?;
            let agent = state
                .agents
                .get(active_session)
                .filter(|agent| agent.authorized)
                .ok_or_else(|| Status::not_found("authorized Agent route is unavailable"))?;
            agent
                .sender
                .try_send(Ok(RelayMessage {
                    payload: Some(RelayPayload::Control(ControlMessage {
                        operation_id: ack.operation_id,
                        idempotency_key: ack.idempotency_key,
                        capability: String::new(),
                        target: String::new(),
                        deadline_epoch_ms: 0,
                        operation_result_ack: true,
                        payload_json: String::new(),
                    })),
                }))
                .map_err(|_| Status::resource_exhausted("Agent relay queue is full"))
        }
        Some(CorePayload::AgentAuthorization(authorization)) => {
            validate_authorization(&authorization)?;
            let mut state = state.lock().await;
            let _core = state
                .core
                .as_ref()
                .filter(|core| core.session_id == session_id)
                .ok_or_else(|| Status::failed_precondition("Core session was fenced"))?;
            let matching_session = state
                .agents
                .iter()
                .find(|(_, agent)| {
                    agent.agent_id == authorization.agent_id
                        && agent.fingerprint == authorization.certificate_fingerprint
                })
                .map(|(session_id, _)| session_id.clone());
            let Some(matching_session) = matching_session else {
                if state
                    .agents
                    .values()
                    .any(|agent| agent.agent_id == authorization.agent_id)
                {
                    return Err(Status::permission_denied(
                        "Agent certificate does not match the authenticated session",
                    ));
                }
                // A delayed authorization for a disconnected session is harmless.
                return Ok(());
            };
            if authorization.allowed {
                if let Some(previous_session) = state
                    .active_agents
                    .insert(authorization.agent_id.clone(), matching_session.clone())
                    .filter(|previous| previous != &matching_session)
                    && let Some(previous) = state.agents.remove(&previous_session)
                {
                    let _ = previous.sender.try_send(Err(Status::unavailable(
                        "Agent route was replaced after Core authorization",
                    )));
                }
                if let Some(agent) = state.agents.get_mut(&matching_session) {
                    agent.authorized = true;
                }
            } else {
                if state.active_agents.get(&authorization.agent_id) == Some(&matching_session) {
                    state.active_agents.remove(&authorization.agent_id);
                }
                if let Some(agent) = state.agents.remove(&matching_session) {
                    let _ = agent.sender.try_send(Err(Status::permission_denied(
                        "Core rejected Agent route authorization",
                    )));
                }
            }
            Ok(())
        }
        Some(CorePayload::Hello(_)) | None => {
            Err(Status::invalid_argument("unexpected Core relay message"))
        }
    }
}

fn validate_control(agent_id: &str, control: &ControlMessage) -> Result<(), Status> {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|_| Status::internal("system clock is before Unix epoch"))?
        .as_millis() as i64;
    if agent_id.is_empty()
        || agent_id.len() > 64
        || control.operation_id.is_empty()
        || control.operation_id.len() > 128
        || control.idempotency_key.is_empty()
        || control.idempotency_key.len() > 128
        || control.capability.is_empty()
        || control.capability.len() > 96
        || control.target.len() > 255
        || control.payload_json.len() > 48 * 1024
        || control.encoded_len() > MAX_FRAME_BYTES
        || control.deadline_epoch_ms <= now
        || control.deadline_epoch_ms > now + 10 * 60 * 1000
        || control.operation_result_ack
    {
        return Err(Status::invalid_argument(
            "invalid or oversized Core control",
        ));
    }
    Ok(())
}

fn validate_authorization(authorization: &RelayAgentAuthorization) -> Result<(), Status> {
    if authorization.agent_id.is_empty()
        || authorization.agent_id.len() > 64
        || !valid_fingerprint(&authorization.certificate_fingerprint)
    {
        return Err(Status::invalid_argument("invalid Agent authorization"));
    }
    Ok(())
}

fn peer_fingerprint<T>(request: &Request<T>) -> Result<String, Status> {
    request
        .peer_certs()
        .and_then(|certificates| certificates.first().cloned())
        .map(|certificate| hex::encode(Sha256::digest(certificate.as_ref())))
        .ok_or_else(|| Status::unauthenticated("client certificate is required"))
}

fn valid_fingerprint(fingerprint: &str) -> bool {
    fingerprint.len() == 64
        && fingerprint
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
}

fn uuid_session_id() -> String {
    // Unique per process session without depending on externally supplied identifiers.
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT_ID: AtomicU64 = AtomicU64::new(1);
    format!("relay-session-{}", NEXT_ID.fetch_add(1, Ordering::Relaxed))
}

pub async fn serve_from_env() -> anyhow::Result<()> {
    let address = std::env::var("NANPANEL_RELAY_LISTEN")
        .unwrap_or_else(|_| "0.0.0.0:50053".to_owned())
        .parse()?;
    let relay_id = required("NANPANEL_RELAY_ID")?;
    let cluster_id = required("NANPANEL_RELAY_CLUSTER_ID")?;
    let core_fingerprints = std::env::var("NANPANEL_RELAY_CORE_CERT_FINGERPRINTS")?
        .split(',')
        .map(str::trim)
        .map(str::to_owned)
        .filter(|fingerprint| !fingerprint.is_empty())
        .collect::<Vec<_>>();
    let ca = tokio::fs::read(required("NANPANEL_RELAY_CLIENT_CA")?).await?;
    let cert = tokio::fs::read(required("NANPANEL_RELAY_CERT")?).await?;
    let key = tokio::fs::read(required("NANPANEL_RELAY_KEY")?).await?;
    let epoch_file = std::env::var("NANPANEL_RELAY_STATE_DIRECTORY")
        .unwrap_or_else(|_| "/var/lib/nanpanel-relay".to_owned());
    let relay = Relay::new(relay_id, cluster_id, core_fingerprints)
        .map_err(anyhow::Error::msg)?
        .with_epoch_file(PathBuf::from(epoch_file).join("core-epoch"))
        .await?;
    let tls = tonic::transport::ServerTlsConfig::new()
        .identity(tonic::transport::Identity::from_pem(cert, key))
        .client_ca_root(tonic::transport::Certificate::from_pem(ca));
    info!(%address, "starting mTLS Control Relay");
    tonic::transport::Server::builder()
        .tls_config(tls)?
        .add_service(
            ControlRelayServer::new(relay)
                .max_decoding_message_size(MAX_FRAME_BYTES)
                .max_encoding_message_size(MAX_FRAME_BYTES),
        )
        .serve_with_shutdown(address, async {
            #[cfg(unix)]
            {
                use tokio::signal::unix::{SignalKind, signal};
                if let Ok(mut terminate) = signal(SignalKind::terminate()) {
                    tokio::select! {
                        _ = tokio::signal::ctrl_c() => {},
                        _ = terminate.recv() => {},
                    }
                } else {
                    let _ = tokio::signal::ctrl_c().await;
                }
            }
            #[cfg(not(unix))]
            {
                let _ = tokio::signal::ctrl_c().await;
            }
        })
        .await?;
    Ok(())
}

fn required(name: &str) -> anyhow::Result<String> {
    std::env::var(name).map_err(|_| anyhow::anyhow!("missing required environment variable {name}"))
}

async fn persist_epoch(path: &PathBuf, epoch: u64) -> anyhow::Result<()> {
    let parent = path
        .parent()
        .context("Relay epoch file must have a parent directory")?;
    ensure_private_state_directory(parent).await?;
    let temporary = path.with_extension(format!("tmp-{}", uuid_session_id()));
    let mut options = tokio::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    options.mode(0o600);
    let mut file = options.open(&temporary).await?;
    file.write_all(format!("{epoch}\n").as_bytes()).await?;
    file.sync_all().await?;
    drop(file);
    if let Err(error) = tokio::fs::rename(&temporary, path).await {
        let _ = tokio::fs::remove_file(&temporary).await;
        return Err(error).context("atomically persist Core epoch");
    }
    tokio::fs::File::open(parent).await?.sync_all().await?;
    Ok(())
}

async fn ensure_private_state_directory(path: &std::path::Path) -> anyhow::Result<()> {
    match tokio::fs::metadata(path).await {
        Ok(_) => check_directory_permissions(path),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            tokio::fs::create_dir_all(path)
                .await
                .with_context(|| format!("create Relay state directory {}", path.display()))?;
            set_directory_permissions(path)?;
            check_directory_permissions(path)
        }
        Err(error) => Err(error).context("inspect Relay state directory permissions"),
    }
}

fn set_directory_permissions(path: &std::path::Path) -> anyhow::Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o700))?;
    }
    Ok(())
}

fn check_directory_permissions(path: &std::path::Path) -> anyhow::Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = std::fs::metadata(path)?.permissions().mode();
        if mode & 0o077 != 0 {
            anyhow::bail!("Relay state directory must not be accessible by group or others")
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn relay_requires_a_trusted_core_certificate_fingerprint() {
        assert!(Relay::new("r1".into(), "cluster".into(), vec![]).is_err());
        assert!(Relay::new("r1".into(), "cluster".into(), vec!["A".repeat(64)]).is_err());
        assert!(Relay::new("r1".into(), "cluster".into(), vec!["a".repeat(64)]).is_ok());
    }

    #[test]
    fn relay_rejects_expired_unbounded_and_result_ack_controls() {
        let now_result = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|duration| duration.as_millis() as i64);
        assert!(now_result.is_ok());
        let Ok(now) = now_result else {
            return;
        };
        let control = |deadline_epoch_ms, operation_result_ack| ControlMessage {
            operation_id: "op1".into(),
            idempotency_key: "idem1".into(),
            capability: "systemd.restart".into(),
            target: "sshd.service".into(),
            deadline_epoch_ms,
            operation_result_ack,
            payload_json: String::new(),
        };
        assert!(validate_control("agent1", &control(now + 60_000, false)).is_ok());
        assert!(validate_control("agent1", &control(now - 1, false)).is_err());
        assert!(validate_control("agent1", &control(now + 60_000, true)).is_err());
        assert!(
            validate_control(
                "agent1",
                &ControlMessage {
                    payload_json: "x".repeat(48 * 1024 + 1),
                    ..control(now + 60_000, false)
                }
            )
            .is_err()
        );
    }

    #[tokio::test]
    async fn authorization_is_bound_to_the_connected_agent_certificate() {
        let relay_result = Relay::new("r1".into(), "cluster".into(), vec!["a".repeat(64)]);
        assert!(relay_result.is_ok());
        let Ok(relay) = relay_result else {
            return;
        };
        let route = AgentRoute {
            agent_id: "agent1".into(),
            fingerprint: "b".repeat(64),
            authorized: false,
            sender: mpsc::channel(1).0,
        };
        relay
            .state
            .lock()
            .await
            .agents
            .insert("agent-session".into(), route);
        let mut state = relay.state.lock().await;
        state.core = Some(CoreRoute {
            session_id: "core-session".into(),
            sender: mpsc::channel(1).0,
        });
        drop(state);
        let result = handle_core_message(
            &relay.state,
            "core-session",
            CoreMessage {
                payload: Some(CorePayload::AgentAuthorization(RelayAgentAuthorization {
                    agent_id: "agent1".into(),
                    certificate_fingerprint: "c".repeat(64),
                    allowed: true,
                })),
            },
        )
        .await;
        assert!(result.is_err());
        if let Err(error) = result {
            assert_eq!(error.code(), tonic::Code::PermissionDenied);
        }
    }

    #[tokio::test]
    async fn authorized_route_carries_core_controls_to_the_matching_agent_session() {
        let relay_result = Relay::new("r1".into(), "cluster".into(), vec!["a".repeat(64)]);
        assert!(relay_result.is_ok());
        let Ok(relay) = relay_result else {
            return;
        };
        let (agent_sender, mut agent_receiver) = mpsc::channel(2);
        {
            let mut state = relay.state.lock().await;
            state.core = Some(CoreRoute {
                session_id: "core-session".into(),
                sender: mpsc::channel(1).0,
            });
            state.agents.insert(
                "agent-session".into(),
                AgentRoute {
                    agent_id: "agent1".into(),
                    fingerprint: "b".repeat(64),
                    authorized: false,
                    sender: agent_sender,
                },
            );
        }
        let authorized = handle_core_message(
            &relay.state,
            "core-session",
            CoreMessage {
                payload: Some(CorePayload::AgentAuthorization(RelayAgentAuthorization {
                    agent_id: "agent1".into(),
                    certificate_fingerprint: "b".repeat(64),
                    allowed: true,
                })),
            },
        )
        .await;
        assert!(authorized.is_ok());

        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|duration| duration.as_millis() as i64);
        assert!(now.is_ok());
        let Ok(now) = now else {
            return;
        };
        let sent = handle_core_message(
            &relay.state,
            "core-session",
            CoreMessage {
                payload: Some(CorePayload::Control(v1::RelayControl {
                    agent_id: "agent1".into(),
                    control: Some(ControlMessage {
                        operation_id: "operation1".into(),
                        idempotency_key: "idempotency1".into(),
                        capability: "systemd.restart".into(),
                        target: "sshd.service".into(),
                        deadline_epoch_ms: now + 60_000,
                        operation_result_ack: false,
                        payload_json: String::new(),
                    }),
                })),
            },
        )
        .await;
        assert!(sent.is_ok());
        let routed = agent_receiver.recv().await;
        assert!(matches!(
            routed,
            Some(Ok(RelayMessage {
                payload: Some(RelayPayload::Control(ControlMessage { operation_id, .. }))
            })) if operation_id == "operation1"
        ));
    }
}
