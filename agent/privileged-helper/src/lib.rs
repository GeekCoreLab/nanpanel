use std::{
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

const MAX_FRAME: usize = 16 * 1024;
const MAX_FILE_BYTES: usize = 64 * 1024;
const MAX_AGENT_BYTES: usize = 64 * 1024 * 1024;
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
    pub controller_url: reqwest::Url,
    pub controller_cert: reqwest::Certificate,
    pub agent_binary: std::path::PathBuf,
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
            controller_url,
            controller_cert,
            agent_binary,
        })
    }
}

pub async fn serve(config: HelperConfig) -> Result<()> {
    let listener = UnixListener::bind(&config.socket_path)
        .with_context(|| format!("bind helper socket {}", config.socket_path.display()))?;
    std::fs::set_permissions(&config.socket_path, std::fs::Permissions::from_mode(0o660))?;
    if let Err(error) =
        std::os::unix::fs::chown(&config.socket_path, Some(0), Some(config.socket_gid))
    {
        let _ = std::fs::remove_file(&config.socket_path);
        return Err(error).context("set helper socket group");
    }
    let read_root = Arc::new(config.allowed_read_root);
    let controller_url = config.controller_url;
    let controller_cert = config.controller_cert;
    let agent_binary = config.agent_binary;
    let mut terminate = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        .context("install Helper SIGTERM handler")?;
    let interrupted = tokio::signal::ctrl_c();
    tokio::pin!(interrupted);
    loop {
        tokio::select! {
            accepted = listener.accept() => {
                let (stream, _) = accepted?;
                let read_root = Arc::clone(&read_root);
                let controller_url = controller_url.clone();
                let controller_cert = controller_cert.clone();
                let agent_binary = agent_binary.clone();
                let allowed_uid = config.allowed_agent_uid;
                tokio::spawn(async move {
                    if let Err(error) = handle(
                        stream,
                        allowed_uid,
                        read_root,
                        controller_url,
                        controller_cert,
                        agent_binary,
                    )
                    .await
                    {
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
    match std::fs::remove_file(&config.socket_path) {
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

async fn handle(
    stream: UnixStream,
    allowed_uid: u32,
    read_root: Arc<File>,
    controller_url: reqwest::Url,
    controller_cert: reqwest::Certificate,
    agent_binary: std::path::PathBuf,
) -> Result<()> {
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
                let result = if peer.uid() != allowed_uid {
                    Err("caller identity is not authorized".to_owned())
                } else {
                    match validate_envelope(&request) {
                        Err(error) => Err(error),
                        Ok(()) => {
                            match validate_capability_target(&request.capability, &request.target) {
                                Err(error) => Err(error),
                                Ok(()) => match request.capability.as_str() {
                                    "helper.status" => Ok("AVAILABLE".to_owned()),
                                    "agent.update" => {
                                        update_agent(
                                            &controller_url,
                                            &controller_cert,
                                            &agent_binary,
                                        )
                                        .await
                                    }
                                    "systemd.restart" => restart_agent_service().await,
                                    "file.read.allowed" => execute(&request, &read_root),
                                    _ => Err("capability is not allowlisted".to_owned()),
                                },
                            }
                        }
                    }
                };
                match result {
                    Ok(result) => {
                        audit(
                            peer.uid(),
                            &request.operation_id,
                            &request.capability,
                            &request.target,
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
                        audit(
                            peer.uid(),
                            &request.operation_id,
                            &request.capability,
                            &request.target,
                            "rejected",
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
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;
    if request.deadline_epoch_ms < now || request.deadline_epoch_ms > now + 10 * 60 * 1000 {
        return Err("deadline is expired or exceeds the 10 minute limit".to_owned());
    }
    Ok(())
}

fn validate_capability_target(capability: &str, target: &str) -> std::result::Result<(), String> {
    match capability {
        "helper.status" | "agent.update" if target.is_empty() => Ok(()),
        "systemd.restart" if target == "nanpanel-agent.service" => Ok(()),
        "file.read.allowed" => Ok(()),
        "helper.status" | "agent.update" | "systemd.restart" => {
            Err("target is not allowlisted for this capability".to_owned())
        }
        _ => Err("capability is not allowlisted".to_owned()),
    }
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

async fn restart_agent_service() -> std::result::Result<String, String> {
    let mut child = tokio::process::Command::new("/usr/bin/systemctl")
        .args(["restart", "nanpanel-agent.service"])
        .kill_on_drop(true)
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map_err(|error| format!("start fixed systemd restart: {error}"))?;
    match tokio::time::timeout(Duration::from_secs(20), child.wait()).await {
        Ok(Ok(status)) if status.success() => Ok("nanpanel-agent.service restarted".to_owned()),
        Ok(Ok(status)) => Err(format!("systemctl restart failed with {status}")),
        Ok(Err(error)) => Err(format!("wait for systemctl restart: {error}")),
        Err(_) => Err("systemctl restart timed out after 20 seconds".to_owned()),
    }
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
        flags: (libc::O_RDONLY | libc::O_CLOEXEC) as u64,
        mode: 0,
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
    let mut file = unsafe { File::from_raw_fd(fd) };
    let mut bytes = Vec::with_capacity(MAX_FILE_BYTES.min(4096));
    let mut limited: Take<&mut File> = (&mut file).take((MAX_FILE_BYTES + 1) as u64);
    limited.read_to_end(&mut bytes)?;
    if bytes.len() > MAX_FILE_BYTES {
        bail!("file exceeds the 64 KiB output limit");
    }
    String::from_utf8(bytes).context("file content is not UTF-8")
}

#[cfg(not(target_os = "linux"))]
fn read_beneath(_root: &File, _target: &str) -> Result<String> {
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
        assert!(validate_envelope(&request("agent.update", "", now + 30_000)).is_ok());
        assert!(validate_capability_target("agent.update", "").is_ok());
        assert!(validate_capability_target("systemd.restart", "nanpanel-agent.service").is_ok());
        assert!(validate_capability_target("systemd.restart", "sshd.service").is_err());
        assert!(validate_capability_target("agent.update", "https://evil.invalid").is_err());
        assert!(validate_capability_target("package.install", "nano").is_err());
        Ok(())
    }
}
