#!/usr/bin/env bash
set -Eeuo pipefail

if [[ ${P4_DISPOSABLE:-} != yes ]]; then
    printf 'Set P4_DISPOSABLE=yes only for an isolated local Core database.\n' >&2
    exit 2
fi

core_url=${CORE_URL:-http://127.0.0.1:8080}
case "$core_url" in
    http://127.0.0.1:*|http://localhost:*) ;;
    *) printf 'This fixture accepts only a loopback Core URL.\n' >&2; exit 2 ;;
esac
origin=${P4_ORIGIN:-http://127.0.0.1:5173}
username=${NANPANEL_TEST_USERNAME:-admin}
password=${NANPANEL_TEST_PASSWORD:?Set NANPANEL_TEST_PASSWORD for the disposable Core}
next_password=${P4_NEXT_PASSWORD:-P4-kind-import-temporary-2026}
context_a=${P4_CONTEXT_A:-kind-p4-a}
context_b=${P4_CONTEXT_B:-kind-p4-b}
cluster_a_name=${P4_CLUSTER_A_NAME:-p4-kind-a}
cluster_b_name=${P4_CLUSTER_B_NAME:-p4-kind-b}
fixture_name=${P4_FIXTURE_NAME:-Kind}
expected_node_count=${P4_EXPECTED_NODE_COUNT:-1}
test_node_mapping=${P4_TEST_NODE_MAPPING:-no}
[[ $expected_node_count =~ ^[1-9][0-9]{0,2}$ ]] || {
    printf 'P4_EXPECTED_NODE_COUNT must be an integer from 1 through 999.\n' >&2; exit 2;
}

command -v curl >/dev/null
command -v kubectl >/dev/null
command -v python3 >/dev/null
tmp=$(mktemp -d)
chmod 700 "$tmp"
denied_account=
restricted_binding=
restricted_role=
unmanaged_deployment=
mapping_node_a=
mapping_node_b=
cleanup() {
    if [[ -n $unmanaged_deployment ]]; then kubectl --context="$context_a" -n default delete deployment "$unmanaged_deployment" --ignore-not-found >/dev/null 2>&1 || true; fi
    if [[ -n $restricted_binding ]]; then kubectl --context="$context_a" delete clusterrolebinding "$restricted_binding" --ignore-not-found >/dev/null 2>&1 || true; fi
    if [[ -n $restricted_role ]]; then kubectl --context="$context_a" delete clusterrole "$restricted_role" --ignore-not-found >/dev/null 2>&1 || true; fi
    if [[ -n $denied_account ]]; then kubectl --context="$context_a" -n default delete serviceaccount "$denied_account" --ignore-not-found >/dev/null 2>&1 || true; fi
    if [[ -n $mapping_node_a ]]; then kubectl --context="$context_a" delete node "$mapping_node_a" --ignore-not-found >/dev/null 2>&1 || true; fi
    if [[ -n $mapping_node_b ]]; then kubectl --context="$context_b" delete node "$mapping_node_b" --ignore-not-found >/dev/null 2>&1 || true; fi
    rm -rf "$tmp"
}
trap cleanup EXIT

if [[ $test_node_mapping == yes ]]; then
    mapping_node_a="p4-map-a-$(date +%s)"
    mapping_node_b="p4-map-b-$(date +%s)"
    for entry in "$context_a:$mapping_node_a" "$context_b:$mapping_node_b"; do
        context=${entry%%:*}
        node_name=${entry#*:}
        printf '{"apiVersion":"v1","kind":"Node","metadata":{"name":"%s"}}\n' "$node_name" |
            kubectl --context="$context" apply -f - >/dev/null
        kubectl --context="$context" patch node "$node_name" --subresource=status \
            --type=merge -p '{"status":{"nodeInfo":{"kubeletVersion":"p4-fixture"}}}' >/dev/null
    done
fi

kubectl config view --raw --flatten --minify --context="$context_a" -o json > "$tmp/a.json"
kubectl config view --raw --flatten --minify --context="$context_b" -o json > "$tmp/b.json"
if [[ -n ${P4_API_ENDPOINT_A:-} || -n ${P4_API_ENDPOINT_B:-} ]]; then
    python3 - "$tmp/a.json" "$tmp/b.json" "${P4_API_ENDPOINT_A:-}" "${P4_API_ENDPOINT_B:-}" <<'PY'
import json
import sys

for path, endpoint in zip(sys.argv[1:3], sys.argv[3:5]):
    if not endpoint:
        continue
    with open(path, encoding="utf-8") as source:
        config = json.load(source)
    config["clusters"][0]["cluster"]["server"] = endpoint
    with open(path, "w", encoding="utf-8") as output:
        json.dump(config, output)
PY
fi
chmod 600 "$tmp/a.json" "$tmp/b.json"

python3 - "$tmp/a.json" "$tmp/b.json" <<'PY'
import json
import sys

configs = [json.load(open(path, encoding="utf-8")) for path in sys.argv[1:]]
servers = [config["clusters"][0]["cluster"]["server"] for config in configs]
if len(set(servers)) != 2:
    raise SystemExit("P4 fixture contexts must address two distinct Kubernetes APIs")
PY

curl --fail-with-body --silent --show-error \
    -H "Origin: $origin" -c "$tmp/cookies" \
    "$core_url/api/v1/auth/csrf" > "$tmp/anonymous.json"
csrf=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["csrfToken"])' "$tmp/anonymous.json")
python3 - "$tmp/login.json" "$username" <<'PY'
import json
import os
import sys

with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump({"username": sys.argv[2], "password": os.environ["NANPANEL_TEST_PASSWORD"]}, output)
PY
chmod 600 "$tmp/login.json"
curl --fail-with-body --silent --show-error \
    -H "Origin: $origin" -H 'Content-Type: application/json' \
    -H "X-CSRF-Token: $csrf" -b "$tmp/cookies" -c "$tmp/cookies" \
    --data-binary "@$tmp/login.json" "$core_url/api/v1/auth/login" > "$tmp/login-response.json"
csrf=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["csrfToken"])' "$tmp/login-response.json")
printf 'Authenticated disposable Core.\n' >&2

