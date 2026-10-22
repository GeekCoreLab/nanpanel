package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
class K3sInstallationApiTest {
  @Inject AuthStore store;
  @Inject K3sInstallationResource installations;
  @Inject ObjectMapper mapper;
  private String sessionCookie;
  private String csrfToken;

  @BeforeEach
  void authenticateAdmin() {
    boolean adminExists = store.transaction(connection -> !AuthStore.query(connection,
        "SELECT id FROM panel_user WHERE username='admin'").isEmpty());
    if (!adminExists) {
      var bootstrap = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
      var login = given().cookie("nanpanel_session", bootstrap.cookie("nanpanel_session"))
          .header("X-CSRF-Token", bootstrap.path("csrfToken"))
          .contentType("application/json")
          .body(Map.of("username", "admin", "password", "test-only-password"))
          .post("/api/v1/auth/login").then().extract();
      if (login.statusCode() != 200 && login.statusCode() != 401) {
        throw new AssertionError("Unable to establish the test admin session: HTTP "
            + login.statusCode());
      }
    }
    store.transaction(connection -> {
      var users = AuthStore.query(connection,
          "SELECT id FROM panel_user WHERE username='installation-test-admin'");
      String id = users.isEmpty() ? UUID.randomUUID().toString()
          : String.valueOf(users.getFirst().get("id"));
      if (users.isEmpty()) {
        AuthStore.update(connection,
            "INSERT INTO panel_user (id,username,display_name,password_hash,must_change,status,"
                + "version,created_at,updated_at,last_login) VALUES (?,?,?, ?,FALSE,'ACTIVE',0,?,?,NULL)",
            id, "installation-test-admin", "Installation Test Admin",
            AuthStore.hash("installation-test-password"), System.currentTimeMillis(),
            System.currentTimeMillis());
      } else {
        AuthStore.update(connection,
            "UPDATE panel_user SET password_hash=?,must_change=FALSE,status='ACTIVE' WHERE id=?",
            AuthStore.hash("installation-test-password"), id);
      }
      AuthStore.update(connection, "DELETE FROM web_session WHERE user_id=?", id);
      return null;
    });
    var pre = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var authenticated = given().cookie("nanpanel_session", pre.cookie("nanpanel_session"))
        .header("X-CSRF-Token", pre.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("username", "installation-test-admin", "password", "installation-test-password"))
        .post("/api/v1/auth/login").then().statusCode(200).extract();
    sessionCookie = authenticated.cookie("nanpanel_session");
    csrfToken = authenticated.path("csrfToken");
  }

  @Test
  void installationRejectsUnpinnedVersionsBeforePersistingSecrets() {
    String token = "test-install-token-0123456789";
    given().cookie("nanpanel_session", sessionCookie)
        .header("X-CSRF-Token", csrfToken)
        .contentType("application/json")
        .body(Map.of("serverHostId", UUID.randomUUID().toString(), "agentHostIds", new String[0],
            "targetVersion", "stable", "serverUrl", "https://k3s.example.net:6443",
            "token", token, "reason", "Reject an unpinned version"))
        .post("/api/v1/kubernetes/installations")
        .then().statusCode(422).body("code", equalTo("VALIDATION_FAILED"))
        .body(not(containsString(token)));
  }

  @Test
  void installationDoesNotEchoTokenWhenHostIsMissingAndHidesUnknownPlans() {
    String token = "test-install-token-0123456789";
    given().cookie("nanpanel_session", sessionCookie)
        .header("X-CSRF-Token", csrfToken)
        .contentType("application/json")
        .body(Map.of("serverHostId", UUID.randomUUID().toString(), "agentHostIds", new String[0],
            "targetVersion", "v1.33.5+k3s1", "serverUrl", "https://k3s.example.net:6443",
            "token", token, "reason", "Ensure the short token is not echoed"))
        .post("/api/v1/kubernetes/installations")
        .then().statusCode(404).body("code", equalTo("NOT_FOUND"))
        .body(not(containsString(token)));

    given().cookie("nanpanel_session", sessionCookie)
        .get("/api/v1/kubernetes/installations/" + UUID.randomUUID())
        .then().statusCode(404).body("code", equalTo("K3S_INSTALL_PLAN_NOT_FOUND"));
  }

