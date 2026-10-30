package io.nanpanel.core;

import java.util.List;

/**
 * Maps an HTTP endpoint to the permission it requires. Rules are matched in order and
 * the first prefix match wins, so method-specific rules must precede a family-wide
 * {@code *} rule. {@link #PUBLIC} marks endpoints that must stay reachable without a
 * session (login, CSRF bootstrap, agent enrolment/download, internal agent callbacks).
 *
 * This is the mapping only: no endpoint enforces it yet. {@code EndpointPermissionsTest}
 * fails when a resource exposes an endpoint that has no rule, so the table cannot silently
 * fall behind new resources.
 */
public final class EndpointPermissions {

  private EndpointPermissions() {}

  public static final String PUBLIC = "PUBLIC";

  public record Rule(String method, String prefix, String permission) {}

  private static final String API = "/api/v1";

  private static final List<Rule> RULES = List.of(
      // Public: no session by design.
      new Rule("*", "/internal/", PUBLIC),
      new Rule("*", API + "/system", PUBLIC),
      new Rule("*", API + "/auth/csrf", PUBLIC),
      new Rule("*", API + "/auth/login", PUBLIC),
      new Rule("*", API + "/auth/logout", PUBLIC),
      new Rule("*", API + "/agent-enrollments/redeem", PUBLIC),
      new Rule("*", API + "/agent-enrollments", "agent.enroll"),
      new Rule("*", API + "/agent-installation", PUBLIC),
      new Rule("*", API + "/agent-controller-cert", PUBLIC),
      new Rule("*", API + "/agent-download", PUBLIC),
      new Rule("*", API + "/helper-download", PUBLIC),

      // Self-service session endpoints.
      new Rule("*", API + "/auth", "auth.self"),

      // Administration.
      new Rule("*", API + "/admin/users", "admin.user.manage"),
      new Rule("*", API + "/admin/roles", "admin.user.manage"),
      new Rule("*", API + "/admin/audit-events", "admin.audit.read"),
      new Rule("*", API + "/admin/settings", "admin.settings.manage"),
      new Rule("*", API + "/admin/overview", "admin.overview.read"),

      // Hosts, agents and their operations.
      new Rule("GET", API + "/hosts", "host.read"),
      new Rule("*", API + "/hosts", "host.write"),
      new Rule("GET", API + "/agents", "agent.read"),
      new Rule("*", API + "/agents", "agent.write"),
      new Rule("GET", API + "/operations", "operation.read"),
      new Rule("*", API + "/operations", "operation.read"),
      new Rule("*", API + "/host-application-releases", "host.deploy"),

      // Kubernetes and its providers.
      new Rule("GET", API + "/kubernetes", "kubernetes.read"),
      new Rule("*", API + "/kubernetes", "kubernetes.write"),
      new Rule("GET", API + "/cluster-providers", "kubernetes.read"),
      new Rule("*", API + "/cluster-providers", "kubernetes.write"),
      new Rule("*", API + "/config-translators", "kubernetes.write"),

      // Network, topology and platform plugins.
      new Rule("GET", API + "/topology", "topology.read"),
      new Rule("*", API + "/topology", "topology.write"),
      new Rule("GET", API + "/network-providers", "network.read"),
      new Rule("*", API + "/network-providers", "network.write"),
      new Rule("*", API + "/plugins", "admin.settings.manage"),

      // Panel features.
      new Rule("*", API + "/app-store/catalog", "app-store.read"),
      new Rule("GET", API + "/app-store/deployments", "app-store.read"),
      new Rule("*", API + "/app-store/deployments", "app-store.deploy"),
      new Rule("GET", API + "/reverse-proxy-sites", "site.read"),
      new Rule("DELETE", API + "/reverse-proxy-sites", "site.delete"),
      new Rule("*", API + "/reverse-proxy-sites", "site.apply"),
      new Rule("GET", API + "/alert-channels", "alert-rule.read"),
      new Rule("GET", API + "/alert-events", "alert-rule.read"),
      new Rule("DELETE", API + "/alert-channels", "alert-rule.delete"),
      new Rule("*", API + "/alert-channels", "alert-rule.create"),
      new Rule("GET", API + "/alert-rules", "alert-rule.read"),
      new Rule("POST", API + "/alert-rules", "alert-rule.create"),
      new Rule("DELETE", API + "/alert-rules", "alert-rule.delete"),
      new Rule("GET", API + "/alerts", "alert-rule.read"),
      new Rule("GET", API + "/databases", "database.read"),

      // Privileged SSH bastion sessions.
      new Rule("GET", API + "/bastion-sessions", "bastion.read"),
      new Rule("*", API + "/bastion-sessions", "bastion.session"));

  /** Permission required for a concrete request, or null when no rule matches. */
  public static String permissionFor(String method, String path) {
    if (method == null || path == null) return null;
    for (Rule rule : RULES) {
      if (!"*".equals(rule.method()) && !rule.method().equalsIgnoreCase(method)) continue;
      if (path.startsWith(rule.prefix())) return rule.permission();
    }
    return null;
  }

  public static List<Rule> rules() {
    return RULES;
  }
}
