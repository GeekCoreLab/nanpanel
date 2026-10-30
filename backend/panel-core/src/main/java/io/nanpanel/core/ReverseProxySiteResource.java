package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 站点（反向代理）管理：把「域名 → 主机上 127.0.0.1:端口」记录为可管理的站点，
 * 应用时编译为既有的受管 Nginx 路由（{@code nginx.route.set}），不引入新的网关。
 * 站点只是期望状态的记录 + 一次类型化 Helper 调用；本资源不直接写主机。
 */
@Path("/api/v1/reverse-proxy-sites")
@Produces(MediaType.APPLICATION_JSON)
public class ReverseProxySiteResource {

  @Inject AuthStore store;
  @Inject HostHelperOperationResource helperOperations;
  private static final ObjectMapper JSON = new ObjectMapper();
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  public record SiteRequest(String hostId, String domain, String listenAddress,
                            Integer listenPort, Integer upstreamPort, String healthPath) {}

  @SuppressWarnings("unchecked")
  private Map<String, Object> session() {
    Map<String, Object> value = (Map<String, Object>) request.getProperty("session");
    if (value == null || value.get("user_id") == null) {
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    }
    return value;
  }

  private String actorId() {
    return String.valueOf(session().get("user_id"));
  }

  private String clientIp() {
    return http.remoteAddress() == null ? null : http.remoteAddress().host();
  }

  @GET
  public Map<String, Object> list() {
    session();
    var rows = store.transaction(c -> query(c, """
        SELECT s.site_id, s.host_id, h.hostname, s.domain, s.listen_address, s.listen_port,
               s.upstream_port, s.health_path, s.tls_name, s.state, s.operation_id,
               s.created_at, s.updated_at
        FROM reverse_proxy_site s JOIN host h ON h.id = s.host_id
        ORDER BY s.created_at DESC LIMIT 200"""));
    var items = new ArrayList<Map<String, Object>>(rows.size());
    for (var row : rows) items.add(siteMap(row));
    return Map.of("items", items);
  }

  @POST
  public Response create(SiteRequest input) {
    String actor = actorId();
    if (input == null) throw fail(422, "VALIDATION_FAILED", "站点参数缺失");
    UUID hostId = uuidText(input.hostId(), "hostId");
    String domain = input.domain() == null ? "" : input.domain().trim().toLowerCase();
    String address = input.listenAddress() == null ? "" : input.listenAddress().trim();
    int listenPort = port(input.listenPort());
    int upstreamPort = port(input.upstreamPort());
    String healthPath = input.healthPath() == null || input.healthPath().isBlank()
        ? "/" : input.healthPath().trim();
    if (host(hostId) == null) throw fail(404, "HOST_NOT_FOUND", "主机未登记");
    // Reuse the exact dispatch-time validator so a stored site can always be applied.
    helperOperations.validateNginxRoute(payload(address, listenPort, domain, healthPath, upstreamPort));
    UUID siteId = UUID.randomUUID();
    long now = System.currentTimeMillis();
    try {
      store.transaction(c -> {
        update(c, """
            INSERT INTO reverse_proxy_site (site_id, host_id, domain, listen_address, listen_port,
              upstream_port, health_path, state, created_by, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)""", siteId, hostId, domain, address, listenPort,
            upstreamPort, healthPath, "DRAFT", actor, now, now);
        audit(c, actor, "reverse-proxy-site.create", "reverse_proxy_site", siteId.toString(),
            "SUCCEEDED", clientIp(), "创建反向代理站点 " + domain);
        return null;
      });
    } catch (RuntimeException conflict) {
      if (!uniqueViolation(conflict)) throw conflict;
      throw fail(409, "SITE_CONFLICT", "同一主机上该域名与监听端口已存在站点");
    }
    return Response.status(201).entity(loadSite(siteId)).build();
  }