# Fresh disposable databases require changing the bootstrap password. Password change
# revokes every session, so log in again before importing clusters.
python3 - "$tmp/password.json" <<'PY'
import json
import os
import sys

with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump({"currentPassword": os.environ["NANPANEL_TEST_PASSWORD"],
               "newPassword": os.environ.get("P4_NEXT_PASSWORD", "P4-kind-import-temporary-2026")}, output)
PY
chmod 600 "$tmp/password.json"
if ! curl --fail-with-body --silent --show-error -X PUT \
    -H "Origin: $origin" -H 'Content-Type: application/json' \
    -H "X-CSRF-Token: $csrf" -b "$tmp/cookies" -c "$tmp/cookies" \
    --data-binary "@$tmp/password.json" "$core_url/api/v1/auth/password" > "$tmp/password-response.json"; then
    cat "$tmp/password-response.json" >&2
    exit 1
fi
curl --fail-with-body --silent --show-error \
    -H "Origin: $origin" -c "$tmp/cookies" \
    "$core_url/api/v1/auth/csrf" > "$tmp/anonymous.json"
csrf=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["csrfToken"])' "$tmp/anonymous.json")
python3 - "$tmp/login.json" <<'PY'
import json
import os
import sys

with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump({"username": os.environ.get("NANPANEL_TEST_USERNAME", "admin"),
               "password": os.environ.get("P4_NEXT_PASSWORD", "P4-kind-import-temporary-2026")}, output)
