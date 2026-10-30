package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(DatabaseInstanceTest.DatabaseProfile.class)
class DatabaseInstanceTest {
  public static class DatabaseProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:database_instances;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    }
  }

  @jakarta.inject.Inject AuthStore store;

  private record Session(String cookie, String token) {}

  private void reset() {
    long now = System.currentTimeMillis();
    store.transaction(c -> {
      var rows = AuthStore.query(c, "SELECT id FROM panel_user WHERE username='admin'");
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
      AuthStore.update(c, "DELETE FROM database_instance");
      return null;
    });
  }

  private Session login() {
    var pre = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var first = given().cookie("nanpanel_session", pre.cookie("nanpanel_session"))
        .header("X-CSRF-Token", pre.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"test-only-password\"}")
        .post("/api/v1/auth/login").then().statusCode(200).extract();
    given().cookie("nanpanel_session", first.cookie("nanpanel_session"))
        .header("X-CSRF-Token", first.path("csrfToken"))
        .contentType("application/json")
        .body("{\"currentPassword\":\"test-only-password\","
            + "\"newPassword\":\"database-instance-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    var next = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var second = given().cookie("nanpanel_session", next.cookie("nanpanel_session"))
        .header("X-CSRF-Token", next.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"database-instance-password-2026\"}")
        .post("/api/v1/auth/login").then().statusCode(200).extract();
    return new Session(second.cookie("nanpanel_session"), second.path("csrfToken"));
  }

  private UUID seedHost(String hostname) {
    UUID hostId = UUID.randomUUID();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO host (id,hostname,capabilities,status,created_at) "
              + "VALUES (?,?,'[\"container.deploy\"]','ONLINE',?)", hostId, hostname,
          Timestamp.from(Instant.now()));
      return null;
    });
    return hostId;
  }

  private String adminId() {
    var rows = store.transaction(c -> AuthStore.query(c,
        "SELECT id FROM panel_user WHERE username='admin'"));
    return String.valueOf(rows.getFirst().get("id"));
  }

  private UUID seedInstance(UUID hostId, String engine, int port) {
    UUID instanceId = UUID.randomUUID();
    long now = System.currentTimeMillis();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO database_instance (instance_id,host_id,engine,"
              + "engine_version,container_name,host_port,data_volume,database_name,state,"
              + "created_by,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,'APPLIED',?,?,?)",
          instanceId, hostId, engine, "16", "npdb-" + instanceId.toString().substring(0, 8),
          port, "npdb-" + instanceId.toString().substring(0, 8), "nanpanel", adminId(), now,
          now);
      return null;
    });
    return instanceId;
  }

  @Test
  void listingRequiresALoginAndReportsEnginesAndInstances() {
    reset();
    UUID hostId = seedHost("db-host-1");
    seedInstance(hostId, "postgres", 15432);
    given().get("/api/v1/database-instances").then().statusCode(401);
    Session session = login();
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/database-instances/engines")
        .then().statusCode(200).body("items", hasSize(3))
        .body("items[0].image", notNullValue());
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/database-instances")
        .then().statusCode(200).body("items", hasSize(1))
        .body("items[0].engine", equalTo("postgres"))
        .body("items[0].port", equalTo(15432))
        .body("items[0].state", equalTo("APPLIED"));
  }

  @Test
  void credentialsAreNeverReturnedOutsideCreation() {
    reset();
    Session session = login();
    UUID hostId = seedHost("db-host-2");
    UUID instanceId = seedInstance(hostId, "mysql", 13306);
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/database-instances/" + instanceId + "/connection")
        .then().statusCode(200)
        .body("engine", equalTo("mysql"))
        .body("host", equalTo("127.0.0.1"))
        .body("port", equalTo(13306))
        .body("username", equalTo("root"))
        .body("database", equalTo("nanpanel"))
        .body("passwordStored", equalTo(false))
        .body("password", equalTo(null));
  }

  @Test
  void creationValidatesThenFailsHonestlyWithoutAnAgent() {
    reset();
    Session session = login();
    UUID hostId = seedHost("db-host-3");

    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType("application/json")
        .body("{\"hostId\":\"" + hostId + "\",\"engine\":\"redis\"}")
        .post("/api/v1/database-instances").then().statusCode(422)
        .body("code", equalTo("VALIDATION_FAILED"));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType("application/json")
        .body("{\"hostId\":\"" + hostId + "\",\"engine\":\"mysql\",\"port\":53}")
        .post("/api/v1/database-instances").then().statusCode(422)
        .body("code", equalTo("VALIDATION_FAILED"));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType("application/json")
        .body("{\"hostId\":\"" + UUID.randomUUID() + "\",\"engine\":\"mysql\"}")
        .post("/api/v1/database-instances").then().statusCode(404)
        .body("code", equalTo("HOST_NOT_FOUND"));

    // No agent: the request fails and leaves no half-record behind.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType("application/json")
        .body("{\"hostId\":\"" + hostId + "\",\"engine\":\"mysql\"}")
        .post("/api/v1/database-instances").then().statusCode(503)
        .body("code", equalTo("AGENT_OFFLINE"));
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/database-instances")
        .then().statusCode(200).body("items", hasSize(0));
  }

  @Test
  void theVolumePathIsAskedOfTheHostInsteadOfGuessed() {
    reset();
    Session session = login();
    UUID hostId = seedHost("db-host-6");
    UUID instanceId = seedInstance(hostId, "mysql", 13306);
    // No agent: the request is accepted as a dispatch and fails at the transport, loudly.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .post("/api/v1/database-instances/" + instanceId + "/volume")
        .then().statusCode(503)
        .body("code", equalTo("AGENT_OFFLINE"));
    // Without an idempotency key the dispatch is refused before anything is recorded.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .post("/api/v1/database-instances/" + instanceId + "/volume")
        .then().statusCode(400);
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .post("/api/v1/database-instances/" + UUID.randomUUID() + "/volume")
        .then().statusCode(404)
        .body("code", equalTo("DATABASE_NOT_FOUND"));
  }

  @Test
  void aPortCannotBeSharedByTwoInstances() {
    reset();
    Session session = login();
    UUID hostId = seedHost("db-host-4");
    seedInstance(hostId, "mysql", 13306);
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType("application/json")
        .body("{\"hostId\":\"" + hostId + "\",\"engine\":\"mariadb\",\"port\":13306}")
        .post("/api/v1/database-instances").then().statusCode(409)
        .body("code", equalTo("PORT_IN_USE"));
  }

  @Test
  void deletingAnInstanceWithoutAnAgentKeepsTheRecord() {
    reset();
    Session session = login();
    UUID hostId = seedHost("db-host-5");
    UUID instanceId = seedInstance(hostId, "mysql", 13306);
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .delete("/api/v1/database-instances/" + instanceId).then().statusCode(503)
        .body("code", equalTo("AGENT_OFFLINE"));
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/database-instances")
        .then().statusCode(200).body("items", hasSize(1));
  }

  @Test
  void generatedPasswordsAreLongAndUrlSafe() {
    for (int index = 0; index < 200; index++) {
      String password = DatabaseInstanceResource.password();
      Assertions.assertEquals(24, password.length());
      Assertions.assertTrue(password.matches("[A-Za-z0-9]+"));
      Assertions.assertFalse(password.contains("l") || password.contains("I")
          || password.contains("1") || password.contains("O") || password.contains("0"),
          "look-alike characters make transcription errors likely");
    }
  }
}
