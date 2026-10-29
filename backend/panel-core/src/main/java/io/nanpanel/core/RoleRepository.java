package io.nanpanel.core;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted role definitions (migration 047). The built-in catalogue is seeded from
 * {@link RoleCatalog} on first read, so the Java definition stays the single source of
 * truth and no permission list is duplicated in SQL where it could drift.
 *
 * Authorisation itself is still not enforced by any endpoint.
 */
@ApplicationScoped
public class RoleRepository {

  @Inject AuthStore store;

  public synchronized List<RoleCatalog.Role> roles() {
    seedBuiltins();
    var roleRows = store.transaction(c -> AuthStore.query(c,
        "SELECT role_id, name FROM panel_role ORDER BY builtin DESC, role_id"));
    var permissionRows = store.transaction(c -> AuthStore.query(c,
        "SELECT role_id, permission FROM panel_role_permission ORDER BY permission"));
    Map<String, List<String>> permissions = new LinkedHashMap<>();
    for (var row : permissionRows) {
      permissions.computeIfAbsent(String.valueOf(row.get("role_id")), ignored -> new ArrayList<>())
          .add(String.valueOf(row.get("permission")));
    }
    List<RoleCatalog.Role> roles = new ArrayList<>(roleRows.size());
    for (var row : roleRows) {
      String id = String.valueOf(row.get("role_id"));
      roles.add(new RoleCatalog.Role(id, String.valueOf(row.get("name")),
          List.copyOf(permissions.getOrDefault(id, List.of()))));
    }
    return roles;
  }

  /** Insert the built-in roles once; a second call is a no-op. */
  private void seedBuiltins() {
    var existing = store.transaction(c -> AuthStore.query(c, "SELECT role_id FROM panel_role"));
    if (!existing.isEmpty()) return;
    long now = System.currentTimeMillis();
    store.transaction(c -> {
      for (RoleCatalog.Role role : RoleCatalog.roles()) {
        AuthStore.update(c, "INSERT INTO panel_role (role_id,name,builtin,created_at) "
            + "VALUES (?,?,TRUE,?)", role.id(), role.name(), now);
        for (String permission : role.permissions()) {
          AuthStore.update(c, "INSERT INTO panel_role_permission (role_id,permission) "
              + "VALUES (?,?)", role.id(), permission);
        }
      }
      return null;
    });
  }
}
