package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(TopologyResourceTest.TopologyProfile.class)
class TopologyResourceTest {
  private static final String PASSWORD = "topology-api-test-password";

  public static class TopologyProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:topology_resource;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
          "quarkus.scheduler.enabled", "false");
    }
  }

  @Inject AuthStore store;

  private String sessionCookie;
  private String csrfToken;
  private String userId;
  private UUID gatewayHostId;
  private UUID applicationHostId;

  @BeforeEach
  void authenticateAndCreateHostAssets() {
    userId = store.transaction(connection -> {
      var users = AuthStore.query(connection,
          "SELECT id FROM panel_user WHERE username='topology-api-test-admin'");
      String id = users.isEmpty() ? UUID.randomUUID().toString()
          : String.valueOf(users.getFirst().get("id"));
      if (users.isEmpty()) {
        AuthStore.update(connection,
            "INSERT INTO panel_user (id,username,display_name,password_hash,must_change,status,"
                + "version,created_at,updated_at,last_login) VALUES (?,?,?, ?,FALSE,'ACTIVE',0,?,?,NULL)",
            id, "topology-api-test-admin", "Topology API Test Admin",
            AuthStore.hash(PASSWORD), System.currentTimeMillis(), System.currentTimeMillis());
      } else {
        AuthStore.update(connection,
            "UPDATE panel_user SET password_hash=?,must_change=FALSE,status='ACTIVE' WHERE id=?",
            AuthStore.hash(PASSWORD), id);
      }
      AuthStore.update(connection, "DELETE FROM web_session WHERE user_id=?", id);
      return id;
    });

    var preLogin = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var login = given().cookie("nanpanel_session", preLogin.cookie("nanpanel_session"))
        .header("X-CSRF-Token", preLogin.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("username", "topology-api-test-admin", "password", PASSWORD))
        .post("/api/v1/auth/login").then().statusCode(200).extract();
    sessionCookie = login.cookie("nanpanel_session");
    csrfToken = login.path("csrfToken");

    gatewayHostId = insertHost("topology-gateway-asset");
    applicationHostId = insertHost("topology-application-asset");
    store.transaction(connection -> {
      AuthStore.update(connection, "DELETE FROM topology_revision WHERE generation > 0");
      AuthStore.update(connection,
          "UPDATE topology_revision SET snapshot='{\"nodes\":[],\"links\":[]}',"
              + "updated_by=NULL,updated_at=0 WHERE generation=0");
      AuthStore.update(connection,
          "UPDATE topology_head SET revision_id='00000000-0000-0000-0000-000000000001',"
              + "generation=0,snapshot='{\"nodes\":[],\"links\":[]}',"
              + "updated_by=NULL,updated_at=0 WHERE id=1");
      return null;
    });
  }

  @Test
  void savesAndReloadsHostGatewayAndObservedRouterWithRevisionGuards() {
    UUID gatewayNodeId = UUID.randomUUID();
    UUID hostNodeId = UUID.randomUUID();
    UUID routerNodeId = UUID.randomUUID();
    UUID linkId = UUID.randomUUID();
    getDraft();
    String initialEtag = lastEtag;

    Map<String, Object> gateway = node(gatewayNodeId, "GATEWAY", "Public Gateway",
        100, 100, gatewayHostId, null, 0, 1, List.of(port("out-1", "OUTPUT")));
    Map<String, Object> host = node(hostNodeId, "HOST", "k3s-1", 420, 100,
        applicationHostId, null, 1, 1,
        List.of(port("in-1", "INPUT"), port("out-1", "OUTPUT")));
    Map<String, Object> router = node(routerNodeId, "ROUTER", "Observed Router 10.0.0.1",
        740, 100, null, "10.0.0.1", 1, 1,
        List.of(port("in-1", "INPUT"), port("out-1", "OUTPUT")));
    router.put("providerId", "p6-linux-network");
    Map<String, Object> link = Map.of("id", linkId.toString(),
        "sourceNodeId", gatewayNodeId.toString(), "sourcePort", "out-1",
        "targetNodeId", hostNodeId.toString(), "targetPort", "in-1",
        "layer", "L4", "protocol", "TCP", "status", "UNVERIFIED");
    Map<String, Object> payload = Map.of("nodes", List.of(gateway, host, router),
        "links", List.of(link));

    var save = given().cookie("nanpanel_session", sessionCookie)
        .header("X-CSRF-Token", csrfToken).header("If-Match", initialEtag)
        .contentType("application/json").body(payload)
        .put("/api/v1/topology/draft").then().statusCode(200).extract();
    String savedEtag = save.header("ETag");
    assertNotEquals(initialEtag, savedEtag);
    JsonNode saved = save.as(JsonNode.class);
    assertEquals(1, saved.path("generation").asInt());
    assertEquals(userId, saved.path("updatedBy").asText());

    JsonNode reloaded = getDraft();
    assertEquals(savedEtag, lastEtag);
    assertEquals(3, reloaded.path("nodes").size());
    assertEquals(gatewayHostId.toString(), findNode(reloaded, gatewayNodeId)
        .path("assetId").asText());
    assertEquals(applicationHostId.toString(), findNode(reloaded, hostNodeId)
        .path("assetId").asText());
    assertEquals("10.0.0.1", findNode(reloaded, routerNodeId).path("ipAddress").asText());
    assertEquals("p6-linux-network", findNode(reloaded, routerNodeId).path("providerId").asText());
    assertEquals("PENDING", reloaded.path("links").get(0).path("state").asText());
    assertEquals(linkId.toString(), reloaded.path("links").get(0).path("id").asText());
    JsonNode revisions = given().cookie("nanpanel_session", sessionCookie)
        .get("/api/v1/topology/revisions").then().statusCode(200).extract().as(JsonNode.class);
    assertEquals(saved.path("revisionId").asText(),
        revisions.path("items").get(0).path("revisionId").asText());
    assertEquals(3, revisions.path("items").get(0).path("nodeCount").asInt());
    assertEquals(1, revisions.path("items").get(0).path("linkCount").asInt());

    given().cookie("nanpanel_session", sessionCookie)
        .header("X-CSRF-Token", csrfToken).header("If-Match", initialEtag)
        .contentType("application/json").body(payload)
        .put("/api/v1/topology/draft").then().statusCode(412)
        .body("code", org.hamcrest.Matchers.equalTo("PRECONDITION_FAILED"));

    Map<String, Object> invalidHost = node(UUID.randomUUID(), "HOST", "Missing asset",
        100, 300, UUID.randomUUID(), null, 1, 1,
        List.of(port("in-1", "INPUT"), port("out-1", "OUTPUT")));
    given().cookie("nanpanel_session", sessionCookie)
        .header("X-CSRF-Token", csrfToken).header("If-Match", savedEtag)
        .contentType("application/json")
        .body(Map.of("nodes", List.of(invalidHost), "links", List.of()))
        .put("/api/v1/topology/draft").then().statusCode(422)
        .body("code", org.hamcrest.Matchers.equalTo("INVALID_TOPOLOGY"));

    JsonNode afterRejectedWrite = getDraft();
    assertEquals(1, afterRejectedWrite.path("generation").asInt());
    assertTrue(afterRejectedWrite.path("nodes").has(0));
  }

  @Test
  void acceptsFirewallAndPacketTapOnlyWhenTheirPortCapabilitiesAreConsistent() {
    getDraft();
    UUID firewallId = UUID.randomUUID();
    UUID packetTapId = UUID.randomUUID();
    Map<String, Object> firewall = node(firewallId, "FIREWALL", "L3 Firewall",
        100, 100, null, null, 1, 1, List.of(
            port("in-1", "INPUT", "L3", List.of("IP")),
            port("out-1", "OUTPUT", "L3", List.of("IP"))));
    List<Map<String, Object>> packetTapPorts = List.of(
        port("in-1", "INPUT", "L2", List.of("ETHERNET")),
        port("out-1", "OUTPUT", "L2", List.of("ETHERNET")),
        port("out-2", "OUTPUT", "L2", List.of("ETHERNET")));
    Map<String, Object> packetTap = node(packetTapId, "PACKET_TAP", "Packet Tap",
        420, 100, null, null, 1, 2, packetTapPorts);
    var save = given().cookie("nanpanel_session", sessionCookie)
        .header("X-CSRF-Token", csrfToken).header("If-Match", lastEtag)
        .contentType("application/json")
        .body(Map.of("nodes", List.of(firewall, packetTap), "links", List.of()))
        .put("/api/v1/topology/draft").then().statusCode(200).extract();
    String savedEtag = save.header("ETag");
    assertNotEquals(lastEtag, savedEtag);

    Map<String, Object> invalidTap = new LinkedHashMap<>(packetTap);
    List<Map<String, Object>> invalidPorts = new java.util.ArrayList<>(packetTapPorts);
    invalidPorts.set(2, port("out-2", "OUTPUT", "L3", List.of("IP")));
    invalidTap.put("ports", invalidPorts);
    given().cookie("nanpanel_session", sessionCookie)
        .header("X-CSRF-Token", csrfToken).header("If-Match", savedEtag)
        .contentType("application/json")
        .body(Map.of("nodes", List.of(invalidTap), "links", List.of()))
        .put("/api/v1/topology/draft").then().statusCode(422)
        .body("code", org.hamcrest.Matchers.equalTo("INVALID_TOPOLOGY"));
    assertEquals(1, getDraft().path("generation").asInt());
    assertEquals(savedEtag, lastEtag);
  }

  private String lastEtag;

  private JsonNode getDraft() {
    var response = given().cookie("nanpanel_session", sessionCookie)
        .get("/api/v1/topology/draft").then().statusCode(200).extract();
    lastEtag = response.header("ETag");
    return response.as(JsonNode.class);
  }

  private UUID insertHost(String prefix) {
    UUID id = UUID.randomUUID();
    store.transaction(connection -> {
      AuthStore.update(connection,
          "INSERT INTO host (id,hostname,capabilities,generation,observed_generation,status) "
              + "VALUES (?,?, '[]',1,0,'UNKNOWN')",
          id, prefix + "-" + id.toString().substring(0, 8));
      return null;
    });
    return id;
  }

  private static Map<String, Object> node(UUID id, String kind, String name, double x, double y,
                                          UUID assetId, String ipAddress, int inputs, int outputs,
                                          List<Map<String, Object>> ports) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("id", id.toString());
    value.put("kind", kind);
    value.put("name", name);
    value.put("position", Map.of("x", x, "y", y));
    value.put("inputCount", inputs);
    value.put("outputCount", outputs);
    value.put("ports", ports);
    value.put("parentId", null);
    value.put("assetId", assetId == null ? null : assetId.toString());
    value.put("clusterId", null);
    value.put("ipAddress", ipAddress);
    return value;
  }

  private static Map<String, Object> port(String id, String direction) {
    return Map.of("id", id, "direction", direction, "layer", "L4",
        "protocols", List.of("TCP", "UDP"));
  }

  private static Map<String, Object> port(String id, String direction, String layer,
                                           List<String> protocols) {
    return Map.of("id", id, "direction", direction, "layer", layer,
        "protocols", protocols);
  }

  private static JsonNode findNode(JsonNode draft, UUID id) {
    for (JsonNode node : draft.path("nodes")) {
      if (id.toString().equals(node.path("id").asText())) return node;
    }
    throw new AssertionError("Topology draft did not contain node " + id);
  }
}
