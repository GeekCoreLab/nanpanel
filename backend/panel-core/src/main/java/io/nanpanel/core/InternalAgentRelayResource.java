package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.MediaType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Path("/internal/agent-relay")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class InternalAgentRelayResource {
  @Inject AuthStore store;

  @Inject
  @ConfigProperty(name = "nanpanel.agent.gateway-token")
  String gatewayToken;

  @Inject
  @ConfigProperty(name = "nanpanel.gateway.cluster-id", defaultValue = "disabled")
  String expectedClusterId;

  @Inject
  @ConfigProperty(name = "nanpanel.agent.relay-endpoints", defaultValue = "disabled")
  String relayEndpointConfiguration;
  @Inject
  @ConfigProperty(name = "nanpanel.gateway.lease-seconds", defaultValue = "45")
  int gatewayLeaseSeconds;

  public record LeaseRequest(String ownerId, String clusterId, long epoch) {}
  public record Lease(long epoch, long leaseSeconds) {}
  public record RelayStatus(String relayId, String clusterId, long epoch,
                            String endpoint, boolean healthy) {}

  @POST
  @Path("lease/acquire")
  public Lease acquireLease(@HeaderParam("X-NanPanel-Gateway-Token") String supplied,
                            LeaseRequest request) {
    requireGateway(supplied);
    validateLeaseRequest(request);
    Instant now = Instant.now();
    return store.transaction(c -> {
      var rows = query(c,
          "SELECT owner_id,epoch,lease_until FROM agent_gateway_fencing WHERE id=1 FOR UPDATE");
      if (rows.isEmpty()) throw fail(503, "RELAY_LEASE_UNAVAILABLE", "无法读取 Gateway 租约");
      var row = rows.getFirst();
      String currentOwner = row.get("owner_id") == null ? null : String.valueOf(row.get("owner_id"));
      long currentEpoch = ((Number)row.get("epoch")).longValue();
      Instant expires = toInstant(row.get("lease_until"));
      if (request.ownerId().equals(currentOwner) && expires != null && expires.isAfter(now))
        return new Lease(currentEpoch, gatewayLeaseSeconds);
      if (expires != null && expires.isAfter(now))
        throw fail(409, "RELAY_LEASE_HELD", "另一个 Gateway 当前持有 Relay 租约");
      long nextEpoch = currentEpoch + 1;
      update(c,
          "UPDATE agent_gateway_fencing SET owner_id=?,epoch=?,lease_until=? WHERE id=1",
          request.ownerId(), nextEpoch,
          Timestamp.from(now.plusSeconds(gatewayLeaseSeconds)));
      return new Lease(nextEpoch, gatewayLeaseSeconds);
    });
  }

  @POST
  @Path("lease/renew")
  public Lease renewLease(@HeaderParam("X-NanPanel-Gateway-Token") String supplied,
                          LeaseRequest request) {
    requireGateway(supplied);
    validateLeaseRequest(request);
    if (request.epoch() <= 0)
      throw fail(400, "INVALID_RELAY_LEASE", "Gateway epoch 无效");
    Instant now = Instant.now();
    return store.transaction(c -> {
      var rows = query(c,
          "SELECT owner_id,epoch,lease_until FROM agent_gateway_fencing WHERE id=1 FOR UPDATE");
      if (rows.isEmpty()) throw fail(503, "RELAY_LEASE_UNAVAILABLE", "无法读取 Gateway 租约");
      var row = rows.getFirst();
      String currentOwner = row.get("owner_id") == null ? null : String.valueOf(row.get("owner_id"));
      long currentEpoch = ((Number)row.get("epoch")).longValue();
      Instant expires = toInstant(row.get("lease_until"));
      if (!request.ownerId().equals(currentOwner) || request.epoch() != currentEpoch ||
          expires == null || !expires.isAfter(now))
        throw fail(409, "RELAY_LEASE_LOST", "Gateway Relay 租约已失效");
      update(c,
          "UPDATE agent_gateway_fencing SET lease_until=? WHERE id=1 AND owner_id=? AND epoch=?",
          Timestamp.from(now.plusSeconds(gatewayLeaseSeconds)), request.ownerId(), request.epoch());
      return new Lease(currentEpoch, gatewayLeaseSeconds);
    });
  }

  private void validateLeaseRequest(LeaseRequest request) {
    if (request == null || request.ownerId() == null ||
        !request.ownerId().matches(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}") ||
        request.clusterId() == null || !expectedClusterId.equals(request.clusterId()) ||
        gatewayLeaseSeconds < 15 || gatewayLeaseSeconds > 120)
      throw fail(400, "INVALID_RELAY_LEASE", "Gateway 租约请求字段无效");
  }

  private static Instant toInstant(Object value) {
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
    if (value instanceof Instant instant) return instant;
    if (value == null) return null;
    return Instant.parse(String.valueOf(value));
  }

  @POST
  @Path("status")
  public Response relayStatus(@HeaderParam("X-NanPanel-Gateway-Token") String supplied,
                              RelayStatus status) {
    requireGateway(supplied);
    if (status == null || status.relayId() == null ||
        !status.relayId().matches("[A-Za-z0-9_.-]{1,64}") ||
        status.epoch() <= 0 || !expectedClusterId.equals(status.clusterId()))
      throw fail(400, "INVALID_RELAY_STATUS", "Relay 状态字段无效");
    List<String> configured = AgentEnrollmentResource.parseRelayEndpoints(
        relayEndpointConfiguration);
    final String targetName;
    try {
      String canonical = AgentEnrollmentResource.normalizeRelayEndpoint(status.endpoint());
      URI uri = URI.create(canonical);
      if (!configured.contains(canonical) || uri.getHost() == null)
        throw new IllegalArgumentException();
      targetName = uri.getHost().replace("[", "").replace("]", "")
          .toLowerCase(Locale.ROOT);
      if (targetName.isBlank()) throw new IllegalArgumentException();
    } catch (Exception invalid) {
      throw fail(400, "INVALID_RELAY_STATUS", "Relay 状态必须对应已配置的 HTTPS 源站");
    }
    Instant now = Instant.now();
    store.transaction(c -> {
      var leaseRows = query(c,
          "SELECT epoch,lease_until FROM agent_gateway_fencing WHERE id=1 FOR UPDATE");
      if (leaseRows.isEmpty())
        throw fail(503, "RELAY_LEASE_UNAVAILABLE", "无法验证 Gateway 租约");
      var lease = leaseRows.getFirst();
      Instant leaseUntil = toInstant(lease.get("lease_until"));
      if (((Number)lease.get("epoch")).longValue() != status.epoch() ||
          leaseUntil == null || !leaseUntil.isAfter(now))
        throw fail(409, "RELAY_LEASE_LOST", "Gateway Relay 租约已失效");
      var existing = query(c,
          "SELECT epoch,healthy,healthy_count,unhealthy_count,last_counted_at " +
              "FROM agent_relay_status WHERE relay_id=?",
          status.relayId());
      if (existing.isEmpty()) {
        update(c,
            "INSERT INTO agent_relay_status " +
                "(relay_id,cluster_id,target_name,epoch,healthy,healthy_count,unhealthy_count,last_seen,last_counted_at,updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?)",
            status.relayId(), status.clusterId(), targetName, status.epoch(),
            status.healthy(), status.healthy() ? 1 : 0, status.healthy() ? 0 : 1,
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
      } else if (((Number)existing.getFirst().get("epoch")).longValue() <= status.epoch()) {
        long previousEpoch = ((Number)existing.getFirst().get("epoch")).longValue();
        boolean wasHealthy = Boolean.TRUE.equals(existing.getFirst().get("healthy"));
        int previousCount = ((Number)existing.getFirst().get("healthy_count")).intValue();
        int previousFailures = ((Number)existing.getFirst().get("unhealthy_count")).intValue();
        Instant previousCountedAt = toInstant(existing.getFirst().get("last_counted_at"));
        boolean confirmationIntervalPassed = previousCountedAt == null ||
            !previousCountedAt.isAfter(now.minusSeconds(8));
        int healthyCount = !status.healthy() ? 0
            : previousEpoch == status.epoch() && wasHealthy
                ? confirmationIntervalPassed ? Math.min(3, previousCount + 1) : previousCount
                : 1;
        int unhealthyCount = status.healthy() ? 0
            : previousEpoch == status.epoch() && !wasHealthy
                ? confirmationIntervalPassed ? Math.min(3, previousFailures + 1) : previousFailures
                : 1;
        Instant lastCountedAt = previousEpoch != status.epoch() || wasHealthy != status.healthy() ||
            confirmationIntervalPassed ? now : previousCountedAt;
        update(c,
            "UPDATE agent_relay_status SET cluster_id=?,target_name=?,epoch=?,healthy=?,healthy_count=?,unhealthy_count=?," +
                "last_seen=?,last_counted_at=?,updated_at=? WHERE relay_id=?",
            status.clusterId(), targetName, status.epoch(), status.healthy(), healthyCount, unhealthyCount,
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(lastCountedAt),
            java.sql.Timestamp.from(now), status.relayId());
      }
      return null;
    });
    return Response.noContent().build();
  }

  private void requireGateway(String supplied) {
    if (gatewayToken.isBlank() || supplied == null ||
        !MessageDigest.isEqual(gatewayToken.getBytes(StandardCharsets.UTF_8),
                               supplied.getBytes(StandardCharsets.UTF_8)))
      throw fail(401, "GATEWAY_UNAUTHORIZED", "Gateway 身份无效");
  }
}
