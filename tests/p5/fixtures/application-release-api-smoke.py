#!/usr/bin/env python3
"""Exercise NaNPanel's progressive-release API against an isolated Kind cluster."""

import argparse
import collections
import http.cookiejar
import json
import os
import subprocess
import threading
import time
import uuid
import urllib.error
import urllib.request
from decimal import Decimal, InvalidOperation
from pathlib import Path


class Api:
    def __init__(self, base_url: str, username: str, password: str):
        self.base_url = base_url.rstrip("/")
        self.cookies = http.cookiejar.CookieJar()
        self.client = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.cookies)
        )
        csrf = self.request("GET", "/api/v1/auth/csrf")
        self.csrf = csrf["csrfToken"]
        login = self.request("POST", "/api/v1/auth/login", {
            "username": username,
            "password": password,
        })
        self.csrf = login["csrfToken"]
        if login.get("user", {}).get("mustChangePassword"):
            next_password = os.environ.get(
                "NANPANEL_SMOKE_NEW_PASSWORD",
                "p5-local-admin-password-after-bootstrap-2026",
            )
            self.request("PUT", "/api/v1/auth/password", {
                "currentPassword": password,
                "newPassword": next_password,
            }, expected=(204,))
            self.csrf = self.request("GET", "/api/v1/auth/csrf")["csrfToken"]
            login = self.request("POST", "/api/v1/auth/login", {
                "username": username,
                "password": next_password,
            })
            self.csrf = login["csrfToken"]

    def request(self, method: str, path: str, body=None, expected=(200,)):
        data = None if body is None else json.dumps(body).encode()
        headers = {"Accept": "application/json"}
        if data is not None:
            headers["Content-Type"] = "application/json"
        if method not in ("GET", "HEAD", "OPTIONS"):
            headers["X-CSRF-Token"] = self.csrf
        request = urllib.request.Request(
            self.base_url + path, data=data, headers=headers, method=method
        )
        try:
            with self.client.open(request, timeout=30) as response:
                status = response.status
                payload = response.read()
        except urllib.error.HTTPError as response:
            status = response.code
            payload = response.read()
        parsed = json.loads(payload) if payload else {}
        if status not in expected:
            raise RuntimeError(f"API {method} {path} returned {status}: {parsed}")
        return parsed


class TrafficPump:
    def __init__(self, url: str, hostname: str):
        self.url = url
        self.hostname = hostname
        self.responses = collections.Counter()
        self.transport_errors = 0
        self.lock = threading.Lock()
        self.stop_event = threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True)

    def start(self):
        self.thread.start()

    def stop(self):
        self.stop_event.set()
        self.thread.join(timeout=5)

    def request(self):
        request = urllib.request.Request(self.url, headers={"Host": self.hostname})
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                key = f"{response.status} {response.read().decode().strip()}"
        except urllib.error.HTTPError as response:
            key = f"{response.code} {response.read().decode().strip()}"
        except (OSError, TimeoutError):
            with self.lock:
                self.transport_errors += 1
            return
        with self.lock:
            self.responses[key] += 1

    def run(self):
        while not self.stop_event.is_set():
            self.request()
            self.stop_event.wait(0.03)

    def burst(self, count: int):
        with self.lock:
            before = self.responses.copy()
            errors_before = self.transport_errors
        for _ in range(count):
            self.request()
        with self.lock:
            delta = self.responses.copy()
            delta.subtract(before)
            return {
                "requests": sum(delta.values()),
                "responses": {key: value for key, value in sorted(delta.items()) if value},
                "transportErrors": self.transport_errors - errors_before,
            }


def await_view(api: Api, path: str, predicate, label: str, timeout: int = 300):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = api.request("GET", path)
        if predicate(last):
            return last
        if last.get("state") in {"FAILED", "ROLLED_BACK"}:
            raise RuntimeError(f"{label} entered terminal state {last['state']}: {last}")
        time.sleep(1)
    raise TimeoutError(f"Timed out waiting for {label}; last observation: {last}")


