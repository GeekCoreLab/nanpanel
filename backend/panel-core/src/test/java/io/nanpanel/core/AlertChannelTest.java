package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(AlertChannelTest.ChannelProfile.class)
class AlertChannelTest {
  public static class ChannelProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:alert_channels;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
          // The timer is off: every dispatch in this test is an explicit runOnce() call.
          "nanpanel.alerting.dispatch-enabled", "false");
    }
  }

  @jakarta.inject.Inject AuthStore store;
  @jakarta.inject.Inject AlertNotifier notifier;

  private HttpServer server;
  private final List<String> received = new CopyOnWriteArrayList<>();
  private volatile int responseStatus = 204;
  private String hookUrl;

  @BeforeEach
  void startReceiver() throws IOException {
    received.clear();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/hook", exchange -> {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      received.add(body);
      exchange.sendResponseHeaders(responseStatus, -1);
      exchange.close();
    });
    server.start();
    hookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
  }

  @AfterEach
  void stopReceiver() {
    if (server != null) server.stop(0);
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
            + "\"newPassword\":\"channel-secure-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    var next = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var restored = given().cookie("nanpanel_session", next.cookie("nanpanel_session"))
        .header("X-CSRF-Token", next.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"channel-secure-password-2026\"}")
        .post("/api/v1/auth/login").then().statusCode(200)
        .body("user.mustChangePassword", equalTo(false)).extract();
    return new Session(restored.cookie("nanpanel_session"), restored.path("csrfToken"));
  }

  @Test
  void channelsAreValidatedTestedAndRemoved() {
    given().get("/api/v1/alert-channels").then().statusCode(401);
    seedAdmin();
    Session session = login();

    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json")
        .body("{\"name\":\"运维群\",\"url\":\"ftp://example.com/hook\"}")
        .post("/api/v1/alert-channels").then().statusCode(422)
        .body("code", equalTo("VALIDATION_FAILED"));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json")
        .body("{\"url\":\"" + hookUrl + "\"}")
        .post("/api/v1/alert-channels").then().statusCode(422);

    var created = given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json")
        .body("{\"name\":\"运维群\",\"url\":\"" + hookUrl + "\"}")
        .post("/api/v1/alert-channels").then().statusCode(201)
        .body("name", equalTo("运维群"))
        .body("enabled", equalTo(true))
        .body("ruleCount", equalTo(0))
        .body("lastStatus", equalTo(null))
        .extract();
    String channelId = created.path("id");

    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json")
        .body("{\"name\":\"运维群\",\"url\":\"" + hookUrl + "\"}")
        .post("/api/v1/alert-channels").then().statusCode(409)
        .body("code", equalTo("CHANNEL_CONFLICT"));

    // Rules can bind a channel; an unknown channel is rejected outright.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json")
        .body("{\"name\":\"绑定规则\",\"condition\":\"HOST_NOT_ONLINE\",\"severity\":\"WARNING\","
            + "\"channelId\":\"" + UUID.randomUUID() + "\"}")
        .post("/api/v1/alert-rules").then().statusCode(404)
        .body("code", equalTo("CHANNEL_NOT_FOUND"));
    String ruleId = given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json")
        .body("{\"name\":\"绑定规则\",\"condition\":\"HOST_NOT_ONLINE\",\"severity\":\"WARNING\","
            + "\"channelId\":\"" + channelId + "\"}")
        .post("/api/v1/alert-rules").then().statusCode(201)
        .body("channelName", equalTo("运维群"))
        .body("channelId", equalTo(channelId)).extract().path("id");
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/alert-rules")
        .then().statusCode(200)
        .body("items.find { it.id == '" + ruleId + "' }.channelName", equalTo("运维群"));

    // A successful test records the delivery and sends the documented payload.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .post("/api/v1/alert-channels/" + channelId + "/test").then().statusCode(200)
        .body("status", equalTo("SUCCEEDED"));
    assertEquals(1, received.size());
    assertTrue(received.getFirst().contains("\"event\":\"alert.test\""));
    assertTrue(received.getFirst().contains("\"channel\":\"运维群\""));
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/alert-channels")
        .then().statusCode(200).body("items[0].lastStatus", equalTo("SUCCEEDED"));

    // A receiver that answers 500 is reported as a failed delivery, not a silent success.
    responseStatus = 500;
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .post("/api/v1/alert-channels/" + channelId + "/test").then().statusCode(502)
        .body("status", equalTo("FAILED"))
        .body("error", equalTo("HTTP 500"));
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/alert-channels")
        .then().statusCode(200).body("items[0].lastStatus", equalTo("FAILED"));

    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json").body("{\"enabled\":false}")
        .put("/api/v1/alert-channels/" + channelId).then().statusCode(200)
        .body("enabled", equalTo(false));

    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .delete("/api/v1/alert-channels/" + channelId).then().statusCode(204);
    // Other tests in this class share the database, so assert on this channel, not the list size.
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/alert-channels")
        .then().statusCode(200)
        .body("items.find { it.id == '" + channelId + "' }", nullValue());
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .post("/api/v1/alert-channels/" + channelId + "/test").then().statusCode(404)
        .body("code", equalTo("CHANNEL_NOT_FOUND"));
  }

  @Test
  void aStillFiringAlertIsSentOnceAndAgainOnlyAfterRecovery() {
    String adminId = seedAdmin();
    UUID hostId = seedHost("web-01", "OFFLINE");
    UUID channelId = UUID.randomUUID();
    long now = System.currentTimeMillis();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO alert_channel (channel_id,name,kind,url,enabled,"
          + "created_by,created_at,updated_at) VALUES (?,?,?,?,TRUE,?,?,?)",
          channelId, "值班群", "WEBHOOK", hookUrl, adminId, now, now);
      AuthStore.update(c, "INSERT INTO alert_rule (rule_id,name,condition_kind,host_id,severity,"
              + "enabled,created_by,created_at,channel_id) VALUES (?,?,?,?,?,TRUE,?,?,?)",
          UUID.randomUUID(), "主机离线", "HOST_NOT_ONLINE", hostId, "CRITICAL", adminId, now,
          channelId);
      return null;
    });

    assertEquals(1, notifier.runOnce());
    assertEquals(1, received.size());
    String body = received.getFirst();
    assertTrue(body.contains("\"event\":\"alert.firing\""));
    assertTrue(body.contains("\"ruleName\":\"主机离线\""));
    assertTrue(body.contains("\"hostname\":\"web-01\""));
    assertTrue(body.contains("\"severity\":\"CRITICAL\""));

    // Still offline: the same subject must not page twice.
    assertEquals(0, notifier.runOnce());
    assertEquals(1, received.size());

    // Recovery clears the state without notifying.
    store.transaction(c -> AuthStore.update(c,
        "UPDATE host SET status='ONLINE' WHERE id=?", hostId));
    assertEquals(0, notifier.runOnce());
    assertEquals(1, received.size());

    // A relapse is a new incident, so it notifies again.
    store.transaction(c -> AuthStore.update(c,
        "UPDATE host SET status='OFFLINE' WHERE id=?", hostId));
    assertEquals(1, notifier.runOnce());
    assertEquals(2, received.size());
  }

  @Test
  void aFailedDeliveryIsRetriedOnTheNextPass() {
    String adminId = seedAdmin();
    Session session = login();
    UUID hostId = seedHost("web-02", "OFFLINE");
    UUID channelId = UUID.randomUUID();
    long now = System.currentTimeMillis();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO alert_channel (channel_id,name,kind,url,enabled,"
          + "created_by,created_at,updated_at) VALUES (?,?,?,?,TRUE,?,?,?)",
          channelId, "失败重试", "WEBHOOK", hookUrl, adminId, now, now);
      AuthStore.update(c, "INSERT INTO alert_rule (rule_id,name,condition_kind,host_id,severity,"
              + "enabled,created_by,created_at,channel_id) VALUES (?,?,?,?,?,TRUE,?,?,?)",
          UUID.randomUUID(), "主机离线", "HOST_NOT_ONLINE", hostId, "WARNING", adminId, now,
          channelId);
      return null;
    });

    responseStatus = 500;
    assertEquals(0, notifier.runOnce());
    assertEquals(1, received.size());
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/alert-channels")
        .then().statusCode(200).body("items[0].lastStatus", equalTo("FAILED"));

    // The target is still down; because nothing was acknowledged, the next pass resends.
    responseStatus = 204;
    assertEquals(1, notifier.runOnce());
    assertEquals(2, received.size());
    assertEquals(0, notifier.runOnce());
    assertEquals(2, received.size());
  }

  /**
   * Tests share one database, so each starts from the same administrator state: the
   * bootstrap password plus a mandatory change. Otherwise the first test to rotate the
   * password would break every later login in the class.
   */
  private String seedAdmin() {
    var rows = store.transaction(c -> AuthStore.query(c,
        "SELECT id FROM panel_user WHERE username='admin'"));
    if (!rows.isEmpty()) {
      String id = String.valueOf(rows.getFirst().get("id"));
      store.transaction(c -> {
        AuthStore.update(c, "UPDATE panel_user SET password_hash=?, must_change=TRUE, "
                + "status='ACTIVE' WHERE id=?", AuthStore.hash("test-only-password"), id);
        AuthStore.update(c, "DELETE FROM web_session");
        return null;
      });
      return id;
    }
    // The bootstrap admin does not exist until the first login; create it explicitly.
    String id = UUID.randomUUID().toString();
    long now = System.currentTimeMillis();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO panel_user (id,username,display_name,password_hash,"
              + "must_change,created_at,updated_at,status,version) "
              + "VALUES (?,?,?,?,TRUE,?,?,'ACTIVE',0)",
          id, "admin", "管理员", AuthStore.hash("test-only-password"), now, now);
      return null;
    });
    return id;
  }

  private UUID seedHost(String hostname, String status) {
    UUID hostId = UUID.randomUUID();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO host (id,hostname,capabilities,status,created_at) "
          + "VALUES (?,?,'[]',?,?)", hostId, hostname, status,
          Timestamp.from(Instant.now()));
      return null;
    });
    return hostId;
  }
}
