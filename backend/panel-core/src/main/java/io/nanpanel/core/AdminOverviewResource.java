package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.query;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/v1/admin/overview")
@Produces(MediaType.APPLICATION_JSON)
public class AdminOverviewResource {
  @Inject AuthStore store;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;

  private void authenticated() {
    var session = (Map<String, Object>)request.getProperty("session");
    if (session == null || session.get("user_id") == null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
  }

  private long count(Map<String, Object> row, String field) {
    return ((Number)row.get(field)).longValue();
  }

  @GET
  public Map<String, Object> get() {
    authenticated();
    return store.transaction(c -> {
      Instant observedAt = Instant.now();
      var settings = query(c,
                           "SELECT host_offline_after_seconds FROM " +
                               "platform_settings WHERE id=1");
      int threshold = settings.isEmpty()
          ? 90
          : ((Number)settings.getFirst().get("host_offline_after_seconds"))
                .intValue();
      var cutoff = java.sql.Timestamp.from(observedAt.minusSeconds(threshold));
      var hostRows = query(
          c,
          "SELECT COUNT(*) AS total," +
              "COUNT(*) FILTER (WHERE a.last_seen>=?) AS online," +
              "COUNT(*) FILTER (WHERE a.last_seen IS NOT NULL AND a.last_seen<?) AS offline," +
              "COUNT(*) FILTER (WHERE a.id IS NULL OR a.last_seen IS NULL) AS unknown " +
              "FROM host h LEFT JOIN LATERAL (SELECT id,last_seen FROM agent " +
              "WHERE host_id=h.id ORDER BY last_seen DESC NULLS LAST,id LIMIT 1) a " +
              "ON TRUE",
          cutoff, cutoff);
      var operationRows = query(
          c,
          "SELECT COUNT(*) FILTER (WHERE status='PENDING') AS pending," +
              "COUNT(*) FILTER (WHERE status='RUNNING') AS running," +
              "COUNT(*) FILTER (WHERE status='UNKNOWN') AS unknown," +
              "COUNT(*) FILTER (WHERE status='FAILED' AND updated_at>=?) AS failed " +
              "FROM operation",
          java.sql.Timestamp.from(observedAt.minusSeconds(86400)));
      var userRows = query(
          c, "SELECT COUNT(*) AS active FROM panel_user WHERE status='ACTIVE'");
      var host = hostRows.getFirst();
      var operations = operationRows.getFirst();
      var hostSummary = new LinkedHashMap<String, Object>();
      hostSummary.put("total", count(host, "total"));
      hostSummary.put("online", count(host, "online"));
      hostSummary.put("offline", count(host, "offline"));
      hostSummary.put("unknown", count(host, "unknown"));
      var operationSummary = new LinkedHashMap<String, Object>();
      operationSummary.put("pending", count(operations, "pending"));
      operationSummary.put("running", count(operations, "running"));
      operationSummary.put("unknown", count(operations, "unknown"));
      operationSummary.put("failedLast24Hours", count(operations, "failed"));
      var result = new LinkedHashMap<String, Object>();
      result.put("observedAt", observedAt.toString());
      result.put("hosts", hostSummary);
      result.put("operations", operationSummary);
      result.put("activeAdminUsers", count(userRows.getFirst(), "active"));
      return result;
    });
  }
}
