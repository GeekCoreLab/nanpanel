use std::{
    fs::{self, OpenOptions},
    io::Write,
    net::{IpAddr, SocketAddr},
    os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
    time::{Duration, Instant},
};

use anyhow::{Context, bail};
use serde::Deserialize;
use sha2::{Digest, Sha256};
use tokio::{net::TcpListener, process::Command, time::timeout};
use uuid::Uuid;

const MAX_ROUTE_BYTES: usize = 16 * 1024;
const MAX_NGINX_OUTPUT_BYTES: usize = 2 * 1024 * 1024;
const MAX_TLS_MATERIAL_BYTES: usize = 64 * 1024;
const MAX_TLS_PAYLOAD_BYTES: usize = 3 * MAX_TLS_MATERIAL_BYTES;

#[derive(Clone, Debug)]
pub struct NginxRouteConfig {
    include_dir: PathBuf,
    tls_dir: Option<PathBuf>,
    binary: PathBuf,
}

impl NginxRouteConfig {
    pub fn from_environment() -> anyhow::Result<Option<Self>> {
        let Some(directory) = std::env::var_os("NANPANEL_NGINX_INCLUDE_DIR") else {
            return Ok(None);
        };
        let directory = PathBuf::from(directory);
        if !directory.is_absolute() {
            bail!("NANPANEL_NGINX_INCLUDE_DIR must be an absolute path");
        }
        let include_dir = fs::canonicalize(&directory)
            .with_context(|| format!("resolve Nginx include directory {}", directory.display()))?;
        let metadata = fs::metadata(&include_dir).context("inspect Nginx include directory")?;
        if !metadata.is_dir() || !root_owned_path(&include_dir) {
            bail!("Nginx include directory must be a root-owned, non-writable directory");
        }

        let tls_dir = match std::env::var_os("NANPANEL_NGINX_TLS_DIR") {
            Some(value) => {
                let directory = PathBuf::from(value);
                if !directory.is_absolute() {
                    bail!("NANPANEL_NGINX_TLS_DIR must be an absolute path");
                }
                let directory = fs::canonicalize(&directory).with_context(|| {
                    format!("resolve Nginx TLS directory {}", directory.display())
                })?;
                let metadata = fs::metadata(&directory).context("inspect Nginx TLS directory")?;
                if !metadata.is_dir() || !root_owned_path(&directory) {
                    bail!("Nginx TLS directory must be a root-owned, non-writable directory");
                }
                Some(directory)
            }
            None => None,
        };

        let binary = match std::env::var_os("NANPANEL_NGINX_BINARY") {
            Some(value) => trusted_binary(PathBuf::from(value))?,
            None => trusted_binary_candidates(&["/usr/sbin/nginx", "/usr/bin/nginx"])
                .context("Nginx route capability requires a trusted nginx executable")?,
        };
        Ok(Some(Self {
            include_dir,
            tls_dir,
            binary,
        }))
    }

    pub fn validate_tls_target(target: &str) -> bool {
        valid_tls_name(target)
    }

    pub fn validate_target(target: &str) -> bool {
        target.starts_with("nanpanel-")
            && target.len() <= 62
            && target
                .bytes()
                .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
            && target.as_bytes().get(9).is_some_and(u8::is_ascii_lowercase)
    }

