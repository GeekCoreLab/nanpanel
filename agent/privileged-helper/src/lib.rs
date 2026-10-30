use std::{
    collections::HashSet,
    ffi::CString,
    fs::File,
    io::{Read, Take},
    os::{
        fd::{AsRawFd, FromRawFd},
        unix::fs::{FileTypeExt, PermissionsExt},
    },
    path::{Component, Path},
    process::Stdio,
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use anyhow::{Context, Result, bail};
use futures_util::{SinkExt, StreamExt};
use nix::sys::socket::{getsockopt, sockopt::PeerCredentials};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tokio::io::AsyncReadExt;
use tokio::net::{UnixListener, UnixStream};
use tokio_util::codec::{Framed, LinesCodec};

mod backup;
mod cron_jobs;
mod metrics;
mod nginx_routes;
mod topology;
pub use backup::BackupConfig;
pub use cron_jobs::CronConfig;
pub use metrics::MetricsConfig;
pub use nginx_routes::NginxRouteConfig;

const MAX_FRAME: usize = 512 * 1024;
const MAX_FILE_BYTES: usize = 64 * 1024;
const MAX_WRITE_BYTES: usize = 32 * 1024;
const MAX_DIRECTORY_ENTRIES: usize = 200;
const MAX_COMMAND_OUTPUT_BYTES: usize = 32 * 1024;
const MAX_PAYLOAD_BYTES: usize = 128 * 1024;
const MAX_AGENT_BYTES: usize = 64 * 1024 * 1024;
const MAX_JOURNAL_CAPTURE_BYTES: usize = 64 * 1024;
const MAX_NETBIRD_STATUS_BYTES: usize = 2 * 1024 * 1024;
const MAX_NETBIRD_PEERS: usize = 256;
const MAX_NETBIRD_PEER_RESPONSE_BYTES: usize = 120 * 1024;
const RESOLVE_NO_MAGICLINKS: u64 = 0x02;
const RESOLVE_NO_SYMLINKS: u64 = 0x04;
const RESOLVE_BENEATH: u64 = 0x08;

#[derive(Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Request {
    #[serde(rename = "operationId")]
    pub operation_id: String,
    #[serde(rename = "idempotencyKey")]
    pub idempotency_key: String,
    pub capability: String,
    pub target: String,
    #[serde(rename = "deadlineEpochMs")]
    pub deadline_epoch_ms: i64,
    #[serde(
        rename = "payloadJson",
        default,
        skip_serializing_if = "Option::is_none"
    )]
    pub payload_json: Option<String>,
}

#[derive(Debug, Deserialize, Serialize)]
pub struct Response {
    pub ok: bool,
    pub operation_id: String,
    pub result: Option<String>,
    pub error: Option<String>,
}