def wait_for_pause(api: Api, path: str, weight: int):
    return await_view(
        api, path,
        lambda view: view.get("state") == "PAUSED"
        and view.get("canaryWeight") == weight
        and view.get("stableWeight") == 100 - weight,
        f"{weight}% canary pause",
    )


def analysis_passed(view, weight: int):
    stages = [stage for stage in view.get("stages", [])
              if stage.get("observedCanaryWeight") == weight
              and stage.get("analysisRunName")]
    if not stages:
        return False
    run_name = stages[-1]["analysisRunName"]
    measurements = [measurement for measurement in view.get("measurements", [])
                    if measurement.get("analysisRunName") == run_name
                    and measurement.get("metricName") == "canary-http-error-rate"]
    if len(measurements) < 3:
        return False
    latest = sorted(measurements, key=lambda item: item.get("measurementOrdinal", 0))[-3:]
    try:
            return all(str(item.get("phase", "")).upper() == "SUCCESSFUL"
                   and Decimal(str(item.get("value"))) <= Decimal("0.025")
                   for item in latest)
    except (InvalidOperation, TypeError):
        return False


def path_for(cluster_id: str, release_id: str):
    return (f"/api/v1/kubernetes/clusters/{cluster_id}/namespaces/default/"
            f"application-releases/{release_id}")


def create_release(api, cluster_id, name, stable, target, hostname, reason):
    base = f"/api/v1/kubernetes/clusters/{cluster_id}/namespaces/default/application-releases"
    release = api.request("POST", base, {
        "name": name,
        "stableImage": stable,
        "image": target,
        "replicas": 2,
        "containerPort": 8080,
        "gatewayName": "eg",
        "hostname": hostname,
        "prometheusService": "prometheus",
        "maxErrorRate": 0.05,
        "reason": reason,
    }, expected=(201,))
    return release, path_for(cluster_id, release["id"])


def kubectl_json(kubeconfig: Path, context: str, *arguments, manifest=None):
    result = subprocess.run(
        ["kubectl", "--kubeconfig", str(kubeconfig), "--context", context,
         *arguments],
        input=manifest,
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"kubectl {' '.join(arguments)} failed ({result.returncode}): "
            f"{result.stderr.strip()}"
        )
    return json.loads(result.stdout) if result.stdout.strip() else {}


