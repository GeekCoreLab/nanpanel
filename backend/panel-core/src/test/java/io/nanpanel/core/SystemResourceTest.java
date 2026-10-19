package io.nanpanel.core;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
@QuarkusTest
class SystemResourceTest {
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
    var installation = given().get("/api/v1/agent-installation")
        .then().statusCode(200).extract();
    String controllerFingerprint = installation.path("controllerFingerprint");
    assertTrue(controllerFingerprint.matches("[a-f0-9]{64}"));
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
    assertEquals(controllerFingerprint, enrollment.path("controllerFingerprint"));
    assertEquals(30444, (int)enrollment.path("gatewayPort"));
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
        java.util.List.of("host.inventory", "agent.heartbeat"));
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
        .body("capabilities", hasItems("host.inventory", "agent.heartbeat"));
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/agents/" + agentId)
        .then().statusCode(200)
        .body("status", equalTo("ONLINE"))
        .body("helperStatus", equalTo("UNKNOWN"));
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
