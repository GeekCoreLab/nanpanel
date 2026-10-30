package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The task list must be newest-first, filterable and page without repeating rows. */
@QuarkusTest
@TestProfile(OperationListFilterTest.FilterProfile.class)
class OperationListFilterTest {
  public static class FilterProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:operation_list_filter;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
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
            + "\"newPassword\":\"operation-filter-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    var next = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var restored = given().cookie("nanpanel_session", next.cookie("nanpanel_session"))
        .header("X-CSRF-Token", next.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"operation-filter-password-2026\"}")
        .post("/api/v1/auth/login").then().statusCode(200)
        .body("user.mustChangePassword", equalTo(false)).extract();
    return new Session(restored.cookie("nanpanel_session"), restored.path("csrfToken"));
  }

  private UUID insertHost(String hostname) {
    UUID hostId = UUID.randomUUID();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO host (id,hostname,capabilities,status,created_at) "
          + "VALUES (?,?,'[]','ONLINE',?)", hostId, hostname, Timestamp.from(Instant.now()));
      return null;
    });
    return hostId;
  }

  private String insertOperation(UUID hostId, String capability, String status, int secondsAgo) {
    UUID id = UUID.randomUUID();
    Timestamp at = Timestamp.from(Instant.now().minusSeconds(secondsAgo));
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO operation (id,idempotency_key,phase,status,kind,host_id,"
              + "capability,target,reason,error_json,created_at,updated_at) "
              + "VALUES (?,?,?,?,'HOST_HELPER_OPERATION',?,?,?,?,?,?,?)",
          id, "filt-" + id, "APPLY", status, hostId, capability, "route:demo",
          "筛选测试", null, at, at);
      return null;
    });
    return id.toString();
  }

  @Test
  void theListIsNewestFirstFilterableAndPageable() {
    Session session = login();
    UUID hostA = insertHost("filter-host-a");
    UUID hostB = insertHost("filter-host-b");
    String oldest = insertOperation(hostA, "nginx.route.set", "SUCCEEDED", 300);
    String middle = insertOperation(hostA, "nginx.route.set", "FAILED", 200);
    String newest = insertOperation(hostB, "k3s.upgrade", "PENDING", 100);

    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/operations")
        .then().statusCode(200)
        .body("items", hasSize(3))
        .body("items[0].id", equalTo(newest))
        .body("items[0].hostname", equalTo("filter-host-b"))
        .body("items[1].id", equalTo(middle))
        .body("items[1].hostname", equalTo("filter-host-a"))
        .body("items[2].id", equalTo(oldest))
        .body("capabilities", contains("k3s.upgrade", "nginx.route.set"))
        .body("statuses", hasSize(6))
        .body("limit", equalTo(20));

    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/operations?capability=k3s.upgrade").then().statusCode(200)
        .body("items", hasSize(1)).body("items[0].id", equalTo(newest));
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/operations?status=FAILED").then().statusCode(200)
        .body("items", hasSize(1)).body("items[0].id", equalTo(middle));
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/operations?hostId=" + hostA).then().statusCode(200)
        .body("items", hasSize(2));

    // Keyset paging walks newest → oldest without repeating or skipping a row.
    var first = given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/operations?limit=2").then().statusCode(200)
        .body("items[0].id", equalTo(newest))
        .body("items[1].id", equalTo(middle))
        .body("nextCursor", notNullValue()).extract();
    String cursor = first.path("nextCursor");
    var second = given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/operations?limit=2&cursor=" + cursor).then().statusCode(200)
        .body("items", hasSize(1)).body("items[0].id", equalTo(oldest)).extract();
    assertNotEquals(newest, second.path("items[0].id"));

    // A cursor is bound to its filters.
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/operations?limit=2&cursor=" + cursor + "&status=FAILED")
        .then().statusCode(400).body("code", equalTo("INVALID_CURSOR"));
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/operations?capability=Bad%20Capability").then().statusCode(400)
        .body("code", equalTo("VALIDATION_FAILED"));
  }
}
