use std::pin::Pin;
use std::time::Duration;

use futures_core::Stream;
use serde::Serialize;
use sha2::{Digest, Sha256};
use tokio::sync::mpsc;
use tokio_stream::wrappers::ReceiverStream;
use tonic::{Request, Response, Status};
use tracing::{info, warn};

use crate::v1::{
    AgentMessage, ControlMessage,
    agent_gateway_server::{AgentGateway, AgentGatewayServer},
    agent_message::Payload,
};

#[derive(Clone)]
pub struct Gateway {
    callback: reqwest::Client,
    callback_url: String,
    callback_token: String,
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
        let callback = self.callback.clone();
        let callback_url = self.callback_url.clone();
        let callback_token = self.callback_token.clone();
        tokio::spawn(async move {
            let _sender_keeps_control_stream_open = sender;
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
                                };
                                match callback
                                    .post(&callback_url)
                                    .header("X-NanPanel-Gateway-Token", &callback_token)
                                    .json(&report)
                                    .send()
                                    .await
                                {
                                    Ok(response) if response.status().is_success() => {}
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
                            Some(Payload::OperationResult(result)) => info!(
                                remote = ?remote,
                                agent_id = %message.agent_id,
                                operation_id = %result.operation_id,
                                status = %result.status,
                                "agent operation result"
                            ),
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
        });
        Ok(Response::new(Box::pin(ReceiverStream::new(receiver))))
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
    info!(%address, "starting mTLS Agent Gateway");
    let serving = tonic::transport::Server::builder()
        .tls_config(tls)?
        .add_service(AgentGatewayServer::new(gateway))
        .serve_with_shutdown(address, shutdown_signal());
    match tokio::time::timeout(Duration::from_secs(2), serving).await {
        Ok(result) => result?,
        Err(_) => warn!("Agent Gateway shutdown deadline reached; closing active streams"),
    }
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
