# Public FRP ingress, TLS, access policy, and backend recovery — 2026-10-20

Run identifier: 20261020105924-316706 (UTC start time: 10:59:24).

Command: ./tests/p2/frp-public-ingress.sh

The test used the public Nginx TLS vhost and existing FRPS service, a temporary
FRPC client on the NAT host, and two disposable K3s HTTP backends. Curl used its
default certificate validation with a run-specific hostname resolved to the
public host; no DNS record was changed. A temporary Basic Auth password was
generated for the run and was not retained in this report.

Sanitized output:

    PASS: public TLS certificate validation succeeded; unauthenticated request returned 401 and authorized request reached backend A.
    PASS: gateway backend change reached backend B while the access policy remained enforced.
    PASS: compensation restored backend A and preserved the TLS/access policy.
    PASS: isolated FRP public ingress test completed.
    exit=0

The active Nginx configuration hash was checked after each stage. Cleanup was
verified: the temporary Nginx vhost, Basic Auth file, public and NAT host
directories, FRPC container, and K3s namespace were absent; Nginx passed its
configuration check after cleanup. This proves the public test path and
compensation behavior. It does not implement Core-managed production gateway
reconciliation.