PY
curl --fail-with-body --silent --show-error \
    -H "Origin: $origin" -H 'Content-Type: application/json' \
    -H "X-CSRF-Token: $csrf" -b "$tmp/cookies" -c "$tmp/cookies" \
    --data-binary "@$tmp/login.json" "$core_url/api/v1/auth/login" > "$tmp/login-response.json"
csrf=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["csrfToken"])' "$tmp/login-response.json")
printf 'Bootstrap password changed; disposable admin session renewed.\n' >&2

make_import() {
    local config=$1 name=$2 context=$3 output=$4
    python3 - "$config" "$name" "$context" "$output" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    kubeconfig = json.load(source)
with open(sys.argv[4], "w", encoding="utf-8") as output:
    json.dump({"name": sys.argv[2], "kind": "K3S", "contextName": sys.argv[3],
               "kubeconfig": json.dumps(kubeconfig, separators=(",", ":"))}, output)
PY
    chmod 600 "$output"
}

post_import() {
    local body=$1 output=$2
    if ! curl --fail-with-body --silent --show-error \
        -H "Origin: $origin" -H "X-CSRF-Token: $csrf" \
        -H 'Content-Type: application/json' -b "$tmp/cookies" -c "$tmp/cookies" \
        --data-binary "@$body" "$core_url/api/v1/kubernetes/clusters" > "$output"; then
        cat "$output" >&2
        exit 1
    fi
}

make_import "$tmp/a.json" "$cluster_a_name" "$context_a" "$tmp/import-a.json"
post_import "$tmp/import-a.json" "$tmp/import-a-response.json"
printf 'Imported context %s.\n' "$context_a" >&2
make_import "$tmp/b.json" "$cluster_b_name" "$context_b" "$tmp/import-b.json"
post_import "$tmp/import-b.json" "$tmp/import-b-response.json"
printf 'Imported context %s.\n' "$context_b" >&2

python3 - "$tmp/a.json" "$tmp/b.json" "$tmp/import-a-response.json" \
    "$tmp/import-b-response.json" "$context_a" "$context_b" "$expected_node_count" <<'PY'
import json
import sys

configs = [json.load(open(path, encoding="utf-8")) for path in sys.argv[1:3]]
responses = [json.load(open(path, encoding="utf-8")) for path in sys.argv[3:5]]
servers = [config["clusters"][0]["cluster"]["server"] for config in configs]
if [response["contextName"] for response in responses] != sys.argv[5:7]:
    raise SystemExit("Imported clusters did not retain their explicit contexts")
if [response["endpoint"] for response in responses] != servers or servers[0] == servers[1]:
    raise SystemExit("Imported endpoints do not match the two independent API servers")
if any(response["healthStatus"] != "HEALTHY" or response["nodeCount"] != int(sys.argv[7])
       for response in responses):
    raise SystemExit("Both imported clusters must return the configured healthy Node snapshot")
PY

cluster_a_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$tmp/import-a-response.json")
cluster_b_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$tmp/import-b-response.json")

