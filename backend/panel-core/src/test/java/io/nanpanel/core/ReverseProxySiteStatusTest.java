package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(ReverseProxySiteStatusTest.StatusProfile.class)
class ReverseProxySiteStatusTest {
  public static class StatusProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:reverse_proxy_site_status;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    }
  }

  @jakarta.inject.Inject AuthStore store;

  private UUID insertHost(String hostname) {
    UUID hostId = UUID.randomUUID();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO host (id,hostname,capabilities,status,created_at) "
          + "VALUES (?,?,'[]','ONLINE',?)", hostId, hostname,
          java.sql.Timestamp.from(Instant.now()));
      return null;
    });
    return hostId;
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
            + "\"newPassword\":\"site-status-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    var next = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var restored = given().cookie("nanpanel_session", next.cookie("nanpanel_session"))
        .header("X-CSRF-Token", next.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"site-status-password-2026\"}")
        .post("/api/v1/auth/login").then().statusCode(200)
        .body("user.mustChangePassword", equalTo(false)).extract();
    return new Session(restored.cookie("nanpanel_session"), restored.path("csrfToken"));
  }

  /** Seeds the operation a real apply would have produced and links it to the site. */
  private void attachOperation(String siteId, String status) {
    UUID operationId = UUID.randomUUID();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO operation (id,idempotency_key,phase,status,created_at,"
              + "updated_at) VALUES (?,?,?,?,?,?)", operationId,
          "site-status-" + operationId, "APPLY", status,
          java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));
      AuthStore.update(c, "UPDATE reverse_proxy_site SET state='APPLIED', operation_id=? "
          + "WHERE site_id=?", operationId, UUID.fromString(siteId));
      return null;
    });
  }

  @Test
  void aFailedOperationIsNeverReportedAsApplied() {
    Session session = login();
    UUID hostId = insertHost("status-host-1");
    String body = "{\"hostId\":\"" + hostId + "\",\"domain\":\"status.example.com\","
        + "\"listenAddress\":\"192.0.2.11\",\"listenPort\":80,\"upstreamPort\":8080}";
    String failedId = given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json").body(body)
        .post("/api/v1/reverse-proxy-sites").then().statusCode(201).extract().path("id");

    // The panel accepted the apply but the helper then failed: the site must not claim to serve.
    attachOperation(failedId, "FAILED");
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/reverse-proxy-sites")
        .then().statusCode(200)
        .body("items.find { it.id == '" + failedId + "' }.state", equalTo("FAILED"))
        .body("items.find { it.id == '" + failedId + "' }.operationStatus", equalTo("FAILED"));

    // A failed apply leaves no working route, so the record can be cleaned up or edited.
    String editedBody = "{\"domain\":\"status2.example.com\",\"listenAddress\":\"192.0.2.11\","
        + "\"listenPort\":80,\"upstreamPort\":8080}";
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json").body(editedBody)
        .put("/api/v1/reverse-proxy-sites/" + failedId).then().statusCode(200)
        .body("domain", equalTo("status2.example.com"));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .delete("/api/v1/reverse-proxy-sites/" + failedId).then().statusCode(204);

    // An uncertain outcome is reported as such and still requires an explicit disable.
    String uncertainId = given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json")
        .body("{\"hostId\":\"" + hostId + "\",\"domain\":\"uncertain.example.com\","
            + "\"listenAddress\":\"192.0.2.11\",\"listenPort\":8080,\"upstreamPort\":8080}")
        .post("/api/v1/reverse-proxy-sites").then().statusCode(201).extract().path("id");
    attachOperation(uncertainId, "UNKNOWN");
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/reverse-proxy-sites")
        .then().statusCode(200)
        .body("items.find { it.id == '" + uncertainId + "' }.state", equalTo("UNKNOWN"));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .delete("/api/v1/reverse-proxy-sites/" + uncertainId).then().statusCode(409)
        .body("code", equalTo("SITE_APPLIED"));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json")
        .body("{\"domain\":\"uncertain.example.com\",\"listenAddress\":\"192.0.2.11\","
            + "\"listenPort\":8080,\"upstreamPort\":9090}")
        .put("/api/v1/reverse-proxy-sites/" + uncertainId)
        .then().statusCode(409).body("code", equalTo("SITE_APPLIED"));
  }
}
