package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(RoleAssignmentTest.RoleAssignmentProfile.class)
class RoleAssignmentTest {
  public static class RoleAssignmentProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:role_assignment;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    }
  }

  @jakarta.inject.Inject AuthStore store;

  private record Session(String cookie, String token) {}

  private Session login(String username, String password, boolean expectMustChange) {
    var csrf = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var response = given().cookie("nanpanel_session", csrf.cookie("nanpanel_session"))
        .header("X-CSRF-Token", csrf.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
        .post("/api/v1/auth/login").then().statusCode(200)
        .body("user.mustChangePassword", equalTo(expectMustChange)).extract();
    return new Session(response.cookie("nanpanel_session"), response.path("csrfToken"));
  }

  @Test
  void anAdministratorCanAssignRolesWithoutLockingThePlatformOut() {
    // Bootstrap the seeded administrator, then rotate its password as the console does.
    Session bootstrapped = login("admin", "test-only-password", true);
    given().cookie("nanpanel_session", bootstrapped.cookie())
        .header("X-CSRF-Token", bootstrapped.token())
        .contentType("application/json")
        .body("{\"currentPassword\":\"test-only-password\","
            + "\"newPassword\":\"role-assign-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    Session admin = login("admin", "role-assign-password-2026", false);

    long now = System.currentTimeMillis();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO panel_role (role_id,name,builtin,created_at) "
          + "VALUES ('limited','受限角色',FALSE,?)", now);
      AuthStore.update(c, "INSERT INTO panel_role_permission (role_id,permission) "
          + "VALUES ('limited','host.read')");
      return null;
    });

    var created = given().cookie("nanpanel_session", admin.cookie())
        .header("X-CSRF-Token", admin.token())
        .contentType("application/json")
        .body("{\"username\":\"ops-user\",\"displayName\":\"运维\","
            + "\"initialPassword\":\"ops-secure-password-2026\"}")
        .post("/api/v1/admin/users").then().statusCode(201)
        .body("roles[0]", equalTo("ADMIN")).extract();
    String userId = created.path("id");

    // Assign the restricted role.
    given().cookie("nanpanel_session", admin.cookie())
        .header("X-CSRF-Token", admin.token())
        .header("If-Match", "\"user-" + userId + "-v0\"")
        .contentType("application/json")
        .body("{\"roleId\":\"limited\"}")
        .patch("/api/v1/admin/users/" + userId).then().statusCode(200)
        .body("roles[0]", equalTo("limited"));

    // Unknown roles are rejected.
    given().cookie("nanpanel_session", admin.cookie())
        .header("X-CSRF-Token", admin.token())
        .header("If-Match", "\"user-" + userId + "-v1\"")
        .contentType("application/json")
        .body("{\"roleId\":\"does-not-exist\"}")
        .patch("/api/v1/admin/users/" + userId).then().statusCode(422)
        .body("code", equalTo("VALIDATION_FAILED"));

    // The last remaining administrator cannot demote itself.
    var adminId = given().cookie("nanpanel_session", admin.cookie())
        .get("/api/v1/admin/users").then().statusCode(200).extract()
        .path("items.find { it.username == 'admin' }.id");
    given().cookie("nanpanel_session", admin.cookie())
        .header("X-CSRF-Token", admin.token())
        .header("If-Match", "\"user-" + adminId + "-v0\"")
        .contentType("application/json")
        .body("{\"roleId\":\"limited\"}")
        .patch("/api/v1/admin/users/" + adminId).then().statusCode(409)
        .body("code", equalTo("ADMIN_CONFLICT"));

    // The assignment is visible in the user list.
    given().cookie("nanpanel_session", admin.cookie()).get("/api/v1/admin/users")
        .then().statusCode(200)
        .body("items.find { it.username == 'ops-user' }.roles[0]", equalTo("limited"))
        .body("items.find { it.username == 'admin' }.roles[0]", equalTo("ADMIN"));
  }
}
