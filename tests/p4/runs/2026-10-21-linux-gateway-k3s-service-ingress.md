# Linux Gateway to target K3s Service ingress (2026-10-21)

## Scope

- Target: real single-node `k3s-1` cluster (`v1.33.5+k3s1`).
- Linux Gateway: temporary FRPC client on `bt-main`; public TLS gateway on
  `bj-aliyun`.
- Fixture: two disposable Ready HTTP Deployments and NodePort Services in one
  uniquely named K3s namespace. No DNS records or existing workload configs
  were changed.
- Run ID: `20261021070559-1425272`.

## Command and result

```sh
./tests/p2/frp-public-ingress.sh
```

Result: **PASS**. The Linux Gateway's FRPC reached both Ready K3s Service
backends directly. The public TLS endpoint passed certificate verification;
unauthenticated traffic received `401`, and authenticated traffic returned the
unique response from backend A. Switching the public Nginx route to backend B
returned B's response while preserving the access policy. Compensation
restored backend A and its response. The script checked the active Nginx
configuration hash after each transition.

## Cleanup and boundary

Independent follow-up checks confirmed that the temporary K3s namespace,
FRPC container, Gateway temporary directories, Nginx vhost, and Basic Auth file
were absent. `nginx -t` passed after cleanup. Existing K3s workloads and DNS
were not changed.

This satisfies the P4 live network-path slice for an ordinary Linux Gateway,
an explicit FRP-to-K3s-Service mapping, TLS/access health, and route
compensation. The fixture configures FRPC and Nginx directly; it does not
prove that NaNPanel topology edits automatically reconcile a remote Gateway or
that an arbitrary Host asset can be enrolled as a production Gateway.
