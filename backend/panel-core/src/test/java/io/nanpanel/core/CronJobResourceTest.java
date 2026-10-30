package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(CronJobResourceTest.CronResourceProfile.class)
class CronJobResourceTest {
  public static class CronResourceProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:cron_resource;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    }
  }

  @jakarta.inject.Inject AuthStore store;

  /** Tests share one database, so each starts from the same administrator state. */
  private void resetAdmin() {
    long now = System.currentTimeMillis();
    var rows = store.transaction(c -> AuthStore.query(c,
        "SELECT id FROM panel_user WHERE username='admin'"));
    store.transaction(c -> {
      if (rows.isEmpty()) {
        AuthStore.update(c, "INSERT INTO panel_user (id,username,display_name,password_hash,"
                + "must_change,created_at,updated_at,status,version) "
                + "VALUES (?,?,?,?,TRUE,?,?,'ACTIVE',0)",
            UUID.randomUUID().toString(), "admin", "管理员",
            AuthStore.hash("test-only-password"), now, now);
      } else {
        AuthStore.update(c, "UPDATE panel_user SET password_hash=?, must_change=TRUE, "
            + "status='ACTIVE' WHERE username='admin'", AuthStore.hash("test-only-password"));
        AuthStore.update(c, "DELETE FROM web_session");
      }
      return null;
    });
  }

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
            + "\"newPassword\":\"cron-resource-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    var next = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var restored = given().cookie("nanpanel_session", next.cookie("nanpanel_session"))
        .header("X-CSRF-Token", next.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"cron-resource-password-2026\"}")
        .post("/api/v1/auth/login").then().statusCode(200).extract();
    return new Session(restored.cookie("nanpanel_session"), restored.path("csrfToken"));
  }

  private UUID seedHost(String hostname) {
    UUID hostId = UUID.randomUUID();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO host (id,hostname,capabilities,status,created_at) "
              + "VALUES (?,?,'[]','ONLINE',?)", hostId, hostname,
          Timestamp.from(Instant.now()));
      return null;
    });
    return hostId;
  }

  private String body(UUID hostId, String name, String schedule, String command) {
    return "{\"hostId\":\"" + hostId + "\",\"name\":\"" + name + "\",\"schedule\":\""
        + schedule + "\",\"command\":\"" + command + "\"}";
  }

  @Test
  void jobsAreValidatedStoredAppliedAndRemoved() {
    given().get("/api/v1/cron-jobs").then().statusCode(401);
    resetAdmin();
    Session session = login();
    UUID hostId = seedHost("cron-host-1");

    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token()).contentType("application/json")
        .body(body(hostId, "backup", "* * * *", "/bin/true"))
        .post("/api/v1/cron-jobs").then().statusCode(422)
        .body("code", equalTo("VALIDATION_FAILED"));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token()).contentType("application/json")
        .body(body(hostId, "Bad Name", "* * * * *", "/bin/true"))
        .post("/api/v1/cron-jobs").then().statusCode(422);
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token()).contentType("application/json")
        .body(body(UUID.randomUUID(), "backup", "* * * * *", "/bin/true"))
        .post("/api/v1/cron-jobs").then().statusCode(404)
        .body("code", equalTo("HOST_NOT_FOUND"));

    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token()).contentType("application/json")
        .body(body(hostId, "backup", "*/15 2 * * *", "/usr/local/bin/backup.sh"))
        .post("/api/v1/cron-jobs").then().statusCode(201)
        .body("id", equalTo("nanpanel-backup"))
        .body("state", equalTo("DRAFT"))
        .body("hostname", equalTo("cron-host-1"))
        .body("operationStatus", nullValue());
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token()).contentType("application/json")
        .body(body(hostId, "backup", "* * * * *", "/bin/true"))
        .post("/api/v1/cron-jobs").then().statusCode(409)
        .body("code", equalTo("CRON_JOB_EXISTS"));

    // Editing is allowed while the job is not serving, and is re-validated.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token()).contentType("application/json")
        .body("{\"schedule\":\"0 3 * * *\"}")
        .put("/api/v1/cron-jobs/" + hostId + "/nanpanel-backup").then().statusCode(200)
        .body("schedule", equalTo("0 3 * * *"))
        .body("command", equalTo("/usr/local/bin/backup.sh"));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token()).contentType("application/json")
        .body("{\"schedule\":\"@daily\"}")
        .put("/api/v1/cron-jobs/" + hostId + "/nanpanel-backup").then().statusCode(422);

    // Without an agent the dispatch is refused, and the failure is recorded honestly.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .post("/api/v1/cron-jobs/" + hostId + "/nanpanel-backup/apply").then().statusCode(503)
        .body("code", equalTo("AGENT_OFFLINE"));
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/cron-jobs")
        .then().statusCode(200).body("items", hasSize(1))
        .body("items[0].state", equalTo("FAILED"));

    // A failed job can be removed without a disable step.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .delete("/api/v1/cron-jobs/" + hostId + "/nanpanel-backup").then().statusCode(204);
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/cron-jobs")
        .then().statusCode(200).body("items", hasSize(0));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .delete("/api/v1/cron-jobs/" + hostId + "/nanpanel-backup").then().statusCode(404)
        .body("code", equalTo("CRON_JOB_NOT_FOUND"));
  }

  @Test
  void aServingJobCannotBeEditedOrDeletedAndAFailedOperationIsReported() {
    resetAdmin();
    Session session = login();
    UUID hostId = seedHost("cron-host-2");
    long now = System.currentTimeMillis();
    UUID operationId = UUID.randomUUID();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO operation (id,idempotency_key,phase,status,kind,host_id,"
          + "capability,target,created_at,updated_at) "
          + "VALUES (?,?,?,?,?,?,?,?,?,?)", operationId, "cron-op-" + operationId, "APPLY",
          "FAILED", "HOST_HELPER_OPERATION", hostId, "cron.write", "nanpanel-legacy",
          Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
      AuthStore.update(c, "INSERT INTO cron_job (host_id,job_id,schedule,command,state,"
              + "operation_id,created_by,created_at,updated_at) "
              + "VALUES (?,?,?,?,'APPLIED',?,?,?,?)", hostId, "nanpanel-legacy", "0 1 * * *",
          "/bin/true", operationId, adminId(), now, now);
      return null;
    });

    // Stored as APPLIED but the host reported failure: report sorry honestly and unblock editing.
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/cron-jobs")
        .then().statusCode(200)
        .body("items[0].state", equalTo("FAILED"))
        .body("items[0].operationStatus", equalTo("FAILED"));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token()).contentType("application/json")
        .body("{\"schedule\":\"0 2 * * *\"}")
        .put("/api/v1/cron-jobs/" + hostId + "/nanpanel-legacy").then().statusCode(200);

    // An uncertain outcome blocks edit and delete until it is disabled.
    store.transaction(c -> {
      AuthStore.update(c, "UPDATE operation SET status='UNKNOWN' WHERE id=?", operationId);
      return null;
    });
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/cron-jobs")
        .then().statusCode(200).body("items[0].state", equalTo("UNKNOWN"));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .delete("/api/v1/cron-jobs/" + hostId + "/nanpanel-legacy").then().statusCode(409)
        .body("code", equalTo("CRON_JOB_APPLIED"));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token()).contentType("application/json")
        .body("{\"schedule\":\"0 2 * * *\"}")
        .put("/api/v1/cron-jobs/" + hostId + "/nanpanel-legacy").then().statusCode(409);
  }

  private String adminId() {
    var rows = store.transaction(c -> AuthStore.query(c,
        "SELECT id FROM panel_user WHERE username='admin'"));
    return String.valueOf(rows.getFirst().get("id"));
  }
}