def assert_kubernetes_resource_absent(kubeconfig: Path, context: str,
                                      resource: str, name: str):
    result = subprocess.run(
        ["kubectl", "--kubeconfig", str(kubeconfig), "--context", context,
         "get", resource, name, "--namespace", "default", "-o", "json"],
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode == 0:
        raise RuntimeError(f"unexpected {resource}/{name} was created")
    if "not found" not in result.stderr.lower():
        raise RuntimeError(
            f"unable to verify {resource}/{name} absence: {result.stderr.strip()}"
        )


def run_ownership_conflict_probe(api, cluster_id, kubeconfig: Path, context: str):
    marker = uuid.uuid4().hex
    name = f"p5-ownership-{marker[:8]}"
    hostname = f"{name}.example.test"
    release, path = create_release(
        api, cluster_id, name,
        "localhost/nanpanel-p5-app:v1", "localhost/nanpanel-p5-app:v2",
        hostname, "P5 unowned Kubernetes resource conflict probe",
    )
    prefix = "np-" + release["id"].replace("-", "")[:12]
    stable_service = prefix + "-stable"
    conflict_service = {
        "apiVersion": "v1",
        "kind": "Service",
        "metadata": {
            "name": stable_service,
            "namespace": "default",
            "labels": {"p5.nanpanel.io/probe": marker},
            "annotations": {"p5.nanpanel.io/ownership": "pre-existing-unowned"},
        },
        "spec": {
            "type": "ClusterIP",
            "selector": {"p5.nanpanel.io/probe": marker},
            "ports": [{"name": "http", "port": 8080, "protocol": "TCP",
                       "targetPort": "http"}],
        },
    }
    created = False
    try:
        kubectl_json(kubeconfig, context, "create", "-f", "-", "-o", "json",
                     manifest=json.dumps(conflict_service))
        created = True
        before = kubectl_json(kubeconfig, context, "get", "service", stable_service,
                              "--namespace", "default", "-o", "json")
        rejected = api.request("POST", path + "/start", expected=(409,))
        if rejected.get("code") != "APPLICATION_RELEASE_OWNERSHIP_CONFLICT":
            raise RuntimeError(f"unexpected conflict response: {rejected}")
        after = kubectl_json(kubeconfig, context, "get", "service", stable_service,
                             "--namespace", "default", "-o", "json")
        before_metadata = before.get("metadata", {})
        after_metadata = after.get("metadata", {})
        identity_preserved = all(
            before_metadata.get(field) == after_metadata.get(field)
            for field in ("uid", "resourceVersion")
        )
        metadata_preserved = all(
            before_metadata.get(field) == after_metadata.get(field)
            for field in ("labels", "annotations")
        )
        spec_preserved = before.get("spec") == after.get("spec")
        if not identity_preserved or not metadata_preserved or not spec_preserved:
            raise RuntimeError("the pre-existing unowned Service changed during rejection")
        for resource, resource_name in (
            ("services", prefix + "-canary"),
            ("analysistemplates.argoproj.io", prefix),
            ("httproutes.gateway.networking.k8s.io", prefix + "-route"),
            ("rollouts.argoproj.io", prefix),
        ):
            assert_kubernetes_resource_absent(
                kubeconfig, context, resource, resource_name
            )
        return {
            "releaseId": release["id"],
            "rejectedCode": rejected["code"],
            "preExistingService": stable_service,
            "uidAndResourceVersionPreserved": identity_preserved,
            "labelsAndAnnotationsPreserved": metadata_preserved,
            "specPreserved": spec_preserved,
            "noLaterReleaseResourcesCreated": True,
        }
    finally:
        if created:
            cleanup = subprocess.run(
                ["kubectl", "--kubeconfig", str(kubeconfig), "--context", context,
                 "delete", "service", stable_service, "--namespace", "default",
                 "--ignore-not-found=true", "--wait=true"],
                text=True,
                capture_output=True,
                check=False,
            )
            if cleanup.returncode != 0:
                raise RuntimeError(
                    f"failed to remove probe Service {stable_service}: "
                    f"{cleanup.stderr.strip()}"
                )


def run_existing_route_conflict_probe(api, cluster_id, kubeconfig: Path, context: str):
    route_name = "p5-demo"
    route_resource = "httproutes.gateway.networking.k8s.io"
    before = kubectl_json(kubeconfig, context, "get", route_resource, route_name,
                          "--namespace", "default", "-o", "json")
    before_metadata = before.get("metadata", {})
    before_spec = before.get("spec", {})
    if "p5.example.test" not in before_spec.get("hostnames", []):
        raise RuntimeError("the existing p5-demo route no longer owns its documented hostname")
    name = f"p5-route-conflict-{uuid.uuid4().hex[:8]}"
    release, path = create_release(
        api, cluster_id, name,
        "localhost/nanpanel-p5-app:v1", "localhost/nanpanel-p5-app:v2",
        "p5.example.test", "P5 existing HTTPRoute conflict probe",
    )
    prefix = "np-" + release["id"].replace("-", "")[:12]
    rejected = api.request("POST", path + "/start", expected=(409,))
    if rejected.get("code") != "APPLICATION_RELEASE_HOSTNAME_CONFLICT":
        raise RuntimeError(f"unexpected existing-route conflict response: {rejected}")
    after = kubectl_json(kubeconfig, context, "get", route_resource, route_name,
                         "--namespace", "default", "-o", "json")
    after_metadata = after.get("metadata", {})
    identity_preserved = all(
        before_metadata.get(field) == after_metadata.get(field)
        for field in ("uid", "generation")
    )
    metadata_preserved = all(
        before_metadata.get(field) == after_metadata.get(field)
        for field in ("labels", "annotations")
    )
    spec_preserved = before_spec == after.get("spec", {})
    if not identity_preserved or not metadata_preserved or not spec_preserved:
        raise RuntimeError("the existing HTTPRoute changed during conflict rejection")
    for resource, resource_name in (
        ("services", prefix + "-stable"),
        ("services", prefix + "-canary"),
        ("analysistemplates.argoproj.io", prefix),
        (route_resource, prefix + "-route"),
        ("rollouts.argoproj.io", prefix),
    ):
        assert_kubernetes_resource_absent(kubeconfig, context, resource, resource_name)
    return {
        "releaseId": release["id"],
        "rejectedCode": rejected["code"],
        "preExistingRoute": route_name,
        "uidAndGenerationPreserved": identity_preserved,
        "labelsAndAnnotationsPreserved": metadata_preserved,
        "specPreserved": spec_preserved,
        "noReleaseResourcesCreated": True,
    }


def run_healthy_release(api, cluster_id, name, hostname, traffic_url, release_id=None):
    if release_id:
        path = path_for(cluster_id, release_id)
        release = api.request("GET", path)
        name = release["name"]
        hostname = release["hostname"]
        started = release
    else:
        release, path = create_release(
            api, cluster_id, name,
            "localhost/nanpanel-p5-app:v1", "localhost/nanpanel-p5-app:v2",
            hostname, "P5 disposable API integration acceptance",
        )
        started = api.request("POST", path + "/start", expected=(200,))
    traffic = TrafficPump(traffic_url, hostname)
    traffic.start()
    observations = {"releaseId": release["id"], "name": name, "hostname": hostname,
                    "initialState": started.get("state"), "windows": {}}
    try:
        await_view(
            api, path,
            lambda view: view.get("rolloutPhase", "").lower() == "healthy"
            and view.get("stableWeight") == 100 and view.get("canaryWeight") == 0,
            "healthy stable baseline",
        )
        observations["windows"]["0"] = traffic.burst(100)
        for weight in (10, 50, 100):
            if weight == 10 and not release_id:
                analyzing = await_view(
                    api, path,
                    lambda current: current.get("canaryWeight") == 10
                    and current.get("state") != "PAUSED",
                    "10% inline analysis",
                )
                rejected = api.request("POST", path + "/promote", expected=(200, 409))
                observations["analysisGate"] = rejected.get("code", "already-passed")
            view = wait_for_pause(api, path, weight)
            if weight == 10:
                api.request("POST", path + "/pause", expected=(200,))
                api.request("POST", path + "/resume", expected=(200,))
                view = api.request("GET", path)
            deadline = time.monotonic() + 240
            while not analysis_passed(view, weight) and time.monotonic() < deadline:
                time.sleep(1)
                view = api.request("GET", path)
            if not analysis_passed(view, weight):
                raise TimeoutError(f"{weight}% canary analysis did not pass: {view}")
            observations["windows"][str(weight)] = traffic.burst(100)
            promoted = api.request("POST", path + "/promote", expected=(200,))
            observations[f"after{weight}Promote"] = promoted.get("state")
        completed = await_view(
            api, path,
            lambda view: view.get("state") == "SUCCEEDED"
            and view.get("stableWeight") == 100 and view.get("canaryWeight") == 0,
            "successful v2 release",
        )
        observations["terminalState"] = completed.get("state")
        observations["terminalWeights"] = {
            "stable": completed.get("stableWeight"),
            "canary": completed.get("canaryWeight"),
        }
        observations["measurements"] = len(completed.get("measurements", []))
    finally:
        traffic.stop()
    return observations


def run_failing_release(api, cluster_id, name, hostname, traffic_url):
    release, path = create_release(
        api, cluster_id, name,
        "localhost/nanpanel-p5-app:v2", "localhost/nanpanel-p5-app:v3-broken",
        hostname, "P5 disposable automatic rollback acceptance",
    )
    started = api.request("POST", path + "/start", expected=(200,))
    traffic = TrafficPump(traffic_url, hostname)
    traffic.start()
    try:
        rolled_back = await_view(
            api, path,
            lambda view: view.get("state") == "ROLLED_BACK"
            and view.get("stableWeight") == 100 and view.get("canaryWeight") == 0,
            "automatic failed-canary rollback",
            timeout=300,
        )
        restored = traffic.burst(100)
        return {
            "releaseId": release["id"],
            "hostname": hostname,
            "initialState": started.get("state"),
            "terminalState": rolled_back.get("state"),
            "terminalWeights": {"stable": rolled_back.get("stableWeight"),
                                "canary": rolled_back.get("canaryWeight")},
            "measurements": len(rolled_back.get("measurements", [])),
            "restoredRouteRequests": restored,
        }
    finally:
        traffic.stop()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--api", default=os.environ.get(
        "NANPANEL_SMOKE_API", "http://127.0.0.1:18081"))
    parser.add_argument("--gateway-url", default=os.environ.get(
        "NANPANEL_SMOKE_GATEWAY", "http://127.0.0.1:18080/"))
    parser.add_argument("--kubeconfig", type=Path, default=Path(os.environ.get(
        "NANPANEL_SMOKE_KUBECONFIG", "/tmp/nanpanel-p5-kind.kubeconfig")))
    parser.add_argument("--kubectl-kubeconfig", type=Path,
                        help="local kubectl config when the imported endpoint uses a container alias")
    parser.add_argument("--context", default="kind-nanpanel-p5")
    parser.add_argument("--cluster-id", help="reuse a cluster already imported by this API")
    parser.add_argument("--resume-release-id", help="continue a saved healthy release")
    parser.add_argument("--import-cluster-only", action="store_true",
                        help="import the selected cluster and print its ID without starting a release")
    parser.add_argument("--ownership-conflict-probe", action="store_true",
                        help="verify a real Core rejection preserves an unowned Service")
    parser.add_argument("--existing-route-conflict-probe", action="store_true",
                        help="verify an existing HTTPRoute is preserved on hostname conflict")
    args = parser.parse_args()
    password = os.environ.get("NANPANEL_SMOKE_PASSWORD")
    if not password:
        parser.error("set NANPANEL_SMOKE_PASSWORD in the environment")
    kubeconfig = args.kubeconfig.read_text()
    api = Api(args.api, os.environ.get("NANPANEL_SMOKE_USERNAME", "admin"), password)
    suffix = str(int(time.time()))[-8:]
    if args.cluster_id:
        cluster = api.request("GET", f"/api/v1/kubernetes/clusters/{args.cluster_id}")
    else:
        cluster = api.request("POST", "/api/v1/kubernetes/clusters", {
            "name": f"nanpanel-p5-api-{suffix}",
            "kind": "KUBERNETES",
            "contextName": args.context,
            "kubeconfig": kubeconfig,
        }, expected=(201,))
    if args.import_cluster_only:
        print(json.dumps({
            "cluster": {"id": cluster["id"], "name": cluster["name"],
                        "healthStatus": cluster["healthStatus"]},
        }, indent=2, sort_keys=True))
        return
    if args.ownership_conflict_probe:
        result = run_ownership_conflict_probe(
            api, cluster["id"], args.kubectl_kubeconfig or args.kubeconfig, args.context
        )
        print(json.dumps({
            "cluster": {"id": cluster["id"], "name": cluster["name"],
                        "healthStatus": cluster["healthStatus"]},
            "ownershipConflict": result,
        }, indent=2, sort_keys=True))
        return
    if args.existing_route_conflict_probe:
        result = run_existing_route_conflict_probe(
            api, cluster["id"], args.kubectl_kubeconfig or args.kubeconfig, args.context
        )
        print(json.dumps({
            "cluster": {"id": cluster["id"], "name": cluster["name"],
                        "healthStatus": cluster["healthStatus"]},
            "existingRouteConflict": result,
        }, indent=2, sort_keys=True))
        return
    name = f"p5-api-release-{suffix}"
    healthy = run_healthy_release(
        api, cluster["id"], name, f"api-p5-{suffix}.example.test", args.gateway_url,
        args.resume_release_id,
    )
    failed = run_failing_release(
        api, cluster["id"], healthy["name"], healthy["hostname"], args.gateway_url
    )
    print(json.dumps({
        "cluster": {"id": cluster["id"], "name": cluster["name"],
                    "kind": cluster["kind"], "healthStatus": cluster["healthStatus"]},
        "healthyRelease": healthy,
        "automaticRollback": failed,
    }, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
