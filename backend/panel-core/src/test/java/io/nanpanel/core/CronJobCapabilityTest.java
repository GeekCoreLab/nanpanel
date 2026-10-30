package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The cron capability is validated at the control plane before it is ever dispatched, so a
 * bad schedule, a multi-line command or a foreign user is refused here — not on the host.
 */
@QuarkusTest
@TestProfile(CronJobCapabilityTest.CronProfile.class)
class CronJobCapabilityTest {
  public static class CronProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:cron_capability;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
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
            + "\"newPassword\":\"cron-capability-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    var next = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var restored = given().cookie("nanpanel_session", next.cookie("nanpanel_session"))
        .header("X-CSRF-Token", next.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"cron-capability-password-2026\"}")
        .post("/api/v1/auth/login").then().statusCode(200).extract();
    return new Session(restored.cookie("nanpanel_session"), restored.path("csrfToken"));
  }

  private UUID seedHost() {
    UUID hostId = UUID.randomUUID();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO host (id,hostname,capabilities,status,created_at) "
              + "VALUES (?,?,'[]','ONLINE',?)", hostId, "cron-host",
          Timestamp.from(Instant.now()));
      return null;
    });
    return hostId;
  }

  private io.restassured.response.Response submit(Session session, UUID hostId, String capability,
      String target, String payload) {
    String body = "{\"capability\":\"" + capability + "\",\"target\":\"" + target + "\","
        + "\"payload\":" + payload + ",\"reason\":\"测试\",\"timeoutSeconds\":60,"
        + "\"expectedGeneration\":1}";
    return given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType("application/json").body(body)
        .post("/api/v1/hosts/" + hostId + "/operations").then().extract()
        .response();
  }

  private static String job(String schedule, String command) {
    return "{\"schedule\":\"" + schedule + "\",\"command\":\"" + command + "\"}";
  }

  @Test
  void badCronRequestsAreRejectedBeforeDispatch() {
    Session session = login();
    UUID hostId = seedHost();

    // Four fields, a shell injection in the schedule, a multi-line command, a non-root user
    // and unknown fields are all refused by the control plane.
    assertError(submit(session, hostId, "cron.write", "nanpanel-backup",
        job("* * * *", "/bin/true")));
    assertError(submit(session, hostId, "cron.write", "nanpanel-backup",
        job("* * * * *; rm -rf /", "/bin/true")));
    assertError(submit(session, hostId, "cron.write", "nanpanel-backup",
        "{\"schedule\":\"* * * * *\",\"command\":\"/bin/true\\n/bin/false\"}"));
    assertError(submit(session, hostId, "cron.write", "nanpanel-backup",
        "{\"schedule\":\"* * * * *\",\"command\":\"/bin/true\",\"user\":\"nobody\"}"));
    assertError(submit(session, hostId, "cron.write", "nanpanel-backup",
        "{\"schedule\":\"* * * * *\",\"command\":\"/bin/true\",\"extra\":1}"));
    // Unmanaged names are refused for every cron capability.
    assertError(submit(session, hostId, "cron.write", "backup", job("* * * * *", "/bin/true")));
    assertError(submit(session, hostId, "cron.remove", "backup", "null"));
    assertError(submit(session, hostId, "cron.remove", "nanpanel-ok", job("* * * * *", "/bin/true")));
    assertError(submit(session, hostId, "cron.list", "nanpanel-ok", "null"));

    // A valid request passes validation and only then fails on the missing agent.
    var accepted = submit(session, hostId, "cron.write", "nanpanel-backup",
        job("*/15 2 * * *", "/usr/local/bin/backup.sh"));
    org.junit.jupiter.api.Assertions.assertEquals(503, accepted.statusCode());
    org.junit.jupiter.api.Assertions.assertEquals("AGENT_OFFLINE",
        accepted.jsonPath().getString("code"));
  }

  private static void assertError(io.restassured.response.Response response) {
    org.junit.jupiter.api.Assertions.assertEquals(422, response.statusCode(), response.asString());
    org.junit.jupiter.api.Assertions.assertEquals("VALIDATION_FAILED",
        response.jsonPath().getString("code"));
  }
}
