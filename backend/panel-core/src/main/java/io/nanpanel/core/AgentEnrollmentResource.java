package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.net.URI;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
public class AgentEnrollmentResource {
  @Inject AuthStore store;
  @Inject AgentCertificateAuthority authority;
  @Inject RelayFallbackSigner relayFallbackSigner;
  @Inject SessionFilter limits;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  @Inject
  @ConfigProperty(name = "nanpanel.agent.binary")
  String agentBinary;

  @Inject
  @ConfigProperty(name = "nanpanel.agent.helper-binary", defaultValue = "/work/nanpanel-helper")
  String helperBinary;

  @Inject
  @ConfigProperty(name = "nanpanel.agent.gateway-port", defaultValue = "30444")
  int gatewayPort;

  @Inject
  @ConfigProperty(name = "nanpanel.agent.relay-endpoints", defaultValue = "disabled")
  String relayEndpointConfiguration;

  @Inject
  @ConfigProperty(name = "nanpanel.agent.enrollment-validity-seconds", defaultValue = "900")
  int enrollmentValiditySeconds;

  public record CreateEnrollment(String hostname) {}
  public record RedeemEnrollment(String csrPem) {}
  public record HeartbeatReport(
      String agentId,
      String version,
      String certificateFingerprint,
      String hostname,
      String os,
      String arch,
      List<String> capabilities,
      String helperStatus,
      Long systemdObservedAtEpochMs,
      List<SystemdServiceReport> systemdServices,
      Long networkObservedAtEpochMs,
      List<NetworkInterfaceReport> networkInterfaces,
      List<NetworkRouteReport> networkRoutes,
      Long netbirdOverlayObservedAtEpochMs,
      OverlayNetworkReport netbirdOverlay,
      Long netbirdPeerPathsObservedAtEpochMs,
      List<NetbirdPeerPathReport> netbirdPeerPaths,
      KubernetesRuntimeReport kubernetesRuntime) {}
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SystemdServiceReport(String unitName, String displayName,
                                     String activeState, String subState) {}
  public record NetworkInterfaceReport(String name, String macAddress, long mtu,
                                       String state, List<NetworkAddressReport> addresses) {}
  public record NetworkAddressReport(String family, String address, long prefixLength,
                                     String scope) {}
  public record NetworkRouteReport(String destination, String gateway, String interfaceName,
                                   String protocol, String scope, String routeType,
                                   String table, long metric) {}
  public record OverlayNetworkReport(Long peerCount, Long connectedPeerCount,
                                     Long p2pPeerCount, Long relayedPeerCount) {}
  public record NetbirdPeerPathReport(String peerName, String status,
                                      String connectionType) {}
  public record KubernetesRuntimeReport(boolean detected, boolean kubeletRunning,
      boolean apiServerLocal, String runtimeKind, Long observedAtEpochMs) {}

