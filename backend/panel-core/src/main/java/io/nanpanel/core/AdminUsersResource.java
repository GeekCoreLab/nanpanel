package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.COOKIE;
import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthResource.number;
import static io.nanpanel.core.AuthResource.stamp;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.digest;
import static io.nanpanel.core.AuthStore.hash;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/api/v1/admin")
@Produces(MediaType.APPLICATION_JSON)
public class AdminUsersResource {
  private static final Pattern USERNAME =
      Pattern.compile("^[a-z][a-z0-9_.-]{2,63}$");
  @Inject AuthStore store;
  @Inject io.vertx.core.http.HttpServerRequest http;
  @Inject
  @ConfigProperty(name = "nanpanel.auth.secure-cookie", defaultValue = "true")
  boolean secure;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;

  private Map<String, Object> session() {
    var s = (Map<String, Object>)request.getProperty("session");
    if (s == null || s.get("user_id") == null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    return s;
  }

  private void recent(Map<String, Object> session) {
    if (System.currentTimeMillis() - number(session, "authenticated_at") >
        300000) {
      store.transaction(c -> {
        audit(c, String.valueOf(session.get("user_id")),
              "security.reauthentication.required", "admin", null, "DENIED",
              http.remoteAddress().host(), "管理员写操作需要重新验证身份");
        return null;
      });
      throw fail(403, "REAUTHENTICATION_REQUIRED",
                 "请重新验证密码后刷新账号信息，再确认提交");
    }
  }

  private String tag(Map<String, Object> user) {
    return "\"user-" + user.get("id") + "-v" + user.get("version") + "\"";
  }

  private void checkTag(Map<String, Object> user) {
    String supplied = request.getHeaderString(HttpHeaders.IF_MATCH);
    if (supplied == null)
      throw fail(428, "PRECONDITION_REQUIRED", "更新账号需要 If-Match");
    if (!supplied.equals(tag(user)))
      throw fail(412, "PRECONDITION_FAILED", "账号已变化，请重新读取后确认");
  }

  private Map<String, Object> view(Map<String, Object> row) {
    var value = new LinkedHashMap<String, Object>();
    value.put("id", row.get("id"));
    value.put("username", row.get("username"));
    value.put("displayName", row.get("display_name"));
    value.put("roles", List.of("ADMIN"));
    value.put("status", row.get("status"));
    value.put("mustChangePassword", row.get("must_change"));
    value.put("createdAt", stamp(row.get("created_at")));
    value.put("updatedAt", stamp(row.get("updated_at")));
    value.put("lastLoginAt", stamp(row.get("last_login")));
    return value;
  }

  private Map<String, Object> byId(java.sql.Connection c, String id)
      throws Exception {
    var rows = query(c, "SELECT * FROM panel_user WHERE id=?", id);
    if (rows.isEmpty())
      throw fail(404, "NOT_FOUND", "管理员账号不存在");
    return rows.getFirst();
  }

  public record
      Create(String username, String displayName, String initialPassword) {}
  public record ResetPassword(String newPassword) {}

  @GET
  @Path("users")
  public Response list(@QueryParam("limit") Integer requestedLimit,
                       @QueryParam("cursor") String cursor,
                       @QueryParam("status") String status) {
    session();
    int limit = requestedLimit == null ? 20 : requestedLimit;
    if (limit < 1 || limit > 100)
      throw fail(400, "VALIDATION_FAILED", "limit 须为 1–100");
    if (status != null && !Set.of("ACTIVE", "DISABLED").contains(status))
      throw fail(400, "VALIDATION_FAILED", "无效账号状态筛选");
    int offset = 0;
    if (cursor != null) {
      try {
        String[] parts = new String(Base64.getUrlDecoder().decode(cursor),
                                    java.nio.charset.StandardCharsets.UTF_8)
                             .split("\\|", -1);
        if (parts.length != 2 ||
            !Objects.equals(parts[1], status == null ? "" : status))
          throw new IllegalArgumentException();
        offset = Integer.parseInt(parts[0]);
        if (offset < 0)
          throw new IllegalArgumentException();
      } catch (IllegalArgumentException e) {
        throw fail(400, "INVALID_CURSOR", "分页游标无效");
      }
    }
    final int start = offset;
    final String filter = status;
    return store.transaction(c -> {
      String where = filter == null ? "" : " WHERE status=?";
      var rows = filter == null
                     ? query(c,
                             "SELECT * FROM panel_user ORDER BY created_at,id "
                                 + "LIMIT ? OFFSET ?",
                             limit + 1, start)
                     : query(c,
                             "SELECT * FROM panel_user" + where +
                                 " ORDER BY created_at,id LIMIT ? OFFSET ?",
                             filter, limit + 1, start);
      boolean more = rows.size() > limit;
      var items = rows.stream().limit(limit).map(this::view).toList();
      String next =
          more ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                     ((start + limit) + "|" + (filter == null ? "" : filter))
                         .getBytes(java.nio.charset.StandardCharsets.UTF_8))
               : null;
      var body = new HashMap<String, Object>();
      body.put("items", items);
      body.put("nextCursor", next);
      return Response.ok(body).build();
    });
  }

