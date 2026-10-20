use std::{
    collections::{HashMap, HashSet},
    ffi::CString,
    fs::File,
    io::{Read, Take},
    os::{
        fd::{AsRawFd, FromRawFd},
        unix::fs::{FileTypeExt, PermissionsExt},
    },
    path::{Component, Path},
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use anyhow::{Context, Result, bail};
use futures_util::{SinkExt, StreamExt};
use nix::sys::socket::{getsockopt, sockopt::PeerCredentials};
use serde::{Deserialize, Serialize};
use tokio::net::{UnixListener, UnixStream};
use tokio_util::codec::{Framed, LinesCodec};

const MAX_FRAME: usize = 128 * 1024;
const MAX_FILE_BYTES: usize = 64 * 1024;
const MAX_WRITE_BYTES: usize = 32 * 1024;
const MAX_COMMAND_OUTPUT_BYTES: usize = 32 * 1024;
const MAX_PAYLOAD_BYTES: usize = 48 * 1024;
const MAX_AGENT_BYTES: usize = 64 * 1024 * 1024;
const MAX_JOURNAL_CAPTURE_BYTES: usize = 64 * 1024;
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
    pub allowed_service_units: HashSet<String>,
    pub allowed_journal_units: HashSet<String>,
    pub allowlists_disabled: bool,
    pub allowed_packages: HashSet<String>,
    pub allowed_files: HashSet<String>,
    pub allowed_containers: HashMap<String, ContainerSpec>,
    allowed_network_rules: HashMap<String, NetworkRuleSpec>,
    pub container_runtime: Option<std::path::PathBuf>,
    network_operations_available: bool,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ContainerSpec {
    image: String,
    host_port: Option<u16>,
    container_port: Option<u16>,
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
struct ContainerDeployPayload {
    image: String,
    host_port: Option<u16>,
    container_port: Option<u16>,
}

struct UnitAllowlist {
    restart: HashSet<String>,
    journal: HashSet<String>,
}

struct HelperRuntime {
    allowed_uid: u32,
    read_root: Arc<File>,
    write_root: Arc<File>,
    controller_url: reqwest::Url,
    controller_cert: reqwest::Certificate,
    agent_binary: std::path::PathBuf,
    allowed_units: Arc<UnitAllowlist>,
    allowlists_disabled: bool,
    allowed_packages: Arc<HashSet<String>>,
    allowed_files: Arc<HashSet<String>>,
    allowed_containers: Arc<HashMap<String, ContainerSpec>>,
    allowed_network_rules: Arc<HashMap<String, NetworkRuleSpec>>,
    container_runtime: Option<Arc<std::path::PathBuf>>,
    network_operations_available: bool,
    network_lock: tokio::sync::Mutex<()>,
}

#[derive(Clone, Copy)]
struct CapabilityAllowlist<'a> {
    restart_units: &'a HashSet<String>,
    journal_units: &'a HashSet<String>,
    allowlists_disabled: bool,
    packages: &'a HashSet<String>,
    files: &'a HashSet<String>,
    containers: &'a HashMap<String, ContainerSpec>,
    network_rules: &'a HashMap<String, NetworkRuleSpec>,
    has_container_runtime: bool,
    network_operations_available: bool,
}

impl HelperRuntime {
    fn capability_allowlist(&self) -> CapabilityAllowlist<'_> {
        CapabilityAllowlist {
            restart_units: &self.allowed_units.restart,
            journal_units: &self.allowed_units.journal,
            allowlists_disabled: self.allowlists_disabled,
            packages: &self.allowed_packages,
            files: &self.allowed_files,
            containers: &self.allowed_containers,
            network_rules: &self.allowed_network_rules,
            has_container_runtime: self.container_runtime.is_some(),
            network_operations_available: self.network_operations_available,
        }
    }
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
        let allowed_service_units = read_service_allowlist(&allowed_read_root)?;
        let allowed_journal_units =
            read_unit_allowlist(&allowed_read_root, "journal-units.allowlist")?;
        let allowlists_disabled = read_allowlist_mode(&allowed_read_root)?;
        let allowed_packages = read_simple_allowlist(
            &allowed_read_root,
            "packages.allowlist",
            valid_package_name,
            256,
        )?;
        let allowed_files =
            read_simple_allowlist(&allowed_read_root, "files.allowlist", valid_file_name, 128)?;
        let allowed_containers = read_container_allowlist(&allowed_read_root)?;
        let allowed_network_rules = read_network_allowlist(&allowed_read_root)?;
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
        Ok(Self {
            socket_path: path,
            allowed_agent_uid,
            socket_gid,
            allowed_read_root,
            allowed_write_root,
            controller_url,
            controller_cert,
            agent_binary,
            allowed_service_units,
            allowed_journal_units,
            allowlists_disabled,
            allowed_packages,
            allowed_files,
            allowed_containers,
            allowed_network_rules,
            container_runtime,
            network_operations_available,
        })
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
    let runtime = Arc::new(HelperRuntime {
        allowed_uid: config.allowed_agent_uid,
        read_root: Arc::new(config.allowed_read_root),
        write_root: Arc::new(config.allowed_write_root),
        controller_url: config.controller_url,
        controller_cert: config.controller_cert,
        agent_binary: config.agent_binary,
        allowed_units: Arc::new(UnitAllowlist {
            restart: config.allowed_service_units,
            journal: config.allowed_journal_units,
        }),
        allowlists_disabled: config.allowlists_disabled,
        allowed_packages: Arc::new(config.allowed_packages),
        allowed_files: Arc::new(config.allowed_files),
        allowed_containers: Arc::new(config.allowed_containers),
        allowed_network_rules: Arc::new(config.allowed_network_rules),
        container_runtime: config.container_runtime.map(Arc::new),
        network_operations_available: config.network_operations_available,
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
                                    &runtime.capability_allowlist(),
                                )
                            }) {
                                Err(error) => Err(error),
                                Ok(()) => match request.capability.as_str() {
                                    "helper.status" => Ok("AVAILABLE".to_owned()),
                                    "helper.capabilities" => Ok(serde_json::json!(
                                        available_capabilities(runtime.capability_allowlist(),)
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
                                    "file.read.allowed" => execute(&request, &runtime.read_root),
                                    "package.install" => {
                                        install_packages(
                                            &request,
                                            &runtime.allowed_packages,
                                            runtime.allowlists_disabled,
                                        )
                                        .await
                                    }
                                    "file.write.protected" => write_protected_file(
                                        &request,
                                        &runtime.write_root,
                                        &runtime.allowed_files,
                                        runtime.allowlists_disabled,
                                    ),
                                    "container.list" => {
                                        manage_container(
                                            &request,
                                            &runtime.allowed_containers,
                                            runtime
                                                .container_runtime
                                                .as_deref()
                                                .map(|path| path.as_path()),
                                            runtime.allowlists_disabled,
                                        )
                                        .await
                                    }
                                    "container.logs" | "container.start" | "container.stop"
                                    | "container.deploy" => {
                                        manage_container(
                                            &request,
                                            &runtime.allowed_containers,
                                            runtime
                                                .container_runtime
                                                .as_deref()
                                                .map(|path| path.as_path()),
                                            runtime.allowlists_disabled,
                                        )
                                        .await
                                    }
                                    "network.firewall.expose" => {
                                        let _guard = runtime.network_lock.lock().await;
                                        expose_network_port(
                                            &request,
                                            &runtime.allowed_network_rules,
                                            runtime.allowlists_disabled,
                                        )
                                        .await
                                    }
                                    _ => Err("capability is not allowlisted".to_owned()),
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
    if request.capability != "file.read.allowed" {
        return Err("capability is not allowlisted".to_owned());
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

fn validate_capability_target(
    capability: &str,
    target: &str,
    allowed: &CapabilityAllowlist<'_>,
) -> std::result::Result<(), String> {
    match capability {
        "helper.status" | "helper.capabilities" | "agent.update" if target.is_empty() => Ok(()),
        "systemd.restart"
            if valid_systemd_unit(target)
                && (target == "nanpanel-agent.service"
                    || allowed.allowlists_disabled
                    || allowed.restart_units.contains(target)) =>
        {
            Ok(())
        }
        "systemd.journal.read"
            if valid_systemd_unit(target)
                && (allowed.allowlists_disabled || allowed.journal_units.contains(target)) =>
        {
            Ok(())
        }
        "file.read.allowed" => Ok(()),
        "package.install"
            if target.is_empty()
                && (allowed.allowlists_disabled || !allowed.packages.is_empty()) =>
        {
            Ok(())
        }
        "file.write.protected"
            if valid_file_name(target)
                && (allowed.allowlists_disabled || allowed.files.contains(target)) =>
        {
            Ok(())
        }
        "container.list"
            if target.is_empty()
                && allowed.has_container_runtime
                && (allowed.allowlists_disabled || !allowed.containers.is_empty()) =>
        {
            Ok(())
        }
        "container.logs" | "container.start" | "container.stop" | "container.deploy"
            if valid_container_name(target)
                && allowed.has_container_runtime
                && (allowed.allowlists_disabled || allowed.containers.contains_key(target)) =>
        {
            Ok(())
        }
        "network.firewall.expose"
            if allowed.network_operations_available
                && valid_network_rule_name(target)
                && (allowed.allowlists_disabled || allowed.network_rules.contains_key(target)) =>
        {
            Ok(())
        }
        "helper.status"
        | "helper.capabilities"
        | "agent.update"
        | "systemd.restart"
        | "systemd.journal.read"
        | "package.install"
        | "file.write.protected"
        | "container.list"
        | "container.logs"
        | "container.start"
        | "container.stop"
        | "container.deploy"
        | "network.firewall.expose" => {
            Err("DENIED: target is not allowlisted for this capability".to_owned())
        }
        _ => Err("DENIED: capability is not allowlisted".to_owned()),
    }
}

fn validate_payload_presence(request: &Request) -> std::result::Result<(), String> {
    let required = matches!(
        request.capability.as_str(),
        "package.install" | "file.write.protected" | "network.firewall.expose"
    );
    if required != request.payload_json.is_some() {
        return Err("DENIED: payload presence does not match capability schema".to_owned());
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

fn available_capabilities(allowed: CapabilityAllowlist<'_>) -> Vec<&'static str> {
    let mut capabilities = vec!["systemd.restart", "helper.capabilities"];
    if allowed.allowlists_disabled || !allowed.journal_units.is_empty() {
        capabilities.push("systemd.journal.read");
    }
    if allowed.allowlists_disabled || !allowed.packages.is_empty() {
        capabilities.push("package.install");
    }
    if allowed.allowlists_disabled || !allowed.files.is_empty() {
        capabilities.push("file.write.protected");
    }
    if allowed.has_container_runtime
        && (allowed.allowlists_disabled || !allowed.containers.is_empty())
    {
        capabilities.extend([
            "container.list",
            "container.logs",
            "container.start",
            "container.stop",
            "container.deploy",
        ]);
    }
    if allowed.network_operations_available
        && (allowed.allowlists_disabled || !allowed.network_rules.is_empty())
    {
        capabilities.push("network.firewall.expose");
    }
    capabilities
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

async fn install_packages(
    request: &Request,
    allowed_packages: &HashSet<String>,
    allowlists_disabled: bool,
) -> std::result::Result<String, String> {
    let payload = parse_payload::<PackageInstallPayload>(request)?;
    if payload.packages.is_empty()
        || payload.packages.len() > 16
        || payload.packages.iter().any(|name| {
            !valid_package_name(name) || (!allowlists_disabled && !allowed_packages.contains(name))
        })
    {
        return Err("DENIED: package list is empty, invalid, or not authorized".to_owned());
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
    allowed_files: &HashSet<String>,
    allowlists_disabled: bool,
) -> std::result::Result<String, String> {
    let payload = parse_payload::<ProtectedFilePayload>(request)?;
    if !valid_file_name(&request.target)
        || (!allowlists_disabled && !allowed_files.contains(&request.target))
    {
        return Err("DENIED: protected file is not authorized".to_owned());
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

async fn expose_network_port(
    request: &Request,
    allowlist: &HashMap<String, NetworkRuleSpec>,
    allowlists_disabled: bool,
) -> std::result::Result<String, String> {
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
    let requested_rule = NetworkRuleSpec {
        interface: payload.interface_name,
        protocol: payload.protocol,
        port: payload.port,
    };
    let rule = if allowlists_disabled {
        requested_rule
    } else {
        let Some(allowed_rule) = allowlist.get(&request.target) else {
            return Err("DENIED: network rule is not allowlisted".to_owned());
        };
        if allowed_rule != &requested_rule {
            return Err("DENIED: firewall parameters do not match the allowlisted rule".to_owned());
        }
        allowed_rule.clone()
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
        return Err("FAILED: firewall rule differs from its allowlisted definition".to_owned());
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
    allowed_containers: &HashMap<String, ContainerSpec>,
    runtime: Option<&Path>,
    allowlists_disabled: bool,
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
            if !allowlists_disabled && allowed_containers.is_empty() {
                return Err("DENIED: no containers are allowlisted".to_owned());
            }
            if allowlists_disabled {
                args.extend([
                    "ps".to_owned(),
                    "--all".to_owned(),
                    "--format".to_owned(),
                    "{{.Names}} {{.Status}}".to_owned(),
                ]);
            } else {
                args.extend([
                    "inspect".to_owned(),
                    "--format".to_owned(),
                    "{{.Name}} {{.State.Status}}".to_owned(),
                ]);
                args.extend(allowed_containers.keys().cloned());
            }
        }
        "container.logs" => {
            if request.payload_json.is_some() {
                return Err("DENIED: container.logs does not accept a payload".to_owned());
            }
            if !valid_container_name(&request.target)
                || (!allowlists_disabled && !allowed_containers.contains_key(&request.target))
            {
                return Err("DENIED: container is not authorized".to_owned());
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
            if request.payload_json.is_some()
                || !valid_container_name(&request.target)
                || (!allowlists_disabled && !allowed_containers.contains_key(&request.target))
            {
                return Err("DENIED: container start target or payload is invalid".to_owned());
            }
            args.extend(["start".to_owned(), request.target.clone()]);
        }
        "container.stop" => {
            if request.payload_json.is_some()
                || !valid_container_name(&request.target)
                || (!allowlists_disabled && !allowed_containers.contains_key(&request.target))
            {
                return Err("DENIED: container stop target or payload is invalid".to_owned());
            }
            args.extend([
                "stop".to_owned(),
                "--time".to_owned(),
                "10".to_owned(),
                request.target.clone(),
            ]);
        }
        "container.deploy" => {
            let requested_spec = match request.payload_json.as_deref() {
                Some(_) => parse_container_deploy_spec(request)?,
                None if !allowlists_disabled => allowed_containers
                    .get(&request.target)
                    .cloned()
                    .ok_or_else(|| "DENIED: container deployment is not allowlisted".to_owned())?,
                None => {
                    return Err(
                        "DENIED: container deploy requires an image specification".to_owned()
                    );
                }
            };
            if !allowlists_disabled
                && allowed_containers.get(&request.target) != Some(&requested_spec)
            {
                return Err(
                    "DENIED: container parameters do not match the allowlisted specification"
                        .to_owned(),
                );
            };
            args.extend([
                "run".to_owned(),
                "--detach".to_owned(),
                "--name".to_owned(),
                request.target.clone(),
                "--restart=unless-stopped".to_owned(),
                "--memory=256m".to_owned(),
                "--cpus=1".to_owned(),
                "--pids-limit=256".to_owned(),
                "--cap-drop=ALL".to_owned(),
                "--security-opt=no-new-privileges".to_owned(),
                "--read-only".to_owned(),
                "--network=bridge".to_owned(),
            ]);
            if let (Some(host_port), Some(container_port)) =
                (requested_spec.host_port, requested_spec.container_port)
            {
                args.extend([
                    "--publish".to_owned(),
                    format!("127.0.0.1:{host_port}:{container_port}"),
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
    if !valid_container_image(&payload.image)
        || payload.host_port.is_some_and(|port| port == 0)
        || payload.container_port.is_some_and(|port| port == 0)
        || payload.host_port.is_some() != payload.container_port.is_some()
    {
        return Err("DENIED: container image or port mapping is invalid".to_owned());
    }
    Ok(ContainerSpec {
        image: payload.image,
        host_port: payload.host_port,
        container_port: payload.container_port,
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

fn read_service_allowlist(root: &File) -> Result<HashSet<String>> {
    read_unit_allowlist(root, "systemd-units.allowlist")
}

fn read_allowlist_mode(root: &File) -> Result<bool> {
    match read_beneath(root, "allowlist-mode") {
        Ok(mode) => Ok(allowlist_mode_is_disabled(&mode)),
        Err(error)
            if error.chain().any(|source| {
                source
                    .downcast_ref::<std::io::Error>()
                    .is_some_and(|io| io.kind() == std::io::ErrorKind::NotFound)
            }) =>
        {
            Ok(false)
        }
        Err(error) => Err(error).context("read root-managed operation allowlist mode"),
    }
}

fn allowlist_mode_is_disabled(mode: &str) -> bool {
    mode.trim() == "disabled"
}

fn read_unit_allowlist(root: &File, filename: &str) -> Result<HashSet<String>> {
    let contents = match read_beneath(root, filename) {
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
        Err(error) => return Err(error).context("read root-managed systemd allowlist"),
    };
    let mut units = HashSet::new();
    for unit in contents.lines().map(str::trim) {
        if unit.is_empty() || unit.starts_with('#') {
            continue;
        }
        if !valid_systemd_unit(unit)
            || (filename == "systemd-units.allowlist" && unit == "nanpanel-agent.service")
        {
            bail!("invalid or reserved service unit in systemd allowlist");
        }
        units.insert(unit.to_owned());
        if units.len() > 128 {
            bail!("systemd allowlist exceeds 128 units");
        }
    }
    Ok(units)
}

fn read_simple_allowlist(
    root: &File,
    filename: &str,
    validator: fn(&str) -> bool,
    maximum_entries: usize,
) -> Result<HashSet<String>> {
    let contents = match read_beneath(root, filename) {
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
        Err(error) => return Err(error).context("read root-managed operation allowlist"),
    };
    let mut entries = HashSet::new();
    for entry in contents.lines().map(str::trim) {
        if entry.is_empty() || entry.starts_with('#') {
            continue;
        }
        if !validator(entry) {
            bail!("invalid entry in {filename}");
        }
        entries.insert(entry.to_owned());
        if entries.len() > maximum_entries {
            bail!("{filename} exceeds {maximum_entries} entries");
        }
    }
    Ok(entries)
}

fn read_container_allowlist(root: &File) -> Result<HashMap<String, ContainerSpec>> {
    let contents = match read_beneath(root, "containers.allowlist") {
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
        Err(error) => return Err(error).context("read root-managed container allowlist"),
    };
    let mut containers = HashMap::new();
    for line in contents.lines().map(str::trim) {
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let fields = line.split_whitespace().collect::<Vec<_>>();
        if fields.len() != 4
            || !valid_container_name(fields[0])
            || !valid_container_image(fields[1])
        {
            bail!("invalid entry in containers.allowlist");
        }
        let (host_port, container_port) = match (fields[2], fields[3]) {
            ("-", "-") => (None, None),
            (host, container) => {
                let host_port = host
                    .parse::<u16>()
                    .ok()
                    .filter(|port| *port > 0)
                    .context("invalid host port in container allowlist")?;
                let container_port = container
                    .parse::<u16>()
                    .ok()
                    .filter(|port| *port > 0)
                    .context("invalid container port in container allowlist")?;
                (Some(host_port), Some(container_port))
            }
        };
        let name = fields[0].to_owned();
        if containers
            .insert(
                name,
                ContainerSpec {
                    image: fields[1].to_owned(),
                    host_port,
                    container_port,
                },
            )
            .is_some()
        {
            bail!("duplicate container name in allowlist");
        }
        if containers.len() > 128 {
            bail!("containers.allowlist exceeds 128 entries");
        }
    }
    Ok(containers)
}

fn read_network_allowlist(root: &File) -> Result<HashMap<String, NetworkRuleSpec>> {
    use std::os::unix::fs::MetadataExt;

    let contents = match read_beneath(root, "network.allowlist") {
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
        Err(error) => return Err(error).context("read root-managed network allowlist"),
    };
    if let Ok(file) = open_beneath(
        root,
        "network.allowlist",
        libc::O_RDONLY | libc::O_CLOEXEC,
        0,
    ) {
        let metadata = file.metadata()?;
        if !metadata.is_file() || metadata.uid() != 0 || metadata.mode() & 0o022 != 0 {
            bail!("network.allowlist must be a root-owned non-writable regular file");
        }
    }
    parse_network_allowlist(&contents)
}

fn parse_network_allowlist(contents: &str) -> Result<HashMap<String, NetworkRuleSpec>> {
    let mut rules = HashMap::new();
    let mut unique_rules = HashSet::new();
    for line in contents.lines().map(str::trim) {
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let fields = line.split_whitespace().collect::<Vec<_>>();
        if fields.len() != 4
            || !valid_network_rule_name(fields[0])
            || !valid_network_interface(fields[1])
            || !matches!(fields[2], "tcp" | "udp")
        {
            bail!("invalid entry in network.allowlist");
        }
        let port = fields[3]
            .parse::<u16>()
            .ok()
            .filter(|port| *port > 0)
            .context("invalid port in network allowlist")?;
        let rule = NetworkRuleSpec {
            interface: fields[1].to_owned(),
            protocol: fields[2].to_owned(),
            port,
        };
        if !unique_rules.insert(rule.clone()) {
            bail!("duplicate interface/protocol/port in network allowlist");
        }
        if rules.insert(fields[0].to_owned(), rule).is_some() {
            bail!("duplicate network rule name in allowlist");
        }
        if rules.len() > 128 {
            bail!("network.allowlist exceeds 128 entries");
        }
    }
    Ok(rules)
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
        bail!("target must be a single allowlisted file name");
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
                &request("file.read.allowed", "allowed.txt", now + 30_000),
                &root_fd
            )
            .map_err(anyhow::Error::msg)?,
            "root-only fixture"
        );
        assert!(
            execute(
                &request("file.read.allowed", "../etc/passwd", now + 30_000),
                &root_fd
            )
            .is_err()
        );
        #[cfg(unix)]
        assert!(
            execute(
                &request("file.read.allowed", "outside.txt", now + 30_000),
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
                &request("file.read.allowed", "allowed.txt", now - 1),
                &root_fd
            )
            .is_err()
        );
        Ok(())
    }

    #[test]
    fn helper_capabilities_are_typed_and_target_limited() -> Result<()> {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .context("system clock is before Unix epoch")?
            .as_millis() as i64;
        let allowed = HashSet::from(["ssh.service".to_owned()]);
        let journal_allowed = HashSet::from(["cron.service".to_owned()]);
        let no_packages = HashSet::new();
        let no_files = HashSet::new();
        let no_containers = HashMap::new();
        let no_network_rules = HashMap::new();
        let allowlist = CapabilityAllowlist {
            restart_units: &allowed,
            journal_units: &journal_allowed,
            allowlists_disabled: false,
            packages: &no_packages,
            files: &no_files,
            containers: &no_containers,
            network_rules: &no_network_rules,
            has_container_runtime: false,
            network_operations_available: false,
        };
        let target_allowed =
            |capability, target| validate_capability_target(capability, target, &allowlist);
        assert!(validate_envelope(&request("agent.update", "", now + 30_000)).is_ok());
        assert!(target_allowed("agent.update", "").is_ok());
        assert!(target_allowed("systemd.restart", "nanpanel-agent.service").is_ok());
        assert!(target_allowed("systemd.restart", "ssh.service").is_ok());
        assert!(target_allowed("systemd.restart", "sshd.service").is_err());
        assert!(target_allowed("systemd.restart", "--help").is_err());
        assert!(target_allowed("systemd.restart", "--help.service").is_err());
        assert!(target_allowed("systemd.journal.read", "cron.service").is_ok());
        assert!(target_allowed("systemd.journal.read", "ssh.service").is_err());
        assert!(target_allowed("systemd.journal.read", "--help").is_err());
        assert!(target_allowed("systemd.journal.read", "--help.service").is_err());
        assert!(target_allowed("agent.update", "https://evil.invalid").is_err());
        assert!(target_allowed("package.install", "nano").is_err());
        let unrestricted = CapabilityAllowlist {
            allowlists_disabled: true,
            has_container_runtime: true,
            network_operations_available: true,
            ..allowlist
        };
        assert!(
            validate_capability_target("systemd.restart", "sshd.service", &unrestricted).is_ok()
        );
        assert!(
            validate_capability_target("systemd.journal.read", "ssh.service", &unrestricted)
                .is_ok()
        );
        assert!(validate_capability_target("systemd.restart", "--help", &unrestricted).is_err());
        assert!(
            validate_capability_target("systemd.restart", "--help.service", &unrestricted).is_err()
        );
        assert!(
            validate_capability_target("systemd.journal.read", "--help", &unrestricted).is_err()
        );
        assert!(
            validate_capability_target("systemd.journal.read", "--help.service", &unrestricted)
                .is_err()
        );
        for (capability, target) in [
            ("package.install", ""),
            ("file.write.protected", "new.conf"),
            ("container.list", ""),
            ("container.logs", "web-test"),
            ("container.start", "web-test"),
            ("container.stop", "web-test"),
            ("container.deploy", "web-test"),
            ("network.firewall.expose", "temporary-test"),
        ] {
            assert!(validate_capability_target(capability, target, &unrestricted).is_ok());
        }
        Ok(())
    }

    #[test]
    fn firewall_capability_requires_allowlist_and_trusted_system_tools() {
        let rules = HashMap::from([(
            "ssh-temp".to_owned(),
            NetworkRuleSpec {
                interface: "eth0".to_owned(),
                protocol: "tcp".to_owned(),
                port: 2222,
            },
        )]);
        let no_units = UnitAllowlist {
            restart: HashSet::new(),
            journal: HashSet::new(),
        };
        let empty_packages = HashSet::new();
        let empty_files = HashSet::new();
        let empty_containers = HashMap::new();
        let unavailable = CapabilityAllowlist {
            restart_units: &no_units.restart,
            journal_units: &no_units.journal,
            allowlists_disabled: false,
            packages: &empty_packages,
            files: &empty_files,
            containers: &empty_containers,
            network_rules: &rules,
            has_container_runtime: false,
            network_operations_available: false,
        };
        assert!(
            validate_capability_target("network.firewall.expose", "ssh-temp", &unavailable)
                .is_err()
        );
        let unavailable_capabilities = available_capabilities(unavailable);
        assert!(!unavailable_capabilities.contains(&"network.firewall.expose"));
        let available_network_capabilities = available_capabilities(CapabilityAllowlist {
            network_operations_available: true,
            ..unavailable
        });
        assert!(available_network_capabilities.contains(&"network.firewall.expose"));
        let unrestricted_allowlist = CapabilityAllowlist {
            allowlists_disabled: true,
            has_container_runtime: true,
            ..unavailable
        };
        let unrestricted_capabilities = available_capabilities(unrestricted_allowlist);
        assert!(unrestricted_capabilities.contains(&"systemd.journal.read"));
        assert!(unrestricted_capabilities.contains(&"package.install"));
        assert!(unrestricted_capabilities.contains(&"file.write.protected"));
        assert!(unrestricted_capabilities.contains(&"container.deploy"));
        let unrestricted_network_capabilities = available_capabilities(CapabilityAllowlist {
            network_operations_available: true,
            ..unrestricted_allowlist
        });
        assert!(unrestricted_network_capabilities.contains(&"network.firewall.expose"));
    }

    #[test]
    fn package_and_container_allowlists_reject_options_and_unbounded_configuration() -> Result<()> {
        assert!(valid_package_name("libssl3"));
        assert!(!valid_package_name("-y"));
        assert!(!valid_package_name("curl;id"));
        assert!(valid_container_image("nginx:1.27"));
        assert!(!valid_container_image("./nginx"));
        assert!(valid_network_interface("br-0"));
        assert!(!valid_network_interface("-eth0"));
        let root = tempfile::tempdir()?;
        std::fs::write(
            root.path().join("containers.allowlist"),
            "web-test nginx:1.27 8080 80\nworker busybox:1.36 - -\n",
        )?;
        let root_fd = File::open(root.path())?;
        let containers = read_container_allowlist(&root_fd)?;
        assert_eq!(containers.len(), 2);
        assert_eq!(containers["web-test"].host_port, Some(8080));
        assert_eq!(containers["worker"].host_port, None);
        std::fs::write(
            root.path().join("containers.allowlist"),
            "web-test nginx:1.27 8080 80\ninvalid nginx:latest 80\n",
        )?;
        assert!(read_container_allowlist(&root_fd).is_err());
        Ok(())
    }

    #[test]
    fn network_allowlist_is_named_and_rejects_duplicate_or_unbounded_rules() -> Result<()> {
        let rules = parse_network_allowlist(
            "# name interface protocol port\nssh-temp eth0 tcp 22\ndns-temp wlan0 udp 53\n",
        )?;
        assert_eq!(rules.len(), 2);
        assert_eq!(rules["ssh-temp"].interface, "eth0");
        assert_eq!(rules["ssh-temp"].protocol, "tcp");
        assert_eq!(rules["ssh-temp"].port, 22);
        assert!(parse_network_allowlist("all eth0 tcp 0\n").is_err());
        assert!(parse_network_allowlist("open eth0 tcp 22\nother eth0 tcp 22\n").is_err());
        assert!(parse_network_allowlist("open eth0 tcp 22\nopen wlan0 udp 53\n").is_err());
        assert!(parse_network_allowlist("open eth0;id tcp 22\n").is_err());
        Ok(())
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
    fn protected_file_writes_use_single_allowlisted_names_and_atomic_contents() -> Result<()> {
        let root = tempfile::tempdir()?;
        let write_root_path = root.path().join("files");
        std::fs::create_dir(&write_root_path)?;
        let write_root = File::open(&write_root_path)?;
        let allowed = HashSet::from(["app.conf".to_owned()]);
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .context("system clock is before Unix epoch")?
            .as_millis() as i64;
        let mut request = request("file.write.protected", "app.conf", now + 30_000);
        request.payload_json = Some(r#"{"content":"port=8080\n"}"#.to_owned());
        let result = write_protected_file(&request, &write_root, &allowed, false)
            .map_err(anyhow::Error::msg)?;
        assert!(result.contains("\"bytesWritten\":10"));
        assert_eq!(
            std::fs::read_to_string(write_root_path.join("app.conf"))?,
            "port=8080\n"
        );
        request.target = "unlisted.conf".to_owned();
        assert!(write_protected_file(&request, &write_root, &allowed, false).is_err());
        write_protected_file(&request, &write_root, &allowed, true).map_err(anyhow::Error::msg)?;
        assert!(write_root_path.join("unlisted.conf").is_file());
        request.target = "../outside".to_owned();
        assert!(write_protected_file(&request, &write_root, &allowed, true).is_err());
        Ok(())
    }

    #[test]
    fn root_managed_systemd_allowlist_rejects_invalid_units() -> Result<()> {
        let root = tempfile::tempdir()?;
        let root_fd = File::open(root.path())?;
        assert!(!read_allowlist_mode(&root_fd)?);
        std::fs::write(root.path().join("allowlist-mode"), "disabled\n")?;
        assert!(read_allowlist_mode(&root_fd)?);
        std::fs::write(root.path().join("allowlist-mode"), "restricted\n")?;
        assert!(!read_allowlist_mode(&root_fd)?);
        std::fs::write(
            root.path().join("systemd-units.allowlist"),
            "# root-controlled\nssh.service\ninvalid;command.service\n",
        )?;
        assert!(read_service_allowlist(&root_fd).is_err());
        std::fs::write(
            root.path().join("systemd-units.allowlist"),
            "--help.service\n",
        )?;
        assert!(read_service_allowlist(&root_fd).is_err());
        std::fs::write(
            root.path().join("systemd-units.allowlist"),
            "# root-controlled\nssh.service\n",
        )?;
        let allowed = read_service_allowlist(&root_fd)?;
        assert!(allowed.contains("ssh.service"));
        assert!(!allowed.contains("nanpanel-agent.service"));
        std::fs::write(
            root.path().join("journal-units.allowlist"),
            "cron.service\n",
        )?;
        assert!(read_unit_allowlist(&root_fd, "journal-units.allowlist")?.contains("cron.service"));
        Ok(())
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
