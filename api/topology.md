# NetworkGraph and topology Revision API

The Core persists one shared workspace graph as immutable Revisions. Saving a desired graph creates a new Revision and advances the current pointer. A saved graph does not mean that routes were deployed or verified.

## Typed graph

Nodes keep editor coordinates and explicit typed ports. A port has an ID, direction (`INPUT` or `OUTPUT`), layer (`L2`, `L3`, `L4`, `L7`) and supported protocols. Supported protocol values are `ETHERNET`, `IP`, `TCP`, `UDP`, `SCTP`, `HTTP` and `HTTPS`; they must match the declared layer. A link declares its layer and protocol, and both endpoint ports must support them.

The editor currently creates `L4` ports that support TCP and UDP, and creates TCP links by default. A link always points from an output port to an input port. Each port can be used once. Missing references, self-links, duplicate IDs, invalid node shapes, incompatible port types, excess ports, cycles and unknown fields are rejected. Empty graphs are allowed; the limits are 100 nodes and 800 links.

`FOLDER` nodes create nested editing scopes. Nodes may set `parentId` to a folder UUID; omitted or null means the outermost topology. Folders can nest at most eight levels, and a link may only connect nodes in the same scope. The complete hierarchy is saved in the same revision and shares its ETag. Deleting a folder removes all descendants. A `HOST` node may set `assetId` to a registered host UUID; the Core verifies that the host exists and that the same host is not bound more than once in a draft.

| kind | Default inputs / outputs | Allowed range |
| --- | --- | --- |
| `GATEWAY` | 0 / 1 | 0 / 1–16 |
| `HOST` | 1 / 1 | 1–16 / 1–16 |
| `ROUTER` | 1 / 1 | 1–16 / 1–16 |
| `SERVICE` | 1 / 0 | 1–16 / 0 |
| `SWITCH` | 4 / 4 | 2–16 / 2–16 |
| `MERGE` | 3 / 1 | 2–16 / 1 |
| `SPLIT` | 1 / 3 | 1 / 2–16 |
| `FORWARDER` | 3 / 3 | 2–16 / 2–16 |
| `FOLDER` | 1 / 1 | 1–16 / 1–16 |

These are desired logical nodes, not deployed devices. Their names do not imply MAC learning, traffic replication, load balancing, port mapping or route selection. Providers must declare the capabilities and execution semantics they support.

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