  @POST
  @Path("users")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response create(Create input) {
    var actor = session();
    recent(actor);
    if (input == null || input.username() == null ||
        input.displayName() == null || input.initialPassword() == null)
      throw fail(422, "VALIDATION_FAILED", "用户名、显示名称和初始密码均必填");
    String username = input.username().trim().toLowerCase(Locale.ROOT);
    String displayName = input.displayName().trim();
    String password = input.initialPassword();
    if (!USERNAME.matcher(username).matches() || displayName.isEmpty() ||
        displayName.codePointCount(0, displayName.length()) > 100)
      throw fail(422, "VALIDATION_FAILED", "用户名或显示名称无效");
    validatePassword(password);
    String id = UUID.randomUUID().toString();
    long now = System.currentTimeMillis();
    return store.transaction(c -> {
      query(c, "SELECT id FROM auth_bootstrap_lock WHERE id=1 FOR UPDATE");
      if (!query(c, "SELECT id FROM panel_user WHERE username=?", username)
               .isEmpty())
        throw fail(409, "USERNAME_EXISTS", "用户名已存在");
      update(c,
             "INSERT INTO panel_user "
                 + "(id,username,display_name,password_hash,must_change,"
                 + "created_at,updated_at,last_login,status,version) "
                 + "VALUES (?,?,?,?,true,?,?,NULL,'ACTIVE',0)",
             id, username, displayName, hash(password), now, now);
      var user = byId(c, id);
      audit(c, String.valueOf(actor.get("user_id")), "admin.user.create",
            "user", id, "SUCCEEDED", http.remoteAddress().host(),
            "创建管理员账号");
      return Response
          .created(UriBuilder.fromResource(AdminUsersResource.class)
                       .path("users/{id}")
                       .build(id))
          .entity(view(user))
          .tag(tag(user))
          .build();
    });
  }

  @GET
  @Path("users/{id}")
  public Response get(@PathParam("id") String id) {
    session();
    return store.transaction(c -> {
      var user = byId(c, id);
      return Response.ok(view(user)).tag(tag(user)).build();
    });
  }

