# NetworkGraph and topology Revision API

The Core persists one shared workspace graph as immutable Revisions. Saving a desired graph creates a new Revision and advances the current pointer. A saved graph does not mean that routes were deployed or verified.

## Typed graph

Nodes keep editor coordinates and explicit typed ports. A port has an ID, direction (`INPUT` or `OUTPUT`), layer (`L2`, `L3`, `L4`, `L7`) and supported protocols. Supported protocol values are `ETHERNET`, `IP`, `TCP`, `UDP`, `SCTP`, `HTTP` and `HTTPS`; they must match the declared layer. A link declares its layer and protocol, and both endpoint ports must support them.

For `SWITCH`, matching `in-N` and `out-N` declarations represent the input and output sides of physical interface `N`. The editor renders each pair as one interface with a target and source handle, so a neighboring link may use the interface in either direction. The two declarations must have identical layer and protocol capabilities, and the paired physical interface can be used by only one topology link or workload attachment at a time. Other device kinds retain separate input and output interfaces.

Every editable node card also opens a source YAML view using `apiVersion: topology.nanpanel.io/v1alpha1`, `kind: TopologyNode`, `metadata.id`, and a `spec` containing the node fields (`kind`, `name`, `description`, `position`, `inputCount`, `outputCount`, `ports`, `parentId`, `assetId`, `clusterId`, `ipAddress`, `providerId`, `switchBackend`, `firewallPolicy`, `routerConfiguration`, and `loadBalancerConfiguration`). The editor keeps `metadata.id` and `spec.kind` fixed, rejects unknown or missing fields, and validates the parsed node against the complete current graph before updating the unsaved draft. Save the graph separately to create a Revision; YAML edits do not apply a Provider. YAML formatting and comments are regenerated from the normalized graph fields when reopened.

A `LOAD_BALANCER` may store one 0–100 relative weight per output port in `loadBalancerConfiguration.outputWeights`. The editor shows each output's normalized share (`weight / sum(weights)`) beside its port; for example, weights 100 and 100 show 50% each, while weights 100 and 0 show 100% and 0%. If every weight is zero, every output share is 0%. These values record desired distribution intent and do not imply a Provider has executed or verified load balancing.

The editor creates typed default ports for each node kind: switches, Firewalls, and Packet Taps start at `L2/ETHERNET`, while other traffic devices start at `L4/TCP,UDP`. A Firewall or Packet Tap must declare the same layer and protocol set on every port. A Firewall carries an optional desired policy with a default `ACCEPT`/`DROP` action and up to 64 ordered TCP/UDP/SCTP destination-port rules scoped to one input/output port pair. Rule ports must exist on that Firewall and duplicate match conditions are rejected. New Firewall nodes default to `DROP` with no allow rules. This is desired configuration only; it does not imply network rules are active.

A node may optionally set `providerId`; when that Provider is loaded, Core accepts the binding only if one capability declares the node kind, every port layer and protocol, and `PLAN`, `VALIDATE`, `APPLY`, `VERIFY` and `COMPENSATE`. If the Provider is unavailable, the binding is retained as desired state and is not executable until the Provider can be checked. Links use the first common protocol in `ETHERNET`, `IP`, `TCP`, `UDP`, `SCTP`, `HTTP`, `HTTPS` order. A link always points from an output port to an input port. Each port can be used once. Missing references, self-links, duplicate IDs, invalid node shapes, incompatible port types, excess ports, cycles and unknown fields are rejected. Empty graphs are allowed; the limits are 100 nodes and 800 links.

The built-in linux-netns Provider executes selected root-level SWITCH, ROUTER, FIREWALL, and PACKET_TAP nodes bound to linux-netns, joined by L2/Ethernet wires. A plan supports up to 16 devices and 64 wires. Linux Bridge is the default SWITCH backend. OPENVSWITCH uses a private OVSDB and ovs-vswitchd with the netdev datapath inside the topology namespace; it requires the execution Helper to report network.topology.ovs and cannot be combined in one plan with Firewall or Packet Tap nodes.

Every applied topology Revision and generation gets one network namespace created by the Root Helper. Bridge, veth, Router, nftables, traffic-control, and OVS data-plane resources are scoped to that namespace. Host interfaces are never moved and host routes or firewall rules are never configured by this Provider. This isolation is the management-path safety boundary; the Provider does not run an active host-management-path probe. Invalid configurations are rejected before dispatch by Core and revalidated by the Agent and Helper. Compensation removes only a namespace with the matching topology ownership marker.

Each executable Router requires one to 16 explicit IPv4 host CIDRs, one for every connected interface. Within one Router, addresses must be usable and unique, and connected networks cannot overlap. The topology allows at most 64 static routes across all Routers. Routes bind to a connected output port and may use a next hop on that interface's direct subnet; destinations are unique within one Router table, and non-default destinations cannot overlap connected networks. Routers use separate Linux VRF routing tables, so different Routers may declare the same destination prefix. Router plans require the Helper capability network.topology.vrf. IPv6, NAT, dynamic routing, and cross-Provider L3 remain unsupported.

