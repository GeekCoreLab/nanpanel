package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RoleCatalogTest {

  @Test
  void theOnlyBuiltInRoleIsAdminWithTheDocumentedPermissions() {
    var roles = RoleCatalog.roles();
    assertEquals(1, roles.size());
    var admin = roles.get(0);
    assertEquals("ADMIN", admin.id());
    assertEquals("平台管理员", admin.name());
    assertEquals(36, admin.permissions().size());
    assertTrue(admin.permissions().contains("host.read"));
    assertTrue(admin.permissions().contains("admin.user.manage"));
    assertTrue(admin.permissions().contains("system-service.restart"));
    assertTrue(admin.permissions().contains("database.read"));
    assertTrue(admin.permissions().contains("site.tls"));
    assertTrue(admin.permissions().contains("file.write"));
  }

  @Test
  void everyBuiltInPermissionIsAValidAuthorisationAction() {
    for (String permission : RoleCatalog.role("ADMIN").orElseThrow().permissions()) {
      assertTrue(AuthorizationPolicy.validAction(permission), permission);
    }
  }

  @Test
  void adminPermitsItsPermissionsOnAnyResource() {
    assertTrue(RoleCatalog.permits("ADMIN", "host.read", "host:h-1"));
    assertTrue(RoleCatalog.permits("ADMIN", "admin.user.manage", "user:u-1"));
    assertTrue(RoleCatalog.permits("ADMIN", "system-service.restart", "service:s-1"));
  }

  @Test
  void adminDoesNotPermitActionsOutsideItsList() {
    assertFalse(RoleCatalog.permits("ADMIN", "cluster.create", "cluster:c-1"));
    assertFalse(RoleCatalog.permits("ADMIN", "network.firewall.expose", "host:h-1"));
  }

  @Test
  void unknownRolesPermitNothing() {
    assertTrue(RoleCatalog.statements("NOPE").isEmpty());
    assertFalse(RoleCatalog.permits("NOPE", "host.read", "host:h-1"));
    assertFalse(RoleCatalog.permits(null, "host.read", "host:h-1"));
    assertTrue(RoleCatalog.role(null).isEmpty());
  }
}
