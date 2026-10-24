package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class HostApplicationReleaseApiTest {
  @Inject AuthStore store;
  @Inject HostApplicationReleaseService releases;
  @Inject ObjectMapper mapper;

  private String sessionCookie;
  private String csrfToken;

  @BeforeEach
  void authenticateAdmin() {
    String userId = store.transaction(connection -> {
      var users = AuthStore.query(connection,
          "SELECT id FROM panel_user WHERE username='host-release-test-admin'");
      String id = users.isEmpty() ? UUID.randomUUID().toString()
          : String.valueOf(users.getFirst().get("id"));
      if (users.isEmpty()) {
        AuthStore.update(connection,
            "INSERT INTO panel_user (id,username,display_name,password_hash,must_change,status,"
                + "version,created_at,updated_at,last_login) VALUES (?,?,?, ?,FALSE,'ACTIVE',0,?,?,NULL)",
            id, "host-release-test-admin", "Host Release Test Admin",
            AuthStore.hash("host-release-test-password"), System.currentTimeMillis(),
            System.currentTimeMillis());
      } else {
        AuthStore.update(connection,
            "UPDATE panel_user SET password_hash=?,must_change=FALSE,status='ACTIVE' WHERE id=?",
            AuthStore.hash("host-release-test-password"), id);
      }
      AuthStore.update(connection, "DELETE FROM web_session WHERE user_id=?", id);
      return id;
    });
    long now = System.currentTimeMillis();
    sessionCookie = AuthStore.token();
    csrfToken = AuthStore.token();
    String tokenHash = AuthStore.digest(sessionCookie);
    String sessionId = UUID.randomUUID().toString();
    store.transaction(connection -> {
      AuthStore.update(connection, "INSERT INTO web_session VALUES (?,?,?,?,?,?,?,?,?,?)",
          sessionId, tokenHash, userId, csrfToken, now, now, now, now + 1_800_000,
          now + 86_400_000, "host-release-test");
      return null;
    });
  }

  @Test
  void persistsAnEligibleHostReleaseAndRecoversDispatchByTheSameIdempotencyKey()
      throws Exception {
    UUID hostId = UUID.randomUUID();
    var now = java.sql.Timestamp.from(Instant.now());
    store.transaction(connection -> {
      AuthStore.update(connection,
          "INSERT INTO host (id,hostname,capabilities,generation,observed_generation,status) "
              + "VALUES (?,?,?,1,1,'ONLINE')", hostId, "host-release-target-" + hostId,
          "[\"container.deploy\",\"container.start\",\"container.stop\",\"nginx.route.set\"]");
      AuthStore.update(connection,
          "INSERT INTO agent (id,host_id,cert_identity,version,last_seen,helper_status) "
              + "VALUES (?,?,?,'test',?,'AVAILABLE')", UUID.randomUUID(), hostId,
          "host-release-agent-" + hostId, now);
      return null;
    });

    var request = Map.of("name", "web", "stableImage", "registry.example/web:1.0",
        "image", "registry.example/web:1.1", "containerPort", 8080,
        "serverName", "web.example.test", "listenPort", 18080, "healthPath", "/health",
        "batchSize", 1, "targets", List.of(Map.of("hostId", hostId.toString(),
            "listenAddress", "192.0.2.10", "expectedGeneration", 1)),
        "reason", "Verify persistent host release draft");
    String releaseId = given().cookie("nanpanel_session", sessionCookie)
        .header("X-CSRF-Token", csrfToken).contentType("application/json").body(request)
        .post("/api/v1/host-application-releases")
        .then().statusCode(201).body("state", equalTo("DRAFT"))
        .body("targets", hasSize(1)).extract().path("id");

    given().cookie("nanpanel_session", sessionCookie)
        .get("/api/v1/host-application-releases")
        .then().statusCode(200).body("items", hasSize(1))
        .body("items[0].id", equalTo(releaseId));

    given().cookie("nanpanel_session", sessionCookie)
        .header("X-CSRF-Token", csrfToken).contentType("application/json").body(request)
        .post("/api/v1/host-application-releases")
        .then().statusCode(409).body("code", equalTo("HOST_APPLICATION_RELEASE_CONFLICT"));

    String persistedState = store.transaction(connection -> String.valueOf(
        AuthStore.query(connection, "SELECT state FROM host_application_release_plan WHERE id=?",
            UUID.fromString(releaseId)).getFirst().get("state")));
    assertEquals("DRAFT", persistedState);

    var dispatchBody = new AtomicReference<String>();
    var dispatchCount = new AtomicInteger();
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
      given().cookie("nanpanel_session", sessionCookie).header("X-CSRF-Token", csrfToken)
          .contentType("application/json").body(Map.of())
          .post("/api/v1/host-application-releases/" + releaseId + "/start")
          .then().statusCode(200).body("state", equalTo("RUNNING"));

      releases.advancePlans();
      JsonNode firstDispatch = mapper.readTree(dispatchBody.get());
      UUID operationId = UUID.fromString(firstDispatch.path("operationId").asText());
      String idempotencyKey = "np-hostrel:" + releaseId + ":0:baseline_deploy";
      assertEquals(idempotencyKey, firstDispatch.path("idempotencyKey").asText());
      assertEquals("container.deploy", firstDispatch.path("capability").asText());
      assertEquals(1, dispatchCount.get());

      store.transaction(connection -> {
        AuthStore.update(connection,
            "UPDATE host_application_release_target SET operation_id=NULL WHERE release_id=? AND ordinal=0",
            UUID.fromString(releaseId));
        return null;
      });
      releases.advancePlans();
      JsonNode replay = mapper.readTree(dispatchBody.get());
      assertEquals(operationId.toString(), replay.path("operationId").asText());
      assertEquals(idempotencyKey, replay.path("idempotencyKey").asText());
      assertEquals(2, dispatchCount.get());
      int operationCount = store.transaction(connection -> AuthStore.query(connection,
          "SELECT id FROM operation WHERE idempotency_key=?", idempotencyKey).size());
      assertEquals(1, operationCount);
    } finally {
      fakeGateway.stop(0);
      cleanupTestRelease(releaseId);
    }
  }

  @Test
  void reconcilesAnUnknownHelperResultByReplayingTheSameOperationIdentity() throws Exception {
    UUID hostId = eligibleHost("host-release-uncertain-");
    var request = Map.of("name", "uncertain-web", "stableImage", "registry.example/web:1.0",
        "image", "registry.example/web:1.1", "containerPort", 8080,
        "serverName", "uncertain.example.test", "listenPort", 18080, "healthPath", "/health",
        "batchSize", 1, "targets", List.of(Map.of("hostId", hostId.toString(),
            "listenAddress", "192.0.2.12", "expectedGeneration", 1)),
        "reason", "Recover a persisted uncertain Helper result");
    String releaseId = given().cookie("nanpanel_session", sessionCookie)
        .header("X-CSRF-Token", csrfToken).contentType("application/json").body(request)
        .post("/api/v1/host-application-releases")
        .then().statusCode(201).extract().path("id");

    var dispatchBody = new AtomicReference<String>();
    var dispatchCount = new AtomicInteger();
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
      given().cookie("nanpanel_session", sessionCookie).header("X-CSRF-Token", csrfToken)
          .contentType("application/json").body(Map.of())
          .post("/api/v1/host-application-releases/" + releaseId + "/start")
          .then().statusCode(200);
      releases.advancePlans();
      JsonNode initial = mapper.readTree(dispatchBody.get());
      String operationKey = initial.path("idempotencyKey").asText();
      UUID operationId = UUID.fromString(initial.path("operationId").asText());

      store.transaction(connection -> {
        AuthStore.update(connection,
            "UPDATE operation SET status='UNKNOWN',phase='VERIFY',error_json=? WHERE id=?",
            mapper.writeValueAsString(Map.of(
                "code", "EXECUTION_OUTCOME_UNKNOWN",
                "message", "Agent result delivery was interrupted",
                "executionOutcome", "UNCERTAIN",
                "agentDetail", "UNCERTAIN: simulated lost result")), operationId);
        AuthStore.update(connection,
            "UPDATE host_application_release_plan SET state='UNKNOWN',"
                + "error_code='EXECUTION_OUTCOME_UNKNOWN' WHERE id=?",
            UUID.fromString(releaseId));
        return null;
      });

      given().cookie("nanpanel_session", sessionCookie).header("X-CSRF-Token", csrfToken)
          .contentType("application/json").body(Map.of())
          .post("/api/v1/host-application-releases/" + releaseId + "/reconcile")
          .then().statusCode(200).body("state", equalTo("RUNNING"));

      JsonNode replay = mapper.readTree(dispatchBody.get());
      assertEquals(2, dispatchCount.get());
      assertEquals(initial.path("operationId").asText(), replay.path("operationId").asText());
      assertEquals(operationKey, replay.path("idempotencyKey").asText());
      assertEquals(initial.path("capability").asText(), replay.path("capability").asText());
      assertEquals(initial.path("target").asText(), replay.path("target").asText());
      assertEquals(initial.path("payloadJson").asText(), replay.path("payloadJson").asText());
      int matchingOperations = store.transaction(connection -> AuthStore.query(connection,
          "SELECT id FROM operation WHERE idempotency_key=?", operationKey).size());
      assertEquals(1, matchingOperations);

      releases.advancePlans();
      assertEquals(2, dispatchCount.get(), "an unresolved result must not cause blind retries");
      assertEquals("UNKNOWN", releases.get(UUID.fromString(releaseId), sessionAdminId()).state());

      given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
          .contentType("application/json").body(Map.of(
              "agentId", initial.path("agentId").asText(),
              "operationId", initial.path("operationId").asText(),
              "idempotencyKey", operationKey,
              "status", "SUCCEEDED",
              "detail", "Operation completed."))
          .post("/internal/agent-control-results")
          .then().statusCode(204).header("X-NanPanel-Result-Ack", equalTo("true"));
      releases.advancePlans();
      var recovered = releases.get(UUID.fromString(releaseId), sessionAdminId());
      assertEquals("RUNNING", recovered.state());
      assertEquals("BASELINE_ROUTE", recovered.targets().getFirst().state());

      releases.advancePlans();
      JsonNode routeOperation = mapper.readTree(dispatchBody.get());
      assertEquals(3, dispatchCount.get());
      String uncertainDetail = "UNCERTAIN: Helper result was interrupted";
      var uncertainResult = Map.of(
          "agentId", routeOperation.path("agentId").asText(),
          "operationId", routeOperation.path("operationId").asText(),
          "idempotencyKey", routeOperation.path("idempotencyKey").asText(),
          "status", "FAILED",
          "detail", uncertainDetail);
      given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
          .contentType("application/json").body(uncertainResult)
          .post("/internal/agent-control-results")
          .then().statusCode(202).header("X-NanPanel-Result-Ack", org.hamcrest.Matchers.nullValue());
      releases.advancePlans();
      assertEquals("UNKNOWN", releases.get(UUID.fromString(releaseId), sessionAdminId()).state());

      given().cookie("nanpanel_session", sessionCookie).header("X-CSRF-Token", csrfToken)
          .contentType("application/json").body(Map.of())
          .post("/api/v1/host-application-releases/" + releaseId + "/reconcile")
          .then().statusCode(200);
      JsonNode uncertainReplay = mapper.readTree(dispatchBody.get());
      assertEquals(4, dispatchCount.get());
      assertEquals(routeOperation.path("operationId").asText(),
          uncertainReplay.path("operationId").asText());
      assertEquals(routeOperation.path("idempotencyKey").asText(),
          uncertainReplay.path("idempotencyKey").asText());

      given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
          .contentType("application/json").body(uncertainResult)
          .post("/internal/agent-control-results")
          .then().statusCode(202).header("X-NanPanel-Result-Ack", org.hamcrest.Matchers.nullValue());
      releases.advancePlans();
      assertEquals("UNKNOWN", releases.get(UUID.fromString(releaseId), sessionAdminId()).state());
      assertEquals(4, dispatchCount.get(), "an unresolved result stays blocked without re-execution");
    } finally {
      fakeGateway.stop(0);
      cleanupTestRelease(releaseId);
    }
  }

  @Test
  void gatesHostBatchesAndCompensatesAnUnhealthyCandidateBeforeReverseRollback()
      throws Exception {
    UUID firstHost = eligibleHost("host-release-batch-first-");
    UUID secondHost = eligibleHost("host-release-batch-second-");
    var request = Map.of("name", "batch-web", "stableImage", "registry.example/web:1.0",
        "image", "registry.example/web:1.1", "containerPort", 8080,
        "serverName", "batch.example.test", "listenPort", 18080, "healthPath", "/health",
        "batchSize", 1,
        "targets", List.of(
            Map.of("hostId", firstHost.toString(), "listenAddress", "192.0.2.10",
                "expectedGeneration", 1),
            Map.of("hostId", secondHost.toString(), "listenAddress", "192.0.2.11",
                "expectedGeneration", 1)),
        "reason", "Verify ordered host batches and route compensation");
    String releaseId = given().cookie("nanpanel_session", sessionCookie)
        .header("X-CSRF-Token", csrfToken).contentType("application/json").body(request)
        .post("/api/v1/host-application-releases")
        .then().statusCode(201).body("state", equalTo("DRAFT"))
        .body("targets", hasSize(2)).extract().path("id");

    var dispatchBody = new AtomicReference<String>();
    var dispatchCount = new AtomicInteger();
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
      given().cookie("nanpanel_session", sessionCookie).header("X-CSRF-Token", csrfToken)
          .contentType("application/json").body(Map.of())
          .post("/api/v1/host-application-releases/" + releaseId + "/start")
          .then().statusCode(200).body("state", equalTo("RUNNING"));

      JsonNode operation = dispatchNext(dispatchBody, dispatchCount, 1);
      assertEquals("container.deploy", operation.path("capability").asText());
      assertTrue(operation.path("idempotencyKey").asText().endsWith(":0:baseline_deploy"));
      completeOperation(operation, 204);
      releases.advancePlans();

      operation = dispatchNext(dispatchBody, dispatchCount, 2);
      assertEquals("nginx.route.set", operation.path("capability").asText());
      completeOperation(operation, 204);
      releases.advancePlans();

      operation = dispatchNext(dispatchBody, dispatchCount, 3);
      assertEquals("container.deploy", operation.path("capability").asText());
      completeOperation(operation, 204);
      releases.advancePlans();

      operation = dispatchNext(dispatchBody, dispatchCount, 4);
      assertEquals("nginx.route.set", operation.path("capability").asText());
      completeOperation(operation, 204);
      releases.advancePlans();

      operation = dispatchNext(dispatchBody, dispatchCount, 5);
      assertEquals("container.stop", operation.path("capability").asText());
      completeOperation(operation, 204);
      releases.advancePlans();
      var paused = releases.get(UUID.fromString(releaseId), sessionAdminId());
      assertEquals("PAUSED", paused.state());
      assertEquals(1, paused.activeOrdinal());
      assertEquals("SUCCEEDED", paused.targets().getFirst().state());
      assertEquals("BASELINE_DEPLOY", paused.targets().get(1).state());
      assertEquals(5, dispatchCount.get(), "the next host must wait for batch approval");

      given().cookie("nanpanel_session", sessionCookie).header("X-CSRF-Token", csrfToken)
          .contentType("application/json").body(Map.of())
          .post("/api/v1/host-application-releases/" + releaseId + "/resume")
          .then().statusCode(200).body("state", equalTo("RUNNING"));
      operation = dispatchNext(dispatchBody, dispatchCount, 6);
      assertTrue(operation.path("idempotencyKey").asText().endsWith(":1:baseline_deploy"));
      completeOperation(operation, 204);
      releases.advancePlans();

      operation = dispatchNext(dispatchBody, dispatchCount, 7);
      completeOperation(operation, 204);
      releases.advancePlans();
      operation = dispatchNext(dispatchBody, dispatchCount, 8);
      completeOperation(operation, 204);
      releases.advancePlans();
      operation = dispatchNext(dispatchBody, dispatchCount, 9);
      assertEquals("nginx.route.set", operation.path("capability").asText());
      completeOperation(operation, 503);
      releases.advancePlans();

      operation = dispatchNext(dispatchBody, dispatchCount, 10);
      assertEquals("container.start", operation.path("capability").asText());
      completeOperation(operation, 204);
      releases.advancePlans();
      operation = dispatchNext(dispatchBody, dispatchCount, 11);
      assertEquals("nginx.route.set", operation.path("capability").asText());
      JsonNode stableRoute = mapper.readTree(operation.path("payloadJson").asText());
      assertEquals(100, stableRoute.path("backends").get(0).path("weight").asInt());
      assertEquals(0, stableRoute.path("backends").get(1).path("weight").asInt());
      completeOperation(operation, 204);
      releases.advancePlans();
      operation = dispatchNext(dispatchBody, dispatchCount, 12);
      assertEquals("container.stop", operation.path("capability").asText());
      completeOperation(operation, 204);
      releases.advancePlans();

      var failed = releases.get(UUID.fromString(releaseId), sessionAdminId());
      assertEquals("FAILED", failed.state());
      assertEquals("HOST_APPLICATION_RELEASE_FAILED", failed.errorCode());
      assertEquals("SUCCEEDED", failed.targets().getFirst().state());
      assertEquals("FAILED", failed.targets().get(1).state());
      assertDeploymentImage(firstHost, "batch-web", "registry.example/web:1.1");
      assertDeploymentImage(secondHost, "batch-web", "registry.example/web:1.0");

      given().cookie("nanpanel_session", sessionCookie).header("X-CSRF-Token", csrfToken)
          .contentType("application/json").body(Map.of())
          .post("/api/v1/host-application-releases/" + releaseId + "/rollback")
          .then().statusCode(200).body("state", equalTo("ROLLING_BACK"));
      operation = dispatchNext(dispatchBody, dispatchCount, 13);
      assertTrue(operation.path("idempotencyKey").asText().endsWith(":0:manual_rollback_start"));
      completeOperation(operation, 204);
      releases.advancePlans();
      operation = dispatchNext(dispatchBody, dispatchCount, 14);
      assertEquals("nginx.route.set", operation.path("capability").asText());
      completeOperation(operation, 204);
      releases.advancePlans();
      operation = dispatchNext(dispatchBody, dispatchCount, 15);
      assertEquals("container.stop", operation.path("capability").asText());
      completeOperation(operation, 204);
      releases.advancePlans();

      var rolledBack = releases.get(UUID.fromString(releaseId), sessionAdminId());
      assertEquals("ROLLED_BACK", rolledBack.state());
      assertEquals("ROLLED_BACK", rolledBack.targets().getFirst().state());
      assertEquals("FAILED", rolledBack.targets().get(1).state());
      assertDeploymentImage(firstHost, "batch-web", "registry.example/web:1.0");
      assertDeploymentImage(secondHost, "batch-web", "registry.example/web:1.0");
    } finally {
      fakeGateway.stop(0);
    }
  }

  @Test
  void rejectsInvalidListenersAndUnknownTargetsBeforeDispatch() {
    var request = Map.of("name", "web", "stableImage", "registry.example/web:1.0",
        "image", "registry.example/web:1.1", "containerPort", 8080,
        "serverName", "web.example.test", "listenPort", 18080, "healthPath", "/health",
        "batchSize", 1, "targets", List.of(Map.of("hostId", UUID.randomUUID().toString(),
            "listenAddress", "0.0.0.0", "expectedGeneration", 1)),
        "reason", "Reject an unsafe listener");
    given().cookie("nanpanel_session", sessionCookie)
        .header("X-CSRF-Token", csrfToken).contentType("application/json").body(request)
        .post("/api/v1/host-application-releases")
        .then().statusCode(422).body("code", equalTo("VALIDATION_FAILED"));
  }

  private UUID eligibleHost(String hostnamePrefix) {
    UUID hostId = UUID.randomUUID();
    var now = java.sql.Timestamp.from(Instant.now());
    store.transaction(connection -> {
      AuthStore.update(connection,
          "INSERT INTO host (id,hostname,capabilities,generation,observed_generation,status) "
              + "VALUES (?,?,?,1,1,'ONLINE')", hostId, hostnamePrefix + hostId,
          "[\"container.deploy\",\"container.start\",\"container.stop\",\"nginx.route.set\"]");
      AuthStore.update(connection,
          "INSERT INTO agent (id,host_id,cert_identity,version,last_seen,helper_status) "
              + "VALUES (?,?,?,'test',?,'AVAILABLE')", UUID.randomUUID(), hostId,
          "host-release-agent-" + hostId, now);
      return null;
    });
    return hostId;
  }

  private JsonNode dispatchNext(AtomicReference<String> dispatchBody, AtomicInteger dispatchCount,
      int expectedCount) throws Exception {
    releases.advancePlans();
    assertEquals(expectedCount, dispatchCount.get());
    return mapper.readTree(dispatchBody.get());
  }

  private void completeOperation(JsonNode dispatch, int routedHttpStatus) throws Exception {
    JsonNode result = mapper.createObjectNode();
    if ("nginx.route.set".equals(dispatch.path("capability").asText())) {
      JsonNode payload = mapper.readTree(dispatch.path("payloadJson").asText());
      var route = mapper.createObjectNode().put("route", dispatch.path("target").asText())
          .put("configSha256", "a".repeat(64))
          .put("listenAddress", payload.path("listenAddress").asText())
          .put("listenPort", payload.path("listenPort").asInt())
          .put("routedHttpStatus", routedHttpStatus);
      var weights = route.putArray("backendWeights");
      for (JsonNode backend : payload.path("backends")) {
        var pair = weights.addArray();
        pair.add(backend.path("name").asText());
        pair.add(backend.path("weight").asInt());
      }
      result = route;
    }
    String resultJson = mapper.writeValueAsString(result);
    Timestamp now = Timestamp.from(Instant.now());
    UUID operationId = UUID.fromString(dispatch.path("operationId").asText());
    store.transaction(connection -> {
      AuthStore.update(connection,
          "UPDATE operation SET status='SUCCEEDED',phase='COMMIT',result_json=?,error_json=NULL,"
              + "updated_at=?,finished_at=? WHERE id=?",
          resultJson, now, now, operationId);
      return null;
    });
  }

  private void assertDeploymentImage(UUID hostId, String application, String expectedImage) {
    String image = store.transaction(connection -> String.valueOf(AuthStore.query(connection,
        "SELECT image FROM host_application_deployment WHERE host_id=? AND application_name=?",
        hostId, application).getFirst().get("image")));
    assertEquals(expectedImage, image);
  }

  private String sessionAdminId() {
    return store.transaction(connection -> String.valueOf(AuthStore.query(connection,
        "SELECT id FROM panel_user WHERE username='host-release-test-admin'").getFirst().get("id")));
  }

  private void cleanupTestRelease(String releaseId) {
    UUID id = UUID.fromString(releaseId);
    Timestamp now = Timestamp.from(Instant.now());
    store.transaction(connection -> {
      AuthStore.update(connection,
          "UPDATE host_application_release_plan SET state='FAILED',finished_at=? WHERE id=?",
          now, id);
      AuthStore.update(connection,
          "UPDATE operation SET status='FAILED',error_json='{}',updated_at=?,finished_at=? "
              + "WHERE id IN (SELECT operation_id FROM host_application_release_target "
              + "WHERE release_id=? AND operation_id IS NOT NULL) "
              + "AND status IN ('PENDING','RUNNING','UNKNOWN')",
          now, now, id);
      return null;
    });
  }
}