  @PATCH
  @Path("users/{id}")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response patch(@PathParam("id") String id, Map<String, Object> body) {
    var actor = session();
    recent(actor);
    if (body == null || body.isEmpty() ||
        !Set.of("displayName", "status").containsAll(body.keySet()))
      throw fail(422, "VALIDATION_FAILED", "仅支持更新显示名称和账号状态");
    String display = null;
    String status = null;
    if (body.containsKey("displayName")) {
      if (!(body.get("displayName") instanceof String value))
        throw fail(422, "VALIDATION_FAILED", "显示名称必须为非空文本");
      display = value.trim();
      if (display.isEmpty() ||
          display.codePointCount(0, display.length()) > 100)
        throw fail(422, "VALIDATION_FAILED", "显示名称长度须为 1–100 字符");
    }
    if (body.containsKey("status")) {
      if (!(body.get("status") instanceof String value) ||
          !Set.of("ACTIVE", "DISABLED").contains(value))
        throw fail(422, "VALIDATION_FAILED", "无效账号状态");
      status = (String)body.get("status");
    }
    final String newDisplay = display;
    final String newStatus = status;
    return store.transaction(c -> {
      query(c, "SELECT id FROM auth_bootstrap_lock WHERE id=1 FOR UPDATE");
      var user = query(c, "SELECT * FROM panel_user WHERE id=? FOR UPDATE", id);
      if (user.isEmpty())
        throw fail(404, "NOT_FOUND", "管理员账号不存在");
      var target = user.getFirst();
      checkTag(target);
      if ("DISABLED".equals(newStatus)) {
        if (id.equals(actor.get("user_id")))
          throw fail(409, "ADMIN_CONFLICT", "不能禁用当前管理员");
        long active =
            ((Number)query(c, "SELECT COUNT(*) AS count FROM "
                                  + "panel_user WHERE status='ACTIVE'")
                 .getFirst()
                 .get("count"))
                .longValue();
        if ("ACTIVE".equals(target.get("status")) && active <= 1)
          throw fail(409, "ADMIN_CONFLICT", "不能禁用最后一位管理员");
      }
      if (newDisplay != null || newStatus != null) {
        update(c,
               "UPDATE panel_user SET display_name=COALESCE(?,display_name),"
                   + "status=COALESCE(?,status),updated_at=?,version=version+1 "
                   + "WHERE id=?",
               newDisplay, newStatus, System.currentTimeMillis(), id);
      }
      if ("DISABLED".equals(newStatus))
        update(c, "DELETE FROM web_session WHERE user_id=?", id);
      audit(c, String.valueOf(actor.get("user_id")),
            newStatus == null ? "admin.user.profile.update"
                              : "admin.user.status.update",
            "user", id, "SUCCEEDED", http.remoteAddress().host(),
            newStatus == null ? "修改管理员显示名称" : "更新管理员账号状态");
      var updated = byId(c, id);
      return Response.ok(view(updated)).tag(tag(updated)).build();
    });
  }

  @PUT
  @Path("users/{id}/password")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response reset(@PathParam("id") String id, ResetPassword input) {
    var actor = session();
    recent(actor);
    if (id.equals(actor.get("user_id")))
      throw fail(409, "SELF_RESET_FORBIDDEN", "请使用本人改密功能");
    if (input == null || input.newPassword() == null)
      throw fail(422, "PASSWORD_REJECTED", "请填写有效初始密码");
    validatePassword(input.newPassword());
    return store.transaction(c -> {
      query(c, "SELECT id FROM auth_bootstrap_lock WHERE id=1 FOR UPDATE");
      var user = query(c, "SELECT * FROM panel_user WHERE id=? FOR UPDATE", id);
      if (user.isEmpty())
        throw fail(404, "NOT_FOUND", "管理员账号不存在");
      checkTag(user.getFirst());
      update(c,
             "UPDATE panel_user SET password_hash=?,must_change=true,"
                 + "updated_at=?,version=version+1 WHERE id=?",
             hash(input.newPassword()), System.currentTimeMillis(), id);
      update(c, "DELETE FROM web_session WHERE user_id=?", id);
      audit(c, String.valueOf(actor.get("user_id")),
            "admin.user.password.reset", "user", id, "SUCCEEDED",
            http.remoteAddress().host(), "重置管理员密码并撤销其会话");
      return Response.noContent().build();
    });
  }

  private void validatePassword(String password) {
    int length = password.codePointCount(0, password.length());
    if (length < 15 || length > 128 ||
        Set.of("password123456789", "123456789012345", "nanpanel-demo-2026")
            .contains(password.toLowerCase(Locale.ROOT)))
      throw fail(422, "PASSWORD_REJECTED",
                 "密码须为 15–128 字符，且不能为常见密码");
  }

  @GET
  @Path("roles")
  public Object roles() {
    session();
    return Map.of("items", RoleCatalog.roles().stream()
        .map(role -> Map.of("id", role.id(), "name", role.name(),
            "permissions", role.permissions()))
        .toList());
  }
}