    pub async fn set_route(
        &self,
        target: &str,
        payload_json: Option<&str>,
    ) -> std::result::Result<String, String> {
        if !Self::validate_target(target) {
            return Err("DENIED: managed Nginx route name is invalid".to_owned());
        }
        let Some(payload_json) = payload_json else {
            return Err("DENIED: Nginx route payload is required".to_owned());
        };
        if payload_json.len() > MAX_ROUTE_BYTES {
            return Err("DENIED: Nginx route payload is too large".to_owned());
        }
        let route: RoutePayload = serde_json::from_str(payload_json)
            .map_err(|_| "DENIED: Nginx route payload schema is invalid".to_owned())?;
        validate_route(&route)?;

        let listen_ip: IpAddr = route
            .listen_address
            .parse()
            .map_err(|_| "DENIED: Nginx listener address must be a local IP address".to_owned())?;
        if listen_ip.is_unspecified() || listen_ip.is_multicast() {
            return Err(
                "DENIED: Nginx listener address must identify one host interface".to_owned(),
            );
        }
        TcpListener::bind(SocketAddr::new(listen_ip, 0))
            .await
            .map_err(|_| {
                "DENIED: Nginx listener address is not assigned to this host".to_owned()
            })?;

        let route_path = self.include_dir.join(format!("{target}.conf"));
        let previous = read_owned_route(&route_path, target)?;
        let previous_source = self.nginx(&["-T"]).await?;
        verify_include_and_listener(
            &previous_source,
            &self.include_dir,
            &route_path,
            route.listen_port,
        )?;
        let tls_dir = match route.tls.as_ref() {
            Some(tls) => Some(self.require_tls_material(tls)?),
            None => None,
        };
        let rendered = render_route(target, &route, tls_dir.as_deref());
        if rendered.len() > MAX_ROUTE_BYTES {
            return Err("DENIED: rendered Nginx route exceeds the size limit".to_owned());
        }

        for backend in route.backends.iter().filter(|backend| backend.weight > 0) {
            probe(
                IpAddr::V4(std::net::Ipv4Addr::LOCALHOST),
                backend.port,
                &route.server_name,
                &route.health_path,
                false,
            )
            .await
            .map_err(|_| {
                format!(
                    "FAILED: backend {} failed its local HTTP health check",
                    backend.name
                )
            })?;
        }

        atomic_write(&route_path, rendered.as_bytes())?;
        if let Err(error) = self.nginx(&["-t", "-q"]).await {
            restore_route(&route_path, previous.as_deref())?;
            return Err(error);
        }
        if let Err(error) = self.nginx(&["-s", "reload"]).await {
            restore_route(&route_path, previous.as_deref())?;
            if self.nginx(&["-t", "-q"]).await.is_err()
                || self.nginx(&["-s", "reload"]).await.is_err()
            {
                return Err(
                    "UNCERTAIN: Nginx reload failed and the prior route could not be restored"
                        .to_owned(),
                );
            }
            return Err(error);
        }

        let routed_status = match probe_after_reload(
            listen_ip,
            route.listen_port,
            &route.server_name,
            &route.health_path,
            tls_dir.is_some(),
        )
        .await
        {
            Ok(status) => status,
            Err(()) => {
                restore_route(&route_path, previous.as_deref())?;
                if self.nginx(&["-t", "-q"]).await.is_err()
                    || self.nginx(&["-s", "reload"]).await.is_err()
                {
                    return Err("UNCERTAIN: routed HTTP health check failed and the prior route could not be restored".to_owned());
                }
                return Err(
                    "FAILED: Nginx route failed its post-reload HTTP health check".to_owned(),
                );
            }
        };

        let digest = Sha256::digest(rendered.as_bytes());
        let weights = route
            .backends
            .iter()
            .map(|backend| (backend.name.as_str(), backend.weight))
            .collect::<Vec<_>>();
        serde_json::to_string(&serde_json::json!({
            "route": target,
            "configSha256": format!("{digest:x}"),
            "listenAddress": route.listen_address,
            "listenPort": route.listen_port,
            "backendWeights": weights,
            "routedHttpStatus": routed_status.as_u16(),
        }))
        .map_err(|_| "FAILED: could not encode Nginx route result".to_owned())
    }

    /// Remove a previously managed route file, restoring it if Nginx rejects the
    /// resulting configuration. Removing an already-absent route is idempotent.
    pub async fn remove_route(&self, target: &str) -> std::result::Result<String, String> {
        if !Self::validate_target(target) {
            return Err("DENIED: managed Nginx route name is invalid".to_owned());
        }
        let route_path = self.include_dir.join(format!("{target}.conf"));
        let Some(previous) = read_owned_route(&route_path, target)? else {
            return serde_json::to_string(&serde_json::json!({
                "route": target,
                "removed": false,
            }))
            .map_err(|_| "FAILED: could not encode Nginx route result".to_owned());
        };
        restore_route(&route_path, None)?;
        if let Err(error) = self.nginx(&["-t", "-q"]).await {
            restore_route(&route_path, Some(&previous))?;
            return Err(error);
        }
        if let Err(error) = self.nginx(&["-s", "reload"]).await {
            restore_route(&route_path, Some(&previous))?;
            if self.nginx(&["-t", "-q"]).await.is_err()
                || self.nginx(&["-s", "reload"]).await.is_err()
            {
                return Err(
                    "UNCERTAIN: Nginx reload failed and the prior route could not be restored"
                        .to_owned(),
                );
            }
            return Err(error);
        }
        serde_json::to_string(&serde_json::json!({ "route": target, "removed": true }))
            .map_err(|_| "FAILED: could not encode Nginx route result".to_owned())
    }

