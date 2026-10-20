#!/usr/bin/env bash
set -euo pipefail

ssh_target="${NANPANEL_ISTIO_SSH_ALIAS:-k3s-1}"
expected_body='NANPANEL_ISTIO_MESH_READY'
remote() {
  ssh -o BatchMode=yes -o ConnectTimeout=10 "$ssh_target" \
    "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl $*"
}

remote '-n istio-system rollout status deployment/istiod --timeout=120s' >/dev/null
remote '-n nanpanel-mesh rollout status deployment/mesh-http --timeout=180s' >/dev/null
remote '-n nanpanel-mesh rollout status deployment/mesh-client --timeout=180s' >/dev/null
remote '-n nanpanel-mesh rollout status deployment/mesh-denied-client --timeout=180s' >/dev/null
remote '-n nanpanel-mesh rollout status deployment/mesh-probe-target --timeout=180s' >/dev/null
pod_json="$(remote '-n nanpanel-mesh get pods -o json')"
proxy_pods="$(python3 -c 'import json,sys
pods=json.load(sys.stdin)["items"]
active=[pod for pod in pods if not pod["metadata"].get("deletionTimestamp")]
apps={pod.get("metadata", {}).get("labels", {}).get("app"):pod for pod in active}
expected={"mesh-http", "mesh-client", "mesh-denied-client", "mesh-probe-target"}
if len(active) != 4 or set(apps) != expected:
    raise SystemExit(f"expected four active fixture workloads, found {sorted(apps)}")
has_proxy=lambda pod:any(container["name"] == "istio-proxy" for container in pod["spec"].get("containers", []) + pod["spec"].get("initContainers", []))
proxy_count=sum(has_proxy(pod) for pod in active)
if proxy_count != 3 or has_proxy(apps["mesh-probe-target"]):
    raise SystemExit(f"expected three sidecars and one non-injected probe target, found {proxy_count} sidecars")
print(proxy_count)' <<<"$pod_json")"
if [[ "$proxy_pods" -ne 3 ]]; then
  echo "Expected three injected workloads and one non-injected probe target; found ${proxy_pods} injected." >&2
  printf '%s\n' "$pod_json" | python3 -c 'import json,sys
for pod in json.load(sys.stdin)["items"]:
    print(pod["metadata"]["name"], pod["metadata"].get("deletionTimestamp", "active"), [container["name"] for container in pod["spec"].get("containers", []) + pod["spec"].get("initContainers", [])])' >&2
  exit 1
fi

service_json="$(remote '-n nanpanel-mesh get service mesh-http -o json')"
endpoint_slices_json="$(remote '-n nanpanel-mesh get endpointslice -l kubernetes.io/service-name=mesh-http -o json')"
discovery="$(python3 -c 'import json,sys
service=json.loads(sys.argv[1])
slices=json.loads(sys.argv[2]).get("items", [])
pods=json.loads(sys.argv[3]).get("items", [])
spec=service.get("spec", {})
if service.get("metadata", {}).get("name") != "mesh-http":
    raise SystemExit("unexpected Service identity")
ports=spec.get("ports", [])
if not any(port.get("port") == 80 and port.get("protocol", "TCP") == "TCP" and port.get("targetPort") == "http" for port in ports):
    raise SystemExit("mesh-http Service does not map TCP/80 to the named http target")
selector=spec.get("selector", {})
if not selector:
    raise SystemExit("mesh-http Service has no workload selector")
pods_by_name={pod.get("metadata", {}).get("name"):pod for pod in pods}
ready=0
for endpoint_slice in slices:
    metadata=endpoint_slice.get("metadata", {})
    if metadata.get("labels", {}).get("kubernetes.io/service-name") != "mesh-http":
        raise SystemExit("EndpointSlice is not owned by mesh-http")
    endpoint_ports=endpoint_slice.get("ports", [])
    if not any(port.get("port") == 8080 and port.get("protocol", "TCP") == "TCP" for port in endpoint_ports):
        continue
    for endpoint in endpoint_slice.get("endpoints", []):
        if endpoint.get("conditions", {}).get("ready") is False:
            continue
        target=endpoint.get("targetRef", {})
        if target.get("kind") != "Pod" or not target.get("name"):
            raise SystemExit("ready EndpointSlice entry has no Pod target identity")
        pod=pods_by_name.get(target["name"])
        if pod is None or pod.get("status", {}).get("phase") != "Running":
            raise SystemExit("ready EndpointSlice target Pod is not running")
        labels=pod.get("metadata", {}).get("labels", {})
        if any(labels.get(key) != value for key,value in selector.items()):
            raise SystemExit("EndpointSlice Pod does not match the Service selector")
        pod_ips={pod.get("status", {}).get("podIP")}
        pod_ips.update(item.get("ip") for item in pod.get("status", {}).get("podIPs", []))
        if not set(endpoint.get("addresses", [])) & pod_ips:
            raise SystemExit("EndpointSlice addresses do not match the target Pod")
        ready += 1
