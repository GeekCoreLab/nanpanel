package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(AppStoreResourceTest.AppStoreProfile.class)
class AppStoreResourceTest {
  public static class AppStoreProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:app_store;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    }
  }

  @Test
  void catalogIsProtectedAndServesCuratedApps() {
    given().get("/api/v1/app-store/catalog").then().statusCode(401);
    given().get("/api/v1/app-store/catalog/nginx").then().statusCode(401);

    var pre = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    String cookie = pre.cookie("nanpanel_session"), csrf = pre.path("csrfToken");
    var login = given().cookie("nanpanel_session", cookie).header("X-CSRF-Token", csrf)
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"test-only-password\"}")
        .post("/api/v1/auth/login").then().statusCode(200)
        .body("user.mustChangePassword", equalTo(true)).extract();
    String active = login.cookie("nanpanel_session"), token = login.path("csrfToken");
    given().cookie("nanpanel_session", active)
        .get("/api/v1/app-store/catalog").then().statusCode(403)
        .body("code", equalTo("PASSWORD_CHANGE_REQUIRED"));

    given().cookie("nanpanel_session", active).header("X-CSRF-Token", token)
        .contentType("application/json")
        .body("{\"currentPassword\":\"test-only-password\","
            + "\"newPassword\":\"app-store-secure-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    var next = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var restored = given().cookie("nanpanel_session", next.cookie("nanpanel_session"))
        .header("X-CSRF-Token", next.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"app-store-secure-password-2026\"}")
        .post("/api/v1/auth/login").then().statusCode(200)
        .body("user.mustChangePassword", equalTo(false)).extract();
    String activeCookie = restored.cookie("nanpanel_session");

    given().cookie("nanpanel_session", activeCookie)
        .get("/api/v1/app-store/catalog")
        .then().statusCode(200)
        .body("version", equalTo(1))
        .body("categories", not(empty()))
        .body("items.size()", greaterThan(0))
        .body("items.find { it.id == 'nginx' }.name", equalTo("Nginx"))
        .body("items.find { it.id == 'nginx' }.deployableNow", equalTo(true))
        .body("items.find { it.id == 'mysql' }.deployableNow", equalTo(false))
        .body("items.find { it.id == 'mysql' }"
            + ".environment.find { it.name == 'MYSQL_ROOT_PASSWORD' }", notNullValue());

    given().cookie("nanpanel_session", activeCookie)
        .get("/api/v1/app-store/catalog/redis")
        .then().statusCode(200)
        .body("id", equalTo("redis"))
        .body("category", equalTo("caching"))
        .body("image", equalTo("redis:7-alpine"))
        .body("defaultPort", equalTo(6379));
    given().cookie("nanpanel_session", activeCookie)
        .get("/api/v1/app-store/catalog/does-not-exist")
        .then().statusCode(404).body("code", equalTo("APP_NOT_FOUND"));
  }
}