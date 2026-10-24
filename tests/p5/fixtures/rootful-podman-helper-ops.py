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


def wait_for_http(url, timeout=30):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
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
    args = parser.parse_args()

    capabilities_response = helper_call(args.socket, "helper.capabilities")
    if not capabilities_response.get("ok"):
        raise RuntimeError(f"Helper capability request failed: {capabilities_response}")
    capabilities = json.loads(capabilities_response["result"])
    if "container.deploy" not in capabilities:
        raise RuntimeError(f"Helper did not advertise container.deploy: {capabilities}")

    deploy = helper_call(args.socket, "container.deploy", args.container_name, {
        "image": args.image,
        "hostPort": args.host_port,
        "containerPort": args.container_port,
    })
    if not deploy.get("ok"):
        raise RuntimeError(f"Helper container.deploy failed: {deploy}")

    stop = None
    try:
        routed = wait_for_http(f"http://127.0.0.1:{args.host_port}/")
        expected = {"status": 200, "body": args.expected_body}
        if routed != expected:
            raise RuntimeError(f"unexpected loopback response: {routed}")
        listed = helper_call(args.socket, "container.list")
        if not listed.get("ok") or args.container_name not in listed.get("result", ""):
            raise RuntimeError(f"Helper container.list missed the deployed target: {listed}")
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
        "routedHttp": {"url": f"http://127.0.0.1:{args.host_port}/", **routed},
        "listContainsContainer": True,
        "stopOperationId": stop["operation_id"],
        "stopResult": stop["result"],
    }, indent=2))


if __name__ == "__main__":
    main()
