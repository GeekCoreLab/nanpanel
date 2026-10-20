package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthResource.number;
import static io.nanpanel.core.AuthResource.stamp;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Path("/api/v1/auth/api-keys")
@Produces(MediaType.APPLICATION_JSON)
public class ApiKeyResource {
  static final String INVENTORY_READ = "kubernetes:inventory:read";
  static final String INVENTORY_REFRESH = "kubernetes:inventory:refresh";
  private static final Set<String> ALLOWED_SCOPES =
      Set.of(INVENTORY_READ, INVENTORY_REFRESH);

  @Inject AuthStore store;
  @Inject io.vertx.core.http.HttpServerRequest http;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;

  public record CreateRequest(String name, List<String> scopes,
                              Integer expiresInDays) {}

  private Map<String, Object> session() {
    @SuppressWarnings("unchecked")
    var value = (Map<String, Object>)request.getProperty("session");
    if (value == null || value.get("user_id") == null ||
        value.get("api_key_id") != null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    return store.transaction(c -> {
      var users = query(c, "SELECT status,must_change FROM panel_user WHERE id=?",
                        value.get("user_id"));
      if (users.isEmpty() || !"ACTIVE".equals(users.getFirst().get("status")))
        throw fail(401, "UNAUTHENTICATED", "账号已停用或会话已失效");
      if (Boolean.TRUE.equals(users.getFirst().get("must_change")))
        throw fail(403, "PASSWORD_CHANGE_REQUIRED", "请先修改初始密码");
      return value;
    });
  }

  private String clientIp() {
    return http.remoteAddress() == null ? null : http.remoteAddress().host();
  }

  private void recent(Map<String, Object> current) {
    if (System.currentTimeMillis() - number(current, "authenticated_at") >
        300000) {
      store.transaction(c -> {
        audit(c, String.valueOf(current.get("user_id")),
            "security.reauthentication.required", "api_key", null,
            "DENIED", clientIp(), "创建 API 密钥需要重新验证身份");
        return null;
      });
      throw fail(403, "REAUTHENTICATION_REQUIRED",
          "请重新验证密码后再创建 API 密钥");
    }
  }

  private Map<String, Object> view(Map<String, Object> row) {
    var value = new HashMap<String, Object>();
    value.put("id", row.get("id"));
    value.put("name", row.get("name"));
    value.put("scopes", List.of(String.valueOf(row.get("scopes")).split(",")));
    value.put("createdAt", stamp(row.get("created_at")));
    value.put("expiresAt", stamp(row.get("expires_at")));
    value.put("revokedAt", stamp(row.get("revoked_at")));
    value.put("lastUsedAt", stamp(row.get("last_used_at")));
    return value;
  }

  @GET
  public Object list() {
    var current = session();
    return store.transaction(c -> Map.of(
        "items", query(c,
                       "SELECT * FROM api_key WHERE user_id=? "
                           + "ORDER BY created_at DESC LIMIT 100",
                       current.get("user_id"))
                     .stream()
                     .map(this::view)
                     .toList()));
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response create(CreateRequest input) {
    var current = session();
    recent(current);
    if (input == null || input.name() == null || input.scopes() == null ||
        input.expiresInDays() == null)
      throw fail(422, "VALIDATION_FAILED", "名称、范围和有效天数均为必填项");
    String name = input.name().trim();
    if (name.isEmpty() || name.codePointCount(0, name.length()) > 80 ||
        name.codePoints().anyMatch(Character::isISOControl))
      throw fail(422, "VALIDATION_FAILED", "密钥名称须为 1–80 个可见字符");
    if (input.scopes().isEmpty() || input.scopes().stream().anyMatch(
            scope -> scope == null || !ALLOWED_SCOPES.contains(scope)))
      throw fail(422, "VALIDATION_FAILED", "密钥范围无效");
    var scopes = new TreeSet<>(input.scopes());
    if (scopes.size() != input.scopes().size())
      throw fail(422, "VALIDATION_FAILED", "密钥范围不能重复");
    if (input.expiresInDays() < 1 || input.expiresInDays() > 90)
      throw fail(422, "VALIDATION_FAILED", "有效期须为 1–90 天");

    String id = UUID.randomUUID().toString();
    String token = "npk_" + id + "." + AuthStore.token();
    long now = System.currentTimeMillis();
    long expiresAt = now + input.expiresInDays() * 24L * 60 * 60 * 1000;
    return store.transaction(c -> {
      long active = query(c,
          "SELECT id FROM api_key WHERE user_id=? AND revoked_at IS NULL "
              + "AND expires_at>?",
          current.get("user_id"), now).size();
      if (active >= 20)
        throw fail(409, "API_KEY_LIMIT_REACHED", "有效 API 密钥已达到 20 个上限");
      update(c,
          "INSERT INTO api_key "
              + "(id,user_id,name,token_hash,scopes,created_at,expires_at,"
              + "revoked_at,last_used_at,created_ip) VALUES (?,?,?,?,?,?,?,?,?,?)",
          id, current.get("user_id"), name, AuthStore.digest(token),
          String.join(",", scopes), now, expiresAt, null, null, clientIp());
      audit(c, String.valueOf(current.get("user_id")), "auth.api_key.create",
          "api_key", id, "SUCCEEDED", clientIp(), "管理员创建 API 密钥");
      var row = query(c, "SELECT * FROM api_key WHERE id=?", id).getFirst();
      return Response.status(Response.Status.CREATED)
          .entity(Map.of("item", view(row), "token", token))
          .header("Cache-Control", "no-store")
          .build();
    });
  }

  @DELETE
  @Path("{keyId}")
  public Response revoke(@PathParam("keyId") String keyId) {
    var current = session();
    final String id;
    try {
      id = UUID.fromString(keyId).toString();
    } catch (RuntimeException e) {
      throw fail(400, "VALIDATION_FAILED", "keyId 必须为 UUID");
    }
    store.transaction(c -> {
      var rows = query(c, "SELECT id,revoked_at FROM api_key "
                              + "WHERE id=? AND user_id=? FOR UPDATE",
                       id, current.get("user_id"));
      if (rows.isEmpty())
        throw fail(404, "NOT_FOUND", "API 密钥不存在");
      if (rows.getFirst().get("revoked_at") == null) {
        long now = System.currentTimeMillis();
        update(c, "UPDATE api_key SET revoked_at=? WHERE id=?", now, id);
        audit(c, String.valueOf(current.get("user_id")),
            "auth.api_key.revoke", "api_key", id, "SUCCEEDED", clientIp(),
            "管理员撤销 API 密钥");
      }
      return null;
    });
    return Response.noContent().build();
  }
}
