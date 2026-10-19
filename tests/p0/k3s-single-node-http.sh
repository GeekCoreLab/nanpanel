#!/bin/sh
set -eu

ssh_alias=${NANPANEL_P0_K3S_SSH_ALIAS:-k3s-1}
http_image=${NANPANEL_P0_K3S_HTTP_IMAGE:-docker.1ms.run/nginx:latest}
run_id=$(date +%Y%m%d%H%M%S)-$$
namespace="nanpanel-p0-k3s-${run_id}"

case "$http_image" in
  *[!A-Za-z0-9._/:@+-]*)
    echo "FAIL: invalid image reference: $http_image" >&2
    exit 2
    ;;
esac

local_port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')
work=$(mktemp -d /tmp/nanpanel-p0-k3s.XXXXXX)
tunnel_pid=''
namespace_created=0

cleanup() {
  status=$?
  trap - EXIT INT TERM
  if [ -n "$tunnel_pid" ]; then
    kill "$tunnel_pid" >/dev/null 2>&1 || true
    wait "$tunnel_pid" >/dev/null 2>&1 || true
  fi
  owner=$(ssh -o BatchMode=yes -o ConnectTimeout=10 "$ssh_alias" \
    kubectl get namespace "$namespace" "-l=nanpanel-p0.run-id=$run_id" -o name 2>/dev/null || true)
  if [ "$namespace_created" = 1 ] || [ "$owner" = "namespace/$namespace" ]; then
    if [ "${KEEP_NANPANEL_P0_K3S_ARTIFACTS:-0}" = 1 ]; then
      echo "Keeping isolated K3s HTTP fixture: namespace=$namespace service=p0-k3s-http"
    else
      ssh -o BatchMode=yes -o ConnectTimeout=10 "$ssh_alias" \
        kubectl delete namespace "$namespace" --wait=true --timeout=90s >/dev/null 2>&1 || true
    fi
  fi
  rm -rf "$work"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

ssh -o BatchMode=yes -o ConnectTimeout=10 "$ssh_alias" kubectl create namespace "$namespace"
namespace_created=1
ssh -o BatchMode=yes -o ConnectTimeout=10 "$ssh_alias" kubectl label namespace "$namespace" \
  "app.kubernetes.io/part-of=nanpanel-p0" "nanpanel-p0.run-id=$run_id"

sed "s/@NAMESPACE@/$namespace/g; s#@HTTP_IMAGE@#$http_image#g" <<'EOF' \
  | ssh -o BatchMode=yes -o ConnectTimeout=10 "$ssh_alias" kubectl apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: p0-k3s-http-content
  namespace: @NAMESPACE@
data:
  index.html: nanpanel-p0-k3s-service
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: p0-k3s-http
  namespace: @NAMESPACE@
  labels:
    app.kubernetes.io/name: p0-k3s-http
spec:
  replicas: 1
  revisionHistoryLimit: 0
  selector:
    matchLabels:
      app.kubernetes.io/name: p0-k3s-http
  template:
    metadata:
      labels:
        app.kubernetes.io/name: p0-k3s-http
    spec:
      containers:
        - name: nginx
          image: @HTTP_IMAGE@
          imagePullPolicy: IfNotPresent
          ports:
            - name: http
              containerPort: 80
          resources:
            requests:
              cpu: 10m
              memory: 16Mi
            limits:
              cpu: 100m
              memory: 64Mi
          volumeMounts:
            - name: content
              mountPath: /usr/share/nginx/html/index.html
              subPath: index.html
              readOnly: true
      volumes:
        - name: content
          configMap:
            name: p0-k3s-http-content
---
apiVersion: v1
kind: Service
metadata:
  name: p0-k3s-http
  namespace: @NAMESPACE@
spec:
  type: ClusterIP
  selector:
    app.kubernetes.io/name: p0-k3s-http
  ports:
    - name: http
      port: 80
      targetPort: http
EOF

ssh -o BatchMode=yes -o ConnectTimeout=10 "$ssh_alias" \
  kubectl -n "$namespace" rollout status deployment/p0-k3s-http --timeout=120s
ssh -o BatchMode=yes -o ConnectTimeout=10 "$ssh_alias" \
  kubectl -n "$namespace" get pods,services -o wide

ssh -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=15 \
  -L "127.0.0.1:${local_port}:127.0.0.1:${local_port}" "$ssh_alias" \
  "kubectl -n ${namespace} port-forward --address=127.0.0.1 service/p0-k3s-http ${local_port}:80" \
  >"$work/port-forward.log" 2>&1 &
tunnel_pid=$!

attempt=0
while [ "$attempt" -lt 40 ]; do
  status=$(curl --noproxy '*' --silent --show-error --max-time 2 \
    -o "$work/response" -w '%{http_code}' "http://127.0.0.1:${local_port}/" 2>/dev/null || true)
  body=$(cat "$work/response" 2>/dev/null || true)
  if [ "$status" = 200 ] && [ "$body" = nanpanel-p0-k3s-service ]; then
    echo "PASS: SSH-tunneled HTTP request reached the real K3s Service; status=$status body=$body"
    exit 0
  fi
  if ! kill -0 "$tunnel_pid" 2>/dev/null; then
    cat "$work/port-forward.log"
    echo 'FAIL: SSH tunnel or kubectl port-forward exited before the K3s Service became reachable' >&2
    exit 1
  fi
  attempt=$((attempt + 1))
  sleep 0.5
done

cat "$work/port-forward.log"
echo "FAIL: expected HTTP 200 with body 'nanpanel-p0-k3s-service'; got status=$status body=$body" >&2
exit 1
