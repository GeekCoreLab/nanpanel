#!/usr/bin/env bash
set -Eeuo pipefail

[[ ${P4_DISPOSABLE:-} == yes && ${P4_TARGET_K3S:-} == yes ]] || {
    printf 'Require P4_DISPOSABLE=yes and P4_TARGET_K3S=yes.\n' >&2; exit 2;
}
core_url=${CORE_URL:-http://127.0.0.1:18081}
case "$core_url" in http://127.0.0.1:*|http://localhost:*) ;; *) exit 2 ;; esac
origin=${P4_ORIGIN:-http://127.0.0.1:5173}
ssh_alias=${P4_K3S_SSH_ALIAS:-k3s-1}
endpoint=${P4_K3S_API_ENDPOINT:?Set reachable target K3s HTTPS endpoint}
username=${NANPANEL_TEST_USERNAME:-admin}
password=${NANPANEL_TEST_PASSWORD:?Set disposable Core bootstrap password}
next_password=${P4_NEXT_PASSWORD:-P4-k3s-target-rotated-2026}
namespace="nanpanel-p4-$(date +%s)-$RANDOM"
cluster_role="${namespace}-discovery"
cluster_binding="${namespace}-discovery-binding"
tmp=$(mktemp -d); chmod 700 "$tmp"
created_namespace= created_cluster_role= created_cluster_binding=
cleanup() {
    if [[ -n $created_cluster_binding ]]; then ssh -o BatchMode=yes "$ssh_alias" kubectl delete clusterrolebinding "$cluster_binding" --ignore-not-found >/dev/null 2>&1 || true; fi
    if [[ -n $created_cluster_role ]]; then ssh -o BatchMode=yes "$ssh_alias" kubectl delete clusterrole "$cluster_role" --ignore-not-found >/dev/null 2>&1 || true; fi
    if [[ -n $created_namespace ]]; then ssh -o BatchMode=yes "$ssh_alias" kubectl delete namespace "$namespace" --wait=false --ignore-not-found >/dev/null 2>&1 || true; fi
    rm -rf "$tmp"
}
trap cleanup EXIT
case "$endpoint" in https://*) ;; *) printf 'Target API endpoint must use HTTPS.\n' >&2; exit 2 ;; esac

# Target changes are limited to temporary discovery RBAC and a dedicated test namespace.
ssh -o BatchMode=yes "$ssh_alias" kubectl create namespace "$namespace" >/dev/null
created_namespace=yes
ssh -o BatchMode=yes "$ssh_alias" kubectl -n "$namespace" create serviceaccount p4-importer >/dev/null
ssh -o BatchMode=yes "$ssh_alias" kubectl create clusterrole "$cluster_role" \
    --verb=get,list --resource=nodes,namespaces >/dev/null
created_cluster_role=yes
ssh -o BatchMode=yes "$ssh_alias" kubectl create clusterrolebinding "$cluster_binding" \
    --clusterrole="$cluster_role" --serviceaccount="$namespace:p4-importer" >/dev/null
created_cluster_binding=yes
ssh -o BatchMode=yes "$ssh_alias" kubectl -n "$namespace" create role p4-deployments \
    --verb=get,list,create,update,patch,delete --resource=deployments,deployments/scale >/dev/null
ssh -o BatchMode=yes "$ssh_alias" kubectl -n "$namespace" create role p4-topology-read \
    --verb=get,list --resource=pods,services,ingresses >/dev/null
ssh -o BatchMode=yes "$ssh_alias" kubectl -n "$namespace" create rolebinding p4-deployments \
    --role=p4-deployments --serviceaccount="$namespace:p4-importer" >/dev/null
ssh -o BatchMode=yes "$ssh_alias" kubectl -n "$namespace" create rolebinding p4-topology-read \
    --role=p4-topology-read --serviceaccount="$namespace:p4-importer" >/dev/null
ssh -o BatchMode=yes "$ssh_alias" kubectl -n "$namespace" create token p4-importer --duration=15m > "$tmp/token"
chmod 600 "$tmp/token"
ca_data=$(ssh -o BatchMode=yes "$ssh_alias" 'kubectl config view --raw --flatten --minify -o json' |
    python3 -c 'import json,sys; print(json.load(sys.stdin)["clusters"][0]["cluster"]["certificate-authority-data"])')
python3 - "$tmp/kubeconfig.json" "$namespace" "$endpoint" "$ca_data" "$tmp/token" <<'PY'
import json, sys
path, namespace, endpoint, ca_data, token_path = sys.argv[1:]
token = open(token_path, encoding="utf-8").read().strip()
config = {"apiVersion":"v1","kind":"Config","preferences":{},
 "clusters":[{"name":"target-k3s","cluster":{"server":endpoint,"certificate-authority-data":ca_data}}],
 "contexts":[{"name":"target-k3s-context","context":{"cluster":"target-k3s","user":"p4-importer","namespace":namespace}}],
 "current-context":"target-k3s-context","users":[{"name":"p4-importer","user":{"token":token}}]}
with open(path,"w",encoding="utf-8") as output: json.dump(config,output,separators=(",",":"))
PY
chmod 600 "$tmp/kubeconfig.json"
kubectl --kubeconfig="$tmp/kubeconfig.json" get nodes -o name > "$tmp/nodes"
node_count=$(wc -l < "$tmp/nodes" | tr -d ' ')
[[ $node_count -gt 0 ]] || { printf 'Target K3s returned no Nodes.\n' >&2; exit 1; }

curl -fsS -H "Origin: $origin" -c "$tmp/cookies" "$core_url/api/v1/auth/csrf" > "$tmp/csrf.json"
csrf=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["csrfToken"])' "$tmp/csrf.json")
python3 - "$tmp/login.json" "$username" "$password" <<'PY'
import json,sys
json.dump({"username":sys.argv[2],"password":sys.argv[3]},open(sys.argv[1],"w",encoding="utf-8"))
PY
curl -fsS -H "Origin: $origin" -H 'Content-Type: application/json' -H "X-CSRF-Token: $csrf" \
    -b "$tmp/cookies" -c "$tmp/cookies" --data-binary "@$tmp/login.json" "$core_url/api/v1/auth/login" > "$tmp/login.json.out"
csrf=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["csrfToken"])' "$tmp/login.json.out")
python3 - "$tmp/password.json" "$password" "$next_password" <<'PY'
import json,sys
json.dump({"currentPassword":sys.argv[2],"newPassword":sys.argv[3]},open(sys.argv[1],"w",encoding="utf-8"))
PY
curl -fsS -X PUT -H "Origin: $origin" -H 'Content-Type: application/json' -H "X-CSRF-Token: $csrf" \
    -b "$tmp/cookies" -c "$tmp/cookies" --data-binary "@$tmp/password.json" "$core_url/api/v1/auth/password" > /dev/null
