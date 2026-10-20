package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.QuarkusTestResource;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = P3ProviderTestResource.class, restrictToAnnotatedClass = true)
class NetworkProviderApiTest {
  private static final String PASSWORD = "p3-provider-test-password-2026";

  private record Session(String cookie, String csrf) {}

  @Test
  void coreRoutesPluginProtocolAndBlocksDisableUntilOperationsAreTerminal() {
    Session session = administratorSession();

    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/network-providers")
        .then().statusCode(200).body("size()", equalTo(1))
        .body("[0].id", equalTo("p3-fixture-provider"))
        .body("[0].capabilities", hasItem("p3.fixture.tcp"))
        .body("[0].capabilityDetails[0].operations", hasItems("PLAN", "VALIDATE", "APPLY"));
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/plugins")
        .then().statusCode(200).body("[0].id", equalTo("p3-api-fixture"))
        .body("[0].state", equalTo("STARTED"));
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/config-translators")
        .then().statusCode(200).body("[0].id", equalTo("p3-fixture-translator"))
        .body("[0].sourceFormats", hasItem("fixture"));

    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .contentType("application/json").body(Map.of("resourceKey", "fixture-route"))
        .post("/api/v1/network-providers/p3-fixture-provider/observations")
        .then().statusCode(200).body("providerId", equalTo("p3-fixture-provider"))
        .body("snapshot", equalTo("observed:fixture-route"));

    Map<String, String> translation = Map.of(
        "sourceFormat", "fixture", "snapshot", "manual-setting=kept", "targetFormat", "normalized");
    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .contentType("application/json").body(translation)
        .post("/api/v1/config-translators/p3-fixture-translator/normalize")
        .then().statusCode(200).body("snapshot", equalTo("manual-setting=kept"))
        .body("preservedFields", hasItem("manual-setting"));
    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .contentType("application/json").body(translation)
        .post("/api/v1/config-translators/p3-fixture-translator/render")
        .then().statusCode(200).body("snapshot", equalTo("rendered:manual-setting=kept"));

    Map<String, String> request = Map.of("desiredSnapshot", "fixture-route=8080",
        "observedSnapshot", "fixture-route=old");
    var created = given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .contentType("application/json").body(request)
        .post("/api/v1/network-providers/p3-fixture-provider/plans")
        .then().statusCode(201).body("providerId", equalTo("p3-fixture-provider"))
        .body("state", equalTo("PLANNED")).extract();
    String operationId = created.path("id");
    var duplicate = given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .contentType("application/json").body(request)
        .post("/api/v1/network-providers/p3-fixture-provider/plans")
        .then().statusCode(201).extract();
    assertEquals(operationId, duplicate.path("id"));

    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .contentType("application/json").body(Map.of("enabled", false))
        .put("/api/v1/plugins/p3-api-fixture/enabled")
        .then().statusCode(409).body("code", equalTo("PROVIDER_OPERATIONS_OPEN"));
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/plugins")
        .then().statusCode(200).body("[0].state", equalTo("STARTED"));

    String operations = "/api/v1/network-providers/p3-fixture-provider/operations/" + operationId;
    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .post(operations + "/validate")
        .then().statusCode(200).body("state", equalTo("VALIDATED"));
    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .post(operations + "/apply")
        .then().statusCode(200).body("state", equalTo("APPLIED"));
    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .post(operations + "/verify")
        .then().statusCode(200).body("state", equalTo("VERIFIED"));
    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .post(operations + "/compensate")
        .then().statusCode(200).body("state", equalTo("COMPENSATED"));
    given().cookie("nanpanel_session", session.cookie())
        .get(operations)
        .then().statusCode(200).body("state", equalTo("COMPENSATED"));

    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .contentType("application/json").body(Map.of("enabled", false))
        .put("/api/v1/plugins/p3-api-fixture/enabled")
        .then().statusCode(200).body("state", equalTo("DISABLED"));
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/network-providers")
        .then().statusCode(200).body("size()", equalTo(0));
    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .contentType("application/json").body(Map.of("enabled", true))
        .put("/api/v1/plugins/p3-api-fixture/enabled")
        .then().statusCode(200).body("state", equalTo("STARTED"));
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/network-providers")
        .then().statusCode(200).body("[0].id", equalTo("p3-fixture-provider"));

    var audit = given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/admin/audit-events?limit=100")
        .then().statusCode(200).extract();
    var actions = audit.jsonPath().getList("items.action", String.class);
    assertTrue(actions.containsAll(java.util.List.of(
        "network.provider.plan", "network.provider.validate", "network.provider.apply",
        "network.provider.verify", "network.provider.compensate", "plugin.disabled",
        "plugin.enabled")));
  }

  private static Session administratorSession() {
    var preLogin = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var initial = login(preLogin.cookie("nanpanel_session"), preLogin.path("csrfToken"),
        "test-only-password");
    if (Boolean.TRUE.equals(initial.path("user.mustChangePassword"))) {
      given().cookie("nanpanel_session", initial.cookie("nanpanel_session"))
          .header("X-CSRF-Token", initial.path("csrfToken"))
          .contentType("application/json")
          .body(Map.of("currentPassword", "test-only-password", "newPassword", PASSWORD))
          .put("/api/v1/auth/password").then().statusCode(204);
      var next = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
      initial = login(next.cookie("nanpanel_session"), next.path("csrfToken"), PASSWORD);
    }
    return new Session(initial.cookie("nanpanel_session"), initial.path("csrfToken"));
  }

  private static ExtractableResponse<Response> login(String cookie, String csrf, String password) {
    return given().cookie("nanpanel_session", cookie).header("X-CSRF-Token", csrf)
        .contentType("application/json")
        .body(Map.of("username", "admin", "password", password))
        .post("/api/v1/auth/login").then().statusCode(200).extract();
  }
}
