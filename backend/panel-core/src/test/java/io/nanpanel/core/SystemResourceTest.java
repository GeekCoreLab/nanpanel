package io.nanpanel.core;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
@QuarkusTest
class SystemResourceTest {
  @Inject AuthStore store;

  private static final class EchoShellCommand implements org.apache.sshd.server.command.Command {
    private java.io.InputStream input;
    private java.io.OutputStream output;
    private org.apache.sshd.server.ExitCallback exitCallback;
    private Thread runner;

    @Override public void setInputStream(java.io.InputStream value) { input = value; }
    @Override public void setOutputStream(java.io.OutputStream value) { output = value; }
    @Override public void setErrorStream(java.io.OutputStream value) { }
    @Override public void setExitCallback(org.apache.sshd.server.ExitCallback value) {
      exitCallback = value;
    }

    @Override
    public void start(org.apache.sshd.server.channel.ChannelSession channel,
        org.apache.sshd.server.Environment environment) {
      runner = Thread.ofVirtual().start(() -> {
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
            input, StandardCharsets.UTF_8))) {
          String line;
          while ((line = reader.readLine()) != null) {
            if (line.startsWith("echo ")) {
              output.write((line.substring(5) + "\r\n").getBytes(StandardCharsets.UTF_8));
              output.flush();
            }
          }
          exitCallback.onExit(0, "", false);
        } catch (java.io.IOException failure) {
          exitCallback.onExit(1, failure.getMessage(), false);
        }
      });
    }

    @Override
    public void destroy(org.apache.sshd.server.channel.ChannelSession channel) {
      if (runner != null) runner.interrupt();
    }
  }

  @Test
  void gatewayLeaseFencesCompetingAndExpiredOwners() {
    String firstOwner = "b0fb3e0a-f38b-4abc-83ea-1c4eb8887a10";
    String secondOwner = "931fb05f-c9d2-44e6-b24e-951c223f5c6c";
    store.transaction(c -> {
      AuthStore.update(c,
          "UPDATE agent_gateway_fencing SET owner_id=NULL,epoch=0,lease_until=? WHERE id=1",
          java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
      return null;
    });
    var initial = Map.of("ownerId", firstOwner, "clusterId", "test-cluster", "epoch", 0);
    Number firstEpoch = given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(initial)
        .post("/internal/agent-relay/lease/acquire")
        .then().statusCode(200).extract().path("epoch");
    Number sameOwnerEpoch = given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(initial)
        .post("/internal/agent-relay/lease/acquire")
        .then().statusCode(200).extract().path("epoch");
    assertEquals(firstEpoch.longValue(), sameOwnerEpoch.longValue());

    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.of("ownerId", secondOwner, "clusterId", "test-cluster", "epoch", 0))
        .post("/internal/agent-relay/lease/acquire")
        .then().statusCode(409).body("code", equalTo("RELAY_LEASE_HELD"));
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.of("ownerId", firstOwner, "clusterId", "test-cluster",
            "epoch", firstEpoch.intValue()))
        .post("/internal/agent-relay/lease/renew")
        .then().statusCode(200).body("epoch", equalTo(firstEpoch.intValue()));

    store.transaction(c -> {
      AuthStore.update(c, "UPDATE agent_gateway_fencing SET lease_until=? WHERE id=1",
          java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
      return null;
    });
    Number nextEpoch = given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.of("ownerId", secondOwner, "clusterId", "test-cluster", "epoch", 0))
        .post("/internal/agent-relay/lease/acquire")
        .then().statusCode(200).extract().path("epoch");
    assertEquals(firstEpoch.longValue() + 1, nextEpoch.longValue());
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.of("ownerId", firstOwner, "clusterId", "test-cluster",
            "epoch", firstEpoch.intValue()))
        .post("/internal/agent-relay/lease/renew")
        .then().statusCode(409).body("code", equalTo("RELAY_LEASE_LOST"));
  }

  @Test
  void relayStatusRequiresAllowlistedEndpointAndRejectsStaleEpochs() {
    String owner = "82791d95-b2d6-469e-a1d1-13550862d956";
    store.transaction(c -> {
      AuthStore.update(c,
          "UPDATE agent_gateway_fencing SET owner_id=NULL,epoch=1,lease_until=? WHERE id=1",
          java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
      AuthStore.update(c, "DELETE FROM agent_relay_status WHERE relay_id=?", "relay-a");
      return null;
    });
    Number epoch = given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.of("ownerId", owner, "clusterId", "test-cluster", "epoch", 0))
        .post("/internal/agent-relay/lease/acquire")
        .then().statusCode(200).extract().path("epoch");
    Map<String, Object> report = Map.of(
        "relayId", "relay-a", "clusterId", "test-cluster", "epoch", epoch.intValue(),
        "endpoint", "https://relay-a.example", "healthy", true);
    given().contentType("application/json").body(report)
        .post("/internal/agent-relay/status").then().statusCode(401);
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.of("relayId", "relay-a", "clusterId", "test-cluster", "epoch", epoch.intValue(),
            "endpoint", "https://untrusted.example", "healthy", true))
        .post("/internal/agent-relay/status").then().statusCode(400);
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.of("relayId", "relay-a", "clusterId", "other-cluster", "epoch", epoch.intValue(),
            "endpoint", "https://relay-a.example", "healthy", true))
        .post("/internal/agent-relay/status").then().statusCode(400);
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(report)
        .post("/internal/agent-relay/status").then().statusCode(204);
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(report)
        .post("/internal/agent-relay/status").then().statusCode(204);
    var burstCount = store.transaction(c -> AuthStore.query(c,
        "SELECT healthy_count FROM agent_relay_status WHERE relay_id=?", "relay-a").getFirst());
    assertEquals(1, ((Number)burstCount.get("healthy_count")).intValue());
    store.transaction(c -> {
      AuthStore.update(c, "UPDATE agent_relay_status SET last_counted_at=? WHERE relay_id=?",
          java.sql.Timestamp.from(Instant.now().minusSeconds(10)), "relay-a");
      return null;
    });
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(report)
        .post("/internal/agent-relay/status").then().statusCode(204);
    store.transaction(c -> {
      AuthStore.update(c, "UPDATE agent_relay_status SET last_counted_at=? WHERE relay_id=?",
          java.sql.Timestamp.from(Instant.now().minusSeconds(10)), "relay-a");
      return null;
    });
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(report)
        .post("/internal/agent-relay/status").then().statusCode(204);
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.of("relayId", "relay-a", "clusterId", "test-cluster", "epoch", epoch.intValue() - 1,
            "endpoint", "https://relay-b.example", "healthy", false))
        .post("/internal/agent-relay/status").then().statusCode(409)
        .body("code", equalTo("RELAY_LEASE_LOST"));
    var stored = store.transaction(c -> AuthStore.query(c,
        "SELECT cluster_id,target_name,epoch,healthy,healthy_count FROM agent_relay_status WHERE relay_id=?",
        "relay-a").getFirst());
    assertEquals("test-cluster", stored.get("cluster_id"));
    assertEquals("relay-a.example", stored.get("target_name"));
    assertEquals(epoch.longValue(), ((Number)stored.get("epoch")).longValue());
    assertEquals(true, stored.get("healthy"));
    assertEquals(3, ((Number)stored.get("healthy_count")).intValue());

    Map<String, Object> failure = Map.of(
        "relayId", "relay-a", "clusterId", "test-cluster", "epoch", epoch.intValue(),
        "endpoint", "https://relay-a.example", "healthy", false);
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(failure)
        .post("/internal/agent-relay/status").then().statusCode(204);
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(failure)
        .post("/internal/agent-relay/status").then().statusCode(204);
    var burstFailures = store.transaction(c -> AuthStore.query(c,
        "SELECT unhealthy_count FROM agent_relay_status WHERE relay_id=?", "relay-a").getFirst());
    assertEquals(1, ((Number)burstFailures.get("unhealthy_count")).intValue());
    for (int expected = 2; expected <= 3; expected++) {
      store.transaction(c -> {
        AuthStore.update(c, "UPDATE agent_relay_status SET last_counted_at=? WHERE relay_id=?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(10)), "relay-a");
        return null;
      });
      given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
          .contentType("application/json").body(failure)
          .post("/internal/agent-relay/status").then().statusCode(204);
      int failures = store.transaction(c -> ((Number)AuthStore.query(c,
          "SELECT unhealthy_count FROM agent_relay_status WHERE relay_id=?", "relay-a")
          .getFirst().get("unhealthy_count")).intValue());
      assertEquals(expected, failures);
    }
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(report)
        .post("/internal/agent-relay/status").then().statusCode(204);
    var recovered = store.transaction(c -> AuthStore.query(c,
        "SELECT healthy_count,unhealthy_count FROM agent_relay_status WHERE relay_id=?", "relay-a")
        .getFirst());
    assertEquals(1, ((Number)recovered.get("healthy_count")).intValue());
    assertEquals(0, ((Number)recovered.get("unhealthy_count")).intValue());
  }

  @Test
  void relayEndpointConfigurationIsAnExplicitHttpsOriginAllowlist() {
    assertEquals(java.util.List.of(), AgentEnrollmentResource.parseRelayEndpoints("disabled"));
    assertEquals(java.util.List.of("https://relay-a.example", "https://relay-b.example:9443"),
        AgentEnrollmentResource.parseRelayEndpoints(
            " HTTPS://Relay-A.Example:443/ , https://relay-b.example:9443 "));
    assertThrows(IllegalArgumentException.class,
        () -> AgentEnrollmentResource.parseRelayEndpoints("https://relay-a.example"));
    assertThrows(IllegalArgumentException.class,
        () -> AgentEnrollmentResource.parseRelayEndpoints(
            "https://relay-a.example,https://RELAY-A.example:443/"));
    assertThrows(IllegalArgumentException.class,
        () -> AgentEnrollmentResource.parseRelayEndpoints(
            "https://relay-a.example/path,https://relay-b.example"));
  }

  @Test
  void sessionLifecycle() throws Exception {
    given().get("/api/v1/system").then().statusCode(401);
    var pre = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    String cookie = pre.cookie("nanpanel_session"), csrf =
                                                        pre.path("csrfToken");
    given()
        .cookie("nanpanel_session", cookie)
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"test-only-password\"}")
        .post("/api/v1/auth/login")
        .then()
        .statusCode(403);
    var login =
        given()
            .cookie("nanpanel_session", cookie)
            .header("X-CSRF-Token", csrf)
            .contentType("application/json")
            .body(
                "{\"username\":\"admin\",\"password\":\"test-only-password\"}")
            .post("/api/v1/auth/login")
            .then()
            .statusCode(200)
            .body("user.mustChangePassword", equalTo(true))
            .extract();
    String active = login.cookie("nanpanel_session"),
           token = login.path("csrfToken");
    given()
        .cookie("nanpanel_session", cookie)
        .get("/api/v1/auth/me")
        .then()
        .statusCode(401);
    given()
        .cookie("nanpanel_session", active)
        .get("/api/v1/auth/me")
        .then()
        .statusCode(200);
    given()
        .cookie("nanpanel_session", active)
        .get("/api/v1/system")
        .then()
        .statusCode(403);
    given()
        .cookie("nanpanel_session", active)
        .header("Origin", "https://evil.example")
        .header("X-CSRF-Token", token)
        .post("/api/v1/auth/logout")
        .then()
        .statusCode(403);
    given()
        .cookie("nanpanel_session", active)
        .header("X-CSRF-Token", token)
        .contentType("application/json")
        .body("{\"currentPassword\":\"test-only-password\",\"newPassword\":" +
              "\"changed-secure-password-2026\"}")
        .put("/api/v1/auth/password")
        .then()
        .statusCode(204);
    given()
        .cookie("nanpanel_session", active)
        .get("/api/v1/auth/me")
        .then()
        .statusCode(401);
    var next =
        given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var restored =
        given()
            .cookie("nanpanel_session", next.cookie("nanpanel_session"))
            .header("X-CSRF-Token", next.path("csrfToken"))
            .contentType("application/json")
            .body("{\"username\":\"admin\",\"password\":\"changed-secure-" +
                  "password-2026\"}")
            .post("/api/v1/auth/login")
            .then()
            .statusCode(200)
            .body("user.mustChangePassword", equalTo(false))
            .extract();
    String restoredCookie = restored.cookie("nanpanel_session");
    given()
        .cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/system")
        .then()
        .statusCode(200);
    given().get("/api/v1/hosts/00000000-0000-0000-0000-000000000001/services")
        .then().statusCode(401);
    var installation = given().get("/api/v1/agent-installation")
        .then().statusCode(200).extract();
    String controllerFingerprint = installation.path("controllerFingerprint");
    assertTrue(controllerFingerprint.matches("[a-f0-9]{64}"));
    assertEquals(java.util.List.of("https://relay-a.example", "https://relay-b.example"),
        installation.path("relayEndpoints"));
    var enrollment = given()
        .cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("hostname", "panel-test-host"))
        .post("/api/v1/agent-enrollments")
        .then().statusCode(201).extract();
    String hostId = enrollment.path("hostId");
    String agentId = enrollment.path("agentId");
    String enrollmentToken = enrollment.path("token");
    verifyBastionTerminal(hostId, restoredCookie, restored.path("csrfToken"));
    assertEquals(controllerFingerprint, enrollment.path("controllerFingerprint"));
    assertEquals(30444, (int)enrollment.path("gatewayPort"));
    assertEquals(java.util.List.of("https://relay-a.example", "https://relay-b.example"),
        enrollment.path("relayEndpoints"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", "invalid")
        .contentType("application/json")
        .body(Map.of("hostname", "csrf-rejected-host"))
        .post("/api/v1/agent-enrollments")
        .then().statusCode(403);

    String mismatchedCsr = createCsr(UUID.randomUUID());
    given().header("Authorization", "Bearer " + enrollmentToken)
        .contentType("application/json")
        .body(Map.of("csrPem", mismatchedCsr))
        .post("/api/v1/agent-enrollments/redeem")
        .then().statusCode(400);

    String csr = createCsr(UUID.fromString(agentId));
    var redemption = given().header("Authorization", "Bearer " + enrollmentToken)
        .contentType("application/json")
        .body(Map.of("csrPem", csr))
        .post("/api/v1/agent-enrollments/redeem")
        .then().statusCode(200).extract();
    String certificate = redemption.path("certificatePem");
    String certificateFingerprint = AgentCertificateAuthority.fingerprint(certificate);
    given().header("Authorization", "Bearer " + enrollmentToken)
        .contentType("application/json")
        .body(Map.of("csrPem", csr))
        .post("/api/v1/agent-enrollments/redeem")
        .then().statusCode(401);

    var heartbeat = Map.of("agentId", agentId, "version", "0.1.0-test",
        "certificateFingerprint", certificateFingerprint,
        "hostname", "panel-test-host-real", "os", "Ubuntu 24.04",
        "arch", "x86_64", "capabilities",
        java.util.List.of("host.inventory", "agent.heartbeat", "agent.self_update", "systemd.read", "systemd.restart", "systemd.journal.read", "package.install", "file.write.protected", "container.list", "container.logs", "container.start", "container.stop", "container.deploy", "network.firewall.expose"),
        "helperStatus", "AVAILABLE",
        "systemdObservedAtEpochMs", System.currentTimeMillis(),
        "systemdServices", java.util.List.of(Map.of(
            "unitName", "ssh.service", "displayName", "OpenSSH server",
            "activeState", "ACTIVE", "subState", "running", "restartAllowed", true,
            "journalReadAllowed", true)));
    var forgedHeartbeat = new java.util.HashMap<>(heartbeat);
    forgedHeartbeat.put("certificateFingerprint", "0".repeat(64));
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(forgedHeartbeat)
        .post("/internal/agent-heartbeats")
        .then().statusCode(403);
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(heartbeat)
        .post("/internal/agent-heartbeats")
        .then().statusCode(204);
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/hosts/" + hostId)
        .then().statusCode(200)
        .body("hostname", equalTo("panel-test-host-real"))
        .body("status", equalTo("ONLINE"))
        .body("generation", equalTo(1))
        .body("capabilities", hasItems("host.inventory", "agent.heartbeat", "agent.self_update", "systemd.read"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("alias", "bad alias", "reason", "Reject malformed SSH alias"))
        .post("/api/v1/hosts/" + hostId + "/ssh/probe")
        .then().statusCode(422).body("code", equalTo("VALIDATION_FAILED"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", "ssh-restart-invalid-01")
        .contentType("application/json")
        .body(Map.of("unitName", "ssh.service;id", "reason", "Reject remote command text"))
        .post("/api/v1/hosts/" + hostId + "/ssh/systemd/restart")
        .then().statusCode(422).body("code", equalTo("VALIDATION_FAILED"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", "ssh-restart-default-deny")
        .contentType("application/json")
        .body(Map.of("unitName", "ssh.service", "reason", "Confirm empty SSH unit allowlist denies by default"))
        .post("/api/v1/hosts/" + hostId + "/ssh/systemd/restart")
        .then().statusCode(422).body("code", equalTo("SSH_ALIAS_NOT_ALLOWED"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("unitName", "ssh.service", "reason", "Check default-deny SSH status diagnostics"))
        .post("/api/v1/hosts/" + hostId + "/ssh/systemd/status")
        .then().statusCode(422).body("code", equalTo("SSH_ALIAS_NOT_ALLOWED"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("unitName", "ssh.service", "lines", 100,
            "reason", "Check default-deny SSH journal diagnostics"))
        .post("/api/v1/hosts/" + hostId + "/ssh/systemd/journal")
        .then().statusCode(422).body("code", equalTo("SSH_ALIAS_NOT_ALLOWED"));
    var deniedSshDiagnostics = store.transaction(c -> AuthStore.query(c,
        "SELECT action,outcome FROM audit_event WHERE resource_id=? AND " +
            "action IN ('SSH_SYSTEMD_STATUS_READ','SSH_SYSTEMD_JOURNAL_READ') ORDER BY action",
        hostId.toString()));
    assertEquals(2, deniedSshDiagnostics.size());
    assertTrue(deniedSshDiagnostics.stream().allMatch(row -> "DENIED".equals(row.get("outcome"))));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", "ssh-agent-install-default-deny")
        .contentType("application/json")
        .body(Map.of("enrollmentId", UUID.randomUUID().toString(), "agentId", agentId, "alias", "host-b",
            "token", enrollmentToken, "reason", "Confirm SSH bootstrap alias is denied by default"))
        .post("/api/v1/hosts/" + hostId + "/ssh/agent/install")
        .then().statusCode(422).body("code", equalTo("SSH_ALIAS_NOT_ALLOWED"));
    String packageOperationKey = "helper-package-key-01";
    var packageRequest = Map.of(
        "capability", "package.install", "target", "",
        "payload", Map.of("packages", java.util.List.of("curl")),
        "reason", "Install the allowlisted diagnostic utility", "timeoutSeconds", 60,
        "expectedGeneration", 1);
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", packageOperationKey)
        .contentType("application/json").body(packageRequest)
        .post("/api/v1/hosts/" + hostId + "/operations")
        .then().statusCode(503).body("code", equalTo("EXECUTION_OUTCOME_UNKNOWN"));
    var helperOperations = given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations?hostId=" + hostId)
        .then().statusCode(200).extract();
    String packageOperationId = helperOperations.path(
        "items.find { it.idempotencyKey == '" + packageOperationKey + "' }.id");
    var packageResultReport = Map.of("agentId", agentId, "operationId", packageOperationId,
        "idempotencyKey", packageOperationKey, "status", "SUCCEEDED",
        "detail", "{\"installed\":[\"curl\"]}");
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(packageResultReport)
        .post("/internal/agent-control-results").then().statusCode(204)
        .header("X-NanPanel-Result-Ack", equalTo("true"));
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations/" + packageOperationId)
        .then().statusCode(200).body("kind", equalTo("HOST_HELPER_OPERATION"))
        .body("capability", equalTo("package.install"))
        .body("status", equalTo("SUCCEEDED"))
        .body("result.output", equalTo("{\"installed\":[\"curl\"]}"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", packageOperationKey)
        .contentType("application/json").body(packageRequest)
        .post("/api/v1/hosts/" + hostId + "/operations")
        .then().statusCode(200).header("Idempotency-Replayed", equalTo("true"))
        .body("id", equalTo(packageOperationId));
    var invalidNetworkRequest = Map.of(
        "capability", "network.firewall.expose", "target", "ssh-test",
        "payload", Map.of("rollbackSeconds", 301),
        "reason", "Test bounded rollback validation", "timeoutSeconds", 60,
        "expectedGeneration", 1);
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", "helper-network-invalid-01")
        .contentType("application/json").body(invalidNetworkRequest)
        .post("/api/v1/hosts/" + hostId + "/operations")
        .then().statusCode(422).body("code", equalTo("VALIDATION_FAILED"));
    var networkRequest = Map.of(
        "capability", "network.firewall.expose", "target", "ssh-test",
        "payload", Map.of("rollbackSeconds", 90),
        "reason", "Test bounded network operation", "timeoutSeconds", 60,
        "expectedGeneration", 1);
    String networkOperationKey = "helper-network-valid-01";
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", networkOperationKey)
        .contentType("application/json").body(networkRequest)
        .post("/api/v1/hosts/" + hostId + "/operations")
        .then().statusCode(503).body("code", equalTo("EXECUTION_OUTCOME_UNKNOWN"));
    var afterNetworkDispatch = given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations?hostId=" + hostId)
        .then().statusCode(200).extract();
    String networkOperationId = afterNetworkDispatch.path(
        "items.find { it.idempotencyKey == '" + networkOperationKey + "' }.id");
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.of("agentId", agentId, "operationId", networkOperationId,
            "idempotencyKey", networkOperationKey, "status", "SUCCEEDED",
            "detail", "{\"verified\":true,\"automaticRollback\":true}"))
        .post("/internal/agent-control-results").then().statusCode(204)
        .header("X-NanPanel-Result-Ack", equalTo("true"));
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations/" + networkOperationId)
        .then().statusCode(200).body("capability", equalTo("network.firewall.expose"))
        .body("status", equalTo("SUCCEEDED"))
        .body("result.output", equalTo("{\"verified\":true,\"automaticRollback\":true}"));
    var staleGenerationRequest = new java.util.HashMap<>(packageRequest);
    staleGenerationRequest.put("expectedGeneration", 2);
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", "helper-stale-generation-01")
        .contentType("application/json").body(staleGenerationRequest)
        .post("/api/v1/hosts/" + hostId + "/operations")
        .then().statusCode(412).body("code", equalTo("PRECONDITION_FAILED"));
    var services = given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/hosts/" + hostId + "/services")
        .then().statusCode(200)
        .body("items.size()", equalTo(1))
        .body("items[0].unitName", equalTo("ssh.service"))
        .body("items[0].activeState", equalTo("ACTIVE"))
        .body("items[0].observationStale", equalTo(false))
        .extract();
    String serviceId = services.path("items[0].id");
    var serviceDetail = given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/hosts/" + hostId + "/services/" + serviceId)
        .then().statusCode(200)
        .body("generation", equalTo(1))
        .body("subState", equalTo("running"))
        .body("allowedActions", hasItem("RESTART"))
        .body("allowedActions", hasItem("READ_JOURNAL"))
        .extract();
    String serviceEtag = serviceDetail.header("ETag");
    assertTrue(serviceEtag.matches("\"[a-f0-9]{64}\""));
    String operationKey = "systemd-test-key-01";
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("If-Match", serviceEtag)
        .header("Idempotency-Key", operationKey)
        .contentType("application/json")
        .body(Map.of("reason", "P1 systemd operation test", "timeoutSeconds", 60))
        .post("/api/v1/hosts/" + hostId + "/services/" + serviceId + "/restart")
        .then().statusCode(503)
        .body("code", equalTo("EXECUTION_OUTCOME_UNKNOWN"));
    var operations = given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations?hostId=" + hostId)
        .then().statusCode(200)
        .extract();
    String operationId = operations.path(
        "items.find { it.idempotencyKey == '" + operationKey + "' }.id");
    assertEquals("UNKNOWN", operations.path(
        "items.find { it.idempotencyKey == '" + operationKey + "' }.status"));
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/admin/audit-events?outcome=UNKNOWN")
        .then().statusCode(200)
        .body("items.find { it.action == 'systemd.restart' }.outcome", equalTo("UNKNOWN"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("If-Match", serviceEtag)
        .header("Idempotency-Key", operationKey)
        .contentType("application/json")
        .body(Map.of("reason", "P1 systemd operation test", "timeoutSeconds", 60))
        .post("/api/v1/hosts/" + hostId + "/services/" + serviceId + "/restart")
        .then().statusCode(202)
        .header("Idempotency-Replayed", equalTo("true"))
        .body("id", equalTo(operationId))
        .body("status", equalTo("UNKNOWN"));
    var uncertainResultReport = Map.of("agentId", agentId, "operationId", operationId,
        "idempotencyKey", operationKey, "status", "FAILED",
        "detail", "UNCERTAIN: result was not available during reconnect");
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(uncertainResultReport)
        .post("/internal/agent-control-results").then().statusCode(204)
        .header("X-NanPanel-Result-Ack", equalTo("true"));
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(uncertainResultReport)
        .post("/internal/agent-control-results").then().statusCode(204);
    var resultReport = Map.of("agentId", agentId, "operationId", operationId,
        "idempotencyKey", operationKey, "status", "SUCCEEDED",
        "detail", "{\"activeState\":\"ACTIVE\",\"evidence\":\"systemd is active\"}");
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(resultReport)
        .post("/internal/agent-control-results").then().statusCode(204);
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(resultReport)
        .post("/internal/agent-control-results").then().statusCode(204);
    var conflictingResult = new java.util.HashMap<>(resultReport);
    conflictingResult.put("status", "FAILED");
    conflictingResult.put("detail", "DENIED: conflicting duplicate");
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(conflictingResult)
        .post("/internal/agent-control-results").then().statusCode(409);
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations/" + operationId)
        .then().statusCode(200).body("status", equalTo("SUCCEEDED"));
    String journalKey = "systemd-journal-key-01";
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("If-Match", serviceEtag)
        .header("Idempotency-Key", journalKey)
        .contentType("application/json")
        .body(Map.of("reason", "P1 bounded journal read test"))
        .post("/api/v1/hosts/" + hostId + "/services/" + serviceId + "/journal")
        .then().statusCode(503).body("code", equalTo("EXECUTION_OUTCOME_UNKNOWN"));
    var journalOperations = given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations?hostId=" + hostId)
        .then().statusCode(200).extract();
    String journalOperationId = journalOperations.path(
        "items.find { it.idempotencyKey == '" + journalKey + "' }.id");
    var journalResultReport = Map.of("agentId", agentId, "operationId", journalOperationId,
        "idempotencyKey", journalKey, "status", "SUCCEEDED",
        "detail", "Oct 19 00:00:01 host service[123]: started\nOct 19 00:00:02 host service[123]: ready");
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(journalResultReport)
        .post("/internal/agent-control-results").then().statusCode(204)
        .header("X-NanPanel-Result-Ack", equalTo("true"));
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(journalResultReport)
        .post("/internal/agent-control-results").then().statusCode(204)
        .header("X-NanPanel-Result-Ack", equalTo("true"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("If-Match", serviceEtag)
        .header("Idempotency-Key", journalKey)
        .contentType("application/json")
        .body(Map.of("reason", "P1 bounded journal read test"))
        .post("/api/v1/hosts/" + hostId + "/services/" + serviceId + "/journal")
        .then().statusCode(200).header("Idempotency-Replayed", equalTo("true"))
        .body("id", equalTo(journalOperationId));
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations/" + journalOperationId)
        .then().statusCode(200).body("kind", equalTo("SYSTEMD_JOURNAL_READ"))
        .body("status", equalTo("SUCCEEDED"))
        .body("result.journal", containsString("service[123]: ready"));
    var journalRevokedHeartbeat = new java.util.HashMap<>(heartbeat);
    journalRevokedHeartbeat.put("capabilities", java.util.List.of(
        "host.inventory", "agent.heartbeat", "agent.self_update", "systemd.read", "systemd.restart"));
    journalRevokedHeartbeat.put("systemdServices", java.util.List.of(Map.of(
        "unitName", "ssh.service", "displayName", "OpenSSH server",
        "activeState", "ACTIVE", "subState", "running", "restartAllowed", true,
        "journalReadAllowed", false)));
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(journalRevokedHeartbeat)
        .post("/internal/agent-heartbeats").then().statusCode(204);
    var journalDeniedService = given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/hosts/" + hostId + "/services/" + serviceId)
        .then().statusCode(200).body("allowedActions", not(hasItem("READ_JOURNAL")))
        .extract();
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("If-Match", journalDeniedService.header("ETag"))
        .header("Idempotency-Key", "journal-denied-key-01")
        .contentType("application/json")
        .body(Map.of("reason", "Reject journal read after allowlist removal"))
        .post("/api/v1/hosts/" + hostId + "/services/" + serviceId + "/journal")
        .then().statusCode(422).body("code", equalTo("OPERATION_UNSUPPORTED"));
    var staleInventoryHeartbeat = new java.util.HashMap<>(heartbeat);
    staleInventoryHeartbeat.put("systemdObservedAtEpochMs", System.currentTimeMillis() - 200_000);
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(staleInventoryHeartbeat)
        .post("/internal/agent-heartbeats").then().statusCode(204);
    var staleService = given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/hosts/" + hostId + "/services/" + serviceId)
        .then().statusCode(200)
        .body("observationStale", equalTo(true))
        .body("allowedActions", empty())
        .extract();
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("If-Match", staleService.header("ETag"))
        .header("Idempotency-Key", "stale-restart-key-01")
        .contentType("application/json")
        .body(Map.of("reason", "Reject stale observation", "timeoutSeconds", 60))
        .post("/api/v1/hosts/" + hostId + "/services/" + serviceId + "/restart")
        .then().statusCode(503).body("code", equalTo("SERVICE_OBSERVATION_STALE"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("If-Match", staleService.header("ETag"))
        .header("Idempotency-Key", "stale-journal-key-01")
        .contentType("application/json")
        .body(Map.of("reason", "Reject stale journal observation"))
        .post("/api/v1/hosts/" + hostId + "/services/" + serviceId + "/journal")
        .then().statusCode(503).body("code", equalTo("SERVICE_OBSERVATION_STALE"));
    var emptyInventoryHeartbeat = new java.util.HashMap<>(heartbeat);
    emptyInventoryHeartbeat.put("systemdObservedAtEpochMs", System.currentTimeMillis());
    emptyInventoryHeartbeat.put("systemdServices", java.util.List.of());
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(emptyInventoryHeartbeat)
        .post("/internal/agent-heartbeats").then().statusCode(204);
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/hosts/" + hostId + "/services")
        .then().statusCode(200).body("items.size()", equalTo(1))
        .body("items[0].activeState", equalTo("UNKNOWN"))
        .body("items[0].allowedActions", empty());
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/agents/" + agentId)
        .then().statusCode(200)
        .body("status", equalTo("ONLINE"))
        .body("helperStatus", equalTo("AVAILABLE"))
        .body("capabilities", hasItems("agent.self_update"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of())
        .post("/api/v1/agents/" + agentId + "/updates")
        .then().statusCode(503)
        .body("code", equalTo("GATEWAY_UNAVAILABLE"));
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/admin/audit-events?outcome=ACCEPTED&action=agent.update")
        .then().statusCode(200)
        .body("items[0].action", equalTo("agent.update"))
        .body("items[0].outcome", equalTo("ACCEPTED"));
    UUID uncertainInstall = UUID.randomUUID();
    Instant heartbeatEvidenceCreatedAt = Instant.now().minusSeconds(10);
    String adminId = store.transaction(c -> String.valueOf(AuthStore.query(c,
        "SELECT id FROM panel_user WHERE username='admin'").getFirst().get("id")));
    store.transaction(c -> {
      AuthStore.update(c,
          "INSERT INTO operation (id,idempotency_key,phase,status,error,created_at,updated_at," +
              "kind,host_id,agent_id,requested_by,reason,started_at,deadline_at,request_hash,target," +
              "capability,result_json,error_json) VALUES (?,?,'APPLY','UNKNOWN',NULL,?,?,?,?,?,?,?,?,?,?,?," +
              "'agent.install',NULL,?)",
          uncertainInstall, "ssh-agent-reconcile-" + UUID.randomUUID(),
          java.sql.Timestamp.from(heartbeatEvidenceCreatedAt), java.sql.Timestamp.from(Instant.now()),
          "SSH_AGENT_INSTALL", UUID.fromString(hostId), UUID.fromString(agentId), adminId,
          "Verify late mTLS heartbeat reconciliation", java.sql.Timestamp.from(heartbeatEvidenceCreatedAt),
          java.sql.Timestamp.from(heartbeatEvidenceCreatedAt.plusSeconds(360)), "a".repeat(64), agentId,
          "{\"code\":\"EXECUTION_OUTCOME_UNKNOWN\",\"message\":\"pending\",\"retryable\":false," +
              "\"executionOutcome\":\"UNCERTAIN\"}");
      return null;
    });
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations/" + uncertainInstall)
        .then().statusCode(200).body("kind", equalTo("SSH_AGENT_INSTALL"))
        .body("status", equalTo("SUCCEEDED"))
        .body("result.agentId", equalTo(agentId));
    var sessions = given()
                       .cookie("nanpanel_session", restoredCookie)
                       .get("/api/v1/auth/sessions")
                       .then()
                       .statusCode(200)
                       .body("items.size()", equalTo(1))
                       .extract();
    String sid = sessions.path("items[0].id");
    given()
        .cookie("nanpanel_session", sid)
        .get("/api/v1/auth/me")
        .then()
        .statusCode(401);
    given()
        .cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .delete("/api/v1/auth/sessions/" + sid)
        .then()
        .statusCode(204);
    given()
        .cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/auth/me")
        .then()
        .statusCode(401);
  }

  private void verifyBastionTerminal(String hostId, String cookie, String csrf) throws Exception {
    var generator = java.security.KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var serverKey = generator.generateKeyPair();
    org.apache.sshd.server.SshServer target = org.apache.sshd.server.SshServer
        .setUpDefaultServer();
    target.setHost("127.0.0.1");
    target.setPort(0);
    target.setKeyPairProvider(org.apache.sshd.common.keyprovider.KeyPairProvider.wrap(serverKey));
    target.setPasswordAuthenticator((username, password, session) ->
        "panel-test".equals(username) && "host-test-password-do-not-return".equals(password));
    target.setShellFactory(channel -> new EchoShellCommand());
    target.start();
    try {
      String password = "host-test-password-do-not-return";
      given().cookie("nanpanel_session", cookie).header("X-CSRF-Token", csrf)
          .contentType("application/json")
          .body(Map.of("address", "127.0.0.1", "port", target.getPort(),
              "username", "panel-test", "authMethod", "PASSWORD", "password", password))
          .put("/api/v1/hosts/" + hostId + "/ssh/config").then().statusCode(204);
      var saved = given().cookie("nanpanel_session", cookie)
          .get("/api/v1/hosts/" + hostId + "/ssh/config").then().statusCode(200).extract();
      assertEquals("127.0.0.1", saved.path("address"));
      assertEquals("PASSWORD", saved.path("authMethod"));
      assertTrue(Boolean.TRUE.equals(saved.path("credentialsConfigured")));
      assertFalse(saved.asString().contains(password));
      byte[] credentialCiphertext = (byte[])store.transaction(c -> AuthStore.query(c,
          "SELECT encrypted_credential FROM host_ssh_config WHERE host_id=?",
          UUID.fromString(hostId))).getFirst().get("encrypted_credential");
      assertFalse(new String(credentialCiphertext, StandardCharsets.ISO_8859_1)
          .contains(password));

      String fingerprint = given().cookie("nanpanel_session", cookie)
          .header("X-CSRF-Token", csrf).contentType("application/json")
          .body(Map.of("reason", "Verify bastion target host key"))
          .post("/api/v1/hosts/" + hostId + "/ssh/host-key/probe")
          .then().statusCode(200).extract().path("fingerprint");
      assertTrue(fingerprint.startsWith("SHA256:"));
      given().cookie("nanpanel_session", cookie).header("X-CSRF-Token", csrf)
          .contentType("application/json")
          .body(Map.of("fingerprint", fingerprint, "reason", "Verified from local test SSHD"))
          .post("/api/v1/hosts/" + hostId + "/ssh/host-key/trust")
          .then().statusCode(204);

      var created = given().cookie("nanpanel_session", cookie).header("X-CSRF-Token", csrf)
          .contentType("application/json")
          .body(Map.of("reason", "Verify audited interactive SSH shell", "columns", 100, "rows", 30))
          .post("/api/v1/hosts/" + hostId + "/ssh/sessions")
          .then().statusCode(201).extract();
      String sessionId = created.path("sessionId");
      String ticket = created.path("ticket");
      String marker = "nanpanel-bastion-e2e-" + UUID.randomUUID().toString().substring(0, 8);
      var frames = new java.util.concurrent.LinkedBlockingQueue<String>();
      var listener = new java.net.http.WebSocket.Listener() {
        @Override
        public void onOpen(java.net.http.WebSocket webSocket) {
          webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(
            java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
          frames.offer(data.toString());
          webSocket.request(1);
          return null;
        }

        @Override
        public void onError(java.net.http.WebSocket webSocket, Throwable error) {
          frames.offer("{\"type\":\"test-error\",\"message\":\"" +
              String.valueOf(error.getMessage()).replace("\"", "'") + "\"}");
        }
      };
      java.net.URI terminalUri = java.net.URI.create(
          "ws://localhost:8081/api/v1/bastion-sessions/" + sessionId + "/terminal");
      var socket = java.net.http.HttpClient.newHttpClient().newWebSocketBuilder()
          .header("Origin", "http://localhost:8081")
          .buildAsync(terminalUri, listener).get(10, TimeUnit.SECONDS);
      socket.sendText("{\"type\":\"auth\",\"ticket\":\"" + ticket +
          "\",\"columns\":100,\"rows\":30}", true).join();
      boolean ready = false;
      long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!ready && System.nanoTime() < readyDeadline) {
        String frame = frames.poll(1, TimeUnit.SECONDS);
        if (frame == null) continue;
        var event = new com.fasterxml.jackson.databind.ObjectMapper().readTree(frame);
        if ("test-error".equals(event.path("type").asText()))
          fail("WebSocket reported an error: " + frame);
        ready = "ready".equals(event.path("type").asText());
      }
      assertTrue(ready, "Bastion WebSocket did not authenticate the ticket");
      socket.sendText("{\"type\":\"input\",\"data\":\"echo " + marker + "\\r\"}", true).join();

      boolean observedMarker = false;
      StringBuilder remoteOutput = new StringBuilder();
      long outputDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (!observedMarker && System.nanoTime() < outputDeadline) {
        String frame = frames.poll(1, TimeUnit.SECONDS);
        if (frame == null) continue;
        var event = new com.fasterxml.jackson.databind.ObjectMapper().readTree(frame);
        if ("test-error".equals(event.path("type").asText()))
          fail("WebSocket reported an error: " + frame);
        if ("output".equals(event.path("type").asText())) {
          byte[] output = java.util.Base64.getDecoder().decode(event.path("data").asText());
          remoteOutput.append(new String(output, StandardCharsets.UTF_8));
          observedMarker = remoteOutput.indexOf(marker) >= 0;
        }
      }
      assertTrue(observedMarker, "Remote shell did not return the submitted command output: " +
          remoteOutput + "; frames=" + frames);
      socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "test complete")
          .get(5, TimeUnit.SECONDS);

      Map<String, Object> savedSession = Map.of();
      long closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < closeDeadline) {
        var listed = given().cookie("nanpanel_session", cookie)
            .get("/api/v1/bastion-sessions?hostId=" + hostId)
            .then().statusCode(200).extract();
        java.util.List<Map<String, Object>> sessions = listed.jsonPath().getList("items");
        if (!sessions.isEmpty() && "CLOSED".equals(sessions.getFirst().get("status"))) {
          savedSession = sessions.getFirst();
          break;
        }
        Thread.sleep(100);
      }
      assertEquals(sessionId, savedSession.get("id"));
      assertTrue(((Number)savedSession.get("bytesIn")).longValue() > 0);
      assertTrue(((Number)savedSession.get("bytesOut")).longValue() > 0);

      var commands = given().cookie("nanpanel_session", cookie)
          .get("/api/v1/bastion-sessions/" + sessionId + "/commands")
          .then().statusCode(200).extract().jsonPath().getList("items");
      assertTrue(commands.stream().anyMatch(item ->
          String.valueOf(((Map<?, ?>)item).get("command")).contains("echo " + marker)));
      var recording = given().cookie("nanpanel_session", cookie)
          .get("/api/v1/bastion-sessions/" + sessionId + "/recording")
          .then().statusCode(200).extract();
      assertEquals("nanpanel-terminal-v1", recording.path("format"));
      var chunks = recording.jsonPath().getList("items");
      assertTrue(chunks.stream().anyMatch(item -> "INPUT".equals(((Map<?, ?>)item).get("direction"))));
      assertTrue(chunks.stream().anyMatch(item -> "OUTPUT".equals(((Map<?, ?>)item).get("direction"))));
      String recordedOutput = chunks.stream()
          .filter(item -> "OUTPUT".equals(((Map<?, ?>)item).get("direction")))
          .map(item -> java.util.Base64.getDecoder().decode(
              String.valueOf(((Map<?, ?>)item).get("data"))))
          .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
          .collect(java.util.stream.Collectors.joining());
      assertTrue(recordedOutput.contains(marker));

      var storedCommand = store.transaction(c -> AuthStore.query(c,
          "SELECT encrypted_command FROM bastion_command WHERE session_id=?",
          UUID.fromString(sessionId))).getFirst().get("encrypted_command");
      assertFalse(new String((byte[])storedCommand, StandardCharsets.ISO_8859_1)
          .contains("echo " + marker));
      var views = store.transaction(c -> AuthStore.query(c,
          "SELECT action FROM audit_event WHERE resource_id=? AND action IN " +
              "('BASTION_COMMANDS_VIEWED','BASTION_RECORDING_VIEWED')", sessionId));
      assertEquals(2, views.size());
    } finally {
      target.stop(true);
    }
  }

  private String createCsr(UUID agentId) throws Exception {
    Path directory = Files.createTempDirectory("nanpanel-agent-csr-test-");
    try {
      Path key = directory.resolve("agent.key");
      Path csr = directory.resolve("agent.csr");
      Process process = new ProcessBuilder("openssl", "req", "-new", "-newkey",
          "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes", "-keyout",
          key.toString(), "-out", csr.toString(), "-subj", "/CN=" + agentId)
          .redirectErrorStream(true).start();
      byte[] output = process.getInputStream().readAllBytes();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "OpenSSL CSR generation timed out");
      assertEquals(0, process.exitValue(), new String(output, StandardCharsets.UTF_8));
      return Files.readString(csr, StandardCharsets.US_ASCII);
    } finally {
      try (var paths = Files.walk(directory)) {
        paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
          try { Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
        });
      }
    }
  }
}
