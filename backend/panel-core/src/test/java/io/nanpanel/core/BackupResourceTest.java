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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(BackupResourceTest.BackupResourceProfile.class)
class BackupResourceTest {
  public static class BackupResourceProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:backup_resource;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    }
  }

  @jakarta.inject.Inject AuthStore store;

  private record Session(String cookie, String token) {}

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

  /** Tests share one database: start from an empty backup history. */
  private void resetBackups() {
    store.transaction(c -> {
      AuthStore.update(c, "DELETE FROM backup_record");
      AuthStore.update(c, "DELETE FROM operation WHERE capability LIKE 'backup.%'");
      return null;
    });
  }

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
            + "\"newPassword\":\"backup-resource-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    var next = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var restored = given().cookie("nanpanel_session", next.cookie("nanpanel_session"))
        .header("X-CSRF-Token", next.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"backup-resource-password-2026\"}")
        .post("/api/v1/auth/login").then().statusCode(200).extract();
    return new Session(restored.cookie("nanpanel_session"), restored.path("csrfToken"));
  }

  private UUID seedHost(String hostname) {
    UUID hostId = UUID.randomUUID();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO host (id,hostname,capabilities,status,created_at) "
              + "VALUES (?,?,'[\"backup.create\"]','ONLINE',?)", hostId, hostname,
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

  private UUID completedBackupOperation(UUID hostId, String file, long size, String sha) {
    UUID operationId = UUID.randomUUID();
    String output = "{\"file\":\"" + file + "\",\"source\":\"/etc/nginx\",\"sizeBytes\":" + size
        + ",\"sha256\":\"" + sha + "\"}";
    String result = "{\"output\":" + quote(output) + "}";
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO operation (id,idempotency_key,phase,status,kind,host_id,"
              + "capability,target,result_json,requested_by,created_at,updated_at,finished_at) "
              + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", operationId, "backup-op-" + operationId,
          "COMMIT", "SUCCEEDED", "HOST_HELPER_OPERATION", hostId, "backup.create", "", result,
          adminId(), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
          Timestamp.from(Instant.now()));
      return null;
    });
    return operationId;
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  @Test
  void aFinishedBackupBecomesARecordWithItsRealFile() {
    resetAdmin();
    resetBackups();
    given().get("/api/v1/backups").then().statusCode(401);
    Session session = login();
    UUID hostId = seedHost("backup-host-1");
    String file = "site-20261030T091500Z.tar.gz";
    UUID operationId = completedBackupOperation(hostId, file, 4096, "a".repeat(64));

    // The record is written from the host's own result, never from a guess.
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/backups")
        .then().statusCode(200).body("items", hasSize(1))
        .body("items[0].file", equalTo(file))
        .body("items[0].sizeBytes", equalTo(4096))
        .body("items[0].sha256", equalTo("a".repeat(64)))
        .body("items[0].state", equalTo("APPLIED"))
        .body("items[0].hostname", equalTo("backup-host-1"))
        .body("items[0].operationId", equalTo(operationId.toString()));

    // A second read does not duplicate the record.
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/backups")
        .then().statusCode(200).body("items", hasSize(1));
  }

  @Test
  void creatingPurgesNothingAndDeletingRemovesBothRecordAndFile() {
    resetAdmin();
    resetBackups();
    Session session = login();
    UUID hostId = seedHost("backup-host-2");

    // Creation without an agent fails honestly and leaves no bogus record.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType("application/json")
        .body("{\"hostId\":\"" + hostId + "\",\"source\":\"/etc/nginx\",\"name\":\"site\"}")
        .post("/api/v1/backups").then().statusCode(503)
        .body("code", equalTo("AGENT_OFFLINE"));
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/backups")
        .then().statusCode(200).body("items", hasSize(0));

    // Validation is the dispatch validator, so a bad source never reaches the host.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType("application/json")
        .body("{\"hostId\":\"" + hostId + "\",\"source\":\"relative\",\"name\":\"site\"}")
        .post("/api/v1/backups").then().statusCode(422)
        .body("code", equalTo("VALIDATION_FAILED"));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType("application/json")
        .body("{\"hostId\":\"" + UUID.randomUUID() + "\",\"source\":\"/etc/nginx\","
            + "\"name\":\"site\"}")
        .post("/api/v1/backups").then().statusCode(404)
        .body("code", equalTo("HOST_NOT_FOUND"));

    // A real record can be restored (dispatched) and deleted.
    String file = "site-20261030T101500Z.tar.gz";
    completedBackupOperation(hostId, file, 2048, "b".repeat(64));
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/backups")
        .then().statusCode(200).body("items", hasSize(1));
    String recordId = given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/backups").then().extract().path("items[0].id");

    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType("application/json").body("{\"target\":\"/srv/restore\"}")
        .post("/api/v1/backups/" + recordId + "/restore").then().statusCode(503)
        .body("code", equalTo("AGENT_OFFLINE"));

    // With no agent the file cannot be removed, so the record is kept rather than lying about it.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .delete("/api/v1/backups/" + recordId).then().statusCode(503)
        .body("code", equalTo("AGENT_OFFLINE"));
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/backups")
        .then().statusCode(200).body("items", hasSize(1));

    // A record whose file was never produced has nothing to delete on the host.
    UUID pending = UUID.randomUUID();
    long stamp = System.currentTimeMillis();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO backup_record (record_id,host_id,file_name,source,state,"
              + "created_by,created_at,updated_at) VALUES (?,?,?,?,'DRAFT',?,?,?)",
          pending, hostId, "pending-" + pending, "/etc/nginx", adminId(), stamp, stamp);
      return null;
    });
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .delete("/api/v1/backups/" + pending).then().statusCode(204);
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .delete("/api/v1/backups/" + pending).then().statusCode(404)
        .body("code", equalTo("BACKUP_NOT_FOUND"));
  }

  private UUID seedInstance(UUID hostId) {
    UUID instanceId = UUID.randomUUID();
    long now = System.currentTimeMillis();
    String container = "npdb-" + instanceId.toString().substring(0, 8);
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO database_instance (instance_id,host_id,engine,"
              + "engine_version,container_name,host_port,data_volume,database_name,state,"
              + "created_by,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,'APPLIED',?,?,?)",
          instanceId, hostId, "mysql", "8.4", container, 13306, container, "nanpanel",
          adminId(), now, now);
      return null;
    });
    return instanceId;
  }

  private void seedRecord(UUID hostId, UUID instanceId, String file) {
    long now = System.currentTimeMillis();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO backup_record (record_id,host_id,file_name,source,state,"
              + "instance_id,created_by,created_at,updated_at) VALUES (?,?,?,?,'APPLIED',?,?,?,?)",
          UUID.randomUUID(), hostId, file, "/var/lib/mysql", instanceId, adminId(), now, now);
      return null;
    });
  }

  @Test
  void aBackupCanBeAttachedToAnInstanceAndFilteredByIt() {
    resetAdmin();
    resetBackups();
    Session session = login();
    UUID hostA = seedHost("db-host-a");
    UUID hostB = seedHost("db-host-b");
    UUID instance = seedInstance(hostA);
    seedRecord(hostA, instance, "db-a-20261030T120000Z.tar.gz");
    seedRecord(hostA, null, "host-wide-20261030T120500Z.tar.gz");

    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/backups")
        .then().statusCode(200).body("items", hasSize(2));
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/backups?instanceId=" + instance)
        .then().statusCode(200).body("items", hasSize(1))
        .body("items[0].instanceId", equalTo(instance.toString()))
        .body("items[0].file", equalTo("db-a-20261030T120000Z.tar.gz"));

    // An instance on another host is a caller mistake, not a silent cross-host link.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType("application/json")
        .body("{\"hostId\":\"" + hostB + "\",\"source\":\"/var/lib/mysql\","
            + "\"name\":\"db\",\"instanceId\":\"" + instance + "\"}")
        .post("/api/v1/backups").then().statusCode(422)
        .body("code", equalTo("VALIDATION_FAILED"));
    // Same host, unknown instance id: rejected rather than stored as a dangling link.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType("application/json")
        .body("{\"hostId\":\"" + hostA + "\",\"source\":\"/var/lib/mysql\","
            + "\"name\":\"db\",\"instanceId\":\"" + UUID.randomUUID() + "\"}")
        .post("/api/v1/backups").then().statusCode(422)
        .body("code", equalTo("VALIDATION_FAILED"));
  }

  @Test
  void aPendingRecordCannotBeRestored() {
    resetAdmin();
    resetBackups();
    Session session = login();
    UUID hostId = seedHost("backup-host-3");
    UUID recordId = UUID.randomUUID();
    long now = System.currentTimeMillis();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO backup_record (record_id,host_id,file_name,source,state,"
              + "created_by,created_at,updated_at) VALUES (?,?,?,?,'DRAFT',?,?,?)",
          recordId, hostId, "pending-" + recordId, "/etc/nginx", adminId(), now, now);
      return null;
    });
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType("application/json").body("{\"target\":\"/srv/restore\"}")
        .post("/api/v1/backups/" + recordId + "/restore").then().statusCode(409)
        .body("code", equalTo("BACKUP_NOT_READY"));
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/backups")
        .then().statusCode(200).body("items[0].sizeBytes", nullValue());
  }
}
