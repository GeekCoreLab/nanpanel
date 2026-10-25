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
use tonic::transport::{Certificate, ClientTlsConfig, Endpoint, Identity};
use tonic::{Request, Response, Status};
use tracing::{info, warn};

use crate::v1::{
    AgentMessage, ControlMessage, CoreMessage,
    agent_gateway_server::{AgentGateway, AgentGatewayServer},
    agent_message::Payload,
    control_relay_client::ControlRelayClient,
    core_message::Payload as CorePayload,
    relay_message::Payload as RelayPayload,
};

#[derive(Clone)]
enum ActiveRoute {
    Direct(mpsc::Sender<ControlMessage>),
    Relay(mpsc::Sender<CoreMessage>),
}

#[derive(Clone)]
struct ActiveSession {
    session_id: String,
    route: ActiveRoute,
    last_seen: std::time::Instant,
}

type ActiveSessions = Arc<RwLock<HashMap<String, Vec<ActiveSession>>>>;

async fn register_active_session(
    sessions: &ActiveSessions,
    agent_id: &str,
    session_id: &str,
    route: ActiveRoute,
) {
    let mut active = sessions.write().await;
    let routes = active.entry(agent_id.to_owned()).or_default();
    if let Some(session) = routes
        .iter_mut()
        .find(|route| route.session_id == session_id)
    {
        session.route = route;
        session.last_seen = std::time::Instant::now();
    } else {
        routes.push(ActiveSession {
            session_id: session_id.to_owned(),
            route,
            last_seen: std::time::Instant::now(),
        });
    }
}

async fn remove_active_session(sessions: &ActiveSessions, agent_id: &str, session_id: &str) {
    let mut active = sessions.write().await;
    if let Some(routes) = active.get_mut(agent_id) {
        routes.retain(|route| route.session_id != session_id);
        if routes.is_empty() {
            active.remove(agent_id);
        }
    }
}

#[derive(Clone)]
struct RelayConfig {
    endpoints: Vec<String>,
    tls_domain: String,
    ca_path: String,
    cert_path: String,
    key_path: String,
    cluster_id: String,
    epoch: u64,
}

#[derive(Clone)]
pub struct Gateway {
    callback: reqwest::Client,
    callback_url: String,
    callback_token: String,
    core_id: String,
    sessions: ActiveSessions,
    relay: Option<RelayConfig>,
}

impl Gateway {
    fn from_env() -> anyhow::Result<Self> {
        let callback_url = required("NANPANEL_GATEWAY_CALLBACK_URL")?;
        let callback_token = required("NANPANEL_GATEWAY_CALLBACK_TOKEN")?;
        let relay = relay_config_from_env()?;
        Ok(Self {
            callback: reqwest::Client::builder()
                .timeout(Duration::from_secs(5))
                .build()?,
            callback_url,
            callback_token,
            core_id: uuid::Uuid::new_v4().to_string(),
            sessions: Arc::new(RwLock::new(HashMap::new())),
            relay,
        })
    }

    async fn start_relay_connectors(&self) -> anyhow::Result<()> {
        let Some(config) = self.relay.clone() else {
            return Ok(());
        };
        let ca = tokio::fs::read(&config.ca_path).await?;
        let cert = tokio::fs::read(&config.cert_path).await?;
        let key = tokio::fs::read(&config.key_path).await?;
        let tls = ClientTlsConfig::new()
            .domain_name(config.tls_domain.clone())
            .ca_certificate(Certificate::from_pem(ca))
            .identity(Identity::from_pem(cert, key));
        let endpoints = config
            .endpoints
            .iter()
            .map(|endpoint| Arc::<str>::from(endpoint.as_str()))
            .collect::<Vec<_>>();
        for endpoint in endpoints {
            let gateway = self.clone();
            let relay_config = config.clone();
            let endpoint_tls = tls.clone();
            tokio::spawn(async move {
                let mut backoff = std::time::Duration::from_secs(1);
                loop {
                    let lease = match gateway.acquire_core_lease(&relay_config.cluster_id).await {
                        Ok(lease) => lease,
                        Err(error) => {
                            warn!(%endpoint, %error, "unable to acquire Core Relay leader lease");
                            tokio::time::sleep(backoff).await;
                            backoff = backoff
                                .saturating_mul(2)
                                .min(std::time::Duration::from_secs(60));
                            continue;
                        }
                    };
                    let mut relay_config = relay_config.clone();
                    relay_config.epoch = lease.epoch;
                    match gateway
                        .run_relay_session(&relay_config, &endpoint, endpoint_tls.clone())
                        .await
                    {
                        Ok(()) => warn!(%endpoint, "Control Relay stream closed"),
                        Err(error) => warn!(%endpoint, %error, "Control Relay connection failed"),
                    }
                    tokio::time::sleep(backoff).await;
                    backoff = backoff
                        .saturating_mul(2)
                        .min(std::time::Duration::from_secs(60));
                }
            });
        }
        Ok(())
    }

    async fn acquire_core_lease(&self, cluster_id: &str) -> anyhow::Result<RelayLeaseReport> {
        let url = self.relay_callback_url("lease/acquire")?;
        let response = self
            .callback
            .post(url)
            .header("X-NanPanel-Gateway-Token", &self.callback_token)
            .json(&RelayLeaseRequest {
                owner_id: self.core_id.clone(),
                cluster_id: cluster_id.to_owned(),
                epoch: 0,
            })
            .send()
            .await?
            .error_for_status()?;
        let lease = response.json::<RelayLeaseReport>().await?;
        if lease.epoch == 0 || !(15..=120).contains(&lease.lease_seconds) {
            anyhow::bail!("Core returned an invalid Relay leader lease")
        }
        Ok(lease)
    }

    async fn renew_core_lease(&self, config: &RelayConfig) -> anyhow::Result<()> {
        let url = self.relay_callback_url("lease/renew")?;
        let response = self
            .callback
            .post(url)
            .header("X-NanPanel-Gateway-Token", &self.callback_token)
            .json(&RelayLeaseRequest {
                owner_id: self.core_id.clone(),
                cluster_id: config.cluster_id.clone(),
                epoch: config.epoch,
            })
            .send()
            .await?
            .error_for_status()?;
        let lease = response.json::<RelayLeaseReport>().await?;
        if lease.epoch != config.epoch || !(15..=120).contains(&lease.lease_seconds) {
            anyhow::bail!("Core returned a mismatched Relay leader lease")
        }
        Ok(())
    }

    fn relay_callback_url(&self, path: &str) -> anyhow::Result<String> {
        self.callback_url
            .strip_suffix("/internal/agent-heartbeats")
            .map(|prefix| format!("{prefix}/internal/agent-relay/{path}"))
            .ok_or_else(|| anyhow::anyhow!("invalid Gateway callback URL"))
    }