  /**
   * Edit a site that is not currently serving traffic. APPLIED sites must be disabled
   * first, mirroring the delete rule, so an edit can never silently diverge from the
   * route the host is running.
   */
  @PUT
  @Path("{siteId}")
  public Response edit(@PathParam("siteId") String siteValue, SiteRequest input) {
    session();
    UUID siteId = uuidText(siteValue, "siteId");
    Map<String, Object> site = loadSite(siteId);
    if ("APPLIED".equals(String.valueOf(site.get("state")))) {
      throw fail(409, "SITE_APPLIED", "站点已生效，需先停用后再修改");
    }
    if (input == null) throw fail(422, "VALIDATION_FAILED", "站点参数缺失");
    if (input.hostId() != null && !input.hostId().isBlank()
        && !input.hostId().equals(String.valueOf(site.get("hostId")))) {
      throw fail(422, "VALIDATION_FAILED", "站点不能更换主机，请新建站点");
    }
    String domain = input.domain() == null ? "" : input.domain().trim().toLowerCase();
    String address = input.listenAddress() == null ? "" : input.listenAddress().trim();
    int listenPort = port(input.listenPort());
    int upstreamPort = port(input.upstreamPort());
    String healthPath = input.healthPath() == null || input.healthPath().isBlank()
        ? "/" : input.healthPath().trim();
    ObjectNode payload = payload(address, listenPort, domain, healthPath, upstreamPort);
    Object existingTls = site.get("tlsName");
    if (existingTls != null) {
      ObjectNode tls = payload.putObject("tls");
      tls.put("certificate", String.valueOf(existingTls));
      tls.put("privateKey", String.valueOf(existingTls));
    }
    helperOperations.validateNginxRoute(payload);
    try {
      store.transaction(c -> update(c, """
          UPDATE reverse_proxy_site SET domain=?, listen_address=?, listen_port=?,
            upstream_port=?, health_path=?, updated_at=? WHERE site_id=?""",
          domain, address, listenPort, upstreamPort, healthPath,
          System.currentTimeMillis(), siteId));
    } catch (RuntimeException conflict) {
      if (!uniqueViolation(conflict)) throw conflict;
      throw fail(409, "SITE_CONFLICT", "同一主机上该域名与监听端口已存在站点");
    }
    return Response.ok(loadSite(siteId)).build();
  }

  @POST
  @Path("{siteId}/apply")
  public Response apply(@PathParam("siteId") String siteValue,
      @HeaderParam("Idempotency-Key") String idempotencyKey) {
    String actor = actorId();
    UUID siteId = uuidText(siteValue, "siteId");
    Map<String, Object> site = loadSite(siteId);
    long hostGeneration = hostGeneration(UUID.fromString(String.valueOf(site.get("hostId"))));
    String target = routeTarget(siteId);
    ObjectNode payload = payload(String.valueOf(site.get("listenAddress")),
        ((Number) site.get("listenPort")).intValue(), String.valueOf(site.get("domain")),
        String.valueOf(site.get("healthPath")), ((Number) site.get("upstreamPort")).intValue());
    Object tlsName = site.get("tlsName");
    if (tlsName != null) {
      ObjectNode tls = payload.putObject("tls");
      tls.put("certificate", String.valueOf(tlsName));
      tls.put("privateKey", String.valueOf(tlsName));
    }
    var helperRequest = new HostHelperOperationResource.HelperOperationRequest(
        "nginx.route.set", target, payload, "应用站点 " + site.get("domain"), 120, hostGeneration);
    Response submitted;
    try {
      submitted = helperOperations.submitAs(UUID.fromString(String.valueOf(site.get("hostId"))),
          idempotencyKey, helperRequest, actor, clientIp());
    } catch (RuntimeException failure) {
      // A failed re-apply does not prove the previous route stopped serving.
      if (!"APPLIED".equals(String.valueOf(site.get("state")))) {
        store.transaction(c -> update(c,
            "UPDATE reverse_proxy_site SET state='FAILED', updated_at=? WHERE site_id=?",
            System.currentTimeMillis(), siteId));
      }
      throw failure;
    }
    UUID operationId = null;
    Object entity = submitted.getEntity();
    if (entity instanceof Map<?, ?> result && result.get("id") != null) {
      operationId = UUID.fromString(String.valueOf(result.get("id")));
    }
    UUID appliedOperation = operationId;
    store.transaction(c -> {
      update(c, "UPDATE reverse_proxy_site SET state='APPLIED', operation_id=?, updated_at=? "
          + "WHERE site_id=?", appliedOperation, System.currentTimeMillis(), siteId);
      audit(c, actor, "reverse-proxy-site.apply", "reverse_proxy_site", siteId.toString(),
          "SUCCEEDED", clientIp(), "应用反向代理站点 " + site.get("domain"));
      return null;
    });
    return Response.ok(loadSite(siteId)).build();
  }