    /// Resolve and vet the certificate/key pair a route asks for. Only root-owned,
    /// non-group/world-writable regular files inside the managed TLS directory pass.
    fn require_tls_material(&self, tls: &RouteTls) -> std::result::Result<PathBuf, String> {
        let Some(directory) = self.tls_dir.as_ref() else {
            return Err("DENIED: managed TLS material is not configured".to_owned());
        };
        for (name, extension) in [(&tls.certificate, "crt"), (&tls.private_key, "key")] {
            let path = directory.join(format!("{name}.{extension}"));
            let metadata = fs::symlink_metadata(&path).map_err(|_| {
                format!("DENIED: managed TLS {extension} file is missing for {name}")
            })?;
            if !metadata.file_type().is_file() || metadata.len() == 0 {
                return Err(format!(
                    "DENIED: managed TLS {extension} file for {name} is not a regular file"
                ));
            }
            if metadata.uid() != 0 || (metadata.mode() & 0o022) != 0 {
                return Err(format!(
                    "DENIED: managed TLS {extension} file for {name} must be root-owned and not group/world writable"
                ));
            }
        }
        Ok(directory.clone())
    }

    /// Write a certificate/key pair into the managed TLS directory. Core never stores
    /// private keys; they live only on the host, in a root-owned directory.
    pub async fn install_tls(
        &self,
        target: &str,
        payload_json: Option<&str>,
    ) -> std::result::Result<String, String> {
        if !valid_tls_name(target) {
            return Err("DENIED: managed TLS name is invalid".to_owned());
        }
        let Some(directory) = self.tls_dir.as_ref() else {
            return Err("DENIED: managed TLS material is not configured".to_owned());
        };
        let Some(payload_json) = payload_json else {
            return Err("DENIED: TLS payload is required".to_owned());
        };
        if payload_json.len() > MAX_TLS_PAYLOAD_BYTES {
            return Err("DENIED: TLS payload is too large".to_owned());
        }
        let payload: TlsPayload = serde_json::from_str(payload_json)
            .map_err(|_| "DENIED: TLS payload schema is invalid".to_owned())?;
        if !valid_pem(&payload.certificate_pem, "CERTIFICATE") {
            return Err("DENIED: certificate PEM is invalid".to_owned());
        }
        if !valid_pem(&payload.private_key_pem, "PRIVATE KEY") {
            return Err("DENIED: private key PEM is invalid".to_owned());
        }
        atomic_write(
            &directory.join(format!("{target}.crt")),
            payload.certificate_pem.as_bytes(),
        )?;
        atomic_write(
            &directory.join(format!("{target}.key")),
            payload.private_key_pem.as_bytes(),
        )?;
        let digest = Sha256::digest(payload.certificate_pem.as_bytes());
        serde_json::to_string(&serde_json::json!({
            "tls": target,
            "certificateSha256": format!("{digest:x}"),
        }))
        .map_err(|_| "FAILED: could not encode TLS result".to_owned())
    }

    async fn nginx(&self, arguments: &[&str]) -> std::result::Result<String, String> {
        let mut command = Command::new(&self.binary);
        command
            .args(arguments)
            .kill_on_drop(true)
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped());
        let output = timeout(Duration::from_secs(10), command.output())
            .await
            .map_err(|_| "FAILED: Nginx command timed out".to_owned())?
            .map_err(|_| "FAILED: could not start trusted Nginx executable".to_owned())?;
        if output.stdout.len() + output.stderr.len() > MAX_NGINX_OUTPUT_BYTES {
            return Err("FAILED: Nginx output exceeds the size limit".to_owned());
        }
        if !output.status.success() {
            return Err("FAILED: Nginx rejected or could not apply its configuration".to_owned());
        }
        let mut combined = output.stdout;
        combined.extend_from_slice(&output.stderr);
        String::from_utf8(combined)
            .map_err(|_| "FAILED: Nginx output was not valid UTF-8".to_owned())
    }
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct RoutePayload {
    listen_address: String,
    listen_port: u16,
    server_name: String,
    health_path: String,
    backends: Vec<RouteBackend>,
    #[serde(default)]
    tls: Option<RouteTls>,
}

