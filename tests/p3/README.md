# P3 topology and plugin protocol evidence

Run the focused validation from the repository root:

```sh
./mvnw -pl backend/panel-core,plugins/provider-frp -am test
(cd web && npm test && npm run build)
python -c 'import yaml; yaml.safe_load(open("api/openapi.yaml", encoding="utf-8"))'
git diff --check
```

The isolated live FRP Provider test additionally requires Podman, `curl`, Python
3, and the pinned FRP/BusyBox images. It creates a temporary network and three
uniquely named containers, then removes those resources when it finishes:

```sh
sh tests/p3/frp-provider-live.sh
```

| P3 item | Evidence | Boundary |
| --- | --- | --- |
| Typed NetworkGraph and Topology Revision | `SystemResourceTest` exercises authenticated draft reads, typed writes, generation/ETag conflict, immutable history, and protocol-layer rejection. | The regular topology page stores desired state; it does not apply Core-managed production routes. |
| Generic Provider and Translator protocol | `NetworkProviderApiTest` boots Core with a separately built PF4J fixture JAR and exercises provider discovery, capabilities, observation, translator normalize/render, idempotent plans, validate/apply/verify/compensate transitions, persisted operation reads, and audit events. `FrpPluginLifecycleTest` also loads the actual FRP Provider and Translator from a separate JAR. | Plugin signing, external installation, permissions beyond the Core session boundary, and upgrade migration are not implemented. |
| Plugin lifecycle and data retention | Core API integration confirms disable is refused while a Provider operation is open, then permits disable and re-enable after compensation. The PF4J test confirms FRP extensions disappear and return and an unrelated FRP config file stays unchanged. | Disable stops the plugin; it does not unload the JAR or erase plugin-owned data. |
| FRP plan/apply/verify/compensate and drift | Provider tests cover config hash preconditions, collisions, restart restoration, atomic file replacement, safe compensation, and drift refusal. On 2026-10-20, `sh tests/p3/frp-provider-live.sh` passed with FRPC v0.71.0: the PF4J Provider itself ran `verify`, atomically changed the live route, ran `reload` and `status`, verified the exact HTTP response, restored operation state in a new Provider instance, and compensated back to the original route. The script confirmed the original port served again and the changed port was closed. The independent P0 route transaction test also passed on this date. | The live lab uses isolated local containers and fixture credentials; it does not change `k3s-1`. The Provider currently manages only a local Core-visible TOML file. |
| Link state UI | The console renders pending, verified, abnormal, and unsupported states with distinct colors and labels; typed L4 ports and TCP/UDP protocol compatibility are validated. | Provider verification does not automatically update topology links yet; the normal topology API returns `PENDING`. |

The FRP Provider requires a configured TOML path, FRPC executable, enabled
FRPC WebServer reload support, and an exact HTTP probe. See
[`plugins/provider-frp/README.md`](../../plugins/provider-frp/README.md).
The tested `k3s-1` cluster is not modified by these P3 tests.
