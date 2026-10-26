# ADR-017: Drag-driven application service traffic steering

- Status: Accepted as a design; implementation and live acceptance have not started.
- Date: 2026-10-26

## Context

The application canvas currently combines Kubernetes resources and virtual
network devices, but its existing connections have distinct meanings:

- A Service-to-Deployment connection edits the Kubernetes Service selector.
- A Deployment-to-device connection attaches the workload to a secondary
  network.
- A device-to-device connection describes a typed network link.

None of these connections declares which application call should be routed to
which backend. A `LOAD_BALANCER` can store relative output weights, but those
weights are desired intent only. Applying the current application network
runtime rejects `LOAD_BALANCER` and executes a limited L2/Ethernet device graph.

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
6. Keep the traffic graph as desired state. A route Provider must bind it to a
   real traffic interception point, compile the route for a declared protocol,
   and report Plan, Apply, Verify, and Compensate state. HTTP/HTTPS/gRPC and
   L4 traffic may require different data-plane capabilities. A graph edge or
   saved weight alone never claims that traffic is being steered.
7. Keep route enforcement separate from the Multus/L2 virtual-network
   Provider. The latter can provide reachability or inspection paths; it does
   not infer application calls or weighted backend policy. Saving or
   publishing topology must not install a mesh, Gateway, or CNI, or mutate a
   cluster. Initial live packet acceptance must use an isolated test namespace
   and cluster.
8. Make packet capture an explicit, temporary operation on a selected
   supported network link. The canvas should offer a link action to start a
   capture, show its capture point and state, and let an authorized operator
   stop it and download a PCAPNG artifact. A capture mirrors packets and must
   not change forwarding, routing, or load-balancer weights. Apply duration,
   packet/byte, and retention bounds; their concrete defaults remain to be
   decided with the capture Provider. Record the topology revision, selected
   link, capture point, direction, filters, start/end times, and truncation or
   failure status with the artifact.
9. Treat `PACKET_TAP` as a possible data-plane source, not as a capture file
   recorder. Its current Linux implementation mirrors both business-link
   directions to a monitor port using `tc mirred`; it does not start an
   operator session or create PCAP/PCAPNG output. A future capture Provider
   must connect an eligible link's mirror source to a bounded recorder and
   report exactly which packets and directions it can observe. A logical
   application traffic-flow edge may offer capture only when its route
   Provider exposes a packet capture point; proxy counters or request logs
   must not be presented as packet captures. Captured packets retain the
   encryption present at the selected point, so a capture before TLS
   termination contains encrypted application payload.

## Consequences

- The canvas needs a clearly distinguished traffic-flow edge type. Resource
  selector edges and virtual network links retain their current meanings.
- A user can connect one stable Service identity to a load balancer, then
  connect backend Services once and set weights on the outputs. The controller
  resolves Pod endpoints from each backend Service; it does not require
  per-Pod configuration.
- A Deployment that both serves and consumes services appears in separate
  flows without being duplicated or assigned an artificial one-way role.
- Existing `LOAD_BALANCER` output weights remain intent until a compatible
  route Provider executes them. Direct Pod-IP traffic and destinations outside
  a Provider's interception boundary are not covered by a Service route.
- No throughput, latency, or split-accuracy target is committed here. Those
  values require a later isolated data-plane experiment.
- Link capture is separate from persistent topology intent and traffic
  steering. It is not implemented: the existing `PACKET_TAP` only mirrors
  frames to a monitor port. Capture requires an explicit bounded session,
  recorder, artifact lifecycle, and Provider evidence. A link without a
  supported mirror point must be shown as unavailable rather than returning an
  empty or inferred capture.
- Capture limits, roles, and artifact retention need to be set by the capture
  Provider before implementation. Starting or stopping a capture must be
  auditable; saving the topology alone never starts one.

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

Packet-capture acceptance is a separate gate. On an isolated supported link,
start a bounded capture and prove that its artifact contains packets observed
at the reported point while the original business path continues forwarding
unchanged. Verify that the artifact records the selected revision/link,
directions, filters, time bounds, and whether configured limits truncated the
capture; stopping the session must stop packet collection and release its
temporary mirror/recorder resources. Also prove that an unsupported link is
reported as unavailable and that application-flow capture is offered only
when its route Provider exposes a real packet source. Encrypted payload must
not be described as decrypted application content.