A Firewall needs at least one connected input and output. Its typed policy installs a bridge-family nftables forward chain with the declared default action and ordered port/protocol/destination-port rules. A Packet Tap needs exactly one in-1, one business out-1, and one monitor out-2 wire; its monitor veth stays outside the forwarding bridge while tc mirred copies frames egressing either business bridge port. Apply and Verify check resource ownership, exact Firewall rules, Packet Tap filters, Router VRF membership and routes, and the absence of undeclared static or default routes. An explicitly configured default Router route is installed in that Router's VRF table. Verify and the Provider observation endpoint return per-link kernel RX/TX byte and packet counters.

A plan names the execution Host and expected Host generation. Core checks the saved topology head, required Helper capabilities, Agent freshness, and Helper availability before dispatch. The typed network.topology.reconcile capability is accepted only through the Provider lifecycle. Saving a graph does not deploy or verify it. Isolated P6 labs exercise real bridge forwarding, firewall drops, packet mirroring, Router forwarding, and VRF route selection while checking that host default routes remain unchanged. These labs test Linux data-plane behavior and isolation; they do not run the complete Core-to-Agent-to-Helper Apply lifecycle. Production-topology Apply remains outside this P6 acceptance boundary.

`FOLDER` nodes create nested editing scopes. Nodes may set `parentId` to a folder UUID; omitted or null means the outermost topology. Folders can nest at most eight levels, and a link may only connect nodes in the same scope. The complete hierarchy is saved in the same revision and shares its ETag. Deleting a folder removes all descendants. A `HOST` node may set `assetId` to a registered host UUID; the Core verifies that the host exists and that the same host is not bound more than once in a draft.

| kind | Default inputs / outputs | Allowed range |
| --- | --- | --- |
| `GATEWAY` | 0 / 1 | 0 / 1–16 |
| `HOST` | 1 / 1 | 1–16 / 1–16 |
| `ROUTER` | 1 / 1 | 1–16 / 1–16 |
| `FIREWALL` | 1 / 1 | 1–16 / 1–16 |
| `SERVICE` | 1 / 0 | 1–16 / 0 |
| `SWITCH` | 4 paired bidirectional interfaces (4 / 4 declarations) | 2–16 paired interfaces |
| `MERGE` | 3 / 1 | 2–16 / 1 |
| `SPLIT` | 1 / 3 | 1 / 2–16 |
| `FORWARDER` | 3 / 3 | 2–16 / 2–16 |
| `LOAD_BALANCER` | 1 / 3 | 1–16 / 2–16 |
| `MIRROR` | 1 / 2 | 1 / 2–16 |
| `PACKET_TAP` | 1 / 2 | 1 / 2–16 |
| `CUSTOM` | 1 / 1 | 1–16 / 1–16 |
| `FOLDER` | 1 / 1 | 1–16 / 1–16 |

These are desired logical nodes, not deployed devices. Their names do not imply MAC learning, firewall enforcement, traffic replication, load balancing, port mapping or route selection. Providers must declare the device kinds, layers, protocols, operations and execution semantics they support. `FIREWALL` models a same-layer policy boundary, `LOAD_BALANCER` models L4/L7 distribution intent, `MIRROR` and `PACKET_TAP` model an input copied to business and observation outputs, and `CUSTOM` represents a device whose capabilities have not been declared. The editor rejects a `LOAD_BALANCER` connection declared as L2/L3. For connections that include traffic appliances without provider capability metadata, it asks the operator to review the connection before saving it; confirmation does not claim the provider deployed or verified that behavior.

The outer topology can include registered Host assets and Router nodes. A Router may declare an IPv4 or IPv6 `ipAddress`; read-only Agent network snapshots match route gateways against selected Host interface addresses and top-level Router addresses to display known next hops. A unique address owner creates an observed route connection; duplicate owners remain ambiguous. Router addresses are static topology annotations and do not create or change operating-system routes. Saving the topology does not change a route table; general host route deployment still needs a capable Provider and an explicit operation.

Opening a folder allows editing its child topology and browsing a selected registered Kubernetes cluster. A folder may be bound to a cluster UUID; an unbound folder uses the server-configured default cluster. Namespace, Ingress, Service, Pod and container metadata is fetched on demand, bounded, and never reads Secrets. Registered clusters have independent kubeconfig contexts and encrypted credentials. Cluster Node mappings use Kubernetes UIDs and explicit Host asset selection. KubeEdge cluster summaries include CloudCore status and observation time; the current observer covers CloudCore Pods in the `kubeedge` namespace and reports binary CloudCore as `NOT_OBSERVED`. EdgeNode status reflects its Kubernetes edge role and Ready condition; it does not imply that Core installed or manages EdgeCore.

