use std::{
    ffi::CString,
    fs::File,
    io::{Read, Take},
    os::{
        fd::{AsRawFd, FromRawFd},
        unix::fs::PermissionsExt,
    },
    path::{Component, Path},
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};

use anyhow::{Context, Result, bail};
use futures_util::{SinkExt, StreamExt};
use nix::sys::socket::{getsockopt, sockopt::PeerCredentials};
use serde::{Deserialize, Serialize};
use tokio::net::{UnixListener, UnixStream};
use tokio_util::codec::{Framed, LinesCodec};

const MAX_FRAME: usize = 16 * 1024;
const MAX_FILE_BYTES: usize = 64 * 1024;
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
}

impl HelperConfig {
    pub fn new(
        socket_path: impl Into<std::path::PathBuf>,
        allowed_agent_uid: u32,
        socket_gid: u32,
        read_root: impl AsRef<Path>,
    ) -> Result<Self> {
        if !nix::unistd::geteuid().is_root() {
            bail!("privileged-helper must run as root");
        }
        let path = socket_path.into();
        if !path.is_absolute() {
            bail!("helper socket path must be absolute");
        }
        if path.exists() {
            bail!(
                "refusing to replace an existing socket path: {}",
                path.display()
            );
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
        let allowed_read_root = File::open(read_root.as_ref())
            .with_context(|| format!("open read root {}", read_root.as_ref().display()))?;
        if !allowed_read_root.metadata()?.is_dir() {
            bail!("allowed read root is not a directory");
        }
        Ok(Self {
            socket_path: path,
            allowed_agent_uid,
            socket_gid,
            allowed_read_root,
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
    loop {
        let (stream, _) = listener.accept().await?;
        let read_root = Arc::clone(&read_root);
        let allowed_uid = config.allowed_agent_uid;
        tokio::spawn(async move {
            if let Err(error) = handle(stream, allowed_uid, read_root).await {
                eprintln!(
                    "{{\"event\":\"helper_request_error\",\"error\":{}}}",
                    serde_json::to_string(&error.to_string())
                        .unwrap_or_else(|_| "\"unknown\"".to_owned())
                );
            }
        });
    }
}

async fn handle(stream: UnixStream, allowed_uid: u32, read_root: Arc<File>) -> Result<()> {
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
                    execute(&request, &read_root)
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
    if request.capability != "file.read.allowed" {
        return Err("capability is not allowlisted".to_owned());
    }
    read_beneath(allowed_read_root, &request.target).map_err(|e| format!("read denied: {e}"))
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
}