/// TLS material is referenced by *name* only: the Helper resolves
/// `<tls_dir>/<name>.crt` and `<tls_dir>/<name>.key`, so a payload can never
/// point Nginx at an arbitrary path.
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct RouteTls {
    certificate: String,
    private_key: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct TlsPayload {
    certificate_pem: String,
    private_key_pem: String,
}

fn valid_pem(pem: &str, label: &str) -> bool {
    let trimmed = pem.trim();
    if trimmed.len() > MAX_TLS_MATERIAL_BYTES {
        return false;
    }
    let begin = format!("-----BEGIN {label}-----");
    let end = format!("-----END {label}-----");
    trimmed.starts_with(&begin)
        && trimmed.ends_with(&end)
        && trimmed
            .bytes()
            .all(|byte| byte.is_ascii_graphic() || byte == b'\n' || byte == b'\r' || byte == b' ')
}

fn valid_tls_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 32
        && name.as_bytes()[0].is_ascii_lowercase()
        && name
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct RouteBackend {
    name: String,
    port: u16,
    weight: u8,
}

fn validate_route(route: &RoutePayload) -> std::result::Result<(), String> {
    let host_valid = route.server_name.len() <= 253
        && route.server_name.split('.').all(|label| {
            !label.is_empty()
                && label.len() <= 63
                && label.as_bytes()[0].is_ascii_alphanumeric()
                && label.as_bytes()[label.len() - 1].is_ascii_alphanumeric()
                && label
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
        });
    if !host_valid
        || route.listen_port == 0
        || route.backends.is_empty()
        || route.backends.len() > 2
    {
        return Err("DENIED: Nginx route host, listener, or backend count is invalid".to_owned());
    }
    if !route.health_path.starts_with('/')
        || route.health_path.len() > 256
        || route.health_path.contains("..")
        || !route
            .health_path
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"/._~-".contains(&byte))
    {
        return Err("DENIED: Nginx route health path is invalid".to_owned());
    }
    if let Some(tls) = &route.tls
        && (!valid_tls_name(&tls.certificate) || !valid_tls_name(&tls.private_key))
    {
        return Err("DENIED: Nginx TLS certificate or key name is invalid".to_owned());
    }
    let mut names = std::collections::HashSet::new();
    let mut ports = std::collections::HashSet::new();
    let mut weight_sum = 0_u16;
    for backend in &route.backends {
        if backend.name.is_empty()
            || backend.name.len() > 32
            || !backend
                .name
                .bytes()
                .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
            || backend.port == 0
            || backend.weight > 100
            || !names.insert(&backend.name)
            || !ports.insert(backend.port)
        {
            return Err("DENIED: Nginx backend name, port, or weight is invalid".to_owned());
        }
        weight_sum += u16::from(backend.weight);
    }
    if weight_sum != 100 || !route.backends.iter().any(|backend| backend.weight > 0) {
        return Err("DENIED: Nginx backend weights must sum to 100".to_owned());
    }
    Ok(())
}

