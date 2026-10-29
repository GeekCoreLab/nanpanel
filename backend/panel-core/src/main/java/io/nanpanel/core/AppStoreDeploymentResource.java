package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Deployment view for containers NaNPanel has deployed on hosts: the deployed list is
 * derived from successful {@code container.deploy} Helper operations that are not
 * superseded by a later redeploy or a successful {@code container.remove}, and is
 * enriched with advisory metadata (image, published loopback ports) recorded by the
 * console so the site form can pre-fill an upstream port. Existence always comes from
 * the operation log; uninstall is performed through the typed {@code container.remove}
 * Helper operation. This resource never mutates external state.
 */
@Path("/api/v1/app-store/deployments")
@Produces(MediaType.APPLICATION_JSON)
public class AppStoreDeploymentResource {

  @Inject AuthStore store;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;

  private void session() {
    @SuppressWarnings("unchecked")
    Map<String, Object> value = (Map<String, Object>)request.getProperty("session");
    if (value == null || value.get("user_id") == null) {
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    }
  }

  @GET
  public Map<String, Object> list() {
    session();
    var rows = store.transaction(connection -> query(connection, """
        SELECT o.host_id, o.target AS container_name, o.created_at, h.hostname,
               d.host_port, d.container_port, d.image
        FROM operation o JOIN host h ON h.id = o.host_id
        LEFT JOIN host_container_deployment d
          ON d.host_id = o.host_id AND d.container_name = o.target
        WHERE o.kind = 'HOST_HELPER_OPERATION' AND o.capability = 'container.deploy'
          AND o.status = 'SUCCEEDED'
          AND NOT EXISTS (
            SELECT 1 FROM operation n
            WHERE n.kind = 'HOST_HELPER_OPERATION' AND n.capability = 'container.deploy'
              AND n.status = 'SUCCEEDED' AND n.host_id = o.host_id AND n.target = o.target
              AND n.created_at > o.created_at)
          AND NOT EXISTS (
            SELECT 1 FROM operation r
            WHERE r.kind = 'HOST_HELPER_OPERATION' AND r.capability = 'container.remove'
              AND r.status = 'SUCCEEDED' AND r.host_id = o.host_id AND r.target = o.target
              AND r.created_at > o.created_at)
        ORDER BY o.created_at DESC
        LIMIT 200"""));
    var items = new ArrayList<Map<String, Object>>(rows.size());
    for (var row : rows) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("hostId", String.valueOf(row.get("host_id")));
      item.put("hostname", row.get("hostname"));
      item.put("containerName", row.get("container_name"));
      item.put("deployedAt", stamp(row.get("created_at")));
      item.put("image", row.get("image"));
      item.put("hostPort", row.get("host_port") == null ? null : ((Number) row.get("host_port")).intValue());
      item.put("containerPort", row.get("container_port") == null ? null
          : ((Number) row.get("container_port")).intValue());
      items.add(item);
    }
    return Map.of("items", items);
  }

  public record DeploymentMetadataRequest(String hostId, String containerName, String image,
                                          Integer hostPort, Integer containerPort) {}

  /**
   * Advisory metadata recorded by the console right after a one-click deploy, so the
   * site form can pre-fill the container's published loopback port. Container existence
   * still comes from the operation-derived query above; a stale row is never shown.
   */
  @POST
  public Response record(DeploymentMetadataRequest input) {
    session();
    if (input == null || input.containerName() == null
        || !input.containerName().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,62}")
        || input.image() == null || input.image().isBlank() || input.image().length() > 255
        || !validOptionalPort(input.hostPort()) || !validOptionalPort(input.containerPort())) {
      throw fail(422, "VALIDATION_FAILED", "部署元数据参数无效");
    }
    final UUID hostId;
    try {
      hostId = UUID.fromString(input.hostId());
    } catch (RuntimeException invalid) {
      throw fail(422, "VALIDATION_FAILED", "hostId 必须是 UUID");
    }
    var hosts = store.transaction(connection -> query(connection, "SELECT id FROM host WHERE id = ?", hostId));
    if (hosts.isEmpty()) throw fail(404, "HOST_NOT_FOUND", "主机未登记");
    long now = System.currentTimeMillis();
    store.transaction(connection -> {
      int updated = update(connection,
          "UPDATE host_container_deployment SET image=?, host_port=?, container_port=? "
              + "WHERE host_id=? AND container_name=?",
          input.image(), input.hostPort(), input.containerPort(), hostId, input.containerName());
      if (updated == 0) {
        update(connection,
            "INSERT INTO host_container_deployment "
                + "(host_id, container_name, image, host_port, container_port, created_at) "
                + "VALUES (?,?,?,?,?,?)",
            hostId, input.containerName(), input.image(), input.hostPort(),
            input.containerPort(), now);
      }
      return null;
    });
    Map<String, Object> stored = new LinkedHashMap<>();
    stored.put("hostId", hostId.toString());
    stored.put("containerName", input.containerName());
    stored.put("image", input.image());
    stored.put("hostPort", input.hostPort());
    stored.put("containerPort", input.containerPort());
    return Response.ok(stored).build();
  }

  private static boolean validOptionalPort(Integer port) {
    return port == null || (port >= 1 && port <= 65535);
  }

  private String stamp(Object value) {
    if (value == null) return null;
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().toString();
    if (value instanceof java.time.OffsetDateTime timestamp) return timestamp.toInstant().toString();
    if (value instanceof Instant timestamp) return timestamp.toString();
    if (value instanceof java.util.Date timestamp) return timestamp.toInstant().toString();
    if (value instanceof Number timestamp) return Instant.ofEpochMilli(timestamp.longValue()).toString();
    return value.toString();
  }
}