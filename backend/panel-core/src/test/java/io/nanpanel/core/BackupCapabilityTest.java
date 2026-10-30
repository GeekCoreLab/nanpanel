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

/** `backup.*` accepts only what the helper can honour; the control plane refuses the rest. */
@QuarkusTest
@TestProfile(BackupCapabilityTest.BackupProfile.class)
class BackupCapabilityTest {
  public static class BackupProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:backup_capability;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
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
            + "\"newPassword\":\"backup-capability-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    var next = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var restored = given().cookie("nanpanel_session", next.cookie("nanpanel_session"))
        .header("X-CSRF-Token", next.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"backup-capability-password-2026\"}")
        .post("/api/v1/auth/login").then().statusCode(200).extract();
    return new Session(restored.cookie("nanpanel_session"), restored.path("csrfToken"));
  }

  private UUID seedHost() {
    UUID hostId = UUID.randomUUID();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO host (id,hostname,capabilities,status,created_at) "
              + "VALUES (?,?,'[]','ONLINE',?)", hostId, "backup-host",
          Timestamp.from(Instant.now()));
      return null;
    });
    return hostId;
  }

  private io.restassured.response.Response submit(Session session, UUID hostId, String capability,
      String target, String payload) {
    String body = "{\"capability\":\"" + capability + "\",\"target\":\"" + target + "\","
        + "\"payload\":" + payload + ",\"reason\":\"测试\",\"timeoutSeconds\":30,"
        + "\"expectedGeneration\":1}";
    return given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType("application/json").body(body)
        .post("/api/v1/hosts/" + hostId + "/operations").then().extract().response();
  }

  @Test
  void backupRequestsAreValidatedBeforeDispatch() {
    Session session = login();
    UUID hostId = seedHost();

    var file = "site-20261030T091500Z.tar.gz";
    // create: needs a payload, an absolute source and a sane name.
    assertRejected(submit(session, hostId, "backup.create", "", "null"));
    assertRejected(submit(session, hostId, "backup.create", "",
        "{\"source\":\"relative\",\"name\":\"site\"}"));
    assertRejected(submit(session, hostId, "backup.create", "",
        "{\"source\":\"/etc/../root\",\"name\":\"site\"}"));
    assertRejected(submit(session, hostId, "backup.create", "",
        "{\"source\":\"/etc/nginx\",\"name\":\"Bad Name\"}"));
    assertRejected(submit(session, hostId, "backup.create", "",
        "{\"source\":\"/etc/nginx\",\"name\":\"site\",\"extra\":1}"));
    // create never takes a target.
    assertRejected(submit(session, hostId, "backup.create", "site",
        "{\"source\":\"/etc/nginx\",\"name\":\"site\"}"));
    // list takes neither target nor payload.
    assertRejected(submit(session, hostId, "backup.list", "site", "null"));
    // delete takes exactly a managed file name and no payload.
    assertRejected(submit(session, hostId, "backup.delete", "site.tar.gz", "null"));
    assertRejected(submit(session, hostId, "backup.delete", file,
        "{\"source\":\"/etc/nginx\",\"name\":\"site\"}"));
    assertRejected(submit(session, hostId, "backup.delete", "../" + file, "null"));

    // Valid shapes pass validation and stop only at the missing agent.
    for (var request : new String[][] {
        {"backup.list", "", "null"},
        {"backup.create", "", "{\"source\":\"/etc/nginx\",\"name\":\"site\"}"},
        {"backup.delete", file, "null"}}) {
      var accepted = submit(session, hostId, request[0], request[1], request[2]);
      Assertions.assertEquals(503, accepted.statusCode(), request[0] + " " + accepted.asString());
      Assertions.assertEquals("AGENT_OFFLINE", accepted.jsonPath().getString("code"));
    }
  }

  private static void assertRejected(io.restassured.response.Response response) {
    Assertions.assertEquals(422, response.statusCode(), response.asString());
  }
}
