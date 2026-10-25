package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.*;
import static io.nanpanel.core.AuthStore.*;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Provider
@jakarta.enterprise.context.ApplicationScoped
@Priority(1000)
public class SessionFilter
    implements ContainerRequestFilter, ContainerResponseFilter {
  private record ApiKeyPrincipal(Map<String, Object> row, Set<String> scopes) {}

  @Inject AuthStore store;
  @Inject io.vertx.core.http.HttpServerRequest http;
  @Inject
  @ConfigProperty(name = "nanpanel.auth.origin",
                  defaultValue = "https://localhost")
  String origin;
  @Inject
  @ConfigProperty(name = "nanpanel.auth.rate-limit.requests-per-minute",
                  defaultValue = "20")
  int requestsPerMinute;
  private final Map<String, long[]> attempts = new ConcurrentHashMap<>();
  synchronized void limit(String key) {
    long now = System.currentTimeMillis();
    attempts.entrySet().removeIf(e -> now - e.getValue()[0] > 60000);
    if (attempts.size() > 10000)
      throw fail(429, "RATE_LIMITED", "请求过于频繁");
    var entry = attempts.computeIfAbsent(key, k -> new long[] {now, 0});
    if (++entry[1] > requestsPerMinute)
      throw new jakarta.ws.rs.WebApplicationException(
          jakarta.ws.rs.core.Response.status(429)
              .header("Retry-After", "60")
              .entity(Map.of("code", "RATE_LIMITED", "detail",
                             "请求过于频繁，请稍后重试"))
              .build());
  }
  private String clientIp() {
    return http.remoteAddress() == null ? null : http.remoteAddress().host();
  }
  private WebApplicationException reject(ContainerRequestContext r, String code,
                                         String detail, String action,
                                         String summary) {
    limit("security:" + Objects.toString(clientIp(), "unknown"));
    var cookie = r.getCookies().get(COOKIE);
    store.transaction(c -> {
      String actor = null;
      if (cookie != null) {
        var rows =
            query(c, "SELECT user_id FROM web_session WHERE token_hash=?",
                  digest(cookie.getValue()));
        if (!rows.isEmpty() && rows.getFirst().get("user_id") != null)
          actor = String.valueOf(rows.getFirst().get("user_id"));
      }
      audit(c, actor, action, "request", null, "DENIED", clientIp(), summary);
      return null;
    });
    return fail(403, code, detail);
  }
  private ApiKeyPrincipal apiKey(String token) {
    if (token == null || token.length() > 128 || !token.startsWith("npk_"))
      return null;
    int separator = token.indexOf('.', 4);
    if (separator < 0 || separator == 4 || separator == token.length() - 1)
      return null;
    String id = token.substring(4, separator);
    String secret = token.substring(separator + 1);
    try {
      if (!UUID.fromString(id).toString().equals(id) ||
          !secret.matches("[A-Za-z0-9_-]{43}"))
        return null;
    } catch (IllegalArgumentException e) {
      return null;
    }
    return store.transaction(c -> {
      long now = System.currentTimeMillis();
      var rows = query(c, "SELECT * FROM api_key WHERE id=?", id);
      if (rows.isEmpty())
        return null;
      var row = rows.getFirst();
      byte[] supplied = digest(token).getBytes(
          java.nio.charset.StandardCharsets.US_ASCII);
      byte[] stored = String.valueOf(row.get("token_hash")).getBytes(
          java.nio.charset.StandardCharsets.US_ASCII);
      if (!java.security.MessageDigest.isEqual(supplied, stored) ||
          row.get("revoked_at") != null ||
          AuthResource.number(row, "expires_at") <= now)
        return null;
      update(c, "UPDATE api_key SET last_used_at=? WHERE id=?", now, id);
      Set<String> scopes = new HashSet<>(Arrays.asList(
          String.valueOf(row.get("scopes")).split(",")));
      return new ApiKeyPrincipal(row, Set.copyOf(scopes));
    });
  }
  private Set<String> requiredApiKeyScopes(String path, String method) {
    if (method.equals("GET") && Set.of(
        "api/v1/kubernetes/service-inventory",
        "api/v1/kubernetes/service-probes").contains(path))
      return Set.of(ApiKeyResource.INVENTORY_READ);
    if (method.equals("POST") && path.equals(
        "api/v1/kubernetes/service-inventory/refresh"))
      return Set.of(ApiKeyResource.INVENTORY_READ,
                    ApiKeyResource.INVENTORY_REFRESH);
    return null;
  }
  private WebApplicationException invalidApiKey() {
    limit("security:" + Objects.toString(clientIp(), "unknown"));
    store.transaction(c -> {
      audit(c, null, "security.api_key.invalid", "api_key", null,
            "DENIED", clientIp(), "API 密钥无效或已失效");
      return null;
    });
    return fail(401, "UNAUTHENTICATED", "请登录以继续");
  }
  private WebApplicationException rejectApiKeyScope(
      ApiKeyPrincipal principal) {
    limit("security:" + Objects.toString(clientIp(), "unknown"));
    String keyId = String.valueOf(principal.row().get("id"));
    String actor = String.valueOf(principal.row().get("user_id"));
    store.transaction(c -> {
      audit(c, actor, "security.api_key.scope.reject", "api_key", keyId,
            "DENIED", clientIp(), "API 密钥范围不允许此请求");
      return null;
    });
    return fail(403, "API_KEY_SCOPE_REJECTED", "API 密钥无权访问此接口");
  }
  @Override
  public void filter(ContainerRequestContext r) {
    String path = r.getUriInfo().getPath().replaceFirst("^/", "");
    if (!path.startsWith("api/"))
      return;
    boolean agentDownload = r.getMethod().equals("GET") && Set.of(
        "api/v1/agent-installation", "api/v1/agent-controller-cert",
        "api/v1/agent-download/linux-amd64",
        "api/v1/helper-download/linux-amd64").contains(path);
    boolean agentRedemption = path.equals("api/v1/agent-enrollments/redeem") &&
        r.getMethod().equals("POST") && r.getCookies().get(COOKIE) == null &&
        Optional.ofNullable(r.getHeaderString("Authorization"))
            .orElse("").startsWith("Bearer ");
    boolean csrf =
        path.equals("api/v1/auth/csrf") && r.getMethod().equals("GET");
    boolean login =
        path.equals("api/v1/auth/login") && r.getMethod().equals("POST");
    boolean write = !Set.of("GET", "HEAD", "OPTIONS").contains(r.getMethod());
    String supplied = r.getHeaderString("Origin");
    if ((write || csrf) &&
        ((supplied != null && !origin.equals(supplied)) ||
         "cross-site".equals(r.getHeaderString("Sec-Fetch-Site"))))
      throw reject(r, "CSRF_REJECTED", "请求来源不受信任",
                   "security.origin.reject", "请求来源校验拒绝");
    if (agentDownload || agentRedemption) {
      r.setProperty("session", null);
      return;
    }
    if (login || path.equals("api/v1/auth/password") && write)
      limit("ip:" + http.remoteAddress().host());
    if (csrf)
      limit("csrf:" + http.remoteAddress().host());
    if ((path.startsWith("api/v1/admin/") || path.startsWith("api/v1/kubernetes/clusters")
        || path.startsWith("api/v1/kubernetes/applications")
        || path.startsWith("api/v1/network-providers/")
        || path.startsWith("api/v1/plugins/") || path.startsWith("api/v1/config-translators/")) && write &&
        r.getCookies().get(COOKIE) != null)
      limit("admin:" + digest(r.getCookies().get(COOKIE).getValue()));
    var cookie = r.getCookies().get(COOKIE);
    ApiKeyPrincipal principal = null;
    Map<String, Object> session;
    String authorization = r.getHeaderString("Authorization");
    if (authorization != null && authorization.startsWith("Bearer ")) {
      if (cookie != null)
        throw reject(r, "AUTHENTICATION_CONFLICT",
                     "请只使用一种认证方式", "security.auth.conflict",
                     "请求同时携带 Cookie 和 Bearer 凭据");
      principal = apiKey(authorization.substring("Bearer ".length()));
      if (principal == null)
        throw invalidApiKey();
      Set<String> required = requiredApiKeyScopes(path, r.getMethod());
      if (required == null || !principal.scopes().containsAll(required))
        throw rejectApiKeyScope(principal);
      session = new HashMap<>();
      session.put("id", principal.row().get("id"));
      session.put("user_id", principal.row().get("user_id"));
      session.put("api_key_id", principal.row().get("id"));
    } else {
      session = store.transaction(c -> {
        long now = System.currentTimeMillis();
        update(c,
               "DELETE FROM web_session WHERE idle_expires<=? OR "
                   + "absolute_expires<=?",
               now, now);
        if (cookie == null)
          return null;
        var rows = query(c, "SELECT * FROM web_session WHERE token_hash=?",
                         digest(cookie.getValue()));
        if (rows.isEmpty())
          return null;
        var s = rows.getFirst();
        if (s.get("user_id") != null) {
          long expiry = Math.min(now + 1800000, number(s, "absolute_expires"));
          update(c,
                 "UPDATE web_session SET last_seen=?,idle_expires=? WHERE id=?",
                 now, expiry, s.get("id"));
          s.put("last_seen", now);
          s.put("idle_expires", expiry);
        }
        return s;
      });
    }
    r.setProperty("session", session);
    r.setProperty("apiKey", principal);
    if (csrf)
      return;
    if (session == null || (!login && session.get("user_id") == null)) {
      limit("security:" + Objects.toString(clientIp(), "unknown"));
      store.transaction(c -> {
        audit(c, null, "security.unauthenticated", "request", null, "DENIED",
              clientIp(), "未认证请求被拒绝");
        return null;
      });
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    }
    if (principal == null && write && !java.security.MessageDigest.isEqual(
                     String.valueOf(session.get("csrf")).getBytes(),
                     Optional.ofNullable(r.getHeaderString("X-CSRF-Token"))
                         .orElse("")
                         .getBytes()))
      throw reject(r, "CSRF_REJECTED", "CSRF 上下文已失效",
                   "security.csrf.reject", "CSRF 校验拒绝写请求");
    if (!path.startsWith("api/v1/auth/")) {
      try {
        store.transaction(c -> {
          var u =
              query(c, "SELECT status,must_change FROM panel_user WHERE id=?",
                    session.get("user_id"));
          if (u.isEmpty() || !"ACTIVE".equals(u.getFirst().get("status")))
            throw fail(401, "UNAUTHENTICATED", "账号已停用或会话已失效");
          if (Boolean.TRUE.equals(u.getFirst().get("must_change")))
            throw fail(403, "PASSWORD_CHANGE_REQUIRED", "请先修改初始密码");
          return null;
        });
      } catch (WebApplicationException e) {
        limit("security:" + Objects.toString(clientIp(), "unknown"));
        store.transaction(c -> {
          audit(c, String.valueOf(session.get("user_id")),
                e.getResponse().getStatus() == 403
                    ? "security.password_change_required"
                    : "security.account_inactive",
                "request", null, "DENIED", clientIp(),
                "管理请求因账号状态被拒绝");
          return null;
        });
        throw e;
      }
    }
  }
  @Override
  public void filter(ContainerRequestContext request,
                     ContainerResponseContext response) {
    if (request.getUriInfo().getPath().replaceFirst("^/", "").startsWith(
            "api/"))
      response.getHeaders().putSingle("Cache-Control", "no-store");
  }
}
