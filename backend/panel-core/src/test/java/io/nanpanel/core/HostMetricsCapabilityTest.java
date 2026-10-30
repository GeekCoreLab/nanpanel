package io.nanpanel.core;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** `metrics.read` takes no target and no payload; anything else is refused at the control plane. */
@QuarkusTest
@TestProfile(HostMetricsCapabilityTest.MetricsProfile.class)
class HostMetricsCapabilityTest {
  public static class MetricsProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:host_metrics_capability;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    }
  }

  @jakarta.inject.Inject AuthStore store;

  private record Session(String cookie, String token) {}

  private Session login() {
    var pre = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var login = given().cookie("nanpanel_session", pre.cookie("nanpanel_session"))
        .header("X-CSRF-Token", pre.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"test-only-password\"}")
        .post("/api/v1/auth/login").then().statusCode(200).extract();
    given().cookie("nanpanel_session", login.cookie("nanpanel_session"))
        .header("X-CSRF-Token", login.path("csrfToken"))
        .contentType("application/json")
        .body("{\"currentPassword\":\"test-only-password\","
            + "\"newPassword\":\"metrics-capability-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    var next = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var restored = given().cookie("nanpanel_session", next.cookie("nanpanel_session"))
        .header("X-CSRF-Token", next.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"metrics-capability-password-2026\"}")
        .post("/api/v1/auth/login").then().statusCode(200).extract();
    return new Session(restored.cookie("nanpanel_session"), restored.path("csrfToken"));
  }

  private UUID seedHost() {
    UUID hostId = UUID.randomUUID();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO host (id,hostname,capabilities,status,created_at) "
              + "VALUES (?,?,'[]','ONLINE',?)", hostId, "metrics-host",
          Timestamp.from(Instant.now()));
      return null;
    });
    return hostId;
  }

  private io.restassured.response.Response submit(Session session, UUID hostId, String target,
      String payload) {
    String body = "{\"capability\":\"metrics.read\",\"target\":\"" + target + "\","
        + "\"payload\":" + payload + ",\"reason\":\"测试\",\"timeoutSeconds\":30,"
        + "\"expectedGeneration\":1}";
    return given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType("application/json").body(body)
        .post("/api/v1/hosts/" + hostId + "/operations").then().extract().response();
  }

  @Test
  void metricsReadAcceptsNoArgumentsAndRejectsSome() {
    Session session = login();
    UUID hostId = seedHost();

    Assertions.assertEquals(422, submit(session, hostId, "eth0", "null").statusCode());
    Assertions.assertEquals(422, submit(session, hostId, "", "{\"detail\":true}").statusCode());

    // Valid shape: only the missing agent stops it.
    var accepted = submit(session, hostId, "", "null");
    Assertions.assertEquals(503, accepted.statusCode());
    Assertions.assertEquals("AGENT_OFFLINE", accepted.jsonPath().getString("code"));
  }
}