pub struct HelperConfig {
    pub socket_path: std::path::PathBuf,
    pub allowed_agent_uid: u32,
    pub socket_gid: u32,
    pub allowed_read_root: File,
    pub allowed_write_root: File,
    pub controller_url: reqwest::Url,
    pub controller_cert: reqwest::Certificate,
    pub agent_binary: std::path::PathBuf,
    pub container_runtime: Option<std::path::PathBuf>,
    nginx_routes: Option<nginx_routes::NginxRouteConfig>,
    cron: Option<cron_jobs::CronConfig>,
    metrics: Option<metrics::MetricsConfig>,
    backup: Option<backup::BackupConfig>,
    network_operations_available: bool,
    topology_operations_available: bool,
    packet_capture_available: bool,
    vrf_operations_available: bool,
    ovs_operations_available: bool,
    netbird_binary: Option<std::path::PathBuf>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ContainerVolumeSpec {
    name: String,
    mount_path: String,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ContainerSpec {
    image: String,
    host_port: Option<u16>,
    container_port: Option<u16>,
    environment: Vec<String>,
    volumes: Vec<ContainerVolumeSpec>,
    memory_mb: u32,
    stateful: bool,
}

#[derive(Clone, Debug, Eq, Hash, PartialEq)]
struct NetworkRuleSpec {
    interface: String,
    protocol: String,
    port: u16,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct NetworkFirewallPayload {
    interface_name: String,
    protocol: String,
    port: u16,
    rollback_seconds: u16,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct ContainerVolumePayload {
    name: String,
    mount_path: String,
}

#[derive(Debug, Deserialize)]
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
#[serde(deny_unknown_fields)]
struct HostFileWritePayload {
    content: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct K3sPreflightPayload {
    role: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct K3sInstallPayload {
    role: String,
    version: String,
    server_url: Option<String>,
    token: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct K3sUpgradePayload {
    role: String,
    version: String,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct KubeEdgeJoinPayload {
    version: String,
    cloud_core_endpoint: String,
    token: String,
}

struct HelperRuntime {
    allowed_uid: u32,
    read_root: Arc<File>,
    write_root: Arc<File>,
    host_root: Arc<File>,
    controller_url: reqwest::Url,
    controller_cert: reqwest::Certificate,
    agent_binary: std::path::PathBuf,
    container_runtime: Option<Arc<std::path::PathBuf>>,
    nginx_routes: Option<Arc<NginxRouteConfig>>,
    cron: Option<Arc<cron_jobs::CronConfig>>,
    metrics: Option<Arc<metrics::MetricsConfig>>,
    backup: Option<Arc<backup::BackupConfig>>,
    network_operations_available: bool,
    topology_operations_available: bool,
    packet_capture_available: bool,
    vrf_operations_available: bool,
    ovs_operations_available: bool,
    kubeedge_available: bool,
    netbird_binary: Option<Arc<std::path::PathBuf>>,
    network_lock: tokio::sync::Mutex<()>,
}

impl HelperConfig {
    pub fn new(
        socket_path: impl Into<std::path::PathBuf>,
        allowed_agent_uid: u32,
        socket_gid: u32,
        read_root: impl AsRef<Path>,
        controller_url: &str,
        controller_cert: impl AsRef<Path>,
        agent_binary: impl Into<std::path::PathBuf>,
    ) -> Result<Self> {
        if !nix::unistd::geteuid().is_root() {
            bail!("privileged-helper must run as root");
        }
        let path = socket_path.into();
        if !path.is_absolute() {
            bail!("helper socket path must be absolute");
        }
        let parent = path
            .parent()
            .context("helper socket has no parent directory")?;
        use std::os::unix::fs::MetadataExt;
        let parent_metadata = std::fs::metadata(parent)
            .with_context(|| format!("inspect socket parent {}", parent.display()))?;
        if parent_metadata.uid() != 0 || parent_metadata.mode() & 0o022 != 0 {
            bail!("helper socket parent must be root-owned and not group/world writable");
        }
        remove_stale_socket(&path)?;
        let allowed_read_root = File::open(read_root.as_ref())
            .with_context(|| format!("open read root {}", read_root.as_ref().display()))?;
        if !allowed_read_root.metadata()?.is_dir() {
            bail!("allowed read root is not a directory");
        }
        let read_root_metadata = allowed_read_root.metadata()?;
        if read_root_metadata.uid() != 0 || read_root_metadata.mode() & 0o022 != 0 {
            bail!("allowed read root must be root-owned and not group/world writable");
        }
        let allowed_write_root = open_beneath(
            &allowed_read_root,
            "files",
            libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC,
            0,
        )
        .context("open root-managed protected-file directory")?;
        let write_root_metadata = allowed_write_root.metadata()?;
        if !write_root_metadata.is_dir()
            || write_root_metadata.uid() != 0
            || write_root_metadata.mode() & 0o077 != 0
        {
            bail!("protected-file directory must be root-owned and inaccessible to group/others");
        }
        let container_runtime = read_container_runtime(&allowed_read_root)?;
        let network_operations_available =
            trusted_executable(&["/usr/bin/systemd-run", "/bin/systemd-run"]).is_ok()
                && trusted_executable(&["/usr/sbin/nft", "/usr/bin/nft"]).is_ok();
        let netbird_binary =
            trusted_executable(&["/usr/bin/netbird", "/usr/local/bin/netbird"]).ok();
        let controller_url =
            reqwest::Url::parse(controller_url).context("parse configured controller URL")?;
        if controller_url.scheme() != "https"
            || controller_url.host_str().is_none()
            || controller_url.username() != ""
            || controller_url.password().is_some()
            || controller_url.path() != "/"
            || controller_url.query().is_some()
            || controller_url.fragment().is_some()
        {
            bail!("controller URL must be an HTTPS origin without credentials or path");
        }
        let certificate_pem = std::fs::read(controller_cert.as_ref())
            .context("read pinned controller certificate")?;
        let controller_cert = reqwest::Certificate::from_pem(&certificate_pem)
            .context("parse pinned controller certificate")?;
        let agent_binary = agent_binary.into();
        if !agent_binary.is_absolute() {
            bail!("Agent binary path must be absolute");
        }
        let parent = agent_binary
            .parent()
            .context("Agent binary has no parent")?;
        let metadata = std::fs::metadata(parent)
            .with_context(|| format!("inspect Agent binary directory {}", parent.display()))?;
        if metadata.uid() != 0 || metadata.mode() & 0o022 != 0 {
            bail!("Agent binary directory must be root-owned and not group/world writable");
        }
        let topology_operations_available = topology::available();
        let packet_capture_available =
            topology_operations_available && topology::capture_available();
        let vrf_operations_available = topology_operations_available && topology::vrf_available();
        Ok(Self {
            socket_path: path,
            allowed_agent_uid,
            socket_gid,
            allowed_read_root,
            allowed_write_root,
            controller_url,
            controller_cert,
            agent_binary,
            container_runtime,
            nginx_routes: None,
            cron: None,
            metrics: None,
            backup: None,
            network_operations_available,
            topology_operations_available,
            packet_capture_available,
            vrf_operations_available,
            ovs_operations_available: topology::ovs_available(),
            netbird_binary,
        })
    }

    pub fn with_nginx_routes(mut self, routes: Option<NginxRouteConfig>) -> Self {
        self.nginx_routes = routes;
        self
    }

    pub fn with_cron(mut self, cron: Option<cron_jobs::CronConfig>) -> Self {
        self.cron = cron;
        self
    }

    pub fn with_metrics(mut self, metrics: Option<metrics::MetricsConfig>) -> Self {
        self.metrics = metrics;
        self
    }

    pub fn with_backup(mut self, backup: Option<backup::BackupConfig>) -> Self {
        self.backup = backup;
        self
    }
}

pub async fn serve(config: HelperConfig) -> Result<()> {
    let socket_path = config.socket_path.clone();
    let listener = UnixListener::bind(&config.socket_path)
        .with_context(|| format!("bind helper socket {}", config.socket_path.display()))?;
    std::fs::set_permissions(&config.socket_path, std::fs::Permissions::from_mode(0o660))?;
    if let Err(error) =
        std::os::unix::fs::chown(&config.socket_path, Some(0), Some(config.socket_gid))
    {
        let _ = std::fs::remove_file(&config.socket_path);
        return Err(error).context("set helper socket group");
    }
    let kubeedge_available =
        trusted_executable(&["/usr/local/bin/keadm", "/usr/bin/keadm"]).is_ok();
    let runtime = Arc::new(HelperRuntime {
        allowed_uid: config.allowed_agent_uid,
        read_root: Arc::new(config.allowed_read_root),
        write_root: Arc::new(config.allowed_write_root),
        host_root: Arc::new(File::open("/").context("open host filesystem root")?),
        controller_url: config.controller_url,
        controller_cert: config.controller_cert,
        agent_binary: config.agent_binary,
        container_runtime: config.container_runtime.map(Arc::new),
        nginx_routes: config.nginx_routes.map(Arc::new),
        cron: config.cron.map(Arc::new),
        metrics: config.metrics.map(Arc::new),
        backup: config.backup.map(Arc::new),
        network_operations_available: config.network_operations_available,
        topology_operations_available: config.topology_operations_available,
        packet_capture_available: config.packet_capture_available,
        vrf_operations_available: config.vrf_operations_available,
        ovs_operations_available: config.ovs_operations_available,
        kubeedge_available,
        netbird_binary: config.netbird_binary.map(Arc::new),
        network_lock: tokio::sync::Mutex::new(()),
    });
    let mut terminate = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        .context("install Helper SIGTERM handler")?;
    let interrupted = tokio::signal::ctrl_c();
    tokio::pin!(interrupted);
    loop {
        tokio::select! {
            accepted = listener.accept() => {
                let (stream, _) = accepted?;
                let runtime = Arc::clone(&runtime);
                tokio::spawn(async move {
                    if let Err(error) = handle(stream, runtime).await {
                        eprintln!(
                            "{{\"event\":\"helper_request_error\",\"error\":{}}}",
                            serde_json::to_string(&error.to_string())
                                .unwrap_or_else(|_| "\"unknown\"".to_owned())
                        );
                    }
                });
            }
            _ = &mut interrupted => break,
            _ = terminate.recv() => break,
        }
    }
    drop(listener);
    match std::fs::remove_file(&socket_path) {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error).context("remove Helper socket during shutdown"),
    }
    Ok(())
}

fn remove_stale_socket(path: &Path) -> Result<()> {
    use std::os::unix::fs::MetadataExt;
    let metadata = match std::fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error).context("inspect existing Helper socket path"),
    };
    if !metadata.file_type().is_socket() || metadata.uid() != 0 {
        bail!("refusing to replace a non-socket or non-root-owned Helper path");
    }
    match std::os::unix::net::UnixStream::connect(path) {
        Ok(_) => bail!("refusing to replace an active Helper socket"),
        Err(error)
            if matches!(
                error.kind(),
                std::io::ErrorKind::ConnectionRefused | std::io::ErrorKind::NotFound
            ) =>
        {
            std::fs::remove_file(path).context("remove stale Helper socket")
        }
        Err(error) => Err(error).context("check whether existing Helper socket is active"),
    }
}

async fn handle(stream: UnixStream, runtime: Arc<HelperRuntime>) -> Result<()> {
    let peer = getsockopt(&stream, PeerCredentials).context("read Unix peer credentials")?;
    let mut framed = Framed::new(stream, LinesCodec::new_with_max_length(MAX_FRAME));
    while let Some(line) = framed.next().await {
        let line = match line {
            Ok(line) => line,
            Err(error) => {
                let response = Response {
                    ok: false,
                    operation_id: String::new(),
                    result: None,
                    error: Some(format!("invalid frame: {error}")),
                };
                framed.send(serde_json::to_string(&response)?).await?;
                return Ok(());
            }
        };
        let parsed = serde_json::from_str::<Request>(&line);
        let response = match parsed {
            Ok(request) => {
                let result = if peer.uid() != runtime.allowed_uid {
                    Err("caller identity is not authorized".to_owned())
                } else {
                    match validate_envelope(&request) {
                        Err(error) => Err(error),
                        Ok(()) => {
                            match validate_payload_presence(&request).and_then(|()| {
                                validate_capability_target(
                                    &request.capability,
                                    &request.target,
                                    runtime.container_runtime.is_some(),
                                    runtime.network_operations_available,
                                    runtime.topology_operations_available,
                                    runtime.netbird_binary.is_some(),
                                    runtime.nginx_routes.is_some(),
                                    runtime.cron.is_some(),
                                    runtime.metrics.is_some(),
                                    runtime.backup.is_some(),
                                )
                            }) {
                                Err(error) => Err(error),
                                Ok(()) => match request.capability.as_str() {
                                    "helper.status" => Ok("AVAILABLE".to_owned()),
                                    "helper.capabilities" => Ok(serde_json::json!(
                                        available_capabilities(CapabilityState {
                                            has_container_runtime: runtime
                                                .container_runtime
                                                .is_some(),
                                            network_operations_available: runtime
                                                .network_operations_available,
                                            topology_operations_available: runtime
                                                .topology_operations_available,
                                            packet_capture_available: runtime
                                                .packet_capture_available,
                                            vrf_operations_available: runtime
                                                .vrf_operations_available,
                                            ovs_operations_available: runtime
                                                .ovs_operations_available,
                                            netbird_status_available: runtime
                                                .netbird_binary
                                                .is_some(),
                                            kubeedge_available: runtime.kubeedge_available,
                                            nginx_routes_available: runtime.nginx_routes.is_some(),
                                            cron_available: runtime.cron.is_some(),
                                            metrics_available: runtime.metrics.is_some(),
                                            backup_available: runtime.backup.is_some(),
                                        })
                                    )
                                    .to_string()),
                                    "agent.update" => {
                                        update_agent(
                                            &runtime.controller_url,
                                            &runtime.controller_cert,
                                            &runtime.agent_binary,
                                        )
                                        .await
                                    }
                                    "systemd.restart" => {
                                        restart_system_service(&request.target).await
                                    }
                                    "systemd.journal.read" => {
                                        read_systemd_journal(&request.target).await
                                    }
                                    "file.read.managed" => execute(&request, &runtime.read_root),
                                    "package.install" => install_packages(&request).await,
                                    "file.write.protected" => {
                                        write_protected_file(&request, &runtime.write_root)
                                    }
                                    "file.list" => {
                                        list_host_directory(&runtime.host_root, &request.target)
                                    }
                                    "file.read" => {
                                        read_host_file(&runtime.host_root, &request.target)
                                    }
                                    "file.write" => write_host_file(&runtime.host_root, &request),
                                    "file.delete" => {
                                        delete_host_file(&runtime.host_root, &request.target)
                                    }
                                    "container.list" => {
                                        manage_container(
                                            &request,
                                            runtime
                                                .container_runtime
                                                .as_deref()
                                                .map(|path| path.as_path()),
                                        )
                                        .await
                                    }
                                    "container.logs" | "container.start" | "container.stop"
                                    | "container.deploy" | "container.remove" => {
                                        manage_container(
                                            &request,
                                            runtime
                                                .container_runtime
                                                .as_deref()
                                                .map(|path| path.as_path()),
                                        )
                                        .await
                                    }
                                    "network.firewall.expose" => {
                                        let _guard = runtime.network_lock.lock().await;
                                        expose_network_port(&request).await
                                    }
                                    "network.topology.reconcile" | "network.topology.capture" => {
                                        let _guard = runtime.network_lock.lock().await;
                                        topology::execute(&request).await
                                    }
                                    "network.overlay.peer_paths.read" => {
                                        collect_netbird_peer_paths(
                                            runtime
                                                .netbird_binary
                                                .as_ref()
                                                .map(|binary| binary.as_path()),
                                        )
                                        .await
                                    }
                                    "nginx.route.set" => match runtime.nginx_routes.as_deref() {
                                        Some(routes) => {
                                            routes
                                                .set_route(
                                                    &request.target,
                                                    request.payload_json.as_deref(),
                                                )
                                                .await
                                        }
                                        None => Err(
                                            "DENIED: managed Nginx route support is not configured"
                                                .to_owned(),
                                        ),
                                    },
                                    "nginx.route.remove" => match runtime.nginx_routes.as_deref() {
                                        Some(routes) => routes.remove_route(&request.target).await,
                                        None => Err(
                                            "DENIED: managed Nginx route support is not configured"
                                                .to_owned(),
                                        ),
                                    },
                                    "nginx.tls.install" => match runtime.nginx_routes.as_deref() {
                                        Some(routes) => {
                                            routes
                                                .install_tls(
                                                    &request.target,
                                                    request.payload_json.as_deref(),
                                                )
                                                .await
                                        }
                                        None => Err(
                                            "DENIED: managed Nginx route support is not configured"
                                                .to_owned(),
                                        ),
                                    },
                                    "metrics.read" => {
                                        match runtime.metrics.as_deref() {
                                            Some(metrics) => metrics.read(),
                                            None => Err("DENIED: host metrics are not configured"
                                                .to_owned()),
                                        }
                                    }
                                    "cron.list" => match runtime.cron.as_deref() {
                                        Some(cron) => cron.list_jobs(),
                                        None => {
                                            Err("DENIED: managed cron support is not configured"
                                                .to_owned())
                                        }
                                    },
                                    "cron.write" => match runtime.cron.as_deref() {
                                        Some(cron) => cron.write_job(
                                            &request.target,
                                            request.payload_json.as_deref(),
                                        ),
                                        None => {
                                            Err("DENIED: managed cron support is not configured"
                                                .to_owned())
                                        }
                                    },
                                    "cron.remove" => match runtime.cron.as_deref() {
                                        Some(cron) => cron.remove_job(&request.target),
                                        None => {
                                            Err("DENIED: managed cron support is not configured"
                                                .to_owned())
                                        }
                                    },
                                    "k3s.preflight" => k3s_preflight(&request),
                                    "k3s.install" => {
                                        k3s_install(&request, &runtime.host_root).await
                                    }
                                    "k3s.upgrade" => {
                                        k3s_upgrade(&request, &runtime.host_root).await
                                    }
                                    "kubeedge.edgecore.join" => {
                                        kubeedge_edgecore_join(&request, &runtime.host_root).await
                                    }
                                    _ => Err("unsupported capability".to_owned()),
                                },
                            }
                        }
                    }
                };
                match result {
                    Ok(result) => {
                        let audit_target = audit_target(&request);
                        audit(
                            peer.uid(),
                            &request.operation_id,
                            &request.capability,
                            &audit_target,
                            "succeeded",
                        );
                        Response {
                            ok: true,
                            operation_id: request.operation_id,
                            result: Some(result),
                            error: None,
                        }
                    }
                    Err(error) => {
                        let audit_target = audit_target(&request);
                        audit(
                            peer.uid(),
                            &request.operation_id,
                            &request.capability,
                            &audit_target,
                            if error.starts_with("UNCERTAIN:") {
                                "uncertain"
                            } else {
                                "rejected"
                            },
                        );
                        Response {
                            ok: false,
                            operation_id: request.operation_id,
                            result: None,
                            error: Some(error),
                        }
                    }
                }
            }
            Err(error) => {
                audit(peer.uid(), "", "invalid-request", "", "rejected");
                Response {
                    ok: false,
                    operation_id: String::new(),
                    result: None,
                    error: Some(format!("invalid request: {error}")),
                }
            }
        };
        framed.send(serde_json::to_string(&response)?).await?;
    }
    Ok(())
}

fn execute(request: &Request, allowed_read_root: &File) -> std::result::Result<String, String> {
    validate_envelope(request)?;
    if request.capability != "file.read.managed" {
        return Err("unsupported capability".to_owned());
    }
    read_beneath(allowed_read_root, &request.target).map_err(|e| format!("read denied: {e}"))
}

fn validate_envelope(request: &Request) -> std::result::Result<(), String> {
    if request.operation_id.is_empty()
        || request.operation_id.len() > 128
        || request.idempotency_key.is_empty()
        || request.idempotency_key.len() > 128
    {
        return Err("operation id and idempotency key must be 1–128 bytes".to_owned());
    }
    if request
        .payload_json
        .as_ref()
        .is_some_and(|payload| payload.len() > MAX_PAYLOAD_BYTES)
    {
        return Err("DENIED: operation payload exceeds the 48 KiB limit".to_owned());
    }
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;
    if request.deadline_epoch_ms < now || request.deadline_epoch_ms > now + 10 * 60 * 1000 {
        return Err("deadline is expired or exceeds the 10 minute limit".to_owned());
    }
    Ok(())
}

// The feature flags mirror the helper's capability set one-to-one; grouping them would just
// move the same list somewhere else.
#[allow(clippy::too_many_arguments)]
fn validate_capability_target(
    capability: &str,
    target: &str,
    has_container_runtime: bool,
    network_operations_available: bool,
    topology_operations_available: bool,
    netbird_status_available: bool,
    nginx_routes_available: bool,
    cron_available: bool,
    metrics_available: bool,
    backup_available: bool,
) -> std::result::Result<(), String> {
    match capability {
        "helper.status" | "helper.capabilities" | "agent.update" if target.is_empty() => Ok(()),
        "network.overlay.peer_paths.read" if target.is_empty() && netbird_status_available => {
            Ok(())
        }
        "nginx.route.set"
            if nginx_routes_available
                && nginx_routes::NginxRouteConfig::validate_target(target) =>
        {
            Ok(())
        }
        "nginx.route.remove"
            if nginx_routes_available
                && nginx_routes::NginxRouteConfig::validate_target(target) =>
        {
            Ok(())
        }
        "nginx.tls.install"
            if nginx_routes_available
                && nginx_routes::NginxRouteConfig::validate_tls_target(target) =>
        {
            Ok(())
        }
        "cron.list" if target.is_empty() && cron_available => Ok(()),
        "metrics.read" if target.is_empty() && metrics_available => Ok(()),
        "backup.list" if target.is_empty() && backup_available => Ok(()),
        "backup.create" if target.is_empty() && backup_available => Ok(()),
        "backup.delete" if backup_available && backup::BackupConfig::validate_target(target) => {
            Ok(())
        }
        "backup.restore" if target.is_empty() && backup_available => Ok(()),
        "cron.write" | "cron.remove"
            if cron_available && cron_jobs::CronConfig::validate_target(target) =>
        {
            Ok(())
        }
        "k3s.preflight" | "k3s.install" | "k3s.upgrade" | "kubeedge.edgecore.join"
            if target.is_empty() =>
        {
            Ok(())
        }
        "systemd.restart" | "systemd.journal.read" if valid_systemd_unit(target) => Ok(()),
        "file.read.managed" if !target.is_empty() => Ok(()),
        "package.install" if target.is_empty() => Ok(()),
        "file.write.protected" if valid_file_name(target) => Ok(()),
        "file.list" if valid_host_path(target, true).is_ok() => Ok(()),
        "file.read" | "file.write" | "file.delete" if valid_host_path(target, false).is_ok() => {
            Ok(())
        }
        "container.list" if target.is_empty() && has_container_runtime => Ok(()),
        "container.logs" | "container.start" | "container.stop" | "container.deploy"
        | "container.remove"
            if valid_container_name(target) && has_container_runtime =>
        {
            Ok(())
        }
        "network.firewall.expose"
            if network_operations_available && valid_network_rule_name(target) =>
        {
            Ok(())
        }
        "network.topology.reconcile"
            if topology_operations_available && uuid::Uuid::parse_str(target).is_ok() =>
        {
            Ok(())
        }
        "network.topology.capture"
            if topology_operations_available && uuid::Uuid::parse_str(target).is_ok() =>
        {
            Ok(())
        }
        "helper.status"
        | "helper.capabilities"
        | "agent.update"
        | "network.overlay.peer_paths.read"
        | "k3s.preflight"
        | "k3s.install"
        | "k3s.upgrade"
        | "kubeedge.edgecore.join"
        | "systemd.restart"
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
        | "nginx.route.set"
        | "nginx.route.remove"
        | "nginx.tls.install" => Err("DENIED: target or parameters are invalid".to_owned()),
        _ => Err("DENIED: unsupported capability or target".to_owned()),
    }
}

fn validate_payload_presence(request: &Request) -> std::result::Result<(), String> {
    let required = matches!(
        request.capability.as_str(),
        "package.install"
            | "file.write.protected"
            | "network.firewall.expose"
            | "network.topology.reconcile"
            | "network.topology.capture"
            | "file.write"
            | "container.deploy"
            | "k3s.preflight"
            | "k3s.install"
            | "k3s.upgrade"
            | "kubeedge.edgecore.join"
            | "nginx.route.set"
            | "nginx.tls.install"
            | "cron.write"
            | "backup.create"
            | "backup.restore"
    );
    if required != request.payload_json.is_some() {
        return Err("DENIED: payload presence does not match capability schema".to_owned());
    }
    if request.capability == "network.topology.reconcile" {
        topology::validate_target_payload(&request.target, request.payload_json.as_deref())?;
    }
    if request.capability == "network.topology.capture" {
        topology::validate_capture_target_payload(
            &request.target,
            request.payload_json.as_deref(),
        )?;
    }
    Ok(())
}

fn audit_target(request: &Request) -> String {
    if request.capability == "package.install"
        && let Some(payload) = request.payload_json.as_deref()
        && let Ok(payload) = serde_json::from_str::<PackageInstallPayload>(payload)
    {
        return payload.packages.join(",");
    }
    request.target.clone()
}

#[derive(Clone, Copy, Default)]
struct CapabilityState {
    has_container_runtime: bool,
    network_operations_available: bool,
    topology_operations_available: bool,
    packet_capture_available: bool,
    vrf_operations_available: bool,
    ovs_operations_available: bool,
    netbird_status_available: bool,
    kubeedge_available: bool,
    nginx_routes_available: bool,
    cron_available: bool,
    metrics_available: bool,
    backup_available: bool,
}

fn available_capabilities(state: CapabilityState) -> Vec<&'static str> {
    let mut capabilities = vec![
        "systemd.restart",
        "systemd.journal.read",
        "helper.capabilities",
        "k3s.preflight",
        "k3s.install",
        "k3s.upgrade",
        "package.install",
        "file.write.protected",
        "file.list",
        "file.read",
        "file.write",
        "file.delete",
    ];
    if state.backup_available {
        capabilities.extend([
            "backup.list",
            "backup.create",
            "backup.restore",
            "backup.delete",
        ]);
    }
    if state.metrics_available {
        capabilities.extend(["metrics.read"]);
    }
    if state.cron_available {
        capabilities.extend(["cron.list", "cron.write", "cron.remove"]);
    }
    if state.has_container_runtime {
        capabilities.extend([
            "container.list",
            "container.logs",
            "container.start",
            "container.stop",
            "container.deploy",
            "container.remove",
        ]);
    }
    if state.network_operations_available {
        capabilities.push("network.firewall.expose");
    }
    if state.topology_operations_available {
        capabilities.push("network.topology.reconcile");
    }
    if state.packet_capture_available {
        capabilities.push("network.topology.capture");
    }
    if state.topology_operations_available && state.vrf_operations_available {
        capabilities.push("network.topology.vrf");
    }
    if state.topology_operations_available && state.ovs_operations_available {
        capabilities.push("network.topology.ovs");
    }
    if state.netbird_status_available {
        capabilities.push("network.overlay.peer_paths.read");
    }
    if state.kubeedge_available {
        capabilities.push("kubeedge.edgecore.join");
    }
    if state.nginx_routes_available {
        capabilities.push("nginx.route.set");
        capabilities.push("nginx.route.remove");
        capabilities.push("nginx.tls.install");
    }
    capabilities
}

#[derive(Debug, Deserialize)]
struct NetbirdCliStatus {
    peers: NetbirdCliPeers,
}

#[derive(Debug, Deserialize)]
struct NetbirdCliPeers {
    details: Vec<NetbirdCliPeer>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct NetbirdCliPeer {
    fqdn: String,
    status: String,
    connection_type: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct NetbirdPeerPath {
    peer_name: String,
    status: String,
    connection_type: String,
}

fn k3s_preflight(request: &Request) -> std::result::Result<String, String> {
    let payload = request
        .payload_json
        .as_deref()
        .ok_or_else(|| "DENIED: K3s preflight role is required".to_owned())?;
    let payload: K3sPreflightPayload = serde_json::from_str(payload)
        .map_err(|_| "DENIED: K3s preflight payload is invalid".to_owned())?;
    let role = payload.role.as_str();
    let server = match role {
        "server" => true,
        "agent" => false,
        _ => return Err("DENIED: K3s role must be server or agent".to_owned()),
    };

    let minimum_cpus = if server { 2 } else { 1 };
    let minimum_memory_kib = if server { 2 * 1024 * 1024 } else { 512 * 1024 };
    let cpu_count = std::thread::available_parallelism().map(usize::from).ok();
    let memory_kib = std::fs::read_to_string("/proc/meminfo")
        .ok()
        .and_then(|contents| {
            contents.lines().find_map(|line| {
                let rest = line.strip_prefix("MemTotal:")?;
                rest.split_whitespace().next()?.parse::<u64>().ok()
            })
        });
    let architecture = std::env::consts::ARCH;
    let supported_architecture = matches!(architecture, "x86_64" | "aarch64" | "arm" | "s390x");
    let systemd_available = Path::new("/run/systemd/system").is_dir()
        && ["/usr/bin/systemctl", "/bin/systemctl"]
            .iter()
            .any(|path| Path::new(path).is_file());
    let cgroup_v2 = Path::new("/sys/fs/cgroup/cgroup.controllers").is_file();
    let k3s_binary = ["/usr/local/bin/k3s", "/usr/bin/k3s"]
        .iter()
        .any(|path| Path::new(path).is_file());
    let k3s_unit = [
        "/etc/systemd/system/k3s.service",
        "/etc/systemd/system/k3s-agent.service",
        "/usr/lib/systemd/system/k3s.service",
        "/usr/lib/systemd/system/k3s-agent.service",
    ]
    .iter()
    .any(|path| Path::new(path).is_file());
    let os_release = std::fs::read_to_string("/etc/os-release").unwrap_or_default();
    let os_id = os_release_value(&os_release, "ID").unwrap_or_else(|| "unknown".to_owned());
    let os_version = os_release_value(&os_release, "VERSION_ID");
    let mut checks = Vec::new();
    let mut blockers = 0;
    let mut warnings = 0;
    let mut add_check = |id: &str, severity: &str, passed: Option<bool>, detail: String| {
        if severity == "BLOCKER" && passed != Some(true) {
            blockers += 1;
        } else if severity == "WARNING" && passed != Some(true) {
            warnings += 1;
        }
        checks.push(serde_json::json!({
            "id": id,
            "severity": severity,
            "passed": passed,
            "detail": detail
        }));
    };
    add_check(
        "linux-architecture",
        "BLOCKER",
        Some(supported_architecture),
        format!("当前架构：{architecture}；支持 x86_64、aarch64、arm、s390x"),
    );
    add_check(
        "cpu-cores",
        "BLOCKER",
        cpu_count.map(|count| count >= minimum_cpus),
        format!(
            "当前可用 CPU：{}；{role} 最低要求：{minimum_cpus}",
            cpu_count.map_or_else(|| "未知".to_owned(), |count| count.to_string())
        ),
    );
    add_check(
        "memory",
        "BLOCKER",
        memory_kib.map(|value| value >= minimum_memory_kib),
        format!(
            "当前内存：{}；{role} 最低要求：{} KiB",
            memory_kib.map_or_else(|| "未知".to_owned(), |value| format!("{value} KiB")),
            minimum_memory_kib
        ),
    );
    add_check(
        "systemd",
        "BLOCKER",
        Some(systemd_available),
        if systemd_available {
            "检测到 systemd 管理环境".to_owned()
        } else {
            "未检测到运行中的 systemd；当前受控安装流程要求 systemd".to_owned()
        },
    );
    add_check(
        "cgroup-v2",
        "WARNING",
        Some(cgroup_v2),
        if cgroup_v2 {
            "检测到 cgroup v2".to_owned()
        } else {
            "未检测到 cgroup v2；需结合发行版和容器运行时确认 cgroup 配置".to_owned()
        },
    );
    add_check(
        "k3s-existing-installation",
        "BLOCKER",
        Some(!k3s_binary && !k3s_unit),
        if k3s_binary || k3s_unit {
            "已检测到 K3s 二进制或 systemd unit；应进入升级/修复流程，不能重复安装".to_owned()
        } else {
            "未检测到已有 K3s 二进制或 systemd unit".to_owned()
        },
    );
    let selinux_active = std::fs::read_to_string("/sys/fs/selinux/enforce")
        .ok()
        .is_some_and(|value| matches!(value.trim(), "0" | "1"));
    add_check(
        "selinux-policy",
        "BLOCKER",
        Some(!selinux_active),
        if selinux_active {
            "检测到启用的 SELinux；当前受控安装流程不会安装 K3s SELinux 策略包".to_owned()
        } else {
            "未检测到已启用的 SELinux".to_owned()
        },
    );
    add_check(
        "os-release",
        "INFO",
        Some(true),
        format!(
            "发行版：{}{}",
            os_id,
            os_version
                .as_ref()
                .map_or_else(String::new, |version| format!(" {version}"))
        ),
    );
    let status = if blockers > 0 {
        "BLOCKED"
    } else if warnings > 0 {
        "READY_WITH_WARNINGS"
    } else {
        "READY"
    };
    serde_json::to_string(&serde_json::json!({
        "schemaVersion": 1,
        "role": role,
        "status": status,
        "blockerCount": blockers,
        "warningCount": warnings,
        "architecture": architecture,
        "cpuCores": cpu_count,
        "memoryKib": memory_kib,
        "osId": os_id,
        "osVersion": os_version,
        "k3sBinaryPresent": k3s_binary,
        "k3sUnitPresent": k3s_unit,
        "checks": checks
    }))
    .map_err(|_| "FAILED: unable to encode K3s preflight report".to_owned())
}

fn os_release_value(contents: &str, key: &str) -> Option<String> {
    contents.lines().find_map(|line| {
        let value = line.strip_prefix(key)?.strip_prefix('=')?;
        let value = value.trim().trim_matches('"').trim_matches('\'');
        (!value.is_empty() && value.len() <= 128).then(|| value.to_owned())
    })
}

fn valid_k3s_version(version: &str) -> bool {
    let Some(release) = version.strip_prefix('v') else {
        return false;
    };
    let Some((kubernetes, k3s_revision)) = release.split_once("+k3s") else {
        return false;
    };
    let components = kubernetes.split('.').collect::<Vec<_>>();
    components.len() == 3
        && components
            .iter()
            .all(|component| !component.is_empty() && component.bytes().all(|b| b.is_ascii_digit()))
        && !k3s_revision.is_empty()
        && k3s_revision.bytes().all(|b| b.is_ascii_digit())
}

fn valid_k3s_upgrade_payload(payload: &K3sUpgradePayload) -> bool {
    matches!(payload.role.as_str(), "server" | "agent") && valid_k3s_version(&payload.version)
}

fn validate_k3s_install_payload(payload: &K3sInstallPayload) -> std::result::Result<(), String> {
    if !valid_k3s_version(&payload.version) {
        return Err("DENIED: K3s version must be a pinned stable vX.Y.Z+k3sN release".to_owned());
    }
    if !valid_k3s_token(&payload.token) {
        return Err("DENIED: K3s token is invalid".to_owned());
    }
    match payload.role.as_str() {
        "server" if payload.server_url.is_none() && !payload.token.starts_with("K10") => Ok(()),
        "agent" => {
            let Some(server_url) = payload.server_url.as_deref() else {
                return Err("DENIED: K3s agent requires a server URL".to_owned());
            };
            validate_k3s_server_url(server_url)
        }
        _ => Err("DENIED: K3s role or server URL is invalid".to_owned()),
    }
}

fn valid_k3s_token(token: &str) -> bool {
    (16..=512).contains(&token.len())
        && token
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.' | b':'))
}

fn validate_k3s_server_url(server_url: &str) -> std::result::Result<(), String> {
    let url = reqwest::Url::parse(server_url)
        .map_err(|_| "DENIED: K3s server URL is invalid".to_owned())?;
    if url.scheme() != "https"
        || url.host_str().is_none()
        || url.username() != ""
        || url.password().is_some()
        || !matches!(url.path(), "" | "/")
        || url.query().is_some()
        || url.fragment().is_some()
        || url.port().is_some_and(|port| port != 6443)
    {
        return Err("DENIED: K3s server URL must be an HTTPS API origin on port 6443".to_owned());
    }
    if let Some(host) = url.host_str()
        && let Ok(address) = host.parse::<std::net::IpAddr>()
    {
        let forbidden = match address {
            std::net::IpAddr::V4(address) => {
                address.is_unspecified()
                    || address.is_loopback()
                    || address.is_link_local()
                    || address.is_multicast()
            }
            std::net::IpAddr::V6(address) => {
                address.is_unspecified()
                    || address.is_loopback()
                    || address.is_multicast()
                    || address.is_unicast_link_local()
            }
        };
        if forbidden {
            return Err("DENIED: K3s server URL uses a non-routable address".to_owned());
        }
    }
    Ok(())
}

async fn k3s_install(request: &Request, host_root: &File) -> std::result::Result<String, String> {
    let payload = parse_payload::<K3sInstallPayload>(request)?;
    validate_k3s_install_payload(&payload)?;
    let preflight_request = Request {
        operation_id: request.operation_id.clone(),
        idempotency_key: request.idempotency_key.clone(),
        capability: "k3s.preflight".to_owned(),
        target: String::new(),
        deadline_epoch_ms: request.deadline_epoch_ms,
        payload_json: Some(format!("{{\"role\":\"{}\"}}", payload.role)),
    };
    let preflight = k3s_preflight(&preflight_request)?;
    let preflight: serde_json::Value = serde_json::from_str(&preflight)
        .map_err(|_| "FAILED: K3s preflight result is invalid".to_owned())?;
    if preflight["status"] == "BLOCKED" {
        return Err("DENIED: K3s host preflight reported one or more blockers".to_owned());
    }
    if !Path::new("/run/systemd/system").is_dir() {
        return Err("DENIED: K3s installation requires a running systemd host".to_owned());
    }
    let systemctl = trusted_executable(&["/usr/bin/systemctl", "/bin/systemctl"])?;
    let architecture = match std::env::consts::ARCH {
        "x86_64" => ("amd64", ""),
        "aarch64" => ("arm64", "-arm64"),
        "arm" => ("arm", "-armhf"),
        "s390x" => ("s390x", "-s390x"),
        _ => return Err("DENIED: K3s binary is unavailable for this architecture".to_owned()),
    };
    let unit = if payload.role == "server" {
        "k3s.service"
    } else {
        "k3s-agent.service"
    };
    let executable_parent = open_beneath(
        host_root,
        "usr/local/bin",
        libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC,
        0,
    )
    .map_err(|_| "DENIED: /usr/local/bin is unavailable or unsafe".to_owned())?;
    let unit_parent = open_beneath(
        host_root,
        "etc/systemd/system",
        libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC,
        0,
    )
    .map_err(|_| "DENIED: /etc/systemd/system is unavailable or unsafe".to_owned())?;
    let environment_file = "k3s.nanpanel.env";
    let state_exists = match open_beneath(
        host_root,
        "var/lib/rancher/k3s",
        libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC,
        0,
    ) {
        Ok(_) => true,
        Err(error)
            if error.chain().any(|source| {
                source
                    .downcast_ref::<std::io::Error>()
                    .is_some_and(|io| io.kind() == std::io::ErrorKind::NotFound)
            }) =>
        {
            false
        }
        Err(_) => return Err("DENIED: K3s data path could not be safely inspected".to_owned()),
    };
    if state_exists
        || host_entry_exists(&executable_parent, "k3s")?
        || host_entry_exists(&unit_parent, unit)?
        || host_entry_exists(&unit_parent, "k3s.service")?
        || host_entry_exists(&unit_parent, "k3s-agent.service")?
        || host_entry_exists(&unit_parent, environment_file)?
    {
        return Err(
            "DENIED: existing K3s files or data require the upgrade or repair workflow".to_owned(),
        );
    }

    let base = format!(
        "https://github.com/k3s-io/k3s/releases/download/{}/k3s",
        payload.version.replace('+', "%2B")
    );
    let binary_url = format!("{base}{}", architecture.1);
    let checksum_url = format!(
        "https://github.com/k3s-io/k3s/releases/download/{}/sha256sum-{}.txt",
        payload.version.replace('+', "%2B"),
        architecture.0
    );
    let client = reqwest::Client::builder()
        .https_only(true)
        .timeout(Duration::from_secs(120))
        .redirect(reqwest::redirect::Policy::custom(|attempt| {
            let host = attempt.url().host_str().unwrap_or_default();
            if attempt.previous().len() >= 5
                || attempt.url().scheme() != "https"
                || !matches!(
                    host,
                    "github.com"
                        | "release-assets.githubusercontent.com"
                        | "objects.githubusercontent.com"
                )
            {
                attempt.stop()
            } else {
                attempt.follow()
            }
        }))
        .user_agent("NaNPanel-K3s-Installer")
        .build()
        .map_err(|_| "FAILED: unable to initialize the pinned K3s download client".to_owned())?;
    let (checksums, binary) = tokio::try_join!(
        download_k3s_asset(&client, &checksum_url),
        download_k3s_asset(&client, &binary_url)
    )?;
    let expected_name = format!("k3s{}", architecture.1);
    let expected_digest = String::from_utf8(checksums)
        .ok()
        .and_then(|checksums| {
            checksums.lines().find_map(|line| {
                let mut columns = line.split_whitespace();
                let digest = columns.next()?;
                let name = columns.next()?.trim_start_matches('*');
                (name == expected_name && digest.len() == 64).then(|| digest.to_ascii_lowercase())
            })
        })
        .ok_or_else(|| {
            "FAILED: release checksum does not contain the expected K3s binary".to_owned()
        })?;
    let actual_digest = hex_digest(&Sha256::digest(&binary));
    if actual_digest != expected_digest {
        return Err("FAILED: downloaded K3s binary does not match the release checksum".to_owned());
    }

    let environment = if payload.role == "server" {
        format!("K3S_TOKEN={}\n", payload.token)
    } else {
        format!(
            "K3S_URL={}\nK3S_TOKEN={}\n",
            payload.server_url.as_deref().unwrap_or_default(),
            payload.token
        )
    };
    let unit_contents = format!(
        "[Unit]\nDescription=Lightweight Kubernetes\nDocumentation=https://k3s.io\nAfter=network-online.target\nWants=network-online.target\n\n[Service]\nType=notify\nEnvironmentFile=/etc/systemd/system/k3s.nanpanel.env\nExecStart=/usr/local/bin/k3s {}\nKillMode=process\nDelegate=yes\nLimitNOFILE=1048576\nLimitNPROC=infinity\nLimitCORE=infinity\nTasksMax=infinity\nTimeoutStartSec=0\nRestart=always\nRestartSec=5s\n\n[Install]\nWantedBy=multi-user.target\n",
        payload.role
    );
    write_new_beneath(
        &unit_parent,
        environment_file,
        environment.as_bytes(),
        0o600,
    )
    .map_err(|_| "FAILED: unable to install the private K3s environment file".to_owned())?;
    if write_new_beneath(&unit_parent, unit, unit_contents.as_bytes(), 0o644).is_err() {
        let _ = remove_entry_beneath(&unit_parent, environment_file);
        return Err("FAILED: unable to install the K3s systemd unit".to_owned());
    }
    if write_new_beneath(&executable_parent, "k3s", &binary, 0o755).is_err() {
        let _ = remove_entry_beneath(&unit_parent, unit);
        let _ = remove_entry_beneath(&unit_parent, environment_file);
        return Err("FAILED: unable to install the K3s executable".to_owned());
    }
    if run_fixed_command(
        &systemctl,
        &["daemon-reload".to_owned()],
        Duration::from_secs(15),
    )
    .await
    .is_err()
    {
        let _ = remove_entry_beneath(&executable_parent, "k3s");
        let _ = remove_entry_beneath(&unit_parent, unit);
        let _ = remove_entry_beneath(&unit_parent, environment_file);
        let _ = run_fixed_command(
            &systemctl,
            &["daemon-reload".to_owned()],
            Duration::from_secs(15),
        )
        .await;
        return Err("FAILED: systemd rejected the K3s unit; staged files were removed".to_owned());
    }
    run_fixed_command(
        &systemctl,
        &["enable".to_owned(), "--now".to_owned(), unit.to_owned()],
        Duration::from_secs(120),
    )
    .await
    .map_err(|_| "FAILED: K3s service did not start; installed files and any created data are retained for repair".to_owned())?;

    let registration = if payload.role == "server" {
        wait_for_k3s_server(Path::new("/usr/local/bin/k3s"))
            .await
            .map_err(|_| "UNCERTAIN: K3s service started, but the server Node did not become Ready; files and data are retained for repair".to_owned())?
    } else {
        "PENDING_CLUSTER_NODE_HEALTH_CHECK".to_owned()
    };
    serde_json::to_string(&serde_json::json!({
        "schemaVersion": 1,
        "role": payload.role,
        "version": payload.version,
        "unit": unit,
        "serviceState": "ACTIVE",
        "clusterRegistration": registration,
        "tokenReturned": false
    }))
    .map_err(|_| "FAILED: unable to encode K3s install result".to_owned())
}

async fn k3s_upgrade(request: &Request, host_root: &File) -> std::result::Result<String, String> {
    use std::os::unix::fs::MetadataExt;

    let payload: K3sUpgradePayload = parse_payload(request)?;
    if !valid_k3s_upgrade_payload(&payload) {
        return Err("DENIED: K3s upgrade role or pinned version is invalid".to_owned());
    }
    if !Path::new("/run/systemd/system").is_dir() {
        return Err("DENIED: K3s upgrade requires a running systemd host".to_owned());
    }
    let systemctl = trusted_executable(&["/usr/bin/systemctl", "/bin/systemctl"])?;
    let unit = if payload.role == "server" {
        "k3s.service"
    } else {
        "k3s-agent.service"
    };
    let unit_contents = read_beneath(host_root, &format!("etc/systemd/system/{unit}"))
        .map_err(|_| "DENIED: K3s unit is not managed by the NaNPanel installer".to_owned())?;
    let expected_exec = format!("ExecStart=/usr/local/bin/k3s {}", payload.role);
    if !unit_contents.lines().any(|line| line == expected_exec)
        || !unit_contents
            .lines()
            .any(|line| line == "EnvironmentFile=/etc/systemd/system/k3s.nanpanel.env")
        || !unit_contents.lines().any(|line| line == "Restart=always")
    {
        return Err("DENIED: K3s unit differs from the supported managed unit".to_owned());
    }
    let env_file = open_beneath(
        host_root,
        "etc/systemd/system/k3s.nanpanel.env",
        libc::O_RDONLY | libc::O_CLOEXEC,
        0,
    )
    .map_err(|_| "DENIED: managed K3s environment file is missing".to_owned())?;
    let env_metadata = env_file
        .metadata()
        .map_err(|_| "DENIED: K3s environment file metadata is unavailable".to_owned())?;
    if !env_metadata.is_file() || env_metadata.uid() != 0 || env_metadata.mode() & 0o077 != 0 {
        return Err("DENIED: K3s environment file permissions are unsafe".to_owned());
    }
    let executable_parent = open_beneath(
        host_root,
        "usr/local/bin",
        libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC,
        0,
    )
    .map_err(|_| "DENIED: /usr/local/bin is unavailable or unsafe".to_owned())?;
    let current_binary = open_beneath(
        &executable_parent,
        "k3s",
        libc::O_RDONLY | libc::O_CLOEXEC,
        0,
    )
    .map_err(|_| "DENIED: managed K3s executable is missing".to_owned())?;
    let metadata = current_binary
        .metadata()
        .map_err(|_| "DENIED: K3s executable metadata is unavailable".to_owned())?;
    if !metadata.is_file() || metadata.uid() != 0 || metadata.mode() & 0o022 != 0 {
        return Err("DENIED: K3s executable ownership or permissions are unsafe".to_owned());
    }
    const MAX_K3S_BINARY_BYTES: u64 = 128 * 1024 * 1024;
    if metadata.len() == 0 || metadata.len() > MAX_K3S_BINARY_BYTES {
        return Err("DENIED: managed K3s executable size is invalid".to_owned());
    }
    let version_output = run_fixed_command(
        Path::new("/usr/local/bin/k3s"),
        &["--version".to_owned()],
        Duration::from_secs(10),
    )
    .await?;
    let current_version = version_output
        .split_whitespace()
        .find(|part| valid_k3s_version(part))
        .ok_or_else(|| "DENIED: current K3s version could not be verified".to_owned())?;
    if !valid_k3s_upgrade_path(current_version, &payload.version) {
        return Err(
            "DENIED: K3s upgrade must move forward by at most one Kubernetes minor version"
                .to_owned(),
        );
    }
    let architecture = match std::env::consts::ARCH {
        "x86_64" => ("amd64", ""),
        "aarch64" => ("arm64", "-arm64"),
        "arm" => ("arm", "-armhf"),
        "s390x" => ("s390x", "-s390x"),
        _ => return Err("DENIED: K3s binary is unavailable for this architecture".to_owned()),
    };
    let base = format!(
        "https://github.com/k3s-io/k3s/releases/download/{}/k3s",
        payload.version.replace('+', "%2B")
    );
    let binary_url = format!("{base}{}", architecture.1);
    let checksum_url = format!(
        "https://github.com/k3s-io/k3s/releases/download/{}/sha256sum-{}.txt",
        payload.version.replace('+', "%2B"),
        architecture.0
    );
    let client = reqwest::Client::builder()
        .https_only(true)
        .timeout(Duration::from_secs(120))
        .redirect(reqwest::redirect::Policy::custom(|attempt| {
            let host = attempt.url().host_str().unwrap_or_default();
            if attempt.previous().len() >= 5
                || attempt.url().scheme() != "https"
                || !matches!(
                    host,
                    "github.com"
                        | "release-assets.githubusercontent.com"
                        | "objects.githubusercontent.com"
                )
            {
                attempt.stop()
            } else {
                attempt.follow()
            }
        }))
        .user_agent("NaNPanel-K3s-Upgrader")
        .build()
        .map_err(|_| "FAILED: unable to initialize the pinned K3s download client".to_owned())?;
    let (checksums, binary) = tokio::try_join!(
        download_k3s_asset(&client, &checksum_url),
        download_k3s_asset(&client, &binary_url)
    )?;
    let expected_name = format!("k3s{}", architecture.1);
    let expected_digest = String::from_utf8(checksums)
        .ok()
        .and_then(|checksums| {
            checksums.lines().find_map(|line| {
                let mut columns = line.split_whitespace();
                let digest = columns.next()?;
                let name = columns.next()?.trim_start_matches('*');
                (name == expected_name && digest.len() == 64).then(|| digest.to_ascii_lowercase())
            })
        })
        .ok_or_else(|| {
            "FAILED: release checksum does not contain the expected K3s binary".to_owned()
        })?;
    if hex_digest(&Sha256::digest(&binary)) != expected_digest {
        return Err("FAILED: downloaded K3s binary does not match the release checksum".to_owned());
    }

    const NEXT_BINARY: &str = "k3s.nanpanel.next";
    const ROLLBACK_BINARY: &str = "k3s.nanpanel.rollback";
    if host_entry_exists(&executable_parent, NEXT_BINARY)?
        || host_entry_exists(&executable_parent, ROLLBACK_BINARY)?
    {
        return Err("DENIED: previous K3s upgrade staging files require manual review".to_owned());
    }
    write_new_beneath(&executable_parent, NEXT_BINARY, &binary, 0o755)
        .map_err(|_| "FAILED: unable to stage the verified K3s executable".to_owned())?;
    if run_fixed_command(
        &systemctl,
        &["stop".to_owned(), unit.to_owned()],
        Duration::from_secs(90),
    )
    .await
    .is_err()
    {
        let _ = remove_entry_beneath(&executable_parent, NEXT_BINARY);
        return Err(
            "FAILED: K3s service did not stop; the current version is unchanged".to_owned(),
        );
    }
    if rename_entry_beneath(&executable_parent, "k3s", ROLLBACK_BINARY, true).is_err() {
        let _ = remove_entry_beneath(&executable_parent, NEXT_BINARY);
        let _ = run_fixed_command(
            &systemctl,
            &["start".to_owned(), unit.to_owned()],
            Duration::from_secs(90),
        )
        .await;
        return Err("FAILED: unable to preserve the previous K3s executable".to_owned());
    }
    if rename_entry_beneath(&executable_parent, NEXT_BINARY, "k3s", true).is_err() {
        let restored =
            rename_entry_beneath(&executable_parent, ROLLBACK_BINARY, "k3s", true).is_ok();
        let started = run_fixed_command(
            &systemctl,
            &["start".to_owned(), unit.to_owned()],
            Duration::from_secs(90),
        )
        .await
        .is_ok();
        let _ = remove_entry_beneath(&executable_parent, NEXT_BINARY);
        return Err(if restored && started {
            "FAILED: upgrade publication failed; the previous K3s version was restored".to_owned()
        } else {
            "UNCERTAIN: upgrade publication failed and automatic recovery needs manual review"
                .to_owned()
        });
    }
    let started = run_fixed_command(
        &systemctl,
        &["start".to_owned(), unit.to_owned()],
        Duration::from_secs(120),
    )
    .await;
    let health = if started.is_ok() && payload.role == "server" {
        wait_for_k3s_server(Path::new("/usr/local/bin/k3s"))
            .await
            .map(|_| ())
    } else if started.is_ok() {
        run_fixed_command(
            &systemctl,
            &["is-active".to_owned(), unit.to_owned()],
            Duration::from_secs(10),
        )
        .await
        .and_then(|state| {
            (state == "active")
                .then_some(())
                .ok_or_else(|| "FAILED: K3s agent service is not active after upgrade".to_owned())
        })
    } else {
        started.map(|_| ())
    };
    if let Err(failure) = health {
        let _ = run_fixed_command(
            &systemctl,
            &["stop".to_owned(), unit.to_owned()],
            Duration::from_secs(90),
        )
        .await;
        let removed_new = remove_entry_beneath(&executable_parent, "k3s").is_ok();
        let restored = removed_new
            && rename_entry_beneath(&executable_parent, ROLLBACK_BINARY, "k3s", true).is_ok();
        let recovered = restored
            && run_fixed_command(
                &systemctl,
                &["start".to_owned(), unit.to_owned()],
                Duration::from_secs(120),
            )
            .await
            .is_ok();
        return Err(if recovered {
            format!("FAILED: K3s upgrade health gate failed; previous version restored ({failure})")
        } else {
            format!(
                "UNCERTAIN: K3s upgrade health gate failed and rollback needs manual review ({failure})"
            )
        });
    }
    remove_entry_beneath(&executable_parent, ROLLBACK_BINARY).map_err(|_| {
        "UNCERTAIN: K3s is healthy but rollback binary cleanup needs review".to_owned()
    })?;
    serde_json::to_string(&serde_json::json!({
        "schemaVersion": 1,
        "role": payload.role,
        "previousVersion": current_version,
        "version": payload.version,
        "unit": unit,
        "serviceState": "ACTIVE",
        "clusterRegistration": if payload.role == "server" { "READY" } else { "PENDING_CLUSTER_NODE_HEALTH_CHECK" },
        "rollbackOnHealthFailure": true
    }))
    .map_err(|_| "FAILED: unable to encode K3s upgrade result".to_owned())
}

async fn kubeedge_edgecore_join(
    request: &Request,
    host_root: &File,
) -> std::result::Result<String, String> {
    let payload: KubeEdgeJoinPayload = parse_payload(request)?;
    if !valid_kubeedge_join_payload(&payload) {
        return Err("DENIED: KubeEdge EdgeCore join parameters are invalid".to_owned());
    }
    if !Path::new("/run/systemd/system").is_dir() {
        return Err("DENIED: EdgeCore join requires a running systemd host".to_owned());
    }

    let systemd_directory = open_beneath(
        host_root,
        "etc/systemd/system",
        libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC,
        0,
    )
    .map_err(|_| "DENIED: host systemd unit directory is unavailable or unsafe".to_owned())?;
    if host_entry_exists(&systemd_directory, "edgecore.service")? {
        return Err("DENIED: an EdgeCore service already exists on this host".to_owned());
    }

    let keadm = trusted_executable(&["/usr/local/bin/keadm", "/usr/bin/keadm"])?;
    let systemctl = trusted_executable(&["/usr/bin/systemctl", "/bin/systemctl"])?;
    let args = vec![
        "join".to_owned(),
        format!("--cloudcore-ipport={}", payload.cloud_core_endpoint),
        format!("--token={}", payload.token),
        format!("--kubeedge-version={}", payload.version),
    ];
    if run_fixed_command(&keadm, &args, Duration::from_secs(600))
        .await
        .is_err()
    {
        return Err(
            "UNCERTAIN: keadm join did not complete; inspect EdgeCore service and cluster registration before retrying"
                .to_owned(),
        );
    }
    let service_state = run_fixed_command(
        &systemctl,
        &["is-active".to_owned(), "edgecore.service".to_owned()],
        Duration::from_secs(10),
    )
    .await
    .map_err(|_| "UNCERTAIN: keadm completed but EdgeCore service is not active".to_owned())?;
    if service_state != "active" {
        return Err("UNCERTAIN: keadm completed but EdgeCore service is not active".to_owned());
    }
    serde_json::to_string(&serde_json::json!({
        "schemaVersion": 1,
        "component": "edgecore",
        "version": payload.version,
        "service": "edgecore.service",
        "serviceState": "ACTIVE",
        "bootstrapTokenReturned": false
    }))
    .map_err(|_| "FAILED: unable to encode EdgeCore join result".to_owned())
}

fn valid_kubeedge_join_payload(payload: &KubeEdgeJoinPayload) -> bool {
    let version = payload.version.strip_prefix('v').unwrap_or_default();
    let version_parts = version.split('.').collect::<Vec<_>>();
    let stable_version = version_parts.len() == 3
        && version_parts
            .iter()
            .all(|part| !part.is_empty() && part.bytes().all(|byte| byte.is_ascii_digit()));
    let endpoint = payload.cloud_core_endpoint.as_str();
    let endpoint_valid = endpoint.len() <= 260
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
        });
    let token = payload.token.as_str();
    let token_valid = (32..=4096).contains(&token.len())
        && token
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
        && token.split('.').count() == 3
        && token.split('.').all(|part| !part.is_empty());
    stable_version && endpoint_valid && token_valid
}

fn valid_k3s_upgrade_path(current: &str, target: &str) -> bool {
    fn parts(version: &str) -> Option<(u64, u64, u64, u64)> {
        let release = version.strip_prefix('v')?;
        let (kubernetes, revision) = release.split_once("+k3s")?;
        let mut components = kubernetes.split('.');
        Some((
            components.next()?.parse().ok()?,
            components.next()?.parse().ok()?,
            components.next()?.parse().ok()?,
            revision.parse().ok()?,
        ))
    }
    let Some(current_parts) = parts(current) else {
        return false;
    };
    let Some(target_parts) = parts(target) else {
        return false;
    };
    target_parts.0 == 1
        && target_parts.0 == current_parts.0
        && target_parts.1 <= current_parts.1.saturating_add(1)
        && target_parts > current_parts
}

async fn download_k3s_asset(
    client: &reqwest::Client,
    url: &str,
) -> std::result::Result<Vec<u8>, String> {
    const MAX_K3S_ASSET_BYTES: u64 = 128 * 1024 * 1024;
    let response = client
        .get(url)
        .send()
        .await
        .map_err(|_| "FAILED: K3s release asset download failed".to_owned())?;
    if !response.status().is_success()
        || response
            .content_length()
            .is_some_and(|length| length > MAX_K3S_ASSET_BYTES)
    {
        return Err("FAILED: K3s release asset response is invalid or too large".to_owned());
    }
    let bytes = response
        .bytes()
        .await
        .map_err(|_| "FAILED: K3s release asset body could not be read".to_owned())?;
    if bytes.is_empty() || bytes.len() as u64 > MAX_K3S_ASSET_BYTES {
        return Err("FAILED: K3s release asset is empty or too large".to_owned());
    }
    Ok(bytes.to_vec())
}

fn hex_digest(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut digest = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        digest.push(HEX[(byte >> 4) as usize] as char);
        digest.push(HEX[(byte & 0x0f) as usize] as char);
    }
    digest
}

fn host_entry_exists(parent: &File, name: &str) -> std::result::Result<bool, String> {
    match open_beneath(parent, name, libc::O_RDONLY | libc::O_CLOEXEC, 0) {
        Ok(_) => Ok(true),
        Err(error)
            if error.chain().any(|source| {
                source
                    .downcast_ref::<std::io::Error>()
                    .is_some_and(|io| io.kind() == std::io::ErrorKind::NotFound)
            }) =>
        {
            Ok(false)
        }
        Err(_) => Err("DENIED: K3s installation target could not be safely inspected".to_owned()),
    }
}

fn write_new_beneath(parent: &File, name: &str, bytes: &[u8], mode: u32) -> Result<()> {
    use std::io::Write;
    use std::os::fd::AsRawFd;
    if name.is_empty() || name.contains('/') || name.contains('\0') {
        bail!("K3s installation filename is invalid");
    }
    let temporary = format!(".nanpanel-{}.tmp", uuid::Uuid::new_v4());
    let temporary_c = CString::new(temporary.as_str())?;
    let target_c = CString::new(name)?;
    // SAFETY: parent is a live directory FD and the temporary name is a
    // generated single component. O_EXCL and O_NOFOLLOW prevent path reuse.
    let fd = unsafe {
        libc::openat(
            parent.as_raw_fd(),
            temporary_c.as_ptr(),
            libc::O_WRONLY | libc::O_CREAT | libc::O_EXCL | libc::O_CLOEXEC | libc::O_NOFOLLOW,
            0o600,
        )
    };
    if fd < 0 {
        return Err(std::io::Error::last_os_error())
            .context("create K3s installation staging file");
    }
    // SAFETY: openat returned a unique, valid descriptor which is owned here.
    let mut file = unsafe { File::from_raw_fd(fd) };
    let write_result = (|| -> Result<()> {
        file.write_all(bytes)?;
        file.sync_all()?;
        file.set_permissions(std::fs::Permissions::from_mode(mode))?;
        // SAFETY: both names are NUL-terminated, parent is a live directory
        // FD, and RENAME_NOREPLACE prevents replacing a file created in a race.
        let renamed = unsafe {
            libc::syscall(
                libc::SYS_renameat2,
                parent.as_raw_fd(),
                temporary_c.as_ptr(),
                parent.as_raw_fd(),
                target_c.as_ptr(),
                libc::RENAME_NOREPLACE,
            ) as i32
        };
        if renamed < 0 {
            return Err(std::io::Error::last_os_error()).context("publish K3s installation file");
        }
        parent.sync_all()?;
        Ok(())
    })();
    if write_result.is_err() {
        // SAFETY: temporary_c identifies only the staging file created above.
        unsafe { libc::unlinkat(parent.as_raw_fd(), temporary_c.as_ptr(), 0) };
    }
    write_result
}

fn remove_entry_beneath(parent: &File, name: &str) -> Result<()> {
    use std::os::fd::AsRawFd;
    let name = CString::new(name)?;
    // SAFETY: parent is a live directory FD and name is one path component.
    let removed = unsafe { libc::unlinkat(parent.as_raw_fd(), name.as_ptr(), 0) };
    if removed < 0 {
        let error = std::io::Error::last_os_error();
        if error.kind() != std::io::ErrorKind::NotFound {
            return Err(error).context("remove K3s installation file");
        }
    }
    Ok(())
}

#[cfg(target_os = "linux")]
fn rename_entry_beneath(parent: &File, source: &str, target: &str, no_replace: bool) -> Result<()> {
    use std::os::fd::AsRawFd;
    if [source, target]
        .iter()
        .any(|name| name.is_empty() || name.contains('/') || name.contains('\0'))
    {
        bail!("K3s upgrade filenames must be single path components");
    }
    let source = CString::new(source)?;
    let target = CString::new(target)?;
    let flags = if no_replace {
        libc::RENAME_NOREPLACE
    } else {
        0
    };
    // SAFETY: parent is a live directory FD and both names are validated single
    // components; RENAME_NOREPLACE prevents replacing a file created in a race.
    let renamed = unsafe {
        libc::syscall(
            libc::SYS_renameat2,
            parent.as_raw_fd(),
            source.as_ptr(),
            parent.as_raw_fd(),
            target.as_ptr(),
            flags,
        ) as i32
    };
    if renamed < 0 {
        return Err(std::io::Error::last_os_error()).context("rename managed K3s file");
    }
    parent.sync_all()?;
    Ok(())
}

#[cfg(not(target_os = "linux"))]
fn rename_entry_beneath(
    _parent: &File,
    _source: &str,
    _target: &str,
    _no_replace: bool,
) -> Result<()> {
    bail!("this privileged-helper PoC requires Linux openat2")
}

async fn wait_for_k3s_server(binary: &Path) -> std::result::Result<String, String> {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(180);
    while tokio::time::Instant::now() < deadline {
        let result = run_fixed_command(
            binary,
            &[
                "kubectl".to_owned(),
                "get".to_owned(),
                "nodes".to_owned(),
                "-o".to_owned(),
                "json".to_owned(),
            ],
            Duration::from_secs(8),
        )
        .await;
        if let Ok(output) = result
            && let Ok(nodes) = serde_json::from_str::<serde_json::Value>(&output)
            && nodes["items"].as_array().is_some_and(|items| {
                items.iter().any(|node| {
                    node["status"]["conditions"]
                        .as_array()
                        .is_some_and(|conditions| {
                            conditions.iter().any(|condition| {
                                condition["type"] == "Ready" && condition["status"] == "True"
                            })
                        })
                })
            })
        {
            return Ok("SERVER_NODE_READY".to_owned());
        }
        tokio::time::sleep(Duration::from_secs(3)).await;
    }
    Err("UNCERTAIN: K3s service started but the server Node did not become Ready within 180 seconds".to_owned())
}

async fn collect_netbird_peer_paths(
    netbird_binary: Option<&Path>,
) -> std::result::Result<String, String> {
    let binary = netbird_binary
        .ok_or_else(|| "UNAVAILABLE: trusted NetBird client binary is not installed".to_owned())?;
    let mut child = tokio::process::Command::new(binary)
        .env_clear()
        .args(["status", "--json"])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .kill_on_drop(true)
        .spawn()
        .map_err(|_| "FAILED: unable to query NetBird client status".to_owned())?;
    let stdout = child
        .stdout
        .take()
        .ok_or_else(|| "FAILED: NetBird status output is unavailable".to_owned())?;
    let mut output = Vec::new();
    let read_result = tokio::time::timeout(
        Duration::from_secs(5),
        stdout
            .take((MAX_NETBIRD_STATUS_BYTES + 1) as u64)
            .read_to_end(&mut output),
    )
    .await
    .map_err(|_| "FAILED: NetBird status query timed out".to_owned())?
    .map_err(|_| "FAILED: unable to read NetBird status output".to_owned());
    if let Err(error) = read_result {
        let _ = child.kill().await;
        return Err(error);
    }
    if output.len() > MAX_NETBIRD_STATUS_BYTES {
        let _ = child.kill().await;
        return Err("FAILED: NetBird status output exceeds the size limit".to_owned());
    }
    let exit = tokio::time::timeout(Duration::from_secs(2), child.wait())
        .await
        .map_err(|_| "FAILED: NetBird status process did not exit".to_owned())?
        .map_err(|_| "FAILED: unable to read NetBird status process result".to_owned())?;
    if !exit.success() {
        return Err("UNAVAILABLE: NetBird client status could not be read".to_owned());
    }
    let peers = parse_netbird_peer_paths(&output)?;
    let response = serde_json::to_string(&peers)
        .map_err(|_| "FAILED: unable to encode NetBird peer paths".to_owned())?;
    if response.len() > MAX_NETBIRD_PEER_RESPONSE_BYTES {
        return Err("FAILED: NetBird peer path response exceeds the size limit".to_owned());
    }
    Ok(response)
}

fn parse_netbird_peer_paths(input: &[u8]) -> std::result::Result<Vec<NetbirdPeerPath>, String> {
    if input.is_empty() || input.len() > MAX_NETBIRD_STATUS_BYTES {
        return Err("FAILED: NetBird status output is empty or exceeds the size limit".to_owned());
    }
    let status = serde_json::from_slice::<NetbirdCliStatus>(input)
        .map_err(|_| "FAILED: NetBird status output is invalid".to_owned())?;
    if status.peers.details.len() > MAX_NETBIRD_PEERS {
        return Err("FAILED: NetBird peer count exceeds the reporting limit".to_owned());
    }
    let mut names = HashSet::with_capacity(status.peers.details.len());
    let mut peers = Vec::with_capacity(status.peers.details.len());
    for peer in status.peers.details {
        if !valid_netbird_peer_name(&peer.fqdn) {
            return Err("FAILED: NetBird peer identity is invalid".to_owned());
        }
        if !names.insert(peer.fqdn.to_ascii_lowercase()) {
            return Err("FAILED: NetBird peer identities are not unique".to_owned());
        }
        let peer_status = match peer.status.to_ascii_lowercase().as_str() {
            "connected" => "CONNECTED",
            "disconnected" => "DISCONNECTED",
            "connecting" => "CONNECTING",
            _ => "UNKNOWN",
        };
        let connection_type = if peer_status != "CONNECTED" {
            "UNKNOWN"
        } else {
            match peer
                .connection_type
                .as_deref()
                .unwrap_or_default()
                .to_ascii_lowercase()
                .as_str()
            {
                "p2p" => "P2P",
                "relay" | "relayed" => "RELAY",
                _ => "UNKNOWN",
            }
        };
        peers.push(NetbirdPeerPath {
            peer_name: peer.fqdn,
            status: peer_status.to_owned(),
            connection_type: connection_type.to_owned(),
        });
    }
    Ok(peers)
}

fn valid_netbird_peer_name(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 253
        && value.parse::<std::net::IpAddr>().is_err()
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct PackageInstallPayload {
    packages: Vec<String>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct ProtectedFilePayload {
    content: String,
}

async fn install_packages(request: &Request) -> std::result::Result<String, String> {
    let payload = parse_payload::<PackageInstallPayload>(request)?;
    if payload.packages.is_empty()
        || payload.packages.len() > 16
        || payload
            .packages
            .iter()
            .any(|name| !valid_package_name(name))
    {
        return Err("DENIED: package list is empty or invalid".to_owned());
    }
    let mut unique = HashSet::new();
    if payload.packages.iter().any(|name| !unique.insert(name)) {
        return Err("DENIED: package list contains duplicates".to_owned());
    }
    let (program, mut args) = if Path::new("/usr/bin/apt-get").is_file() {
        (
            Path::new("/usr/bin/apt-get"),
            vec![
                "--assume-yes".to_owned(),
                "--no-install-recommends".to_owned(),
                "install".to_owned(),
            ],
        )
    } else if Path::new("/usr/bin/dnf").is_file() {
        (
            Path::new("/usr/bin/dnf"),
            vec!["--assumeyes".to_owned(), "install".to_owned()],
        )
    } else {
        return Err("FAILED: no supported fixed package manager was found".to_owned());
    };
    args.extend(payload.packages);
    run_fixed_command(program, &args, Duration::from_secs(300)).await
}

fn write_protected_file(
    request: &Request,
    write_root: &File,
) -> std::result::Result<String, String> {
    let payload = parse_payload::<ProtectedFilePayload>(request)?;
    if !valid_file_name(&request.target) {
        return Err("DENIED: protected file name is invalid".to_owned());
    }
    if payload.content.len() > MAX_WRITE_BYTES || payload.content.contains('\0') {
        return Err("DENIED: protected file content exceeds 32 KiB or contains NUL".to_owned());
    }
    atomic_write_beneath(write_root, &request.target, payload.content.as_bytes())
        .map_err(|error| format!("FAILED: write protected file: {error}"))?;
    Ok(serde_json::json!({
        "path": request.target,
        "bytesWritten": payload.content.len(),
        "mode": "0600"
    })
    .to_string())
}

async fn expose_network_port(request: &Request) -> std::result::Result<String, String> {
    let payload = parse_payload::<NetworkFirewallPayload>(request)?;
    if !(30..=300).contains(&payload.rollback_seconds)
        || !valid_network_interface(&payload.interface_name)
        || !matches!(payload.protocol.as_str(), "tcp" | "udp")
        || payload.port == 0
    {
        return Err(
            "DENIED: firewall interface, protocol, port, or rollback is invalid".to_owned(),
        );
    }
    if !valid_network_rule_name(&request.target) {
        return Err("DENIED: network rule name is invalid".to_owned());
    }
    let rule = NetworkRuleSpec {
        interface: payload.interface_name,
        protocol: payload.protocol,
        port: payload.port,
    };
    let operation_id = uuid::Uuid::parse_str(&request.operation_id)
        .map_err(|_| "DENIED: network operation ID must be a UUID".to_owned())?
        .hyphenated()
        .to_string();
    let systemd_run = trusted_executable(&["/usr/bin/systemd-run", "/bin/systemd-run"])?;
    let helper_binary = std::env::current_exe()
        .map_err(|error| format!("FAILED: locate Helper binary: {error}"))?;
    validate_trusted_executable(&helper_binary)
        .map_err(|error| format!("FAILED: configured Helper binary is not trusted: {error}"))?;

    schedule_network_rollback(
        &systemd_run,
        &helper_binary,
        &operation_id,
        payload.rollback_seconds,
    )
    .await?;
    let verification = run_network_apply_worker(
        &systemd_run,
        &helper_binary,
        &operation_id,
        &rule,
        payload.rollback_seconds,
    )
    .await
    .map_err(|error| format!("{error}; automatic firewall rollback remains scheduled"))?;
    let verification: serde_json::Value = serde_json::from_str(&verification).map_err(|error| {
        format!(
            "UNCERTAIN: firewall worker returned unverified output; rollback remains scheduled: {error}"
        )
    })?;
    Ok(serde_json::json!({
        "rule": request.target,
        "interface": rule.interface,
        "protocol": rule.protocol,
        "port": rule.port,
        "verified": true,
        "automaticRollback": true,
        "rollbackAfterSeconds": payload.rollback_seconds,
        "verification": verification,
    })
    .to_string())
}

pub async fn apply_network_firewall_rule(
    operation_id: &str,
    interface: &str,
    protocol: &str,
    port: u16,
    rollback_seconds: u16,
) -> Result<String> {
    if !nix::unistd::geteuid().is_root() {
        bail!("network apply worker must run as root");
    }
    if !(30..=300).contains(&rollback_seconds)
        || !valid_network_interface(interface)
        || !matches!(protocol, "tcp" | "udp")
        || port == 0
    {
        bail!("network apply worker arguments are invalid");
    }
    let operation_id = uuid::Uuid::parse_str(operation_id)
        .context("network apply operation ID must be a UUID")?
        .hyphenated()
        .to_string();
    let rule = NetworkRuleSpec {
        interface: interface.to_owned(),
        protocol: protocol.to_owned(),
        port,
    };
    let nft = trusted_executable(&["/usr/sbin/nft", "/usr/bin/nft"]).map_err(anyhow::Error::msg)?;
    ensure_network_chain(&nft)
        .await
        .map_err(anyhow::Error::msg)?;
    let rule_comment = format!("nanpanel:{operation_id}");
    let script = format!(
        "add rule inet nanpanel_agent input iifname \"{}\" {} dport {} ct state new,established accept comment \"{}\"\n",
        rule.interface, rule.protocol, rule.port, rule_comment
    );
    run_nft_script(&nft, &script)
        .await
        .map_err(anyhow::Error::msg)?;
    let rules = list_network_chain(&nft)
        .await
        .map_err(anyhow::Error::msg)?
        .context("firewall rule chain disappeared before verification")?;
    let handle = find_network_rule_handle(&rules, &rule_comment, Some(&rule))
        .map_err(anyhow::Error::msg)?
        .context("firewall rule was not found during verification")?;
    Ok(serde_json::json!({
        "verified": true,
        "ruleHandle": handle,
        "rollbackAfterSeconds": rollback_seconds,
    })
    .to_string())
}

pub async fn rollback_network_firewall_rule(operation_id: &str) -> Result<()> {
    if !nix::unistd::geteuid().is_root() {
        bail!("network rollback helper must run as root");
    }
    let operation_id = uuid::Uuid::parse_str(operation_id)
        .context("network rollback operation ID must be a UUID")?
        .hyphenated()
        .to_string();
    let rule_comment = format!("nanpanel:{operation_id}");
    let nft = trusted_executable(&["/usr/sbin/nft", "/usr/bin/nft"]).map_err(anyhow::Error::msg)?;
    let Some(chain) = list_network_chain(&nft).await.map_err(anyhow::Error::msg)? else {
        audit(
            0,
            &operation_id,
            "network.firewall.rollback",
            &operation_id,
            "already-absent",
        );
        return Ok(());
    };
    let Some(handle) =
        find_network_rule_handle(&chain, &rule_comment, None).map_err(anyhow::Error::msg)?
    else {
        audit(
            0,
            &operation_id,
            "network.firewall.rollback",
            &operation_id,
            "already-absent",
        );
        return Ok(());
    };
    let args = [
        "delete".to_owned(),
        "rule".to_owned(),
        "inet".to_owned(),
        "nanpanel_agent".to_owned(),
        "input".to_owned(),
        "handle".to_owned(),
        handle.to_string(),
    ];
    let deletion = run_fixed_command(&nft, &args, Duration::from_secs(5)).await;
    let remaining = list_network_chain(&nft).await.map_err(anyhow::Error::msg)?;
    if remaining.as_ref().is_some_and(|value| {
        find_network_rule_handle(value, &rule_comment, None)
            .ok()
            .flatten()
            .is_some()
    }) {
        deletion.map_err(anyhow::Error::msg)?;
        bail!("network firewall rule remains after rollback verification");
    }
    audit(
        0,
        &operation_id,
        "network.firewall.rollback",
        &operation_id,
        "succeeded",
    );
    Ok(())
}

fn trusted_executable(candidates: &[&str]) -> std::result::Result<std::path::PathBuf, String> {
    candidates
        .iter()
        .map(Path::new)
        .find(|path| validate_trusted_executable(path).is_ok())
        .map(Path::to_path_buf)
        .ok_or_else(|| "FAILED: required root-owned system executable is unavailable".to_owned())
}

fn validate_trusted_executable(path: &Path) -> Result<()> {
    use std::os::unix::fs::MetadataExt;
    let metadata = std::fs::metadata(path)
        .with_context(|| format!("inspect fixed executable {}", path.display()))?;
    if !metadata.is_file()
        || metadata.uid() != 0
        || metadata.mode() & 0o022 != 0
        || metadata.mode() & 0o111 == 0
    {
        bail!("fixed executable is not a root-owned non-writable executable");
    }
    Ok(())
}

async fn ensure_network_chain(nft: &Path) -> std::result::Result<(), String> {
    let tables = nft_json(nft, &["--json", "list", "tables"]).await?;
    let table_exists = tables
        .get("nftables")
        .and_then(serde_json::Value::as_array)
        .is_some_and(|items| {
            items.iter().any(|item| {
                item.get("table").is_some_and(|table| {
                    table.get("family").and_then(serde_json::Value::as_str) == Some("inet")
                        && table.get("name").and_then(serde_json::Value::as_str)
                            == Some("nanpanel_agent")
                })
            })
        });
    if !table_exists {
        let args = ["add", "table", "inet", "nanpanel_agent"].map(str::to_owned);
        run_fixed_command(nft, &args, Duration::from_secs(5)).await?;
    }
    let table = nft_json(nft, &["--json", "list", "table", "inet", "nanpanel_agent"]).await?;
    let chain = table
        .get("nftables")
        .and_then(serde_json::Value::as_array)
        .and_then(|items| {
            items
                .iter()
                .find_map(|item| item.get("chain"))
                .filter(|chain| {
                    chain.get("name").and_then(serde_json::Value::as_str) == Some("input")
                })
        });
    if chain.is_none() {
        run_nft_script(
            nft,
            "add chain inet nanpanel_agent input { type filter hook input priority 0; policy accept; }\n",
        )
        .await?;
    } else if !chain.is_some_and(is_owned_network_chain) {
        return Err("DENIED: existing nanpanel_agent input chain has unexpected policy".to_owned());
    }
    let verified = nft_json(nft, &["--json", "list", "table", "inet", "nanpanel_agent"]).await?;
    let valid = verified
        .get("nftables")
        .and_then(serde_json::Value::as_array)
        .and_then(|items| items.iter().find_map(|item| item.get("chain")))
        .is_some_and(is_owned_network_chain);
    if !valid {
        return Err("FAILED: dedicated firewall chain could not be verified".to_owned());
    }
    Ok(())
}

fn is_owned_network_chain(chain: &serde_json::Value) -> bool {
    chain.get("family").and_then(serde_json::Value::as_str) == Some("inet")
        && chain.get("table").and_then(serde_json::Value::as_str) == Some("nanpanel_agent")
        && chain.get("name").and_then(serde_json::Value::as_str) == Some("input")
        && chain.get("type").and_then(serde_json::Value::as_str) == Some("filter")
        && chain.get("hook").and_then(serde_json::Value::as_str) == Some("input")
        && chain.get("policy").and_then(serde_json::Value::as_str) == Some("accept")
        && chain.get("prio").and_then(serde_json::Value::as_i64) == Some(0)
}

async fn list_network_chain(nft: &Path) -> std::result::Result<Option<serde_json::Value>, String> {
    let tables = nft_json(nft, &["--json", "list", "tables"]).await?;
    let exists = tables
        .get("nftables")
        .and_then(serde_json::Value::as_array)
        .is_some_and(|items| {
            items.iter().any(|item| {
                item.get("table").is_some_and(|table| {
                    table.get("family").and_then(serde_json::Value::as_str) == Some("inet")
                        && table.get("name").and_then(serde_json::Value::as_str)
                            == Some("nanpanel_agent")
                })
            })
        });
    if !exists {
        return Ok(None);
    }
    let table = nft_json(nft, &["--json", "list", "table", "inet", "nanpanel_agent"]).await?;
    let has_chain = table
        .get("nftables")
        .and_then(serde_json::Value::as_array)
        .is_some_and(|items| {
            items.iter().any(|item| {
                item.get("chain").is_some_and(|chain| {
                    chain.get("name").and_then(serde_json::Value::as_str) == Some("input")
                })
            })
        });
    if has_chain { Ok(Some(table)) } else { Ok(None) }
}

async fn nft_json(
    nft: &Path,
    arguments: &[&str],
) -> std::result::Result<serde_json::Value, String> {
    let args = arguments
        .iter()
        .map(|argument| (*argument).to_owned())
        .collect::<Vec<_>>();
    let output = run_fixed_command(nft, &args, Duration::from_secs(5)).await?;
    serde_json::from_str(&output)
        .map_err(|error| format!("FAILED: nft returned invalid JSON: {error}"))
}

async fn run_nft_script(nft: &Path, script: &str) -> std::result::Result<(), String> {
    use tokio::io::AsyncWriteExt;
    let mut child = tokio::process::Command::new(nft)
        .args(["-f", "-"])
        .env("LC_ALL", "C")
        .kill_on_drop(true)
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map_err(|error| format!("FAILED: start fixed nft command: {error}"))?;
    let Some(mut stdin) = child.stdin.take() else {
        return Err("FAILED: fixed nft command input is unavailable".to_owned());
    };
    if let Err(error) =
        tokio::time::timeout(Duration::from_secs(5), stdin.write_all(script.as_bytes())).await
    {
        let _ = child.kill().await;
        return Err(format!("UNCERTAIN: nft script input timed out: {error}"));
    }
    drop(stdin);
    match tokio::time::timeout(Duration::from_secs(5), child.wait()).await {
        Ok(Ok(status)) if status.success() => Ok(()),
        Ok(Ok(_)) => Err("FAILED: nft rejected the bounded firewall change".to_owned()),
        Ok(Err(error)) => Err(format!("UNCERTAIN: wait for nft command: {error}")),
        Err(_) => {
            let _ = child.kill().await;
            Err("UNCERTAIN: nft command exceeded its time limit".to_owned())
        }
    }
}

async fn schedule_network_rollback(
    systemd_run: &Path,
    helper_binary: &Path,
    operation_id: &str,
    rollback_seconds: u16,
) -> std::result::Result<(), String> {
    let unit = format!(
        "nanpanel-network-rollback-{}",
        operation_id.replace('-', "")
    );
    let delay = format!("--on-active={}s", rollback_seconds);
    let properties = [
        "--property=NoNewPrivileges=yes",
        "--property=CapabilityBoundingSet=CAP_NET_ADMIN",
        "--property=AmbientCapabilities=CAP_NET_ADMIN",
        "--property=PrivateTmp=yes",
        "--property=ProtectSystem=strict",
        "--property=ProtectHome=yes",
        "--property=ProtectKernelTunables=yes",
        "--property=ProtectKernelModules=yes",
        "--property=ProtectControlGroups=yes",
    ];
    let mut args = vec![
        "--quiet".to_owned(),
        format!("--unit={unit}"),
        delay,
        "--collect".to_owned(),
    ];
    args.extend(properties.map(str::to_owned));
    args.extend([
        helper_binary.display().to_string(),
        "rollback".to_owned(),
        operation_id.to_owned(),
    ]);
    run_fixed_command(systemd_run, &args, Duration::from_secs(10))
        .await
        .map(|_| ())
        .map_err(|error| format!("FAILED: unable to arm automatic firewall rollback: {error}"))
}

async fn run_network_apply_worker(
    systemd_run: &Path,
    helper_binary: &Path,
    operation_id: &str,
    rule: &NetworkRuleSpec,
    rollback_seconds: u16,
) -> std::result::Result<String, String> {
    let unit = format!("nanpanel-network-apply-{}", operation_id.replace('-', ""));
    let properties = [
        "--property=NoNewPrivileges=yes",
        "--property=CapabilityBoundingSet=CAP_NET_ADMIN",
        "--property=AmbientCapabilities=CAP_NET_ADMIN",
        "--property=PrivateTmp=yes",
        "--property=ProtectSystem=strict",
        "--property=ProtectHome=yes",
        "--property=ProtectKernelTunables=yes",
        "--property=ProtectKernelModules=yes",
        "--property=ProtectControlGroups=yes",
    ];
    let mut args = vec![
        "--quiet".to_owned(),
        "--pipe".to_owned(),
        "--wait".to_owned(),
        "--collect".to_owned(),
        format!("--unit={unit}"),
    ];
    args.extend(properties.map(str::to_owned));
    args.extend([
        helper_binary.display().to_string(),
        "apply-firewall".to_owned(),
        operation_id.to_owned(),
        rule.interface.clone(),
        rule.protocol.clone(),
        rule.port.to_string(),
        rollback_seconds.to_string(),
    ]);
    run_fixed_command(systemd_run, &args, Duration::from_secs(20))
        .await
        .map_err(|error| format!("UNCERTAIN: bounded firewall worker failed: {error}"))
}

fn find_network_rule_handle(
    table: &serde_json::Value,
    comment: &str,
    expected: Option<&NetworkRuleSpec>,
) -> std::result::Result<Option<u64>, String> {
    let entries = table
        .get("nftables")
        .and_then(serde_json::Value::as_array)
        .ok_or_else(|| "FAILED: nft output omitted its rule list".to_owned())?;
    let matches = entries
        .iter()
        .filter_map(|entry| entry.get("rule"))
        .filter(|rule| {
            rule.get("table").and_then(serde_json::Value::as_str) == Some("nanpanel_agent")
                && rule.get("chain").and_then(serde_json::Value::as_str) == Some("input")
                && rule.get("comment").and_then(serde_json::Value::as_str) == Some(comment)
        })
        .collect::<Vec<_>>();
    if matches.len() > 1 {
        return Err("DENIED: multiple firewall rules share the operation marker".to_owned());
    }
    let Some(rule) = matches.first() else {
        return Ok(None);
    };
    if let Some(expected) = expected
        && !nft_rule_matches(rule, expected)
    {
        return Err("FAILED: firewall rule differs from the requested parameters".to_owned());
    }
    let handle = rule
        .get("handle")
        .and_then(serde_json::Value::as_u64)
        .ok_or_else(|| "FAILED: nft rule has no numeric handle".to_owned())?;
    Ok(Some(handle))
}

fn nft_rule_matches(rule: &serde_json::Value, expected: &NetworkRuleSpec) -> bool {
    let Some(expressions) = rule.get("expr").and_then(serde_json::Value::as_array) else {
        return false;
    };
    let mut interface_matches = false;
    let mut port_matches = false;
    let mut accepted = false;
    for expression in expressions {
        if expression.get("accept").is_some() {
            accepted = true;
        }
        if let Some(matched) = expression.get("match") {
            let Some(left) = matched.get("left") else {
                continue;
            };
            if let Some(meta) = left.get("meta")
                && meta.get("key").and_then(serde_json::Value::as_str) == Some("iifname")
                && matched.get("right").and_then(serde_json::Value::as_str)
                    == Some(expected.interface.as_str())
            {
                interface_matches = true;
            }
            if let Some(payload) = left.get("payload")
                && payload.get("protocol").and_then(serde_json::Value::as_str)
                    == Some(expected.protocol.as_str())
                && payload.get("field").and_then(serde_json::Value::as_str) == Some("dport")
                && matched.get("right").and_then(serde_json::Value::as_u64)
                    == Some(u64::from(expected.port))
            {
                port_matches = true;
            }
        }
    }
    interface_matches && port_matches && accepted
}

async fn manage_container(
    request: &Request,
    runtime: Option<&Path>,
) -> std::result::Result<String, String> {
    let Some(runtime) = runtime else {
        return Err(
            "DENIED: no configured rootful Docker or Podman runtime is available".to_owned(),
        );
    };
    let mut args = Vec::new();
    match request.capability.as_str() {
        "container.list" => {
            if request.payload_json.is_some() {
                return Err("DENIED: container.list does not accept a payload".to_owned());
            }
            args.extend([
                "ps".to_owned(),
                "--all".to_owned(),
                "--format".to_owned(),
                "{{.Names}} {{.Status}}".to_owned(),
            ]);
        }
        "container.logs" => {
            if request.payload_json.is_some() {
                return Err("DENIED: container.logs does not accept a payload".to_owned());
            }
            if !valid_container_name(&request.target) {
                return Err("DENIED: container name is invalid".to_owned());
            }
            args.extend([
                "logs".to_owned(),
                "--tail".to_owned(),
                "100".to_owned(),
                "--timestamps".to_owned(),
                request.target.clone(),
            ]);
        }
        "container.start" => {
            if request.payload_json.is_some() || !valid_container_name(&request.target) {
                return Err("DENIED: container start target or payload is invalid".to_owned());
            }
            args.extend(["start".to_owned(), request.target.clone()]);
        }
        "container.stop" => {
            if request.payload_json.is_some() || !valid_container_name(&request.target) {
                return Err("DENIED: container stop target or payload is invalid".to_owned());
            }
            args.extend([
                "stop".to_owned(),
                "--time".to_owned(),
                "10".to_owned(),
                request.target.clone(),
            ]);
        }
        "container.remove" => {
            if request.payload_json.is_some() || !valid_container_name(&request.target) {
                return Err("DENIED: container remove target or payload is invalid".to_owned());
            }
            args.extend([
                "rm".to_owned(),
                "--force".to_owned(),
                request.target.clone(),
            ]);
        }
        "container.deploy" => {
            let requested_spec = parse_container_deploy_spec(request)?;
            args.extend([
                "run".to_owned(),
                "--detach".to_owned(),
                "--name".to_owned(),
                request.target.clone(),
                "--restart=unless-stopped".to_owned(),
                "--memory".to_owned(),
                format!("{}m", requested_spec.memory_mb),
                "--cpus=1".to_owned(),
                "--pids-limit=256".to_owned(),
                "--security-opt=no-new-privileges".to_owned(),
                "--network=bridge".to_owned(),
                "--cap-drop=ALL".to_owned(),
            ]);
            if requested_spec.stateful {
                for capability in ["CHOWN", "DAC_OVERRIDE", "FOWNER", "SETUID", "SETGID"] {
                    args.push("--cap-add".to_owned());
                    args.push(capability.to_owned());
                }
            } else {
                args.push("--read-only".to_owned());
            }
            if let (Some(host_port), Some(container_port)) =
                (requested_spec.host_port, requested_spec.container_port)
            {
                args.extend([
                    "--publish".to_owned(),
                    format!("127.0.0.1:{host_port}:{container_port}"),
                ]);
            }
            for entry in &requested_spec.environment {
                args.extend(["--env".to_owned(), entry.clone()]);
            }
            for volume in &requested_spec.volumes {
                args.extend([
                    "--volume".to_owned(),
                    format!("{}:{}", volume.name, volume.mount_path),
                ]);
            }
            args.push(requested_spec.image);
        }
        _ => return Err("DENIED: unsupported container capability".to_owned()),
    }
    let timeout = if request.capability == "container.logs" {
        Duration::from_secs(15)
    } else {
        Duration::from_secs(60)
    };
    run_fixed_command(runtime, &args, timeout).await
}

fn parse_payload<T>(request: &Request) -> std::result::Result<T, String>
where
    T: for<'de> Deserialize<'de>,
{
    let Some(payload) = request.payload_json.as_deref() else {
        return Err("DENIED: typed operation payload is required".to_owned());
    };
    if payload.len() > MAX_PAYLOAD_BYTES {
        return Err("DENIED: operation payload exceeds the 48 KiB limit".to_owned());
    }
    serde_json::from_str(payload)
        .map_err(|_| "DENIED: operation payload schema is invalid".to_owned())
}

fn parse_container_deploy_spec(request: &Request) -> std::result::Result<ContainerSpec, String> {
    let payload = parse_payload::<ContainerDeployPayload>(request)?;
    let host_port = payload.host_port.unwrap_or_default();
    let container_port = payload.container_port.unwrap_or_default();
    let environment = payload.environment.unwrap_or_default();
    let volumes = payload.volumes.unwrap_or_default();
    let memory_mb = payload.memory_mb.unwrap_or(DEFAULT_CONTAINER_MEMORY_MB);
    let stateful = payload.stateful.unwrap_or(false);
    if !valid_container_image(&payload.image) || (host_port == 0) != (container_port == 0) {
        return Err("DENIED: container image or port mapping is invalid".to_owned());
    }
    if environment.len() > 32
        || environment
            .iter()
            .any(|entry| !valid_environment_entry(entry))
    {
        return Err("DENIED: environment variables must be bounded KEY=VALUE entries".to_owned());
    }
    let mut volume_keys = std::collections::HashSet::new();
    if volumes.len() > 16
        || volumes.iter().any(|volume| {
            !valid_volume_name(&volume.name) || !valid_container_mount_path(&volume.mount_path)
        })
        || volumes
            .iter()
            .any(|volume| !volume_keys.insert((volume.name.clone(), volume.mount_path.clone())))
    {
        return Err("DENIED: volumes must name a valid volume and absolute mount path".to_owned());
    }
    if !(64..=2048).contains(&memory_mb) {
        return Err("DENIED: memory limit must be between 64 and 2048 MiB".to_owned());
    }
    Ok(ContainerSpec {
        image: payload.image,
        host_port: (host_port != 0).then_some(host_port),
        container_port: (container_port != 0).then_some(container_port),
        environment,
        volumes: volumes
            .into_iter()
            .map(|volume| ContainerVolumeSpec {
                name: volume.name,
                mount_path: volume.mount_path,
            })
            .collect(),
        memory_mb,
        stateful,
    })
}

async fn run_fixed_command(
    program: &Path,
    args: &[String],
    timeout: Duration,
) -> std::result::Result<String, String> {
    use tokio::io::AsyncReadExt;
    let mut child = tokio::process::Command::new(program)
        .args(args)
        .kill_on_drop(true)
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map_err(|error| format!("FAILED: start fixed command: {error}"))?;
    let stdout = child
        .stdout
        .take()
        .ok_or_else(|| "FAILED: fixed command output pipe is unavailable".to_owned())?;
    let mut bytes = Vec::with_capacity(4 * 1024);
    let read = tokio::time::timeout(
        timeout,
        stdout
            .take((MAX_COMMAND_OUTPUT_BYTES + 1) as u64)
            .read_to_end(&mut bytes),
    )
    .await;
    match read {
        Ok(Ok(_)) => {}
        Ok(Err(error)) => {
            let _ = child.kill().await;
            return Err(format!("FAILED: read fixed command output: {error}"));
        }
        Err(_) => {
            let _ = child.kill().await;
            return Err("UNCERTAIN: fixed command exceeded its time limit".to_owned());
        }
    }
    if bytes.len() > MAX_COMMAND_OUTPUT_BYTES {
        let _ = child.kill().await;
        let _ = child.wait().await;
        return Err("UNCERTAIN: fixed command output exceeded 32 KiB".to_owned());
    }
    match tokio::time::timeout(timeout, child.wait()).await {
        Ok(Ok(status)) if status.success() => {}
        Ok(Ok(status)) => return Err(format!("FAILED: fixed command exited with {status}")),
        Ok(Err(error)) => return Err(format!("FAILED: wait for fixed command: {error}")),
        Err(_) => {
            let _ = child.kill().await;
            return Err("UNCERTAIN: fixed command exceeded its time limit".to_owned());
        }
    }
    let result = String::from_utf8_lossy(&bytes)
        .chars()
        .filter(|character| *character == '\n' || *character == '\t' || !character.is_control())
        .collect::<String>()
        .trim()
        .to_owned();
    Ok(if result.is_empty() {
        "Operation completed.".to_owned()
    } else {
        result
    })
}

async fn update_agent(
    controller_url: &reqwest::Url,
    controller_cert: &reqwest::Certificate,
    agent_binary: &Path,
) -> std::result::Result<String, String> {
    let client = reqwest::Client::builder()
        .add_root_certificate(controller_cert.clone())
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(120))
        .build()
        .map_err(|error| format!("build pinned update client: {error}"))?;
    let url = controller_url
        .join("api/v1/agent-download/linux-amd64")
        .map_err(|error| format!("build fixed Agent download URL: {error}"))?;
    let response = client
        .get(url)
        .send()
        .await
        .map_err(|error| format!("download Agent from configured controller: {error}"))?
        .error_for_status()
        .map_err(|error| format!("controller rejected Agent download: {error}"))?;
    if response
        .content_length()
        .is_some_and(|length| length > MAX_AGENT_BYTES as u64)
    {
        return Err("Agent download exceeds the 64 MiB limit".to_owned());
    }
    let bytes = response
        .bytes()
        .await
        .map_err(|error| format!("read Agent download: {error}"))?;
    if bytes.len() < 4 || bytes.len() > MAX_AGENT_BYTES || &bytes[..4] != b"\x7fELF" {
        return Err("download is not a valid-sized Linux ELF Agent".to_owned());
    }

    let parent = agent_binary
        .parent()
        .ok_or_else(|| "configured Agent binary has no parent directory".to_owned())?;
    let temporary = parent.join(format!(".nanpanel-agent-{}.tmp", uuid::Uuid::new_v4()));
    let write_result = async {
        use tokio::io::AsyncWriteExt;
        let mut file = tokio::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o755)
            .open(&temporary)
            .await
            .map_err(|error| format!("create root-owned Agent staging file: {error}"))?;
        file.write_all(&bytes)
            .await
            .map_err(|error| format!("write staged Agent: {error}"))?;
        file.sync_all()
            .await
            .map_err(|error| format!("sync staged Agent: {error}"))?;
        drop(file);
        tokio::fs::set_permissions(&temporary, std::fs::Permissions::from_mode(0o755))
            .await
            .map_err(|error| format!("set staged Agent permissions: {error}"))?;
        tokio::fs::rename(&temporary, agent_binary)
            .await
            .map_err(|error| format!("atomically install Agent: {error}"))?;
        let directory = tokio::fs::File::open(parent)
            .await
            .map_err(|error| format!("open Agent binary directory: {error}"))?;
        directory
            .sync_all()
            .await
            .map_err(|error| format!("sync Agent binary directory: {error}"))?;
        Ok::<(), String>(())
    }
    .await;
    if write_result.is_err() {
        let _ = tokio::fs::remove_file(&temporary).await;
    }
    write_result?;
    Ok("Agent binary atomically replaced; restart is required".to_owned())
}

async fn restart_system_service(unit_name: &str) -> std::result::Result<String, String> {
    let mut child = tokio::process::Command::new("/usr/bin/systemctl")
        .args(["restart", unit_name])
        .kill_on_drop(true)
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map_err(|error| format!("start fixed systemd restart: {error}"))?;
    match tokio::time::timeout(Duration::from_secs(20), child.wait()).await {
        Ok(Ok(status)) if status.success() => {}
        Ok(Ok(status)) => return Err(format!("FAILED: systemctl restart failed with {status}")),
        Ok(Err(error)) => return Err(format!("FAILED: wait for systemctl restart: {error}")),
        Err(_) => return Err("FAILED: systemctl restart timed out after 20 seconds".to_owned()),
    }
    let mut verify = tokio::process::Command::new("/usr/bin/systemctl")
        .args(["is-active", "--quiet", unit_name])
        .kill_on_drop(true)
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map_err(|error| format!("FAILED: start fixed systemd verification: {error}"))?;
    match tokio::time::timeout(Duration::from_secs(5), verify.wait()).await {
        Ok(Ok(status)) if status.success() => Ok(serde_json::json!({
            "activeState": "ACTIVE",
            "evidence": format!("{unit_name} restarted and systemd reports active")
        })
        .to_string()),
        Ok(Ok(status)) => Err(format!(
            "FAILED: systemd verification did not report active ({status})"
        )),
        Ok(Err(error)) => Err(format!("FAILED: wait for systemd verification: {error}")),
        Err(_) => Err("FAILED: systemd verification timed out after 5 seconds".to_owned()),
    }
}

async fn read_systemd_journal(unit_name: &str) -> std::result::Result<String, String> {
    use tokio::io::AsyncReadExt;

    let mut child = tokio::process::Command::new("/usr/bin/journalctl")
        .args([
            "--no-pager",
            "--output=short-iso",
            "--lines=12",
            &format!("--unit={unit_name}"),
        ])
        .kill_on_drop(true)
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map_err(|error| format!("FAILED: start fixed journalctl query: {error}"))?;
    let stdout = child
        .stdout
        .take()
        .ok_or_else(|| "FAILED: journalctl stdout pipe is unavailable".to_owned())?;
    let mut bytes = Vec::with_capacity(8 * 1024);
    let read = tokio::time::timeout(
        Duration::from_secs(5),
        stdout
            .take((MAX_JOURNAL_CAPTURE_BYTES + 1) as u64)
            .read_to_end(&mut bytes),
    )
    .await;
    match read {
        Ok(Ok(_)) => {}
        Ok(Err(error)) => {
            let _ = child.kill().await;
            return Err(format!("FAILED: read journalctl output: {error}"));
        }
        Err(_) => {
            let _ = child.kill().await;
            return Err("FAILED: journalctl query timed out after 5 seconds".to_owned());
        }
    }
    if bytes.len() > MAX_JOURNAL_CAPTURE_BYTES {
        let _ = child.kill().await;
        let _ = child.wait().await;
        bytes.truncate(MAX_JOURNAL_CAPTURE_BYTES);
    } else {
        match tokio::time::timeout(Duration::from_secs(5), child.wait()).await {
            Ok(Ok(status)) if status.success() => {}
            Ok(Ok(_)) => return Err("FAILED: journalctl query failed".to_owned()),
            Ok(Err(error)) => return Err(format!("FAILED: wait for journalctl query: {error}")),
            Err(_) => {
                let _ = child.kill().await;
                return Err("FAILED: journalctl query timed out after 5 seconds".to_owned());
            }
        }
    }
    let text = String::from_utf8_lossy(&bytes)
        .chars()
        .filter(|character| *character == '\n' || *character == '\t' || !character.is_control())
        .collect::<String>()
        .trim()
        .to_owned();
    if text.is_empty() {
        return Ok("No journal entries were returned.".to_owned());
    }
    Ok(truncate_journal_output(&text))
}

fn truncate_journal_output(text: &str) -> String {
    let mut end = text.len().min(1_800);
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    if end < text.len() {
        format!("{}\n[output truncated]", &text[..end])
    } else {
        text.to_owned()
    }
}

fn read_container_runtime(root: &File) -> Result<Option<std::path::PathBuf>> {
    use std::os::unix::fs::MetadataExt;
    let contents = match read_beneath(root, "container-runtime") {
        Ok(contents) => contents,
        Err(error)
            if error.chain().any(|source| {
                source
                    .downcast_ref::<std::io::Error>()
                    .is_some_and(|io| io.kind() == std::io::ErrorKind::NotFound)
            }) =>
        {
            String::new()
        }
        Err(error) => return Err(error).context("read root-managed container runtime selection"),
    };
    let runtime = contents.trim();
    if runtime.is_empty() {
        return Ok(None);
    }
    let path = match runtime {
        "docker" => Path::new("/usr/bin/docker"),
        "podman" => Path::new("/usr/bin/podman"),
        _ => bail!("container-runtime must contain only docker or podman"),
    };
    let metadata = std::fs::metadata(path)
        .with_context(|| format!("open configured container runtime {}", path.display()))?;
    if !metadata.is_file() || metadata.mode() & 0o111 == 0 {
        bail!("configured container runtime is not an executable regular file");
    }
    Ok(Some(path.to_path_buf()))
}

fn valid_package_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 128
        && !name.starts_with('-')
        && name
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"+_.:-".contains(&byte))
}

fn valid_file_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 128
        && name != "."
        && name != ".."
        && name
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"_.-".contains(&byte))
}

