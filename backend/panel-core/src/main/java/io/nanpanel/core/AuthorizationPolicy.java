package io.nanpanel.core;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Authorisation decision core for role-based access control. Deliberately pure and
 * not wired into any endpoint yet: the semantics are pinned down by unit tests before
 * touching the authentication path.
 *
 * Rules:
 * <ul>
 *   <li>Default deny — a request is allowed only when an explicit ALLOW matches.</li>
 *   <li>Deny wins — any matching DENY rejects, regardless of ALLOW statements.</li>
 *   <li>Actions use the platform's existing dotted vocabulary ({@code host.read},
 *       {@code admin.user.manage}); wildcards are {@code *} and {@code host.*}.
 *       Resources are {@code type:id}, with {@code host:*} matching any host.</li>
 *   <li>A statement that matches the action but not the resource has no effect.</li>
 * </ul>
 */
public final class AuthorizationPolicy {

  private AuthorizationPolicy() {}

  public static final String ALLOW = "ALLOW";
  public static final String DENY = "DENY";
  public static final Set<String> EFFECTS = Set.of(ALLOW, DENY);

  /** A single policy statement: `effect` over a set of actions and resources. */
  public record Statement(String effect, List<String> actions, List<String> resources) {}

  private static final java.util.regex.Pattern ACTION =
      java.util.regex.Pattern.compile("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+");
  private static final java.util.regex.Pattern ACTION_PATTERN =
      java.util.regex.Pattern.compile(
          "(\\*|[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*\\.\\*"
              + "|[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+)");
  private static final java.util.regex.Pattern RESOURCE_PATTERN =
      java.util.regex.Pattern.compile("(\\*|[a-z][a-z0-9-]*:[A-Za-z0-9._-]+|[a-z][a-z0-9-]*:\\*)");

  public static boolean permits(List<Statement> statements, String action, String resource) {
    if (statements == null || statements.isEmpty() || !validAction(action)
        || resource == null || resource.isBlank()) {
      return false;
    }
    boolean allowed = false;
    for (Statement statement : statements) {
      if (statement == null || !validStatement(statement)) continue;
      if (!matchesAny(statement.actions(), action)) continue;
      if (!matchesAny(statement.resources(), resource)) continue;
      if (DENY.equals(statement.effect())) return false;
      allowed = true;
    }
    return allowed;
  }

  /** A statement is only usable when its effect is known and every pattern is well formed. */
  public static boolean validStatement(Statement statement) {
    if (statement == null || !EFFECTS.contains(statement.effect())) return false;
    if (statement.actions() == null || statement.actions().isEmpty()
        || statement.resources() == null || statement.resources().isEmpty()) {
      return false;
    }
    return statement.actions().stream().allMatch(action -> action != null
            && ACTION_PATTERN.matcher(action).matches())
        && statement.resources().stream().allMatch(resource -> resource != null
            && RESOURCE_PATTERN.matcher(resource).matches());
  }

  public static boolean validAction(String action) {
    return action != null && ACTION.matcher(action).matches();
  }

  /** `*`, `prefix.*` or an exact match; `type:*` wildcards the resource id. */
  private static boolean matchesAny(List<String> patterns, String value) {
    if (patterns == null) return false;
    for (String pattern : patterns) {
      if (pattern == null) continue;
      String norm = pattern.toLowerCase(Locale.ROOT);
      if ("*".equals(norm) || norm.equals(value)) return true;
      if (norm.endsWith(".*") && value.startsWith(norm.substring(0, norm.length() - 1))) {
        return true;
      }
      if (norm.endsWith(":*") && value.startsWith(norm.substring(0, norm.length() - 1))) {
        return true;
      }
    }
    return false;
  }
}
