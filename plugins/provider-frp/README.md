# FRP TCP Route Provider

This PF4J plugin manages TCP proxy entries whose names use the
`nanpanel-managed-` prefix in one explicitly configured FRPC TOML file. It
preserves unrelated TOML text, rejects remote-port collisions, hashes the full
configuration as a freshness precondition, validates candidate files with
`frpc verify`, writes through an atomic replacement, and uses `frpc reload` to
apply proxy-only changes. Verification checks `frpc status` and an exact HTTP
response body from a configured route probe. Compensation restores only the
managed proxy and refuses to proceed if any other configuration changed.

The persisted Core plan contains the managed route fields and before/after
configuration hashes. It does not contain the full FRPC file or its auth token.
The TOML translator carries the original source inside its normalized model so
that unknown settings survive a render; callers that submit a config snapshot
to the Translator receive that source back as part of the result.

## Configuration

The plugin is packaged in the Core image and loaded with the other providers.
It remains unconfigured unless all required values are supplied to the Core
process:

| Variable | Purpose |
| --- | --- |
| `NANPANEL_FRPC_CONFIG` | Absolute path to a regular `.toml` file, at most 1 MiB, owned by the Core process user. |
| `NANPANEL_FRPC_BINARY` | Optional FRPC executable path; defaults to `frpc`. |
| `NANPANEL_FRPC_RELOAD` | Must be `true` to enable apply and compensation. FRPC must have its WebServer enabled for `reload` and `status`. |
| `NANPANEL_FRP_PROBE_URL` | HTTP(S) URL checked after apply; credentials and fragments are rejected. |
| `NANPANEL_FRP_PROBE_BODY` | Exact UTF-8 response body expected from the probe, at most 8 KiB. |

FRPC's own documentation requires its WebServer for dynamic reload. If it is
not available, the Provider reports that a restart is required and rejects
mutating operations. It does not restart arbitrary services or run a shell.
See the official [FRPC client reload documentation](https://gofrp.org/zh-cn/docs/features/common/client/)
and [configuration validation documentation](https://gofrp.org/zh-cn/docs/features/common/configure/).

The current execution mode requires the Core process to have access to the
local FRPC config, binary, and route probe. It does not provide remote Agent
file transfer, FRPC process discovery, or a production public Gateway control
plane. Configure only a file on a host where this local execution boundary is
appropriate.

## Core workflow

All Provider endpoints require an authenticated session, CSRF protection for
writes, and recent reauthentication for state changes. Core persists each
operation and audit entry. Use the observation snapshot as the optional plan
precondition, then call `validate`, `apply`, `verify`, and `compensate` through
the generic `/api/v1/network-providers/frp-routes/...` API. A timed-out apply
is an unknown outcome; restore the operation from its persisted hashes and
verify or compensate before retrying.

Only TCP proxy blocks under the managed prefix can be edited. The plugin
retains unrelated routes and unknown fields, including auth settings, and will
not overwrite configuration drift. HTTP probing is an explicit fixed URL and
body supplied by the Core operator, not by an API request.
