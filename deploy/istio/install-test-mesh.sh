#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ssh_target="${NANPANEL_ISTIO_SSH_ALIAS:-k3s-1}"
istio_version="1.31.1"
istio_image_hub="${ISTIO_IMAGE_HUB:-docker.1ms.run/istio}"
istioctl_path="${ISTIOCTL_PATH:-$(command -v istioctl || true)}"
remote_dir="/tmp/nanpanel-istio-${USER:-user}-$$"

if [[ ! "$istio_image_hub" =~ ^[A-Za-z0-9.-]+(:[0-9]+)?/[A-Za-z0-9._-]+$ ]]; then
  echo 'ISTIO_IMAGE_HUB must be a registry host and one image namespace.' >&2
  exit 1
fi

if [[ -z "$istioctl_path" || ! -x "$istioctl_path" ]]; then
  echo 'Set ISTIOCTL_PATH to the executable from the pinned Istio 1.31.1 release.' >&2
  exit 1
fi
client_version="$("$istioctl_path" version --remote=false 2>/dev/null | awk -F ': ' '/client version:/ { print $2; exit }')"
if [[ "$client_version" != "$istio_version" ]]; then
  echo "This setup is pinned to Istio ${istio_version}; supplied istioctl is ${client_version:-unknown}." >&2
  exit 1
fi

ssh -o BatchMode=yes -o ConnectTimeout=10 "$ssh_target" \
  "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl get node k3s-1 >/dev/null"
existing_image="$(ssh -o BatchMode=yes "$ssh_target" \
  "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n istio-system get deployment istiod -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true")"
if [[ -n "$existing_image" && "$existing_image" != *":${istio_version}" ]]; then
  echo "Refusing to replace existing Istio control plane image: ${existing_image}" >&2
  exit 1
fi

cleanup() {
  ssh -o BatchMode=yes "$ssh_target" "rm -rf -- '$remote_dir'" >/dev/null 2>&1 || true
}
trap cleanup EXIT

ssh -o BatchMode=yes "$ssh_target" "umask 077 && mkdir '$remote_dir'"
scp -q "$istioctl_path" "$ssh_target:$remote_dir/istioctl"
scp -q "$repo_root/deploy/istio/mesh.yaml" "$ssh_target:$remote_dir/mesh.yaml"
scp -q "$repo_root/deploy/istio/probe-target.yaml" "$ssh_target:$remote_dir/probe-target.yaml"
ssh -o BatchMode=yes "$ssh_target" \
  "chmod 700 '$remote_dir/istioctl' && sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml '$remote_dir/istioctl' install --set profile=minimal --set values.global.hub='$istio_image_hub' --skip-confirmation"
ssh -o BatchMode=yes "$ssh_target" \
  "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl apply -f '$remote_dir/mesh.yaml'"
ssh -o BatchMode=yes "$ssh_target" \
  "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl apply -f '$remote_dir/probe-target.yaml'"
ssh -o BatchMode=yes "$ssh_target" \
  "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n istio-system rollout status deployment/istiod --timeout=240s"
ssh -o BatchMode=yes "$ssh_target" \
  "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n nanpanel-mesh rollout status deployment/mesh-http --timeout=600s"
ssh -o BatchMode=yes "$ssh_target" \
  "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n nanpanel-mesh rollout status deployment/mesh-client --timeout=600s"
ssh -o BatchMode=yes "$ssh_target" \
  "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n nanpanel-mesh rollout status deployment/mesh-denied-client --timeout=600s"
ssh -o BatchMode=yes "$ssh_target" \
  "sudo -n env KUBECONFIG=/etc/rancher/k3s/k3s.yaml kubectl -n nanpanel-mesh rollout status deployment/mesh-probe-target --timeout=600s"

printf 'Istio %s control plane and isolated nanpanel-mesh fixtures are installed on %s using %s.\n' \
  "$istio_version" "$ssh_target" "$istio_image_hub"
printf 'Run deploy/istio/verify-test-mesh.sh to verify sidecar injection, strict mTLS, and the service policy.\n'
