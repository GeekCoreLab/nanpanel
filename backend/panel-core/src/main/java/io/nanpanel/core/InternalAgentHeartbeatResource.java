package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/internal/agent-heartbeats")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class InternalAgentHeartbeatResource {
  @Inject AuthStore store;
  @Inject ObjectMapper mapper;

  @ConfigProperty(name = "nanpanel.agent.gateway-token")
  String gatewayToken;

  @POST
  public Response heartbeat(
      @HeaderParam("X-NanPanel-Gateway-Token") String supplied,
      AgentEnrollmentResource.HeartbeatReport report) {
    if (gatewayToken.isBlank() || supplied == null ||
        !MessageDigest.isEqual(gatewayToken.getBytes(StandardCharsets.UTF_8),
                               supplied.getBytes(StandardCharsets.UTF_8)))
      throw fail(401, "GATEWAY_UNAUTHORIZED", "Gateway 身份无效");
    if (report == null || report.agentId() == null || report.version() == null ||
        report.certificateFingerprint() == null || report.hostname() == null ||
        report.os() == null || report.arch() == null || report.capabilities() == null ||
        report.version().isBlank() || report.version().length() > 64 ||
        report.hostname().isBlank() || report.hostname().length() > 255 ||
        report.hostname().chars().anyMatch(Character::isISOControl) ||
        report.os().length() > 255 || report.arch().length() > 64 ||
        report.capabilities().size() > 16 || report.capabilities().stream().anyMatch(
            capability -> !Set.of("host.inventory", "agent.heartbeat").contains(capability)) ||
        !report.certificateFingerprint().matches("[a-f0-9]{64}"))
      throw fail(400, "INVALID_HEARTBEAT", "Agent 心跳字段无效");

    final UUID agentId;
    try {
      agentId = UUID.fromString(report.agentId());
      if (!agentId.toString().equalsIgnoreCase(report.agentId())) throw new IllegalArgumentException();
    } catch (RuntimeException e) {
      throw fail(400, "INVALID_HEARTBEAT", "Agent ID 无效");
    }

    return store.transaction(c -> {
      var rows = query(c,
          "SELECT host_id,cert_fingerprint FROM agent WHERE id=?", agentId);
      if (rows.isEmpty() || rows.getFirst().get("cert_fingerprint") == null ||
          !MessageDigest.isEqual(
              String.valueOf(rows.getFirst().get("cert_fingerprint")).trim()
                  .getBytes(StandardCharsets.US_ASCII),
              report.certificateFingerprint().getBytes(StandardCharsets.US_ASCII)))
        throw fail(403, "AGENT_IDENTITY_MISMATCH", "Agent 证书与登记身份不匹配");
      UUID hostId = (UUID)rows.getFirst().get("host_id");
      update(c, "UPDATE agent SET version=?,last_seen=? WHERE id=?",
          report.version(), java.sql.Timestamp.from(Instant.now()), agentId);
      String capabilities = mapper.writeValueAsString(report.capabilities());
      String jsonParameter = c.getMetaData().getDatabaseProductName()
          .equalsIgnoreCase("PostgreSQL") ? "?::jsonb" : "?";
      update(c,
          "UPDATE host SET hostname=?,os=?,arch=?,capabilities=" + jsonParameter +
              " WHERE id=?",
          report.hostname(), report.os(), report.arch(), capabilities, hostId);
      return Response.noContent().build();
    });
  }
}
