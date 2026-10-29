package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(AppStoreDeploymentResourceTest.DeploymentProfile.class)
class AppStoreDeploymentResourceTest {
  public static class DeploymentProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:app_store_deployments;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    }
  }

  @jakarta.inject.Inject AuthStore store;

  private void insertHost(UUID hostId, String hostname) {
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO host (id,hostname,capabilities,status,created_at) "
          + "VALUES (?,?,'[]','ONLINE',?)", hostId, hostname,
          java.sql.Timestamp.from(Instant.now()));
      return null;
    });
  }

  private void insertOperation(UUID hostId, String capability, String target, Instant at) {
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO operation (id,idempotency_key,phase,status,created_at,"
              + "updated_at,kind,host_id,capability,target) "
              + "VALUES (?,?,'APPLY','SUCCEEDED',?,?,'HOST_HELPER_OPERATION',?,?,?)",
          UUID.randomUUID(), "deploy-" + UUID.randomUUID(),
          java.sql.Timestamp.from(at), java.sql.Timestamp.from(at),
          hostId, capability, target);
      return null;
    });
  }

  @Test
  void listsDeployedContainersExcludingRemovedAndSuperseded() {
    given().get("/api/v1/app-store/deployments").then().statusCode(401);

    var pre = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    String cookie = pre.cookie("nanpanel_session"), csrf = pre.path("csrfToken");
    var login = given().cookie("nanpanel_session", cookie).header("X-CSRF-Token", csrf)
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"test-only-password\"}")
        .post("/api/v1/auth/login").then().statusCode(200).extract();
    String active = login.cookie("nanpanel_session"), token = login.path("csrfToken");
    given().cookie("nanpanel_session", active).header("X-CSRF-Token", token)
        .contentType("application/json")
        .body("{\"currentPassword\":\"test-only-password\","
            + "\"newPassword\":\"app-store-deploy-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    var next = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var restored = given().cookie("nanpanel_session", next.cookie("nanpanel_session"))
        .header("X-CSRF-Token", next.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"app-store-deploy-password-2026\"}")
        .post("/api/v1/auth/login").then().statusCode(200).extract();
    String activeCookie = restored.cookie("nanpanel_session");
    String activeToken = restored.path("csrfToken");

    UUID hostId = UUID.randomUUID();
    insertHost(hostId, "deploy-host-1");
    Instant base = Instant.now().minusSeconds(100);
    insertOperation(hostId, "container.deploy", "nanpanel-web", base);        // present
    insertOperation(hostId, "container.deploy", "nanpanel-db", base.plusSeconds(5)); // present
    insertOperation(hostId, "container.remove", "nanpanel-web", base.plusSeconds(10)); // removes web
    insertOperation(hostId, "container.deploy", "nanpanel-db", base.plusSeconds(20)); // redeploy supersedes older db deploy

    var items = given().cookie("nanpanel_session", activeCookie)
        .get("/api/v1/app-store/deployments")
        .then().statusCode(200)
        .body("items.size()", equalTo(1))
        .body("items[0].containerName", equalTo("nanpanel-db"))
        .body("items[0].hostname", equalTo("deploy-host-1"))
        .body("items[0].hostId", equalTo(hostId.toString()))
        .body("items[0].deployedAt", notNullValue())
        .extract();
    assertEquals("nanpanel-db", items.path("items[0].containerName"));

    // Advisory deploy metadata enriches the derived list with the published loopback port.
    given().cookie("nanpanel_session", activeCookie).header("X-CSRF-Token", activeToken)
        .contentType("application/json")
        .body("{\"hostId\":\"" + hostId + "\",\"containerName\":\"nanpanel-db\","
            + "\"image\":\"mariadb:11\",\"hostPort\":13306,\"containerPort\":3306}")
        .post("/api/v1/app-store/deployments").then().statusCode(200)
        .body("hostPort", equalTo(13306));

    given().cookie("nanpanel_session", activeCookie)
        .get("/api/v1/app-store/deployments").then().statusCode(200)
        .body("items.size()", equalTo(1))
        .body("items[0].hostPort", equalTo(13306))
        .body("items[0].containerPort", equalTo(3306))
        .body("items[0].image", equalTo("mariadb:11"));

    // Upsert updates the existing row instead of duplicating it.
    given().cookie("nanpanel_session", activeCookie).header("X-CSRF-Token", activeToken)
        .contentType("application/json")
        .body("{\"hostId\":\"" + hostId + "\",\"containerName\":\"nanpanel-db\","
            + "\"image\":\"mariadb:11\",\"hostPort\":13307,\"containerPort\":3306}")
        .post("/api/v1/app-store/deployments").then().statusCode(200);
    given().cookie("nanpanel_session", activeCookie)
        .get("/api/v1/app-store/deployments").then().statusCode(200)
        .body("items.size()", equalTo(1))
        .body("items[0].hostPort", equalTo(13307));

    given().cookie("nanpanel_session", activeCookie).header("X-CSRF-Token", activeToken)
        .contentType("application/json")
        .body("{\"hostId\":\"" + hostId + "\",\"containerName\":\"bad name!\","
            + "\"image\":\"x\",\"hostPort\":80,\"containerPort\":80}")
        .post("/api/v1/app-store/deployments").then().statusCode(422)
        .body("code", equalTo("VALIDATION_FAILED"));
    given().cookie("nanpanel_session", activeCookie).header("X-CSRF-Token", activeToken)
        .contentType("application/json")
        .body("{\"hostId\":\"" + UUID.randomUUID() + "\",\"containerName\":\"ok-name\","
            + "\"image\":\"x\",\"hostPort\":80,\"containerPort\":80}")
        .post("/api/v1/app-store/deployments").then().statusCode(404)
        .body("code", equalTo("HOST_NOT_FOUND"));
  }
}