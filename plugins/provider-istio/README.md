# Istio Provider groundwork

Istio is the optional Kubernetes workload service-network layer. The isolated
test mesh in [`deploy/istio`](../../deploy/istio/README.md) verifies sidecar
injection, STRICT workload mTLS, Service-to-EndpointSlice mapping, and an
identity-based allow/deny policy. Core now also provides a server-configured,
namespace-limited Service/EndpointSlice inventory API, persists its latest
snapshot, and can run bounded opt-in TCP probes of ready private endpoints.

There is not yet a PF4J Istio Provider or production reconciliation. Core does
not inject its own namespace or manage application namespaces. A future
adapter must own only resources explicitly marked for NaNPanel, retain
unmanaged fields, report proxy and policy observations, and compensate safely.
It must also prove Core-to-Agent Gateway health and recovery before any
production control-plane injection. The NetBird Provider remains the separate
cross-host overlay adapter; Istio does not replace NAT traversal or public
ingress.
