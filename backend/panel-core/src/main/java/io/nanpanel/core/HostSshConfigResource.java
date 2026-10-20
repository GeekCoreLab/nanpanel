package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Path("/api/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class HostSshConfigResource {
  private static final org.jboss.logging.Logger LOG =
      org.jboss.logging.Logger.getLogger(HostSshConfigResource.class);
  private static final Pattern HOSTNAME = Pattern.compile(
      "[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");
  private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:]{2,45}");
  private static final Pattern USERNAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,127}");
  private static final Pattern FINGERPRINT = Pattern.compile("SHA256:[A-Za-z0-9+/]{43}");

  @Inject AuthStore store;
  @Inject HostSshCredentialService credentials;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  public record ConfigRequest(String address, Integer port, String username,
                              String authMethod, String password, String privateKey,
                              String privateKeyPassphrase) {
    @Override
    public String toString() {
      return "ConfigRequest[redacted]";
    }
  }
  public record ProbeRequest(String reason) {}
  public record TrustRequest(String fingerprint, String reason) {}

  private UUID uuid(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException e) {
      throw fail(400, "VALIDATION_FAILED", "hostId 必须为 UUID");
    }
  }

  private String actor() {
    @SuppressWarnings("unchecked")
    var session = (Map<String, Object>)request.getProperty("session");
    if (session == null || session.get("user_id") == null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    return String.valueOf(session.get("user_id"));
  }

  @GET
  @Path("hosts/{hostId}/ssh/config")
  public Response getConfig(@PathParam("hostId") String hostValue) {
    UUID hostId = uuid(hostValue);
    actor();
    return store.transaction(c -> {
      var config = credentials.find(c, hostId);
      if (config == null) throw fail(404, "SSH_CONFIG_NOT_FOUND", "该主机尚未配置直连 SSH");
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("hostId", hostId.toString());
      result.put("address", config.address());
      result.put("port", config.port());
      result.put("username", config.username());
      result.put("authMethod", config.authMethod());
      result.put("credentialsConfigured", true);
      result.put("hostKeyFingerprint", config.hostKeyFingerprint());
      result.put("probedFingerprint", config.probedFingerprint());
      result.put("probedAt", config.probedAt() == null ? null : config.probedAt().toString());
      return Response.ok(result).header("Cache-Control", "no-store").build();
    });
  }

  @PUT
  @Path("hosts/{hostId}/ssh/config")
  public Response saveConfig(@PathParam("hostId") String hostValue, ConfigRequest input) {
    UUID hostId = uuid(hostValue);
    String actor = actor();
    String address = input == null || input.address() == null ? "" : input.address().trim();
    String username = input == null || input.username() == null ? "" : input.username().trim();
    int port = input == null || input.port() == null ? 22 : input.port();
    String authMethod = input == null || input.authMethod() == null
        ? "" : input.authMethod().trim().toUpperCase(java.util.Locale.ROOT);
    String password = input == null ? null : input.password();
    String privateKey = input == null ? null : input.privateKey();
    String passphrase = input == null ? null : input.privateKeyPassphrase();
    if ((!HOSTNAME.matcher(address).matches() && !IPV6.matcher(address).matches()) ||
        address.contains("..") || address.startsWith("-") || address.endsWith("-") ||
        port < 1 || port > 65535 || !USERNAME.matcher(username).matches())
      throw fail(422, "VALIDATION_FAILED", "SSH 地址、端口或用户名无效");
    HostSshCredentialService.Secret secret;
    if ("PASSWORD".equals(authMethod) && password != null && !password.isEmpty() &&
        password.length() <= 4096 && privateKey == null && passphrase == null) {
      secret = new HostSshCredentialService.Secret(password, null, null);
    } else if ("PRIVATE_KEY".equals(authMethod) && privateKey != null &&
        privateKey.startsWith("-----BEGIN ") && privateKey.length() <= 64 * 1024 &&
        password == null && (passphrase == null || passphrase.length() <= 4096)) {
      secret = new HostSshCredentialService.Secret(null, privateKey, passphrase);
    } else {
      throw fail(422, "VALIDATION_FAILED", "请提供匹配认证方式的 SSH 密码或私钥");
    }

    byte[] encrypted;
    try {
      encrypted = credentials.encrypt(hostId, secret);
    } catch (IllegalStateException unavailable) {
      throw fail(503, "SSH_CREDENTIAL_KEY_UNAVAILABLE",
          "SSH 凭据加密密钥未正确配置，未保存任何凭据");
    }
    String remoteAddress = http.remoteAddress() == null ? null : http.remoteAddress().host();
    store.transaction(c -> {
      if (query(c, "SELECT id FROM host WHERE id=?", hostId).isEmpty())
        throw fail(404, "NOT_FOUND", "主机不存在");
      var existing = query(c,
          "SELECT address,port FROM host_ssh_config WHERE host_id=? FOR UPDATE", hostId);
      boolean sameTarget = !existing.isEmpty() &&
          address.equalsIgnoreCase(String.valueOf(existing.getFirst().get("address"))) &&
          port == ((Number)existing.getFirst().get("port")).intValue();
      if (existing.isEmpty()) {
        update(c, "INSERT INTO host_ssh_config " +
                "(host_id,address,port,username,auth_method,encrypted_credential,created_by,updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?)",
            hostId, address, port, username, authMethod, encrypted, actor,
            Timestamp.from(Instant.now()));
      } else {
        update(c, "UPDATE host_ssh_config SET address=?,port=?,username=?,auth_method=?," +
                "encrypted_credential=?,host_key_fingerprint=?,probed_host_key_fingerprint=NULL," +
                "probed_at=NULL,updated_at=? WHERE host_id=?",
            address, port, username, authMethod, encrypted,
            sameTarget ? existingHostFingerprint(c, hostId) : null,
            Timestamp.from(Instant.now()), hostId);
      }
      audit(c, actor, "HOST_SSH_CONFIG_UPDATED", "host", hostId.toString(), "SUCCEEDED",
          remoteAddress, "更新主机 SSH 连接配置（凭据已加密且不回显）");
      return null;
    });
    java.util.Arrays.fill(encrypted, (byte)0);
    return Response.noContent().build();
  }

  @POST
  @Path("hosts/{hostId}/ssh/host-key/probe")
  public Response probeHostKey(@PathParam("hostId") String hostValue, ProbeRequest input) {
    UUID hostId = uuid(hostValue);
    String actor = actor();
    String reason = input == null || input.reason() == null ? "" : input.reason().trim();
    if (reason.isEmpty() || reason.length() > 500 ||
        reason.chars().anyMatch(Character::isISOControl))
      throw fail(422, "VALIDATION_FAILED", "必须填写有效的主机指纹探测原因");
    HostSshCredentialService.HostConfig config = store.transaction(c -> credentials.find(c, hostId));
    if (config == null) throw fail(404, "SSH_CONFIG_NOT_FOUND", "该主机尚未配置直连 SSH");
    String fingerprint;
    try {
      fingerprint = credentials.probeFingerprint(config);
    } catch (Exception e) {
      LOG.warnf(e, "SSH host key probe failed for host asset %s", hostId);
      record(actor, hostId, "HOST_SSH_KEY_PROBED", "FAILED",
          "SSH 主机指纹探测失败；原因：" + reason);
      throw fail(502, "SSH_HOST_KEY_PROBE_FAILED", "无法读取目标 SSH host key");
    }
    String observed = fingerprint;
    Instant now = Instant.now();
    store.transaction(c -> {
      update(c, "UPDATE host_ssh_config SET probed_host_key_fingerprint=?,probed_at=? " +
              "WHERE host_id=? AND address=? AND port=?",
          observed, Timestamp.from(now), hostId, config.address(), config.port());
      audit(c, actor, "HOST_SSH_KEY_PROBED", "host", hostId.toString(), "SUCCEEDED",
          clientIp(), "读取 SSH host key 指纹；原因：" + reason);
      return null;
    });
    return Response.ok(Map.of("hostId", hostId.toString(), "fingerprint", fingerprint,
        "matchesTrustedFingerprint", fingerprint.equals(config.hostKeyFingerprint()),
        "requiresConfirmation", !fingerprint.equals(config.hostKeyFingerprint()),
        "probedAt", now.toString())).header("Cache-Control", "no-store").build();
  }

  @POST
  @Path("hosts/{hostId}/ssh/host-key/trust")
  public Response trustHostKey(@PathParam("hostId") String hostValue, TrustRequest input) {
    UUID hostId = uuid(hostValue);
    String actor = actor();
    String fingerprint = input == null || input.fingerprint() == null
        ? "" : input.fingerprint().trim();
    String reason = input == null || input.reason() == null ? "" : input.reason().trim();
    if (!FINGERPRINT.matcher(fingerprint).matches() || reason.isEmpty() || reason.length() > 500 ||
        reason.chars().anyMatch(Character::isISOControl))
      throw fail(422, "VALIDATION_FAILED", "SSH host key 指纹或确认原因无效");
    store.transaction(c -> {
      var rows = query(c, "SELECT probed_host_key_fingerprint,probed_at FROM host_ssh_config " +
          "WHERE host_id=? FOR UPDATE", hostId);
      if (rows.isEmpty()) throw fail(404, "SSH_CONFIG_NOT_FOUND", "该主机尚未配置直连 SSH");
      var row = rows.getFirst();
      Object probedAt = row.get("probed_at");
      Instant observedAt = probedAt instanceof Timestamp timestamp ? timestamp.toInstant()
          : probedAt instanceof java.time.OffsetDateTime timestamp ? timestamp.toInstant() : null;
      if (observedAt == null || observedAt.isBefore(Instant.now().minusSeconds(300)) ||
          !MessageDigest.isEqual(fingerprint.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
              String.valueOf(row.get("probed_host_key_fingerprint")).getBytes(
                  java.nio.charset.StandardCharsets.US_ASCII)))
        throw fail(409, "SSH_HOST_KEY_PROBE_EXPIRED", "指纹探测已过期或与当前探测结果不一致");
      update(c, "UPDATE host_ssh_config SET host_key_fingerprint=?,updated_at=? WHERE host_id=?",
          fingerprint, Timestamp.from(Instant.now()), hostId);
      audit(c, actor, "HOST_SSH_KEY_TRUSTED", "host", hostId.toString(), "SUCCEEDED",
          clientIp(), "管理员确认 SSH host key 指纹 " + fingerprint + "；原因：" + reason);
      return null;
    });
    return Response.noContent().build();
  }

  private String existingHostFingerprint(java.sql.Connection c, UUID hostId) throws Exception {
    var rows = query(c, "SELECT host_key_fingerprint FROM host_ssh_config WHERE host_id=?", hostId);
    return rows.isEmpty() ? null : (String)rows.getFirst().get("host_key_fingerprint");
  }

  private String clientIp() {
    return http.remoteAddress() == null ? null : http.remoteAddress().host();
  }

  private void record(String actor, UUID hostId, String action, String outcome, String summary) {
    store.transaction(c -> {
      audit(c, actor, action, "host", hostId.toString(), outcome, clientIp(), summary);
      return null;
    });
  }
}