if [[ $test_node_mapping == yes ]]; then
    curl --fail-with-body --silent --show-error -H "Origin: $origin" -b "$tmp/cookies" \
        "$core_url/api/v1/kubernetes/clusters/$cluster_a_id/nodes" > "$tmp/mapping-nodes-a.json"
    curl --fail-with-body --silent --show-error -H "Origin: $origin" -b "$tmp/cookies" \
        "$core_url/api/v1/kubernetes/clusters/$cluster_b_id/nodes" > "$tmp/mapping-nodes-b.json"
    mapping_uid_a=$(python3 - "$tmp/mapping-nodes-a.json" "$mapping_node_a" <<'PY'
import json,sys
nodes=json.load(open(sys.argv[1],encoding="utf-8"))["items"]
matches=[node["nodeUid"] for node in nodes if node["nodeName"]==sys.argv[2] and node["nodePresent"]]
if len(matches)!=1: raise SystemExit("Synthetic first-cluster Node was not reconciled by UID")
print(matches[0])
PY
)
    mapping_uid_b=$(python3 - "$tmp/mapping-nodes-b.json" "$mapping_node_b" <<'PY'
import json,sys
nodes=json.load(open(sys.argv[1],encoding="utf-8"))["items"]
matches=[node["nodeUid"] for node in nodes if node["nodeName"]==sys.argv[2] and node["nodePresent"]]
if len(matches)!=1: raise SystemExit("Synthetic second-cluster Node was not reconciled by UID")
print(matches[0])
PY
)
    mapping_uid_a_other=$(python3 - "$tmp/mapping-nodes-a.json" "$mapping_uid_a" <<'PY'
import json,sys
nodes=json.load(open(sys.argv[1],encoding="utf-8"))["items"]
matches=[node["nodeUid"] for node in nodes if node["nodePresent"] and node["nodeUid"]!=sys.argv[2]]
if len(matches)!=1: raise SystemExit("Expected one other Node in the first cluster")
print(matches[0])
PY
)
    printf '%s' '{"hostname":"p4-k3s-mapped-host"}' > "$tmp/mapping-enrollment.json"
    curl --fail-with-body --silent --show-error -H "Origin: $origin" -H "X-CSRF-Token: $csrf" \
        -H 'Content-Type: application/json' -b "$tmp/cookies" -c "$tmp/cookies" \
        --data-binary "@$tmp/mapping-enrollment.json" "$core_url/api/v1/agent-enrollments" \
        > "$tmp/mapping-enrollment-response.json"
    mapping_host_id=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["hostId"])' \
        "$tmp/mapping-enrollment-response.json")
    printf '{"hostId":"%s"}' "$mapping_host_id" > "$tmp/mapping-host.json"
    map_status=$(curl --silent --show-error -o "$tmp/mapping-response.json" -w '%{http_code}' -X PUT \
        -H "Origin: $origin" -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
        -b "$tmp/cookies" -c "$tmp/cookies" --data-binary "@$tmp/mapping-host.json" \
        "$core_url/api/v1/kubernetes/clusters/$cluster_a_id/nodes/$mapping_uid_a/host")
    [[ $map_status == 200 ]] || { printf 'First Node mapping returned HTTP %s\n' "$map_status" >&2; exit 1; }
    for mapping_target in \
        "$core_url/api/v1/kubernetes/clusters/$cluster_a_id/nodes/$mapping_uid_a_other/host" \
        "$core_url/api/v1/kubernetes/clusters/$cluster_b_id/nodes/$mapping_uid_b/host"; do
        conflict_status=$(curl --silent --show-error -o "$tmp/mapping-conflict.json" -w '%{http_code}' -X PUT \
            -H "Origin: $origin" -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
            -b "$tmp/cookies" -c "$tmp/cookies" --data-binary "@$tmp/mapping-host.json" "$mapping_target")
        [[ $conflict_status == 409 ]] || { printf 'Conflicting Node mapping returned HTTP %s, expected 409\n' "$conflict_status" >&2; exit 1; }
    done
    kubectl --context="$context_a" delete node "$mapping_node_a" >/dev/null
    curl --fail-with-body --silent --show-error -H "Origin: $origin" -b "$tmp/cookies" \
        "$core_url/api/v1/kubernetes/clusters/$cluster_a_id/nodes" > "$tmp/mapping-removed-node.json"
    python3 - "$tmp/mapping-removed-node.json" "$mapping_uid_a" "$mapping_host_id" <<'PY'
import json,sys
nodes=json.load(open(sys.argv[1],encoding="utf-8"))["items"]
matches=[node for node in nodes if node["nodeUid"]==sys.argv[2]]
if len(matches)!=1 or matches[0]["nodePresent"] or matches[0]["hostId"]!=sys.argv[3]:
 raise SystemExit("Removed Node did not retain its UID tombstone and Host mapping")
