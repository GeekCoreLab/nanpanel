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
import java.util.HashSet;
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

  @Inject
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
        report.helperStatus() == null ||
        !Set.of("AVAILABLE", "UNAVAILABLE").contains(report.helperStatus()) ||
        (report.capabilities() != null &&
            report.capabilities().contains("agent.self_update") !=
                "AVAILABLE".equals(report.helperStatus())) ||
        (report.capabilities() != null && report.capabilities().contains("systemd.read") &&
            (report.systemdObservedAtEpochMs() == null || report.systemdObservedAtEpochMs() <= 0)) ||
        (report.systemdObservedAtEpochMs() != null && report.systemdObservedAtEpochMs() > 0 &&
            (report.systemdObservedAtEpochMs() > Instant.now().plusSeconds(60).toEpochMilli() ||
             report.systemdObservedAtEpochMs() < Instant.now().minusSeconds(300).toEpochMilli())) ||
        report.version().isBlank() || report.version().length() > 64 ||
        report.hostname().isBlank() || report.hostname().length() > 255 ||
        report.hostname().chars().anyMatch(Character::isISOControl) ||
        report.os().length() > 255 || report.arch().length() > 64 ||
        report.capabilities().size() > 24 || report.capabilities().stream().anyMatch(
            capability -> !Set.of("host.inventory", "agent.heartbeat", "agent.self_update",
                "systemd.read", "systemd.restart", "systemd.journal.read", "package.install",
                "file.write.protected", "container.list", "container.logs", "container.start",
                "container.stop", "container.deploy", "network.firewall.expose")
                .contains(capability)) ||
        (report.systemdServices() != null &&
            (report.systemdServices().size() > 512 ||
             report.systemdServices().stream().anyMatch(service ->
                 service == null || service.unitName() == null || service.displayName() == null ||
                 service.activeState() == null || service.subState() == null ||
                 !service.unitName().matches("[A-Za-z0-9_.@:-]{1,247}\\.service") ||
                 service.displayName().isBlank() || service.displayName().length() > 255 ||
                 service.displayName().chars().anyMatch(Character::isISOControl) ||
                 !Set.of("UNKNOWN", "ACTIVE", "INACTIVE", "ACTIVATING", "DEACTIVATING", "RELOADING", "FAILED")
                     .contains(service.activeState()) || service.subState().length() > 64 ||
                 service.subState().chars().anyMatch(Character::isISOControl) ||
                 (service.restartAllowed() &&
                    (!"AVAILABLE".equals(report.helperStatus()) ||
                     !report.capabilities().contains("systemd.restart"))) ||
                 (service.journalReadAllowed() &&
                    (!"AVAILABLE".equals(report.helperStatus()) ||
                     !report.capabilities().contains("systemd.journal.read")))))) ||
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
      update(c, "UPDATE agent SET version=?,last_seen=?,helper_status=? WHERE id=?",
          report.version(), java.sql.Timestamp.from(Instant.now()),
          report.helperStatus(), agentId);
      String capabilities = mapper.writeValueAsString(report.capabilities());
      String jsonParameter = c.getMetaData().getDatabaseProductName()
          .equalsIgnoreCase("PostgreSQL") ? "?::jsonb" : "?";
      update(c,
          "UPDATE host SET hostname=?,os=?,arch=?,capabilities=" + jsonParameter +
              " WHERE id=?",
          report.hostname(), report.os(), report.arch(), capabilities, hostId);
      if (report.systemdServices() != null && report.systemdObservedAtEpochMs() != null &&
          report.systemdObservedAtEpochMs() > 0) {
        Instant observedAt = Instant.ofEpochMilli(report.systemdObservedAtEpochMs());
        var observedNames = new HashSet<String>();
        for (var service : report.systemdServices()) {
          observedNames.add(service.unitName());
          var existing = query(c,
              "SELECT id FROM system_service_observation WHERE host_id=? AND unit_name=?",
              hostId, service.unitName());
          if (existing.isEmpty()) {
            update(c,
                "INSERT INTO system_service_observation " +
                    "(id,host_id,unit_name,display_name,active_state,sub_state,observed_at,restart_allowed,journal_read_allowed) " +
                    "VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), hostId, service.unitName(), service.displayName(),
                service.activeState(), service.subState(), java.sql.Timestamp.from(observedAt),
                service.restartAllowed(), service.journalReadAllowed());
          } else {
            update(c,
                "UPDATE system_service_observation SET display_name=?,active_state=?," +
                    "sub_state=?,observed_at=?,restart_allowed=?,journal_read_allowed=? WHERE host_id=? AND unit_name=?",
                service.displayName(), service.activeState(), service.subState(),
                java.sql.Timestamp.from(observedAt), service.restartAllowed(),
                service.journalReadAllowed(), hostId, service.unitName());
          }
        }
        var priorServices = query(c,
            "SELECT unit_name FROM system_service_observation WHERE host_id=?", hostId);
        for (var prior : priorServices) {
          String unitName = String.valueOf(prior.get("unit_name"));
          if (!observedNames.contains(unitName)) {
            update(c,
                "UPDATE system_service_observation SET active_state='UNKNOWN',sub_state=''," +
                    "observed_at=?,restart_allowed=FALSE,journal_read_allowed=FALSE WHERE host_id=? AND unit_name=?",
                java.sql.Timestamp.from(observedAt), hostId, unitName);
          }
        }
      }
      return Response.noContent().build();
    });
  }
}