The cluster page also supports namespace-scoped Deployment management. It can create a Deployment with one container, one declared port and 1–20 replicas, scale a recorded Deployment to 0–20 replicas, or delete it. NaNPanel records the Deployment UID and ownership token at creation; list and mutation calls reject objects whose UID, labels or annotation no longer match. Other resources and Deployments that were not created and recorded by NaNPanel remain outside its management boundary. The imported kubeconfig must grant the required Kubernetes RBAC verbs for the specific action. These operations do not read Secrets and do not imply topology-to-provider reconciliation or Gateway routing.

## Endpoints

| Method | Path | Meaning |
| --- | --- | --- |
| `GET` | `/api/v1/topology/draft` | Read the current Revision and its strong ETag. |
| `PUT` | `/api/v1/topology/draft` | Validate and create a new immutable Revision using `If-Match`. |
| `GET` | `/api/v1/topology/revisions?limit=50` | List up to 100 recent Revisions. |
| `GET` | `/api/v1/topology/revisions/{revisionId}` | Read an immutable historical graph. |
| `GET` | `/api/v1/kubernetes/topology/namespaces` | Read bounded namespace inventory for the configured cluster. |
| `GET` | `/api/v1/kubernetes/topology/namespaces/{namespace}` | Read bounded Ingress, Service, Pod and container observations for a namespace. |
| `GET` | `/api/v1/kubernetes/clusters` | List imported independent clusters and their last health observation. |
| `POST` | `/api/v1/kubernetes/clusters` | Validate and import one explicit kubeconfig context. |
| `GET` | `/api/v1/kubernetes/clusters/{clusterId}/nodes` | Refresh bounded Nodes by UID; API failures retain stale observations and mark the cluster unavailable. |
| `PUT/DELETE` | `/api/v1/kubernetes/clusters/{clusterId}/nodes/{nodeUid}/host` | Explicitly map or unmap one registered cluster Node and Host asset. |
| `GET/POST` | `/api/v1/kubernetes/clusters/{clusterId}/namespaces/{namespace}/deployments` | List registered Deployment state or create a namespace-scoped managed Deployment. |
| `PUT` | `/api/v1/kubernetes/clusters/{clusterId}/namespaces/{namespace}/deployments/{name}/scale` | Scale only a registered, ownership-matching Deployment. |
| `DELETE` | `/api/v1/kubernetes/clusters/{clusterId}/namespaces/{namespace}/deployments/{name}` | Delete only a registered, ownership-matching Deployment. |

All endpoints require an active administrator session with the initial password change completed. PUT also requires CSRF and the exact `If-Match` returned by GET. Missing ETag returns 428, stale ETag returns 412, and invalid topology returns 422 `INVALID_TOPOLOGY`. Wildcards and multiple ETags are rejected. Errors do not change the current graph or history.

The current ETag is the strong value `"topology-<generation>"`. A successful PUT writes the immutable Revision, updates the current pointer and records `TOPOLOGY_DRAFT_UPDATED` in one database transaction. The response includes `revisionId`, `generation`, `updatedAt`, `updatedBy` and per-link state. PUT replaces the complete desired graph and does not use `Idempotency-Key`; after a transport failure, clients must read again and confirm before retrying.

The console compares a selected historical graph with the current canvas by stable node and link IDs, including changed node settings, ports, positions, and link protocol. Replaying a Revision only loads its desired graph into the editor as an unsaved draft. It does not call a Provider or change a host/Kubernetes network. Saving that draft uses the current head ETag and creates a new Revision; it never rewinds the current pointer to the historical row.

Submitted link `status` is always `UNVERIFIED`. Response `state` values are `PENDING`, `VERIFIED`, `ERROR` or `UNSUPPORTED`; the state describes runtime evidence and cannot be set by a draft write. New or changed links begin as `PENDING`. Saving the graph never marks a link healthy.

## Example

```json
{
  "nodes": [
    {
      "id": "8b395d24-5d5d-4f27-b70b-000000000001",
      "kind": "GATEWAY",
      "name": "公网入口",
      "position": { "x": 0, "y": 100 },
      "inputCount": 0,
      "outputCount": 1,
      "ports": [
        { "id": "out-1", "direction": "OUTPUT", "layer": "L4", "protocols": ["TCP", "UDP"] }
      ]
    },
    {
      "id": "8b395d24-5d5d-4f27-b70b-000000000002",
      "kind": "SERVICE",
      "name": "示例服务",
      "position": { "x": 350, "y": 100 },
      "inputCount": 1,
      "outputCount": 0,
      "ports": [
        { "id": "in-1", "direction": "INPUT", "layer": "L4", "protocols": ["TCP"] }
      ]
    }
  ],
  "links": [
    {
      "id": "8b395d24-5d5d-4f27-b70b-000000000003",
      "sourceNodeId": "8b395d24-5d5d-4f27-b70b-000000000001",
      "sourcePort": "out-1",
      "targetNodeId": "8b395d24-5d5d-4f27-b70b-000000000002",
      "targetPort": "in-1",
      "layer": "L4",
      "protocol": "TCP",
      "status": "UNVERIFIED"
    }
  ]
}
```

The browser Mock models identity, CSRF, ETag and graph validation in memory. It resets on page reload and does not provide durable Revision history or real Provider operations.