PY
    removed_map_status=$(curl --silent --show-error -o "$tmp/removed-map.json" -w '%{http_code}' -X PUT \
        -H "Origin: $origin" -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
        -b "$tmp/cookies" -c "$tmp/cookies" --data-binary "@$tmp/mapping-host.json" \
        "$core_url/api/v1/kubernetes/clusters/$cluster_a_id/nodes/$mapping_uid_a/host")
    [[ $removed_map_status == 404 ]] || { printf 'Mapping removed Node returned HTTP %s, expected 404\n' "$removed_map_status" >&2; exit 1; }
    unmap_status=$(curl --silent --show-error -o "$tmp/unmap-response.json" -w '%{http_code}' -X DELETE \
        -H "Origin: $origin" -H "X-CSRF-Token: $csrf" -b "$tmp/cookies" -c "$tmp/cookies" \
        "$core_url/api/v1/kubernetes/clusters/$cluster_a_id/nodes/$mapping_uid_a/host")
    [[ $unmap_status == 204 ]] || { printf 'Explicit stale-Node unmap returned HTTP %s, expected 204\n' "$unmap_status" >&2; exit 1; }
    curl --fail-with-body --silent --show-error -H "Origin: $origin" -b "$tmp/cookies" \
        "$core_url/api/v1/hosts?limit=100" > "$tmp/mapping-hosts.json"
    python3 - "$tmp/mapping-hosts.json" "$mapping_host_id" <<'PY'
import json,sys
hosts=json.load(open(sys.argv[1],encoding="utf-8"))["items"]
if not any(host["id"]==sys.argv[2] for host in hosts):
 raise SystemExit("Host asset identity was lost after Node removal and explicit unmap")
PY
fi

deployment_name="p4-managed-$(date +%s)"
deployment_path="$core_url/api/v1/kubernetes/clusters/$cluster_a_id/namespaces/default/deployments"
python3 - "$tmp/deployment.json" "$deployment_name" <<'PY'
import json
import sys

with open(sys.argv[1], "w", encoding="utf-8") as output:
    json.dump({"name": sys.argv[2], "image": "registry.k8s.io/pause:3.10",
               "replicas": 1, "containerPort": 8080}, output)
PY
create_status=$(curl --silent --show-error -o "$tmp/deployment-response.json" -w '%{http_code}' \
    -H "Origin: $origin" -H "X-CSRF-Token: $csrf" \
    -H 'Content-Type: application/json' -b "$tmp/cookies" -c "$tmp/cookies" \
    --data-binary "@$tmp/deployment.json" "$deployment_path")
[[ "$create_status" == 201 ]] || { printf 'Managed Deployment create returned HTTP %s\n' "$create_status" >&2; cat "$tmp/deployment-response.json" >&2; exit 1; }
python3 - "$tmp/deployment-response.json" "$deployment_name" <<'PY'
import json
import sys

deployment = json.load(open(sys.argv[1], encoding="utf-8"))
if deployment["name"] != sys.argv[2] or deployment["replicas"] != 1:
    raise SystemExit("Created Deployment response does not match the desired resource")
PY

invalid_status=$(curl --silent --show-error -o "$tmp/invalid-deployment-response.json" -w '%{http_code}' \
    -H "Origin: $origin" -H "X-CSRF-Token: $csrf" \
    -H 'Content-Type: application/json' -b "$tmp/cookies" -c "$tmp/cookies" \
    --data "{\"name\":\"p4-invalid-$RANDOM\",\"image\":\"registry.k8s.io/pause:3.10\",\"replicas\":21,\"containerPort\":8080}" \
    "$deployment_path")
[[ "$invalid_status" == 422 ]] || { printf 'Invalid Deployment request returned HTTP %s, expected 422\n' "$invalid_status" >&2; exit 1; }

managed_status=$(curl --silent --show-error -o "$tmp/managed-list.json" -w '%{http_code}' \
    -H "Origin: $origin" -b "$tmp/cookies" "$deployment_path")
