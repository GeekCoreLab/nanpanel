package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class K3sRolloutApiTest {
  @Inject AuthStore store;
  @Inject K3sRolloutResource rollouts;
  @Inject ObjectMapper mapper;

  private String sessionCookie;
  private String csrfToken;

  @BeforeEach
  void authenticateAdmin() {
    boolean adminExists = store.transaction(connection -> !AuthStore.query(connection,
        "SELECT id FROM panel_user WHERE username='admin'").isEmpty());
    if (!adminExists) {
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

  @Test
  void submittingStepReplaysStableUpgradeKeyWithoutDispatchingTwice() throws Exception {
    UUID userId = store.transaction(connection -> UUID.fromString(String.valueOf(
        AuthStore.query(connection,
            "SELECT id FROM panel_user WHERE username='rollout-test-admin'")
            .getFirst().get("id"))));
    UUID clusterId = UUID.randomUUID();
    UUID hostId = UUID.randomUUID();
    UUID planId = UUID.randomUUID();
    var now = new java.sql.Timestamp(System.currentTimeMillis());
    store.transaction(connection -> {
      UUID agentId = UUID.randomUUID();
      AuthStore.update(connection,
          "INSERT INTO host (id,hostname,capabilities,generation,observed_generation,status) "
              + "VALUES (?,?,'[\"k3s.upgrade\"]',1,1,'ONLINE')", hostId,
          "rollout-replay-server");
      AuthStore.update(connection,
          "INSERT INTO agent (id,host_id,cert_identity,version,last_seen,helper_status) "
              + "VALUES (?,?,?,'test',?,'AVAILABLE')", agentId, hostId,
          "rollout-replay-agent-" + agentId, now);
      AuthStore.update(connection,
          "INSERT INTO kubernetes_cluster (id,name,kind,endpoint,context_name,"
              + "encrypted_kubeconfig,health_status,observed_at,imported_by) "
              + "VALUES (?,?,'K3S','https://127.0.0.1:6443','rollout-replay-context',?,'HEALTHY',?,?)",
          clusterId, "rollout-replay-cluster", new byte[] {1}, now, userId);
      AuthStore.update(connection,
          "INSERT INTO kubernetes_cluster_node (cluster_id,node_uid,node_name,roles,ready,"
              + "kubelet_version,container_owner,host_id,observed_at,node_present) "
              + "VALUES (?,?,'rollout-replay-server','control-plane',TRUE,'v1.33.5+k3s1',"
              + "'KUBERNETES',?,?,TRUE)", clusterId, "rollout-replay-node-uid", hostId, now);
      AuthStore.update(connection,
          "INSERT INTO k3s_rollout_plan (id,cluster_id,target_version,reason,state,active_ordinal,"
              + "requested_by) VALUES (?,?,?,'Verify persisted upgrade submission replay',"
              + "'RUNNING',0,?)", planId, clusterId, "v1.34.0+k3s1", userId);
      AuthStore.update(connection,
          "INSERT INTO k3s_rollout_step (plan_id,ordinal,host_id,node_uid,node_name,role,"
              + "expected_generation,state,idempotency_key) VALUES (?,0,?,'rollout-replay-node-uid',"
              + "'rollout-replay-server','server',1,'PENDING',?)", planId, hostId,
          "k3s-rollout:" + planId + ":0");
      return null;
    });

    var dispatchCount = new AtomicInteger();
    var dispatchBody = new AtomicReference<String>();
    HttpServer fakeGateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 59999), 0);
    fakeGateway.createContext("/control", exchange -> {
      dispatchCount.incrementAndGet();
      dispatchBody.set(new String(exchange.getRequestBody().readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8));
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
    });
    fakeGateway.start();
    try {
      rollouts.advancePlans();
      JsonNode firstDispatch = mapper.readTree(dispatchBody.get());
      UUID operationId = UUID.fromString(firstDispatch.path("operationId").asText());
      assertEquals("k3s-rollout:" + planId + ":0",
          firstDispatch.path("idempotencyKey").asText());
      assertEquals(1, dispatchCount.get());

      store.transaction(connection -> {
        AuthStore.update(connection,
            "UPDATE k3s_rollout_step SET state='SUBMITTING',operation_id=NULL "
                + "WHERE plan_id=? AND ordinal=0", planId);
        return null;
      });
      rollouts.advancePlans();

      var persisted = store.transaction(connection -> {
        var step = AuthStore.query(connection,
            "SELECT state,operation_id FROM k3s_rollout_step WHERE plan_id=? AND ordinal=0", planId);
        var operations = AuthStore.query(connection,
            "SELECT id FROM operation WHERE idempotency_key=?", "k3s-rollout:" + planId + ":0");
        return Map.of("state", step.getFirst().get("state"),
            "operationId", step.getFirst().get("operation_id"),
            "operationCount", operations.size());
      });
      assertEquals(1, dispatchCount.get(), "A replayed operation must not be sent to the Agent twice");
      assertEquals(1, persisted.get("operationCount"));
      assertEquals("RUNNING", persisted.get("state"));
      assertEquals(operationId, persisted.get("operationId"));
    } finally {
      fakeGateway.stop(0);
    }
  }
}