    async fn run_relay_session(
        &self,
        config: &RelayConfig,
        endpoint: &str,
        tls: ClientTlsConfig,
    ) -> anyhow::Result<()> {
        let channel = Endpoint::from_shared(endpoint.to_owned())?
            .connect_timeout(std::time::Duration::from_secs(10))
            .tcp_keepalive(Some(std::time::Duration::from_secs(30)))
            .keep_alive_while_idle(true)
            .tls_config(tls)?
            .connect()
            .await?;
        let mut client = ControlRelayClient::new(channel);
        let (sender, receiver) = mpsc::channel(8);
        sender
            .send(CoreMessage {
                payload: Some(CorePayload::Hello(crate::v1::RelayCoreHello {
                    cluster_id: config.cluster_id.clone(),
                    epoch: config.epoch,
                })),
            })
            .await?;
        let mut stream = client
            .core_session(ReceiverStream::new(receiver))
            .await?
            .into_inner();
        let session_id = uuid::Uuid::new_v4().to_string();
        let mut relay_id: Option<String> = None;
        let stream_result: anyhow::Result<()> = async {
            let mut hello_acknowledged = false;
            let mut status_tick = tokio::time::interval(std::time::Duration::from_secs(10));
            status_tick.tick().await;
            loop {
                tokio::select! {
                    received = stream.message() => {
                        let Some(message) = received? else { break; };
                        match message.payload {
                    Some(RelayPayload::HelloAck(ack)) => {
                        if hello_acknowledged
                            || ack.cluster_id != config.cluster_id
                            || ack.core_epoch != config.epoch
                            || ack.relay_id.is_empty()
                            || ack.max_frame_bytes == 0
                        {
                            anyhow::bail!(
                                "Control Relay returned an invalid hello acknowledgement"
                            )
                        }
                        hello_acknowledged = true;
                        relay_id = Some(ack.relay_id.clone());
                        info!(%endpoint, relay_id = %ack.relay_id, "Core established its outbound Relay path");
                        if let Err(error) = self.report_relay_status(
                            config, endpoint, &ack.relay_id, true
                        ).await {
                            warn!(%endpoint, %error, "unable to publish healthy Relay status to Core");
                        }
                    }
                    Some(RelayPayload::AgentMessage(relayed)) if hello_acknowledged => {
                        self.forward_relay_agent_message(&sender, &session_id, relayed)
                            .await?;
                    }
                    Some(RelayPayload::AgentMessage(_)) => {
                        anyhow::bail!("Control Relay sent Agent data before hello acknowledgement")
                    }
                    Some(RelayPayload::Control(_)) | None => {
                        anyhow::bail!("Control Relay sent an invalid Core stream message")
                    }
                }
                    }
                    _ = status_tick.tick(), if hello_acknowledged => {
                        self.renew_core_lease(config).await?;
                        if let Some(relay_id) = relay_id.as_deref()
                            && let Err(error) = self.report_relay_status(
                                config, endpoint, relay_id, true
                            ).await {
                            warn!(%endpoint, %error, "unable to refresh healthy Relay status");
                        }
                    }
                }
            }
            Ok(())
        }
        .await;
        let mut active = self.sessions.write().await;
        for routes in active.values_mut() {
            routes.retain(|route| route.session_id != session_id);
        }
        active.retain(|_, routes| !routes.is_empty());
        drop(active);
        if let Some(relay_id) = relay_id.as_deref()
            && let Err(error) = self
                .report_relay_status(config, endpoint, relay_id, false)
                .await
        {
            warn!(%endpoint, %error, "unable to publish disconnected Relay status");
        }
        stream_result
    }

    async fn report_relay_status(
        &self,
        config: &RelayConfig,
        endpoint: &str,
        relay_id: &str,
        healthy: bool,
    ) -> anyhow::Result<()> {
        let url = self
            .callback_url
            .strip_suffix("/internal/agent-heartbeats")
            .map(|prefix| format!("{prefix}/internal/agent-relay/status"))
            .ok_or_else(|| anyhow::anyhow!("invalid Gateway callback URL"))?;
        self.callback
            .post(url)
            .header("X-NanPanel-Gateway-Token", &self.callback_token)
            .json(&RelayStatusReport {
                relay_id: relay_id.to_owned(),
                cluster_id: config.cluster_id.clone(),
                epoch: config.epoch,
                endpoint: endpoint.to_owned(),
                healthy,
            })
            .send()
            .await?
            .error_for_status()?;
        Ok(())
    }