if ready == 0:
    raise SystemExit("mesh-http has no ready Pod endpoint on TCP/8080")
print(ready)' "$service_json" "$endpoint_slices_json" "$pod_json")"

probe_service_json="$(remote '-n nanpanel-mesh get service mesh-probe-target -o json')"
probe_slices_json="$(remote '-n nanpanel-mesh get endpointslice -l kubernetes.io/service-name=mesh-probe-target -o json')"
probe_endpoint_count="$(python3 -c 'import ipaddress,json,sys
service=json.loads(sys.argv[1])
slices=json.loads(sys.argv[2]).get("items", [])
pods=json.loads(sys.argv[3]).get("items", [])
if service.get("metadata", {}).get("name") != "mesh-probe-target":
    raise SystemExit("unexpected raw TCP probe Service identity")
spec=service.get("spec", {})
selector=spec.get("selector", {})
if not selector or not any(port.get("port") == 8080 and port.get("protocol", "TCP") == "TCP" and port.get("targetPort") == "http" for port in spec.get("ports", [])):
    raise SystemExit("probe target Service must expose TCP/8080 to the named http port")
pods_by_name={pod.get("metadata", {}).get("name"):pod for pod in pods}
ready=0
for endpoint_slice in slices:
    metadata=endpoint_slice.get("metadata", {})
    if metadata.get("labels", {}).get("kubernetes.io/service-name") != "mesh-probe-target":
        raise SystemExit("EndpointSlice is not owned by mesh-probe-target")
    if not any(port.get("port") == 8080 and port.get("protocol", "TCP") == "TCP" for port in endpoint_slice.get("ports", [])):
        continue
    for endpoint in endpoint_slice.get("endpoints", []):
        if endpoint.get("conditions", {}).get("ready") is False:
            continue
        target=endpoint.get("targetRef", {})
        pod=pods_by_name.get(target.get("name"))
        if target.get("kind") != "Pod" or pod is None or pod.get("status", {}).get("phase") != "Running":
            raise SystemExit("ready probe endpoint has no running Pod identity")
        labels=pod.get("metadata", {}).get("labels", {})
        if any(labels.get(key) != value for key,value in selector.items()):
            raise SystemExit("probe endpoint Pod does not match the Service selector")
        containers=pod.get("spec", {}).get("containers", []) + pod.get("spec", {}).get("initContainers", [])
        if any(container.get("name") == "istio-proxy" for container in containers):
            raise SystemExit("raw TCP probe target unexpectedly has an Istio sidecar")
        pod_ips={pod.get("status", {}).get("podIP")}
        pod_ips.update(item.get("ip") for item in pod.get("status", {}).get("podIPs", []))
        addresses=set(endpoint.get("addresses", []))
        if not addresses & pod_ips:
            raise SystemExit("probe EndpointSlice address does not match its Pod")
        for address in addresses:
            parsed=ipaddress.ip_address(address)
            if not parsed.is_private or parsed.is_loopback or parsed.is_link_local or parsed.is_multicast:
                raise SystemExit("probe target endpoint is not a private unicast address")
        ready += 1
if ready == 0:
    raise SystemExit("mesh-probe-target has no ready private endpoint on TCP/8080")
print(ready)' "$probe_service_json" "$probe_slices_json" "$pod_json")"

body="$(remote '-n nanpanel-mesh exec deployment/mesh-client -c curl -- curl --silent --show-error --max-time 5 http://mesh-http/' | tr -d '\r\n')"
if [[ "$body" != "$expected_body" ]]; then
  echo "Allowed in-mesh request returned an unexpected body: ${body}" >&2
  exit 1
fi

status="$(remote "-n nanpanel-mesh exec deployment/mesh-denied-client -c curl -- sh -ec 'curl --silent --output /dev/null --write-out \"%{http_code}\" --max-time 5 http://mesh-http/'" | tr -d '\r\n')"
if [[ "$status" != '403' ]]; then
  echo "Unauthorized service-account request returned HTTP ${status}, expected 403." >&2
  exit 1
fi

printf 'PASS: mesh-http maps to %s ready TCP/8080 endpoint(s); 3/4 workloads have Istio sidecars; mesh-probe-target maps to %s ready private TCP/8080 endpoint(s) without a sidecar; allowed strict-mTLS HTTP probe returned the fixture body; denied identity received HTTP 403.\n' "$discovery" "$probe_endpoint_count"