curl -fsS -H "Origin: $origin" -c "$tmp/cookies" "$core_url/api/v1/auth/csrf" > "$tmp/csrf.json"
csrf=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["csrfToken"])' "$tmp/csrf.json")
python3 - "$tmp/login.json" "$username" "$next_password" <<'PY'
import json,sys
json.dump({"username":sys.argv[2],"password":sys.argv[3]},open(sys.argv[1],"w",encoding="utf-8"))
PY
curl -fsS -H "Origin: $origin" -H 'Content-Type: application/json' -H "X-CSRF-Token: $csrf" \
    -b "$tmp/cookies" -c "$tmp/cookies" --data-binary "@$tmp/login.json" "$core_url/api/v1/auth/login" > "$tmp/login.json.out"
csrf=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["csrfToken"])' "$tmp/login.json.out")

python3 - "$tmp/import.json" "$tmp/kubeconfig.json" "$namespace" <<'PY'
import json,sys
config=json.load(open(sys.argv[2],encoding="utf-8"))
json.dump({"name":"p4-target-k3s-"+sys.argv[3],"kind":"K3S","contextName":"target-k3s-context",
 "kubeconfig":json.dumps(config,separators=(",",":"))},open(sys.argv[1],"w",encoding="utf-8"))
PY
chmod 600 "$tmp/import.json"
if ! curl -fsS -H "Origin: $origin" -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
    -b "$tmp/cookies" -c "$tmp/cookies" --data-binary "@$tmp/import.json" \
    "$core_url/api/v1/kubernetes/clusters" > "$tmp/cluster.json"; then cat "$tmp/cluster.json" >&2; exit 1; fi
cluster_id=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["id"])' "$tmp/cluster.json")
python3 - "$tmp/cluster.json" "$endpoint" "$node_count" <<'PY'
import json,sys
c=json.load(open(sys.argv[1],encoding="utf-8"))
if c["kind"]!="K3S" or c["endpoint"]!=sys.argv[2] or c["healthStatus"]!="HEALTHY" or c["nodeCount"]!=int(sys.argv[3]):
 raise SystemExit("Imported target K3s summary did not match live API state")
