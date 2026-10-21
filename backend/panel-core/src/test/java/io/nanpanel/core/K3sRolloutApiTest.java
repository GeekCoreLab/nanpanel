package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class K3sRolloutApiTest {
  @Inject AuthStore store;

  private String sessionCookie;
  private String csrfToken;

  @BeforeEach
  void authenticateAdmin() {
    // Preserve the suite's first-login bootstrap behavior before this test
    // inserts a separate user into an otherwise empty H2 database.
    var bootstrapCsrf = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var bootstrapLogin = given()
        .cookie("nanpanel_session", bootstrapCsrf.cookie("nanpanel_session"))
        .header("X-CSRF-Token", bootstrapCsrf.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("username", "admin", "password", "test-only-password"))
        .post("/api/v1/auth/login").then().extract();
    if (bootstrapLogin.statusCode() != 200 && bootstrapLogin.statusCode() != 401) {
      throw new AssertionError("Unable to preserve the test admin bootstrap fixture: HTTP "
          + bootstrapLogin.statusCode());
    }
    store.transaction(connection -> {
      var users = AuthStore.query(connection,
          "SELECT id FROM panel_user WHERE username='rollout-test-admin'");
      String id = users.isEmpty() ? UUID.randomUUID().toString()
          : String.valueOf(users.getFirst().get("id"));
      if (users.isEmpty()) {
        AuthStore.update(connection,
            "INSERT INTO panel_user (id,username,display_name,password_hash,must_change,status,"
                + "version,created_at,updated_at,last_login) VALUES (?,?,?, ?,FALSE,'ACTIVE',0,?,?,NULL)",
            id, "rollout-test-admin", "Rollout Test Admin", AuthStore.hash("rollout-test-password"),
            System.currentTimeMillis(), System.currentTimeMillis());
      } else {
        AuthStore.update(connection,
            "UPDATE panel_user SET password_hash=?,must_change=FALSE,status='ACTIVE' WHERE id=?",
            AuthStore.hash("rollout-test-password"), id);
      }
      AuthStore.update(connection, "DELETE FROM web_session WHERE user_id=?", id);
      return id;
    });
    var pre = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var login = given().cookie("nanpanel_session", pre.cookie("nanpanel_session"))
        .header("X-CSRF-Token", pre.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("username", "rollout-test-admin", "password", "rollout-test-password"))
        .post("/api/v1/auth/login").then().statusCode(200).extract();
    sessionCookie = login.cookie("nanpanel_session");
    csrfToken = login.path("csrfToken");
  }

  @Test
  void rolloutListStartsEmptyForANewPlanOwner() {
    given().cookie("nanpanel_session", sessionCookie)
        .get("/api/v1/kubernetes/rollouts")
        .then().statusCode(200).body("items", hasSize(0));
  }

  @Test
  void createRejectsFloatingVersionsBeforeContactingAnyCluster() {
    given().cookie("nanpanel_session", sessionCookie)
        .header("X-CSRF-Token", csrfToken)
        .contentType("application/json")
        .body(Map.of("clusterId", UUID.randomUUID().toString(), "targetVersion", "latest",
            "reason", "Reject an unpinned version"))
        .post("/api/v1/kubernetes/rollouts")
        .then().statusCode(422).body("code", equalTo("VALIDATION_FAILED"));
  }

  @Test
  void createRejectsAnUnregisteredClusterAndStartRejectsMalformedIds() {
    given().cookie("nanpanel_session", sessionCookie)
        .header("X-CSRF-Token", csrfToken)
        .contentType("application/json")
        .body(Map.of("clusterId", UUID.randomUUID().toString(), "targetVersion", "v1.34.0+k3s1",
            "reason", "Verify unknown cluster rejection"))
        .post("/api/v1/kubernetes/rollouts")
        .then().statusCode(404).body("code", equalTo("KUBERNETES_CLUSTER_NOT_FOUND"));
    given().cookie("nanpanel_session", sessionCookie)
        .header("X-CSRF-Token", csrfToken)
        .contentType("application/json").body(Map.of())
        .post("/api/v1/kubernetes/rollouts/not-a-uuid/start")
        .then().statusCode(404).body("code", equalTo("K3S_ROLLOUT_NOT_FOUND"));
  }
}
