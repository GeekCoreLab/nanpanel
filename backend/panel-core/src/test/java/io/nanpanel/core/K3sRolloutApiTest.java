package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class K3sRolloutApiTest {
  @Inject AuthStore store;
  @Inject K3sRolloutResource rollouts;

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

  @Test
  void unknownUpgradeOperationStopsPlanWithoutSubmittingNextNode() {
    var fixture = store.transaction(connection -> {
      String userId = String.valueOf(AuthStore.query(connection,
          "SELECT id FROM panel_user WHERE username='rollout-test-admin'").getFirst().get("id"));
      UUID clusterId = UUID.randomUUID();
      UUID hostId = UUID.randomUUID();
      UUID planId = UUID.randomUUID();
      UUID operationId = UUID.randomUUID();
      UUID secondHostId = UUID.randomUUID();
      AuthStore.update(connection,
          "INSERT INTO host (id,hostname,capabilities,generation,observed_generation,status) "
              + "VALUES (?,?,'[]',1,1,'ONLINE')", hostId, "rollout-unknown-server");
      AuthStore.update(connection,
          "INSERT INTO host (id,hostname,capabilities,generation,observed_generation,status) "
              + "VALUES (?,?,'[]',1,1,'ONLINE')", secondHostId, "rollout-pending-agent");
      AuthStore.update(connection,
          "INSERT INTO kubernetes_cluster (id,name,kind,endpoint,context_name,"
              + "encrypted_kubeconfig,health_status,imported_by) VALUES (?,?,'K3S',?,?,?,'UNKNOWN',?)",
          clusterId, "rollout-unknown-cluster", "https://127.0.0.1:6443", "rollout-unknown-context",
          new byte[] {1}, userId);
      AuthStore.update(connection,
          "INSERT INTO operation (id,idempotency_key,phase,status,error_json,finished_at) "
              + "VALUES (?,?,'UNKNOWN','UNKNOWN',?,?)", operationId, "rollout-unknown-operation",
          "{\"code\":\"EXECUTION_OUTCOME_UNKNOWN\"}", java.time.OffsetDateTime.now());
      AuthStore.update(connection,
          "INSERT INTO k3s_rollout_plan (id,cluster_id,target_version,reason,state,active_ordinal,"
              + "requested_by) VALUES (?,?,?,'test unknown upgrade result','RUNNING',0,?)",
          planId, clusterId, "v1.34.0+k3s1", userId);
      AuthStore.update(connection,
          "INSERT INTO k3s_rollout_step (plan_id,ordinal,host_id,node_uid,node_name,role,"
              + "expected_generation,state,idempotency_key,operation_id) "
              + "VALUES (?,0,?,'node-server-uid','server','server',1,'RUNNING',?,?),"
              + "(?,1,?,'node-agent-uid','agent','agent',1,'PENDING',?,NULL)",
          planId, hostId, "rollout-step-server", operationId,
          planId, secondHostId, "rollout-step-agent");
      return Map.of("planId", planId);
    });

    rollouts.advancePlans();
    rollouts.advancePlans();

    var states = store.transaction(connection -> {
      var plan = AuthStore.query(connection,
          "SELECT state FROM k3s_rollout_plan WHERE id=?", fixture.get("planId"));
      var steps = AuthStore.query(connection,
          "SELECT ordinal,state FROM k3s_rollout_step WHERE plan_id=? ORDER BY ordinal",
          fixture.get("planId"));
      var operations = AuthStore.query(connection,
          "SELECT id FROM operation WHERE idempotency_key='k3s-rollout:"
              + fixture.get("planId") + ":1'");
      return Map.of("plan", plan.getFirst().get("state"), "steps", steps,
          "nextOperationCount", operations.size());
    });

    assertEquals("UNKNOWN", states.get("plan"));
    @SuppressWarnings("unchecked")
    var steps = (java.util.List<Map<String, Object>>)states.get("steps");
    assertEquals("UNKNOWN", steps.get(0).get("state"));
    assertEquals("PENDING", steps.get(1).get("state"));
    assertEquals(0, states.get("nextOperationCount"));
  }
}
