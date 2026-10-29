package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(RoleRepositoryTest.RoleProfile.class)
class RoleRepositoryTest {
  public static class RoleProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:roles;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    }
  }

  @jakarta.inject.Inject RoleRepository roles;
  @jakarta.inject.Inject AuthStore store;

  @Test
  void builtInRolesAreSeededOnceAndServedUnchangedOverHttp() {
    var first = roles.roles();
    assertEquals(1, first.size());
    var admin = first.get(0);
    assertEquals("ADMIN", admin.id());
    assertEquals("平台管理员", admin.name());
    assertTrue(admin.permissions().contains("host.read"));
    assertTrue(admin.permissions().contains("database.read"));
    assertTrue(admin.permissions().contains("site.tls"));

    // Seeding is idempotent: a second read does not duplicate rows or permissions.
    var second = roles.roles();
    assertEquals(1, second.size());
    assertEquals(admin.permissions(), second.get(0).permissions());
    var roleRows = store.transaction(c -> AuthStore.query(c, "SELECT role_id FROM panel_role"));
    assertEquals(1, roleRows.size());
    var permissionRows = store.transaction(c ->
        AuthStore.query(c, "SELECT permission FROM panel_role_permission"));
    assertEquals(admin.permissions().size(), permissionRows.size());

    var pre = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var login = given().cookie("nanpanel_session", pre.cookie("nanpanel_session"))
        .header("X-CSRF-Token", pre.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"test-only-password\"}")
        .post("/api/v1/auth/login").then().statusCode(200)
        .body("user.mustChangePassword", equalTo(true)).extract();
    given().cookie("nanpanel_session", login.cookie("nanpanel_session"))
        .header("X-CSRF-Token", login.path("csrfToken"))
        .contentType("application/json")
        .body("{\"currentPassword\":\"test-only-password\","
            + "\"newPassword\":\"role-secure-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    var next = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var restored = given().cookie("nanpanel_session", next.cookie("nanpanel_session"))
        .header("X-CSRF-Token", next.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"role-secure-password-2026\"}")
        .post("/api/v1/auth/login").then().statusCode(200)
        .body("user.mustChangePassword", equalTo(false)).extract();

    given().cookie("nanpanel_session", restored.cookie("nanpanel_session"))
        .get("/api/v1/admin/roles").then().statusCode(200)
        .body("items", hasSize(1))
        .body("items[0].id", equalTo("ADMIN"))
        .body("items[0].permissions", hasItem("host.read"))
        .body("items[0].permissions", hasItem("file.write"));
  }
}
