# ADR-017: Drag-driven application service traffic steering

- Status: Accepted; first application implementation is available, live data-plane acceptance is pending.
- Date: 2026-10-26

## Context

The application canvas currently combines Kubernetes resources and virtual
network devices, but its existing connections have distinct meanings:

- A Service-to-Deployment connection edits the Kubernetes Service selector.
- A Deployment-to-device connection attaches the workload to a secondary
  network.
- A device-to-device connection describes a typed network link.

None of these connections declares which application call should be routed to
which backend. A `LOAD_BALANCER` can store relative output weights; the
virtual-network runtime still executes only a limited L2/Ethernet device graph.
An independent Service route runtime now applies the supported HTTP subset
through Istio VirtualServices.

A Kubernetes Deployment can serve requests and initiate calls to other
Services at the same time. Modeling a Deployment as having one traffic
direction would make common service chains ambiguous. Operators should select
logical Services or backend groups in the graph; Kubernetes should continue to
discover their Ready Pod endpoints.

## Decision

1. Keep three relationship types distinct in the application design:
   - **Resource binding** describes which Deployments a Kubernetes Service
     selects. It changes the Kubernetes resource relationship.
   - **Network link** connects typed ports on virtual network devices or
     workload network attachments. It describes L2/L3 reachability.
   - **Traffic flow** describes an application call and its routing policy. It
     does not mean that two devices have a physical or virtual cable between
     them.
2. Treat Kubernetes Services as stable traffic identities. A traffic flow may
   name an optional caller workload, the destination Service and port, and a
   protocol-specific match such as HTTP host/path. An omitted caller means the
   route applies to all eligible callers for that Service identity.
3. Place a `LOAD_BALANCER` in a traffic flow between its stable Service entry
   and one or more logical backend Service groups. Each output represents one
   backend group and carries a relative weight. Weights are normalized across
   positive outputs; weight zero disables that group. The graph need not list
   individual Pods.
4. Use a Kubernetes Service as the backend group for one Deployment or a set
   of replicas. Kubernetes Service/EndpointSlice discovery supplies Ready Pod
   endpoints as replicas scale or roll. Different release groups, such as
   stable and canary, use distinct Services when they need separate weights.
5. Treat a workload interface as full duplex. A Deployment may receive calls
   through one Service and initiate calls to other Services through separate
   traffic flows. The response to a request is the return direction of the
   same connection and does not require a second reverse flow. A new request
   initiated by the Deployment is a separate flow.
6. Keep the traffic graph as desired state. The first route Provider binds
   HTTP Service-port routes to an application-owned Istio VirtualService and
   reports Apply, Verify, and Compensate state. It accepts TCP Service ports
   named `http*` or numbered 80/8080, requires an injected and Ready caller
   sidecar, and supports an optional Deployment caller selector. HTTPS/gRPC
   classification, host/path matches, L4 traffic, and Gateway ingress are not
   in this first slice. A graph edge or saved weight alone never claims that
   traffic is being steered.
7. Keep route enforcement separate from the Multus/L2 virtual-network
   Provider. The latter can provide reachability or inspection paths; it does
   not infer application calls or weighted backend policy. Saving or
   publishing topology must not install a mesh, Gateway, or CNI, or mutate a
   cluster. Initial live packet acceptance must use an isolated test namespace
   and cluster.
8. Make packet capture an explicit, temporary operation on a selected
   supported network link. The canvas offers a link action to start capture,
   show its capture point and state, and download a PCAPNG artifact after the
   bounded capture ends automatically. The first implementation accepts only
   an applied L2 Ethernet wire and uses trusted `tcpdump` in its topology
   namespace. Bounds are 5 seconds, 128 packets, 128 bytes per packet, 64 KiB
   per artifact, and 24-hour retention. Capture mirrors packets and does not
   change forwarding, routing, or load-balancer weights. PCAPNG metadata
   records topology Revision/generation, selected link, source-side interface,
   both directions, no extra filter, start/end times, and truncation/failure.
9. Treat `PACKET_TAP` as separate from file capture. Its Linux implementation
   mirrors both business-link directions to a monitor port using `tc mirred`;
   the capture action reads the source-side interface of the selected wire and
   does not record the monitor port. A logical application traffic-flow edge
   has no packet capture point in the first route Provider. Proxy counters or
   request logs must not be presented as packet captures. Captured packets
   retain the encryption present at the selected point, so a capture before
   TLS termination contains encrypted application payload.

## Consequences

- The canvas needs a clearly distinguished traffic-flow edge type. Resource
  selector edges and virtual network links retain their current meanings.
- A user can connect one stable Service identity to a load balancer, then
  connect backend Services once and set weights on the outputs. The controller
  resolves Pod endpoints from each backend Service; it does not require
  per-Pod configuration.
- A Deployment that both serves and consumes services appears in separate
  flows without being duplicated or assigned an artificial one-way role.
- Existing `LOAD_BALANCER` output weights remain intent until the explicit
  Istio route Apply executes them. Direct Pod-IP traffic and destinations
  outside the mesh interception boundary are not covered by a Service route.
- No throughput, latency, or split-accuracy target is committed here. Those
  values require a later isolated data-plane experiment.
- Link capture is separate from persistent topology intent and traffic
  steering. It is implemented for applied L2 Ethernet wires, with an audited,
  bounded automatic session and a 24-hour PCAPNG artifact. It does not use
  `PACKET_TAP`'s monitor port; unsupported links are unavailable. Saving the
  topology alone never starts a capture.

## Verification boundary

Acceptance must show requests sent to the stable Service identity reaching the
configured backend Service groups at the requested relative weights, with the
observed route tied to the applied graph revision. It must also show that Pod
scaling and replacement update backend membership without manual Pod edits,
zero-weight backends receive no new traffic, and compensation restores the
previous verified route. A workload that serves one Service and calls another
must work in both roles; response traffic must not require a separately drawn
reverse route. Live acceptance must distinguish Service-routed traffic from
direct Pod-IP traffic and must not be inferred from topology rendering,
configuration creation, or mock requests.

Packet-capture acceptance remains a separate live gate. On an isolated
supported link, start a bounded capture and prove that its artifact contains
packets observed at the reported point while the original business path
continues forwarding unchanged. Verify selected revision/link, directions,
filter description, time bounds, and limit/truncation metadata. Also prove
that unsupported links are reported as unavailable and that application-flow
capture is not offered without a real route-provider packet source. Encrypted
payload must not be described as decrypted application content.
