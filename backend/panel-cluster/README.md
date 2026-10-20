# panel-cluster

Cluster identity and read-only Kubernetes discovery adapters. `KubernetesServiceDiscovery`
lists Services and EndpointSlices only in the configured namespace and returns
a bounded, timestamped snapshot containing service ports, endpoint addresses,
readiness, and Pod target identity. It performs no writes or probes. Core exposes
the snapshot at `GET /api/v1/kubernetes/service-inventory` only when explicitly
enabled and configured with one cluster identity and namespace. Historical
inventory history and the Istio reconciliation Provider remain separate
integration work. Core has an independent opt-in, bounded TCP probe path for
Ready private IP endpoints; see the service-network architecture document for
its limits and evidence boundary.
