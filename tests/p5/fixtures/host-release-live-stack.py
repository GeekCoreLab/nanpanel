#!/usr/bin/env python3
"""Run an isolated real Core -> Agent -> Helper P5 host release acceptance."""

from __future__ import annotations

import argparse
import base64
import hashlib
import http.cookiejar
import http.server
import json
import os
import pathlib
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


ROOT = pathlib.Path(__file__).resolve().parents[3]
AGENT = ROOT / "agent/target/release/nanpanel-agent"
HELPER = ROOT / "agent/target/release/nanpanel-helper"
CORE_RUNNER = ROOT / "backend/panel-core/target/quarkus-app/quarkus-run.jar"
PODMAN_SOCKET = pathlib.Path(os.environ.get(
    "XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}"
)) / "podman/podman.sock"
APP_IMAGES = (
    "localhost/nanpanel-p5-app:v1",
    "localhost/nanpanel-p5-app:v2",
    "localhost/nanpanel-p5-app:v3-broken",
    "docker.io/library/ubuntu:26.04",
    "docker.io/library/postgres:17-alpine",
)
HELPER_IMAGE_CACHE_SPEC = b"ubuntu26.04-runtime-specific-helper-images-docker-rootless-ubuntu-user-v3"


def helper_image_cache_key(runtime):
    digest = hashlib.sha256()
    digest.update(AGENT.read_bytes())
    digest.update(HELPER.read_bytes())
    digest.update(HELPER_IMAGE_CACHE_SPEC)
    digest.update(runtime.encode())
    digest.update(pathlib.Path("/etc/ssl/certs/ca-certificates.crt").read_bytes())
    return digest.hexdigest()[:16]


def clean_runtime_environment():
    environment = os.environ.copy()
    for key in tuple(environment):
        if key.startswith(("NANPANEL_", "QUARKUS_", "DB_", "POSTGRES_", "VITE_")) or key in {
            "CONTAINER_HOST", "DOCKER_HOST", "CONTAINERS_STORAGE_CONF",
        }:
            environment.pop(key, None)
    return environment


class AcceptanceError(RuntimeError):
    pass


def command(args, *, env=None, cwd=ROOT, timeout=300, capture=True):
    result = subprocess.run(
        [str(item) for item in args], cwd=cwd, env=env, text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None, timeout=timeout,
    )
    if result.returncode:
        output = "\n".join(part for part in (result.stdout, result.stderr) if part)
        raise AcceptanceError(
            f"command failed ({result.returncode}): {' '.join(map(str, args))}\n{output[-6000:]}"
        )
    return result.stdout.strip() if capture else ""


def free_port(bind_address="127.0.0.1"):
    with socket.socket() as listener:
        listener.bind((bind_address, 0))
        return listener.getsockname()[1]


def wait_port(port, process=None, label="service", timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            raise AcceptanceError(f"{label} exited with status {process.returncode}")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                return
        except OSError:
            time.sleep(0.25)
    raise AcceptanceError(f"timed out waiting for {label} on 127.0.0.1:{port}")


def wait_http(url, process=None, label="HTTP service", timeout=120):
    deadline = time.monotonic() + timeout
    last_error = "no response"
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            raise AcceptanceError(f"{label} exited with status {process.returncode}")
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status < 500:
                    return
                last_error = f"HTTP {response.status}"
        except Exception as error:  # local service startup can race its socket bind
            last_error = str(error)
            time.sleep(0.5)
    raise AcceptanceError(f"timed out waiting for {label}: {last_error}")


def routed_http(address, port, server_name, timeout=5):
    request = urllib.request.Request(
        f"http://{address}:{port}/", headers={"Host": server_name}
    )
    try:
        direct = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with direct.open(request, timeout=timeout) as response:
            return response.status, response.read().decode(errors="replace").strip()
    except urllib.error.HTTPError as error:
        detail = error.read().decode(errors="replace").strip()
        raise AcceptanceError(
            f"routed HTTP {address}:{port} host={server_name} returned "
            f"{error.code}: {detail[:500]}"
        ) from error


def tail(path, count=100):
    try:
        return "\n".join(pathlib.Path(path).read_text(errors="replace").splitlines()[-count:])
    except OSError:
        return ""


class CoreCallbackProxy:
    """Forward Agent callbacks and drop one selected result to test durable replay."""

    def __init__(self, core_origin):
        self.core_origin = core_origin.rstrip("/")
        self._lock = threading.Lock()
        self._drop_agent_id = None
        self._dropped_report = None
        self._replayed_report = None
        self._drop_event = threading.Event()
        self._replay_event = threading.Event()
        proxy = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, format_string, *args):
                return

            def _respond(self, status, body=b"", headers=None):
                self.send_response(status)
                for name, value in (headers or {}).items():
                    self.send_header(name, value)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if body:
                    self.wfile.write(body)

            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                body = self.rfile.read(length)
                report = None
                is_result = self.path.endswith("/internal/agent-control-results")
                if is_result:
                    try:
                        report = json.loads(body)
                    except (json.JSONDecodeError, UnicodeDecodeError):
                        self._respond(400, b"invalid callback JSON")
                        return
                    with proxy._lock:
                        if (proxy._drop_agent_id == report.get("agentId")
                                and proxy._dropped_report is None):
                            proxy._drop_agent_id = None
                            proxy._dropped_report = report
                            proxy._drop_event.set()
                            drop = True
                        else:
                            drop = False
                else:
                    drop = False
                if drop:
                    self._respond(503, b"P5 acceptance intentionally dropped this result")
                    return

                request = urllib.request.Request(
                    proxy.core_origin + self.path,
                    data=body,
                    headers={
                        "Content-Type": self.headers.get("Content-Type", "application/json"),
                        "X-NanPanel-Gateway-Token": self.headers.get(
                            "X-NanPanel-Gateway-Token", ""
                        ),
                    },
                    method="POST",
                )
                opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                try:
                    with opener.open(request, timeout=10) as response:
                        response_body = response.read()
                        status = response.status
                        headers = {
                            name: response.headers[name]
                            for name in ("Content-Type", "X-NanPanel-Result-Ack", "Retry-After")
                            if response.headers.get(name) is not None
                        }
                except urllib.error.HTTPError as error:
                    response_body = error.read()
                    status = error.code
                    headers = {
                        name: error.headers[name]
                        for name in ("Content-Type", "X-NanPanel-Result-Ack", "Retry-After")
                        if error.headers.get(name) is not None
                    }
                except (urllib.error.URLError, OSError) as error:
                    response_body = f"Core callback temporarily unavailable: {error}".encode()
                    status = 503
                    headers = {"Content-Type": "text/plain; charset=utf-8"}
                self._respond(status, response_body, headers)
                if is_result and report is not None:
                    with proxy._lock:
                        dropped = proxy._dropped_report
                        if (dropped is not None
                                and report.get("operationId") == dropped.get("operationId")):
                            proxy._replayed_report = report
                            proxy._replay_event.set()

        self._server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self._server.daemon_threads = True
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()
        self.port = self._server.server_address[1]

    @property
    def callback_url(self):
        return f"http://127.0.0.1:{self.port}/internal/agent-heartbeats"

    def drop_next_result_for(self, agent_id):
        with self._lock:
            self._drop_agent_id = agent_id

    def wait_for_dropped_result(self, timeout):
        if not self._drop_event.wait(timeout):
            raise AcceptanceError("timed out waiting for the selected Agent result to be dropped")
        with self._lock:
            return dict(self._dropped_report)

    def wait_for_replayed_result(self, operation_id, timeout):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self._replay_event.wait(min(0.25, deadline - time.monotonic())):
                with self._lock:
                    report = self._replayed_report
                if report is not None and report.get("operationId") == operation_id:
                    return dict(report)
        raise AcceptanceError(f"timed out waiting for durable Agent replay of {operation_id}")

    def close(self):
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=5)


