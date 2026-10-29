package io.nanpanel.core;

import static io.restassured.RestAssured.given;
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
@TestProfile(DatabaseResourceTest.DatabaseProfile.class)
class DatabaseResourceTest {
  public static class DatabaseProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:databases;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
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
            + "\"newPassword\":\"database-secure-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    var next = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var restored = given().cookie("nanpanel_session", next.cookie("nanpanel_session"))
        .header("X-CSRF-Token", next.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"database-secure-password-2026\"}")
        .post("/api/v1/auth/login").then().statusCode(200)
        .body("user.mustChangePassword", equalTo(false)).extract();
    return new Session(restored.cookie("nanpanel_session"), restored.path("csrfToken"));
  }

  @Test
  void databaseInstancesAreDerivedFromDeployedContainersWithConnectionAddresses() {
    given().get("/api/v1/databases").then().statusCode(401);
    Session session = login();

    UUID hostId = UUID.randomUUID();
    Instant now = Instant.now();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO host (id,hostname,capabilities,status,created_at) "
          + "VALUES (?,?,'[]','ONLINE',?)", hostId, "db-host-1",
          java.sql.Timestamp.from(now));
      for (String target : new String[] {"nanpanel-mysql", "nanpanel-web", "nanpanel-redis"}) {
        AuthStore.update(c, "INSERT INTO operation (id,idempotency_key,phase,status,created_at,"
                + "updated_at,kind,host_id,capability,target) "
                + "VALUES (?,?,'APPLY','SUCCEEDED',?,?,'HOST_HELPER_OPERATION',?,?,?)",
            UUID.randomUUID(), "deploy-" + UUID.randomUUID(),
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
            hostId, "container.deploy", target);
      }
      AuthStore.update(c, "INSERT INTO host_container_deployment "
              + "(host_id,container_name,image,host_port,container_port,created_at) "
              + "VALUES (?,?,?,?,?,?)", hostId, "nanpanel-mysql", "mysql:8.0", 13306, 3306,
          now.toEpochMilli());
      AuthStore.update(c, "INSERT INTO host_container_deployment "
              + "(host_id,container_name,image,host_port,container_port,created_at) "
              + "VALUES (?,?,?,?,?,?)", hostId, "nanpanel-web", "nginx:1.27", 18080, 80,
          now.toEpochMilli());
      // A database without a published port: listed, but not reachable.
      AuthStore.update(c, "INSERT INTO host_container_deployment "
              + "(host_id,container_name,image,host_port,container_port,created_at) "
              + "VALUES (?,?,?,?,?,?)", hostId, "nanpanel-redis", "redis:7-alpine", null, 6379,
          now.toEpochMilli());
      return null;
    });

    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/databases").then().statusCode(200)
        .body("engines", hasSize(5))
        .body("items", hasSize(2))
        .body("items.find { it.containerName == 'nanpanel-mysql' }.engine.id", equalTo("mysql"))
        .body("items.find { it.containerName == 'nanpanel-mysql' }.engine.defaultPort",
            equalTo(3306))
        .body("items.find { it.containerName == 'nanpanel-mysql' }.hostPort", equalTo(13306))
        .body("items.find { it.containerName == 'nanpanel-mysql' }.connectionUrl",
            equalTo("jdbc:mysql://db-host-1:13306"))
        .body("items.find { it.containerName == 'nanpanel-mysql' }.note", equalTo(""))
        .body("items.find { it.containerName == 'nanpanel-redis' }.engine.id", equalTo("redis"))
        .body("items.find { it.containerName == 'nanpanel-redis' }.connectionUrl", nullValue())
        .body("items.find { it.containerName == 'nanpanel-redis' }.note",
            equalTo("容器未发布端口，无法从主机外部连接"));
  }
}
