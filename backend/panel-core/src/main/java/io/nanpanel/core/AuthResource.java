package io.nanpanel.core;

import static io.nanpanel.core.AuthStore.*;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.sql.Connection;
import java.time.Instant;
import java.util.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/api/v1/auth")
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {
  @Inject AuthStore store;
  @Inject io.vertx.core.http.HttpServerRequest http;
  @Inject SessionFilter limits;
  @Inject
  @ConfigProperty(name = "nanpanel.auth.secure-cookie", defaultValue = "true")
  boolean secure;
  @Inject
  @ConfigProperty(name = "nanpanel.auth.bootstrap-password")
  Optional<String> bootstrap;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  static final String COOKIE = "nanpanel_session";
  static WebApplicationException fail(int status, String code, String detail) {
    return new WebApplicationException(
        Response.status(status)
            .type("application/problem+json")
            .entity(Map.of("status", status, "code", code, "title", code,
                           "detail", detail, "traceId",
                           UUID.randomUUID().toString()))
            .build());
  }
  static long number(Map<String, Object> row, String key) {
    return ((Number)row.get(key)).longValue();
  }
  static String stamp(Object value) {
    return value == null
        ? null
        : Instant.ofEpochMilli(((Number)value).longValue()).toString();
  }
  Map<String, Object> session() {
    var s = (Map<String, Object>)request.getProperty("session");
    if (s == null || s.get("user_id") == null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    return s;
  }
  Map<String, Object> user(Connection c, Map<String, Object> s)
      throws Exception {
    return query(c, "SELECT * FROM panel_user WHERE id=?", s.get("user_id"))
        .getFirst();
  }
  String cookie(String token, int age) {
    return COOKIE + "=" + token +
        "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + age +
        (secure ? "; Secure" : "");
  }
  Map<String, Object> create(Connection c, Object uid, String token,
                             long lifetime) throws Exception {
    long now = System.currentTimeMillis();
    String id = UUID.randomUUID().toString();
    update(
        c, "INSERT INTO web_session VALUES (?,?,?,?,?,?,?,?,?,?)", id,
        digest(token), uid, token(), now, now, now,
        now + Math.min(lifetime, 1800000), now + lifetime,
        Optional.ofNullable(request.getHeaderString("User-Agent"))
            .orElse("")
            .substring(0, Math.min(512, Optional
                                            .ofNullable(request.getHeaderString(
                                                "User-Agent"))
                                            .orElse("")
                                            .length())));
    return query(c, "SELECT * FROM web_session WHERE id=?", id).getFirst();
  }
  Map<String, Object> view(Map<String, Object> s, Object current) {
    var v = new HashMap<String, Object>();
    v.put("id", s.get("id"));
    v.put("userId", s.get("user_id"));
    v.put("current", s.get("id").equals(current));
    for (var p :
         Map.of("createdAt", "created_at", "authenticatedAt",
                "authenticated_at", "lastSeenAt", "last_seen", "idleExpiresAt",
                "idle_expires", "absoluteExpiresAt", "absolute_expires")
             .entrySet())
      v.put(p.getKey(), stamp(s.get(p.getValue())));
    v.put("userAgent", s.get("user_agent"));
    v.put("clientIp", null);
    return v;
  }
  Map<String, Object> context(Connection c, Map<String, Object> s)
      throws Exception {
    var u = user(c, s);
    var v = new HashMap<String, Object>();
    v.put("id", u.get("id"));
    v.put("username", u.get("username"));
    v.put("displayName", u.get("display_name"));
    v.put("roles", List.of("ADMIN"));
    v.put("status", "ACTIVE");
    v.put("mustChangePassword", u.get("must_change"));
    v.put("createdAt", stamp(u.get("created_at")));
    v.put("updatedAt", stamp(u.get("updated_at")));
    v.put("lastLoginAt", stamp(u.get("last_login")));
    return Map.of(
        "user", v, "session", view(s, s.get("id")), "permissions",
        Boolean.TRUE.equals(u.get("must_change"))
            ? List.of()
            : List.of("hosts:read", "services:restart", "admin:manage"),
        "csrfToken", s.get("csrf"));
  }
  @GET
  @Path("csrf")
  public Response csrf() {
    return store.transaction(c -> {
      var s = (Map<String, Object>)request.getProperty("session");
      if (s != null)
        return Response
            .ok(Map.of("csrfToken", s.get("csrf"), "expiresAt",
                       stamp(s.get("idle_expires"))))
            .build();
      String token = token();
      var fresh = create(c, null, token, 600000);
      return Response
          .ok(Map.of("csrfToken", fresh.get("csrf"), "expiresAt",
                     stamp(fresh.get("idle_expires"))))
          .header("Set-Cookie", cookie(token, 600))
          .build();
    });
  }
  public record Login(String username, String password) {}
  @POST
  @Path("login")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response login(Login input) {
    if (input == null || input.username() == null || input.password() == null ||
        input.password().length() > 1024)
      throw fail(401, "INVALID_CREDENTIALS", "用户名或密码不正确");
    limits.limit("account:" + input.username().toLowerCase(Locale.ROOT));
    try {
      return store.transaction(c -> {
        query(c, "SELECT id FROM auth_bootstrap_lock WHERE id=1 FOR UPDATE");
        // Bootstrap only an empty installation; subsequent restarts never
        // overwrite a password.
        if (query(c, "SELECT id FROM panel_user").isEmpty()) {
          String password =
              bootstrap
                  .filter(p
                          -> p.codePointCount(0, p.length()) >= 15 &&
                                 p.codePointCount(0, p.length()) <= 128)
                  .orElseThrow(
                      ()
                          -> fail(503, "BOOTSTRAP_REQUIRED",
                                  "请配置至少 15 字符的初始管理员密码"));
          long now = System.currentTimeMillis();
          update(
              c,
              "INSERT INTO panel_user "
                  +
                  "(id,username,display_name,password_hash,must_change,created_"
                  + "at,updated_at,last_login) VALUES (?,?,?,?,?,?,?,?)",
              UUID.randomUUID().toString(), "admin", "管理员", hash(password),
              true, now, now, null);
        }
        var users = query(c,
                          "SELECT * FROM panel_user WHERE username=? AND "
                              + "status='ACTIVE' FOR UPDATE",
                          input.username().toLowerCase(Locale.ROOT));
        String stored =
            users.isEmpty()
                ? hash("dummy-password-for-timing")
                : String.valueOf(users.getFirst().get("password_hash"));
        if (!verify(input.password(), stored) || users.isEmpty())
          throw fail(401, "INVALID_CREDENTIALS", "用户名或密码不正确");
        var u = users.getFirst();
        var old = (Map<String, Object>)request.getProperty("session");
        if (old.get("user_id") != null &&
            !old.get("user_id").equals(u.get("id")))
          throw fail(409, "ACCOUNT_MISMATCH", "请先退出当前账号");
        update(c, "DELETE FROM web_session WHERE id=?", old.get("id"));
        long now = System.currentTimeMillis();
        update(c, "UPDATE panel_user SET last_login=? WHERE id=?", now,
               u.get("id"));
        String token = token();
        var fresh = create(c, u.get("id"), token, 43200000);
        audit(c, String.valueOf(u.get("id")), "auth.login", "session",
              String.valueOf(fresh.get("id")), "SUCCEEDED",
              http.remoteAddress().host(), "管理员登录成功");
        var active = query(c,
                           "SELECT id FROM web_session WHERE user_id=? ORDER " +
                           "BY created_at DESC",
                           u.get("id"));
        for (int i = 10; i < active.size(); i++)
          update(c, "DELETE FROM web_session WHERE id=?",
                 active.get(i).get("id"));
        return Response.ok(context(c, fresh))
            .header("Set-Cookie", cookie(token, 43200))
            .build();
      });
    } catch (WebApplicationException e) {
      if (e.getResponse().getStatus() == 401 ||
          e.getResponse().getStatus() == 409) {
        store.transaction(c -> {
          audit(c, null, "auth.login", "session", null, "DENIED",
                http.remoteAddress().host(), "管理员登录验证失败");
          return null;
        });
      }
      throw e;
    }
  }
  @GET
  @Path("me")
  public Object me() {
    var s = session();
    return store.transaction(c -> context(c, s));
  }
  @POST
  @Path("logout")
  public Response logout() {
    var s = session();
    store.transaction(c -> {
      update(c, "DELETE FROM web_session WHERE id=?", s.get("id"));
      audit(c, String.valueOf(s.get("user_id")), "auth.logout", "session",
            String.valueOf(s.get("id")), "SUCCEEDED",
            http.remoteAddress().host(), "管理员退出登录");
      return null;
    });
    return Response.noContent().header("Set-Cookie", cookie("", 0)).build();
  }
  public record Password(String currentPassword, String newPassword) {}
  @PUT
  @Path("password")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response password(Password input) {
    var s = session();
    if (input == null || input.currentPassword() == null ||
        input.currentPassword().length() > 1024 || input.newPassword() == null)
      throw fail(422, "PASSWORD_REJECTED", "请填写有效密码");
    String p = input.newPassword();
    if (p.codePointCount(0, p.length()) < 15 ||
        p.codePointCount(0, p.length()) > 128 ||
        p.equals(input.currentPassword()) ||
        Set.of("password123456789", "123456789012345", "nanpanel-demo-2026")
            .contains(p.toLowerCase(Locale.ROOT)))
      throw fail(422, "PASSWORD_REJECTED",
                 "新密码须为 15–128 字符，且不能为常见密码或旧密码");
    store.transaction(c -> {
      query(c, "SELECT id FROM panel_user WHERE id=? FOR UPDATE",
            s.get("user_id"));
      var u = user(c, s);
      if (!verify(input.currentPassword(),
                  String.valueOf(u.get("password_hash"))))
        throw fail(400, "INVALID_PASSWORD", "旧密码不正确");
      update(c,
             "UPDATE panel_user SET "
                 + "password_hash=?,must_change=false,updated_at=? WHERE id=?",
             hash(p), System.currentTimeMillis(), u.get("id"));
      update(c, "DELETE FROM web_session WHERE user_id=?", u.get("id"));
      audit(c, String.valueOf(u.get("id")), "auth.password.change", "user",
            String.valueOf(u.get("id")), "SUCCEEDED",
            http.remoteAddress().host(), "管理员修改本人密码并撤销全部会话");
      return null;
    });
    return Response.noContent().header("Set-Cookie", cookie("", 0)).build();
  }
  @GET
  @Path("sessions")
  public Object sessions() {
    var s = session();
    return store.transaction(
        c
        -> Map.of("items",
                  query(c,
                        "SELECT * FROM web_session WHERE user_id=? "
                            + "AND idle_expires>? AND absolute_expires>? "
                            + "ORDER BY created_at DESC",
                        s.get("user_id"), System.currentTimeMillis(),
                        System.currentTimeMillis())
                      .stream()
                      .map(row -> view(row, s.get("id")))
                      .toList()));
  }
  @DELETE
  @Path("sessions/{id}")
  public Response revoke(@PathParam("id") String id) {
    var s = session();
    int count = store.transaction(c -> {
      int deleted =
          update(c, "DELETE FROM web_session WHERE id=? AND user_id=?", id,
                 s.get("user_id"));
      if (deleted > 0)
        audit(c, String.valueOf(s.get("user_id")), "auth.session.revoke",
              "session", id, "SUCCEEDED", http.remoteAddress().host(),
              "管理员撤销本人会话");
      return deleted;
    });
    if (count == 0)
      throw fail(404, "NOT_FOUND", "会话不存在");
    return Response.noContent()
        .header("Set-Cookie",
                id.equals(s.get("id")) ? cookie("", 0) : cookieFromRequest())
        .build();
  }
  String cookieFromRequest() {
    return cookie(request.getCookies().get(COOKIE).getValue(), 43200);
  }
}