  private String actor() {
    @SuppressWarnings("unchecked")
    var session = (Map<String, Object>)request.getProperty("session");
    if (session == null || session.get("user_id") == null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    return String.valueOf(session.get("user_id"));
  }

  private UUID uuid(String value, String field) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException e) {
      throw fail(400, "VALIDATION_FAILED", field + " 必须为 UUID");
    }
  }

  @GET
  @Path("agent-installation")
  public Object installation() {
    return Map.of("controllerFingerprint", authority.controllerFingerprint(),
                  "gatewayPort", gatewayPort,
                  "relayEndpoints", relayEndpoints(),
                  "relayFallback", relayFallbackSigner.manifest(),
                  "installerPath", "/agent-install.sh",
                  "agentDownloadPath", "/api/v1/agent-download/linux-amd64",
                  "helperDownloadPath", "/api/v1/helper-download/linux-amd64");
  }

  @GET
  @Path("agent-controller-cert")
  @Produces("application/x-pem-file")
  public Response controllerCertificate() {
    byte[] certificate = authority.controllerCertificatePem();
    return Response.ok(certificate, "application/x-pem-file")
        .header("Content-Disposition", "attachment; filename=nanpanel-controller.pem")
        .build();
  }

  @GET
  @Path("agent-download/linux-amd64")
  @Produces(MediaType.APPLICATION_OCTET_STREAM)
  public Response downloadAgent() {
    java.nio.file.Path binary = java.nio.file.Path.of(agentBinary);
    if (!Files.isRegularFile(binary) || !Files.isReadable(binary))
      throw fail(503, "AGENT_BINARY_UNAVAILABLE", "当前版本未包含 Linux x86_64 Agent");
    return Response.ok((StreamingOutput)output -> Files.copy(binary, output),
                       MediaType.APPLICATION_OCTET_STREAM)
        .header("Content-Disposition", "attachment; filename=nanpanel-agent")
        .header("Cache-Control", "public, max-age=300")
        .build();
  }

  @GET
  @Path("helper-download/linux-amd64")
  @Produces(MediaType.APPLICATION_OCTET_STREAM)
  public Response downloadHelper() {
    java.nio.file.Path binary = java.nio.file.Path.of(helperBinary);
    if (!Files.isRegularFile(binary) || !Files.isReadable(binary))
      throw fail(503, "HELPER_BINARY_UNAVAILABLE", "当前版本未包含 Linux x86_64 Helper");
    return Response.ok((StreamingOutput)output -> Files.copy(binary, output),
                       MediaType.APPLICATION_OCTET_STREAM)
        .header("Content-Disposition", "attachment; filename=nanpanel-helper")
        .header("Cache-Control", "public, max-age=300")
        .build();
  }

  @POST
  @Path("agent-enrollments")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response create(CreateEnrollment input) {
    String actor = actor();
    limits.limit("agent-enrollment-create:" + AuthStore.digest(actor));
    String hostname = input == null || input.hostname() == null
        ? "" : input.hostname().trim();
    if (hostname.isBlank() || hostname.length() > 255 ||
        hostname.chars().anyMatch(Character::isISOControl))
      throw fail(422, "VALIDATION_FAILED", "主机名称须为 1–255 个可见字符");

    List<String> configuredRelays = relayEndpoints();
    RelayFallbackSigner.Manifest relayFallback = relayFallbackSigner.manifest();
    String controllerFingerprint = authority.controllerFingerprint();
    String token = AuthStore.token();
    UUID enrollmentId = UUID.randomUUID();
    UUID hostId = UUID.randomUUID();
    UUID agentId = UUID.randomUUID();
    Instant expiresAt = Instant.now().plusSeconds(
        Math.max(60, Math.min(1800, enrollmentValiditySeconds)));
    store.transaction(c -> {
      update(c,
          "INSERT INTO host (id,hostname,os,arch,capabilities,status) " +
              "VALUES (?,?,NULL,NULL," + jsonExpression(c) + ",'UNKNOWN')",
          hostId, hostname, "[]");
      update(c,
          "INSERT INTO agent (id,host_id,cert_identity,version,last_seen) " +
              "VALUES (?,?,?,NULL,NULL)",
          agentId, hostId, "pending:" + agentId);
      update(c,
          "INSERT INTO agent_enrollment " +
              "(id,host_id,agent_id,token_hash,expires_at,created_by) " +
              "VALUES (?,?,?,?,?,?)",
          enrollmentId, hostId, agentId, AuthStore.digest(token),
          java.sql.Timestamp.from(expiresAt), actor);
      AuthStore.audit(c, actor, "AGENT_ENROLLMENT_CREATED", "host",
          hostId.toString(), "SUCCEEDED", http.remoteAddress() == null
              ? null : http.remoteAddress().host(), "创建一次性 Agent 接入码");
      return null;
    });

    return Response.status(Response.Status.CREATED)
        .entity(Map.of("enrollmentId", enrollmentId.toString(),
                       "hostId", hostId.toString(), "agentId", agentId.toString(),
                       "token", token, "expiresAt", expiresAt.toString(),
                       "controllerFingerprint", controllerFingerprint,
                       "gatewayPort", gatewayPort,
                       "relayEndpoints", configuredRelays,
                       "relayFallback", relayFallback))
        .header("Cache-Control", "no-store")
        .build();
  }

  private List<String> relayEndpoints() {
    try {
      return parseRelayEndpoints(relayEndpointConfiguration);
    } catch (IllegalArgumentException invalid) {
      throw fail(500, "RELAY_CONFIGURATION_INVALID",
                 "Relay 地址配置必须是 2–8 个唯一的 HTTPS 源站");
    }
  }

  static List<String> parseRelayEndpoints(String configuration) {
    if (configuration == null || configuration.isBlank() ||
        configuration.trim().equalsIgnoreCase("disabled"))
      return List.of();
    var endpoints = new ArrayList<String>();
    var unique = new HashSet<String>();
    for (String raw : configuration.split(",")) {
      String value = raw.trim();
      if (value.isEmpty()) continue;
      try {
        String origin = normalizeRelayEndpoint(value);
        if (!unique.add(origin)) throw new IllegalArgumentException();
        endpoints.add(origin);
      } catch (RuntimeException invalid) {
        throw new IllegalArgumentException("invalid Relay HTTPS origin", invalid);
      }
    }
    if (!endpoints.isEmpty() && (endpoints.size() < 2 || endpoints.size() > 8))
      throw new IllegalArgumentException("Relay endpoint count must be between two and eight");
    return List.copyOf(endpoints);
  }

  static String normalizeRelayEndpoint(String value) {
    URI uri = URI.create(value);
    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
        uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawUserInfo() != null ||
        (uri.getRawPath() != null && !uri.getRawPath().isEmpty() &&
         !uri.getRawPath().equals("/")) || uri.getRawQuery() != null ||
        uri.getRawFragment() != null)
      throw new IllegalArgumentException("Relay endpoint must be a plain HTTPS origin");
    String host = uri.getHost().replace("[", "").replace("]", "")
        .toLowerCase(Locale.ROOT);
    String authority = host.contains(":") ? "[" + host + "]" : host;
    if (uri.getPort() > 0 && uri.getPort() != 443)
      authority += ":" + uri.getPort();
    return "https://" + authority;
  }

  @POST
  @Path("agent-enrollments/redeem")
  @Consumes(MediaType.APPLICATION_JSON)
  public Object redeem(@HeaderParam("Authorization") String authorization,
                       RedeemEnrollment input) {
    String token = bearer(authorization);
    if (input == null || input.csrPem() == null ||
        input.csrPem().length() < 100 || input.csrPem().length() > 16_384 ||
        !input.csrPem().contains("BEGIN CERTIFICATE REQUEST"))
      throw fail(400, "INVALID_CERTIFICATE_REQUEST", "CSR 格式无效或过大");
    limits.limit("agent-enrollment:" +
        (http.remoteAddress() == null ? "unknown" : http.remoteAddress().host()));
    String tokenHash = AuthStore.digest(token);
    return store.transaction(c -> {
      var rows = query(c, "SELECT * FROM agent_enrollment WHERE token_hash=? FOR UPDATE",
                       tokenHash);
      if (rows.isEmpty()) throw fail(401, "ENROLLMENT_INVALID", "接入码无效或已失效");
      var row = rows.getFirst();
      Object expiration = row.get("expires_at");
      Instant expiry = expiration instanceof java.sql.Timestamp timestamp
          ? timestamp.toInstant()
          : expiration instanceof java.time.OffsetDateTime timestamp
              ? timestamp.toInstant()
              : expiration instanceof Instant timestamp
                  ? timestamp
                  : Instant.parse(String.valueOf(expiration));
      if (row.get("consumed_at") != null || !expiry.isAfter(Instant.now()))
        throw fail(401, "ENROLLMENT_INVALID", "接入码无效或已失效");
      UUID agentId = (UUID)row.get("agent_id");
      UUID hostId = (UUID)row.get("host_id");
      String certificate = authority.signClientRequest(agentId, input.csrPem());
      String fingerprint = AgentCertificateAuthority.fingerprint(certificate);
      int updated = update(c,
          "UPDATE agent SET cert_identity=?,cert_fingerprint=? " +
              "WHERE id=? AND cert_fingerprint IS NULL",
          "spiffe://nanpanel/agent/" + agentId, fingerprint, agentId);
      if (updated != 1) throw fail(409, "ENROLLMENT_ALREADY_USED", "接入码已使用");
      update(c, "UPDATE agent_enrollment SET consumed_at=? WHERE id=?",
             java.sql.Timestamp.from(Instant.now()), row.get("id"));
      AuthStore.audit(c, String.valueOf(row.get("created_by")),
          "AGENT_ENROLLED", "host", hostId.toString(), "SUCCEEDED",
          http.remoteAddress() == null ? null : http.remoteAddress().host(),
          "Agent 完成 mTLS 接入");
      return Map.of("agentId", agentId.toString(), "hostId", hostId.toString(),
                    "certificatePem", certificate);
    });
  }

  private String bearer(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ") ||
        authorization.length() > 512)
      throw fail(401, "ENROLLMENT_INVALID", "接入码无效或已失效");
    String token = authorization.substring(7).trim();
    if (!token.matches("[A-Za-z0-9_-]{40,128}"))
      throw fail(401, "ENROLLMENT_INVALID", "接入码无效或已失效");
    return token;
  }

  private String jsonExpression(java.sql.Connection connection)
      throws java.sql.SQLException {
    return connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL")
        ? "?::jsonb" : "?";
  }
}