  @POST
  @Path("{siteId}/disable")
  public Response disable(@PathParam("siteId") String siteValue,
      @HeaderParam("Idempotency-Key") String idempotencyKey) {
    String actor = actorId();
    UUID siteId = uuidText(siteValue, "siteId");
    Map<String, Object> site = loadSite(siteId);
    long hostGeneration = hostGeneration(UUID.fromString(String.valueOf(site.get("hostId"))));
    var helperRequest = new HostHelperOperationResource.HelperOperationRequest(
        "nginx.route.remove", routeTarget(siteId), null, "停用站点 " + site.get("domain"),
        120, hostGeneration);
    // A failed disable leaves the site as it was: the route may still be serving.
    Response submitted = helperOperations.submitAs(
        UUID.fromString(String.valueOf(site.get("hostId"))), idempotencyKey, helperRequest,
        actor, clientIp());
    UUID operationId = null;
    Object entity = submitted.getEntity();
    if (entity instanceof Map<?, ?> result && result.get("id") != null) {
      operationId = UUID.fromString(String.valueOf(result.get("id")));
    }
    UUID disabledOperation = operationId;
    store.transaction(c -> {
      update(c, "UPDATE reverse_proxy_site SET state='DISABLED', operation_id=?, updated_at=? "
          + "WHERE site_id=?", disabledOperation, System.currentTimeMillis(), siteId);
      audit(c, actor, "reverse-proxy-site.disable", "reverse_proxy_site", siteId.toString(),
          "SUCCEEDED", clientIp(), "停用反向代理站点 " + site.get("domain"));
      return null;
    });
    return Response.ok(loadSite(siteId)).build();
  }

  public record SiteTlsRequest(String certificatePem, String privateKeyPem, Integer listenPort) {}

  /**
   * Install a certificate/key pair for this site and remember the managed name. The
   * private key is written by the Helper into its root-owned TLS directory; Core never
   * stores it. The site must be re-applied for the HTTPS listener to take effect.
   */
  @POST
  @Path("{siteId}/tls")
  public Response installTls(@PathParam("siteId") String siteValue,
      @HeaderParam("Idempotency-Key") String idempotencyKey, SiteTlsRequest input) {
    String actor = actorId();
    UUID siteId = uuidText(siteValue, "siteId");
    Map<String, Object> site = loadSite(siteId);
    if (input == null || input.certificatePem() == null || input.privateKeyPem() == null) {
      throw fail(422, "VALIDATION_FAILED", "证书或私钥缺失");
    }
    int listenPort = input.listenPort() == null ? 443 : port(input.listenPort());
    UUID hostId = UUID.fromString(String.valueOf(site.get("hostId")));
    String tlsName = managedTlsName(siteId);
    ObjectNode payload = tlsPayload(input.certificatePem(), input.privateKeyPem());
    // Same validator as dispatch, so a stored TLS name can always be applied.
    helperOperations.validateTargetAndPayload("nginx.tls.install", tlsName, payload);
    var helperRequest = new HostHelperOperationResource.HelperOperationRequest(
        "nginx.tls.install", tlsName, payload, "为站点 " + site.get("domain") + " 安装证书",
        120, hostGeneration(hostId));
    Response submitted = helperOperations.submitAs(hostId, idempotencyKey, helperRequest,
        actor, clientIp());
    UUID operationId = null;
    Object entity = submitted.getEntity();
    if (entity instanceof Map<?, ?> result && result.get("id") != null) {
      operationId = UUID.fromString(String.valueOf(result.get("id")));
    }
    UUID tlsOperation = operationId;
    try {
      store.transaction(c -> {
        update(c, "UPDATE reverse_proxy_site SET tls_name=?, listen_port=?, operation_id=?, "
            + "updated_at=? WHERE site_id=?", tlsName, listenPort, tlsOperation,
            System.currentTimeMillis(), siteId);
        audit(c, actor, "reverse-proxy-site.tls", "reverse_proxy_site", siteId.toString(),
            "SUCCEEDED", clientIp(), "为站点安装证书 " + site.get("domain"));
        return null;
      });
    } catch (RuntimeException conflict) {
      if (!uniqueViolation(conflict)) throw conflict;
      throw fail(409, "SITE_CONFLICT", "切换到该 HTTPS 端口会与同主机上的其它站点冲突");
    }
    return Response.ok(loadSite(siteId)).build();
  }

  @DELETE
  @Path("{siteId}")
  public Response delete(@PathParam("siteId") String siteValue) {
    String actor = actorId();
    UUID siteId = uuidText(siteValue, "siteId");
    Map<String, Object> site = loadSite(siteId);
    if ("APPLIED".equals(String.valueOf(site.get("state")))) {
      throw fail(409, "SITE_APPLIED", "站点已生效，需先在主机上覆盖或恢复受管路由后再删除");
    }
    store.transaction(c -> {
      update(c, "DELETE FROM reverse_proxy_site WHERE site_id=?", siteId);
      audit(c, actor, "reverse-proxy-site.delete", "reverse_proxy_site", siteId.toString(),
          "SUCCEEDED", clientIp(), "删除反向代理站点 " + site.get("domain"));
      return null;
    });
    return Response.noContent().build();
  }