[[ "$managed_status" == 200 ]] || { printf 'Managed Deployment list returned HTTP %s\n' "$managed_status" >&2; exit 1; }
python3 - "$tmp/managed-list.json" "$deployment_name" <<'PY'
import json
import sys

items = json.load(open(sys.argv[1], encoding="utf-8"))["items"]
if not any(item["name"] == sys.argv[2] and item["state"] != "OWNERSHIP_CONFLICT" for item in items):
    raise SystemExit("Registered Deployment was not visible in the managed list")
PY

scale_status=$(curl --silent --show-error -o "$tmp/scale-response.json" -w '%{http_code}' -X PUT \
    -H "Origin: $origin" -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
    -b "$tmp/cookies" -c "$tmp/cookies" --data '{"replicas":2}' \
    "$deployment_path/$deployment_name/scale")
[[ "$scale_status" == 200 ]] || { printf 'Managed Deployment scale returned HTTP %s\n' "$scale_status" >&2; exit 1; }
python3 - "$tmp/scale-response.json" <<'PY'
import json
import sys

if json.load(open(sys.argv[1], encoding="utf-8"))["replicas"] != 2:
    raise SystemExit("Managed Deployment scale did not persist the requested replica count")
PY

wrong_cluster_status=$(curl --silent --show-error -o "$tmp/wrong-cluster-response.json" -w '%{http_code}' -X DELETE \
    -H "Origin: $origin" -H "X-CSRF-Token: $csrf" -b "$tmp/cookies" -c "$tmp/cookies" \
    "$core_url/api/v1/kubernetes/clusters/$cluster_b_id/namespaces/default/deployments/$deployment_name")
[[ "$wrong_cluster_status" == 404 ]] || { printf 'Cross-cluster deletion returned HTTP %s, expected 404\n' "$wrong_cluster_status" >&2; exit 1; }

unmanaged_deployment="p4-unmanaged-$(date +%s)"
kubectl --context="$context_a" -n default create deployment "$unmanaged_deployment" \
    --image=registry.k8s.io/pause:3.10 >/dev/null
unmanaged_path="$core_url/api/v1/kubernetes/clusters/$cluster_a_id/namespaces/default/deployments/$unmanaged_deployment"
unmanaged_status=$(curl --silent --show-error -o "$tmp/unmanaged-response.json" -w '%{http_code}' -X DELETE \
    -H "Origin: $origin" -H "X-CSRF-Token: $csrf" -b "$tmp/cookies" -c "$tmp/cookies" \
    "$unmanaged_path")
[[ "$unmanaged_status" == 404 ]] || { printf 'Unowned Deployment deletion returned HTTP %s, expected 404\n' "$unmanaged_status" >&2; exit 1; }
kubectl --context="$context_a" -n default get deployment "$unmanaged_deployment" >/dev/null

managed_delete_status=$(curl --silent --show-error -o "$tmp/delete-response.json" -w '%{http_code}' -X DELETE \
    -H "Origin: $origin" -H "X-CSRF-Token: $csrf" -b "$tmp/cookies" -c "$tmp/cookies" \
    "$deployment_path/$deployment_name")
[[ "$managed_delete_status" == 204 ]] || { printf 'Managed Deployment delete returned HTTP %s\n' "$managed_delete_status" >&2; exit 1; }

# A read-only Node role permits cluster import but cannot inspect or mutate Deployments.
denied_account="p4-deploy-denied-$(date +%s)"
restricted_role="p4-node-reader-$(date +%s)"
restricted_binding="p4-node-reader-binding-$(date +%s)"
kubectl --context="$context_a" create clusterrole "$restricted_role" \
    --verb=get,list --resource=nodes,namespaces >/dev/null
kubectl --context="$context_a" create clusterrolebinding "$restricted_binding" \
    --clusterrole="$restricted_role" --serviceaccount="default:$denied_account" >/dev/null