    async fn forward_relay_agent_message(
        &self,
        relay_sender: &mpsc::Sender<CoreMessage>,
        session_id: &str,
        relayed: crate::v1::RelayedAgentMessage,
    ) -> anyhow::Result<()> {
        let agent_id = relayed.agent_id;
        let fingerprint = relayed.certificate_fingerprint;
        let message = *relayed
            .message
            .ok_or_else(|| anyhow::anyhow!("Relay sent an empty Agent message"))?;
        if agent_id.is_empty()
            || agent_id.len() > 64
            || message.agent_id != agent_id
            || fingerprint.len() != 64
            || !fingerprint.bytes().all(|byte| byte.is_ascii_hexdigit())
        {
            anyhow::bail!("Relay sent an invalid Agent identity envelope")
        }
        match message.payload {
            Some(Payload::Heartbeat(heartbeat)) => {
                let report = HeartbeatReport {
                    agent_id: agent_id.clone(),
                    version: heartbeat.version,
                    certificate_fingerprint: fingerprint.clone(),
                    hostname: heartbeat.hostname,
                    os: heartbeat.os,
                    arch: heartbeat.arch,
                    capabilities: heartbeat.capabilities,
                    helper_status: heartbeat.helper_status,
                    systemd_observed_at_epoch_ms: heartbeat.systemd_observed_at_epoch_ms,
                    network_observed_at_epoch_ms: heartbeat.network_observed_at_epoch_ms,
                    network_interfaces: heartbeat
                        .network_interfaces
                        .into_iter()
                        .map(|interface| NetworkInterfaceReport {
                            name: interface.name,
                            mac_address: interface.mac_address,
                            mtu: interface.mtu,
                            state: interface.state,
                            addresses: interface
                                .addresses
                                .into_iter()
                                .map(|address| NetworkAddressReport {
                                    family: address.family,
                                    address: address.address,
                                    prefix_length: address.prefix_length,
                                    scope: address.scope,
                                })
                                .collect(),
                        })
                        .collect(),
                    network_routes: heartbeat
                        .network_routes
                        .into_iter()
                        .map(|route| NetworkRouteReport {
                            destination: route.destination,
                            gateway: route.gateway,
                            interface_name: route.interface_name,
                            protocol: route.protocol,
                            scope: route.scope,
                            route_type: route.route_type,
                            table: route.table,
                            metric: route.metric,
                        })
                        .collect(),
                    netbird_overlay_observed_at_epoch_ms: heartbeat
                        .netbird_overlay_observed_at_epoch_ms,
                    netbird_overlay: heartbeat.netbird_overlay.map(|overlay| {
                        NetbirdOverlayReport {
                            peer_count: overlay.peer_count,
                            connected_peer_count: overlay.connected_peer_count,
                            p2p_peer_count: overlay.p2p_peer_count,
                            relayed_peer_count: overlay.relayed_peer_count,
                        }
                    }),
                    netbird_peer_paths_observed_at_epoch_ms: heartbeat
                        .netbird_peer_paths_observed_at_epoch_ms,
                    netbird_peer_paths: heartbeat
                        .netbird_peer_paths
                        .into_iter()
                        .map(|peer| NetbirdPeerPathReport {
                            peer_name: peer.peer_name,
                            status: peer.status,
                            connection_type: peer.connection_type,
                        })
                        .collect(),
                    kubernetes_runtime: heartbeat.kubernetes_runtime.map(|runtime| {
                        KubernetesRuntimeReport {
                            detected: runtime.detected,
                            kubelet_running: runtime.kubelet_running,
                            api_server_local: runtime.api_server_local,
                            runtime_kind: runtime.runtime_kind,
                            observed_at_epoch_ms: runtime.observed_at_epoch_ms,
                        }
                    }),
                    systemd_services: heartbeat
                        .systemd_services
                        .into_iter()
                        .map(|service| SystemdServiceReport {
                            unit_name: service.unit_name,
                            display_name: service.display_name,
                            active_state: service.active_state,
                            sub_state: service.sub_state,
                        })
                        .collect(),
                };
                let response = self
                    .callback
                    .post(&self.callback_url)
                    .header("X-NanPanel-Gateway-Token", &self.callback_token)
                    .json(&report)
                    .send()
                    .await?;
                let allowed = response.status().is_success();
                if allowed {
                    register_active_session(
                        &self.sessions,
                        &agent_id,
                        session_id,
                        ActiveRoute::Relay(relay_sender.clone()),
                    )
                    .await;
                } else {
                    warn!(%agent_id, status = %response.status(), "Core rejected relayed Agent heartbeat");
                    remove_active_session(&self.sessions, &agent_id, session_id).await;
                }
                relay_sender
                    .send(CoreMessage {
                        payload: Some(CorePayload::AgentAuthorization(
                            crate::v1::RelayAgentAuthorization {
                                agent_id,
                                certificate_fingerprint: fingerprint,
                                allowed,
                            },
                        )),
                    })
                    .await?;
            }
            Some(Payload::OperationResult(result)) => {
                let result_url = self
                    .callback_url
                    .strip_suffix("/internal/agent-heartbeats")
                    .map(|prefix| format!("{prefix}/internal/agent-control-results"))
                    .ok_or_else(|| anyhow::anyhow!("invalid Gateway callback URL"))?;
                let report = OperationResultReport {
                    agent_id,
                    operation_id: result.operation_id,
                    idempotency_key: result.idempotency_key,
                    status: result.status,
                    detail: result.detail,
                };
                let response = self
                    .callback
                    .post(result_url)
                    .header("X-NanPanel-Gateway-Token", &self.callback_token)
                    .json(&report)
                    .send()
                    .await?;
                if response.status().is_success()
                    && response
                        .headers()
                        .get("X-NanPanel-Result-Ack")
                        .and_then(|value| value.to_str().ok())
                        == Some("true")
                {
                    relay_sender
                        .send(CoreMessage {
                            payload: Some(CorePayload::Acknowledge(crate::v1::RelayAcknowledge {
                                agent_id: report.agent_id,
                                operation_id: report.operation_id,
                                idempotency_key: report.idempotency_key,
                            })),
                        })
                        .await?;
                } else if !response.status().is_success() {
                    warn!(
                        operation_id = %report.operation_id,
                        status = %response.status(),
                        "Core rejected relayed Agent operation result"
                    );
                }
            }
            None => anyhow::bail!("Relay delivered an empty Agent payload"),
        }
        Ok(())
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
    systemd_observed_at_epoch_ms: i64,
    systemd_services: Vec<SystemdServiceReport>,
    network_observed_at_epoch_ms: i64,
    network_interfaces: Vec<NetworkInterfaceReport>,
    network_routes: Vec<NetworkRouteReport>,
    netbird_overlay_observed_at_epoch_ms: i64,
    netbird_overlay: Option<NetbirdOverlayReport>,
    netbird_peer_paths_observed_at_epoch_ms: i64,
    netbird_peer_paths: Vec<NetbirdPeerPathReport>,
    kubernetes_runtime: Option<KubernetesRuntimeReport>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct KubernetesRuntimeReport {
    detected: bool,
    kubelet_running: bool,
    api_server_local: bool,
    runtime_kind: String,
    observed_at_epoch_ms: i64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct NetbirdOverlayReport {
    peer_count: u32,
    connected_peer_count: u32,
    p2p_peer_count: u32,
    relayed_peer_count: u32,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct NetbirdPeerPathReport {
    peer_name: String,
    status: String,
    connection_type: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct NetworkInterfaceReport {
    name: String,
    mac_address: String,
    mtu: u32,
    state: String,
    addresses: Vec<NetworkAddressReport>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct NetworkAddressReport {
    family: String,
    address: String,
    prefix_length: u32,
    scope: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct NetworkRouteReport {
    destination: String,
    gateway: String,
    interface_name: String,
    protocol: String,
    scope: String,
    route_type: String,
    table: String,
    metric: u32,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SystemdServiceReport {
    unit_name: String,
    display_name: String,
    active_state: String,
    sub_state: String,
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

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct RelayLeaseRequest {
    owner_id: String,
    cluster_id: String,
    epoch: u64,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct RelayLeaseReport {
    epoch: u64,
    lease_seconds: u64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct RelayStatusReport {
    relay_id: String,
    cluster_id: String,
    epoch: u64,
    endpoint: String,
    healthy: bool,
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
    payload_json: Option<String>,
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
struct NetworkTopologyPayload {
    action: String,
    topology_id: String,
    revision_id: String,
    generation: u64,
    nodes: Vec<NetworkTopologyNode>,
    links: Vec<NetworkTopologyLink>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct NetworkTopologyNode {
    id: String,
    kind: String,
    switch_backend: Option<String>,
    firewall_policy: Option<NetworkFirewallPolicy>,
    router_configuration: Option<NetworkRouterConfiguration>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct NetworkRouterConfiguration {
    interfaces: Vec<NetworkRouterInterface>,
    routes: Vec<NetworkStaticRoute>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct NetworkRouterInterface {
    port: String,
    address: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct NetworkStaticRoute {
    destination: String,
    gateway: Option<String>,
    output_port: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct NetworkFirewallPolicy {
    default_action: String,
    rules: Vec<NetworkFirewallRule>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct NetworkFirewallRule {
    input_port: String,
    output_port: String,
    protocol: String,
    destination_port: u16,
    action: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct NetworkTopologyLink {
    id: String,
    source_node_id: String,
    source_port: String,
    target_node_id: String,
    target_port: String,
    layer: String,
    protocol: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct NginxRoutePayload {
    listen_address: String,
    listen_port: u16,
    server_name: String,
    health_path: String,
    backends: Vec<NginxRouteBackend>,
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
struct ContainerDeployPayload {
    image: String,
    host_port: Option<u16>,
    container_port: Option<u16>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct K3sPreflightPayload {
    role: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct K3sInstallPayload {
    role: String,
    version: String,
    server_url: Option<String>,
    token: String,
}

#[derive(Deserialize)]
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
        || request.target.len() > 255
        || request
            .payload_json
            .as_ref()
            .is_some_and(|payload| payload.len() > 48 * 1024)
        || !valid_dispatch_target(&request.capability, &request.target)
        || !valid_dispatch_request_payload(
            &request.capability,
            &request.target,
            request.payload_json.as_deref(),
        )
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
    let now = std::time::Instant::now();
    let mut routes = gateway
        .sessions
        .read()
        .await
        .get(&request.agent_id)
        .cloned()
        .unwrap_or_default()
        .into_iter()
        .filter(|session| now.duration_since(session.last_seen) <= Duration::from_secs(20))
        .collect::<Vec<_>>();
    routes.sort_by_key(|session| std::cmp::Reverse(session.last_seen));
    if routes.is_empty() {
        return Err(StatusCode::NOT_FOUND);
    }
    let agent_id = request.agent_id;
    let control = ControlMessage {
        operation_id: request.operation_id,
        idempotency_key: request.idempotency_key,
        capability: request.capability,
        target: request.target,
        deadline_epoch_ms: request.deadline_epoch_ms,
        operation_result_ack: false,
        payload_json: request.payload_json.unwrap_or_default(),
    };
    for session in routes {
        let result = match session.route {
            ActiveRoute::Direct(sender) => sender.send(control.clone()).await.map_err(|_| ()),
            ActiveRoute::Relay(sender) => sender
                .send(CoreMessage {
                    payload: Some(CorePayload::Control(crate::v1::RelayControl {
                        agent_id: agent_id.clone(),
                        control: Some(control.clone()),
                    })),
                })
                .await
                .map_err(|_| ()),
        };
        if result.is_ok() {
            return Ok(StatusCode::ACCEPTED);
        }
        remove_active_session(&gateway.sessions, &agent_id, &session.session_id).await;
    }
    Err(StatusCode::SERVICE_UNAVAILABLE)
}

fn valid_dispatch_target(capability: &str, target: &str) -> bool {
    match capability {
        "agent.update"
        | "package.install"
        | "container.list"
        | "k3s.preflight"
        | "k3s.install"
        | "k3s.upgrade"
        | "kubeedge.edgecore.join" => target.is_empty(),
        "nginx.route.set" => valid_nginx_route_target(target),
        "systemd.restart" | "systemd.journal.read" => valid_service_unit(target),
        "file.write.protected" => valid_file_target(target),
        "container.logs" | "container.start" | "container.stop" | "container.deploy" => {
            valid_container_target(target)
        }
        "network.firewall.expose" => valid_container_target(target),
        "network.topology.reconcile" => valid_topology_uuid(target),
        _ => false,
    }
}

fn valid_dispatch_payload(capability: &str, payload: Option<&str>) -> bool {
    match (capability, payload) {
        ("k3s.preflight", Some(json)) => serde_json::from_str::<K3sPreflightPayload>(json)
            .is_ok_and(|payload| matches!(payload.role.as_str(), "server" | "agent")),
        ("k3s.install", Some(json)) => serde_json::from_str::<K3sInstallPayload>(json)
            .is_ok_and(|payload| valid_k3s_install_payload(&payload)),
        ("k3s.upgrade", Some(json)) => {
            serde_json::from_str::<K3sUpgradePayload>(json).is_ok_and(|payload| {
                matches!(payload.role.as_str(), "server" | "agent")
                    && valid_k3s_version(&payload.version)
            })
        }
        ("kubeedge.edgecore.join", Some(json)) => serde_json::from_str::<KubeEdgeJoinPayload>(json)
            .is_ok_and(|payload| {
                valid_kubeedge_version(&payload.version)
                    && valid_cloud_core_endpoint(&payload.cloud_core_endpoint)
                    && valid_kubeedge_token(&payload.token)
            }),
        ("package.install" | "file.write.protected", Some(json)) => {
            serde_json::from_str::<serde_json::Value>(json).is_ok_and(|value| value.is_object())
        }
        ("network.firewall.expose", Some(json)) => {
            serde_json::from_str::<NetworkFirewallPayload>(json).is_ok_and(|payload| {
                valid_network_interface(&payload.interface_name)
                    && matches!(payload.protocol.as_str(), "tcp" | "udp")
                    && payload.port > 0
                    && (30..=300).contains(&payload.rollback_seconds)
            })
        }
        ("network.topology.reconcile", Some(json)) => {
            serde_json::from_str::<NetworkTopologyPayload>(json)
                .is_ok_and(valid_network_topology_payload)
        }
        ("container.deploy", Some(json)) => serde_json::from_str::<ContainerDeployPayload>(json)
            .is_ok_and(|payload| {
                valid_container_image(&payload.image)
                    && payload.host_port.is_some() == payload.container_port.is_some()
                    && payload.host_port.is_none_or(|port| port > 0)
                    && payload.container_port.is_none_or(|port| port > 0)
            }),
        ("nginx.route.set", Some(json)) => serde_json::from_str::<NginxRoutePayload>(json)
            .is_ok_and(|payload| valid_nginx_route_payload(&payload)),
        (
            "agent.update"
            | "systemd.restart"
            | "systemd.journal.read"
            | "container.list"
            | "container.logs"
            | "container.start"
            | "container.stop"
            | "container.deploy",
            None,
        ) => true,
        _ => false,
    }
}

fn valid_dispatch_request_payload(capability: &str, target: &str, payload: Option<&str>) -> bool {
    valid_dispatch_payload(capability, payload)
        && (capability != "network.topology.reconcile"
            || payload.is_some_and(|json| valid_topology_dispatch(target, json)))
}

pub(crate) fn valid_topology_dispatch(target: &str, json: &str) -> bool {
    valid_topology_uuid(target)
        && serde_json::from_str::<NetworkTopologyPayload>(json).is_ok_and(|payload| {
            payload.topology_id == target && valid_network_topology_payload(payload)
        })
}

fn valid_network_topology_payload(payload: NetworkTopologyPayload) -> bool {
    if !matches!(payload.action.as_str(), "APPLY" | "VERIFY" | "COMPENSATE")
        || !valid_topology_uuid(&payload.topology_id)
        || !valid_topology_uuid(&payload.revision_id)
        || payload.generation == 0
        || payload.nodes.is_empty()
        || payload.nodes.len() > 16
        || payload.links.len() > 64
    {
        return false;
    }
    let node_ids = payload
        .nodes
        .iter()
        .map(|node| node.id.as_str())
        .collect::<std::collections::HashSet<_>>();
    let total_router_routes = payload
        .nodes
        .iter()
        .filter_map(|node| node.router_configuration.as_ref())
        .map(|configuration| configuration.routes.len())
        .sum::<usize>();
    if node_ids.len() != payload.nodes.len()
        || total_router_routes > 64
        || payload.nodes.iter().any(|node| {
            !valid_topology_uuid(&node.id)
                || !matches!(
                    node.kind.as_str(),
                    "SWITCH" | "PACKET_TAP" | "FIREWALL" | "ROUTER"
                )
                || (node.kind == "SWITCH"
                    && node
                        .switch_backend
                        .as_deref()
                        .is_some_and(|backend| !matches!(backend, "BRIDGE" | "OPENVSWITCH")))
                || (node.kind != "SWITCH" && node.switch_backend.is_some())
                || (node.kind == "FIREWALL") != node.firewall_policy.is_some()
                || (node.kind == "ROUTER") != node.router_configuration.is_some()
        })
    {
        return false;
    }
    let uses_ovs = payload
        .nodes
        .iter()
        .any(|node| node.switch_backend.as_deref() == Some("OPENVSWITCH"));
    if uses_ovs
        && payload
            .nodes
            .iter()
            .any(|node| matches!(node.kind.as_str(), "FIREWALL" | "PACKET_TAP"))
    {
        return false;
    }
    if payload.nodes.iter().any(|node| {
        node.firewall_policy.as_ref().is_some_and(|policy| {
            !matches!(policy.default_action.as_str(), "ACCEPT" | "DROP") || policy.rules.len() > 64
        })
    }) {
        return false;
    }
    let mut link_ids = std::collections::HashSet::new();
    let mut ports = std::collections::HashSet::new();
    let mut connected_inputs =
        std::collections::HashMap::<&str, std::collections::HashSet<&str>>::new();
    let mut connected_outputs =
        std::collections::HashMap::<&str, std::collections::HashSet<&str>>::new();
    let mut connected_count = std::collections::HashMap::<&str, usize>::new();
    let links_valid = payload.links.iter().all(|link| {
        let valid = valid_topology_uuid(&link.id)
            && link_ids.insert(link.id.as_str())
            && link.source_node_id != link.target_node_id
            && node_ids.contains(link.source_node_id.as_str())
            && node_ids.contains(link.target_node_id.as_str())
            && valid_topology_port(&link.source_port, "out-")
            && valid_topology_port(&link.target_port, "in-")
            && link.layer == "L2"
            && link.protocol == "ETHERNET"
            && ports.insert((link.source_node_id.as_str(), link.source_port.as_str()))
            && ports.insert((link.target_node_id.as_str(), link.target_port.as_str()));
        if valid {
            connected_outputs
                .entry(link.source_node_id.as_str())
                .or_default()
                .insert(link.source_port.as_str());
            connected_inputs
                .entry(link.target_node_id.as_str())
                .or_default()
                .insert(link.target_port.as_str());
            *connected_count
                .entry(link.source_node_id.as_str())
                .or_default() += 1;
            *connected_count
                .entry(link.target_node_id.as_str())
                .or_default() += 1;
        }
        valid
    });
    if !links_valid {
        return false;
    }
    payload.nodes.iter().all(|node| match node.kind.as_str() {
        "SWITCH" => true,
        "PACKET_TAP" => {
            connected_count.get(node.id.as_str()) == Some(&3)
                && connected_inputs
                    .get(node.id.as_str())
                    .is_some_and(|ports| ports.contains("in-1"))
                && connected_outputs
                    .get(node.id.as_str())
                    .is_some_and(|ports| ports.contains("out-1") && ports.contains("out-2"))
        }
        "FIREWALL" => {
            let Some(policy) = &node.firewall_policy else {
                return false;
            };
            let Some(inputs) = connected_inputs.get(node.id.as_str()) else {
                return false;
            };
            let Some(outputs) = connected_outputs.get(node.id.as_str()) else {
                return false;
            };
            if inputs.is_empty() || outputs.is_empty() {
                return false;
            }
            let mut matches = std::collections::HashSet::new();
            policy.rules.iter().all(|rule| {
                inputs.contains(rule.input_port.as_str())
                    && outputs.contains(rule.output_port.as_str())
                    && matches!(rule.protocol.as_str(), "TCP" | "UDP" | "SCTP")
                    && rule.destination_port > 0
                    && matches!(rule.action.as_str(), "ACCEPT" | "DROP")
                    && matches.insert((
                        rule.input_port.as_str(),
                        rule.output_port.as_str(),
                        rule.protocol.as_str(),
                        rule.destination_port,
                    ))
            })
        }
        "ROUTER" => valid_router_configuration(
            node,
            connected_inputs.get(node.id.as_str()),
            connected_outputs.get(node.id.as_str()),
        ),
        _ => false,
    })
}

fn valid_router_configuration(
    node: &NetworkTopologyNode,
    inputs: Option<&std::collections::HashSet<&str>>,
    outputs: Option<&std::collections::HashSet<&str>>,
) -> bool {
    let Some(configuration) = &node.router_configuration else {
        return false;
    };
    if configuration.interfaces.is_empty()
        || configuration.interfaces.len() > 16
        || configuration.routes.len() > 64
    {
        return false;
    }
    let connected = inputs
        .into_iter()
        .flat_map(|ports| ports.iter().copied())
        .chain(outputs.into_iter().flat_map(|ports| ports.iter().copied()))
        .collect::<std::collections::HashSet<_>>();
    let mut interfaces = std::collections::HashMap::new();
    let mut connected_networks = Vec::new();
    let mut assigned_addresses = std::collections::HashSet::new();
    for interface in &configuration.interfaces {
        if !connected.contains(interface.port.as_str()) {
            return false;
        }
        let Some((network, prefix)) = ipv4_cidr(&interface.address) else {
            return false;
        };
        let Some((local, _)) = interface.address.split_once('/') else {
            return false;
        };
        let Some(local) = ipv4(local) else {
            return false;
        };
        let last = network | !ipv4_mask(prefix);
        if (prefix <= 30 && (local == network || local == last))
            || !assigned_addresses.insert(local)
            || connected_networks
                .iter()
                .any(|(other_network, other_prefix)| {
                    network <= (*other_network | !ipv4_mask(*other_prefix))
                        && *other_network <= last
                })
        {
            return false;
        }
        connected_networks.push((network, prefix));
        if interfaces
            .insert(interface.port.as_str(), (network, prefix, local))
            .is_some()
        {
            return false;
        }
    }
    if !connected.is_subset(&interfaces.keys().copied().collect()) {
        return false;
    }
    let output_ports = outputs
        .into_iter()
        .flat_map(|ports| ports.iter().copied())
        .collect::<std::collections::HashSet<_>>();
    let mut destinations = std::collections::HashSet::new();
    configuration.routes.iter().all(|route| {
        let Some((network, prefix)) = ipv4_cidr(&route.destination) else {
            return false;
        };
        let canonical_destination =
            route.destination == format!("{}/{}", ipv4_text(network), prefix);
        let Some(output) = interfaces.get(route.output_port.as_str()) else {
            return false;
        };
        if !canonical_destination
            || !output_ports.contains(route.output_port.as_str())
            || !destinations.insert(route.destination.as_str())
            || (route.destination != "0.0.0.0/0"
                && connected_networks
                    .iter()
                    .any(|(connected_network, connected_prefix)| {
                        network <= (*connected_network | !ipv4_mask(*connected_prefix))
                            && *connected_network <= (network | !ipv4_mask(prefix))
                    }))
        {
            return false;
        }
        route.gateway.as_deref().is_none_or(|gateway| {
            let Some(gateway) = ipv4(gateway) else {
                return false;
            };
            let last = output.0 | !ipv4_mask(output.1);
            gateway & ipv4_mask(output.1) == output.0
                && gateway != output.2
                && (output.1 > 30 || (gateway != output.0 && gateway != last))
        })
    })
}

fn ipv4_cidr(value: &str) -> Option<(u32, u8)> {
    let (address, prefix) = value.split_once('/')?;
    let address = ipv4(address)?;
    let prefix = prefix.parse::<u8>().ok().filter(|prefix| *prefix <= 32)?;
    Some((address & ipv4_mask(prefix), prefix))
}

fn ipv4(value: &str) -> Option<u32> {
    let address = value.parse::<std::net::Ipv4Addr>().ok()?;
    Some(u32::from_be_bytes(address.octets()))
}

fn ipv4_text(address: u32) -> std::net::Ipv4Addr {
    std::net::Ipv4Addr::from(address)
}

fn ipv4_mask(prefix: u8) -> u32 {
    if prefix == 0 {
        0
    } else {
        u32::MAX << (32 - prefix)
    }
}

fn valid_topology_uuid(value: &str) -> bool {
    uuid::Uuid::parse_str(value).is_ok_and(|id| id.hyphenated().to_string() == value)
}

fn valid_topology_port(value: &str, prefix: &str) -> bool {
    value.strip_prefix(prefix).is_some_and(|number| {
        number
            .parse::<u8>()
            .is_ok_and(|parsed| (1..=16).contains(&parsed) && parsed.to_string() == number)
    })
}

fn valid_k3s_install_payload(payload: &K3sInstallPayload) -> bool {
    let valid_version = payload
        .version
        .strip_prefix('v')
        .and_then(|release| release.split_once("+k3s"))
        .is_some_and(|(version, revision)| {
            let components = version.split('.').collect::<Vec<_>>();
            components.len() == 3
                && components
                    .iter()
                    .all(|part| !part.is_empty() && part.bytes().all(|b| b.is_ascii_digit()))
                && !revision.is_empty()
                && revision.bytes().all(|b| b.is_ascii_digit())
        });
    let valid_token = valid_k3s_token(&payload.token);
    let valid_server = payload
        .server_url
        .as_deref()
        .is_some_and(valid_k3s_server_url);
    valid_version
        && valid_token
        && match payload.role.as_str() {
            "server" => payload.server_url.is_none() && !payload.token.starts_with("K10"),
            "agent" => valid_server,
            _ => false,
        }
}

fn valid_k3s_version(version: &str) -> bool {
    version
        .strip_prefix('v')
        .and_then(|release| release.split_once("+k3s"))
        .is_some_and(|(version, revision)| {
            let components = version.split('.').collect::<Vec<_>>();
            components.len() == 3
                && components
                    .iter()
                    .all(|part| !part.is_empty() && part.bytes().all(|b| b.is_ascii_digit()))
                && !revision.is_empty()
                && revision.bytes().all(|b| b.is_ascii_digit())
        })
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

fn valid_k3s_token(token: &str) -> bool {
    (16..=512).contains(&token.len())
        && token
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.' | b':'))
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
    if !valid_address
        || payload.listen_port == 0
        || !valid_host
        || !valid_path
        || payload.backends.is_empty()
        || payload.backends.len() > 2
    {
        return false;
    }
    let mut names = std::collections::HashSet::new();
    let mut ports = std::collections::HashSet::new();
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
                                    systemd_observed_at_epoch_ms: heartbeat
                                        .systemd_observed_at_epoch_ms,
                                    network_observed_at_epoch_ms: heartbeat
                                        .network_observed_at_epoch_ms,
                                    network_interfaces: heartbeat
                                        .network_interfaces
                                        .into_iter()
                                        .map(|interface| NetworkInterfaceReport {
                                            name: interface.name,
                                            mac_address: interface.mac_address,
                                            mtu: interface.mtu,
                                            state: interface.state,
                                            addresses: interface
                                                .addresses
                                                .into_iter()
                                                .map(|address| NetworkAddressReport {
                                                    family: address.family,
                                                    address: address.address,
                                                    prefix_length: address.prefix_length,
                                                    scope: address.scope,
                                                })
                                                .collect(),
                                        })
                                        .collect(),
                                    network_routes: heartbeat
                                        .network_routes
                                        .into_iter()
                                        .map(|route| NetworkRouteReport {
                                            destination: route.destination,
                                            gateway: route.gateway,
                                            interface_name: route.interface_name,
                                            protocol: route.protocol,
                                            scope: route.scope,
                                            route_type: route.route_type,
                                            table: route.table,
                                            metric: route.metric,
                                        })
                                        .collect(),
                                    netbird_overlay_observed_at_epoch_ms: heartbeat
                                        .netbird_overlay_observed_at_epoch_ms,
                                    netbird_overlay: heartbeat.netbird_overlay.map(|overlay| {
                                        NetbirdOverlayReport {
                                            peer_count: overlay.peer_count,
                                            connected_peer_count: overlay.connected_peer_count,
                                            p2p_peer_count: overlay.p2p_peer_count,
                                            relayed_peer_count: overlay.relayed_peer_count,
                                        }
                                    }),
                                    netbird_peer_paths_observed_at_epoch_ms: heartbeat
                                        .netbird_peer_paths_observed_at_epoch_ms,
                                    netbird_peer_paths: heartbeat
                                        .netbird_peer_paths
                                        .into_iter()
                                        .map(|peer| NetbirdPeerPathReport {
                                            peer_name: peer.peer_name,
                                            status: peer.status,
                                            connection_type: peer.connection_type,
                                        })
                                        .collect(),
                                    kubernetes_runtime: heartbeat.kubernetes_runtime.map(
                                        |runtime| KubernetesRuntimeReport {
                                            detected: runtime.detected,
                                            kubelet_running: runtime.kubelet_running,
                                            api_server_local: runtime.api_server_local,
                                            runtime_kind: runtime.runtime_kind,
                                            observed_at_epoch_ms: runtime.observed_at_epoch_ms,
                                        },
                                    ),
                                    systemd_services: heartbeat
                                        .systemd_services
                                        .into_iter()
                                        .map(|service| SystemdServiceReport {
                                            unit_name: service.unit_name,
                                            display_name: service.display_name,
                                            active_state: service.active_state,
                                            sub_state: service.sub_state,
                                        })
                                        .collect(),
                                };
                                match callback
                                    .post(&callback_url)
                                    .header("X-NanPanel-Gateway-Token", &callback_token)
                                    .json(&report)
                                    .send()
                                    .await
                                {
                                    Ok(response) if response.status().is_success() => {
                                        register_active_session(
                                            &sessions,
                                            &report.agent_id,
                                            &session_id,
                                            ActiveRoute::Direct(sender.clone()),
                                        )
                                        .await;
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
                                        Ok(response) if response.status().is_success() => {
                                            let acknowledged = response
                                                .headers()
                                                .get("X-NanPanel-Result-Ack")
                                                .and_then(|value| value.to_str().ok())
                                                == Some("true");
                                            if acknowledged
                                                && let Err(error) = sender
                                                    .send(ControlMessage {
                                                        operation_id: report.operation_id.clone(),
                                                        idempotency_key: report
                                                            .idempotency_key
                                                            .clone(),
                                                        capability: String::new(),
                                                        target: String::new(),
                                                        deadline_epoch_ms: 0,
                                                        operation_result_ack: true,
                                                        payload_json: String::new(),
                                                    })
                                                    .await
                                            {
                                                warn!(
                                                    operation_id = %report.operation_id,
                                                    %error,
                                                    "unable to acknowledge Agent operation result"
                                                );
                                            }
                                        }
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
                remove_active_session(&sessions, &agent_id, &session_id).await;
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
    gateway.start_relay_connectors().await?;
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

fn relay_config_from_env() -> anyhow::Result<Option<RelayConfig>> {
    let value = match std::env::var("NANPANEL_GATEWAY_RELAY_ENDPOINTS") {
        Ok(value) => value,
        Err(std::env::VarError::NotPresent) => return Ok(None),
        Err(error) => return Err(error.into()),
    };
    let endpoints = parse_core_relay_endpoints(&value)?;
    Ok(Some(RelayConfig {
        endpoints,
        tls_domain: required("NANPANEL_GATEWAY_RELAY_TLS_DOMAIN")?,
        ca_path: required("NANPANEL_GATEWAY_RELAY_CA")?,
        cert_path: required("NANPANEL_GATEWAY_RELAY_CERT")?,
        key_path: required("NANPANEL_GATEWAY_RELAY_KEY")?,
        cluster_id: required("NANPANEL_GATEWAY_CLUSTER_ID")?,
        epoch: 0,
    }))
}

fn parse_core_relay_endpoints(value: &str) -> anyhow::Result<Vec<String>> {
    let mut endpoints = Vec::new();
    for raw in value
        .split(',')
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        let parsed = reqwest::Url::parse(raw)?;
        if parsed.scheme() != "https"
            || parsed.host_str().is_none()
            || !parsed.username().is_empty()
            || parsed.password().is_some()
            || parsed.path() != "/"
            || parsed.query().is_some()
            || parsed.fragment().is_some()
        {
            anyhow::bail!("Core Relay endpoints must be HTTPS origins without paths or credentials")
        }
        let endpoint = parsed.origin().ascii_serialization();
        if endpoints.contains(&endpoint) {
            anyhow::bail!("Core Relay endpoints must be unique")
        }
        endpoints.push(endpoint);
    }
    if !(2..=8).contains(&endpoints.len()) {
        anyhow::bail!("Core Relay configuration requires between two and eight endpoints")
    }
    Ok(endpoints)
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

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn agent_keeps_distinct_relay_routes_and_removes_only_the_disconnected_one() {
        let sessions = Arc::new(RwLock::new(HashMap::new()));
        let (relay_a, _receiver_a) = mpsc::channel(1);
        let (relay_b, _receiver_b) = mpsc::channel(1);
        register_active_session(
            &sessions,
            "agent-1",
            "relay-session-a",
            ActiveRoute::Relay(relay_a),
        )
        .await;
        register_active_session(
            &sessions,
            "agent-1",
            "relay-session-b",
            ActiveRoute::Relay(relay_b),
        )
        .await;
        assert_eq!(sessions.read().await["agent-1"].len(), 2);

        remove_active_session(&sessions, "agent-1", "relay-session-a").await;
        let active = sessions.read().await;
        let remaining = &active["agent-1"];
        assert_eq!(remaining.len(), 1);
        assert_eq!(remaining[0].session_id, "relay-session-b");
    }

    #[test]
    fn dispatch_only_accepts_bounded_firewall_requests() {
        assert!(valid_dispatch_target("network.firewall.expose", "ssh-test"));
        assert!(!valid_dispatch_target(
            "network.firewall.expose",
            "ssh-test;id"
        ));
        assert!(valid_dispatch_payload(
            "network.firewall.expose",
            Some(r#"{"interfaceName":"eth0","protocol":"tcp","port":2222,"rollbackSeconds":90}"#),
        ));
        assert!(!valid_dispatch_payload(
            "network.firewall.expose",
            Some(r#"{"interfaceName":"eth0","protocol":"tcp","port":2222,"rollbackSeconds":301}"#),
        ));
        assert!(!valid_dispatch_payload(
            "network.firewall.expose",
            Some(
                r#"{"interfaceName":"eth0","protocol":"tcp","port":2222,"rollbackSeconds":90,"command":"id"}"#
            ),
        ));
        assert!(!valid_dispatch_payload("network.firewall.expose", None));
        assert!(!valid_dispatch_payload(
            "network.firewall.expose",
            Some(r#"{"interfaceName":"-eth0","protocol":"tcp","port":2222,"rollbackSeconds":90}"#),
        ));
        assert!(valid_dispatch_payload(
            "container.deploy",
            Some(r#"{"image":"nginx:1.27","hostPort":8080,"containerPort":80}"#),
        ));
        assert!(!valid_dispatch_payload(
            "container.deploy",
            Some(r#"{"image":"--privileged","hostPort":8080,"containerPort":80}"#),
        ));
        assert!(!valid_dispatch_payload(
            "container.deploy",
            Some(r#"{"image":"./nginx","hostPort":8080,"containerPort":80}"#),
        ));
        assert!(valid_dispatch_payload(
            "k3s.install",
            Some(
                r#"{"role":"agent","version":"v1.36.5+k3s1","serverUrl":"https://k3s.example.net:6443/","token":"K10aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa::server:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}"#
            ),
        ));
        assert!(!valid_dispatch_payload(
            "k3s.install",
            Some(
                r#"{"role":"agent","version":"v1.36.5+k3s1","serverUrl":"https://127.0.0.1:6443","token":"K10aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa::server:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}"#
            ),
        ));
        assert!(valid_dispatch_payload(
            "k3s.upgrade",
            Some(r#"{"role":"server","version":"v1.37.1+k3s1"}"#),
        ));
        assert!(!valid_dispatch_payload(
            "k3s.upgrade",
            Some(r#"{"role":"server","version":"latest","command":"id"}"#),
        ));
        assert!(valid_dispatch_target("kubeedge.edgecore.join", ""));
        assert!(valid_dispatch_payload(
            "kubeedge.edgecore.join",
            Some(
                r#"{"version":"v1.23.1","cloudCoreEndpoint":"cloud.example.test:10000","token":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.cccccccccccccccccccccccccccccccccccccccc"}"#
            ),
        ));
        assert!(!valid_dispatch_payload(
            "kubeedge.edgecore.join",
            Some(
                r#"{"version":"v1.23.1","cloudCoreEndpoint":"cloud.example.test:10000","token":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.cccccccccccccccccccccccccccccccccccccccc","command":"id"}"#
            ),
        ));
        assert!(!valid_dispatch_payload(
            "kubeedge.edgecore.join",
            Some(
                r#"{"version":"v1.23.1","cloudCoreEndpoint":"cloud.example.test:10000;id","token":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.cccccccccccccccccccccccccccccccccccccccc"}"#
            ),
        ));
        assert!(valid_dispatch_target(
            "nginx.route.set",
            "nanpanel-route0123456789abcdef0123456789abcdef"
        ));
        assert!(!valid_dispatch_target("nginx.route.set", "customer-site"));
        assert!(valid_dispatch_payload(
            "nginx.route.set",
            Some(
                r#"{"listenAddress":"127.0.0.1","listenPort":18080,"serverName":"app.example.test","healthPath":"/health","backends":[{"name":"stable","port":20080,"weight":100},{"name":"canary","port":20081,"weight":0}]}"#
            ),
        ));
        assert!(!valid_dispatch_payload(
            "nginx.route.set",
            Some(
                r#"{"listenAddress":"0.0.0.0","listenPort":18080,"serverName":"app.example.test","healthPath":"/health","backends":[{"name":"stable","port":20080,"weight":100}]}"#
            ),
        ));
    }

    #[test]
    fn topology_dispatch_accepts_only_bounded_bridge_graphs_for_the_named_topology() {
        let topology = "11111111-1111-4111-8111-111111111111";
        let payload = r#"{
          "action":"APPLY",
          "topologyId":"11111111-1111-4111-8111-111111111111",
          "revisionId":"22222222-2222-4222-8222-222222222222",
          "generation":3,
          "nodes":[
            {"id":"33333333-3333-4333-8333-333333333333","kind":"SWITCH"},
            {"id":"44444444-4444-4444-8444-444444444444","kind":"SWITCH"}
          ],
          "links":[{
            "id":"55555555-5555-4555-8555-555555555555",
            "sourceNodeId":"33333333-3333-4333-8333-333333333333",
            "sourcePort":"out-1",
            "targetNodeId":"44444444-4444-4444-8444-444444444444",
            "targetPort":"in-1",
            "layer":"L2",
            "protocol":"ETHERNET"
          }]
        }"#;
        assert!(valid_topology_dispatch(topology, payload));
        let ovs_payload = payload.replace(
            "\"kind\":\"SWITCH\"",
            "\"kind\":\"SWITCH\",\"switchBackend\":\"OPENVSWITCH\"",
        );
        assert!(valid_topology_dispatch(topology, &ovs_payload));
        assert!(!valid_topology_dispatch(
            topology,
            &ovs_payload.replace("OPENVSWITCH", "UNSUPPORTED")
        ));
        assert!(!valid_topology_dispatch(
            "66666666-6666-4666-8666-666666666666",
            payload
        ));
        assert!(!valid_topology_dispatch(
            topology,
            &payload.replace("SWITCH", "ROUTER")
        ));
        assert!(!valid_topology_dispatch(
            topology,
            &payload.replace("out-1", "--help")
        ));
        assert!(!valid_topology_dispatch(
            topology,
            &payload.replace("out-1", "out-01")
        ));
        assert!(!valid_topology_dispatch(
            topology,
            &payload.replace("\"generation\":3", "\"generation\":3,\"command\":\"id\"")
        ));
    }

    #[test]
    fn topology_dispatch_accepts_only_connected_firewall_rules() {
        let topology = "11111111-1111-4111-8111-111111111111";
        let payload = r#"{
          "action":"APPLY",
          "topologyId":"11111111-1111-4111-8111-111111111111",
          "revisionId":"22222222-2222-4222-8222-222222222222",
          "generation":3,
          "nodes":[
            {"id":"33333333-3333-4333-8333-333333333333","kind":"SWITCH"},
            {"id":"44444444-4444-4444-8444-444444444444","kind":"FIREWALL",
             "firewallPolicy":{"defaultAction":"DROP","rules":[
               {"inputPort":"in-1","outputPort":"out-1","protocol":"TCP",
                "destinationPort":8080,"action":"ACCEPT"}]}},
            {"id":"66666666-6666-4666-8666-666666666666","kind":"SWITCH"}
          ],
          "links":[
            {"id":"55555555-5555-4555-8555-555555555555",
             "sourceNodeId":"33333333-3333-4333-8333-333333333333","sourcePort":"out-1",
             "targetNodeId":"44444444-4444-4444-8444-444444444444","targetPort":"in-1",
             "layer":"L2","protocol":"ETHERNET"},
            {"id":"77777777-7777-4777-8777-777777777777",
             "sourceNodeId":"44444444-4444-4444-8444-444444444444","sourcePort":"out-1",
             "targetNodeId":"66666666-6666-4666-8666-666666666666","targetPort":"in-1",
             "layer":"L2","protocol":"ETHERNET"}
          ]
        }"#;
        assert!(valid_topology_dispatch(topology, payload));
        assert!(!valid_topology_dispatch(
            topology,
            &payload.replace(
                "\"kind\":\"SWITCH\"",
                "\"kind\":\"SWITCH\",\"switchBackend\":\"OPENVSWITCH\""
            )
        ));
        assert!(!valid_topology_dispatch(
            topology,
            &payload.replace("\"inputPort\":\"in-1\"", "\"inputPort\":\"in-2\"")
        ));
        assert!(!valid_topology_dispatch(
            topology,
            &payload.replace("\"action\":\"ACCEPT\"", "\"action\":\"EXEC\"")
        ));
        assert!(!valid_topology_dispatch(
            topology,
            &payload.replace("\"defaultAction\":\"DROP\"", "\"defaultAction\":\"LOG\"")
        ));
        assert!(!valid_topology_dispatch(
            topology,
            &payload.replace(
                "\"kind\":\"FIREWALL\",",
                "\"kind\":\"FIREWALL\",\"command\":\"id\","
            )
        ));
    }

    #[test]
    fn topology_dispatch_validates_multiple_router_tables_and_ipv4_routes() {
        let topology = "11111111-1111-4111-8111-111111111111";
        let payload = r#"{
          "action":"APPLY","topologyId":"11111111-1111-4111-8111-111111111111",
          "revisionId":"22222222-2222-4222-8222-222222222222","generation":4,
          "nodes":[
            {"id":"33333333-3333-4333-8333-333333333333","kind":"SWITCH"},
            {"id":"44444444-4444-4444-8444-444444444444","kind":"ROUTER",
             "routerConfiguration":{"interfaces":[
               {"port":"in-1","address":"198.18.1.1/24"},
               {"port":"out-1","address":"198.18.2.1/24"}],
              "routes":[{"destination":"198.18.3.0/24","gateway":"198.18.2.2","outputPort":"out-1"}]}},
            {"id":"66666666-6666-4666-8666-666666666666","kind":"SWITCH"},
            {"id":"88888888-8888-4888-8888-888888888888","kind":"SWITCH"},
            {"id":"99999999-9999-4999-8999-999999999999","kind":"ROUTER",
             "routerConfiguration":{"interfaces":[
               {"port":"in-1","address":"198.18.4.1/24"},
               {"port":"out-1","address":"198.18.5.1/24"}],
              "routes":[{"destination":"198.18.3.0/24","gateway":"198.18.5.2","outputPort":"out-1"}]}},
            {"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","kind":"SWITCH"}
          ],
          "links":[
            {"id":"55555555-5555-4555-8555-555555555555",
             "sourceNodeId":"33333333-3333-4333-8333-333333333333","sourcePort":"out-1",
             "targetNodeId":"44444444-4444-4444-8444-444444444444","targetPort":"in-1",
             "layer":"L2","protocol":"ETHERNET"},
            {"id":"77777777-7777-4777-8777-777777777777",
             "sourceNodeId":"44444444-4444-4444-8444-444444444444","sourcePort":"out-1",
             "targetNodeId":"66666666-6666-4666-8666-666666666666","targetPort":"in-1",
             "layer":"L2","protocol":"ETHERNET"},
            {"id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
             "sourceNodeId":"88888888-8888-4888-8888-888888888888","sourcePort":"out-1",
             "targetNodeId":"99999999-9999-4999-8999-999999999999","targetPort":"in-1",
             "layer":"L2","protocol":"ETHERNET"},
            {"id":"cccccccc-cccc-4ccc-8ccc-cccccccccccc",
             "sourceNodeId":"99999999-9999-4999-8999-999999999999","sourcePort":"out-1",
             "targetNodeId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","targetPort":"in-1",
             "layer":"L2","protocol":"ETHERNET"}
          ]
        }"#;
        assert!(valid_topology_dispatch(topology, payload));
        assert!(!valid_topology_dispatch(
            topology,
            &payload.replace("198.18.2.2", "198.18.4.2")
        ));
        assert!(!valid_topology_dispatch(
            topology,
            &payload.replace("198.18.2.2", "198.18.2.255")
        ));
        assert!(!valid_topology_dispatch(
            topology,
            &payload.replace("198.18.1.1/24", "198.18.2.3/24")
        ));
        assert!(!valid_topology_dispatch(
            topology,
            &payload.replace("\"kind\":\"SWITCH\"", "\"kind\":\"ROUTER\"")
        ));
    }

    #[test]
    fn core_relay_pool_requires_distinct_https_origins() {
        assert_eq!(
            parse_core_relay_endpoints("https://relay-a.example, https://relay-b.example:9443")
                .ok(),
            Some(vec![
                "https://relay-a.example".to_owned(),
                "https://relay-b.example:9443".to_owned(),
            ])
        );
        assert!(parse_core_relay_endpoints("https://relay-a.example").is_err());
        assert!(
            parse_core_relay_endpoints("https://relay-a.example,https://RELAY-A.example:443/")
                .is_err()
        );
        assert!(
            parse_core_relay_endpoints("https://relay-a.example/control,https://relay-b.example")
                .is_err()
        );
    }
}
