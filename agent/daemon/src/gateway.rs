use std::pin::Pin;
use std::{collections::HashMap, sync::Arc, time::Duration};

use axum::{
    Json, Router,
    extract::State,
    http::{HeaderMap, StatusCode},
    routing::post,
};
use futures_core::Stream;
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::sync::{RwLock, mpsc};
use tokio_stream::wrappers::ReceiverStream;
use tonic::{Request, Response, Status};
use tracing::{info, warn};

use crate::v1::{
    AgentMessage, ControlMessage,
    agent_gateway_server::{AgentGateway, AgentGatewayServer},
    agent_message::Payload,
};

type ActiveSession = (String, mpsc::Sender<ControlMessage>);
type ActiveSessions = Arc<RwLock<HashMap<String, ActiveSession>>>;

#[derive(Clone)]
pub struct Gateway {
    callback: reqwest::Client,
    callback_url: String,
    callback_token: String,
    sessions: ActiveSessions,
}

impl Gateway {
    fn from_env() -> anyhow::Result<Self> {
        let callback_url = required("NANPANEL_GATEWAY_CALLBACK_URL")?;
        let callback_token = required("NANPANEL_GATEWAY_CALLBACK_TOKEN")?;
        Ok(Self {
            callback: reqwest::Client::builder()
                .timeout(Duration::from_secs(5))
                .build()?,
            callback_url,
            callback_token,
            sessions: Arc::new(RwLock::new(HashMap::new())),
        })
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct HeartbeatReport {
    agent_id: String,
    version: String,
    certificate_fingerprint: String,
    hostname: String,
    os: String,
    arch: String,
    capabilities: Vec<String>,
    helper_status: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct OperationResultReport {
    agent_id: String,
    operation_id: String,
    idempotency_key: String,
    status: String,
    detail: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct DispatchRequest {
    agent_id: String,
    operation_id: String,
    idempotency_key: String,
    capability: String,
    target: String,
    deadline_epoch_ms: i64,
}

async fn dispatch_control(
    State(gateway): State<Gateway>,
    headers: HeaderMap,
    Json(request): Json<DispatchRequest>,
) -> Result<StatusCode, StatusCode> {
    let supplied = headers
        .get("X-NanPanel-Gateway-Token")
        .and_then(|value| value.to_str().ok())
        .ok_or(StatusCode::UNAUTHORIZED)?;
    if !constant_time_equal(supplied.as_bytes(), gateway.callback_token.as_bytes()) {
        return Err(StatusCode::UNAUTHORIZED);
    }
    if request.agent_id.is_empty()
        || request.agent_id.len() > 64
        || request.operation_id.is_empty()
        || request.operation_id.len() > 128
        || request.idempotency_key.is_empty()
        || request.idempotency_key.len() > 128
        || request.capability != "agent.update"
        || !request.target.is_empty()
    {
        return Err(StatusCode::BAD_REQUEST);
    }
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?
        .as_millis() as i64;
    if request.deadline_epoch_ms <= now || request.deadline_epoch_ms > now + 10 * 60 * 1000 {
        return Err(StatusCode::BAD_REQUEST);
    }
    let sessions = gateway.sessions.read().await;
    let Some((_, sender)) = sessions.get(&request.agent_id) else {
        return Err(StatusCode::NOT_FOUND);
    };
    sender
        .send(ControlMessage {
            operation_id: request.operation_id,
            idempotency_key: request.idempotency_key,
            capability: request.capability,
            target: request.target,
            deadline_epoch_ms: request.deadline_epoch_ms,
        })
        .await
        .map_err(|_| StatusCode::SERVICE_UNAVAILABLE)?;
    Ok(StatusCode::ACCEPTED)
}

fn constant_time_equal(left: &[u8], right: &[u8]) -> bool {
    left.len() == right.len()
        && left
            .iter()
            .zip(right)
            .fold(0_u8, |difference, (a, b)| difference | (a ^ b))
            == 0
}

#[tonic::async_trait]
impl AgentGateway for Gateway {
    type SessionStream =
        Pin<Box<dyn Stream<Item = Result<ControlMessage, Status>> + Send + 'static>>;

    async fn session(
        &self,
        request: Request<tonic::Streaming<AgentMessage>>,
    ) -> Result<Response<Self::SessionStream>, Status> {
        let remote = request.remote_addr();
        let certificate_fingerprint = request
            .peer_certs()
            .and_then(|certificates| certificates.first().cloned())
            .map(|certificate| hex::encode(Sha256::digest(certificate.as_ref())))
            .ok_or_else(|| Status::unauthenticated("client certificate is required"))?;
        let mut inbound = request.into_inner();
        let (sender, receiver) = mpsc::channel(8);
        let session_id = uuid::Uuid::new_v4().to_string();
        let sessions = Arc::clone(&self.sessions);
        let callback = self.callback.clone();
        let callback_url = self.callback_url.clone();
        let callback_token = self.callback_token.clone();
        let result_callback_url = callback_url
            .strip_suffix("/internal/agent-heartbeats")
            .map(|prefix| format!("{prefix}/internal/agent-control-results"));
        tokio::spawn(async move {
            let _sender_keeps_control_stream_open = sender.clone();
            let mut registered_agent_id = None;
            let mut last_sequence = 0_u64;
            loop {
                match inbound.message().await {
                    Ok(Some(message)) => {
                        if message.agent_id.is_empty() || message.sequence <= last_sequence {
                            warn!(remote = ?remote, "rejected invalid or replayed agent message");
                            break;
                        }
                        last_sequence = message.sequence;
                        match message.payload {
                            Some(Payload::Heartbeat(heartbeat)) => {
                                info!(
                                    remote = ?remote,
                                    agent_id = %message.agent_id,
                                    sequence = message.sequence,
                                    version = %heartbeat.version,
                                    "agent heartbeat"
                                );
                                let report = HeartbeatReport {
                                    agent_id: message.agent_id,
                                    version: heartbeat.version,
                                    certificate_fingerprint: certificate_fingerprint.clone(),
                                    hostname: heartbeat.hostname,
                                    os: heartbeat.os,
                                    arch: heartbeat.arch,
                                    capabilities: heartbeat.capabilities,
                                    helper_status: heartbeat.helper_status,
                                };
                                match callback
                                    .post(&callback_url)
                                    .header("X-NanPanel-Gateway-Token", &callback_token)
                                    .json(&report)
                                    .send()
                                    .await
                                {
                                    Ok(response) if response.status().is_success() => {
                                        let mut active = sessions.write().await;
                                        active.insert(
                                            report.agent_id.clone(),
                                            (session_id.clone(), sender.clone()),
                                        );
                                        registered_agent_id = Some(report.agent_id.clone());
                                    }
                                    Ok(response) => warn!(
                                        agent_id = %report.agent_id,
                                        status = %response.status(),
                                        "Core rejected Agent heartbeat"
                                    ),
                                    Err(error) => warn!(
                                        agent_id = %report.agent_id,
                                        %error,
                                        "unable to forward Agent heartbeat to Core"
                                    ),
                                }
                            }
                            Some(Payload::OperationResult(result)) => {
                                info!(
                                    remote = ?remote,
                                    agent_id = %message.agent_id,
                                    operation_id = %result.operation_id,
                                    status = %result.status,
                                    "agent operation result"
                                );
                                if let Some(result_url) = &result_callback_url {
                                    let report = OperationResultReport {
                                        agent_id: message.agent_id,
                                        operation_id: result.operation_id,
                                        idempotency_key: result.idempotency_key,
                                        status: result.status,
                                        detail: result.detail,
                                    };
                                    match callback
                                        .post(result_url)
                                        .header("X-NanPanel-Gateway-Token", &callback_token)
                                        .json(&report)
                                        .send()
                                        .await
                                    {
                                        Ok(response) if response.status().is_success() => {}
                                        Ok(response) => warn!(
                                            operation_id = %report.operation_id,
                                            status = %response.status(),
                                            "Core rejected Agent operation result"
                                        ),
                                        Err(error) => warn!(
                                            operation_id = %report.operation_id,
                                            %error,
                                            "unable to forward Agent operation result to Core"
                                        ),
                                    }
                                }
                            }
                            None => {
                                warn!(remote = ?remote, "agent sent an empty message");
                                break;
                            }
                        }
                    }
                    Ok(None) => break,
                    Err(error) => {
                        warn!(remote = ?remote, %error, "agent stream ended with error");
                        break;
                    }
                }
            }
            if let Some(agent_id) = registered_agent_id {
                let mut active = sessions.write().await;
                if active
                    .get(&agent_id)
                    .is_some_and(|(active_session, _)| active_session == &session_id)
                {
                    active.remove(&agent_id);
                }
            }
        });
        Ok(Response::new(Box::pin(
            ReceiverStream::new(receiver).map(Ok),
        )))
    }
}

pub async fn serve_from_env() -> anyhow::Result<()> {
    let address = std::env::var("NANPANEL_GATEWAY_LISTEN")
        .unwrap_or_else(|_| "0.0.0.0:50051".to_owned())
        .parse()?;
    let ca = tokio::fs::read(required("NANPANEL_GATEWAY_CLIENT_CA")?).await?;
    let cert = tokio::fs::read(required("NANPANEL_GATEWAY_CERT")?).await?;
    let key = tokio::fs::read(required("NANPANEL_GATEWAY_KEY")?).await?;
    let identity = tonic::transport::Identity::from_pem(cert, key);
    let tls = tonic::transport::ServerTlsConfig::new()
        .identity(identity)
        .client_ca_root(tonic::transport::Certificate::from_pem(ca));
    let gateway = Gateway::from_env()?;
    let control_address: std::net::SocketAddr = std::env::var("NANPANEL_GATEWAY_CONTROL_LISTEN")
        .unwrap_or_else(|_| "127.0.0.1:50052".to_owned())
        .parse()?;
    let control_listener = tokio::net::TcpListener::bind(control_address).await?;
    let control_state = gateway.clone();
    let control_server = tokio::spawn(async move {
        axum::serve(
            control_listener,
            Router::new()
                .route("/control", post(dispatch_control))
                .with_state(control_state),
        )
        .await
    });
    info!(%address, "starting mTLS Agent Gateway");
    let result = tonic::transport::Server::builder()
        .tls_config(tls)?
        .add_service(AgentGatewayServer::new(gateway))
        .serve_with_shutdown(address, shutdown_signal())
        .await;
    control_server.abort();
    result?;
    Ok(())
}

async fn shutdown_signal() {
    #[cfg(unix)]
    {
        use tokio::signal::unix::{SignalKind, signal};
        let Ok(mut terminate) = signal(SignalKind::terminate()) else {
            warn!("unable to install SIGTERM handler; waiting for Ctrl-C");
            let _ = tokio::signal::ctrl_c().await;
            return;
        };
        tokio::select! {
            _ = tokio::signal::ctrl_c() => {},
            _ = terminate.recv() => {},
        }
    }
    #[cfg(not(unix))]
    if let Err(error) = tokio::signal::ctrl_c().await {
        warn!(%error, "unable to install Ctrl-C handler");
    }
    info!("mTLS Agent Gateway shutting down gracefully");
}

fn required(name: &str) -> anyhow::Result<String> {
    std::env::var(name).map_err(|_| anyhow::anyhow!("missing required environment variable {name}"))
}
