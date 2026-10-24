#!/usr/bin/env python3
"""Exercise typed container operations over an Agent-authorized Helper socket."""

import argparse
import json
import socket
import time
import urllib.error
import urllib.request
import uuid


def helper_call(socket_path, capability, target="", payload=None):
    request = {
        "operationId": str(uuid.uuid4()),
        "idempotencyKey": str(uuid.uuid4()),
        "capability": capability,
        "target": target,
        "deadlineEpochMs": int(time.time() * 1000) + 240_000,
    }
    if payload is not None:
        request["payloadJson"] = json.dumps(payload, separators=(",", ":"))
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(70)
        client.connect(socket_path)
        client.sendall(json.dumps(request, separators=(",", ":")).encode() + b"\n")
        chunks = bytearray()
        while not chunks.endswith(b"\n"):
            chunk = client.recv(65_536)
            if not chunk:
                raise RuntimeError("Helper closed the response stream")
            chunks.extend(chunk)
    return json.loads(chunks)


def wait_for_http(url, timeout=30, host_header=None):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            request = urllib.request.Request(url)
            if host_header:
                request.add_header("Host", host_header)
            with urllib.request.urlopen(request, timeout=2) as response:
                body = response.read().decode().strip()
                return {"status": response.status, "body": body}
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            last_error = str(error)
            time.sleep(0.25)
    raise RuntimeError(f"published container did not answer before timeout: {last_error}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--socket", required=True, help="configured Helper Unix socket")
    parser.add_argument("--image", required=True, help="locally available app image")
    parser.add_argument("--container-name", required=True)
    parser.add_argument("--host-port", type=int, required=True)
    parser.add_argument("--container-port", type=int, default=8080)
    parser.add_argument("--expected-body", default="v2")
    parser.add_argument("--route-port", type=int, help="optional managed Nginx listener port")
    parser.add_argument("--server-name", default="nanpanel-rootful.test")
    args = parser.parse_args()

    capabilities_response = helper_call(args.socket, "helper.capabilities")
    if not capabilities_response.get("ok"):
        raise RuntimeError(f"Helper capability request failed: {capabilities_response}")
    capabilities = json.loads(capabilities_response["result"])
    required = {"container.deploy", "container.list", "container.stop"}
    if args.route_port is not None:
        required.add("nginx.route.set")
    missing = required - set(capabilities)
    if missing:
        raise RuntimeError(f"Helper did not advertise {sorted(missing)}: {capabilities}")

    deploy = helper_call(args.socket, "container.deploy", args.container_name, {
        "image": args.image,
        "hostPort": args.host_port,
        "containerPort": args.container_port,
    })
    if not deploy.get("ok"):
        raise RuntimeError(f"Helper container.deploy failed: {deploy}")

    stop = None
    route = None
    direct_http = None
    nginx_http = None
    try:
        direct_http = wait_for_http(f"http://127.0.0.1:{args.host_port}/")
        expected = {"status": 200, "body": args.expected_body}
        if direct_http != expected:
            raise RuntimeError(f"unexpected loopback response: {direct_http}")
        listed = helper_call(args.socket, "container.list")
        if not listed.get("ok") or args.container_name not in listed.get("result", ""):
            raise RuntimeError(f"Helper container.list missed the deployed target: {listed}")
        if args.route_port is not None:
            route = helper_call(args.socket, "nginx.route.set", args.container_name, {
                "listenAddress": "127.0.0.1",
                "listenPort": args.route_port,
                "serverName": args.server_name,
                "healthPath": "/",
                "backends": [{"name": "stable", "port": args.host_port, "weight": 100}],
            })
            if not route.get("ok"):
                raise RuntimeError(f"Helper nginx.route.set failed: {route}")
            nginx_http = wait_for_http(
                f"http://127.0.0.1:{args.route_port}/",
                host_header=args.server_name,
            )
            if nginx_http != expected:
                raise RuntimeError(f"unexpected Nginx routed response: {nginx_http}")
    finally:
        stop = helper_call(args.socket, "container.stop", args.container_name)

    if not stop.get("ok"):
        raise RuntimeError(f"Helper container.stop failed: {stop}")
    print(json.dumps({
        "result": "PASS",
        "containerName": args.container_name,
        "capabilities": [
            name for name in capabilities
            if name in {"container.deploy", "container.list", "container.stop", "nginx.route.set"}
        ],
        "deployOperationId": deploy["operation_id"],
        "deployResult": deploy["result"],
        "directHttp": {"url": f"http://127.0.0.1:{args.host_port}/", **direct_http},
        "listContainsContainer": True,
        "routeOperationId": route["operation_id"] if route else None,
        "routeResult": route["result"] if route else None,
        "nginxRoutedHttp": (
            {"url": f"http://127.0.0.1:{args.route_port}/", **nginx_http}
            if args.route_port is not None else None
        ),
        "stopOperationId": stop["operation_id"],
        "stopResult": stop["result"],
    }, indent=2))


if __name__ == "__main__":
    main()
