# NetBird live Provider acceptance

- Time: 2026-10-20 11:39 UTC.
- Fixture: NetBird server `0.75.1`, one connected peer from the temporary
  Docker/K3s tenant, and a run-scoped group in the dedicated managed prefix.
- Command:

  ```sh
  NANPANEL_P2_PEER_SSH_ALIAS=rwt-dev \
  NANPANEL_P2_PATH_WAIT_SECONDS=90 \
  NANPANEL_P2_NETBIRD_PROVIDER_LIVE=1 \
  NANPANEL_P2_NETBIRD_PROVIDER_ONLY=1 \
  bash tests/p2/netbird-path-switch.sh
  ```

The live Provider observed the absent group, planned and validated membership
for the connected peer, applied it, and verified the membership. A new Provider
instance restored the operation from its persisted plan, verified the live
state, and compensated by deleting the group. A final observation confirmed
that the group was absent. The command exited 0:

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
PASS: live NetBird peer membership observe, plan, apply, restart recovery, verify, and compensation completed.
```

The run also exercised the self-hosted API's missing-name-filter behavior: a
404 from the exact-name group query is checked against the bounded full group
list before the Provider treats the group as absent. Empty group responses may
encode `peers` and `resources` as null; the Provider normalizes these to empty
collections.

Cleanup completed: the temporary K3s namespace, peer/server containers, remote
state directories, and temporary Nginx vhost were removed. No run-scoped UDP
firewall rule remained, and `nginx -t` passed. PATs, peer IDs, and endpoint
addresses were not retained in this record.