  @Test
  void createsOrderedServerAndAgentDraftWithoutReturningOrPersistingPlaintextToken() {
    UUID serverId = insertInstallCapableHost("p4-install-server");
    UUID agentId = insertInstallCapableHost("p4-install-agent");
    String token = "test-install-token-0123456789";
    String planId = given().cookie("nanpanel_session", sessionCookie)
        .header("X-CSRF-Token", csrfToken)
        .contentType("application/json")
        .body(Map.of("serverHostId", serverId.toString(), "agentHostIds", new String[] { agentId.toString() },
            "targetVersion", "v1.33.5+k3s1", "serverUrl", "https://k3s.example.net:6443",
            "token", token, "reason", "Verify persistent ordered installation draft"))
        .post("/api/v1/kubernetes/installations")
        .then().statusCode(201)
        .body("state", equalTo("DRAFT"))
        .body("steps.size()", equalTo(2))
        .body("steps[0].hostId", equalTo(serverId.toString()))
        .body("steps[0].role", equalTo("server"))
        .body("steps[1].hostId", equalTo(agentId.toString()))
        .body("steps[1].role", equalTo("agent"))
        .body(not(containsString(token)))
        .extract().path("id");
    store.transaction(connection -> {
      var rows = AuthStore.query(connection,
          "SELECT encrypted_token FROM k3s_install_plan WHERE id=?", UUID.fromString(planId));
      byte[] envelope = (byte[])rows.getFirst().get("encrypted_token");
      assertFalse(new String(envelope, java.nio.charset.StandardCharsets.UTF_8).contains(token));
      return null;
    });
  }

  @Test
  void startRechecksAgentHelperAvailabilityBeforeChangingDraftState() {
    UUID serverId = insertInstallCapableHost("p4-preflight-server");
    UUID agentId = insertInstallCapableHost("p4-preflight-agent");
    String planId = given().cookie("nanpanel_session", sessionCookie)
        .header("X-CSRF-Token", csrfToken)
        .contentType("application/json")
        .body(Map.of("serverHostId", serverId.toString(), "agentHostIds", new String[] { agentId.toString() },
            "targetVersion", "v1.33.5+k3s1", "serverUrl", "https://k3s.example.net:6443",
            "token", "test-install-token-0123456789", "reason", "Verify install start preflight"))
        .post("/api/v1/kubernetes/installations")
        .then().statusCode(201).extract().path("id");
    store.transaction(connection -> {
      AuthStore.update(connection, "UPDATE agent SET helper_status='UNAVAILABLE' WHERE host_id=?", agentId);
      return null;
    });

    given().cookie("nanpanel_session", sessionCookie)
        .header("X-CSRF-Token", csrfToken)
        .contentType("application/json").body(Map.of())
        .post("/api/v1/kubernetes/installations/" + planId + "/start")
        .then().statusCode(409).body("code", equalTo("K3S_INSTALL_PREFLIGHT_FAILED"));
    given().cookie("nanpanel_session", sessionCookie)
        .get("/api/v1/kubernetes/installations/" + planId)
        .then().statusCode(200).body("state", equalTo("DRAFT"));
  }