kubectl --context="$context_a" -n default create serviceaccount "$denied_account" >/dev/null
kubectl --context="$context_a" -n default create token "$denied_account" --duration=10m > "$tmp/denied-token"
chmod 600 "$tmp/denied-token"
python3 - "$tmp/a.json" "$tmp/denied-token" "$tmp/restricted.json" <<'PY'
import json
import sys

config = json.load(open(sys.argv[1], encoding="utf-8"))
config["users"][0]["user"] = {"token": open(sys.argv[2], encoding="utf-8").read().strip()}
with open(sys.argv[3], "w", encoding="utf-8") as output:
    json.dump({"name": "p4-deployment-rbac", "kind": "K3S",
               "contextName": config["contexts"][0]["name"],
               "kubeconfig": json.dumps(config, separators=(",", ":"))}, output)
PY
chmod 600 "$tmp/restricted.json"
restricted_status=$(curl --silent --show-error -o "$tmp/restricted-response.json" -w '%{http_code}' \
    -H "Origin: $origin" -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
    -b "$tmp/cookies" -c "$tmp/cookies" --data-binary "@$tmp/restricted.json" \
    "$core_url/api/v1/kubernetes/clusters")
[[ "$restricted_status" == 201 ]] || { printf 'Node-read-only cluster import returned HTTP %s\n' "$restricted_status" >&2; cat "$tmp/restricted-response.json" >&2; exit 1; }
restricted_cluster_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$tmp/restricted-response.json")
restricted_path="$core_url/api/v1/kubernetes/clusters/$restricted_cluster_id/namespaces/default/deployments"
status=$(curl --silent --show-error -o "$tmp/restricted-resource-response.json" -w '%{http_code}' \
    -H "Origin: $origin" -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
    -b "$tmp/cookies" -c "$tmp/cookies" \
    --data '{"name":"p4-rbac-probe","image":"registry.k8s.io/pause:3.10","replicas":1,"containerPort":8080}' \
    "$restricted_path")
[[ "$status" == 403 ]] || { printf 'Deployment create with Node-only RBAC returned HTTP %s, expected 403\n' "$status" >&2; exit 1; }
kubectl --context="$context_a" -n default delete serviceaccount "$denied_account" --ignore-not-found >/dev/null
denied_account=

# A duplicate name must not overwrite the first cluster or its credentials.
make_import "$tmp/b.json" "$cluster_a_name" "$context_b" "$tmp/duplicate.json"
duplicate_status=$(curl --silent --show-error -o "$tmp/duplicate-response.json" -w '%{http_code}' \
    -H "Origin: $origin" -H "X-CSRF-Token: $csrf" \
    -H 'Content-Type: application/json' -b "$tmp/cookies" -c "$tmp/cookies" \
    --data-binary "@$tmp/duplicate.json" "$core_url/api/v1/kubernetes/clusters")
[[ "$duplicate_status" == 409 ]] || { printf 'Duplicate cluster name returned HTTP %s\n' "$duplicate_status" >&2; exit 1; }

# Exec plugins and insecure TLS are rejected before any credential is persisted.
python3 - "$tmp/a.json" "$tmp/exec.json" "$tmp/insecure.json" <<'PY'
import json
import sys

source = json.load(open(sys.argv[1], encoding="utf-8"))
exec_config = json.loads(json.dumps(source))
exec_config["users"][0]["user"] = {"exec": {"command": "/bin/false", "apiVersion": "client.authentication.k8s.io/v1"}}
insecure_config = json.loads(json.dumps(source))
cluster = insecure_config["clusters"][0]["cluster"]
cluster.pop("certificate-authority-data", None)
cluster["insecure-skip-tls-verify"] = True
for path, config, name in ((sys.argv[2], exec_config, "p4-exec-rejected"),
                           (sys.argv[3], insecure_config, "p4-insecure-rejected")):
    with open(path, "w", encoding="utf-8") as output:
        json.dump({"name": name, "kind": "K3S", "contextName": config["contexts"][0]["name"],
                   "kubeconfig": json.dumps(config, separators=(",", ":"))}, output)