if "kubeconfig" in c or "token" in json.dumps(c).lower(): raise SystemExit("Cluster credential leaked in import response")
PY
curl -fsS -H "Origin: $origin" -b "$tmp/cookies" \
    "$core_url/api/v1/kubernetes/clusters/$cluster_id/nodes" > "$tmp/nodes.json"
python3 - "$tmp/nodes.json" "$node_count" <<'PY'
import json,sys
nodes=json.load(open(sys.argv[1],encoding="utf-8"))["items"]
if len(nodes)!=int(sys.argv[2]) or not nodes or not all(n["nodePresent"] for n in nodes):
 raise SystemExit("Core Node inventory differs from target K3s")
PY
node_uid=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["items"][0]["nodeUid"])' "$tmp/nodes.json")
printf '%s' '{"hostname":"p4-k3s-target-mapped-host"}' > "$tmp/enrollment.json"
curl -fsS -H "Origin: $origin" -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
    -b "$tmp/cookies" -c "$tmp/cookies" --data-binary "@$tmp/enrollment.json" \
    "$core_url/api/v1/agent-enrollments" > "$tmp/enrollment-response.json"
host_id=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["hostId"])' "$tmp/enrollment-response.json")
printf '{"hostId":"%s"}' "$host_id" > "$tmp/mapping.json"
map_status=$(curl -sS -o "$tmp/mapping-response.json" -w '%{http_code}' -X PUT \
    -H "Origin: $origin" -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
    -b "$tmp/cookies" -c "$tmp/cookies" --data-binary "@$tmp/mapping.json" \
    "$core_url/api/v1/kubernetes/clusters/$cluster_id/nodes/$node_uid/host")
[[ $map_status == 200 ]] || { printf 'Target K3s Node mapping HTTP %s\n' "$map_status" >&2; cat "$tmp/mapping-response.json" >&2; exit 1; }
python3 - "$tmp/mapping-response.json" "$host_id" <<'PY'
import json,sys
if json.load(open(sys.argv[1],encoding="utf-8"))["hostId"]!=sys.argv[2]:
 raise SystemExit("Target K3s Node mapped to a different Host asset")
PY

