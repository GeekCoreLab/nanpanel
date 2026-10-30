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
  @Inject RoleRepository roleRepository;
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
    value.put("roles", List.of(row.get("role_id") == null ? "ADMIN"
        : String.valueOf(row.get("role_id"))));
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
        !Set.of("displayName", "status", "roleId").containsAll(body.keySet()))
      throw fail(422, "VALIDATION_FAILED", "仅支持更新显示名称、账号状态和角色");
    String display = null;
    String status = null;
    String roleId = null;
    if (body.containsKey("roleId")) {
      if (!(body.get("roleId") instanceof String value)
          || !value.matches("[A-Za-z][A-Za-z0-9_-]{1,63}"))
        throw fail(422, "VALIDATION_FAILED", "无效角色");
      roleId = value;
    }
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
    final String newRole = roleId;
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
      if (newRole != null) {
        var roles = query(c, "SELECT role_id FROM panel_role WHERE role_id=?", newRole);
        if (roles.isEmpty())
          throw fail(422, "VALIDATION_FAILED", "角色不存在");
        boolean wasAdmin = "ADMIN".equals(String.valueOf(target.get("role_id")));
        if (wasAdmin && !"ADMIN".equals(newRole)
            && "ACTIVE".equals(target.get("status"))) {
          long admins = ((Number)query(c,
              "SELECT COUNT(*) AS count FROM panel_user "
                  + "WHERE role_id='ADMIN' AND status='ACTIVE' AND id<>?", id)
              .getFirst().get("count")).longValue();
          if (admins == 0)
            throw fail(409, "ADMIN_CONFLICT", "不能移除最后一位管理员的管理员角色");
        }
      }
      if (newDisplay != null || newStatus != null || newRole != null) {
        update(c,
               "UPDATE panel_user SET display_name=COALESCE(?,display_name),"
                   + "status=COALESCE(?,status),role_id=COALESCE(?,role_id),"
                   + "updated_at=?,version=version+1 WHERE id=?",
               newDisplay, newStatus, newRole, System.currentTimeMillis(), id);
      }
      if ("DISABLED".equals(newStatus))
        update(c, "DELETE FROM web_session WHERE user_id=?", id);
      String action = newRole != null ? "admin.user.role.update"
          : newStatus != null ? "admin.user.status.update"
                              : "admin.user.profile.update";
      audit(c, String.valueOf(actor.get("user_id")), action, "user", id,
            "SUCCEEDED", http.remoteAddress().host(),
            newRole != null ? "更新管理员角色为 " + newRole
                : newStatus != null ? "更新管理员账号状态" : "修改管理员显示名称");
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

  public record RoleRequest(String id, String name, List<String> permissions) {}

  @GET
  @Path("roles")
  public Object roles() {
    session();
    return Map.of("items", roleRepository.roles().stream()
            .map(role -> Map.of("id", role.id(), "name", role.name(),
                "permissions", role.permissions()))
            .toList(),
        "vocabulary", RoleCatalog.vocabulary());
  }

  @POST
  @Path("roles")
  public Response createRole(RoleRequest input) {
    var actor = session();
    recent(actor);
    roleRepository.roles(); // seeds the built-ins if this is the first role operation
    String id = roleId(input == null ? null : input.id());
    String name = roleName(input == null ? null : input.name());
    List<String> permissions = rolePermissions(input == null ? null : input.permissions());
    long now = System.currentTimeMillis();
    store.transaction(c -> {
      if (!query(c, "SELECT role_id FROM panel_role WHERE role_id=?", id).isEmpty())
        throw fail(409, "ROLE_EXISTS", "角色标识已存在");
      update(c, "INSERT INTO panel_role (role_id,name,builtin,created_at) VALUES (?,?,FALSE,?)",
          id, name, now);
      for (String permission : permissions)
        update(c, "INSERT INTO panel_role_permission (role_id,permission) VALUES (?,?)",
            id, permission);
      audit(c, String.valueOf(actor.get("user_id")), "admin.role.create", "panel_role", id,
          "SUCCEEDED", http.remoteAddress().host(), "创建角色 " + name);
      return null;
    });
    return Response.status(201).entity(roleView(id)).build();
  }

  @PUT
  @Path("roles/{roleId}")
  public Response updateRole(@PathParam("roleId") String roleValue, RoleRequest input) {
    var actor = session();
    recent(actor);
    roleRepository.roles();
    String id = roleId(roleValue);
    builtin(id);
    String name = input == null || input.name() == null ? null : roleName(input.name());
    List<String> permissions = input == null || input.permissions() == null ? null
        : rolePermissions(input.permissions());
    if (name == null && permissions == null)
      throw fail(422, "VALIDATION_FAILED", "没有需要更新的字段");
    store.transaction(c -> {
      if (name != null) update(c, "UPDATE panel_role SET name=? WHERE role_id=?", name, id);
      if (permissions != null) {
        // Replace wholesale: a role edit is a statement about the whole permission set.
        update(c, "DELETE FROM panel_role_permission WHERE role_id=?", id);
        for (String permission : permissions)
          update(c, "INSERT INTO panel_role_permission (role_id,permission) VALUES (?,?)",
              id, permission);
      }
      audit(c, String.valueOf(actor.get("user_id")), "admin.role.update", "panel_role", id,
          "SUCCEEDED", http.remoteAddress().host(), "更新角色 " + id);
      return null;
    });
    return Response.ok(roleView(id)).build();
  }

  @DELETE
  @Path("roles/{roleId}")
  public Response deleteRole(@PathParam("roleId") String roleValue) {
    var actor = session();
    recent(actor);
    roleRepository.roles();
    String id = roleId(roleValue);
    builtin(id);
    store.transaction(c -> {
      var users = query(c, "SELECT id FROM panel_user WHERE role_id=?", id);
      if (!users.isEmpty())
        throw fail(409, "ROLE_IN_USE", "仍有 " + users.size() + " 个账号使用该角色，请先改派");
      update(c, "DELETE FROM panel_role_permission WHERE role_id=?", id);
      update(c, "DELETE FROM panel_role WHERE role_id=?", id);
      audit(c, String.valueOf(actor.get("user_id")), "admin.role.delete", "panel_role", id,
          "SUCCEEDED", http.remoteAddress().host(), "删除角色 " + id);
      return null;
    });
    return Response.noContent().build();
  }

  private Map<String, Object> roleView(String id) {
    return roleRepository.roles().stream()
        .filter(role -> role.id().equals(id))
        .findFirst()
        .<Map<String, Object>>map(role -> Map.of("id", role.id(), "name", role.name(),
            "permissions", role.permissions()))
        .orElseThrow(() -> fail(404, "ROLE_NOT_FOUND", "角色不存在"));
  }

  private static String roleId(String value) {
    if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_-]{1,63}"))
      throw fail(422, "VALIDATION_FAILED", "角色标识需为 2–64 位字母、数字、下划线或连字符");
    return value;
  }

  private static String roleName(String value) {
    String name = value.trim();
    if (name.isEmpty() || name.codePointCount(0, name.length()) > 120)
      throw fail(422, "VALIDATION_FAILED", "角色名称需为 1–120 字符");
    return name;
  }

  private static List<String> rolePermissions(List<String> values) {
    if (values == null || values.isEmpty())
      throw fail(422, "VALIDATION_FAILED", "至少需要一个权限");
    var known = RoleCatalog.vocabulary();
    for (String permission : values)
      if (!known.contains(permission))
        throw fail(422, "VALIDATION_FAILED", "未知权限：" + permission);
    return List.copyOf(new java.util.LinkedHashSet<>(values));
  }

  /** The built-in administrator role is what guarantees the platform stays manageable. */
  private void builtin(String id) {
    var rows = store.transaction(c -> query(c,
        "SELECT builtin FROM panel_role WHERE role_id=?", id));
    if (rows.isEmpty()) throw fail(404, "ROLE_NOT_FOUND", "角色不存在");
    if (Boolean.TRUE.equals(rows.getFirst().get("builtin")))
      throw fail(409, "ROLE_BUILTIN", "内置角色不可修改或删除");
  }
}
