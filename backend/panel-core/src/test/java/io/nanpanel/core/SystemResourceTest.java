package io.nanpanel.core;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
@QuarkusTest
class SystemResourceTest {
  @Test
  void sessionLifecycle() {
    given().get("/api/v1/system").then().statusCode(401);
    var pre = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    String cookie = pre.cookie("nanpanel_session"), csrf =
                                                        pre.path("csrfToken");
    given()
        .cookie("nanpanel_session", cookie)
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"test-only-password\"}")
        .post("/api/v1/auth/login")
        .then()
        .statusCode(403);
    var login =
        given()
            .cookie("nanpanel_session", cookie)
            .header("X-CSRF-Token", csrf)
            .contentType("application/json")
            .body(
                "{\"username\":\"admin\",\"password\":\"test-only-password\"}")
            .post("/api/v1/auth/login")
            .then()
            .statusCode(200)
            .body("user.mustChangePassword", equalTo(true))
            .extract();
    String active = login.cookie("nanpanel_session"),
           token = login.path("csrfToken");
    given()
        .cookie("nanpanel_session", cookie)
        .get("/api/v1/auth/me")
        .then()
        .statusCode(401);
    given()
        .cookie("nanpanel_session", active)
        .get("/api/v1/auth/me")
        .then()
        .statusCode(200);
    given()
        .cookie("nanpanel_session", active)
        .get("/api/v1/system")
        .then()
        .statusCode(403);
    given()
        .cookie("nanpanel_session", active)
        .header("Origin", "https://evil.example")
        .header("X-CSRF-Token", token)
        .post("/api/v1/auth/logout")
        .then()
        .statusCode(403);
    given()
        .cookie("nanpanel_session", active)
        .header("X-CSRF-Token", token)
        .contentType("application/json")
        .body("{\"currentPassword\":\"test-only-password\",\"newPassword\":" +
              "\"changed-secure-password-2026\"}")
        .put("/api/v1/auth/password")
        .then()
        .statusCode(204);
    given()
        .cookie("nanpanel_session", active)
        .get("/api/v1/auth/me")
        .then()
        .statusCode(401);
    var next =
        given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var restored =
        given()
            .cookie("nanpanel_session", next.cookie("nanpanel_session"))
            .header("X-CSRF-Token", next.path("csrfToken"))
            .contentType("application/json")
            .body("{\"username\":\"admin\",\"password\":\"changed-secure-" +
                  "password-2026\"}")
            .post("/api/v1/auth/login")
            .then()
            .statusCode(200)
            .body("user.mustChangePassword", equalTo(false))
            .extract();
    String restoredCookie = restored.cookie("nanpanel_session");
    given()
        .cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/system")
        .then()
        .statusCode(200);
    var sessions = given()
                       .cookie("nanpanel_session", restoredCookie)
                       .get("/api/v1/auth/sessions")
                       .then()
                       .statusCode(200)
                       .body("items.size()", equalTo(1))
                       .extract();
    String sid = sessions.path("items[0].id");
    given()
        .cookie("nanpanel_session", sid)
        .get("/api/v1/auth/me")
        .then()
        .statusCode(401);
    given()
        .cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .delete("/api/v1/auth/sessions/" + sid)
        .then()
        .statusCode(204);
    given()
        .cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/auth/me")
        .then()
        .statusCode(401);
  }
}
