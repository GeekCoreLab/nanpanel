package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A panel that cannot move its configuration between machines forces its operator to retype
 * everything. These tests exercise the real export document: checksum, version gate, and the
 * skip/overwrite strategies.
 */
@QuarkusTest
@TestProfile(ConfigPortabilityTest.PortabilityProfile.class)
class ConfigPortabilityTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  public static class PortabilityProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:config_portability;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
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
                + "status='ACTIVE' WHERE username='admin'",
            AuthStore.hash("test-only-password"));
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
        .post("/api/v1/auth/login").then().statusCode(200)
        .body("user.mustChangePassword", equalTo(true)).extract();
    given().cookie("nanpanel_session", login.cookie("nanpanel_session"))
        .header("X-CSRF-Token", login.path("csrfToken"))
        .contentType("application/json")
        .body("{\"currentPassword\":\"test-only-password\","
            + "\"newPassword\":\"portability-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    var next = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var restored = given().cookie("nanpanel_session", next.cookie("nanpanel_session"))
        .header("X-CSRF-Token", next.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"portability-password-2026\"}")
        .post("/api/v1/auth/login").then().statusCode(200)
        .body("user.mustChangePassword", equalTo(false)).extract();
    return new Session(restored.cookie("nanpanel_session"), restored.path("csrfToken"));
  }

  private void seed() {
    // Tests share one database: start from an empty configuration every time.
    wipe();
    store.transaction(c -> {
      AuthStore.update(c, "DELETE FROM host WHERE hostname='web-01'");
      return null;
    });
    UUID hostId = UUID.randomUUID();
    UUID channelId = UUID.randomUUID();
    long now = System.currentTimeMillis();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO platform_settings (id,site_name,default_timezone,"
              + "host_offline_after_seconds,operation_retention_days,updated_at,updated_by,version) "
              + "VALUES (1,'生产面板','Asia/Shanghai',90,30,?,?,1)",
          now, UUID.fromString(adminId()));
      AuthStore.update(c, "INSERT INTO host (id,hostname,capabilities,status,created_at) "
          + "VALUES (?,?,'[]','ONLINE',?)", hostId, "web-01", Timestamp.from(Instant.now()));
      AuthStore.update(c, "INSERT INTO alert_channel (channel_id,name,kind,url,enabled,created_by,"
          + "created_at,updated_at,notify_recovery) VALUES (?,?,?,?,TRUE,?,?,?,FALSE)",
          channelId, "值班群", "WEBHOOK", "https://example.com/hook", adminId(), now, now);
      AuthStore.update(c, "INSERT INTO alert_rule (rule_id,name,condition_kind,host_id,severity,"
              + "enabled,created_by,created_at,channel_id) VALUES (?,?,?,?,?,TRUE,?,?,?)",
          UUID.randomUUID(), "主机离线", "HOST_NOT_ONLINE", hostId, "CRITICAL", adminId(), now,
          channelId);
      AuthStore.update(c, "INSERT INTO reverse_proxy_site (site_id,host_id,domain,listen_address,"
              + "listen_port,upstream_port,health_path,state,created_by,created_at,updated_at) "
              + "VALUES (?,?,?,?,?,?,?,'APPLIED',?,?,?)",
          UUID.randomUUID(), hostId, "shop.example.com", "192.0.2.10", 80, 8080, "/healthz",
          adminId(), now, now);
      return null;
    });
  }

  private String adminId() {
    var rows = store.transaction(c -> AuthStore.query(c,
        "SELECT id FROM panel_user WHERE username='admin'"));
    return String.valueOf(rows.getFirst().get("id"));
  }

  private void wipe() {
    store.transaction(c -> {
      AuthStore.update(c, "DELETE FROM alert_firing_state");
      AuthStore.update(c, "DELETE FROM alert_rule");
      AuthStore.update(c, "DELETE FROM reverse_proxy_site");
      AuthStore.update(c, "DELETE FROM alert_channel");
      AuthStore.update(c, "DELETE FROM platform_settings");
      return null;
    });
  }

  @Test
  void anExportedDocumentRoundTripsAndRefusesTampering() throws Exception {
    resetAdmin();
    Session session = login();
    seed();

    given().get("/api/v1/config/export").then().statusCode(401);
    String exported = given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/config/export").then().statusCode(200).extract().asString();
    assertFalse(exported.contains("tls_name"));
    ObjectNode document = (ObjectNode) JSON.readTree(exported);
    assertEquals(1, document.path("version").asInt());
    assertEquals(1, document.path("sites").size());
    assertEquals(1, document.path("alertRules").size());
    assertEquals(1, document.path("alertChannels").size());
    assertEquals("web-01", document.path("sites").get(0).path("hostname").asText());
    assertEquals("值班群", document.path("alertRules").get(0).path("channelName").asText());
    assertNotNull(document.path("checksum").asText(null));
    assertEquals(ConfigPortabilityResource.checksum(withoutChecksum(document)),
        document.path("checksum").asText());

    // A tampered document is refused instead of half-applied.
    ObjectNode tampered = document.deepCopy();
    ((ObjectNode) ((ArrayNode) tampered.path("sites")).get(0)).put("upstreamPort", 9999);
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json").body(tampered.toString())
        .post("/api/v1/config/import").then().statusCode(422)
        .body("code", equalTo("EXPORT_CHECKSUM_MISMATCH"));

    ObjectNode futureVersion = document.deepCopy();
    futureVersion.put("version", 2);
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json").body(futureVersion.toString())
        .post("/api/v1/config/import").then().statusCode(422)
        .body("code", equalTo("EXPORT_VERSION_UNSUPPORTED"));

    // Fresh machine: the same document rebuilds everything, and sites arrive as drafts.
    wipe();
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json").body(document.toString())
        .post("/api/v1/config/import").then().statusCode(200)
        .body("strategy", equalTo("skip"))
        .body("imported", equalTo(4))
        .body("skipped", equalTo(0))
        .body("failed", org.hamcrest.Matchers.hasSize(0));
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/reverse-proxy-sites")
        .then().statusCode(200)
        .body("items[0].state", equalTo("DRAFT"))
        .body("items[0].upstreamPort", equalTo(8080));
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/alert-rules")
        .then().statusCode(200)
        .body("items[0].channelName", equalTo("值班群"));

    // Importing the same document again changes nothing.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json").body(document.toString())
        .post("/api/v1/config/import").then().statusCode(200)
        .body("imported", equalTo(0)).body("skipped", equalTo(4));

    // Overwrite applies the document's values, once its checksum is recomputed.
    ObjectNode changed = document.deepCopy();
    ((ObjectNode) ((ArrayNode) changed.path("sites")).get(0)).put("upstreamPort", 9000);
    changed.put("checksum", ConfigPortabilityResource.checksum(withoutChecksum(changed)));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json").body(changed.toString())
        .post("/api/v1/config/import?strategy=overwrite").then().statusCode(200)
        .body("strategy", equalTo("overwrite")).body("imported", equalTo(4));
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/reverse-proxy-sites")
        .then().statusCode(200).body("items[0].upstreamPort", equalTo(9000));

    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token()).contentType("application/json")
        .body(document.toString()).post("/api/v1/config/import?strategy=merge")
        .then().statusCode(422).body("code", equalTo("VALIDATION_FAILED"));
  }

  @Test
  void aSiteWhoseHostIsMissingIsReportedInsteadOfSilentlyDropped() throws Exception {
    resetAdmin();
    Session session = login();
    seed();
    String exported = given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/config/export").then().statusCode(200).extract().asString();
    ObjectNode document = (ObjectNode) JSON.readTree(exported);
    ((ObjectNode) ((ArrayNode) document.path("sites")).get(0)).put("hostname", "ghost-host");
    document.put("checksum", ConfigPortabilityResource.checksum(withoutChecksum(document)));

    wipe();
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json").body(document.toString())
        .post("/api/v1/config/import").then().statusCode(200)
        .body("imported", equalTo(3))
        .body("failed[0].kind", equalTo("site"))
        .body("failed[0].name", equalTo("shop.example.com"));
    assertTrue(store.transaction(c -> AuthStore.query(c,
        "SELECT site_id FROM reverse_proxy_site")).isEmpty());
  }

  private static ObjectNode withoutChecksum(ObjectNode document) {
    ObjectNode copy = document.deepCopy();
    copy.remove("checksum");
    return copy;
  }

  @Test
  void aDryRunReportsWithoutWritingAnything() throws Exception {
    resetAdmin();
    Session session = login();
    seed();
    ObjectNode document = (ObjectNode) JSON.readTree(
        given().cookie("nanpanel_session", session.cookie())
            .get("/api/v1/config/export").then().statusCode(200).extract().asString());
    wipe();

    // The preview counts exactly what the real run would do, but touches nothing.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json").body(document.toString())
        .post("/api/v1/config/import?dryRun=true").then().statusCode(200)
        .body("dryRun", equalTo(true))
        .body("imported", equalTo(4))
        .body("skipped", equalTo(0))
        .body("failed", org.hamcrest.Matchers.hasSize(0));
    assertTrue(store.transaction(c -> AuthStore.query(c, "SELECT site_id FROM reverse_proxy_site"))
        .isEmpty());
    assertTrue(store.transaction(c -> AuthStore.query(c, "SELECT channel_id FROM alert_channel"))
        .isEmpty());
    assertTrue(store.transaction(c -> AuthStore.query(c, "SELECT rule_id FROM alert_rule")).isEmpty());
    assertTrue(store.transaction(c -> AuthStore.query(c, "SELECT id FROM platform_settings"))
        .isEmpty());

    // A channel created earlier in the same document still satisfies a rule in a dry run.
    ObjectNode withNewChannel = document.deepCopy();
    ObjectNode channel = ((ArrayNode) withNewChannel.path("alertChannels")).addObject();
    channel.put("name", "新渠道");
    channel.put("url", "https://example.com/new");
    channel.put("enabled", true);
    channel.put("notifyRecovery", true);
    ((ObjectNode) ((ArrayNode) withNewChannel.path("alertRules")).get(0)).put("channelName", "新渠道");
    withNewChannel.put("checksum", ConfigPortabilityResource.checksum(withoutChecksum(withNewChannel)));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json").body(withNewChannel.toString())
        .post("/api/v1/config/import?dryRun=true").then().statusCode(200)
        .body("imported", equalTo(5))
        .body("failed", org.hamcrest.Matchers.hasSize(0));

    // And the real run then behaves exactly like the preview said it would.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json").body(withNewChannel.toString())
        .post("/api/v1/config/import").then().statusCode(200)
        .body("dryRun", equalTo(false))
        .body("imported", equalTo(5));
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/alert-rules")
        .then().statusCode(200).body("items[0].channelName", equalTo("新渠道"));
  }
}
