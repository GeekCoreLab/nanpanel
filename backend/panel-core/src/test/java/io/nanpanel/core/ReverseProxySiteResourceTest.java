package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
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
@TestProfile(ReverseProxySiteResourceTest.SiteProfile.class)
class ReverseProxySiteResourceTest {
  public static class SiteProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:reverse_proxy_sites;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
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
            + "\"newPassword\":\"site-secure-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    var next = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var restored = given().cookie("nanpanel_session", next.cookie("nanpanel_session"))
        .header("X-CSRF-Token", next.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"site-secure-password-2026\"}")
        .post("/api/v1/auth/login").then().statusCode(200)
        .body("user.mustChangePassword", equalTo(false)).extract();
    return new Session(restored.cookie("nanpanel_session"), restored.path("csrfToken"));
  }

  @Test
  void sitesAreProtectedStoredAppliedAndRemoved() {
    given().get("/api/v1/reverse-proxy-sites").then().statusCode(401);
    Session session = login();
    UUID hostId = insertHost("shop-host-1");

    String body = "{\"hostId\":\"" + hostId + "\",\"domain\":\"Shop.Example.com\","
        + "\"listenAddress\":\"192.0.2.10\",\"listenPort\":80,\"upstreamPort\":8080}";
    var created = given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json").body(body)
        .post("/api/v1/reverse-proxy-sites").then().statusCode(201)
        .body("domain", equalTo("shop.example.com"))
        .body("hostname", equalTo("shop-host-1"))
        .body("state", equalTo("DRAFT"))
        .body("healthPath", equalTo("/"))
        .body("upstreamPort", equalTo(8080))
        .extract();
    String siteId = created.path("id");

    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/reverse-proxy-sites").then().statusCode(200)
        .body("items", hasSize(1))
        .body("items[0].id", equalTo(siteId));

    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json").body(body)
        .post("/api/v1/reverse-proxy-sites").then().statusCode(409)
        .body("code", equalTo("SITE_CONFLICT"));

    // A draft site can be corrected instead of deleted and recreated.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json")
        .body("{\"hostId\":\"" + hostId + "\",\"domain\":\"shop.example.com\","
            + "\"listenAddress\":\"192.0.2.10\",\"listenPort\":80,\"upstreamPort\":8081,"
            + "\"healthPath\":\"/healthz\"}")
        .put("/api/v1/reverse-proxy-sites/" + siteId).then().statusCode(200)
        .body("healthPath", equalTo("/healthz"))
        .body("upstreamPort", equalTo(8081))
        .body("state", equalTo("DRAFT"));

    // An edit is validated with the same rules as a create.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json")
        .body("{\"hostId\":\"" + hostId + "\",\"domain\":\"bad_domain\","
            + "\"listenAddress\":\"192.0.2.10\",\"listenPort\":80,\"upstreamPort\":8081}")
        .put("/api/v1/reverse-proxy-sites/" + siteId).then().statusCode(422)
        .body("code", equalTo("VALIDATION_FAILED"));

    // Sites cannot be moved to another host by an edit.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json")
        .body("{\"hostId\":\"" + UUID.randomUUID() + "\",\"domain\":\"shop.example.com\","
            + "\"listenAddress\":\"192.0.2.10\",\"listenPort\":80,\"upstreamPort\":8081}")
        .put("/api/v1/reverse-proxy-sites/" + siteId).then().statusCode(422)
        .body("code", equalTo("VALIDATION_FAILED"));

    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json")
        .body("{\"hostId\":\"" + hostId + "\",\"domain\":\"bad_domain\","
            + "\"listenAddress\":\"192.0.2.10\",\"listenPort\":80,\"upstreamPort\":8080}")
        .post("/api/v1/reverse-proxy-sites").then().statusCode(422)
        .body("code", equalTo("VALIDATION_FAILED"));

    // Unknown host is rejected before any row is written.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json")
        .body("{\"hostId\":\"" + UUID.randomUUID() + "\",\"domain\":\"app.example.com\","
            + "\"listenAddress\":\"192.0.2.11\",\"listenPort\":80,\"upstreamPort\":3000}")
        .post("/api/v1/reverse-proxy-sites").then().statusCode(404)
        .body("code", equalTo("HOST_NOT_FOUND"));

    // TLS material that is not PEM is rejected before any dispatch happens.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", "site-tls-0001")
        .contentType("application/json")
        .body("{\"certificatePem\":\"not a pem\",\"privateKeyPem\":\"also not\"}")
        .post("/api/v1/reverse-proxy-sites/" + siteId + "/tls").then().statusCode(422)
        .body("code", equalTo("VALIDATION_FAILED"));

    // A well-formed pair still cannot be installed while the host has no Agent.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", "site-tls-0002")
        .contentType("application/json")
        .body("{\"certificatePem\":\"-----BEGIN CERTIFICATE-----\\nMIIB\\n-----END CERTIFICATE-----\","
            + "\"privateKeyPem\":\"-----BEGIN PRIVATE KEY-----\\nMIIB\\n-----END PRIVATE KEY-----\","
            + "\"listenPort\":443}")
        .post("/api/v1/reverse-proxy-sites/" + siteId + "/tls").then().statusCode(503)
        .body("code", equalTo("AGENT_OFFLINE"));
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/reverse-proxy-sites").then().statusCode(200)
        .body("items[0].tlsName", nullValue());

    // Once material is recorded, apply compiles a TLS route payload: it clears
    // validation and fails only because the Agent is offline (503, not 422).
    store.transaction(c -> AuthStore.update(c,
        "UPDATE reverse_proxy_site SET tls_name='site-0123456789ab', listen_port=443 "
            + "WHERE site_id=?", UUID.fromString(siteId)));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", "site-apply-0002")
        .post("/api/v1/reverse-proxy-sites/" + siteId + "/apply").then().statusCode(503)
        .body("code", equalTo("AGENT_OFFLINE"));
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/reverse-proxy-sites").then().statusCode(200)
        .body("items[0].tlsName", equalTo("site-0123456789ab"));

    // No agent is enrolled, so dispatch is rejected and the site is marked FAILED.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", "site-apply-0001")
        .post("/api/v1/reverse-proxy-sites/" + siteId + "/apply").then().statusCode(503)
        .body("code", equalTo("AGENT_OFFLINE"));
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/reverse-proxy-sites").then().statusCode(200)
        .body("items[0].state", equalTo("FAILED"));

    // An APPLIED site cannot be deleted, and a failed disable keeps it APPLIED.
    store.transaction(c -> AuthStore.update(c,
        "UPDATE reverse_proxy_site SET state='APPLIED' WHERE site_id=?", UUID.fromString(siteId)));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .delete("/api/v1/reverse-proxy-sites/" + siteId).then().statusCode(409)
        .body("code", equalTo("SITE_APPLIED"));
    // An applied site must be disabled before it can be edited.
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .contentType("application/json")
        .body("{\"domain\":\"shop.example.com\",\"listenAddress\":\"192.0.2.10\","
            + "\"listenPort\":80,\"upstreamPort\":9000}")
        .put("/api/v1/reverse-proxy-sites/" + siteId).then().statusCode(409)
        .body("code", equalTo("SITE_APPLIED"));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .header("Idempotency-Key", "site-disable-0001")
        .post("/api/v1/reverse-proxy-sites/" + siteId + "/disable").then().statusCode(503)
        .body("code", equalTo("AGENT_OFFLINE"));
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/reverse-proxy-sites").then().statusCode(200)
        .body("items[0].state", equalTo("APPLIED"));

    // A DISABLED site has no route left on the host, so its record can be removed.
    store.transaction(c -> AuthStore.update(c,
        "UPDATE reverse_proxy_site SET state='DISABLED' WHERE site_id=?", UUID.fromString(siteId)));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.token())
        .delete("/api/v1/reverse-proxy-sites/" + siteId).then().statusCode(204);
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/reverse-proxy-sites").then().statusCode(200)
        .body("items", empty());
  }
}