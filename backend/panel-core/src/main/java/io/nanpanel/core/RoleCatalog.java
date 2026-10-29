package io.nanpanel.core;

import java.util.List;
import java.util.Optional;

/**
 * Built-in role definitions and their translation into authorisation statements.
 *
 * This is the single source of truth for the roles the console already exposes: the
 * only shipped role today is {@code ADMIN}, which holds every platform permission.
 * Persisted, user-defined roles and workspace scoping are not implemented yet, so
 * {@link #permits} is not wired into any endpoint.
 */
public final class RoleCatalog {

  private RoleCatalog() {}

  public record Role(String id, String name, List<String> permissions) {}

  private static final List<String> ADMIN_PERMISSIONS = List.of(
      "host.read", "agent.read", "system-service.read", "system-service.restart",
      "operation.read", "admin.overview.read", "admin.user.manage",
      "admin.settings.manage", "admin.audit.read",
      "app-store.read", "app-store.deploy",
      "site.read", "site.apply", "site.disable", "site.delete", "site.tls",
      "alert-rule.read", "alert-rule.create", "alert-rule.delete",
      "database.read",
      "file.read", "file.write", "file.delete");

  private static final List<Role> ROLES = List.of(
      new Role("ADMIN", "平台管理员", ADMIN_PERMISSIONS));

  public static List<Role> roles() {
    return ROLES;
  }

  public static Optional<Role> role(String id) {
    if (id == null) return Optional.empty();
    return ROLES.stream().filter(role -> role.id().equals(id)).findFirst();
  }

  /** One ALLOW statement covering every permission the role holds, on any resource. */
  public static List<AuthorizationPolicy.Statement> statements(String roleId) {
    return role(roleId)
        .map(role -> List.of(new AuthorizationPolicy.Statement(
            AuthorizationPolicy.ALLOW, role.permissions(), List.of("*"))))
        .orElse(List.of());
  }

  public static boolean permits(String roleId, String action, String resource) {
    return AuthorizationPolicy.permits(statements(roleId), action, resource);
  }
}
