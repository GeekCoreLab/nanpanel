package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.query;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/v1/kubernetes/discoveries")
@Produces(MediaType.APPLICATION_JSON)
public class KubernetesDiscoveryResource {
  public record DiscoveryList(List<HostRuntimeDiscovery> items) {}
  public record HostRuntimeDiscovery(UUID hostId, String hostname, String runtimeKind,
      boolean detected, boolean kubeletRunning, boolean apiServerLocal,
      Instant observedAt, Instant agentLastSeen) {}

  @Inject AuthStore store;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;

  @GET
  public Response list() {
    @SuppressWarnings("unchecked")
    var session = (Map<String, Object>)request.getProperty("session");
    if (session == null || session.get("user_id") == null) {
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    }
    List<HostRuntimeDiscovery> discoveries = store.transaction(connection -> query(connection,
        "SELECT h.id AS host_id,h.hostname,d.runtime_kind,d.detected,d.kubelet_running,"
            + "d.api_server_local,d.observed_at,a.last_seen AS agent_last_seen "
            + "FROM host_kubernetes_runtime_discovery d JOIN host h ON h.id=d.host_id "
            + "LEFT JOIN agent a ON a.host_id=h.id WHERE d.detected=TRUE "
            + "ORDER BY d.observed_at DESC,h.hostname,h.id LIMIT 100").stream()
        .map(KubernetesDiscoveryResource::discovery)
        .toList());
    return Response.ok(new DiscoveryList(discoveries))
        .header("Cache-Control", "no-store")
        .build();
  }

  private static HostRuntimeDiscovery discovery(Map<String, Object> row) {
    return new HostRuntimeDiscovery((UUID)row.get("host_id"),
        String.valueOf(row.get("hostname")), String.valueOf(row.get("runtime_kind")),
        Boolean.TRUE.equals(row.get("detected")), Boolean.TRUE.equals(row.get("kubelet_running")),
        Boolean.TRUE.equals(row.get("api_server_local")),
        instant(row.get("observed_at")), instant(row.get("agent_last_seen")));
  }

  private static Instant instant(Object value) {
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof java.time.OffsetDateTime dateTime) return dateTime.toInstant();
    if (value instanceof Instant instant) return instant;
    return null;
  }
}