PY
for rejected in "$tmp/exec.json" "$tmp/insecure.json"; do
    status=$(curl --silent --show-error -o "$tmp/rejected.json" -w '%{http_code}' \
        -H "Origin: $origin" -H "X-CSRF-Token: $csrf" \
        -H 'Content-Type: application/json' -b "$tmp/cookies" -c "$tmp/cookies" \
        --data-binary "@$rejected" "$core_url/api/v1/kubernetes/clusters")
    [[ "$status" == 422 ]] || { printf 'Unsafe kubeconfig returned HTTP %s, expected 422\n' "$status" >&2; exit 1; }
done

# An unprivileged ServiceAccount proves RBAC is checked against the selected context.
denied_account="p4-import-denied-$(date +%s)"
kubectl --context="$context_a" -n default create serviceaccount "$denied_account" >/dev/null
kubectl --context="$context_a" -n default create token "$denied_account" --duration=10m > "$tmp/denied-token"
chmod 600 "$tmp/denied-token"
python3 - "$tmp/a.json" "$tmp/denied-token" "$tmp/denied.json" <<'PY'
import json
import sys

config = json.load(open(sys.argv[1], encoding="utf-8"))
token = open(sys.argv[2], encoding="utf-8").read().strip()
config["users"][0]["user"] = {"token": token}
with open(sys.argv[3], "w", encoding="utf-8") as output:
    json.dump({"name": "p4-rbac-denied", "kind": "K3S",
               "contextName": config["contexts"][0]["name"],
               "kubeconfig": json.dumps(config, separators=(",", ":"))}, output)
PY
chmod 600 "$tmp/denied.json"
denied_status=$(curl --silent --show-error -o "$tmp/denied-response.json" -w '%{http_code}' \
    -H "Origin: $origin" -H "X-CSRF-Token: $csrf" \
    -H 'Content-Type: application/json' -b "$tmp/cookies" -c "$tmp/cookies" \
    --data-binary "@$tmp/denied.json" "$core_url/api/v1/kubernetes/clusters")
[[ "$denied_status" == 403 ]] || { printf 'Unauthorized context returned HTTP %s, expected 403\n' "$denied_status" >&2; exit 1; }

curl --fail-with-body --silent --show-error \
    -H "Origin: $origin" -b "$tmp/cookies" \
    "$core_url/api/v1/kubernetes/clusters" > "$tmp/clusters.json"
python3 - "$tmp/clusters.json" "$tmp/a.json" "$tmp/b.json" \
    "$cluster_a_name" "$cluster_b_name" <<'PY'
import json
import sys

clusters = json.load(open(sys.argv[1], encoding="utf-8"))["items"]
if {cluster["name"] for cluster in clusters} != {sys.argv[4], sys.argv[5], "p4-deployment-rbac"}:
    raise SystemExit("Rejected contexts changed the registered cluster inventory")
serialized = json.dumps(clusters)
for path in sys.argv[2:4]:
    config = json.load(open(path, encoding="utf-8"))
    user = config["users"][0]["user"]
    for key in ("client-certificate-data", "client-key-data", "token"):
        if key in user and user[key] and user[key] in serialized:
            raise SystemExit("Cluster list leaked credential material")
if "kubeconfig" in serialized.lower() or "client-key-data" in serialized:
    raise SystemExit("Cluster list exposed credential fields")
PY

printf 'PASS: two independent %s API servers imported by explicit context; managed Deployment create/list/scale/delete passed; invalid input, cross-cluster and unowned deletion, insufficient RBAC, duplicate name, exec plugin, insecure TLS, and credential leakage rejected.\n' "$fixture_name"
if [[ $test_node_mapping == yes ]]; then
    printf 'PASS: Node UID mapping, same-cluster and cross-cluster mapping conflicts, removed-Node tombstone, explicit unmap, and retained Host identity.\n'
fi
