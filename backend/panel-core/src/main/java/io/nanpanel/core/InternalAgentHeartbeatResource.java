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
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/internal/agent-heartbeats")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class InternalAgentHeartbeatResource {
  private static final Pattern NETWORK_INTERFACE = Pattern.compile("[A-Za-z0-9_.:-]{1,15}");
  private static final Pattern NETWORK_TOKEN = Pattern.compile("[A-Za-z0-9_.-]{0,64}");
  private static final Pattern NETWORK_ADDRESS = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
  private static final Pattern NETWORK_DESTINATION = Pattern.compile("[A-Za-z0-9:./-]{1,128}");
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
        !validNetworkInventory(report) ||
        report.version().isBlank() || report.version().length() > 64 ||
        report.hostname().isBlank() || report.hostname().length() > 255 ||
        report.hostname().chars().anyMatch(Character::isISOControl) ||
        report.os().length() > 255 || report.arch().length() > 64 ||
        report.capabilities().size() > 24 || report.capabilities().stream().anyMatch(
            capability -> !Set.of("host.inventory", "agent.heartbeat", "agent.self_update",
                "systemd.read", "systemd.restart", "systemd.journal.read", "package.install",
                "file.write.protected", "container.list", "container.logs", "container.start",
                "container.stop", "container.deploy", "network.firewall.expose",
                "network.inventory", "file.list", "file.read", "file.write", "file.delete")
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
                 service.subState().chars().anyMatch(Character::isISOControl)))) ||
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
                    "(id,host_id,unit_name,display_name,active_state,sub_state,observed_at) " +
                    "VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(), hostId, service.unitName(), service.displayName(),
                service.activeState(), service.subState(), java.sql.Timestamp.from(observedAt));
          } else {
            update(c,
                "UPDATE system_service_observation SET display_name=?,active_state=?," +
                    "sub_state=?,observed_at=? WHERE host_id=? AND unit_name=?",
                service.displayName(), service.activeState(), service.subState(),
                java.sql.Timestamp.from(observedAt), hostId, service.unitName());
          }
        }
        var priorServices = query(c,
            "SELECT unit_name FROM system_service_observation WHERE host_id=?", hostId);
        for (var prior : priorServices) {
          String unitName = String.valueOf(prior.get("unit_name"));
          if (!observedNames.contains(unitName)) {
            update(c,
                "UPDATE system_service_observation SET active_state='UNKNOWN',sub_state=''," +
                    "observed_at=? WHERE host_id=? AND unit_name=?",
                java.sql.Timestamp.from(observedAt), hostId, unitName);
          }
        }
      }
      if (report.networkObservedAtEpochMs() != null && report.networkObservedAtEpochMs() > 0) {
        String interfaces = mapper.writeValueAsString(report.networkInterfaces());
        String routes = mapper.writeValueAsString(report.networkRoutes());
        var existing = query(c, "SELECT host_id FROM host_network_inventory WHERE host_id=?", hostId);
        if (existing.isEmpty()) {
          update(c,
              "INSERT INTO host_network_inventory (host_id,interfaces,routes,observed_at) " +
                  "VALUES (?," + jsonParameter + "," + jsonParameter + ",?)",
              hostId, interfaces, routes,
              java.sql.Timestamp.from(Instant.ofEpochMilli(report.networkObservedAtEpochMs())));
        } else {
          update(c,
              "UPDATE host_network_inventory SET interfaces=" + jsonParameter + ",routes=" +
                  jsonParameter + ",observed_at=? WHERE host_id=?",
              interfaces, routes,
              java.sql.Timestamp.from(Instant.ofEpochMilli(report.networkObservedAtEpochMs())),
              hostId);
        }
      }
      return Response.noContent().build();
    });
  }

  private boolean validNetworkInventory(AgentEnrollmentResource.HeartbeatReport report) {
    Long observedAt = report.networkObservedAtEpochMs();
    if (observedAt == null || observedAt <= 0) {
      return !report.capabilities().contains("network.inventory") &&
          (report.networkInterfaces() == null || report.networkInterfaces().isEmpty()) &&
          (report.networkRoutes() == null || report.networkRoutes().isEmpty());
    }
    if (!report.capabilities().contains("network.inventory") ||
        observedAt > Instant.now().plusSeconds(60).toEpochMilli() ||
        observedAt < Instant.now().minusSeconds(300).toEpochMilli() ||
        report.networkInterfaces() == null || report.networkInterfaces().size() > 128 ||
        report.networkRoutes() == null || report.networkRoutes().size() > 2048)
      return false;
    for (var networkInterface : report.networkInterfaces()) {
      if (networkInterface == null || networkInterface.name() == null ||
          !NETWORK_INTERFACE.matcher(networkInterface.name()).matches() ||
          networkInterface.macAddress() == null || networkInterface.macAddress().length() > 32 ||
          networkInterface.macAddress().chars().anyMatch(Character::isISOControl) ||
          (!networkInterface.macAddress().isEmpty() &&
              !networkInterface.macAddress().matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) ||
          networkInterface.mtu() < 0 || networkInterface.mtu() > 100_000 ||
          !safeNetworkToken(networkInterface.state(), 32) ||
          networkInterface.addresses() == null || networkInterface.addresses().size() > 32)
        return false;
      for (var address : networkInterface.addresses()) {
        if (address == null || address.family() == null ||
            !Set.of("inet", "inet6").contains(address.family()) ||
            address.address() == null || address.address().length() > 45 ||
            !NETWORK_ADDRESS.matcher(address.address()).matches() ||
            address.prefixLength() > ("inet".equals(address.family()) ? 32 : 128) ||
            !safeNetworkToken(address.scope(), 32))
          return false;
      }
    }
    for (var route : report.networkRoutes()) {
      if (route == null || route.destination() == null ||
          !NETWORK_DESTINATION.matcher(route.destination()).matches() ||
          route.gateway() == null || route.gateway().length() > 45 ||
          (!route.gateway().isEmpty() && !NETWORK_ADDRESS.matcher(route.gateway()).matches()) ||
          route.interfaceName() == null ||
          (!route.interfaceName().isEmpty() &&
              !NETWORK_INTERFACE.matcher(route.interfaceName()).matches()) ||
          !safeNetworkToken(route.protocol(), 32) || !safeNetworkToken(route.scope(), 32) ||
          !safeNetworkToken(route.routeType(), 32) || !safeNetworkToken(route.table(), 64) ||
          route.metric() < 0 || route.metric() > 4_294_967_295L)
        return false;
    }
    return true;
  }

  private boolean safeNetworkToken(String value, int maxLength) {
    return value != null && value.length() <= maxLength &&
        !value.chars().anyMatch(Character::isISOControl) && NETWORK_TOKEN.matcher(value).matches();
  }
}
