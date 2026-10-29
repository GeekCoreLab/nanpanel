package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(AlertResourceTest.AlertProfile.class)
class AlertResourceTest {
  public static class AlertProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:alert_rules;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
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
        .post("/api/v1/auth/login").then().statusCode(200)
        .body("user.mustChangePassword", equalTo(true)).extract();
    given().cookie("nanpanel_session", login.cookie("nanpanel_session"))
        .header("X-CSRF-Token", login.path("csrfToken"))
        .contentType("application/json")
        .body("{\"currentPassword\":\"test-only-password\","
            + "\"newPassword\":\"alert-secure-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    var next = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var restored = given().cookie("nanpanel_session", next.cookie("nanpanel_session"))
        .header("X-CSRF-Token", next.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"alert-secure-password-2026\"}")
        .post("/api/v1/auth/login").then().statusCode(200)
        .body("user.mustChangePassword", equalTo(false)).extract();
    return new Session(restored.cookie("nanpanel_session"), restored.path("csrfToken"));
  }

  @Test
  void rulesAreValidatedPersistedAndEvaluatedAgainstLiveObservations() {
    given().get("/api/v1/alert-rules").then().statusCode(401);
    given().get("/api/v1/alerts").then().statusCode(401);
    Session session = login();

    UUID hostId = UUID.randomUUID();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO host (id,hostname,capabilities,status,created_at) "
          + "VALUES (?,?,'[]','OFFLINE',?)", hostId, "alert-host-1",
          java.sql.Timestamp.from(Instant.now()));
      AuthStore.update(c, "INSERT INTO agent (id,host_id,cert_identity,last_seen,helper_status) "
          + "VALUES (?,?,?,?,'UNAVAILABLE')", UUID.randomUUID(), hostId, "alert-agent-1",
          java.sql.Timestamp.from(Instant.now()));
      AuthStore.update(c, "INSERT INTO system_service_observation "
          + "(id,host_id,unit_name,display_name,active_state,sub_state,observed_at) "
          + "VALUES (?,?,?,?,?,?,?)", UUID.randomUUID(), hostId, "sshd.service", "OpenSSH",
          "failed", "dead", java.sql.Timestamp.from(Instant.now()));
      return null;
    });

    String hostRule = "{\"name\":\"主机离线\",\"condition\":\"HOST_NOT_ONLINE\","
        + "\"severity\":\"CRITICAL\"}";
    var created = given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json").body(hostRule)
        .post("/api/v1/alert-rules").then().statusCode(201)
        .body("condition", equalTo("HOST_NOT_ONLINE"))
        .body("hostId", nullValue())
        .body("enabled", equalTo(true))
        .extract();
    String ruleId = created.path("id");

    // Duplicate condition/host/unit is rejected.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json").body(hostRule)
        .post("/api/v1/alert-rules").then().statusCode(409)
        .body("code", equalTo("ALERT_RULE_DUPLICATE"));

    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json")
        .body("{\"name\":\"helper\",\"condition\":\"HELPER_UNAVAILABLE\",\"severity\":\"WARNING\"}")
        .post("/api/v1/alert-rules").then().statusCode(201);

    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json")
        .body("{\"name\":\"ssh\",\"condition\":\"SERVICE_INACTIVE\","
            + "\"unitName\":\"sshd.service\",\"hostId\":\"" + hostId + "\","
            + "\"severity\":\"CRITICAL\"}")
        .post("/api/v1/alert-rules").then().statusCode(201)
        .body("hostname", equalTo("alert-host-1"))
        .body("unitName", equalTo("sshd.service"));

    // SERVICE_INACTIVE requires a unit; other conditions must not carry one.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json")
        .body("{\"name\":\"no-unit\",\"condition\":\"SERVICE_INACTIVE\",\"severity\":\"WARNING\"}")
        .post("/api/v1/alert-rules").then().statusCode(422);
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json")
        .body("{\"name\":\"stray-unit\",\"condition\":\"HOST_NOT_ONLINE\","
            + "\"unitName\":\"sshd.service\",\"severity\":\"WARNING\"}")
        .post("/api/v1/alert-rules").then().statusCode(422);
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json")
        .body("{\"name\":\"bad-severity\",\"condition\":\"HOST_NOT_ONLINE\",\"severity\":\"LOW\"}")
        .post("/api/v1/alert-rules").then().statusCode(422);

    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/alert-rules").then().statusCode(200)
        .body("items", hasSize(3));

    // Live evaluation: offline host, unavailable helper and failed unit all fire.
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/alerts").then().statusCode(200)
        .body("evaluatedRules", equalTo(3))
        .body("alerts", hasSize(3))
        .body("alerts[0].severity", equalTo("CRITICAL"))
        .body("alerts.find { it.condition == 'HOST_NOT_ONLINE' }.detail", equalTo("status=OFFLINE"))
        .body("alerts.find { it.condition == 'HELPER_UNAVAILABLE' }.detail",
            equalTo("helper=UNAVAILABLE"))
        .body("alerts.find { it.condition == 'SERVICE_INACTIVE' }.detail",
            equalTo("sshd.service · failed"));

    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .delete("/api/v1/alert-rules/" + ruleId).then().statusCode(204);
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/alerts").then().statusCode(200)
        .body("evaluatedRules", equalTo(2))
        .body("alerts", hasSize(2));

    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .delete("/api/v1/alert-rules/" + ruleId).then().statusCode(404)
        .body("code", equalTo("ALERT_RULE_NOT_FOUND"));
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/alert-rules").then().statusCode(200)
        .body("items", hasSize(2));
  }
}