fn render_route(target: &str, route: &RoutePayload, tls_dir: Option<&Path>) -> String {
    let upstream = target.replace('-', "_");
    let address = route
        .listen_address
        .parse::<IpAddr>()
        .map(|address| match address {
            IpAddr::V4(address) => address.to_string(),
            IpAddr::V6(address) => format!("[{address}]"),
        })
        .unwrap_or_default();
    let mut output = format!("# Managed by NaNPanel; route {target}\nupstream {upstream} {{\n");
    for backend in route.backends.iter().filter(|backend| backend.weight > 0) {
        output.push_str(&format!(
            "    server 127.0.0.1:{} weight={};\n",
            backend.port, backend.weight
        ));
    }
    let listen = if route.tls.is_some() {
        format!("listen {address}:{} ssl;", route.listen_port)
    } else {
        format!("listen {address}:{};", route.listen_port)
    };
    let tls_block = match (route.tls.as_ref(), tls_dir) {
        (Some(tls), Some(directory)) => format!(
            "    ssl_certificate {};\n    ssl_certificate_key {};\n    ssl_protocols TLSv1.2 TLSv1.3;\n",
            directory.join(format!("{}.crt", tls.certificate)).display(),
            directory.join(format!("{}.key", tls.private_key)).display(),
        ),
        _ => String::new(),
    };
    output.push_str(&format!(
        "}}\nserver {{\n    {listen}\n    server_name {server_name};\n{tls_block}    location / {{\n        proxy_set_header Host $host;\n        proxy_set_header X-Real-IP $remote_addr;\n        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n        proxy_set_header X-Forwarded-Proto $scheme;\n        proxy_http_version 1.1;\n        proxy_pass http://{upstream};\n    }}\n}}\n",
        listen = listen,
        server_name = route.server_name,
        tls_block = tls_block,
    ));
    output
}

async fn probe(
    address: IpAddr,
    port: u16,
    server_name: &str,
    health_path: &str,
    secure: bool,
) -> std::result::Result<reqwest::StatusCode, ()> {
    let client = reqwest::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(3))
        .danger_accept_invalid_certs(secure)
        .build()
        .map_err(|_| ())?;
    let host = match address {
        IpAddr::V4(address) => address.to_string(),
        IpAddr::V6(address) => format!("[{address}]"),
    };
    let scheme = if secure { "https" } else { "http" };
    let url = format!("{scheme}://{host}:{port}{health_path}");
    let response = client
        .get(url)
        .header(reqwest::header::HOST, server_name)
        .send()
        .await
        .map_err(|_| ())?;
    if response.status().is_success() {
        Ok(response.status())
    } else {
        Err(())
    }
}

async fn probe_after_reload(
    address: IpAddr,
    port: u16,
    server_name: &str,
    health_path: &str,
    secure: bool,
) -> std::result::Result<reqwest::StatusCode, ()> {
    let deadline = Instant::now() + Duration::from_secs(3);
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(());
        }
        let attempt_timeout = remaining.min(Duration::from_millis(250));
        if let Ok(Ok(status)) = timeout(
            attempt_timeout,
            probe(address, port, server_name, health_path, secure),
        )
        .await
        {
            return Ok(status);
        }
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(());
        }
        tokio::time::sleep(Duration::from_millis(50).min(remaining)).await;
    }
}

fn verify_include_and_listener(
    config: &str,
    include_dir: &Path,
    route_path: &Path,
    requested_port: u16,
) -> std::result::Result<(), String> {
    let expected_include = format!("{}/*.conf", include_dir.display());
    let expected_route = route_path.to_string_lossy();
    let mut current_file = String::new();
    let mut found_include = false;
    for line in config.lines() {
        let line = line.trim();
        if let Some(path) = line
            .strip_prefix("# configuration file ")
            .and_then(|value| value.strip_suffix(':'))
        {
            current_file = path.to_owned();
            continue;
        }
        if line.starts_with("include ") {
            let include = line
                .trim_start_matches("include ")
                .trim_end_matches(';')
                .trim_matches('"');
            if include == expected_include {
                found_include = true;
            }
        }
        if current_file == expected_route || line.starts_with('#') {
            continue;
        }
        if nginx_listen_port(line) == Some(requested_port) {
            return Err(
                "DENIED: Nginx listener port is already used by another configuration".to_owned(),
            );
        }
    }
    if found_include {
        Ok(())
    } else {
        Err("DENIED: Nginx config must include the configured directory with *.conf".to_owned())
    }
}

fn nginx_listen_port(line: &str) -> Option<u16> {
    let config = line.split('#').next()?;
    let tokens = config
        .split(|character: char| character.is_whitespace() || matches!(character, '{' | '}'))
        .filter(|token| !token.is_empty())
        .collect::<Vec<_>>();
    tokens.windows(2).find_map(|pair| {
        if pair[0] != "listen" {
            return None;
        }
        let address = pair[1].trim_end_matches(';');
        let candidate = address.rsplit_once(':').map_or(address, |(_, port)| port);
        candidate.parse().ok()
    })
}