  private Map<String, Object> loadSite(UUID siteId) {
    var rows = store.transaction(c -> query(c, """
        SELECT s.site_id, s.host_id, h.hostname, s.domain, s.listen_address, s.listen_port,
               s.upstream_port, s.health_path, s.tls_name, s.state, s.operation_id,
               s.created_at, s.updated_at
        FROM reverse_proxy_site s JOIN host h ON h.id = s.host_id WHERE s.site_id = ?""", siteId));
    if (rows.isEmpty()) throw fail(404, "SITE_NOT_FOUND", "站点不存在");
    return siteMap(rows.get(0));
  }

  private Map<String, Object> host(UUID hostId) {
    var rows = store.transaction(c -> query(c, "SELECT id, hostname FROM host WHERE id = ?", hostId));
    return rows.isEmpty() ? null : rows.get(0);
  }

  private long hostGeneration(UUID hostId) {
    var rows = store.transaction(c -> query(c, "SELECT generation FROM host WHERE id = ?", hostId));
    if (rows.isEmpty()) throw fail(404, "HOST_NOT_FOUND", "主机未登记");
    return ((Number) rows.get(0).get("generation")).longValue();
  }

  private static Map<String, Object> siteMap(Map<String, Object> row) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("id", String.valueOf(row.get("site_id")));
    item.put("hostId", String.valueOf(row.get("host_id")));
    item.put("hostname", row.get("hostname") == null ? "" : String.valueOf(row.get("hostname")));
    item.put("domain", String.valueOf(row.get("domain")));
    item.put("listenAddress", String.valueOf(row.get("listen_address")));
    item.put("listenPort", ((Number) row.get("listen_port")).intValue());
    item.put("upstreamPort", ((Number) row.get("upstream_port")).intValue());
    item.put("healthPath", String.valueOf(row.get("health_path")));
    item.put("tlsName", row.get("tls_name") == null ? null : String.valueOf(row.get("tls_name")));
    item.put("state", String.valueOf(row.get("state")));
    item.put("operationId", row.get("operation_id") == null ? null
        : String.valueOf(row.get("operation_id")));
    item.put("createdAt", ((Number) row.get("created_at")).longValue());
    item.put("updatedAt", ((Number) row.get("updated_at")).longValue());
    return item;
  }

  /** Compile a site into the exact {@code nginx.route.set} payload the Helper expects. */
  static ObjectNode payload(String listenAddress, int listenPort, String domain,
      String healthPath, int upstreamPort) {
    ObjectNode payload = JSON.createObjectNode();
    payload.put("listenAddress", listenAddress);
    payload.put("listenPort", listenPort);
    payload.put("serverName", domain);
    payload.put("healthPath", healthPath);
    ArrayNode backends = payload.putArray("backends");
    ObjectNode backend = backends.addObject();
    backend.put("name", slug(domain));
    backend.put("port", upstreamPort);
    backend.put("weight", 100);
    return payload;
  }

  /** Managed TLS material name for this site, matching [a-z][a-z0-9-]{0,31}. */
  static String managedTlsName(UUID siteId) {
    return "site-" + siteId.toString().replace("-", "").substring(0, 12);
  }

  static ObjectNode tlsPayload(String certificatePem, String privateKeyPem) {
    ObjectNode payload = JSON.createObjectNode();
    payload.put("certificatePem", certificatePem);
    payload.put("privateKeyPem", privateKeyPem);
    return payload;
  }

  /** Stable managed-route target derived from the site id (matches nanpanel-[a-z][a-z0-9-]{0,52}). */
  static String routeTarget(UUID siteId) {
    return "nanpanel-site-s" + siteId.toString().replace("-", "").substring(0, 12);
  }

  /** Backend name slug: lowercase alphanumeric/hyphen, 1–32 chars, starts with alphanumeric. */
  static String slug(String domain) {
    String slug = domain == null ? "" : domain.toLowerCase()
        .replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
    if (slug.isEmpty()) return "site";
    if (slug.length() > 32) slug = slug.substring(0, 32).replaceAll("-+$", "");
    return slug;
  }

  private static int port(Integer value) {
    if (value == null || value < 1 || value > 65535) {
      throw fail(422, "VALIDATION_FAILED", "监听端口或上游端口无效");
    }
    return value;
  }

  private static UUID uuidText(String value, String field) {
    try {
      return UUID.fromString(value);
    } catch (RuntimeException invalid) {
      throw fail(422, "VALIDATION_FAILED", field + " 必须是 UUID");
    }
  }

  private static boolean uniqueViolation(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) return true;
    }
    return false;
  }
}
