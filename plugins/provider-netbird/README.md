# NetBird Provider

This PF4J plugin reconciles peer membership in a dedicated NetBird group. It
will only read or mutate group names under `NETBIRD_MANAGED_GROUP_PREFIX`
(default `nanpanel-managed-`). It preserves the group's existing `resources`
field, rejects duplicate or invalid peer IDs, and uses a live state fingerprint
as the plan precondition. It refuses to overwrite drift detected between plan,
apply, verify, and compensation.

## Runtime configuration

Core loads the packaged plugin when `NANPANEL_PROVIDERS_ENABLED=true`. The
container image places the plugin jar in `/work/providers`; the directory may
be overridden with `NANPANEL_PROVIDERS_DIRECTORY`. Configure the external API
with:

| Variable | Default | Notes |
| --- | --- | --- |
| `NETBIRD_API_URL` | `https://api.netbird.io` | HTTPS origin; local HTTP is allowed only for loopback fixtures. |
| `NETBIRD_API_TOKEN` | unset | Server-side PAT or compatible API token. Never pass it through the API or UI. |
| `NETBIRD_API_TOKEN_SCHEME` | `Token` | `Token` or `Bearer`. |
| `NETBIRD_MANAGED_GROUP_PREFIX` | `nanpanel-managed-` | Lowercase DNS-style prefix ending in `-`. |

NetBird accepts personal access tokens with the `Token` authorization scheme
and OAuth access tokens with `Bearer`; see the official [authentication
guide](https://docs.netbird.io/api/guides/authentication). Group reads and
replacement use the official [Groups API](https://docs.netbird.io/api/resources/groups).
If an exact-name lookup returns HTTP 404, the Provider confirms absence against
the bounded group-list response; self-hosted NetBird may use 404 for a missing
name-filtered group. Empty `peers` and `resources` values returned as null are
normalized to empty collections.

## Core workflow

All routes require an authenticated administrator session and CSRF protection
for writes. Mutating steps require recent reauthentication. Core stores the
opaque plan configuration and operation state in `network_provider_operation`
and audits each stage. The routes are:

- `GET /api/v1/network-providers`
- `POST /api/v1/network-providers/netbird-groups/observations`
- `POST /api/v1/network-providers/netbird-groups/plans`
- `GET /api/v1/network-providers/netbird-groups/operations/{id}`
- `POST .../operations/{id}/validate`, `/apply`, `/verify`, `/compensate`

The desired snapshot is JSON encoded inside `desiredSnapshot`:

```json
{"groupName":"nanpanel-managed-app","generation":3,"peerIds":["peer00001","peer00002"]}
```

Use the returned observation's `snapshot` as `observedSnapshot` when the caller
needs an explicit freshness precondition. Plan creation is read-only; external
changes occur only on `apply` after validation. If apply times out, Core keeps
the operation as `UNKNOWN`; verify or compensate before attempting another
plan. After a Core restart, the provider rebuilds ephemeral operation context
from the persisted plan only when live membership and preserved resource state
still match. A later external change is reported as drift and will not be
overwritten during compensation.

The provider deliberately manages only membership in an explicitly prefixed
group. It does not create users or peers, assign overlay addresses, manage
NetBird policies, alter routes, or prove an application-level service path.
Credential setup, multi-NAT behavior, production gateway switching, and
external service-probe evidence remain separate P2 acceptance work.

## Agent observation boundary

The Agent can optionally read a trusted local NetBird CLI and report peer name,
connection state, P2P/Relay type, and observation time. The Root Helper drops
addresses, keys, and relay endpoints. That read-only inventory is separate from
this management Provider and from business service probes. The isolated path
experiment and its current boundary are documented in the [P2 acceptance
record](../../tests/p2/README.md).