fn read_owned_route(path: &Path, target: &str) -> std::result::Result<Option<Vec<u8>>, String> {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(_) => return Err("FAILED: could not inspect existing Nginx route".to_owned()),
    };
    if !metadata.is_file() || metadata.uid() != 0 || metadata.mode() & 0o022 != 0 {
        return Err("DENIED: existing Nginx route is not a safe regular file".to_owned());
    }
    if metadata.len() as usize > MAX_ROUTE_BYTES {
        return Err("DENIED: existing Nginx route exceeds the size limit".to_owned());
    }
    let contents =
        fs::read(path).map_err(|_| "FAILED: could not read existing Nginx route".to_owned())?;
    let marker = format!("# Managed by NaNPanel; route {target}\n");
    if !contents.starts_with(marker.as_bytes()) {
        return Err("DENIED: refusing to replace an Nginx file not owned by this route".to_owned());
    }
    Ok(Some(contents))
}

fn atomic_write(path: &Path, contents: &[u8]) -> std::result::Result<(), String> {
    let parent = path
        .parent()
        .ok_or_else(|| "FAILED: Nginx route has no parent directory".to_owned())?;
    let temp_path = parent.join(format!(".nanpanel-{}.tmp", Uuid::new_v4().simple()));
    let mut options = OpenOptions::new();
    options.write(true).create_new(true).mode(0o600);
    let mut file = options
        .open(&temp_path)
        .map_err(|_| "FAILED: could not create atomic Nginx route file".to_owned())?;
    if file
        .write_all(contents)
        .and_then(|()| file.sync_all())
        .is_err()
    {
        let _ = fs::remove_file(&temp_path);
        return Err("FAILED: could not persist Nginx route file".to_owned());
    }
    if fs::set_permissions(&temp_path, fs::Permissions::from_mode(0o600)).is_err() {
        let _ = fs::remove_file(&temp_path);
        return Err("FAILED: could not secure Nginx route file".to_owned());
    }
    fs::rename(&temp_path, path).map_err(|_| {
        let _ = fs::remove_file(&temp_path);
        "FAILED: could not atomically replace Nginx route file".to_owned()
    })?;
    fs::File::open(parent)
        .and_then(|directory| directory.sync_all())
        .map_err(|_| "FAILED: could not persist Nginx route directory update".to_owned())
}

fn restore_route(path: &Path, contents: Option<&[u8]>) -> std::result::Result<(), String> {
    match contents {
        Some(contents) => atomic_write(path, contents),
        None => fs::remove_file(path)
            .map_err(|_| "UNCERTAIN: could not remove an uncommitted Nginx route".to_owned()),
    }
}

fn trusted_binary(path: PathBuf) -> anyhow::Result<PathBuf> {
    if !path.is_absolute() {
        bail!("NANPANEL_NGINX_BINARY must be an absolute path");
    }
    let canonical = fs::canonicalize(&path)
        .with_context(|| format!("resolve configured Nginx binary {}", path.display()))?;
    let metadata = fs::metadata(&canonical).context("inspect configured Nginx binary")?;
    if !metadata.is_file()
        || metadata.uid() != 0
        || metadata.mode() & 0o022 != 0
        || !root_owned_path(canonical.parent().unwrap_or(Path::new("/")))
    {
        bail!("configured Nginx binary must be a root-owned, non-writable file");
    }
    Ok(canonical)
}

fn root_owned_path(path: &Path) -> bool {
    path.ancestors().all(|ancestor| {
        fs::metadata(ancestor).is_ok_and(|metadata| {
            metadata.is_dir() && metadata.uid() == 0 && metadata.mode() & 0o022 == 0
        })
    })
}

fn trusted_binary_candidates(candidates: &[&str]) -> anyhow::Result<PathBuf> {
    for candidate in candidates {
        let path = PathBuf::from(candidate);
        if path.is_file() {
            return trusted_binary(path);
        }
    }
    bail!("no trusted nginx executable was found")
}

#[cfg(test)]
mod tests {
    use super::{
        NginxRouteConfig, RoutePayload, nginx_listen_port, probe_after_reload, render_route,
        validate_route, verify_include_and_listener,
    };
    use std::{
        net::{IpAddr, Ipv4Addr},
        path::Path,
        time::Duration,
    };
    use tokio::{
        io::{AsyncReadExt, AsyncWriteExt},
        net::TcpListener,
    };