class Api:
    def __init__(self, origin):
        self.origin = origin.rstrip("/")
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf = ""

    def request(self, method, path, body=None, *, headers=None, cookies=True):
        request_headers = dict(headers or {})
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            request_headers.setdefault("Content-Type", "application/json")
        if method not in ("GET", "HEAD", "OPTIONS") and self.csrf:
            request_headers.setdefault("X-CSRF-Token", self.csrf)
        request = urllib.request.Request(
            self.origin + "/api/v1" + path, data=data,
            headers=request_headers, method=method,
        )
        opener = self.opener if cookies else urllib.request.build_opener()
        try:
            with opener.open(request, timeout=15) as response:
                payload = response.read()
                content_type = response.headers.get("Content-Type", "")
                value = json.loads(payload) if "json" in content_type else payload.decode()
                return response.status, value
        except urllib.error.HTTPError as error:
            detail = error.read().decode(errors="replace")
            raise AcceptanceError(
                f"Core {method} {path} returned HTTP {error.code}: {detail[:1200]}"
            ) from error

    def authenticate(self, bootstrap_password, final_password):
        _, csrf = self.request("GET", "/auth/csrf")
        self.csrf = csrf["csrfToken"]
        _, login = self.request("POST", "/auth/login", {
            "username": "admin", "password": bootstrap_password,
        })
        self.csrf = login["csrfToken"]
        if login["user"].get("mustChangePassword"):
            self.request("PUT", "/auth/password", {
                "currentPassword": bootstrap_password,
                "newPassword": final_password,
            })
            self.jar.clear()
            _, csrf = self.request("GET", "/auth/csrf")
            self.csrf = csrf["csrfToken"]
            _, login = self.request("POST", "/auth/login", {
                "username": "admin", "password": final_password,
            })
            self.csrf = login["csrfToken"]
        if login["user"].get("mustChangePassword"):
            raise AcceptanceError("Core admin still requires a password change")

    def enroll(self, hostname, work):
        work.mkdir(mode=0o700)
        _, enrollment = self.request("POST", "/agent-enrollments", {"hostname": hostname})
        key_path = work / "agent.key"
        csr_path = work / "agent.csr"
        command([
            "openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes",
            "-keyout", key_path, "-out", csr_path,
            "-subj", f"/CN={enrollment['agentId']}",
        ], timeout=30)
        csr = csr_path.read_text()
        _, certificate = self.request(
            "POST", "/agent-enrollments/redeem", {"csrPem": csr},
            headers={"Authorization": f"Bearer {enrollment['token']}"},
            cookies=False,
        )
        if certificate["agentId"] != enrollment["agentId"]:
            raise AcceptanceError("Core returned a certificate for another Agent")
        cert_dir = work / "certificates"
        cert_dir.mkdir(mode=0o700)
        shutil.copyfile(key_path, cert_dir / "agent.key")
        (cert_dir / "agent.crt").write_text(certificate["certificatePem"])
        shutil.copyfile(work.parent / "ca.crt", cert_dir / "ca.crt")
        for path in cert_dir.iterdir():
            path.chmod(0o600 if path.name.endswith(".key") else 0o644)
        return {
            "hostId": enrollment["hostId"],
            "agentId": enrollment["agentId"],
            "hostname": hostname,
            "certDir": cert_dir,
        }


