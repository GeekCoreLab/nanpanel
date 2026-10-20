# ADR-013: Optional Istio service network for Kubernetes workloads

- Status: Accepted; isolated K3s fixture deployed; platform Provider not implemented.
- Date: 2026-10-20

## Context

NaNPanel manages both ordinary Linux hosts and Kubernetes workloads. Those
resources need a common service/endpoint model, but they do not share one
network boundary. NetBird provides cross-host overlay transport and NAT relay;
Kubernetes Service/EndpointSlice provides cluster-local endpoint discovery.
Istio can add workload identity, mTLS, and L7 policy inside Kubernetes without
making ordinary hosts Kubernetes nodes.

The test cluster is a single-node K3s 1.33 installation and already hosts the
NaNPanel control plane. Injecting the control plane, Agent Gateway, or database
without exercising health and recovery behavior could interrupt management.

## Decision

1. Treat Istio as an optional Kubernetes workload service-network adapter.
2. Keep NetBird as the overlay adapter for ordinary hosts and cross-NAT paths.
3. Keep public ingress as an Envoy/FRP concern. Istio's in-cluster Gateway API
   support may be used for Kubernetes ingress later, but does not silently
   replace existing public FRP/Traefik routes.
4. Deploy a pinned Istio 1.31.1 minimal control plane in the test cluster and
   exercise a separate `nanpanel-mesh` namespace with strict mTLS and a
   service-account allow policy. Do not inject the existing `nanpanel`
   namespace as part of this groundwork.
5. Implement a Provider only after Kubernetes discovery, resource ownership,
   proxy status, operation rollback, and Core-to-Gateway connectivity have
   stable contracts.

## Consequences

- Kubernetes services can use Istio workload identity and traffic policy while
  ordinary hosts remain independently managed.
- Istio discovery and an HTTP 200 alone do not prove a cross-host overlay path.
  NetBird path state and application probes remain separate evidence.
- The test control plane consumes cluster resources and is cluster-scoped; its
  removal requires an inventory of other namespaces first.
- `nanpanel-mesh` is evidence of Istio installation and policy behavior only.
  It is not a PF4J adapter, production reconciliation, or a P2 completion claim.

## Verification

Run `deploy/istio/verify-test-mesh.sh`. It proves all three fixture pods have
injected proxies, the allowed identity reaches the Service, and an untrusted
identity receives HTTP 403 while STRICT mTLS is required. Production namespace
injection and Provider lifecycle remain open work.
