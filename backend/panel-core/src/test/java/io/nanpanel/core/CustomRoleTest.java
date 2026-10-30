package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Custom roles must be creatable, editable, deletable — and actually authorise. */
@QuarkusTest
@TestProfile(CustomRoleTest.RoleProfile.class)
class CustomRoleTest {
  public static class RoleProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:custom_roles;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    }
  }

  @jakarta.inject.Inject AuthStore store;

  private record Session(String cookie, String token) {}

  private Session login(String username, String password, boolean expectMustChange) {
    var pre = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var login = given().cookie("nanpanel_session", pre.cookie("nanpanel_session"))
        .header("X-CSRF-Token", pre.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
        .post("/api/v1/auth/login").then().statusCode(200)
        .body("user.mustChangePassword", equalTo(expectMustChange)).extract();
    return new Session(login.cookie("nanpanel_session"), login.path("csrfToken"));
  }

  private Session admin() {
    var bootstrap = login("admin", "test-only-password", true);
    given().cookie("nanpanel_session", bootstrap.cookie())
        .header("X-CSRF-Token", bootstrap.token())
        .contentType("application/json")
        .body("{\"currentPassword\":\"test-only-password\","
            + "\"newPassword\":\"custom-role-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    return login("admin", "custom-role-password-2026", false);
  }

  @Test
  void rolesAreCreatedEditedAndDeletedWithGuards() {
    Session session = admin();

    // Validation first: the vocabulary is closed and the id is a slug.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token()).contentType("application/json")
        .body("{\"id\":\"operator\",\"name\":\"运维\",\"permissions\":[\"host.read\",\"nope\"]}")
        .post("/api/v1/admin/roles").then().statusCode(422)
        .body("code", equalTo("VALIDATION_FAILED"));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token()).contentType("application/json")
        .body("{\"id\":\"operator\",\"name\":\"运维\",\"permissions\":[]}")
        .post("/api/v1/admin/roles").then().statusCode(422);
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token()).contentType("application/json")
        .body("{\"id\":\"9 bad id\",\"name\":\"运维\",\"permissions\":[\"host.read\"]}")
        .post("/api/v1/admin/roles").then().statusCode(422);

    var created = given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token()).contentType("application/json")
        .body("{\"id\":\"operator\",\"name\":\"运维\",\"permissions\":"
            + "[\"host.read\",\"site.read\",\"site.apply\"]}")
        .post("/api/v1/admin/roles").then().statusCode(201)
        .body("id", equalTo("operator"))
        .body("permissions", hasItem("site.apply")).extract();
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token()).contentType("application/json")
        .body("{\"id\":\"operator\",\"name\":\"重复\",\"permissions\":[\"host.read\"]}")
        .post("/api/v1/admin/roles").then().statusCode(409)
        .body("code", equalTo("ROLE_EXISTS"));

    // Editing replaces the permission set rather than merging it.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token()).contentType("application/json")
        .body("{\"name\":\"值班运维\",\"permissions\":[\"host.read\"]}")
        .put("/api/v1/admin/roles/operator").then().statusCode(200)
        .body("name", equalTo("值班运维"))
        .body("permissions", equalTo(java.util.List.of("host.read")));
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/admin/roles")
        .then().statusCode(200)
        .body("items.find { it.id == 'operator' }.name", equalTo("值班运维"))
        .body("vocabulary", hasItem("site.apply"));

    // The built-in administrator cannot be edited or removed.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token()).contentType("application/json")
        .body("{\"name\":\"改名\"}").put("/api/v1/admin/roles/ADMIN").then().statusCode(409)
        .body("code", equalTo("ROLE_BUILTIN"));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .delete("/api/v1/admin/roles/ADMIN").then().statusCode(409)
        .body("code", equalTo("ROLE_BUILTIN"));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .delete("/api/v1/admin/roles/ghost").then().statusCode(404);

    // A role still used by an account cannot be deleted.
    var user = given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token()).contentType("application/json")
        .body("{\"username\":\"oncall\",\"displayName\":\"值班员\","
            + "\"initialPassword\":\"oncall-secure-password-2026\"}")
        .post("/api/v1/admin/users").then().statusCode(201).extract();
    String userId = user.path("id");
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("If-Match", "\"user-" + userId + "-v0\"")
        .contentType("application/json").body("{\"roleId\":\"operator\"}")
        .patch("/api/v1/admin/users/" + userId).then().statusCode(200)
        .body("roles[0]", equalTo("operator"));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .delete("/api/v1/admin/roles/operator").then().statusCode(409)
        .body("code", equalTo("ROLE_IN_USE"));

    // The custom role really authorises: it grants only what it lists.
    var oncall = login("oncall", "oncall-secure-password-2026", true);
    given().cookie("nanpanel_session", oncall.cookie())
        .header("X-CSRF-Token", oncall.token()).contentType("application/json")
        .body("{\"currentPassword\":\"oncall-secure-password-2026\","
            + "\"newPassword\":\"oncall-rotated-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    var restricted = login("oncall", "oncall-rotated-password-2026", false);
    given().cookie("nanpanel_session", restricted.cookie()).get("/api/v1/hosts")
        .then().statusCode(200);
    given().cookie("nanpanel_session", restricted.cookie()).get("/api/v1/reverse-proxy-sites")
        .then().statusCode(403).body("code", equalTo("PERMISSION_DENIED"));
    given().cookie("nanpanel_session", restricted.cookie()).get("/api/v1/admin/roles")
        .then().statusCode(403);

    // Once nothing uses it, the role can go — and it disappears from the list.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("If-Match", "\"user-" + userId + "-v1\"")
        .contentType("application/json").body("{\"roleId\":\"ADMIN\"}")
        .patch("/api/v1/admin/users/" + userId).then().statusCode(200);
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .delete("/api/v1/admin/roles/operator").then().statusCode(204);
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/admin/roles")
        .then().statusCode(200).body("items.id", not(hasItem("operator")));
  }
}