def make_ca(directory):
    ca_key = directory / "ca.key"
    ca_cert = directory / "ca.crt"
    gateway_key = directory / "gateway.key"
    gateway_csr = directory / "gateway.csr"
    gateway_cert = directory / "gateway.crt"
    ext = directory / "gateway-extensions.cnf"
    ext.write_text(
        "basicConstraints=critical,CA:FALSE\n"
        "keyUsage=critical,digitalSignature,keyEncipherment\n"
        "extendedKeyUsage=serverAuth\n"
        "subjectAltName=DNS:localhost,IP:127.0.0.1\n"
    )
    command([
        "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
        "-keyout", ca_key, "-out", ca_cert, "-days", "2", "-sha256",
        "-subj", "/CN=NaNPanel P5 isolated Agent CA",
        "-addext", "basicConstraints=critical,CA:TRUE",
        "-addext", "keyUsage=critical,keyCertSign,cRLSign",
    ], timeout=30)
    command([
        "openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes",
        "-keyout", gateway_key, "-out", gateway_csr, "-subj", "/CN=localhost",
    ], timeout=30)
    command([
        "openssl", "x509", "-req", "-in", gateway_csr,
        "-CA", ca_cert, "-CAkey", ca_key, "-CAcreateserial",
        "-out", gateway_cert, "-days", "2", "-sha256", "-extfile", ext,
    ], timeout=30)
    return ca_key, ca_cert, gateway_key, gateway_cert


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--runtime", choices=("podman", "docker", "both"), default="podman",
        help="runtime used by both Helper sandboxes, or Podman for host A and Docker for host B",
    )
    parser.add_argument(
        "--docker-host-socket", type=pathlib.Path,
        help="use an already-running Docker Engine socket for Docker Helpers instead of starting a nested daemon",
    )
    args = parser.parse_args()
    runtime_assignments = (
        ["podman", "docker"] if args.runtime == "both"
        else [args.runtime, args.runtime]
    )
    docker_host_socket = args.docker_host_socket.resolve() if args.docker_host_socket else None
    if docker_host_socket and "docker" not in runtime_assignments:
        raise AcceptanceError("--docker-host-socket requires --runtime docker or --runtime both")
    if docker_host_socket and not docker_host_socket.is_socket():
        raise AcceptanceError(f"external Docker Engine socket is unavailable: {docker_host_socket}")

    for required in (AGENT, HELPER, CORE_RUNNER):
        if not required.is_file():
            raise AcceptanceError(f"required built artifact is missing: {required}")
    if not PODMAN_SOCKET.is_socket():
        raise AcceptanceError(f"rootless Podman socket is unavailable: {PODMAN_SOCKET}")
    for image in APP_IMAGES:
        command(["podman", "image", "inspect", image])

    run_id = uuid.uuid4().hex[:8]
    root = pathlib.Path(tempfile.mkdtemp(prefix=f"nanpanel-p5-live-{run_id}-"))
    logs = root / "logs"
    logs.mkdir()
    processes = []
    remote_env = clean_runtime_environment()
    remote_env["HOME"] = str(pathlib.Path.home())
    storage_root = root / "podman-store"
    runroot = root / "podman-run"
    podman_tmp = root / "podman-tmp"
    storage_root.mkdir()
    runroot.mkdir()
    podman_tmp.mkdir()
    podman_socket = root / "podman.sock"
    podman_uri = f"unix://{podman_socket}"
    selected_ports = set()

    def unique_port(bind_address="127.0.0.1"):
        port = free_port(bind_address)
        while port in selected_ports:
            port = free_port(bind_address)
        selected_ports.add(port)
        return port

    db_port = unique_port()
    core_port = unique_port()
    gateway_port = unique_port()
    control_port = unique_port()
    web_port = unique_port()
    route_port = unique_port("127.0.0.2")
    while route_port < 1024 or route_port == 0:
        selected_ports.discard(route_port)
        route_port = unique_port("127.0.0.2")
    restart_route_port = unique_port("127.0.0.2")
    while restart_route_port < 1024 or restart_route_port == 0:
        selected_ports.discard(restart_route_port)
        restart_route_port = unique_port("127.0.0.2")
    api_origin = f"http://127.0.0.1:{core_port}"
    callback_token = secrets.token_urlsafe(36)
    bootstrap_password = secrets.token_urlsafe(24)
    admin_password = secrets.token_urlsafe(24)
    db_password = secrets.token_urlsafe(24)
    database = f"jdbc:postgresql://127.0.0.1:{db_port}/nanpanel"
    db_name = f"p5-live-db-{run_id}"
    acceptance_complete = False
    helper_names = []
    callback_proxy = None
    helper_images = {}
    helper_image_caches = {}
    plan_prefixes = set()
    core_log = logs / "core.log"
    gateway_log = logs / "gateway.log"
    web_log = logs / "web.log"
    podman_log = logs / "podman-service.log"
    output = {}

    try:
        print(f"[{run_id}] saving required fixture images into a disposable VFS store", flush=True)
        default_images = root / "images.tar"
        command([
            "podman", "save", "--multi-image-archive", "--format", "docker-archive",
            "-o", default_images, *APP_IMAGES,
        ], timeout=300)

        podman_service = subprocess.Popen(
            ["podman", f"--root={storage_root}/graph", f"--runroot={runroot}",
             f"--tmpdir={podman_tmp}", "--storage-driver=vfs", "system", "service",
             "--time=0", podman_uri],
            cwd=ROOT, env=remote_env, stdout=podman_log.open("w"), stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        processes.append(podman_service)
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            if podman_service.poll() is not None:
                raise AcceptanceError(f"isolated Podman service exited: {tail(podman_log)}")
            if podman_socket.exists():
                break
            time.sleep(0.25)
        if not podman_socket.exists():
            raise AcceptanceError("isolated Podman service did not create its socket")
        isolated_env = remote_env | {"CONTAINER_HOST": podman_uri}
        command(["podman", "info", "--format", "rootless={{.Host.Security.Rootless}} graph={{.Store.GraphRoot}}"], env=isolated_env)
        command(["podman", "load", "--input", default_images], env=isolated_env, timeout=300)
        print(f"[{run_id}] isolated Podman API is ready", flush=True)

        ca_key, ca_cert, gateway_key, gateway_cert = make_ca(root)
        postgres_password = db_password
        pg_env = isolated_env | {"POSTGRES_PASSWORD": postgres_password}
        command([
            "podman", "run", "--detach", "--name", db_name, "--network", "host",
            "--env", "POSTGRES_USER=nanpanel", "--env", "POSTGRES_DB=nanpanel",
            "--env", "POSTGRES_PASSWORD", "docker.io/library/postgres:17-alpine",
            "postgres", "-c", "listen_addresses=127.0.0.1", "-c", f"port={db_port}",
        ], env=pg_env, timeout=120)
        wait_port(db_port, label="isolated PostgreSQL", timeout=60)

        core_env = clean_runtime_environment() | {
            "QUARKUS_HTTP_HOST": "127.0.0.1",
            "QUARKUS_HTTP_PORT": str(core_port),
            "NANPANEL_ORIGIN": f"http://127.0.0.1:{web_port}",
            "NANPANEL_AUTH_SECURE_COOKIE": "false",
            "NANPANEL_ADMIN_PASSWORD": bootstrap_password,
            "DB_USER": "nanpanel",
            "DB_PASSWORD": db_password,
            "DB_URL": database,
            "NANPANEL_AGENT_GATEWAY_TOKEN": callback_token,
            "NANPANEL_SSH_CREDENTIAL_KEY": base64.b64encode(secrets.token_bytes(32)).decode(),
            "NANPANEL_KUBERNETES_CREDENTIAL_KEY": base64.b64encode(secrets.token_bytes(32)).decode(),
            "NANPANEL_AGENT_GATEWAY_PORT": str(gateway_port),
            "NANPANEL_AGENT_GATEWAY_CONTROL_URL": f"http://127.0.0.1:{control_port}/control",
            "NANPANEL_AGENT_CA_CERT": str(ca_cert),
            "NANPANEL_AGENT_CA_KEY": str(ca_key),
            "NANPANEL_AGENT_SERVING_CERT": str(gateway_cert),
            "NANPANEL_AGENT_BINARY": str(AGENT),
            "NANPANEL_AGENT_HELPER_BINARY": str(HELPER),
            "NANPANEL_KUBERNETES_DISCOVERY_ENABLED": "false",
        }
        core_stream = core_log.open("w")
        core = subprocess.Popen(
            ["java", "-jar", CORE_RUNNER], cwd=ROOT, env=core_env,
            stdout=core_stream, stderr=subprocess.STDOUT, start_new_session=True,
        )
        processes.append(core)
        wait_http(f"{api_origin}/q/health/ready", core, "Quarkus Core", timeout=150)
        print(f"[{run_id}] disposable Core and PostgreSQL are ready", flush=True)

        callback_proxy = CoreCallbackProxy(api_origin)
        gateway_env = clean_runtime_environment() | {
            "NANPANEL_GATEWAY_LISTEN": f"127.0.0.1:{gateway_port}",
            "NANPANEL_GATEWAY_CONTROL_LISTEN": f"127.0.0.1:{control_port}",
            "NANPANEL_GATEWAY_CLIENT_CA": str(ca_cert),
            "NANPANEL_GATEWAY_CERT": str(gateway_cert),
            "NANPANEL_GATEWAY_KEY": str(gateway_key),
            "NANPANEL_GATEWAY_CALLBACK_URL": callback_proxy.callback_url,
            "NANPANEL_GATEWAY_CALLBACK_TOKEN": callback_token,
        }
        gateway_stream = gateway_log.open("w")
        gateway = subprocess.Popen(
            [AGENT, "gateway"], cwd=ROOT, env=gateway_env,
            stdout=gateway_stream, stderr=subprocess.STDOUT, start_new_session=True,
        )
        processes.append(gateway)
        wait_port(gateway_port, gateway, "mTLS Agent Gateway", timeout=45)
        wait_port(control_port, gateway, "Agent Gateway control API", timeout=45)
        print(f"[{run_id}] mTLS Agent Gateway is ready", flush=True)

        api = Api(api_origin)
        api.authenticate(bootstrap_password, admin_password)
        host_a = f"p5-live-{run_id}-a"
        host_b = f"p5-live-{run_id}-b"
        enrollments = [
            api.enroll(host_a, root / "agent-a"),
            api.enroll(host_b, root / "agent-b"),
        ]

        release_images = root / "release-images.tar"
        if "docker" in runtime_assignments and docker_host_socket is None:
            command([
                "podman", "save", "--multi-image-archive", "--format", "docker-archive",
                "-o", release_images, *APP_IMAGES[:3],
            ], timeout=300)

        for runtime in dict.fromkeys(runtime_assignments):
            containerfile_dir = root / f"helper-agent-image-{runtime}"
            containerfile_dir.mkdir()
            shutil.copyfile(AGENT, containerfile_dir / "nanpanel-agent")
            shutil.copyfile(HELPER, containerfile_dir / "nanpanel-helper")
            shutil.copyfile(
                "/etc/ssl/certs/ca-certificates.crt",
                containerfile_dir / "host-ca-certificates.crt",
            )
            runtime_packages = (
                "nginx podman util-linux ca-certificates curl"
                if runtime == "podman"
                else "nginx docker.io rootlesskit slirp4netns uidmap util-linux ca-certificates curl"
            )
            containerfile = """FROM docker.io/library/ubuntu:26.04
USER 0:0
ENV DEBIAN_FRONTEND=noninteractive
COPY host-ca-certificates.crt /tmp/host-ca-certificates.crt
RUN sed -i 's|http://|https://|g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get -o Acquire::https::CaInfo=/tmp/host-ca-certificates.crt -o Acquire::Retries=2 -o Acquire::https::Timeout=30 update \
    && apt-get -o Acquire::https::CaInfo=/tmp/host-ca-certificates.crt -o Acquire::Retries=2 -o Acquire::https::Timeout=30 install -y --no-install-recommends @RUNTIME_PACKAGES@ \
    && rm -rf /var/lib/apt/lists/* /tmp/host-ca-certificates.crt
COPY nanpanel-agent /usr/local/bin/nanpanel-agent
COPY nanpanel-helper /usr/local/bin/nanpanel-helper
RUN chmod 0755 /usr/local/bin/nanpanel-agent /usr/local/bin/nanpanel-helper \\
    && install -d -o root -g root -m 0755 /run/nanpanel /etc/nanpanel/managed /etc/nginx/nanpanel-routes \\
    && install -d -o root -g root -m 0700 /etc/nanpanel/managed/files \\
    && install -d -o 1000 -g 1000 -m 0700 /var/lib/nanpanel-agent /home/ubuntu/.config/docker \\
    && printf '{"storage-driver":"vfs","data-root":"/home/ubuntu/.local/share/docker","iptables":false,"ip6tables":false}\\n' > /home/ubuntu/.config/docker/daemon.json \\
    && chown -R 1000:1000 /home/ubuntu \\
    && printf '@RUNTIME@\\n' > /etc/nanpanel/managed/container-runtime \\
    && chmod 0644 /etc/nanpanel/managed/container-runtime
RUN printf '%s\\n' 'worker_processes 1;' 'pid /run/nginx.pid;' 'events { worker_connections 256; }' 'http {' '  include /etc/nginx/mime.types;' '  include /etc/nginx/nanpanel-routes/*.conf;' '}' > /etc/nginx/nginx.conf
"""
            (containerfile_dir / "Containerfile").write_text(
                containerfile.replace("@RUNTIME_PACKAGES@", runtime_packages)
                .replace("@RUNTIME@", runtime)
            )
            cache_key = helper_image_cache_key(runtime)
            helper_image = f"localhost/nanpanel-p5-helper-agent-{runtime}:{cache_key}"
            helper_image_cache = pathlib.Path(tempfile.gettempdir()) / (
                f"nanpanel-p5-helper-agent-{runtime}-{cache_key}.tar"
            )
            helper_images[runtime] = helper_image
            helper_image_caches[runtime] = helper_image_cache
            cache_loaded = False
            if helper_image_cache.is_file():
                try:
                    command(["podman", "load", "--input", helper_image_cache],
                            env=isolated_env, timeout=300)
                    command(["podman", "image", "inspect", helper_image], env=isolated_env)
                    cache_loaded = True
                    print(f"[{run_id}] loaded cached {runtime} Helper image {cache_key}", flush=True)
                except AcceptanceError:
                    helper_image_cache.unlink(missing_ok=True)
            if not cache_loaded:
                command(["podman", "build", "--network=host", "--quiet", "--tag", helper_image,
                         "--file", containerfile_dir / "Containerfile", containerfile_dir],
                        env=isolated_env, timeout=600)
                cached_archive = root / f"helper-agent-image-{runtime}.tar"
                command(["podman", "save", "--format", "docker-archive", "--output",
                         cached_archive, helper_image], env=isolated_env, timeout=300)
                cached_archive.replace(helper_image_cache)
                print(f"[{run_id}] saved reusable {runtime} Helper image {cache_key}", flush=True)

        for ordinal, enrollment in enumerate(enrollments):
            helper_name = f"p5-live-agent-{run_id}-{ordinal}"
            cert_mount = enrollment["certDir"]
            runtime = runtime_assignments[ordinal]
            helper_image = helper_images[runtime]
            if runtime == "docker":
                runtime_setup = """
printf 'docker\\n' > /etc/nanpanel/managed/container-runtime
# Map Docker subuids to IDs visible inside the outer rootless user namespace.
sed -i '/^ubuntu:/d' /etc/subuid /etc/subgid
printf 'ubuntu:0:1000\\nubuntu:1001:64536\\n' >> /etc/subuid
printf 'ubuntu:0:1000\\nubuntu:1001:64536\\n' >> /etc/subgid
mkdir -p /run/nanpanel-docker
chown 1000:1000 /run/nanpanel-docker
chmod 0700 /run/nanpanel-docker
runuser -u ubuntu -- env HOME=/home/ubuntu XDG_RUNTIME_DIR=/run/nanpanel-docker \
  DOCKERD_ROOTLESS_ROOTLESSKIT_NET=slirp4netns /usr/share/docker.io/contrib/dockerd-rootless.sh \
  >/tmp/p5-dockerd.log 2>&1 &
docker_daemon_pid=$!
for attempt in $(seq 1 120); do
  if runuser -u ubuntu -- env HOME=/home/ubuntu XDG_RUNTIME_DIR=/run/nanpanel-docker \
    DOCKER_HOST=unix:///run/nanpanel-docker/docker.sock docker info >/dev/null 2>&1; then
    break
  fi
  kill -0 "$docker_daemon_pid" 2>/dev/null || { cat /tmp/p5-dockerd.log; exit 1; }
  sleep 0.25
done
[ -S /run/nanpanel-docker/docker.sock ] || { cat /tmp/p5-dockerd.log; exit 1; }
chmod 0666 /run/nanpanel-docker/docker.sock
runuser -u ubuntu -- env HOME=/home/ubuntu XDG_RUNTIME_DIR=/run/nanpanel-docker \
  DOCKER_HOST=unix:///run/nanpanel-docker/docker.sock docker load --input /mnt/p5-release-images.tar
"""
                if docker_host_socket is not None:
                    runtime_setup = """
mkdir -p /run/nanpanel-docker
[ -S /run/nanpanel-docker/docker.sock ] || { echo 'external Docker Engine socket is missing'; exit 1; }
DOCKER_HOST=unix:///run/nanpanel-docker/docker.sock docker info >/dev/null
for image in localhost/nanpanel-p5-app:v1 localhost/nanpanel-p5-app:v2 localhost/nanpanel-p5-app:v3-broken; do
  DOCKER_HOST=unix:///run/nanpanel-docker/docker.sock docker image inspect "$image" >/dev/null
done
"""
                runtime_launch = "DOCKER_HOST=unix:///run/nanpanel-docker/docker.sock /usr/local/bin/nanpanel-helper"
            else:
                runtime_setup = "mkdir -p /run/nanpanel-podman\n"
                runtime_launch = "/usr/local/bin/nanpanel-helper"
            start_script = f"""
set -eu
mkdir -p /run/p5-agent-certs /var/lib/nanpanel-agent
{runtime_setup}
cp /mnt/p5-certs/ca.crt /run/p5-agent-certs/ca.crt
cp /mnt/p5-certs/agent.crt /run/p5-agent-certs/agent.crt
cp /mnt/p5-certs/agent.key /run/p5-agent-certs/agent.key
chown -R 1000:1000 /run/p5-agent-certs /var/lib/nanpanel-agent
chmod 0644 /run/p5-agent-certs/ca.crt /run/p5-agent-certs/agent.crt
chmod 0600 /run/p5-agent-certs/agent.key
nginx -g 'daemon off;' >/tmp/p5-nginx.log 2>&1 &
{runtime_launch} /run/nanpanel/helper.sock 1000 1000 \\
  /etc/nanpanel/managed https://localhost /run/p5-agent-certs/ca.crt \\
  /usr/local/bin/nanpanel-agent >/tmp/p5-helper.log 2>&1 &
helper_pid=$!
printf '%s' "$helper_pid" > /run/nanpanel/helper.pid
for attempt in $(seq 1 100); do
  [ -S /run/nanpanel/helper.sock ] && break
  kill -0 "$helper_pid" 2>/dev/null || {{ cat /tmp/p5-helper.log; exit 1; }}
  sleep 0.1
done
[ -S /run/nanpanel/helper.sock ] || {{ cat /tmp/p5-helper.log; exit 1; }}
while true; do
  while [ -e /run/nanpanel/hold-agent ]; do sleep 0.2; done
  setpriv --reuid=1000 --regid=1000 --clear-groups /usr/local/bin/nanpanel-agent &
  agent_pid=$!
  printf '%s' "$agent_pid" > /run/nanpanel/agent.pid
  wait "$agent_pid" || true
  rm -f /run/nanpanel/agent.pid
  sleep 0.25
done
"""
            agent_env = clean_runtime_environment() | {
                "NANPANEL_NGINX_INCLUDE_DIR": "/etc/nginx/nanpanel-routes",
                "NANPANEL_NGINX_BINARY": "/usr/sbin/nginx",
                "NANPANEL_AGENT_ENDPOINTS": f"https://localhost:{gateway_port}",
                "NANPANEL_AGENT_TLS_DOMAIN": "localhost",
                "NANPANEL_AGENT_ID": enrollment["agentId"],
                "NANPANEL_AGENT_CA": "/run/p5-agent-certs/ca.crt",
                "NANPANEL_AGENT_CERT": "/run/p5-agent-certs/agent.crt",
                "NANPANEL_AGENT_KEY": "/run/p5-agent-certs/agent.key",
                "NANPANEL_AGENT_HELPER_SOCKET": "/run/nanpanel/helper.sock",
                "NANPANEL_AGENT_STATE_DIR": "/var/lib/nanpanel-agent",
                "NANPANEL_AGENT_HEARTBEAT_SECS": "2",
                "NANPANEL_AGENT_RECONNECT_MIN_MS": "250",
                "NANPANEL_AGENT_RECONNECT_MAX_MS": "2000",
            }
            podman_args = [
                "podman", "run", "--detach", "--name", helper_name,
                "--hostname", enrollment["hostname"], "--network", "host",
                "--volume", f"{cert_mount}:/mnt/p5-certs:ro",
            ]
            if runtime == "podman":
                # Map the Agent UID to the host user for the private Podman socket.
                podman_args.append("--userns=keep-id:uid=1000,gid=1000")
                podman_args.extend([
                    "--volume", f"{podman_socket}:/run/nanpanel-podman/podman.sock",
                ])
                agent_env["CONTAINER_HOST"] = "unix:///run/nanpanel-podman/podman.sock"
            else:
                # The default rootless mapping exposes the subuid range for dockerd.
                # This privilege remains inside that outer user namespace.
                podman_args.append("--privileged")
                if docker_host_socket is not None:
                    # Map the disposable rootless Docker socket owner to Helper UID 0.
                    podman_args.append("--userns=keep-id:uid=0,gid=0")
                    podman_args.extend([
                        "--volume", f"{docker_host_socket}:/run/nanpanel-docker/docker.sock",
                    ])
                else:
                    podman_args.extend([
                        "--volume", f"{release_images}:/mnt/p5-release-images.tar:ro",
                    ])
            podman_keys = {
                "CONTAINER_HOST", "NANPANEL_NGINX_INCLUDE_DIR", "NANPANEL_NGINX_BINARY",
                "NANPANEL_AGENT_ENDPOINTS", "NANPANEL_AGENT_TLS_DOMAIN", "NANPANEL_AGENT_ID",
                "NANPANEL_AGENT_CA", "NANPANEL_AGENT_CERT", "NANPANEL_AGENT_KEY",
                "NANPANEL_AGENT_HELPER_SOCKET", "NANPANEL_AGENT_STATE_DIR",
                "NANPANEL_AGENT_HEARTBEAT_SECS", "NANPANEL_AGENT_RECONNECT_MIN_MS",
                "NANPANEL_AGENT_RECONNECT_MAX_MS",
            }
            for key in podman_keys:
                if key in agent_env:
                    podman_args.extend(["--env", f"{key}={agent_env[key]}"])
            podman_args.extend([helper_image, "bash", "-lc", start_script])
            command(podman_args, env=isolated_env, timeout=120)
            helper_names.append(helper_name)
            helper_deadline = time.monotonic() + 35
            while time.monotonic() < helper_deadline:
                helper_state = command(
                    ["podman", "inspect", "--format={{.State.Status}}", helper_name],
                    env=isolated_env, timeout=15,
                )
                if helper_state == "running":
                    break
                if helper_state in {"exited", "stopped", "removing"}:
                    helper_logs = command(["podman", "logs", helper_name], env=isolated_env)
                    raise AcceptanceError(
                        f"{runtime} Helper sandbox exited during startup: {helper_logs[-6000:]}"
                    )
                time.sleep(0.25)
            else:
                raise AcceptanceError(f"{runtime} Helper sandbox did not remain running after startup")

        default_images.unlink(missing_ok=True)
        release_images.unlink(missing_ok=True)

        host_deadline = time.monotonic() + 120
        hosts_by_name = {}
        agents_by_host = {}
        while time.monotonic() < host_deadline:
            _, inventory = api.request("GET", "/hosts?limit=100")
            hosts_by_name = {
                host["hostname"]: host
                for host in inventory.get("items", [])
                if host.get("hostname") in (host_a, host_b)
            }
            required_capabilities = {
                "container.deploy", "container.start", "container.stop", "nginx.route.set",
            }
            if len(hosts_by_name) == 2 and all(
                host.get("status") == "ONLINE"
                and required_capabilities <= set(host.get("capabilities", []))
                for host in hosts_by_name.values()
            ):
                agents_by_host = {}
                for hostname, host in hosts_by_name.items():
                    _, agent = api.request("GET", f"/agents/{host['agentId']}")
                    agents_by_host[hostname] = agent
                if all(
                    agent.get("status") == "ONLINE"
                    and agent.get("helperStatus") == "AVAILABLE"
                    and required_capabilities <= set(agent.get("capabilities", []))
                    for agent in agents_by_host.values()
                ):
                    break
            time.sleep(1)
        else:
            raise AcceptanceError(
                "two enrolled Agents did not report required Helper capabilities: "
                + json.dumps({"hosts": hosts_by_name, "agents": agents_by_host}, sort_keys=True)
            )

        print(f"[{run_id}] both enrolled Agents report release Helper capabilities", flush=True)
        vite_env = clean_runtime_environment() | {
            "VITE_API_MODE": "live",
            "VITE_CORE_API_TARGET": api_origin,
        }
        web_stream = web_log.open("w")
        web = subprocess.Popen(
            ["npm", "run", "dev", "--", "--host", "127.0.0.1", "--port", str(web_port)],
            cwd=ROOT / "web", env=vite_env,
            stdout=web_stream, stderr=subprocess.STDOUT, start_new_session=True,
        )
        processes.append(web)
        wait_http(f"http://127.0.0.1:{web_port}/", web, "Vite console", timeout=90)

        browser_env = clean_runtime_environment() | {
            "NANPANEL_TEST_URL": f"http://127.0.0.1:{web_port}",
            "NANPANEL_BROWSER_PASSWORD": admin_password,
            "NANPANEL_P5_RUN_ID": run_id,
            "NANPANEL_P5_HOST_A": host_a,
            "NANPANEL_P5_HOST_B": host_b,
            "NANPANEL_P5_FAILURE_HOST": host_b if args.runtime == "both" else host_a,
            "NANPANEL_P5_FAILURE_ADDRESS": "127.0.0.3" if args.runtime == "both" else "127.0.0.2",
            "NANPANEL_P5_RUNTIME_ASSIGNMENTS": ",".join(runtime_assignments),
            "NANPANEL_P5_LISTEN_PORT": str(route_port),
            "NANPANEL_P5_SCREENSHOT": str(ROOT / "tests/p5/artifacts" / f"host-release-live-{run_id}.png"),
        }
        (ROOT / "tests/p5/artifacts").mkdir(parents=True, exist_ok=True)
        browser = command(
            ["npm", "run", "test:p5:host-live"], cwd=ROOT / "web",
            env=browser_env, timeout=900,
        )
        output["browserResult"] = browser
        print(f"[{run_id}] browser host release, rollback, and compensation passed", flush=True)
        stable_route_host = f"p5app{run_id}.example.test"

        _, current_hosts = api.request("GET", "/hosts?limit=100")
        current_hosts_by_name = {
            host["hostname"]: host for host in current_hosts.get("items", [])
            if host.get("hostname") in (host_a, host_b)
        }
        if len(current_hosts_by_name) != 2:
            raise AcceptanceError("host inventory was incomplete before Core restart acceptance")
        restart_plan_name = f"p5restart{run_id}"
        restart_route_host = f"{restart_plan_name}.example.test"
        restart_plan_payload = {
            "name": restart_plan_name,
            "stableImage": APP_IMAGES[0],
            "image": APP_IMAGES[1],
            "containerPort": 8080,
            "serverName": restart_route_host,
            "listenPort": restart_route_port,
            "healthPath": "/health",
            "batchSize": 1,
            "targets": [
                {
                    "hostId": current_hosts_by_name[hostname]["id"],
                    "listenAddress": address,
                    "expectedGeneration": current_hosts_by_name[hostname]["generation"],
                }
                for hostname, address in ((host_a, "127.0.0.2"), (host_b, "127.0.0.3"))
            ],
            "reason": f"P5 Core restart recovery acceptance {run_id}",
        }
        _, restart_plan = api.request(
            "POST", "/host-application-releases", restart_plan_payload
        )
        restart_plan_id = restart_plan["id"]
        print(f"[{run_id}] created isolated Core restart recovery plan {restart_plan_id}", flush=True)
        api.request("POST", f"/host-application-releases/{restart_plan_id}/start", {})
        restart_deadline = time.monotonic() + 360
        while time.monotonic() < restart_deadline:
            _, restart_plan = api.request(
                "GET", f"/host-application-releases/{restart_plan_id}"
            )
            if restart_plan["state"] == "PAUSED" and restart_plan["activeOrdinal"] == 1:
                break
            if restart_plan["state"] in {"FAILED", "UNKNOWN", "ROLLED_BACK"}:
                raise AcceptanceError(
                    "Core restart plan did not reach its first batch gate: "
                    + json.dumps(restart_plan, sort_keys=True)
                )
            time.sleep(1)
        else:
            raise AcceptanceError(
                "Core restart plan timed out before its first batch gate: "
                + json.dumps(restart_plan, sort_keys=True)
            )
        if [target["state"] for target in restart_plan["targets"]] != [
            "SUCCEEDED", "BASELINE_DEPLOY",
        ]:
            raise AcceptanceError(
                "Core restart plan did not persist the expected paused target stages: "
                + json.dumps(restart_plan["targets"], sort_keys=True)
            )
        before_restart_a = routed_http("127.0.0.2", restart_route_port, restart_route_host)
        stable_route_a = routed_http("127.0.0.2", route_port, stable_route_host)
        stable_route_b = routed_http("127.0.0.3", route_port, stable_route_host)
        if (
            before_restart_a != (200, "v2")
            or stable_route_a != (200, "v1")
            or stable_route_b != (200, "v1")
        ):
            raise AcceptanceError(
                "unexpected routed traffic at the persisted batch gate: "
                + json.dumps({
                    "restartHostA": before_restart_a,
                    "stableHostA": stable_route_a,
                    "stableHostB": stable_route_b,
                })
            )

        helper_restart_results = []
        required_helper_capabilities = {"container.deploy", "nginx.route.set"}
        for ordinal, (hostname, address) in enumerate(
            ((host_a, "127.0.0.2"), (host_b, "127.0.0.3"))
        ):
            helper_name = helper_names[ordinal]
            runtime = runtime_assignments[ordinal]
            helper_pid = command(
                ["podman", "exec", helper_name, "cat", "/run/nanpanel/helper.pid"],
                env=isolated_env,
            )
            command(
                ["podman", "exec", helper_name, "kill", "-TERM", helper_pid],
                env=isolated_env,
            )
            helper_socket = [
                "podman", "exec", helper_name, "bash", "-lc",
                "test -S /run/nanpanel/helper.sock",
            ]
            helper_stop_deadline = time.monotonic() + 10
            while time.monotonic() < helper_stop_deadline:
                stopped = subprocess.run(
                    helper_socket, env=isolated_env, stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL, timeout=5,
                )
                if stopped.returncode != 0:
                    break
                time.sleep(0.1)
            else:
                raise AcceptanceError(f"{runtime} Helper did not remove its socket after SIGTERM")

            if ordinal == 0:
                candidate_during_helper_stop = routed_http(
                    address, restart_route_port, restart_route_host
                )
                stable_during_helper_stop = routed_http(
                    address, route_port, stable_route_host
                )
                if candidate_during_helper_stop != (200, "v2") or stable_during_helper_stop != (200, "v1"):
                    raise AcceptanceError(
                        "Host A routes changed while its Helper was stopped: "
                        + json.dumps({
                            "candidate": candidate_during_helper_stop,
                            "stable": stable_during_helper_stop,
                        })
                    )
            else:
                candidate_during_helper_stop = None
                stable_during_helper_stop = routed_http(
                    address, route_port, stable_route_host
                )
                if stable_during_helper_stop != (200, "v1"):
                    raise AcceptanceError(
                        "Host B stable route changed while its Helper was stopped: "
                        + json.dumps(stable_during_helper_stop)
                    )

            restart_helper_command = ["podman", "exec", "--detach"]
            if runtime == "docker":
                restart_helper_command.extend([
                    "--env", "DOCKER_HOST=unix:///run/nanpanel-docker/docker.sock",
                ])
            restart_helper_command.extend([
                helper_name,
                "/usr/local/bin/nanpanel-helper",
                "/run/nanpanel/helper.sock",
                "1000",
                "1000",
                "/etc/nanpanel/managed",
                "https://localhost",
                "/run/p5-agent-certs/ca.crt",
                "/usr/local/bin/nanpanel-agent",
            ])
            command(restart_helper_command, env=isolated_env)
            helper_start_deadline = time.monotonic() + 15
            while time.monotonic() < helper_start_deadline:
                ready = subprocess.run(
                    helper_socket, env=isolated_env, stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL, timeout=5,
                )
                if ready.returncode == 0:
                    break
                time.sleep(0.1)
            else:
                raise AcceptanceError(f"{runtime} Helper did not recreate its Unix socket")

            inventory_deadline = time.monotonic() + 30
            while time.monotonic() < inventory_deadline:
                _, current_hosts = api.request("GET", "/hosts?limit=100")
                restarted_host = next(
                    (host for host in current_hosts.get("items", [])
                     if host.get("hostname") == hostname),
                    None,
                )
                if (
                    restarted_host is not None
                    and restarted_host.get("status") == "ONLINE"
                    and required_helper_capabilities.issubset(
                        set(restarted_host.get("capabilities", []))
                    )
                ):
                    break
                time.sleep(0.5)
            else:
                raise AcceptanceError(
                    f"{runtime} Agent did not restore Helper release capabilities: "
                    + json.dumps(restarted_host, sort_keys=True)
                )
            helper_restart_results.append({
                "hostname": hostname,
                "runtime": runtime,
                "pidBefore": helper_pid,
                "stateAfterRestart": restarted_host["status"],
                "capabilitiesRestored": sorted(required_helper_capabilities),
                "trafficWhileStopped": {
                    "candidate": candidate_during_helper_stop,
                    "stable": stable_during_helper_stop,
                },
            })

        print(f"[{run_id}] first host completed; plan persisted at PAUSED and both Helpers restarted", flush=True)
        previous_core_pid = core.pid
        core.terminate()
        try:
            core.wait(timeout=45)
        except subprocess.TimeoutExpired:
            core.kill()
            core.wait(timeout=10)
            raise AcceptanceError("Core did not stop cleanly for restart recovery acceptance")
        core_stream.close()
        stopped_core_a = routed_http("127.0.0.2", restart_route_port, restart_route_host)
        stopped_stable_a = routed_http("127.0.0.2", route_port, stable_route_host)
        stopped_stable_b = routed_http("127.0.0.3", route_port, stable_route_host)
        if (
            stopped_core_a != (200, "v2")
            or stopped_stable_a != (200, "v1")
            or stopped_stable_b != (200, "v1")
        ):
            raise AcceptanceError(
                "routed traffic changed while Core was stopped: "
                + json.dumps({
                    "restartHostA": stopped_core_a,
                    "stableHostA": stopped_stable_a,
                    "stableHostB": stopped_stable_b,
                })
            )
        core_stream = core_log.open("a")
        core = subprocess.Popen(
            ["java", "-jar", CORE_RUNNER], cwd=ROOT, env=core_env,
            stdout=core_stream, stderr=subprocess.STDOUT, start_new_session=True,
        )
        processes.append(core)
        wait_http(f"{api_origin}/q/health/ready", core, "restarted Quarkus Core", timeout=150)
        print(f"[{run_id}] Core restarted; waiting for persisted plan and Agent inventory", flush=True)
        restart_plan_deadline = time.monotonic() + 60
        while time.monotonic() < restart_plan_deadline:
            _, restart_plan = api.request(
                "GET", f"/host-application-releases/{restart_plan_id}"
            )
            _, current_hosts = api.request("GET", "/hosts?limit=100")
            current_hosts_by_name = {
                host["hostname"]: host for host in current_hosts.get("items", [])
                if host.get("hostname") in (host_a, host_b)
            }
            if (
                restart_plan["state"] == "PAUSED"
                and [target["state"] for target in restart_plan["targets"]]
                == ["SUCCEEDED", "BASELINE_DEPLOY"]
                and len(current_hosts_by_name) == 2
                and all(host.get("status") == "ONLINE" for host in current_hosts_by_name.values())
            ):
                break
            time.sleep(1)
        else:
            raise AcceptanceError(
                "Core did not restore the persisted paused release and Agent inventory: "
                + json.dumps({"plan": restart_plan, "hosts": current_hosts_by_name}, sort_keys=True)
            )
        state_after_core_restart = restart_plan["state"]
        target_states_after_core_restart = [
            target["state"] for target in restart_plan["targets"]
        ]
        after_restart_a = routed_http("127.0.0.2", restart_route_port, restart_route_host)
        after_restart_stable_a = routed_http("127.0.0.2", route_port, stable_route_host)
        after_restart_stable_b = routed_http("127.0.0.3", route_port, stable_route_host)
        if (
            after_restart_a != (200, "v2")
            or after_restart_stable_a != (200, "v1")
            or after_restart_stable_b != (200, "v1")
        ):
            raise AcceptanceError(
                "routed traffic changed after Core restart: "
                + json.dumps({
                    "restartHostA": after_restart_a,
                    "stableHostA": after_restart_stable_a,
                    "stableHostB": after_restart_stable_b,
                })
            )
        api.request("POST", f"/host-application-releases/{restart_plan_id}/resume", {})
        print(f"[{run_id}] persisted PAUSED plan resumed through the live Core API", flush=True)
        completion_deadline = time.monotonic() + 360
        while time.monotonic() < completion_deadline:
            _, restart_plan = api.request(
                "GET", f"/host-application-releases/{restart_plan_id}"
            )
            if restart_plan["state"] == "SUCCEEDED":
                break
            if restart_plan["state"] in {"FAILED", "UNKNOWN", "ROLLED_BACK"}:
                raise AcceptanceError(
                    "Core restart plan failed after operator resume: "
                    + json.dumps(restart_plan, sort_keys=True)
                )
            time.sleep(1)
        else:
            raise AcceptanceError(
                "Core restart plan timed out after operator resume: "
                + json.dumps(restart_plan, sort_keys=True)
            )
        final_restart_a = routed_http("127.0.0.2", restart_route_port, restart_route_host)
        final_restart_b = routed_http("127.0.0.3", restart_route_port, restart_route_host)
        if final_restart_a != (200, "v2") or final_restart_b != (200, "v2"):
            raise AcceptanceError(
                "resumed Core restart plan did not route both hosts to v2: "
                + json.dumps({"hostA": final_restart_a, "hostB": final_restart_b})
            )
        audit_query = urllib.parse.urlencode({
            "limit": 100,
            "action": "HOST_APPLICATION_RELEASE_HOST_SUCCEEDED",
        })
        _, audit_events = api.request("GET", f"/admin/audit-events?{audit_query}")
        expected_audit_resources = {f"{restart_plan_id}:0", f"{restart_plan_id}:1"}
        observed_audit_resources = {
            item.get("resourceId") for item in audit_events.get("items", [])
            if item.get("resourceId") in expected_audit_resources
        }
        if observed_audit_resources != expected_audit_resources:
            raise AcceptanceError(
                "Core restart release did not persist both host success audit events: "
                + json.dumps(audit_events.get("items", []), sort_keys=True)
            )
        output["coreRestartAcceptance"] = {
            "planId": restart_plan_id,
            "listenPort": restart_route_port,
            "corePidBefore": previous_core_pid,
            "corePidAfter": core.pid,
            "stateAfterRestart": state_after_core_restart,
            "targetStatesAfterRestart": target_states_after_core_restart,
            "trafficWhileCoreStopped": {
                "restartHostA": stopped_core_a,
                "stableHostA": stopped_stable_a,
                "stableHostB": stopped_stable_b,
            },
            "trafficAfterCoreRestart": {
                "restartHostA": after_restart_a,
                "stableHostA": after_restart_stable_a,
                "stableHostB": after_restart_stable_b,
            },
            "finalState": restart_plan["state"],
            "finalTraffic": {"hostA": final_restart_a, "hostB": final_restart_b},
            "successAuditResources": sorted(observed_audit_resources),
        }
        output["helperRestartAcceptance"] = helper_restart_results

        recovery_plan_name = f"p5rec{run_id}"
        recovery_hostname = f"{recovery_plan_name}.example.test"
        recovery_route_port = unique_port("127.0.0.2")
        while recovery_route_port < 1024 or recovery_route_port == 0:
            selected_ports.discard(recovery_route_port)
            recovery_route_port = unique_port("127.0.0.2")
        recovery_payload = {
            "name": recovery_plan_name,
            "stableImage": APP_IMAGES[0],
            "image": APP_IMAGES[1],
            "containerPort": 8080,
            "serverName": recovery_hostname,
            "listenPort": recovery_route_port,
            "healthPath": "/health",
            "batchSize": 1,
            "targets": [{
                "hostId": current_hosts_by_name[host_a]["id"],
                "listenAddress": "127.0.0.2",
                "expectedGeneration": current_hosts_by_name[host_a]["generation"],
            }],
            "reason": f"P5 durable Helper result replay {run_id}",
        }
        _, recovery_plan = api.request(
            "POST", "/host-application-releases", recovery_payload
        )
        recovery_plan_id = recovery_plan["id"]
        callback_proxy.drop_next_result_for(enrollments[0]["agentId"])
        api.request("POST", f"/host-application-releases/{recovery_plan_id}/start", {})
        dropped_report = callback_proxy.wait_for_dropped_result(180)
        operation_id = dropped_report["operationId"]
        if dropped_report.get("status") != "SUCCEEDED":
            raise AcceptanceError(
                "the Helper did not complete its runtime operation before result delivery was dropped: "
                + json.dumps(dropped_report, sort_keys=True)
            )
        recovery_deadline = time.monotonic() + 30
        while time.monotonic() < recovery_deadline:
            _, recovery_plan = api.request(
                "GET", f"/host-application-releases/{recovery_plan_id}"
            )
            if (recovery_plan["targets"][0].get("operationId") == operation_id
                    and recovery_plan["targets"][0]["state"] == "BASELINE_DEPLOY"):
                break
            time.sleep(0.25)
        else:
            raise AcceptanceError(
                "Core did not retain the in-flight Helper operation identity: "
                + json.dumps(recovery_plan, sort_keys=True)
            )
        stable_container_name = (
            "npapp-" + recovery_plan_id.replace("-", "")[:10] + "-0-s"
        )
        container_id_before = command(
            ["podman", "inspect", "--format={{.Id}}", stable_container_name],
            env=isolated_env,
        )
        helper_name = helper_names[0]
        agent_pid = command(
            ["podman", "exec", helper_name, "cat", "/run/nanpanel/agent.pid"],
            env=isolated_env,
        )
        command(["podman", "exec", helper_name, "touch", "/run/nanpanel/hold-agent"],
                env=isolated_env)
        command(["podman", "exec", helper_name, "kill", "-TERM", agent_pid],
                env=isolated_env)
        agent_stop_deadline = time.monotonic() + 15
        while time.monotonic() < agent_stop_deadline:
            stopped = subprocess.run(
                ["podman", "exec", helper_name, "test", "-e", "/run/nanpanel/agent.pid"],
                env=isolated_env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                timeout=5,
            )
            if stopped.returncode != 0:
                break
            time.sleep(0.1)
        else:
            raise AcceptanceError("Agent did not stop at the controlled replay gate")

        deadline_update = command([
            "podman", "exec", db_name, "psql", "-h", "127.0.0.1", "-p", str(db_port),
            "-U", "nanpanel", "-d", "nanpanel",
            "-At", "-c",
            "UPDATE operation SET deadline_at=NOW()-INTERVAL '1 second' "
            f"WHERE id='{operation_id}' AND status='RUNNING'",
        ], env=isolated_env)
        if "UPDATE 1" not in deadline_update:
            raise AcceptanceError(
                f"could not expire the isolated operation deadline: {deadline_update}"
            )
        _, unknown_operation = api.request("GET", f"/operations/{operation_id}")
        if unknown_operation.get("status") != "UNKNOWN":
            raise AcceptanceError(
                "the timed-out Core operation was not marked UNKNOWN: "
                + json.dumps(unknown_operation, sort_keys=True)
            )
        unknown_deadline = time.monotonic() + 15
        while time.monotonic() < unknown_deadline:
            _, recovery_plan = api.request(
                "GET", f"/host-application-releases/{recovery_plan_id}"
            )
            if recovery_plan["state"] == "UNKNOWN":
                break
            time.sleep(0.25)
        else:
            raise AcceptanceError(
                "the release did not expose its unknown runtime result: "
                + json.dumps(recovery_plan, sort_keys=True)
            )

        api.request("POST", f"/host-application-releases/{recovery_plan_id}/reconcile", {})
        command(["podman", "exec", helper_name, "rm", "-f", "/run/nanpanel/hold-agent"],
                env=isolated_env)
        replayed_report = callback_proxy.wait_for_replayed_result(operation_id, 60)
        if (replayed_report.get("status") != "SUCCEEDED"
                or replayed_report.get("idempotencyKey") != dropped_report.get("idempotencyKey")
                or replayed_report.get("detail") != dropped_report.get("detail")):
            raise AcceptanceError(
                "Agent did not replay the durable result for the same operation: "
                + json.dumps(replayed_report, sort_keys=True)
            )
        completion_deadline = time.monotonic() + 240
        while time.monotonic() < completion_deadline:
            _, recovery_plan = api.request(
                "GET", f"/host-application-releases/{recovery_plan_id}"
            )
            if recovery_plan["state"] == "SUCCEEDED":
                break
            if recovery_plan["state"] in {"FAILED", "ROLLED_BACK"}:
                raise AcceptanceError(
                    "replayed Helper result did not resume the host release: "
                    + json.dumps(recovery_plan, sort_keys=True)
                )
            time.sleep(0.5)
        else:
            raise AcceptanceError("release did not finish after replaying the durable result")
        container_id_after = command(
            ["podman", "inspect", "--format={{.Id}}", stable_container_name],
            env=isolated_env,
        )
        final_recovery_traffic = routed_http(
            "127.0.0.2", recovery_route_port, recovery_hostname
        )
        if container_id_after != container_id_before:
            raise AcceptanceError(
                "replay created a second baseline container instead of reusing the durable result"
            )
        if final_recovery_traffic != (200, "v2"):
            raise AcceptanceError(
                "recovered release did not serve the candidate through its route: "
                + json.dumps(final_recovery_traffic)
            )
        journal_document = json.loads(command([
            "podman", "exec", helper_name, "cat", "/var/lib/nanpanel-agent/operation-journal.json",
        ], env=isolated_env))
        if dropped_report["idempotencyKey"] in journal_document.get("operations", {}):
            raise AcceptanceError("Core acknowledged the result but Agent did not clear its journal")
        output["uncertainResultRecovery"] = {
            "planId": recovery_plan_id,
            "operationId": operation_id,
            "idempotencyKey": dropped_report["idempotencyKey"],
            "operationStateAfterTimeout": unknown_operation["status"],
            "agentPidBeforeRestart": agent_pid,
            "agentPidAfterRestart": command(
                ["podman", "exec", helper_name, "cat", "/run/nanpanel/agent.pid"],
                env=isolated_env,
            ),
            "droppedResultStatus": dropped_report["status"],
            "replayedResultStatus": replayed_report["status"],
            "sameContainerId": container_id_after == container_id_before,
            "finalState": recovery_plan["state"],
            "finalTraffic": final_recovery_traffic,
            "agentJournalEntryAcknowledged": True,
        }
        output["runId"] = run_id
        output["runtimeAssignments"] = {
            host_a: runtime_assignments[0], host_b: runtime_assignments[1],
        }
        output["runtime"] = "isolated rootless Podman VFS store for Core services; per-Helper runtime as assigned"
        if docker_host_socket is not None:
            output["dockerEngine"] = "external rootless Docker Engine via mounted Unix socket"
        output["hostnames"] = [host_a, host_b]
        output["hostIds"] = [hosts_by_name[host_a]["id"], hosts_by_name[host_b]["id"]]
        output["loopbackAddresses"] = ["127.0.0.2", "127.0.0.3"]
        output["routePort"] = route_port
        output["kubernetesContextChanged"] = False
        print(json.dumps(output, indent=2, sort_keys=True))
        acceptance_complete = True
    except Exception:
        print("P5 live host acceptance failed. Local service logs:", file=sys.stderr)
        if podman_socket.exists():
            for helper_name in helper_names:
                helper_logs = subprocess.run(
                    ["podman", "logs", helper_name], env=isolated_env,
                    text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    timeout=15,
                )
                if helper_logs.stdout.strip():
                    print(f"--- Helper {helper_name} ---\n{helper_logs.stdout[-6000:]}", file=sys.stderr)
        for label, path in (("Core", core_log), ("Gateway", gateway_log),
                            ("Vite", web_log), ("Podman service", podman_log)):
            snippet = tail(path)
            if snippet:
                print(f"--- {label} ---\n{snippet}", file=sys.stderr)
        raise
    finally:
        if podman_socket.exists():
            isolated_env = remote_env | {"CONTAINER_HOST": podman_uri}
            try:
                subprocess.run(
                    ["podman", "kill", "--all"], env=isolated_env,
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30,
                )
                containers = subprocess.run(
                    ["podman", "ps", "--all", "--quiet"], env=isolated_env,
                    text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30,
                )
                if containers.returncode == 0 and containers.stdout.strip():
                    subprocess.run(
                        ["podman", "rm", "--force", "--volumes", *containers.stdout.split()],
                        env=isolated_env, stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL, timeout=120,
                    )
            except subprocess.TimeoutExpired as error:
                print(f"isolated container cleanup timed out: {error.cmd}", file=sys.stderr)
        for process in reversed(processes):
            if process.poll() is None:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                    process.wait(timeout=10)
                except (ProcessLookupError, subprocess.TimeoutExpired):
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
        if callback_proxy is not None:
            callback_proxy.close()
        try:
            reset = subprocess.run(
                ["podman", f"--root={storage_root}/graph", f"--runroot={runroot}",
                 f"--tmpdir={podman_tmp}", "--storage-driver=vfs", "system", "reset", "--force"],
                cwd=ROOT, env=remote_env, text=True, timeout=120,
                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
            )
            if reset.returncode:
                print(f"isolated Podman store reset failed: {reset.stderr[-2000:]}", file=sys.stderr)
        except subprocess.TimeoutExpired:
            print("isolated Podman store reset timed out", file=sys.stderr)
        shutil.rmtree(root, ignore_errors=True)
        if root.exists():
            print(f"temporary P5 store could not be fully removed: {root}", file=sys.stderr)
        if acceptance_complete and os.environ.get("NANPANEL_P5_KEEP_HELPER_IMAGE_CACHE") != "1":
            for helper_image_cache in helper_image_caches.values():
                helper_image_cache.unlink(missing_ok=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
