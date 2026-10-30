package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(PermissionEnforcementTest.PermissionProfile.class)
class PermissionEnforcementTest {
  public static class PermissionProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:permission_enforcement;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    }
  }

  @jakarta.inject.Inject AuthStore store;

  private void seedLimitedRoleAndUser() {
    long now = System.currentTimeMillis();
    String userId = UUID.randomUUID().toString();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO panel_role (role_id,name,builtin,created_at) "
          + "VALUES ('limited','受限角色',FALSE,?)", now);
      AuthStore.update(c, "INSERT INTO panel_role_permission (role_id,permission) "
          + "VALUES ('limited','host.read')");
      AuthStore.update(c, "INSERT INTO panel_user (id,username,display_name,password_hash,"
              + "must_change,created_at,updated_at,status,version,role_id) "
              + "VALUES (?,?,?,?,FALSE,?,?,'ACTIVE',0,'limited')",
          userId, "limited-user", "受限用户", AuthStore.hash("limited-secure-password-2026"),
          now, now);
      return null;
    });
  }

  private record Session(String cookie, String token) {}

  private Session login(String username, String password, boolean expectMustChange) {
    var csrf = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var response = given().cookie("nanpanel_session", csrf.cookie("nanpanel_session"))
        .header("X-CSRF-Token", csrf.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
        .post("/api/v1/auth/login").then().statusCode(200)
        .body("user.mustChangePassword", equalTo(expectMustChange))
        .extract();
    // Login rotates the session, and with it the CSRF token used for later writes.
    return new Session(response.cookie("nanpanel_session"), response.path("csrfToken"));
  }

  @Test
  void aRestrictedRoleIsDeniedWhileAdminKeepsFullAccess() {
    // Log the seeded administrator in first: first login bootstraps the admin account,
    // which only happens while panel_user is empty.
    Session bootstrapped = login("admin", "test-only-password", true);
    given().cookie("nanpanel_session", bootstrapped.cookie())
        .header("X-CSRF-Token", bootstrapped.token())
        .contentType("application/json")
        .body("{\"currentPassword\":\"test-only-password\","
            + "\"newPassword\":\"permission-secure-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    Session admin = login("admin", "permission-secure-password-2026", false);

    seedLimitedRoleAndUser();
    Session limited = login("limited-user", "limited-secure-password-2026", false);

    // Granted action passes the permission check (the endpoint itself still runs).
    given().cookie("nanpanel_session", limited.cookie()).get("/api/v1/hosts")
        .then().statusCode(200);

    // Everything outside the granted permission is rejected with a dedicated code.
    given().cookie("nanpanel_session", limited.cookie()).get("/api/v1/databases")
        .then().statusCode(403).body("code", equalTo("PERMISSION_DENIED"));
    given().cookie("nanpanel_session", limited.cookie()).get("/api/v1/admin/overview")
        .then().statusCode(403).body("code", equalTo("PERMISSION_DENIED"));
    given().cookie("nanpanel_session", limited.cookie()).get("/api/v1/alert-rules")
        .then().statusCode(403).body("code", equalTo("PERMISSION_DENIED"));
    given().cookie("nanpanel_session", limited.cookie())
        .header("X-CSRF-Token", limited.token())
        .contentType("application/json")
        .body("{\"name\":\"denied\",\"condition\":\"HOST_NOT_ONLINE\",\"severity\":\"WARNING\"}")
        .post("/api/v1/alert-rules").then().statusCode(403)
        .body("code", equalTo("PERMISSION_DENIED"));

    // Self-service auth endpoints stay reachable for a restricted role.
    given().cookie("nanpanel_session", limited.cookie()).get("/api/v1/auth/me")
        .then().statusCode(200);

    // ADMIN bypass: the built-in role still reaches the endpoints the limited role lost.
    given().cookie("nanpanel_session", admin.cookie()).get("/api/v1/databases")
        .then().statusCode(200);
    given().cookie("nanpanel_session", admin.cookie()).get("/api/v1/alert-rules")
        .then().statusCode(200);
  }
}