path="$core_url/api/v1/kubernetes/clusters/$cluster_id/namespaces/$namespace/deployments"
printf '%s' '{"name":"p4-target-smoke","image":"registry.k8s.io/pause:3.10","replicas":1,"containerPort":8080}' > "$tmp/deployment.json"
status=$(curl -sS -o "$tmp/deployment-response.json" -w '%{http_code}' -H "Origin: $origin" \
    -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' -b "$tmp/cookies" -c "$tmp/cookies" \
    --data-binary "@$tmp/deployment.json" "$path")
[[ $status == 201 ]] || { printf 'Deployment create HTTP %s\n' "$status" >&2; cat "$tmp/deployment-response.json" >&2; exit 1; }
ssh -o BatchMode=yes "$ssh_alias" kubectl -n "$namespace" expose deployment p4-target-smoke \
    --name=p4-target-smoke --port=8080 --target-port=8080 >/dev/null
python3 - "$tmp/ingress.json" "$namespace" <<'PY'
import json,sys
path, namespace=sys.argv[1:]
ingress={"apiVersion":"networking.k8s.io/v1","kind":"Ingress",
 "metadata":{"name":"p4-target-smoke","namespace":namespace},
 "spec":{"ingressClassName":"nanpanel-p4-no-controller","rules":[{"host":namespace+".invalid",
  "http":{"paths":[{"path":"/p4","pathType":"Prefix","backend":{"service":{"name":"p4-target-smoke","port":{"number":8080}}}}]}}]}}
with open(path,"w",encoding="utf-8") as output: json.dump(ingress,output,separators=(",",":"))
PY
ssh -o BatchMode=yes "$ssh_alias" kubectl apply -f - < "$tmp/ingress.json" >/dev/null
status=$(curl -sS -o "$tmp/list.json" -w '%{http_code}' -H "Origin: $origin" -b "$tmp/cookies" "$path")
[[ $status == 200 ]] || { printf 'Deployment list HTTP %s\n' "$status" >&2; exit 1; }
python3 - "$tmp/list.json" <<'PY'
import json,sys
if not any(i["name"]=="p4-target-smoke" for i in json.load(open(sys.argv[1],encoding="utf-8"))["items"]):
 raise SystemExit("Managed Deployment missing from list")
PY
topology_namespaces="$core_url/api/v1/kubernetes/clusters/$cluster_id/topology/namespaces"
curl -fsS -H "Origin: $origin" -b "$tmp/cookies" "$topology_namespaces" > "$tmp/topology-namespaces.json"
python3 - "$tmp/topology-namespaces.json" "$namespace" <<'PY'
import json,sys
snapshot=json.load(open(sys.argv[1],encoding="utf-8"))
if not any(item["name"]==sys.argv[2] for item in snapshot["namespaces"]):
 raise SystemExit("Target Namespace missing from topology discovery")
PY
curl -fsS -H "Origin: $origin" -b "$tmp/cookies" \
    "$topology_namespaces/$namespace" > "$tmp/topology-detail.json"
python3 - "$tmp/topology-detail.json" "$namespace" <<'PY'
import json,sys
detail=json.load(open(sys.argv[1],encoding="utf-8"))
pods=[p for p in detail["pods"] if p["name"].startswith("p4-target-smoke-")]
if detail["namespace"]!=sys.argv[2] or not pods:
 raise SystemExit("Target topology detail did not expose the test Deployment Pod")
containers=pods[0]["containers"]
if not containers or not isinstance(containers[0]["ready"],bool) or not isinstance(containers[0]["restartCount"],int):
 raise SystemExit("Target topology detail omitted container readiness or restart status")
if not any(port["containerPort"]==8080 for port in containers[0]["ports"]):
 raise SystemExit("Target topology detail omitted the Pod container port")
if not any(s["name"]=="p4-target-smoke" for s in detail["services"]):
 raise SystemExit("Target topology detail omitted the temporary Service")
if not any(i["name"]=="p4-target-smoke" and i["routes"] for i in detail["ingresses"]):
 raise SystemExit("Target topology detail omitted the temporary Ingress route")
PY
ssh -o BatchMode=yes "$ssh_alias" kubectl -n "$namespace" delete rolebinding p4-topology-read >/dev/null
status=$(curl -sS -o "$tmp/denied.json" -w '%{http_code}' -H "Origin: $origin" -b "$tmp/cookies" \
    "$topology_namespaces/$namespace")
[[ $status == 403 ]] || { printf 'Topology read without namespace RBAC returned HTTP %s\n' "$status" >&2; exit 1; }
curl -fsS -H "Origin: $origin" -b "$tmp/cookies" \
    "$core_url/api/v1/kubernetes/clusters/$cluster_id" > "$tmp/unavailable-cluster.json"
python3 - "$tmp/unavailable-cluster.json" <<'PY'
import json,sys
cluster=json.load(open(sys.argv[1],encoding="utf-8"))
if cluster["healthStatus"]!="UNAVAILABLE" or cluster["lastErrorCode"]!="RBAC_DENIED" or not cluster["observationsStale"]:
 raise SystemExit("RBAC denial did not mark target K3s observations unavailable/stale")
PY
status=$(curl -sS -o "$tmp/hosts.json" -w '%{http_code}' -H "Origin: $origin" -b "$tmp/cookies" \
    "$core_url/api/v1/hosts?limit=1")
[[ $status == 200 ]] || { printf 'Host inventory during K3s RBAC denial returned HTTP %s\n' "$status" >&2; exit 1; }
status=$(curl -sS -o "$tmp/scale.json" -w '%{http_code}' -X PUT -H "Origin: $origin" \
    -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' -b "$tmp/cookies" -c "$tmp/cookies" \
    --data '{"replicas":2}' "$path/p4-target-smoke/scale")
[[ $status == 200 ]] || { printf 'Deployment scale HTTP %s\n' "$status" >&2; exit 1; }
status=$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE -H "Origin: $origin" \
    -H "X-CSRF-Token: $csrf" -b "$tmp/cookies" -c "$tmp/cookies" "$path/p4-target-smoke")
[[ $status == 204 ]] || { printf 'Deployment delete HTTP %s\n' "$status" >&2; exit 1; }
printf 'PASS: target K3s %s imported (%s Nodes); UID mapping, topology resource reads, scoped Deployment CRUD/scale, and RBAC-denial stale-state handling passed in %s.\n' \
    "$endpoint" "$node_count" "$namespace"
