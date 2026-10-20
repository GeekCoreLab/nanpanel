# NetworkGraph and topology Revision API

The Core persists one shared workspace graph as immutable Revisions. Saving a desired graph creates a new Revision and advances the current pointer. A saved graph does not mean that routes were deployed or verified.

## Typed graph

Nodes keep editor coordinates and explicit typed ports. A port has an ID, direction (`INPUT` or `OUTPUT`), layer (`L2`, `L3`, `L4`, `L7`) and supported protocols. Supported protocol values are `ETHERNET`, `IP`, `TCP`, `UDP`, `SCTP`, `HTTP` and `HTTPS`; they must match the declared layer. A link declares its layer and protocol, and both endpoint ports must support them.

The editor currently creates `L4` ports that support TCP and UDP, and creates TCP links by default. A link always points from an output port to an input port. Each port can be used once. Missing references, self-links, duplicate IDs, invalid node shapes, incompatible port types, excess ports, cycles and unknown fields are rejected. Empty graphs are allowed; the limits are 100 nodes and 800 links.

| kind | Default inputs / outputs | Allowed range |
| --- | --- | --- |
| `GATEWAY` | 0 / 1 | 0 / 1–16 |
| `HOST` | 1 / 1 | 1–16 / 1–16 |
| `SERVICE` | 1 / 0 | 1–16 / 0 |
| `SWITCH` | 4 / 4 | 2–16 / 2–16 |
| `MERGE` | 3 / 1 | 2–16 / 1 |
| `SPLIT` | 1 / 3 | 1 / 2–16 |
| `FORWARDER` | 3 / 3 | 2–16 / 2–16 |

These are desired logical nodes, not deployed devices. Their names do not imply MAC learning, traffic replication, load balancing, port mapping or route selection. Providers must declare the capabilities and execution semantics they support.

## Endpoints

| Method | Path | Meaning |
| --- | --- | --- |
| `GET` | `/api/v1/topology/draft` | Read the current Revision and its strong ETag. |
| `PUT` | `/api/v1/topology/draft` | Validate and create a new immutable Revision using `If-Match`. |
| `GET` | `/api/v1/topology/revisions?limit=50` | List up to 100 recent Revisions. |
| `GET` | `/api/v1/topology/revisions/{revisionId}` | Read an immutable historical graph. |

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