    #[tokio::test]
    async fn retries_the_routed_health_check_while_nginx_rebinds_a_listener() -> anyhow::Result<()>
    {
        let reservation = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await?;
        let port = reservation.local_addr()?.port();
        drop(reservation);

        let server = tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(150)).await;
            let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, port)).await?;
            let (mut stream, _) = listener.accept().await?;
            let mut request = [0; 1024];
            let _ = stream.read(&mut request).await?;
            stream
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
                .await?;
            Ok::<(), anyhow::Error>(())
        });

        let status = probe_after_reload(
            IpAddr::V4(Ipv4Addr::LOCALHOST),
            port,
            "host.example.test",
            "/health",
            false,
        )
        .await
        .map_err(|()| anyhow::anyhow!("listener did not answer the routed health check"))?;
        assert_eq!(status, reqwest::StatusCode::OK);
        server.await??;
        Ok(())
    }

    fn route() -> RoutePayload {
        RoutePayload {
            listen_address: "127.0.0.1".to_owned(),
            listen_port: 18080,
            server_name: "app.example.test".to_owned(),
            health_path: "/health".to_owned(),
            backends: vec![
                super::RouteBackend {
                    name: "stable".to_owned(),
                    port: 20080,
                    weight: 90,
                },
                super::RouteBackend {
                    name: "canary".to_owned(),
                    port: 20081,
                    weight: 10,
                },
            ],
            tls: None,
        }
    }

    #[test]
    fn accepts_only_nanpanel_owned_route_names() {
        assert!(NginxRouteConfig::validate_target(
            "nanpanel-route0123456789abcdef0123456789abcdef"
        ));
        assert!(!NginxRouteConfig::validate_target("customer-site"));
        assert!(!NginxRouteConfig::validate_target("nanpanel-../nginx"));
        assert!(!NginxRouteConfig::validate_target("nanpanel-"));
    }

    #[test]
    fn renders_weighted_backends_and_a_managed_http_route() {
        let rendered = render_route(
            "nanpanel-route0123456789abcdef0123456789abcdef",
            &route(),
            None,
        );
        assert!(rendered.contains("server 127.0.0.1:20080 weight=90;"));
        assert!(rendered.contains("server 127.0.0.1:20081 weight=10;"));
        assert!(rendered.contains("listen 127.0.0.1:18080;"));
        assert!(rendered.contains("server_name app.example.test;"));
    }

    #[test]
    fn omits_zero_weight_backends_instead_of_emitting_invalid_nginx_weight() {
        let mut route = route();
        route.backends[0].weight = 100;
        route.backends[1].weight = 0;
        let rendered = render_route(
            "nanpanel-route0123456789abcdef0123456789abcdef",
            &route,
            None,
        );
        assert!(rendered.contains("server 127.0.0.1:20080 weight=100;"));
        assert!(!rendered.contains("20081"));
    }

    #[test]
    fn rejects_invalid_weights_hosts_and_health_paths() {
        let mut invalid = route();
        invalid.backends[0].weight = 80;
        assert!(validate_route(&invalid).is_err());
        let mut invalid = route();
        invalid.server_name = "app.example.test; include /tmp/bad".to_owned();
        assert!(validate_route(&invalid).is_err());
        let mut invalid = route();
        invalid.health_path = "/../../etc/passwd".to_owned();
        assert!(validate_route(&invalid).is_err());
    }

    #[test]
    fn listener_conflicts_are_rejected_but_managed_file_is_excluded() {
        let config = "# configuration file /etc/nginx/nginx.conf:\ninclude /etc/nginx/conf.d/*.conf;\n# configuration file /etc/nginx/conf.d/customer.conf:\nserver { listen 127.0.0.1:18080; }\n";
        let result = verify_include_and_listener(
            config,
            Path::new("/etc/nginx/conf.d"),
            Path::new("/etc/nginx/conf.d/nanpanel-route.conf"),
            18080,
        );
        assert!(result.is_err());

        let config = "# configuration file /etc/nginx/nginx.conf:\ninclude /etc/nginx/conf.d/*.conf;\n# configuration file /etc/nginx/conf.d/nanpanel-route.conf:\nserver { listen 127.0.0.1:18080; }\n";
        assert!(
            verify_include_and_listener(
                config,
                Path::new("/etc/nginx/conf.d"),
                Path::new("/etc/nginx/conf.d/nanpanel-route.conf"),
                18080,
            )
            .is_ok()
        );
    }

    #[test]
    fn extracts_ports_from_supported_nginx_listen_forms() {
        assert_eq!(nginx_listen_port("listen 8080;"), Some(8080));
        assert_eq!(nginx_listen_port("listen 127.0.0.1:8080 ssl;"), Some(8080));
        assert_eq!(nginx_listen_port("listen [::1]:8080;"), Some(8080));
        assert_eq!(nginx_listen_port("listen unix:/run/nginx.sock;"), None);
    }

    #[test]
    fn accepts_only_bounded_tls_names() {
        assert!(NginxRouteConfig::validate_tls_target("shop-example"));
        assert!(NginxRouteConfig::validate_tls_target("a1"));
        assert!(!NginxRouteConfig::validate_tls_target(""));
        assert!(!NginxRouteConfig::validate_tls_target("Shop"));
        assert!(!NginxRouteConfig::validate_tls_target("1shop"));
        assert!(!NginxRouteConfig::validate_tls_target("../etc/passwd"));
        assert!(!NginxRouteConfig::validate_tls_target(
            "a".repeat(33).as_str()
        ));
    }

    #[test]
    fn tls_route_requires_valid_certificate_and_key_names() {
        let mut route = route();
        route.tls = Some(super::RouteTls {
            certificate: "shop-example".to_owned(),
            private_key: "shop-example".to_owned(),
        });
        assert!(validate_route(&route).is_ok());

        route.tls = Some(super::RouteTls {
            certificate: "../shop".to_owned(),
            private_key: "shop-example".to_owned(),
        });
        assert!(validate_route(&route).is_err());

        route.tls = Some(super::RouteTls {
            certificate: "shop-example".to_owned(),
            private_key: "".to_owned(),
        });
        assert!(validate_route(&route).is_err());
    }

    #[test]
    fn renders_a_tls_listener_with_managed_certificate_paths() {
        let mut route = route();
        route.listen_port = 443;
        route.tls = Some(super::RouteTls {
            certificate: "shop-example".to_owned(),
            private_key: "shop-example".to_owned(),
        });
        let directory = Path::new("/etc/nanpanel/tls");
        let rendered = render_route(
            "nanpanel-route0123456789abcdef0123456789abcdef",
            &route,
            Some(directory),
        );
        assert!(rendered.contains("listen 127.0.0.1:443 ssl;"));
        assert!(rendered.contains("ssl_certificate /etc/nanpanel/tls/shop-example.crt;"));
        assert!(rendered.contains("ssl_certificate_key /etc/nanpanel/tls/shop-example.key;"));
        assert!(rendered.contains("ssl_protocols TLSv1.2 TLSv1.3;"));
        assert!(!rendered.contains("listen 127.0.0.1:443;"));
    }

    #[test]
    fn plain_routes_get_no_tls_directives() {
        let rendered = render_route(
            "nanpanel-route0123456789abcdef0123456789abcdef",
            &route(),
            Some(Path::new("/etc/nanpanel/tls")),
        );
        assert!(!rendered.contains("ssl_certificate"));
        assert!(rendered.contains("listen 127.0.0.1:18080;"));
    }

    #[test]
    fn pem_markers_are_required_for_certificates_and_keys() {
        assert!(super::valid_pem(
            "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----",
            "CERTIFICATE"
        ));
        assert!(!super::valid_pem(
            "-----BEGIN PRIVATE KEY-----\nMIIB\n-----END PRIVATE KEY-----",
            "CERTIFICATE"
        ));
        assert!(!super::valid_pem("not a pem", "CERTIFICATE"));
        assert!(!super::valid_pem("", "PRIVATE KEY"));
        assert!(super::valid_pem(
            "-----BEGIN PRIVATE KEY-----\nMIIB\n-----END PRIVATE KEY-----",
            "PRIVATE KEY"
        ));
    }
}