  @Test
  void schedulerInstallsServerFirstAndPausesUntilClusterRegistration() throws Exception {
    UUID serverId = insertInstallCapableHost("p4-scheduler-server");
    UUID agentId = insertInstallCapableHost("p4-scheduler-agent");
    String token = "test-install-token-0123456789";
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
      String planId = given().cookie("nanpanel_session", sessionCookie)
          .header("X-CSRF-Token", csrfToken)
          .contentType("application/json")
          .body(Map.of("serverHostId", serverId.toString(), "agentHostIds", new String[] { agentId.toString() },
              "targetVersion", "v1.33.5+k3s1", "serverUrl", "https://k3s.example.net:6443",
              "token", token, "reason", "Verify Server-first scheduler gate"))
          .post("/api/v1/kubernetes/installations")
          .then().statusCode(201).extract().path("id");
      given().cookie("nanpanel_session", sessionCookie)
          .header("X-CSRF-Token", csrfToken)
          .contentType("application/json").body(Map.of())
          .post("/api/v1/kubernetes/installations/" + planId + "/start")
          .then().statusCode(202).body("state", equalTo("RUNNING"));

      installations.advancePlans();
      assertNotNull(dispatchBody.get());
      JsonNode dispatch = mapper.readTree(dispatchBody.get());
      assertEquals("k3s.install", dispatch.path("capability").asText());
      JsonNode payload = mapper.readTree(dispatch.path("payloadJson").asText());
      assertEquals("server", payload.path("role").asText());
      assertEquals(token, payload.path("token").asText());
      UUID operationId = UUID.fromString(dispatch.path("operationId").asText());
      store.transaction(connection -> {
        AuthStore.update(connection,
            "UPDATE k3s_install_step SET state='SUBMITTING',operation_id=NULL "
                + "WHERE plan_id=? AND ordinal=0", UUID.fromString(planId));
        return null;
      });
      installations.advancePlans();
      assertEquals(1, dispatchCount.get(), "Restart recovery must replay by the stable idempotency key");
      int persistedOperations = store.transaction(connection -> AuthStore.query(connection,
          "SELECT id FROM operation WHERE idempotency_key=?", "k3s-install:" + planId + ":0").size());
      assertEquals(1, persistedOperations);
      assertEquals("RUNNING", store.transaction(connection -> AuthStore.query(connection,
          "SELECT state FROM k3s_install_step WHERE plan_id=? AND ordinal=0",
          UUID.fromString(planId)).getFirst().get("state")));
      store.transaction(connection -> {
        AuthStore.update(connection,
            "UPDATE operation SET status='SUCCEEDED',phase='COMMIT',result_json=?,finished_at=?,updated_at=? WHERE id=?",
            "{\"serviceState\":\"ACTIVE\",\"clusterRegistration\":\"NODE_READY\"}",
            new java.sql.Timestamp(System.currentTimeMillis()),
            new java.sql.Timestamp(System.currentTimeMillis()), operationId);
        return null;
      });

      installations.advancePlans();
      given().cookie("nanpanel_session", sessionCookie)
          .get("/api/v1/kubernetes/installations/" + planId)
          .then().statusCode(200)
          .body("state", equalTo("AWAITING_CLUSTER_REGISTRATION"))
          .body("steps[0].state", equalTo("SUCCEEDED"));
      assertEquals(1, dispatchCount.get(), "Agent operation must remain locked until cluster import and mapping");
    } finally {
      fakeGateway.stop(0);
    }
  }

  @Test
  void singleNodePlanInstallsOnlyServerAndWaitsForClusterMapping() throws Exception {
    UUID serverId = insertInstallCapableHost("p4-single-node-server");
    String token = "test-single-node-token-0123456789";
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
      String planId = given().cookie("nanpanel_session", sessionCookie)
          .header("X-CSRF-Token", csrfToken)
          .contentType("application/json")
          .body(Map.of("serverHostId", serverId.toString(), "agentHostIds", new String[0],
              "targetVersion", "v1.33.5+k3s1", "serverUrl", "https://k3s.example.net:6443",
              "token", token, "reason", "Verify the supported single-node installation path"))
          .post("/api/v1/kubernetes/installations")
          .then().statusCode(201).body("steps.size()", equalTo(1))
          .body("steps[0].role", equalTo("server"))
          .body(not(containsString(token))).extract().path("id");
      given().cookie("nanpanel_session", sessionCookie)
          .header("X-CSRF-Token", csrfToken)
          .contentType("application/json").body(Map.of())
          .post("/api/v1/kubernetes/installations/" + planId + "/start")
          .then().statusCode(202).body("state", equalTo("RUNNING"));

      installations.advancePlans();
      assertNotNull(dispatchBody.get());
      JsonNode dispatch = mapper.readTree(dispatchBody.get());
      assertEquals("server", mapper.readTree(dispatch.path("payloadJson").asText())
          .path("role").asText());
      UUID operationId = UUID.fromString(dispatch.path("operationId").asText());
      store.transaction(connection -> {
        AuthStore.update(connection,
            "UPDATE operation SET status='SUCCEEDED',phase='COMMIT',result_json=?,finished_at=?,updated_at=? WHERE id=?",
            "{\"serviceState\":\"ACTIVE\",\"clusterRegistration\":\"NODE_READY\"}",
            new java.sql.Timestamp(System.currentTimeMillis()),
            new java.sql.Timestamp(System.currentTimeMillis()), operationId);
        return null;
      });

      installations.advancePlans();
      given().cookie("nanpanel_session", sessionCookie)
          .get("/api/v1/kubernetes/installations/" + planId)
          .then().statusCode(200)
          .body("state", equalTo("AWAITING_CLUSTER_REGISTRATION"))
          .body("steps.size()", equalTo(1))
          .body("steps[0].state", equalTo("SUCCEEDED"))
          .body(not(containsString(token)));
      installations.advancePlans();
      assertEquals(1, dispatchCount.get(), "Single-node plans must never dispatch an Agent install");
      int agentOperationCount = store.transaction(connection -> AuthStore.query(connection,
          "SELECT id FROM operation WHERE idempotency_key=?", "k3s-install:" + planId + ":1").size());
      assertEquals(0, agentOperationCount);
    } finally {
      fakeGateway.stop(0);
    }
  }

  @Test
  void unknownServerOperationStopsPlanAndPreventsAgentDispatch() throws Exception {
    UUID serverId = insertInstallCapableHost("p4-unknown-server");
    UUID agentId = insertInstallCapableHost("p4-unknown-agent");
    String token = "test-install-token-0123456789";
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
      String planId = given().cookie("nanpanel_session", sessionCookie)
          .header("X-CSRF-Token", csrfToken)
          .contentType("application/json")
          .body(Map.of("serverHostId", serverId.toString(),
              "agentHostIds", new String[] { agentId.toString() },
              "targetVersion", "v1.33.5+k3s1", "serverUrl", "https://k3s.example.net:6443",
              "token", token, "reason", "Stop after an unknown Server operation"))
          .post("/api/v1/kubernetes/installations")
          .then().statusCode(201).extract().path("id");
      given().cookie("nanpanel_session", sessionCookie)
          .header("X-CSRF-Token", csrfToken)
          .contentType("application/json").body(Map.of())
          .post("/api/v1/kubernetes/installations/" + planId + "/start")
          .then().statusCode(202).body("state", equalTo("RUNNING"));

      installations.advancePlans();
      assertNotNull(dispatchBody.get());
      JsonNode dispatch = mapper.readTree(dispatchBody.get());
      assertEquals("server", mapper.readTree(dispatch.path("payloadJson").asText())
          .path("role").asText());
      UUID operationId = UUID.fromString(dispatch.path("operationId").asText());
      store.transaction(connection -> {
        AuthStore.update(connection,
            "UPDATE operation SET status='UNKNOWN',phase='UNKNOWN',error_json=?,finished_at=?,updated_at=? WHERE id=?",
            "{\"code\":\"AGENT_RESULT_UNKNOWN\"}",
            new java.sql.Timestamp(System.currentTimeMillis()),
            new java.sql.Timestamp(System.currentTimeMillis()), operationId);
        return null;
      });

      installations.advancePlans();
      given().cookie("nanpanel_session", sessionCookie)
          .get("/api/v1/kubernetes/installations/" + planId)
          .then().statusCode(200)
          .body("state", equalTo("UNKNOWN"))
          .body("steps[0].state", equalTo("UNKNOWN"))
          .body("steps[1].state", equalTo("PENDING"))
          .body(not(containsString(token)));
      installations.advancePlans();
      assertEquals(1, dispatchCount.get(),
          "An unknown Server result must prevent all subsequent Agent installs");
      int agentOperations = store.transaction(connection -> {
        var rows = AuthStore.query(connection,
            "SELECT id FROM operation WHERE idempotency_key=?",
            "k3s-install:" + planId + ":1");
        return rows.size();
      });
      assertEquals(0, agentOperations);
      int tokenEnvelopeLength = store.transaction(connection -> {
        var rows = AuthStore.query(connection,
            "SELECT encrypted_token FROM k3s_install_plan WHERE id=?", UUID.fromString(planId));
        return ((byte[])rows.getFirst().get("encrypted_token")).length;
      });
      assertEquals(0, tokenEnvelopeLength);
    } finally {
      fakeGateway.stop(0);
    }
  }

  private UUID insertInstallCapableHost(String hostname) {
    UUID hostId = UUID.randomUUID();
    UUID agentId = UUID.randomUUID();
    store.transaction(connection -> {
      var now = new java.sql.Timestamp(System.currentTimeMillis());
      AuthStore.update(connection,
          "INSERT INTO host (id,hostname,capabilities,generation,observed_generation,status,created_at) "
              + "VALUES (?,?,?,1,1,'ONLINE',?)",
          hostId, hostname, "[\"k3s.install\"]", now);
      AuthStore.update(connection,
          "INSERT INTO agent (id,host_id,cert_identity,version,last_seen,cert_fingerprint,helper_status) "
              + "VALUES (?,?,?,'test',?,NULL,'AVAILABLE')",
          agentId, hostId, "test-" + agentId, now);
      return null;
    });
    return hostId;
  }
}