fn valid_container_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 63
        && name.as_bytes()[0].is_ascii_alphanumeric()
        && name
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"_.-".contains(&byte))
}

const DEFAULT_CONTAINER_MEMORY_MB: u32 = 256;

fn valid_environment_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 64
        && (name.as_bytes()[0].is_ascii_alphabetic() || name.as_bytes()[0] == b'_')
        && name
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
}

fn valid_environment_entry(entry: &str) -> bool {
    if entry.is_empty() || entry.len() > 2048 || entry.contains('\0') {
        return false;
    }
    let Some(equals) = entry.find('=') else {
        return false;
    };
    valid_environment_name(&entry[..equals])
}

fn valid_container_mount_path(path: &str) -> bool {
    valid_host_path(path, false).is_ok()
}

fn valid_volume_name(name: &str) -> bool {
    valid_container_name(name)
}

fn valid_network_rule_name(name: &str) -> bool {
    valid_container_name(name)
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

fn valid_systemd_unit(unit_name: &str) -> bool {
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

#[cfg(target_os = "linux")]
#[repr(C)]
struct OpenHow {
    flags: u64,
    mode: u64,
    resolve: u64,
}

#[cfg(target_os = "linux")]
fn read_beneath(root: &File, target: &str) -> Result<String> {
    let mut file = open_beneath(root, target, libc::O_RDONLY | libc::O_CLOEXEC, 0)?;
    let mut bytes = Vec::with_capacity(MAX_FILE_BYTES.min(4096));
    let mut limited: Take<&mut File> = (&mut file).take((MAX_FILE_BYTES + 1) as u64);
    limited.read_to_end(&mut bytes)?;
    if bytes.len() > MAX_FILE_BYTES {
        bail!("file exceeds the 64 KiB output limit");
    }
    String::from_utf8(bytes).context("file content is not UTF-8")
}

fn valid_host_path(target: &str, allow_root: bool) -> std::result::Result<String, String> {
    if target.len() > 4096 || !target.starts_with('/') || target.contains('\0') {
        return Err("DENIED: path must be an absolute host path under 4096 bytes".to_owned());
    }
    if target == "/" {
        return if allow_root {
            Ok(String::new())
        } else {
            Err("DENIED: the filesystem root is not a file target".to_owned())
        };
    }
    let relative = target
        .strip_prefix('/')
        .ok_or_else(|| "DENIED: path must be absolute".to_owned())?;
    if relative.is_empty()
        || relative.split('/').any(|component| {
            component.is_empty()
                || component == "."
                || component == ".."
                || component.chars().any(char::is_control)
        })
    {
        return Err("DENIED: path contains an invalid component".to_owned());
    }
    Ok(relative.to_owned())
}

fn open_host_path(root: &File, target: &str, allow_root: bool) -> Result<File> {
    let relative = valid_host_path(target, allow_root).map_err(anyhow::Error::msg)?;
    if relative.is_empty() {
        return root
            .try_clone()
            .context("clone host filesystem root descriptor");
    }
    open_beneath(root, &relative, libc::O_RDONLY | libc::O_CLOEXEC, 0)
}

fn list_host_directory(root: &File, target: &str) -> std::result::Result<String, String> {
    let directory = open_host_path(root, target, true)
        .map_err(|error| format!("FAILED: open directory: {error}"))?;
    if !directory
        .metadata()
        .map_err(|error| format!("FAILED: inspect directory: {error}"))?
        .is_dir()
    {
        return Err("FAILED: target is not a directory".to_owned());
    }
    let entries_path = format!("/proc/self/fd/{}", directory.as_raw_fd());
    let entries = std::fs::read_dir(entries_path)
        .map_err(|error| format!("FAILED: read directory: {error}"))?;
    let relative_directory = valid_host_path(target, true)?;
    let mut items = Vec::new();
    let mut truncated = false;
    for entry in entries {
        let entry = entry.map_err(|error| format!("FAILED: read directory entry: {error}"))?;
        let name = entry.file_name();
        let Some(name) = name.to_str() else {
            continue;
        };
        if name.is_empty() || name.len() > 255 || name.chars().any(char::is_control) {
            continue;
        }
        let file_type = entry
            .file_type()
            .map_err(|error| format!("FAILED: inspect directory entry: {error}"))?;
        let kind = if file_type.is_dir() {
            "directory"
        } else if file_type.is_file() {
            "file"
        } else if file_type.is_symlink() {
            "symlink"
        } else {
            "other"
        };
        let relative_file = if relative_directory.is_empty() {
            name.to_owned()
        } else {
            format!("{relative_directory}/{name}")
        };
        let size_bytes = if file_type.is_file() {
            open_beneath(
                root,
                &relative_file,
                libc::O_RDONLY | libc::O_NONBLOCK | libc::O_CLOEXEC,
                0,
            )
            .ok()
            .and_then(|file| file.metadata().ok())
            .filter(|metadata| metadata.is_file())
            .map(|metadata| metadata.len())
        } else {
            None
        };
        items.push(serde_json::json!({
            "name": name,
            "kind": kind,
            "sizeBytes": size_bytes
        }));
        if items.len() > MAX_DIRECTORY_ENTRIES {
            items.pop();
            truncated = true;
            break;
        }
    }
    items.sort_by(|left, right| {
        left.get("name")
            .and_then(serde_json::Value::as_str)
            .cmp(&right.get("name").and_then(serde_json::Value::as_str))
    });
    serde_json::to_string(&serde_json::json!({
        "path": target,
        "entries": items,
        "truncated": truncated
    }))
    .map_err(|error| format!("FAILED: serialize directory listing: {error}"))
}

fn read_host_file(root: &File, target: &str) -> std::result::Result<String, String> {
    let relative = valid_host_path(target, false)?;
    let mut file = open_beneath(
        root,
        &relative,
        libc::O_RDONLY | libc::O_NONBLOCK | libc::O_CLOEXEC,
        0,
    )
    .map_err(|error| format!("FAILED: open file: {error}"))?;
    let metadata = file
        .metadata()
        .map_err(|error| format!("FAILED: inspect file: {error}"))?;
    if !metadata.is_file() {
        return Err("FAILED: target is not a regular file".to_owned());
    }
    let mut bytes = Vec::with_capacity(MAX_FILE_BYTES.min(4096));
    let mut limited: Take<&mut File> = (&mut file).take((MAX_FILE_BYTES + 1) as u64);
    limited
        .read_to_end(&mut bytes)
        .map_err(|error| format!("FAILED: read file: {error}"))?;
    if bytes.len() > MAX_FILE_BYTES {
        return Err("FAILED: file exceeds the 64 KiB output limit".to_owned());
    }
    let content = String::from_utf8(bytes)
        .map_err(|_| "FAILED: file content is not UTF-8 text".to_owned())?;
    if !valid_text_file(&content) {
        return Err("FAILED: file contains unsupported control characters".to_owned());
    }
    Ok(content)
}

fn write_host_file(root: &File, request: &Request) -> std::result::Result<String, String> {
    let relative = valid_host_path(&request.target, false)?;
    let payload = parse_payload::<HostFileWritePayload>(request)?;
    if payload.content.len() > MAX_WRITE_BYTES || !valid_text_file(&payload.content) {
        return Err(
            "DENIED: file content exceeds 32 KiB or contains unsupported control characters"
                .to_owned(),
        );
    }
    let mut file = match open_beneath(
        root,
        &relative,
        libc::O_WRONLY | libc::O_NONBLOCK | libc::O_CLOEXEC,
        0,
    ) {
        Ok(file) => file,
        Err(error) if is_not_found(&error) => open_beneath(
            root,
            &relative,
            libc::O_WRONLY | libc::O_CREAT | libc::O_EXCL | libc::O_NONBLOCK | libc::O_CLOEXEC,
            0o644,
        )
        .map_err(|error| format!("FAILED: create file: {error}"))?,
        Err(error) => return Err(format!("FAILED: open file: {error}")),
    };
    let metadata = file
        .metadata()
        .map_err(|error| format!("FAILED: inspect file: {error}"))?;
    if !metadata.is_file() {
        return Err("FAILED: target is not a regular file".to_owned());
    }
    file.set_len(0)
        .map_err(|error| format!("FAILED: truncate file: {error}"))?;
    use std::io::Write;
    file.write_all(payload.content.as_bytes())
        .and_then(|()| file.sync_all())
        .map_err(|error| format!("FAILED: write file: {error}"))?;
    serde_json::to_string(&serde_json::json!({
        "path": request.target,
        "bytesWritten": payload.content.len()
    }))
    .map_err(|error| format!("FAILED: serialize write result: {error}"))
}

fn delete_host_file(root: &File, target: &str) -> std::result::Result<String, String> {
    let relative = valid_host_path(target, false)?;
    let file = open_beneath(
        root,
        &relative,
        libc::O_RDONLY | libc::O_NONBLOCK | libc::O_CLOEXEC,
        0,
    )
    .map_err(|error| format!("FAILED: open file: {error}"))?;
    if !file
        .metadata()
        .map_err(|error| format!("FAILED: inspect file: {error}"))?
        .is_file()
    {
        return Err("FAILED: only regular files can be deleted".to_owned());
    }
    drop(file);
    let (parent_path, file_name) = relative
        .rsplit_once('/')
        .map_or((None, relative.as_str()), |(parent, name)| {
            (Some(parent), name)
        });
    let parent = match parent_path {
        Some(path) => open_beneath(
            root,
            path,
            libc::O_RDONLY | libc::O_DIRECTORY | libc::O_CLOEXEC,
            0,
        ),
        None => root.try_clone().context("clone host root descriptor"),
    }
    .map_err(|error| format!("FAILED: open parent directory: {error}"))?;
    let name = CString::new(file_name).map_err(|_| "DENIED: filename contains NUL".to_owned())?;
    // SAFETY: `parent` is a live directory descriptor and `name` is one validated
    // path component. unlinkat removes only that directory entry and never follows it.
    let result = unsafe { libc::unlinkat(parent.as_raw_fd(), name.as_ptr(), 0) };
    if result != 0 {
        return Err(format!(
            "FAILED: delete file: {}",
            std::io::Error::last_os_error()
        ));
    }
    serde_json::to_string(&serde_json::json!({ "path": target, "deleted": true }))
        .map_err(|error| format!("FAILED: serialize delete result: {error}"))
}

fn is_not_found(error: &anyhow::Error) -> bool {
    error.chain().any(|source| {
        source
            .downcast_ref::<std::io::Error>()
            .is_some_and(|io| io.kind() == std::io::ErrorKind::NotFound)
    })
}

fn valid_text_file(content: &str) -> bool {
    !content
        .chars()
        .any(|character| character.is_control() && !matches!(character, '\n' | '\r' | '\t'))
}

#[cfg(target_os = "linux")]
fn open_beneath(root: &File, target: &str, flags: i32, mode: u32) -> Result<File> {
    let path = Path::new(target);
    if target.is_empty()
        || path
            .components()
            .any(|part| !matches!(part, Component::Normal(_)))
    {
        bail!("target must be a relative path without dot, parent, or root components");
    }
    let name = CString::new(target).context("target contains NUL")?;
    let how = OpenHow {
        flags: flags as u64,
        mode: mode as u64,
        resolve: RESOLVE_BENEATH | RESOLVE_NO_MAGICLINKS | RESOLVE_NO_SYMLINKS,
    };
    // SAFETY: `root` and `name` remain live for the syscall; `how` has the
    // Linux open_how layout, and the kernel reads exactly its declared size.
    let fd = unsafe {
        libc::syscall(
            libc::SYS_openat2,
            root.as_raw_fd(),
            name.as_ptr(),
            &how as *const OpenHow,
            std::mem::size_of::<OpenHow>(),
        ) as i32
    };
    if fd < 0 {
        return Err(std::io::Error::last_os_error()).context("openat2");
    }
    // SAFETY: `fd` is non-negative and uniquely owned by this function after
    // the successful openat2 call, so File may take ownership of it.
    // SAFETY: `fd` is non-negative and uniquely owned by this function after
    // the successful openat2 call, so File may take ownership of it.
    Ok(unsafe { File::from_raw_fd(fd) })
}

#[cfg(target_os = "linux")]
fn atomic_write_beneath(root: &File, target: &str, bytes: &[u8]) -> Result<()> {
    if !valid_file_name(target) {
        bail!("target must be a single protected file name");
    }
    match open_beneath(root, target, libc::O_RDONLY | libc::O_CLOEXEC, 0) {
        Ok(existing) => {
            use std::os::unix::fs::MetadataExt;
            let metadata = existing.metadata()?;
            if !metadata.is_file() || metadata.uid() != 0 || metadata.mode() & 0o022 != 0 {
                bail!("existing protected file must be a root-owned regular file");
            }
        }
        Err(error)
            if error.chain().any(|source| {
                source
                    .downcast_ref::<std::io::Error>()
                    .is_some_and(|io| io.kind() == std::io::ErrorKind::NotFound)
            }) => {}
        Err(error) => return Err(error).context("inspect existing protected file"),
    }
    let temporary = format!(".nanpanel-{}.tmp", uuid::Uuid::new_v4());
    let temporary_c = CString::new(temporary.as_str()).context("temporary path contains NUL")?;
    // SAFETY: the directory FD is valid, the generated name is a single path
    // component, and openat receives a complete mode because O_CREAT is set.
    let fd = unsafe {
        libc::openat(
            root.as_raw_fd(),
            temporary_c.as_ptr(),
            libc::O_WRONLY | libc::O_CREAT | libc::O_EXCL | libc::O_CLOEXEC | libc::O_NOFOLLOW,
            0o600,
        )
    };
    if fd < 0 {
        return Err(std::io::Error::last_os_error()).context("create protected-file staging file");
    }
    // SAFETY: the successful openat call returned a unique FD owned here.
    let mut file = unsafe { File::from_raw_fd(fd) };
    let write_result = (|| -> Result<()> {
        use std::io::Write;
        file.write_all(bytes)?;
        file.sync_all()?;
        let target_c = CString::new(target).context("target path contains NUL")?;
        // SAFETY: both names are NUL-terminated, root is a valid directory FD,
        // and renameat atomically replaces only the entry in this directory.
        let renamed = unsafe {
            libc::renameat(
                root.as_raw_fd(),
                temporary_c.as_ptr(),
                root.as_raw_fd(),
                target_c.as_ptr(),
            )
        };
        if renamed != 0 {
            return Err(std::io::Error::last_os_error())
                .context("atomically replace protected file");
        }
        root.sync_all()?;
        Ok(())
    })();
    if write_result.is_err() {
        // SAFETY: root is a valid directory FD and the generated name is a
        // single path component; removing a missing file is harmless.
        unsafe { libc::unlinkat(root.as_raw_fd(), temporary_c.as_ptr(), 0) };
    }
    write_result
}

#[cfg(not(target_os = "linux"))]
fn read_beneath(_root: &File, _target: &str) -> Result<String> {
    bail!("this privileged-helper PoC requires Linux openat2")
}

#[cfg(not(target_os = "linux"))]
fn open_beneath(_root: &File, _target: &str, _flags: i32, _mode: u32) -> Result<File> {
    bail!("this privileged-helper PoC requires Linux openat2")
}

#[cfg(not(target_os = "linux"))]
fn atomic_write_beneath(_root: &File, _target: &str, _bytes: &[u8]) -> Result<()> {
    bail!("this privileged-helper PoC requires Linux openat2")
}

fn audit(uid: u32, operation_id: &str, capability: &str, target: &str, outcome: &str) {
    eprintln!(
        "{{\"event\":\"helper_operation\",\"caller_uid\":{},\"operation_id\":{},\"capability\":{},\"target\":{},\"phase\":\"complete\",\"outcome\":{}}}",
        uid,
        serde_json::to_string(operation_id).unwrap_or_else(|_| "\"\"".to_owned()),
        serde_json::to_string(capability).unwrap_or_else(|_| "\"request\"".to_owned()),
        serde_json::to_string(target).unwrap_or_else(|_| "\"\"".to_owned()),
        serde_json::to_string(outcome).unwrap_or_else(|_| "\"rejected\"".to_owned())
    );
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn all_capability_state() -> CapabilityState {
        CapabilityState {
            has_container_runtime: true,
            network_operations_available: true,
            topology_operations_available: true,
            packet_capture_available: true,
            vrf_operations_available: true,
            ovs_operations_available: true,
            netbird_status_available: true,
            kubeedge_available: true,
            nginx_routes_available: true,
            cron_available: true,
            metrics_available: true,
            backup_available: true,
        }
    }

    #[test]
    fn k3s_install_accepts_secure_tokens_and_rejects_environment_injection() {
        let secure_token = format!("K10{}::server:{}", "a".repeat(64), "b".repeat(32));
        assert!(valid_k3s_token(&secure_token));
        assert!(valid_k3s_token("abcdef.0123456789abcdef"));
        assert!(!valid_k3s_token("short"));
        assert!(!valid_k3s_token(&format!("{}$", "a".repeat(32))));
        assert!(!valid_k3s_token(&format!(
            "{}\nK3S_URL=evil",
            "a".repeat(32)
        )));

        let mut server = K3sInstallPayload {
            role: "server".to_owned(),
            version: "v1.36.5+k3s1".to_owned(),
            server_url: None,
            token: "abcdef.0123456789abcdef".to_owned(),
        };
        assert!(validate_k3s_install_payload(&server).is_ok());
        server.token = secure_token;
        assert!(validate_k3s_install_payload(&server).is_err());
        server.token = "abcdef.0123456789abcdef".to_owned();
        let invalid_version = K3sInstallPayload {
            version: "v1.36.5-rc1+k3s1".to_owned(),
            ..server
        };
        assert!(validate_k3s_install_payload(&invalid_version).is_err());
    }

    #[test]
    fn k3s_upgrade_allows_only_a_safe_forward_version_step() {
        assert!(valid_k3s_upgrade_path("v1.36.5+k3s1", "v1.36.6+k3s1"));
        assert!(valid_k3s_upgrade_path("v1.36.5+k3s1", "v1.37.1+k3s1"));
        assert!(!valid_k3s_upgrade_path("v1.36.5+k3s1", "v1.38.0+k3s1"));
        assert!(!valid_k3s_upgrade_path("v1.36.5+k3s2", "v1.36.5+k3s1"));
        assert!(!valid_k3s_upgrade_path("invalid", "v1.37.1+k3s1"));
        assert!(valid_k3s_upgrade_payload(&K3sUpgradePayload {
            role: "server".to_owned(),
            version: "v1.37.1+k3s1".to_owned(),
        }));
        assert!(!valid_k3s_upgrade_payload(&K3sUpgradePayload {
            role: "worker".to_owned(),
            version: "v1.37.1+k3s1".to_owned(),
        }));
    }

    #[test]
    fn kubeedge_join_accepts_only_bounded_stable_version_endpoint_and_token() {
        let valid = KubeEdgeJoinPayload {
            version: "v1.23.1".to_owned(),
            cloud_core_endpoint: "cloud.example.test:10000".to_owned(),
            token: format!("{}.{}.{}", "a".repeat(48), "b".repeat(48), "c".repeat(48)),
        };
        assert!(valid_kubeedge_join_payload(&valid));
        assert!(valid_kubeedge_join_payload(&KubeEdgeJoinPayload {
            cloud_core_endpoint: "[2001:db8::5]:10000".to_owned(),
            ..valid.clone()
        }));

        assert!(!valid_kubeedge_join_payload(&KubeEdgeJoinPayload {
            version: "v1.23.1-rc1".to_owned(),
            ..valid.clone()
        }));
        assert!(!valid_kubeedge_join_payload(&KubeEdgeJoinPayload {
            cloud_core_endpoint: "https://cloud.example.test:10000".to_owned(),
            ..valid.clone()
        }));
        assert!(!valid_kubeedge_join_payload(&KubeEdgeJoinPayload {
            cloud_core_endpoint: "cloud.example.test:10000;id".to_owned(),
            ..valid.clone()
        }));
        assert!(!valid_kubeedge_join_payload(&KubeEdgeJoinPayload {
            cloud_core_endpoint: "cloud.example.test:0".to_owned(),
            ..valid.clone()
        }));
        assert!(!valid_kubeedge_join_payload(&KubeEdgeJoinPayload {
            token: format!("{}\n--image-repository=evil", "d".repeat(48)),
            ..valid
        }));
    }

    #[test]
    fn kubeedge_join_is_a_typed_empty_target_helper_capability() {
        let request = Request {
            operation_id: "operation-1".to_owned(),
            idempotency_key: "kubeedge-join-1".to_owned(),
            capability: "kubeedge.edgecore.join".to_owned(),
            target: String::new(),
            deadline_epoch_ms: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as i64
                + 10 * 60 * 1000,
            payload_json: Some(
                r#"{"version":"v1.23.1","cloudCoreEndpoint":"cloud.example.test:10000","token":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.cccccccccccccccccccccccccccccccccccccccc"}"#
                    .to_owned(),
            ),
        };
        assert!(validate_envelope(&request).is_ok());
        assert!(validate_payload_presence(&request).is_ok());
        assert!(
            validate_capability_target(
                &request.capability,
                &request.target,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false
            )
            .is_ok()
        );
        assert!(
            serde_json::from_str::<KubeEdgeJoinPayload>(
                request.payload_json.as_deref().unwrap_or_default()
            )
            .is_ok_and(|payload| valid_kubeedge_join_payload(&payload))
        );

        let mut injected = request;
        injected.payload_json = Some(
            r#"{"version":"v1.23.1","cloudCoreEndpoint":"cloud.example.test:10000","token":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.cccccccccccccccccccccccccccccccccccccccc","command":"id"}"#
                .to_owned(),
        );
        assert!(
            serde_json::from_str::<KubeEdgeJoinPayload>(
                injected.payload_json.as_deref().unwrap_or_default()
            )
            .is_err()
        );
    }

    #[cfg(unix)]
    #[test]
    fn k3s_install_file_publication_is_private_and_never_overwrites() -> Result<()> {
        use std::os::unix::fs::PermissionsExt;

        let directory = tempfile::tempdir()?;
        let parent = File::open(directory.path())?;
        write_new_beneath(&parent, "k3s.nanpanel.env", b"K3S_TOKEN=fixture\n", 0o600)?;
        let installed = directory.path().join("k3s.nanpanel.env");
        assert_eq!(std::fs::read(&installed)?, b"K3S_TOKEN=fixture\n");
        assert_eq!(
            std::fs::metadata(&installed)?.permissions().mode() & 0o777,
            0o600
        );
        assert!(write_new_beneath(&parent, "k3s.nanpanel.env", b"replacement", 0o600).is_err());
        assert_eq!(std::fs::read(installed)?, b"K3S_TOKEN=fixture\n");
        Ok(())
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn k3s_upgrade_rename_preserves_the_old_binary_on_target_conflict() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let parent = File::open(directory.path())?;
        std::fs::write(directory.path().join("k3s"), b"old")?;
        std::fs::write(directory.path().join("k3s.nanpanel.rollback"), b"conflict")?;
        assert!(rename_entry_beneath(&parent, "k3s", "k3s.nanpanel.rollback", true).is_err());
        assert_eq!(std::fs::read(directory.path().join("k3s"))?, b"old");
        assert_eq!(
            std::fs::read(directory.path().join("k3s.nanpanel.rollback"))?,
            b"conflict"
        );
        Ok(())
    }

    fn request(capability: &str, target: &str, deadline: i64) -> Request {
        Request {
            operation_id: "op-1".into(),
            idempotency_key: "idem-1".into(),
            capability: capability.into(),
            target: target.into(),
            deadline_epoch_ms: deadline,
            payload_json: None,
        }
    }

    #[test]
    fn authorized_read_is_beneath_root_and_rejects_traversal_and_symlinks() -> Result<()> {
        let root = tempfile::tempdir()?;
        let mut file = File::create(root.path().join("allowed.txt"))?;
        file.write_all(b"root-only fixture")?;
        #[cfg(unix)]
        std::os::unix::fs::symlink("/etc/passwd", root.path().join("outside.txt"))?;
        let root_fd = File::open(root.path())?;
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .context("system clock is before Unix epoch")?
            .as_millis() as i64;
        assert_eq!(
            execute(
                &request("file.read.managed", "allowed.txt", now + 30_000),
                &root_fd
            )
            .map_err(anyhow::Error::msg)?,
            "root-only fixture"
        );
        assert!(
            execute(
                &request("file.read.managed", "../etc/passwd", now + 30_000),
                &root_fd
            )
            .is_err()
        );
        #[cfg(unix)]
        assert!(
            execute(
                &request("file.read.managed", "outside.txt", now + 30_000),
                &root_fd
            )
            .is_err()
        );
        assert!(
            execute(
                &request("package.install", "allowed.txt", now + 30_000),
                &root_fd
            )
            .is_err()
        );
        assert!(
            execute(
                &request("file.read.managed", "allowed.txt", now - 1),
                &root_fd
            )
            .is_err()
        );
        Ok(())
    }

    #[test]
    fn helper_capabilities_use_fixed_operations_and_validated_parameters() -> Result<()> {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .context("system clock is before Unix epoch")?
            .as_millis() as i64;
        let validate = |capability, target, containers, firewall, nginx| {
            validate_capability_target(
                capability, target, containers, firewall, false, false, nginx, false, false, false,
            )
        };
        assert!(validate("agent.update", "", false, false, false).is_ok());
        assert!(validate("systemd.restart", "ssh.service", false, false, false).is_ok());
        assert!(validate("systemd.journal.read", "cron.service", false, false, false).is_ok());
        assert!(validate("systemd.restart", "--help.service", false, false, false).is_err());
        assert!(
            validate(
                "systemd.restart",
                "bad;command.service",
                false,
                false,
                false
            )
            .is_err()
        );
        assert!(validate("package.install", "", false, false, false).is_ok());
        assert!(validate("file.write.protected", "app.conf", false, false, false).is_ok());
        assert!(validate("file.write.protected", "../app.conf", false, false, false).is_err());
        assert!(validate("file.list", "/", false, false, false).is_ok());
        assert!(validate("file.read", "/etc/hosts", false, false, false).is_ok());
        assert!(validate("file.read", "/etc/../passwd", false, false, false).is_err());
        assert!(validate("container.list", "", false, false, false).is_err());
        assert!(validate("container.list", "", true, false, false).is_ok());
        assert!(validate("container.start", "web-test", true, false, false).is_ok());
        assert!(validate("container.start", "--help", true, false, false).is_err());
        assert!(
            validate(
                "network.firewall.expose",
                "temporary-test",
                false,
                false,
                false
            )
            .is_err()
        );
        assert!(
            validate(
                "network.firewall.expose",
                "temporary-test",
                false,
                true,
                false,
            )
            .is_ok()
        );
        assert!(validate("network.firewall.expose", "bad;name", false, true, false).is_err());
        assert!(
            validate(
                "nginx.route.set",
                "nanpanel-route0123456789abcdef0123456789abcdef",
                false,
                false,
                true
            )
            .is_ok()
        );
        assert!(
            validate(
                "nginx.route.set",
                "nanpanel-route0123456789abcdef0123456789abcdef",
                false,
                false,
                false
            )
            .is_err()
        );
        assert!(
            validate(
                "nginx.route.remove",
                "nanpanel-route0123456789abcdef0123456789abcdef",
                false,
                false,
                true
            )
            .is_ok()
        );
        assert!(
            validate(
                "nginx.route.remove",
                "nanpanel-route0123456789abcdef0123456789abcdef",
                false,
                false,
                false
            )
            .is_err()
        );
        assert!(validate("nginx.route.remove", "customer-site", false, false, true).is_err());
        assert!(validate_envelope(&request("agent.update", "", now + 30_000)).is_ok());
        assert!(valid_package_name("libssl3"));
        assert!(!valid_package_name("-y"));
        assert!(!valid_package_name("curl;id"));
        assert!(valid_container_image("nginx:1.27"));
        assert!(!valid_container_image("./nginx"));
        assert!(valid_network_interface("br-0"));
        assert!(!valid_network_interface("-eth0"));
        let capabilities = available_capabilities(all_capability_state());
        for capability in [
            "systemd.restart",
            "systemd.journal.read",
            "package.install",
            "file.write.protected",
            "file.list",
            "file.read",
            "file.write",
            "file.delete",
            "container.deploy",
            "container.remove",
            "network.firewall.expose",
            "network.topology.reconcile",
            "network.topology.vrf",
            "network.topology.ovs",
            "k3s.preflight",
            "kubeedge.edgecore.join",
            "network.overlay.peer_paths.read",
            "nginx.route.set",
            "nginx.route.remove",
        ] {
            assert!(capabilities.contains(&capability));
        }
        let mut without_ovs = all_capability_state();
        without_ovs.ovs_operations_available = false;
        let capabilities_without_ovs = available_capabilities(without_ovs);
        assert!(capabilities_without_ovs.contains(&"network.topology.reconcile"));
        assert!(!capabilities_without_ovs.contains(&"network.topology.ovs"));
        let mut without_vrf = all_capability_state();
        without_vrf.vrf_operations_available = false;
        let capabilities_without_vrf = available_capabilities(without_vrf);
        assert!(capabilities_without_vrf.contains(&"network.topology.reconcile"));
        assert!(!capabilities_without_vrf.contains(&"network.topology.vrf"));
        assert!(
            validate_capability_target(
                "network.overlay.peer_paths.read",
                "",
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false
            )
            .is_ok()
        );
        assert!(
            validate_capability_target(
                "network.overlay.peer_paths.read",
                "",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false
            )
            .is_err()
        );
        assert!(
            validate_capability_target(
                "k3s.preflight",
                "",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false
            )
            .is_ok()
        );
        Ok(())
    }

    #[test]
    fn container_deploy_requires_a_typed_payload() {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as i64;
        let mut deploy = request("container.deploy", "nanpanel-test", now + 30_000);
        assert!(validate_payload_presence(&deploy).is_err());

        deploy.payload_json = Some(
            r#"{"image":"registry.example.test/web:2.0","hostPort":18080,"containerPort":8080}"#
                .to_owned(),
        );
        assert!(validate_payload_presence(&deploy).is_ok());
        assert!(parse_container_deploy_spec(&deploy).is_ok());

        deploy.payload_json =
            Some(r#"{"image":"registry.example.test/web:2.0","command":"id"}"#.to_owned());
        assert!(validate_payload_presence(&deploy).is_ok());
        assert!(parse_container_deploy_spec(&deploy).is_err());
    }

    #[test]
    fn container_deploy_accepts_bounded_environment_volumes_and_stateful() {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as i64;
        let mut deploy = request("container.deploy", "nanpanel-stateful", now + 30_000);
        deploy.payload_json = Some(
            r##"{"image":"mysql:8.4","hostPort":13306,"containerPort":3306,"environment":["MYSQL_ROOT_PASSWORD=hunter2","MYSQL_DATABASE=app"],"volumes":[{"name":"data","mountPath":"/var/lib/mysql"}],"memoryMb":1024,"stateful":true}"##
                .to_owned(),
        );
        let spec = parse_container_deploy_spec(&deploy).expect("valid stateful payload");
        assert_eq!(spec.environment.len(), 2);
        assert_eq!(spec.volumes.len(), 1);
        assert_eq!(spec.memory_mb, 1024);
        assert!(spec.stateful);

        for invalid in [
            r#"{"image":"mysql:8.4","environment":["-bad=value"]}"#,
            r#"{"image":"mysql:8.4","environment":["NO_EQUALS"]}"#,
            r#"{"image":"mysql:8.4","volumes":[{"name":"data","mountPath":"../etc"}]}"#,
            r#"{"image":"mysql:8.4","volumes":[{"name":"data","mountPath":"relative"}]}"#,
            r#"{"image":"mysql:8.4","memoryMb":32}"#,
            r#"{"image":"mysql:8.4","memoryMb":4096}"#,
        ] {
            deploy.payload_json = Some(invalid.to_owned());
            assert!(
                parse_container_deploy_spec(&deploy).is_err(),
                "must reject: {invalid}"
            );
        }
    }

    #[test]
    fn container_remove_targets_a_container_and_rejects_payload() {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as i64;
        assert!(
            validate_capability_target(
                "container.remove",
                "nanpanel-web",
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false
            )
            .is_ok()
        );
        assert!(
            validate_capability_target(
                "container.remove",
                "bad;name",
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false
            )
            .is_err()
        );
        assert!(
            validate_capability_target(
                "container.remove",
                "nanpanel-web",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false
            )
            .is_err()
        );
        let remove = request("container.remove", "nanpanel-web", now + 30_000);
        assert!(validate_payload_presence(&remove).is_ok());
        let mut with_payload = remove;
        with_payload.payload_json = Some(r#"{"removeVolumes":true}"#.to_owned());
        assert!(validate_payload_presence(&with_payload).is_err());
    }

    #[test]
    fn k3s_preflight_is_typed_read_only_and_returns_bounded_checks() -> Result<()> {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .context("system clock is before Unix epoch")?
            .as_millis() as i64;
        let mut input = request("k3s.preflight", "", now + 30_000);
        input.payload_json = Some(r#"{"role":"server"}"#.to_owned());
        assert!(validate_payload_presence(&input).is_ok());
        let report = k3s_preflight(&input).map_err(anyhow::Error::msg)?;
        let value: serde_json::Value = serde_json::from_str(&report)?;
        assert_eq!(value["schemaVersion"], 1);
        assert_eq!(value["role"], "server");
        assert!(
            value["checks"]
                .as_array()
                .is_some_and(|checks| !checks.is_empty())
        );

        input.payload_json = Some(r#"{"role":"agent","command":"id"}"#.to_owned());
        assert!(k3s_preflight(&input).is_err());
        input.payload_json = Some(r#"{"role":"invalid"}"#.to_owned());
        assert!(k3s_preflight(&input).is_err());
        Ok(())
    }

    #[test]
    fn firewall_capability_depends_on_system_tools_not_rule_configuration() {
        assert!(
            validate_capability_target(
                "network.firewall.expose",
                "ssh-temp",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false
            )
            .is_err()
        );
        assert!(
            validate_capability_target(
                "network.firewall.expose",
                "ssh-temp",
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                false
            )
            .is_ok()
        );
        assert!(
            available_capabilities(CapabilityState {
                kubeedge_available: true,
                ..CapabilityState::default()
            })
            .contains(&"kubeedge.edgecore.join")
        );
        assert!(
            !available_capabilities(CapabilityState::default()).contains(&"kubeedge.edgecore.join")
        );
        assert!(
            !available_capabilities(CapabilityState::default())
                .contains(&"network.firewall.expose")
        );
        assert!(
            available_capabilities(CapabilityState {
                network_operations_available: true,
                ..CapabilityState::default()
            })
            .contains(&"network.firewall.expose")
        );
        assert!(
            !available_capabilities(CapabilityState::default())
                .contains(&"network.overlay.peer_paths.read")
        );
    }

    #[test]
    fn netbird_peer_path_parser_returns_only_bounded_identity_and_path_fields() -> Result<()> {
        let input = br#"{
          "peers": {"total": 2, "connected": 2, "details": [
            {"fqdn":"api-a.example.test","netbirdIp":"100.64.0.1","publicKey":"public-key-a","status":"Connected","connectionType":"P2P","relayAddress":"rel://relay.example.test:443"},
            {"fqdn":"db-b.example.test","netbirdIp":"100.64.0.2","publicKey":"public-key-b","status":"Connected","connectionType":"Relayed"}
          ]}
        }"#;
        let peers = parse_netbird_peer_paths(input).map_err(anyhow::Error::msg)?;
        assert_eq!(peers.len(), 2);
        assert_eq!(peers[0].peer_name, "api-a.example.test");
        assert_eq!(peers[0].status, "CONNECTED");
        assert_eq!(peers[0].connection_type, "P2P");
        assert_eq!(peers[1].connection_type, "RELAY");
        let reduced = serde_json::to_string(&peers)?;
        assert!(!reduced.contains("100.64.0."));
        assert!(!reduced.contains("publicKey"));
        assert!(!reduced.contains("relayAddress"));
        Ok(())
    }

    #[test]
    fn netbird_peer_path_parser_rejects_ambiguous_or_oversized_snapshots() {
        assert!(parse_netbird_peer_paths(br#"{"peers":{"details":[]}}"#).is_ok());
        assert!(parse_netbird_peer_paths(b"not-json").is_err());
        let ip_identity = br#"{"peers":{"details":[{"fqdn":"100.64.0.1","status":"Connected","connectionType":"P2P"}]}}"#;
        assert!(parse_netbird_peer_paths(ip_identity).is_err());
        let duplicate = br#"{"peers":{"details":[{"fqdn":"peer.example.test","status":"Connected","connectionType":"P2P"},{"fqdn":"PEER.example.test","status":"Disconnected","connectionType":"-"}]}}"#;
        assert!(parse_netbird_peer_paths(duplicate).is_err());
        assert!(parse_netbird_peer_paths(&vec![b'x'; MAX_NETBIRD_STATUS_BYTES + 1]).is_err());
    }

    #[test]
    fn network_rule_verification_matches_interface_protocol_port_and_comment() -> Result<()> {
        let spec = NetworkRuleSpec {
            interface: "eth0".to_owned(),
            protocol: "tcp".to_owned(),
            port: 2222,
        };
        let table = serde_json::json!({
            "nftables": [{"rule": {
                "table": "nanpanel_agent",
                "chain": "input",
                "comment": "nanpanel:6d7163d2-5e8c-4c11-96f3-2d03cb47d26a",
                "handle": 42,
                "expr": [
                    {"match": {"op": "==", "left": {"meta": {"key": "iifname"}}, "right": "eth0"}},
                    {"match": {"op": "==", "left": {"payload": {"protocol": "tcp", "field": "dport"}}, "right": 2222}},
                    {"accept": null}
                ]
            }}]
        });
        assert_eq!(
            find_network_rule_handle(
                &table,
                "nanpanel:6d7163d2-5e8c-4c11-96f3-2d03cb47d26a",
                Some(&spec),
            )
            .map_err(anyhow::Error::msg)?,
            Some(42)
        );
        let wrong_protocol = NetworkRuleSpec {
            protocol: "udp".to_owned(),
            ..spec.clone()
        };
        assert!(
            find_network_rule_handle(
                &table,
                "nanpanel:6d7163d2-5e8c-4c11-96f3-2d03cb47d26a",
                Some(&wrong_protocol),
            )
            .is_err()
        );
        Ok(())
    }

    #[test]
    fn protected_file_writes_validate_names_and_write_atomically() -> Result<()> {
        let root = tempfile::tempdir()?;
        let write_root_path = root.path().join("files");
        std::fs::create_dir(&write_root_path)?;
        let write_root = File::open(&write_root_path)?;
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .context("system clock is before Unix epoch")?
            .as_millis() as i64;
        let mut request = request("file.write.protected", "app.conf", now + 30_000);
        request.payload_json = Some(r#"{"content":"port=8080\n"}"#.to_owned());
        let result = write_protected_file(&request, &write_root).map_err(anyhow::Error::msg)?;
        assert!(result.contains("\"bytesWritten\":10"));
        assert_eq!(
            std::fs::read_to_string(write_root_path.join("app.conf"))?,
            "port=8080\n"
        );
        request.target = "other.conf".to_owned();
        write_protected_file(&request, &write_root).map_err(anyhow::Error::msg)?;
        assert!(write_root_path.join("other.conf").is_file());
        request.target = "../outside".to_owned();
        assert!(write_protected_file(&request, &write_root).is_err());
        Ok(())
    }

    #[test]
    fn host_file_operations_are_bounded_and_reject_traversal_and_symlinks() -> Result<()> {
        let directory = tempfile::tempdir()?;
        let root = File::open("/")?;
        let path = directory
            .path()
            .join("managed.conf")
            .to_str()
            .context("temporary path is not UTF-8")?
            .to_owned();
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .context("system clock is before Unix epoch")?
            .as_millis() as i64;
        let mut write = request("file.write", &path, now + 30_000);
        write.payload_json = Some(r#"{"content":"first=1\n"}"#.to_owned());
        write_host_file(&root, &write).map_err(anyhow::Error::msg)?;
        assert_eq!(std::fs::read_to_string(&path)?, "first=1\n");

        write.payload_json = Some(r#"{"content":"second=2\n"}"#.to_owned());
        write_host_file(&root, &write).map_err(anyhow::Error::msg)?;
        let read = read_host_file(&root, &path).map_err(anyhow::Error::msg)?;
        assert_eq!(read, "second=2\n");

        let parent = directory
            .path()
            .to_str()
            .context("temporary directory path is not UTF-8")?;
        let listing = list_host_directory(&root, parent).map_err(anyhow::Error::msg)?;
        let listing_value: serde_json::Value = serde_json::from_str(&listing)?;
        assert_eq!(listing_value["entries"][0]["name"], "managed.conf");

        let symlink = directory.path().join("outside-link");
        std::os::unix::fs::symlink("/etc/hosts", &symlink)?;
        let symlink_path = symlink.to_str().context("symlink path is not UTF-8")?;
        assert!(read_host_file(&root, symlink_path).is_err());
        assert!(valid_host_path("/etc/../passwd", false).is_err());
        assert!(valid_host_path("/", false).is_err());

        delete_host_file(&root, &path).map_err(anyhow::Error::msg)?;
        assert!(!directory.path().join("managed.conf").exists());
        Ok(())
    }

    #[test]
    fn systemd_target_validation_accepts_unit_names_and_rejects_options() {
        assert!(valid_systemd_unit("ssh.service"));
        assert!(valid_systemd_unit("worker@blue.service"));
        assert!(!valid_systemd_unit("--help.service"));
        assert!(!valid_systemd_unit("sshd.service;id"));
    }

    #[test]
    fn journal_result_is_sanitized_and_bounded_on_utf8_boundaries() {
        let input = format!("{}{}", "日志行".repeat(1000), "\u{0007}tail");
        let sanitized = input
            .chars()
            .filter(|character| *character == '\n' || *character == '\t' || !character.is_control())
            .collect::<String>();
        let result = truncate_journal_output(&sanitized);
        assert!(result.len() <= 1_820);
        assert!(result.ends_with("[output truncated]"));
    }
}
