package io.nanpanel.core;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
@QuarkusTest
class SystemResourceTest {
  @Inject AuthStore store;
  @Inject KubernetesClusterRegistryService kubernetesClusters;

  private static final class EchoShellCommand implements org.apache.sshd.server.command.Command {
    private java.io.InputStream input;
    private java.io.OutputStream output;
    private org.apache.sshd.server.ExitCallback exitCallback;
    private Thread runner;

    @Override public void setInputStream(java.io.InputStream value) { input = value; }
    @Override public void setOutputStream(java.io.OutputStream value) { output = value; }
    @Override public void setErrorStream(java.io.OutputStream value) { }
    @Override public void setExitCallback(org.apache.sshd.server.ExitCallback value) {
      exitCallback = value;
    }

    @Override
    public void start(org.apache.sshd.server.channel.ChannelSession channel,
        org.apache.sshd.server.Environment environment) {
      runner = Thread.ofVirtual().start(() -> {
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
            input, StandardCharsets.UTF_8))) {
          String line;
          while ((line = reader.readLine()) != null) {
            if (line.startsWith("echo ")) {
              output.write((line.substring(5) + "\r\n").getBytes(StandardCharsets.UTF_8));
              output.flush();
            }
          }
          exitCallback.onExit(0, "", false);
        } catch (java.io.IOException failure) {
          exitCallback.onExit(1, failure.getMessage(), false);
        }
      });
    }

    @Override
    public void destroy(org.apache.sshd.server.channel.ChannelSession channel) {
      if (runner != null) runner.interrupt();
    }
  }

  @Test
  void gatewayLeaseFencesCompetingAndExpiredOwners() {
    String firstOwner = "b0fb3e0a-f38b-4abc-83ea-1c4eb8887a10";
    String secondOwner = "931fb05f-c9d2-44e6-b24e-951c223f5c6c";
    store.transaction(c -> {
      AuthStore.update(c,
          "UPDATE agent_gateway_fencing SET owner_id=NULL,epoch=0,lease_until=? WHERE id=1",
          java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
      return null;
    });
    var initial = Map.of("ownerId", firstOwner, "clusterId", "test-cluster", "epoch", 0);
    Number firstEpoch = given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(initial)
        .post("/internal/agent-relay/lease/acquire")
        .then().statusCode(200).extract().path("epoch");
    Number sameOwnerEpoch = given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(initial)
        .post("/internal/agent-relay/lease/acquire")
        .then().statusCode(200).extract().path("epoch");
    assertEquals(firstEpoch.longValue(), sameOwnerEpoch.longValue());

    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.of("ownerId", secondOwner, "clusterId", "test-cluster", "epoch", 0))
        .post("/internal/agent-relay/lease/acquire")
        .then().statusCode(409).body("code", equalTo("RELAY_LEASE_HELD"));
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.of("ownerId", firstOwner, "clusterId", "test-cluster",
            "epoch", firstEpoch.intValue()))
        .post("/internal/agent-relay/lease/renew")
        .then().statusCode(200).body("epoch", equalTo(firstEpoch.intValue()));

    store.transaction(c -> {
      AuthStore.update(c, "UPDATE agent_gateway_fencing SET lease_until=? WHERE id=1",
          java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
      return null;
    });
    Number nextEpoch = given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.of("ownerId", secondOwner, "clusterId", "test-cluster", "epoch", 0))
        .post("/internal/agent-relay/lease/acquire")
        .then().statusCode(200).extract().path("epoch");
    assertEquals(firstEpoch.longValue() + 1, nextEpoch.longValue());
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.of("ownerId", firstOwner, "clusterId", "test-cluster",
            "epoch", firstEpoch.intValue()))
        .post("/internal/agent-relay/lease/renew")
        .then().statusCode(409).body("code", equalTo("RELAY_LEASE_LOST"));
  }

  @Test
  void relayStatusRequiresTrustedEndpointAndRejectsStaleEpochs() {
    String owner = "82791d95-b2d6-469e-a1d1-13550862d956";
    store.transaction(c -> {
      AuthStore.update(c,
          "UPDATE agent_gateway_fencing SET owner_id=NULL,epoch=1,lease_until=? WHERE id=1",
          java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
      AuthStore.update(c, "DELETE FROM agent_relay_status WHERE relay_id=?", "relay-a");
      return null;
    });
    Number epoch = given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.of("ownerId", owner, "clusterId", "test-cluster", "epoch", 0))
        .post("/internal/agent-relay/lease/acquire")
        .then().statusCode(200).extract().path("epoch");
    Map<String, Object> report = Map.of(
        "relayId", "relay-a", "clusterId", "test-cluster", "epoch", epoch.intValue(),
        "endpoint", "https://relay-a.example", "healthy", true);
    given().contentType("application/json").body(report)
        .post("/internal/agent-relay/status").then().statusCode(401);
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.of("relayId", "relay-a", "clusterId", "test-cluster", "epoch", epoch.intValue(),
            "endpoint", "https://untrusted.example", "healthy", true))
        .post("/internal/agent-relay/status").then().statusCode(400);
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.of("relayId", "relay-a", "clusterId", "other-cluster", "epoch", epoch.intValue(),
            "endpoint", "https://relay-a.example", "healthy", true))
        .post("/internal/agent-relay/status").then().statusCode(400);
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(report)
        .post("/internal/agent-relay/status").then().statusCode(204);
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(report)
        .post("/internal/agent-relay/status").then().statusCode(204);
    var burstCount = store.transaction(c -> AuthStore.query(c,
        "SELECT healthy_count FROM agent_relay_status WHERE relay_id=?", "relay-a").getFirst());
    assertEquals(1, ((Number)burstCount.get("healthy_count")).intValue());
    store.transaction(c -> {
      AuthStore.update(c, "UPDATE agent_relay_status SET last_counted_at=? WHERE relay_id=?",
          java.sql.Timestamp.from(Instant.now().minusSeconds(10)), "relay-a");
      return null;
    });
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(report)
        .post("/internal/agent-relay/status").then().statusCode(204);
    store.transaction(c -> {
      AuthStore.update(c, "UPDATE agent_relay_status SET last_counted_at=? WHERE relay_id=?",
          java.sql.Timestamp.from(Instant.now().minusSeconds(10)), "relay-a");
      return null;
    });
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(report)
        .post("/internal/agent-relay/status").then().statusCode(204);
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.of("relayId", "relay-a", "clusterId", "test-cluster", "epoch", epoch.intValue() - 1,
            "endpoint", "https://relay-b.example", "healthy", false))
        .post("/internal/agent-relay/status").then().statusCode(409)
        .body("code", equalTo("RELAY_LEASE_LOST"));
    var stored = store.transaction(c -> AuthStore.query(c,
        "SELECT cluster_id,target_name,epoch,healthy,healthy_count FROM agent_relay_status WHERE relay_id=?",
        "relay-a").getFirst());
    assertEquals("test-cluster", stored.get("cluster_id"));
    assertEquals("relay-a.example", stored.get("target_name"));
    assertEquals(epoch.longValue(), ((Number)stored.get("epoch")).longValue());
    assertEquals(true, stored.get("healthy"));
    assertEquals(3, ((Number)stored.get("healthy_count")).intValue());

    Map<String, Object> failure = Map.of(
        "relayId", "relay-a", "clusterId", "test-cluster", "epoch", epoch.intValue(),
        "endpoint", "https://relay-a.example", "healthy", false);
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(failure)
        .post("/internal/agent-relay/status").then().statusCode(204);
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(failure)
        .post("/internal/agent-relay/status").then().statusCode(204);
    var burstFailures = store.transaction(c -> AuthStore.query(c,
        "SELECT unhealthy_count FROM agent_relay_status WHERE relay_id=?", "relay-a").getFirst());
    assertEquals(1, ((Number)burstFailures.get("unhealthy_count")).intValue());
    for (int expected = 2; expected <= 3; expected++) {
      store.transaction(c -> {
        AuthStore.update(c, "UPDATE agent_relay_status SET last_counted_at=? WHERE relay_id=?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(10)), "relay-a");
        return null;
      });
      given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
          .contentType("application/json").body(failure)
          .post("/internal/agent-relay/status").then().statusCode(204);
      int failures = store.transaction(c -> ((Number)AuthStore.query(c,
          "SELECT unhealthy_count FROM agent_relay_status WHERE relay_id=?", "relay-a")
          .getFirst().get("unhealthy_count")).intValue());
      assertEquals(expected, failures);
    }
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(report)
        .post("/internal/agent-relay/status").then().statusCode(204);
    var recovered = store.transaction(c -> AuthStore.query(c,
        "SELECT healthy_count,unhealthy_count FROM agent_relay_status WHERE relay_id=?", "relay-a")
        .getFirst());
    assertEquals(1, ((Number)recovered.get("healthy_count")).intValue());
    assertEquals(0, ((Number)recovered.get("unhealthy_count")).intValue());
  }

  @Test
  void relayEndpointConfigurationAcceptsOnlyExplicitHttpsOrigins() {
    assertEquals(java.util.List.of(), AgentEnrollmentResource.parseRelayEndpoints("disabled"));
    assertEquals(java.util.List.of("https://relay-a.example", "https://relay-b.example:9443"),
        AgentEnrollmentResource.parseRelayEndpoints(
            " HTTPS://Relay-A.Example:443/ , https://relay-b.example:9443 "));
    assertThrows(IllegalArgumentException.class,
        () -> AgentEnrollmentResource.parseRelayEndpoints("https://relay-a.example"));
    assertThrows(IllegalArgumentException.class,
        () -> AgentEnrollmentResource.parseRelayEndpoints(
            "https://relay-a.example,https://RELAY-A.example:443/"));
    assertThrows(IllegalArgumentException.class,
        () -> AgentEnrollmentResource.parseRelayEndpoints(
            "https://relay-a.example/path,https://relay-b.example"));
  }

  @Test
  void sessionLifecycle() throws Exception {
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
    assertTrue(login.header("Set-Cookie").contains("Max-Age=86400"));
    given().cookie("nanpanel_session", active)
        .header("X-CSRF-Token", token)
        .contentType("application/json")
        .body(Map.of("name", "blocked-before-password-change",
            "scopes", java.util.List.of("kubernetes:inventory:read"),
            "expiresInDays", 30))
        .post("/api/v1/auth/api-keys")
        .then().statusCode(403)
        .body("code", equalTo("PASSWORD_CHANGE_REQUIRED"));
    var persistedSession = store.transaction(c -> AuthStore.query(
        c, "SELECT created_at,absolute_expires FROM web_session WHERE token_hash=?",
        AuthStore.digest(active)).getFirst());
    assertEquals(24L * 60 * 60 * 1000,
        ((Number)persistedSession.get("absolute_expires")).longValue() -
            ((Number)persistedSession.get("created_at")).longValue());
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
    UUID resourceClusterA = UUID.randomUUID(), resourceClusterB = UUID.randomUUID();
    UUID managedDeploymentId = UUID.randomUUID();
    Instant resourceFixtureAt = Instant.now();
    String resourceActor = restored.path("user.id").toString();
    store.transaction(c -> {
      for (UUID clusterId : java.util.List.of(resourceClusterA, resourceClusterB)) {
        AuthStore.update(c, "INSERT INTO kubernetes_cluster (id,name,kind,endpoint,context_name,"
            + "encrypted_kubeconfig,health_status,observed_at,imported_by,created_at,updated_at) "
            + "VALUES (?,?, 'K3S', ?, 'test-context', ?, 'HEALTHY', ?, ?, ?, ?)", clusterId,
            "resource-scope-test-" + clusterId, "https://kubernetes.example.test",
            new byte[] {1}, java.sql.Timestamp.from(resourceFixtureAt), resourceActor,
            java.sql.Timestamp.from(resourceFixtureAt), java.sql.Timestamp.from(resourceFixtureAt));
      }
      AuthStore.update(c, "INSERT INTO kubernetes_managed_deployment (cluster_id,namespace,"
          + "deployment_name,deployment_uid,ownership_token,created_by,created_at,updated_at) "
          + "VALUES (?, 'default', 'web', ?, ?, ?, ?, ?)", resourceClusterA,
          managedDeploymentId.toString(), UUID.randomUUID().toString(), resourceActor,
          java.sql.Timestamp.from(resourceFixtureAt), java.sql.Timestamp.from(resourceFixtureAt));
      return null;
    });
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("name", "web", "image", "nginx:1.27", "replicas", 21,
            "containerPort", 8080))
        .post("/api/v1/kubernetes/clusters/" + resourceClusterA
            + "/namespaces/default/deployments")
        .then().statusCode(422).body("code", equalTo("KUBERNETES_RESOURCE_INVALID"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("replicas", 1))
        .put("/api/v1/kubernetes/clusters/" + resourceClusterB
            + "/namespaces/default/deployments/web/scale")
        .then().statusCode(404).body("code", equalTo("KUBERNETES_RESOURCE_NOT_MANAGED"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .delete("/api/v1/kubernetes/clusters/" + resourceClusterB
            + "/namespaces/default/deployments/web")
        .then().statusCode(404).body("code", equalTo("KUBERNETES_RESOURCE_NOT_MANAGED"));
    var createdReadKey = given()
        .cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("name", "p2-inventory-read",
            "scopes", java.util.List.of("kubernetes:inventory:read"),
            "expiresInDays", 30))
        .post("/api/v1/auth/api-keys")
        .then().statusCode(201).extract();
    String readKey = createdReadKey.path("token");
    String readKeyId = createdReadKey.path("item.id");
    assertTrue(readKey.matches("npk_[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}"));
    assertNull(createdReadKey.path("item.token"));
    var storedReadKey = store.transaction(c -> AuthStore.query(c,
        "SELECT token_hash,scopes,last_used_at FROM api_key WHERE id=?", readKeyId)
        .getFirst());
    assertEquals(AuthStore.digest(readKey), storedReadKey.get("token_hash"));
    assertEquals("kubernetes:inventory:read", storedReadKey.get("scopes"));
    assertNull(storedReadKey.get("last_used_at"));
    given().header("Authorization", "Bearer " + readKey)
        .get("/api/v1/kubernetes/service-inventory")
        .then().statusCode(503).body("code", equalTo("KUBERNETES_DISCOVERY_DISABLED"));
    given().header("Authorization", "Bearer " + readKey)
        .get("/api/v1/kubernetes/service-probes")
        .then().statusCode(503).body("code", equalTo("KUBERNETES_DISCOVERY_DISABLED"));
    given().header("Authorization", "Bearer " + readKey)
        .get("/api/v1/system")
        .then().statusCode(403).body("code", equalTo("API_KEY_SCOPE_REJECTED"));
    given().header("Authorization", "Bearer " + readKey)
        .post("/api/v1/kubernetes/service-inventory/refresh")
        .then().statusCode(403).body("code", equalTo("API_KEY_SCOPE_REJECTED"));
    var usedReadKey = store.transaction(c -> AuthStore.query(c,
        "SELECT last_used_at FROM api_key WHERE id=?", readKeyId).getFirst());
    assertNotNull(usedReadKey.get("last_used_at"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .delete("/api/v1/auth/api-keys/" + readKeyId)
        .then().statusCode(204);
    given().header("Authorization", "Bearer " + readKey)
        .get("/api/v1/kubernetes/service-inventory")
        .then().statusCode(401);

    var createdRefreshKey = given()
        .cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("name", "p2-inventory-refresh",
            "scopes", java.util.List.of("kubernetes:inventory:read",
                "kubernetes:inventory:refresh"),
            "expiresInDays", 30))
        .post("/api/v1/auth/api-keys")
        .then().statusCode(201).extract();
    String refreshKey = createdRefreshKey.path("token");
    String refreshKeyId = createdRefreshKey.path("item.id");
    given().header("Authorization", "Bearer " + refreshKey)
        .post("/api/v1/kubernetes/service-inventory/refresh")
        .then().statusCode(503).body("code", equalTo("KUBERNETES_DISCOVERY_DISABLED"));
    store.transaction(c -> {
      AuthStore.update(c,
          "UPDATE api_key SET expires_at=created_at+1 WHERE id=?", refreshKeyId);
      return null;
    });
    given().header("Authorization", "Bearer " + refreshKey)
        .get("/api/v1/kubernetes/service-probes")
        .then().statusCode(401);
    given()
        .cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/system")
        .then()
        .statusCode(200);
    given().get("/api/v1/network-providers").then().statusCode(401);
    given().get("/api/v1/plugins").then().statusCode(401);
    given().get("/api/v1/config-translators").then().statusCode(401);
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/network-providers")
        .then().statusCode(200).body("size()", equalTo(0));
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/plugins")
        .then().statusCode(200).body("size()", equalTo(0));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json").body(Map.of("enabled", false))
        .put("/api/v1/plugins/not-installed/enabled")
        .then().statusCode(404).body("code", equalTo("PLUGIN_UNAVAILABLE"));
    var topology = given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/topology/draft")
        .then().statusCode(200).extract();
    String topologyEtag = topology.header("ETag");
    assertEquals(0L, ((Number)topology.path("generation")).longValue());
    assertEquals("00000000-0000-0000-0000-000000000001", topology.path("revisionId"));
    String topologyWrite = """
        {"nodes":[
          {"id":"10000000-0000-0000-0000-000000000001","kind":"GATEWAY","name":"入口","position":{"x":0,"y":0},"inputCount":0,"outputCount":1,"ports":[{"id":"out-1","direction":"OUTPUT","layer":"L4","protocols":["TCP","UDP"]}]},
          {"id":"10000000-0000-0000-0000-000000000002","kind":"SERVICE","name":"服务","position":{"x":300,"y":0},"inputCount":1,"outputCount":0,"ports":[{"id":"in-1","direction":"INPUT","layer":"L4","protocols":["TCP"]}]}
        ,{"id":"10000000-0000-0000-0000-000000000003","kind":"FOLDER","name":"K3s","position":{"x":600,"y":0},"inputCount":1,"outputCount":1,"ports":[{"id":"in-1","direction":"INPUT","layer":"L4","protocols":["TCP"]},{"id":"out-1","direction":"OUTPUT","layer":"L4","protocols":["TCP"]}],"parentId":null,"assetId":null}
        ,{"id":"10000000-0000-0000-0000-000000000004","kind":"SERVICE","name":"Pod Service","position":{"x":0,"y":0},"inputCount":1,"outputCount":0,"ports":[{"id":"in-1","direction":"INPUT","layer":"L4","protocols":["TCP"]}],"parentId":"10000000-0000-0000-0000-000000000003","assetId":null}
        ,{"id":"10000000-0000-0000-0000-000000000005","kind":"ROUTER","name":"Default Router","position":{"x":300,"y":0},"inputCount":1,"outputCount":1,"ports":[{"id":"in-1","direction":"INPUT","layer":"L4","protocols":["TCP"]},{"id":"out-1","direction":"OUTPUT","layer":"L4","protocols":["TCP"]}],"ipAddress":"10.0.0.1"}
        ],"links":[{"id":"10000000-0000-0000-0000-000000000003","sourceNodeId":"10000000-0000-0000-0000-000000000001","sourcePort":"out-1","targetNodeId":"10000000-0000-0000-0000-000000000002","targetPort":"in-1","layer":"L4","protocol":"TCP","status":"UNVERIFIED"}]}
        """;
    var topologySaved = given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("If-Match", topologyEtag).contentType("application/json").body(topologyWrite)
        .put("/api/v1/topology/draft").then().statusCode(200).extract();
    String topologyRevisionId = topologySaved.path("revisionId");
    assertEquals(1L, ((Number)topologySaved.path("generation")).longValue());
    assertNotEquals(topologyEtag, topologySaved.header("ETag"));
    assertEquals("PENDING", topologySaved.path("links[0].state"));
    assertEquals("UNVERIFIED", topologySaved.path("links[0].status"));
    assertEquals("10000000-0000-0000-0000-000000000003",
        topologySaved.path("nodes[3].parentId"));
    assertEquals("10.0.0.1", topologySaved.path("nodes[4].ipAddress"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("If-Match", topologyEtag).contentType("application/json").body(topologyWrite)
        .put("/api/v1/topology/draft").then().statusCode(412);
    String crossScope = topologyWrite.replace(
        "\"targetNodeId\":\"10000000-0000-0000-0000-000000000002\"",
        "\"targetNodeId\":\"10000000-0000-0000-0000-000000000004\"");
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("If-Match", topologySaved.header("ETag"))
        .contentType("application/json").body(crossScope)
        .put("/api/v1/topology/draft").then().statusCode(422)
        .body("code", equalTo("INVALID_TOPOLOGY"));
    String invalidRouterAddress = topologyWrite.replace("10.0.0.1", "not-an-ip");
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("If-Match", topologySaved.header("ETag"))
        .contentType("application/json").body(invalidRouterAddress)
        .put("/api/v1/topology/draft").then().statusCode(422)
        .body("code", equalTo("INVALID_TOPOLOGY"));
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/topology/revisions?limit=10")
        .then().statusCode(200).body("items.size()", equalTo(2));
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/topology/revisions/" + topologyRevisionId)
        .then().statusCode(200).body("generation", equalTo(1))
        .body("links[0].protocol", equalTo("TCP"));
    String incompatible = topologyWrite.replace("\"protocol\":\"TCP\"", "\"protocol\":\"UDP\"");
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("If-Match", topologySaved.header("ETag"))
        .contentType("application/json").body(incompatible)
        .put("/api/v1/topology/draft").then().statusCode(422)
        .body("code", equalTo("INVALID_TOPOLOGY"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("desiredSnapshot",
            "{\"groupName\":\"nanpanel-managed-app\",\"generation\":1,\"peerIds\":[]}"))
        .post("/api/v1/network-providers/netbird-groups/plans")
        .then().statusCode(404).body("code", equalTo("PROVIDER_UNAVAILABLE"));
    given().get("/api/v1/kubernetes/service-inventory")
        .then().statusCode(401);
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/kubernetes/service-inventory")
        .then().statusCode(503)
        .body("code", equalTo("KUBERNETES_DISCOVERY_DISABLED"));
    given().get("/api/v1/kubernetes/service-probes")
        .then().statusCode(401);
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/kubernetes/service-probes")
        .then().statusCode(503)
        .body("code", equalTo("KUBERNETES_DISCOVERY_DISABLED"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .post("/api/v1/kubernetes/service-inventory/refresh")
        .then().statusCode(503)
        .body("code", equalTo("KUBERNETES_DISCOVERY_DISABLED"));
    given().get("/api/v1/hosts/00000000-0000-0000-0000-000000000001/services")
        .then().statusCode(401);
    var installation = given().get("/api/v1/agent-installation")
        .then().statusCode(200).extract();
    String controllerFingerprint = installation.path("controllerFingerprint");
    assertTrue(controllerFingerprint.matches("[a-f0-9]{64}"));
    assertEquals(java.util.List.of("https://relay-a.example", "https://relay-b.example"),
        installation.path("relayEndpoints"));
    var enrollment = given()
        .cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("hostname", "panel-test-host"))
        .post("/api/v1/agent-enrollments")
        .then().statusCode(201).extract();
    String hostId = enrollment.path("hostId");
    String agentId = enrollment.path("agentId");
    String enrollmentToken = enrollment.path("token");
    String gatewayAssetTopology = topologyWrite.replace(
        "\"kind\":\"GATEWAY\",\"name\":\"入口\"",
        "\"kind\":\"GATEWAY\",\"name\":\"入口\",\"assetId\":\"" + hostId + "\"");
    var gatewayAssetSaved = given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("If-Match", topologySaved.header("ETag"))
        .contentType("application/json").body(gatewayAssetTopology)
        .put("/api/v1/topology/draft").then().statusCode(200).extract();
    assertEquals(hostId, gatewayAssetSaved.path("nodes[0].assetId"));
    verifyBastionTerminal(hostId, restoredCookie, restored.path("csrfToken"));
    assertEquals(controllerFingerprint, enrollment.path("controllerFingerprint"));
    assertEquals(30444, (int)enrollment.path("gatewayPort"));
    assertEquals(java.util.List.of("https://relay-a.example", "https://relay-b.example"),
        enrollment.path("relayEndpoints"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", "invalid")
        .contentType("application/json")
        .body(Map.of("hostname", "csrf-rejected-host"))
        .post("/api/v1/agent-enrollments")
        .then().statusCode(403);

    String mismatchedCsr = createCsr(UUID.randomUUID());
    given().header("Authorization", "Bearer " + enrollmentToken)
        .contentType("application/json")
        .body(Map.of("csrPem", mismatchedCsr))
        .post("/api/v1/agent-enrollments/redeem")
        .then().statusCode(400);

    String csr = createCsr(UUID.fromString(agentId));
    var redemption = given().header("Authorization", "Bearer " + enrollmentToken)
        .contentType("application/json")
        .body(Map.of("csrPem", csr))
        .post("/api/v1/agent-enrollments/redeem")
        .then().statusCode(200).extract();
    String certificate = redemption.path("certificatePem");
    String certificateFingerprint = AgentCertificateAuthority.fingerprint(certificate);
    given().header("Authorization", "Bearer " + enrollmentToken)
        .contentType("application/json")
        .body(Map.of("csrPem", csr))
        .post("/api/v1/agent-enrollments/redeem")
        .then().statusCode(401);

    var heartbeat = new java.util.HashMap<String, Object>();
    heartbeat.put("agentId", agentId);
    heartbeat.put("version", "0.1.0-test");
    heartbeat.put("certificateFingerprint", certificateFingerprint);
    heartbeat.put("hostname", "panel-test-host-real");
    heartbeat.put("os", "Ubuntu 24.04");
    heartbeat.put("arch", "x86_64");
    var heartbeatCapabilities = new java.util.ArrayList<>(java.util.List.of(
        "host.inventory", "agent.heartbeat", "agent.self_update", "systemd.read",
        "systemd.restart", "systemd.journal.read", "package.install", "file.write.protected",
        "container.list", "container.logs", "container.start", "container.stop",
        "container.deploy", "network.firewall.expose", "k3s.preflight", "k3s.install",
        "k3s.upgrade",
        "network.inventory",
        "network.overlay.observe", "network.overlay.peer_paths.observe"));
    heartbeat.put("capabilities", heartbeatCapabilities);
    heartbeat.put("helperStatus", "AVAILABLE");
    heartbeat.put("systemdObservedAtEpochMs", System.currentTimeMillis());
    heartbeat.put("networkObservedAtEpochMs", System.currentTimeMillis());
    heartbeat.put("netbirdOverlayObservedAtEpochMs", System.currentTimeMillis());
    heartbeat.put("netbirdOverlay", Map.of(
        "peerCount", 3, "connectedPeerCount", 2, "p2pPeerCount", 1,
        "relayedPeerCount", 1));
    heartbeat.put("netbirdPeerPathsObservedAtEpochMs", System.currentTimeMillis());
    heartbeat.put("netbirdPeerPaths", java.util.List.of(
        Map.of("peerName", "api-a.example.test", "status", "CONNECTED",
            "connectionType", "P2P"),
        Map.of("peerName", "db-b.example.test", "status", "CONNECTED",
            "connectionType", "RELAY")));
    heartbeat.put("networkInterfaces", java.util.List.of(Map.of(
        "name", "ens18", "macAddress", "02:00:00:00:00:18", "mtu", 1500,
        "state", "UP", "addresses", java.util.List.of(Map.of(
            "family", "inet", "address", "192.0.2.10", "prefixLength", 24,
            "scope", "global")))));
    heartbeat.put("networkRoutes", java.util.List.of(Map.of(
        "destination", "default", "gateway", "192.0.2.1", "interfaceName", "ens18",
        "protocol", "static", "scope", "", "routeType", "unicast", "table", "main",
        "metric", 100)));
    heartbeat.put("systemdServices", java.util.List.of(Map.of(
        "unitName", "ssh.service", "displayName", "OpenSSH server",
        "activeState", "ACTIVE", "subState", "running",
        "restartAllowed", false, "journalReadAllowed", false)));
    var forgedHeartbeat = new java.util.HashMap<>(heartbeat);
    forgedHeartbeat.put("certificateFingerprint", "0".repeat(64));
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(forgedHeartbeat)
        .post("/internal/agent-heartbeats")
        .then().statusCode(403);
    var inconsistentOverlayHeartbeat = new java.util.HashMap<>(heartbeat);
    inconsistentOverlayHeartbeat.put("netbirdOverlay", Map.of(
        "peerCount", 3, "connectedPeerCount", 2, "p2pPeerCount", 2,
        "relayedPeerCount", 1));
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(inconsistentOverlayHeartbeat)
        .post("/internal/agent-heartbeats")
        .then().statusCode(400).body("code", equalTo("INVALID_HEARTBEAT"));
    var unadvertisedOverlayHeartbeat = new java.util.HashMap<>(heartbeat);
    unadvertisedOverlayHeartbeat.put("capabilities", java.util.List.of(
        "host.inventory", "agent.heartbeat", "agent.self_update", "systemd.read",
        "systemd.restart", "systemd.journal.read", "package.install", "file.write.protected",
        "container.list", "container.logs", "container.start", "container.stop",
        "container.deploy", "network.firewall.expose", "k3s.preflight", "network.inventory"));
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(unadvertisedOverlayHeartbeat)
        .post("/internal/agent-heartbeats")
        .then().statusCode(400).body("code", equalTo("INVALID_HEARTBEAT"));
    var invalidPeerPathHeartbeat = new java.util.HashMap<>(heartbeat);
    invalidPeerPathHeartbeat.put("netbirdPeerPaths", java.util.List.of(
        Map.of("peerName", "100.64.0.1", "status", "CONNECTED",
            "connectionType", "P2P")));
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(invalidPeerPathHeartbeat)
        .post("/internal/agent-heartbeats")
        .then().statusCode(400).body("code", equalTo("INVALID_HEARTBEAT"));
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(heartbeat)
        .post("/internal/agent-heartbeats")
        .then().statusCode(204);
    var discoveredKubernetesHeartbeat = new java.util.HashMap<>(heartbeat);
    var discoveredCapabilities = new java.util.ArrayList<>(heartbeatCapabilities);
    discoveredCapabilities.add("kubernetes.runtime.detect");
    discoveredKubernetesHeartbeat.put("capabilities", discoveredCapabilities);
    discoveredKubernetesHeartbeat.put("kubernetesRuntime", Map.of(
        "detected", true, "kubeletRunning", true, "apiServerLocal", true,
        "runtimeKind", "K3S", "observedAtEpochMs", System.currentTimeMillis()));
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(discoveredKubernetesHeartbeat)
        .post("/internal/agent-heartbeats").then().statusCode(204);
    given().get("/api/v1/kubernetes/discoveries").then().statusCode(401);
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/kubernetes/discoveries")
        .then().statusCode(200)
        .body("items.size()", equalTo(1))
        .body("items[0].hostId", equalTo(hostId))
        .body("items[0].hostname", equalTo("panel-test-host-real"))
        .body("items[0].runtimeKind", equalTo("K3S"))
        .body("items[0].kubeletRunning", equalTo(true))
        .body("items[0].apiServerLocal", equalTo(true));
    var invalidKubernetesRuntimeHeartbeat = new java.util.HashMap<>(discoveredKubernetesHeartbeat);
    invalidKubernetesRuntimeHeartbeat.put("kubernetesRuntime", Map.of(
        "detected", false, "kubeletRunning", true, "apiServerLocal", false,
        "runtimeKind", "NONE", "observedAtEpochMs", System.currentTimeMillis()));
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(invalidKubernetesRuntimeHeartbeat)
        .post("/internal/agent-heartbeats")
        .then().statusCode(400).body("code", equalTo("INVALID_HEARTBEAT"));
    var unadvertisedKubernetesRuntimeHeartbeat = new java.util.HashMap<>(discoveredKubernetesHeartbeat);
    unadvertisedKubernetesRuntimeHeartbeat.put("capabilities", java.util.List.of(
        "host.inventory", "agent.heartbeat", "agent.self_update", "systemd.read"));
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(unadvertisedKubernetesRuntimeHeartbeat)
        .post("/internal/agent-heartbeats")
        .then().statusCode(400).body("code", equalTo("INVALID_HEARTBEAT"));
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/hosts?limit=20&search=PANEL-TEST-HOST-REAL")
        .then().statusCode(200)
        .body("items.size()", equalTo(1))
        .body("items[0].hostname", equalTo("panel-test-host-real"))
        .body("nextCursor", nullValue());
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/hosts?search=" + "x".repeat(121))
        .then().statusCode(400).body("code", equalTo("VALIDATION_FAILED"));
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/hosts/" + hostId)
        .then().statusCode(200)
        .body("hostname", equalTo("panel-test-host-real"))
        .body("status", equalTo("ONLINE"))
        .body("generation", equalTo(1))
        .body("capabilities", hasItems("host.inventory", "agent.heartbeat", "agent.self_update", "systemd.read"));
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/hosts/" + hostId + "/network-inventory")
        .then().statusCode(200)
        .body("interfaces[0].name", equalTo("ens18"))
        .body("interfaces[0].addresses[0].address", equalTo("192.0.2.10"))
        .body("routes[0].destination", equalTo("default"))
        .body("routes[0].gateway", equalTo("192.0.2.1"))
        .body("overlay.peerCount", equalTo(3))
        .body("overlay.connectedPeerCount", equalTo(2))
        .body("overlay.p2pPeerCount", equalTo(1))
        .body("overlay.relayedPeerCount", equalTo(1))
        .body("overlayPeers[0].peerName", equalTo("api-a.example.test"))
        .body("overlayPeers[0].connectionType", equalTo("P2P"))
        .body("overlayPeers[1].connectionType", equalTo("RELAY"))
        .body("overlayPeersObservedAt", notNullValue());
    UUID kubeedgeClusterId = UUID.randomUUID();
    String kubeedgeNodeUid = "edge-node-system-test";
    Instant kubeedgeObservedAt = Instant.now();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO kubernetes_cluster (id,name,kind,endpoint,context_name,"
          + "encrypted_kubeconfig,health_status,observed_at,imported_by,created_at,updated_at) "
          + "VALUES (?,?,?,?,?,?,'HEALTHY',?,?,?,?)", kubeedgeClusterId,
          "system-resource-kubeedge-test", "KUBEEDGE", "https://kubeedge.example.test",
          "test-context", new byte[] {1}, java.sql.Timestamp.from(kubeedgeObservedAt),
          restored.path("user.id").toString(), java.sql.Timestamp.from(kubeedgeObservedAt),
          java.sql.Timestamp.from(kubeedgeObservedAt));
      AuthStore.update(c, "INSERT INTO kubernetes_cluster_node (cluster_id,node_uid,node_name,"
          + "roles,kubeedge_edge,ready,kubelet_version,internal_ip,container_owner,host_id,observed_at) "
          + "VALUES (?,?,?,'edge',TRUE,TRUE,?,'192.0.2.10','KUBEEDGE',NULL,?)",
          kubeedgeClusterId, kubeedgeNodeUid, "edge-node-system-test", "v1.31.0-kubeedge.1",
          java.sql.Timestamp.from(kubeedgeObservedAt));
      return null;
    });
    kubernetesClusters.reportApiFailure(kubeedgeClusterId,
        new KubernetesClusterRegistryService.ClusterApiUnavailableException());
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/kubernetes/clusters/" + kubeedgeClusterId)
        .then().statusCode(200).body("healthStatus", equalTo("UNAVAILABLE"))
        .body("observationsStale", equalTo(true))
        .body("lastErrorCode", equalTo("API_UNAVAILABLE"))
        .body("cloudCoreStatus", equalTo("NOT_OBSERVED"))
        .body("cloudCoreObservedAt", nullValue()).body("observedAt", notNullValue());
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("hostId", hostId))
        .put("/api/v1/kubernetes/clusters/" + kubeedgeClusterId + "/nodes/"
            + kubeedgeNodeUid + "/host")
        .then().statusCode(200).body("containerOwner", equalTo("KUBEEDGE"))
        .body("edgeCoreRegistrationStatus", equalTo("REGISTERED_READY"))
        .body("edgeCoreProcessStatus", equalTo("NOT_OBSERVED"))
        .body("edgeCoreProcessObservedAt", nullValue())
        .body("hostId", equalTo(hostId));
    String conflictingNodeUid = "edge-node-system-test-conflict";
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO kubernetes_cluster_node (cluster_id,node_uid,node_name,"
          + "roles,kubeedge_edge,ready,kubelet_version,internal_ip,container_owner,host_id,observed_at) "
          + "VALUES (?,?,?,'edge',TRUE,TRUE,?,'192.0.2.11','KUBEEDGE',NULL,?)",
          kubeedgeClusterId, conflictingNodeUid, "edge-node-system-test-conflict",
          "v1.31.0-kubeedge.1", java.sql.Timestamp.from(kubeedgeObservedAt));
      return null;
    });
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("hostId", hostId))
        .put("/api/v1/kubernetes/clusters/" + kubeedgeClusterId + "/nodes/"
            + conflictingNodeUid + "/host")
        .then().statusCode(409).body("code", equalTo("KUBERNETES_NODE_MAPPING_CONFLICT"));
    Instant edgeCoreObservedAt = Instant.now();
    UUID edgeCoreServiceId = UUID.randomUUID();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO system_service_observation (id,host_id,unit_name,"
          + "display_name,active_state,sub_state,observed_at) VALUES (?,?,?,?,?,?,?)",
          edgeCoreServiceId, hostId, "edgecore.service", "KubeEdge EdgeCore", "ACTIVE",
          "running", java.sql.Timestamp.from(edgeCoreObservedAt));
      return null;
    });
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/hosts/" + hostId + "/services?limit=20")
        .then().statusCode(200)
        .body("items.find { it.unitName == 'edgecore.service' }.activeState",
            equalTo("ACTIVE"))
        .body("items.find { it.unitName == 'edgecore.service' }.observationStale",
            equalTo(false));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("hostId", hostId))
        .put("/api/v1/kubernetes/clusters/" + kubeedgeClusterId + "/nodes/"
            + kubeedgeNodeUid + "/host")
        .then().statusCode(200)
        .body("edgeCoreRegistrationStatus", equalTo("REGISTERED_READY"))
        .body("edgeCoreProcessStatus", equalTo("ACTIVE"))
        .body("edgeCoreProcessObservedAt", notNullValue());
    store.transaction(c -> {
      AuthStore.update(c, "DELETE FROM system_service_observation WHERE id=?", edgeCoreServiceId);
      return null;
    });
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", "helper-kubeedge-owned-01")
        .contentType("application/json")
        .body(Map.of("capability", "container.stop", "target", "edge-web",
            "reason", "Verify KubeEdge remains the sole EdgeCore container owner",
            "expectedGeneration", 1))
        .post("/api/v1/hosts/" + hostId + "/operations")
        .then().statusCode(409).body("code", equalTo("KUBERNETES_CONTAINER_OWNED"));
    assertEquals(0, store.transaction(c -> AuthStore.query(c,
        "SELECT id FROM operation WHERE idempotency_key=?", "helper-kubeedge-owned-01")).size());
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .delete("/api/v1/kubernetes/clusters/" + kubeedgeClusterId + "/nodes/"
            + kubeedgeNodeUid + "/host")
        .then().statusCode(409).body("code", equalTo("KUBERNETES_CONTAINER_OWNED"));
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/hosts/" + hostId + "/network-inventory")
        .then().statusCode(200).body("routes[0].gateway", equalTo("192.0.2.1"));
    store.transaction(c -> {
      KubernetesClusterRegistryService.replaceNodes(c, kubeedgeClusterId, java.util.List.of(),
          Instant.now());
      return null;
    });
    var removedNode = store.transaction(c -> AuthStore.query(c,
        "SELECT node_present,ready,host_id,container_owner FROM kubernetes_cluster_node "
            + "WHERE cluster_id=? AND node_uid=?", kubeedgeClusterId, kubeedgeNodeUid));
    assertEquals(1, removedNode.size());
    assertEquals(Boolean.FALSE, removedNode.getFirst().get("node_present"));
    assertEquals(Boolean.FALSE, removedNode.getFirst().get("ready"));
    assertEquals(hostId, String.valueOf(removedNode.getFirst().get("host_id")));
    assertEquals("KUBEEDGE", removedNode.getFirst().get("container_owner"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("hostId", hostId))
        .put("/api/v1/kubernetes/clusters/" + kubeedgeClusterId + "/nodes/"
            + kubeedgeNodeUid + "/host")
        .then().statusCode(404).body("code", equalTo("KUBERNETES_NODE_NOT_FOUND"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .delete("/api/v1/kubernetes/clusters/" + kubeedgeClusterId + "/nodes/"
            + kubeedgeNodeUid + "/host")
        .then().statusCode(204);
    assertEquals(0, store.transaction(c -> AuthStore.query(c,
        "SELECT node_uid FROM kubernetes_cluster_node WHERE cluster_id=? AND node_uid=?",
        kubeedgeClusterId, kubeedgeNodeUid)).size());
    assertEquals(1, store.transaction(c -> AuthStore.query(c,
        "SELECT id FROM host WHERE id=?", hostId)).size());
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("reason", ""))
        .post("/api/v1/hosts/" + hostId + "/ssh/probe")
        .then().statusCode(422).body("code", equalTo("VALIDATION_FAILED"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", "ssh-restart-invalid-01")
        .contentType("application/json")
        .body(Map.of("unitName", "ssh.service;id", "reason", "Reject remote command text"))
        .post("/api/v1/hosts/" + hostId + "/ssh/systemd/restart")
        .then().statusCode(422).body("code", equalTo("VALIDATION_FAILED"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", "ssh-restart-invalid-unit-02")
        .contentType("application/json")
        .body(Map.of("unitName", "ssh.service;id", "reason", "Reject invalid systemd unit syntax"))
        .post("/api/v1/hosts/" + hostId + "/ssh/systemd/restart")
        .then().statusCode(422).body("code", equalTo("VALIDATION_FAILED"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("unitName", "ssh.service;id", "reason", "Reject invalid systemd unit syntax"))
        .post("/api/v1/hosts/" + hostId + "/ssh/systemd/status")
        .then().statusCode(422).body("code", equalTo("VALIDATION_FAILED"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of("unitName", "ssh.service;id", "lines", 100,
            "reason", "Reject invalid systemd unit syntax"))
        .post("/api/v1/hosts/" + hostId + "/ssh/systemd/journal")
        .then().statusCode(422).body("code", equalTo("VALIDATION_FAILED"));
    var deniedSshDiagnostics = store.transaction(c -> AuthStore.query(c,
        "SELECT action,outcome FROM audit_event WHERE resource_id=? AND " +
            "action IN ('SSH_SYSTEMD_STATUS_READ','SSH_SYSTEMD_JOURNAL_READ') ORDER BY action",
        hostId.toString()));
    assertEquals(0, deniedSshDiagnostics.size());
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", "ssh-agent-install-needs-asset-config")
        .contentType("application/json")
        .body(Map.of("enrollmentId", UUID.randomUUID().toString(), "agentId", agentId,
            "token", "invalid", "reason", "Reject malformed enrollment token"))
        .post("/api/v1/hosts/" + hostId + "/ssh/agent/install")
        .then().statusCode(422).body("code", equalTo("VALIDATION_FAILED"));
    String packageOperationKey = "helper-package-key-01";
    var packageRequest = Map.of(
        "capability", "package.install", "target", "",
        "payload", Map.of("packages", java.util.List.of("curl")),
        "reason", "Install the diagnostic utility using validated package parameters", "timeoutSeconds", 60,
        "expectedGeneration", 1);
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", packageOperationKey)
        .contentType("application/json").body(packageRequest)
        .post("/api/v1/hosts/" + hostId + "/operations")
        .then().statusCode(503).body("code", equalTo("EXECUTION_OUTCOME_UNKNOWN"));
    var helperOperations = given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations?hostId=" + hostId)
        .then().statusCode(200).extract();
    String packageOperationId = helperOperations.path(
        "items.find { it.idempotencyKey == '" + packageOperationKey + "' }.id");
    var packageResultReport = Map.of("agentId", agentId, "operationId", packageOperationId,
        "idempotencyKey", packageOperationKey, "status", "SUCCEEDED",
        "detail", "{\"installed\":[\"curl\"]}");
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(packageResultReport)
        .post("/internal/agent-control-results").then().statusCode(204)
        .header("X-NanPanel-Result-Ack", equalTo("true"));
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations/" + packageOperationId)
        .then().statusCode(200).body("kind", equalTo("HOST_HELPER_OPERATION"))
        .body("capability", equalTo("package.install"))
        .body("status", equalTo("SUCCEEDED"))
        .body("result.output", equalTo("{\"installed\":[\"curl\"]}"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", packageOperationKey)
        .contentType("application/json").body(packageRequest)
        .post("/api/v1/hosts/" + hostId + "/operations")
        .then().statusCode(200).header("Idempotency-Replayed", equalTo("true"))
        .body("id", equalTo(packageOperationId));
    var invalidK3sPreflight = Map.of(
        "capability", "k3s.preflight", "target", "",
        "payload", Map.of("role", "control-plane", "command", "id"),
        "reason", "Reject an invalid K3s preflight role", "expectedGeneration", 1);
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", "helper-k3s-preflight-invalid-01")
        .contentType("application/json").body(invalidK3sPreflight)
        .post("/api/v1/hosts/" + hostId + "/operations")
        .then().statusCode(422).body("code", equalTo("VALIDATION_FAILED"));
    String k3sPreflightKey = "helper-k3s-preflight-valid-01";
    var k3sPreflightRequest = Map.of(
        "capability", "k3s.preflight", "target", "", "payload", Map.of("role", "server"),
        "reason", "Check K3s server prerequisites without changing the host", "timeoutSeconds", 60,
        "expectedGeneration", 1);
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", k3sPreflightKey)
        .contentType("application/json").body(k3sPreflightRequest)
        .post("/api/v1/hosts/" + hostId + "/operations")
        .then().statusCode(503).body("code", equalTo("EXECUTION_OUTCOME_UNKNOWN"));
    var preflightOperations = given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations?hostId=" + hostId)
        .then().statusCode(200).extract();
    String preflightOperationId = preflightOperations.path(
        "items.find { it.idempotencyKey == '" + k3sPreflightKey + "' }.id");
    String preflightResult = "{\"schemaVersion\":1,\"role\":\"server\",\"status\":\"READY\",\"blockerCount\":0,\"warningCount\":0,\"checks\":[]}";
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.of("agentId", agentId, "operationId", preflightOperationId,
            "idempotencyKey", k3sPreflightKey, "status", "SUCCEEDED", "detail", preflightResult))
        .post("/internal/agent-control-results").then().statusCode(204)
        .header("X-NanPanel-Result-Ack", equalTo("true"));
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations/" + preflightOperationId)
        .then().statusCode(200).body("capability", equalTo("k3s.preflight"))
        .body("status", equalTo("SUCCEEDED"))
        .body("result.output", equalTo(preflightResult));
    var invalidK3sInstall = Map.of(
        "capability", "k3s.install", "target", "",
        "payload", Map.of("role", "agent", "version", "v1.36.5+k3s1",
            "serverUrl", "https://k3s.example.test:6443",
            "token", "K10" + "a".repeat(64) + "::server:" + "b".repeat(32) + "\nK3S_URL=evil"),
        "reason", "Reject an injected K3s token", "timeoutSeconds", 300,
        "expectedGeneration", 1);
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", "helper-k3s-install-invalid-01")
        .contentType("application/json").body(invalidK3sInstall)
        .post("/api/v1/hosts/" + hostId + "/operations")
        .then().statusCode(422).body("code", equalTo("VALIDATION_FAILED"));
    var k3sAbsentHeartbeat = new java.util.HashMap<>(heartbeat);
    k3sAbsentHeartbeat.put("capabilities", discoveredCapabilities);
    k3sAbsentHeartbeat.put("kubernetesRuntime", Map.of(
        "detected", false, "kubeletRunning", false, "apiServerLocal", false,
        "runtimeKind", "NONE", "observedAtEpochMs", System.currentTimeMillis()));
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(k3sAbsentHeartbeat)
        .post("/internal/agent-heartbeats").then().statusCode(204);
    var validK3sInstall = Map.of(
        "capability", "k3s.install", "target", "",
        "payload", Map.of("role", "agent", "version", "v1.36.5+k3s1",
            "serverUrl", "https://k3s.example.test:6443/",
            "token", "K10" + "a".repeat(64) + "::server:" + "b".repeat(32)),
        "reason", "Validate secure-format K3s agent installation parameters",
        "timeoutSeconds", 600, "expectedGeneration", 1);
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", "helper-k3s-install-valid-01")
        .contentType("application/json").body(validK3sInstall)
        .post("/api/v1/hosts/" + hostId + "/operations")
        .then().statusCode(503).body("code", equalTo("EXECUTION_OUTCOME_UNKNOWN"));
    var installOperations = given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations?hostId=" + hostId).then().statusCode(200).extract();
    String installOperationId = installOperations.path(
        "items.find { it.idempotencyKey == 'helper-k3s-install-valid-01' }.id");
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.of("agentId", agentId, "operationId", installOperationId,
            "idempotencyKey", "helper-k3s-install-valid-01", "status", "FAILED",
            "detail", "FAILED: test fixture confirms the host has no live Agent session"))
        .post("/internal/agent-control-results").then().statusCode(204)
        .header("X-NanPanel-Result-Ack", equalTo("true"));
    var invalidK3sUpgrade = Map.of(
        "capability", "k3s.upgrade", "target", "",
        "payload", Map.of("role", "server", "version", "latest", "command", "id"),
        "reason", "Reject shell fields and floating K3s versions", "timeoutSeconds", 600,
        "expectedGeneration", 1);
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", "helper-k3s-upgrade-invalid-01")
        .contentType("application/json").body(invalidK3sUpgrade)
        .post("/api/v1/hosts/" + hostId + "/operations")
        .then().statusCode(422).body("code", equalTo("VALIDATION_FAILED"));
    var validK3sUpgrade = Map.of(
        "capability", "k3s.upgrade", "target", "",
        "payload", Map.of("role", "server", "version", "v1.37.1+k3s1"),
        "reason", "Validate fixed-version K3s upgrade request", "timeoutSeconds", 600,
        "expectedGeneration", 1);
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", "helper-k3s-upgrade-valid-01")
        .contentType("application/json").body(validK3sUpgrade)
        .post("/api/v1/hosts/" + hostId + "/operations")
        .then().statusCode(409).body("code", equalTo("K3S_UPGRADE_CLUSTER_UNMAPPED"));
    UUID upgradeHostId = UUID.randomUUID();
    UUID upgradeClusterId = UUID.randomUUID();
    UUID activeUpgradeId = UUID.randomUUID();
    Instant upgradeObservedAt = Instant.now();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO host (id,hostname,os,arch,capabilities,generation,"
          + "observed_generation,status,created_at) VALUES (?,?,?,?,'[]',1,1,'ONLINE',?)",
          upgradeHostId, "k3s-upgrade-gate-host", "Ubuntu 24.04", "x86_64",
          java.sql.Timestamp.from(upgradeObservedAt));
      AuthStore.update(c, "INSERT INTO kubernetes_cluster (id,name,kind,endpoint,context_name,"
          + "encrypted_kubeconfig,health_status,observed_at,imported_by,created_at,updated_at) "
          + "VALUES (?,?, 'K3S',?,?,?,'UNAVAILABLE',?,?,?,?)", upgradeClusterId,
          "system-resource-k3s-upgrade-gate", "https://k3s-upgrade.example.test:6443",
          "test-context", new byte[] {1}, java.sql.Timestamp.from(upgradeObservedAt),
          restored.path("user.id").toString(), java.sql.Timestamp.from(upgradeObservedAt),
          java.sql.Timestamp.from(upgradeObservedAt));
      AuthStore.update(c, "INSERT INTO kubernetes_cluster_node (cluster_id,node_uid,node_name,"
          + "roles,node_present,ready,kubelet_version,internal_ip,container_owner,host_id,observed_at) "
          + "VALUES (?,?,?,'control-plane',TRUE,FALSE,?,'192.0.2.20','KUBERNETES',?,?)",
          upgradeClusterId, "k3s-upgrade-gate-node-uid", "k3s-upgrade-gate-host",
          "v1.36.5+k3s1", upgradeHostId, java.sql.Timestamp.from(upgradeObservedAt));
      return null;
    });
    var installOnMappedHost = Map.of(
        "capability", "k3s.install", "target", "",
        "payload", Map.of("role", "agent", "version", "v1.36.5+k3s1",
            "serverUrl", "https://k3s.example.test:6443", "token", "short-token-for-test-012345"),
        "reason", "Reject installation over an already managed Node",
        "timeoutSeconds", 600, "expectedGeneration", 1);
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", "k3s-install-mapped-host-gate-01")
        .contentType("application/json").body(installOnMappedHost)
        .post("/api/v1/hosts/" + upgradeHostId + "/operations")
        .then().statusCode(409).body("code", equalTo("K3S_INSTALL_HOST_ALREADY_MANAGED"));
    var k3sUpgradePayload = Map.of("capability", "k3s.upgrade", "target", "",
        "payload", Map.of("role", "server", "version", "v1.37.1+k3s1"),
        "reason", "Verify K3s cluster health and serialization gates", "timeoutSeconds", 600,
        "expectedGeneration", 1);
    java.util.function.Function<io.restassured.specification.RequestSpecification,
        io.restassured.specification.RequestSpecification> k3sUpgradeHeaders = spec -> spec
        .cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json").body(k3sUpgradePayload);
    store.transaction(c -> {
      AuthStore.update(c, "UPDATE kubernetes_cluster SET kind='KUBEEDGE' WHERE id=?",
          upgradeClusterId);
      return null;
    });
    k3sUpgradeHeaders.apply(given()).header("Idempotency-Key", "k3s-upgrade-kind-gate-01")
        .post("/api/v1/hosts/" + upgradeHostId + "/operations")
        .then().statusCode(409).body("code", equalTo("K3S_UPGRADE_CLUSTER_KIND"));
    store.transaction(c -> {
      AuthStore.update(c, "UPDATE kubernetes_cluster SET kind='K3S' WHERE id=?", upgradeClusterId);
      return null;
    });
    k3sUpgradeHeaders.apply(given()).header("Idempotency-Key", "k3s-upgrade-health-gate-01")
        .post("/api/v1/hosts/" + upgradeHostId + "/operations")
        .then().statusCode(409).body("code", equalTo("K3S_CLUSTER_HEALTH_GATE"));
    store.transaction(c -> {
      AuthStore.update(c, "UPDATE kubernetes_cluster SET health_status='HEALTHY',observed_at=? "
          + "WHERE id=?", java.sql.Timestamp.from(Instant.now().minusSeconds(121)), upgradeClusterId);
      AuthStore.update(c, "UPDATE kubernetes_cluster_node SET ready=TRUE WHERE cluster_id=? "
          + "AND node_uid=?", upgradeClusterId, "k3s-upgrade-gate-node-uid");
      return null;
    });
    k3sUpgradeHeaders.apply(given()).header("Idempotency-Key", "k3s-upgrade-stale-gate-01")
        .post("/api/v1/hosts/" + upgradeHostId + "/operations")
        .then().statusCode(409).body("code", equalTo("K3S_CLUSTER_HEALTH_GATE"));
    store.transaction(c -> {
      AuthStore.update(c, "UPDATE kubernetes_cluster SET observed_at=? WHERE id=?",
          java.sql.Timestamp.from(Instant.now()), upgradeClusterId);
      AuthStore.update(c, "INSERT INTO operation (id,idempotency_key,phase,status,error,created_at,"
          + "updated_at,kind,host_id,capability) VALUES (?,?,?,'RUNNING',NULL,?,?,?,?,'k3s.upgrade')",
          activeUpgradeId, "k3s-upgrade-gate-active-01", "APPLY",
          java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()),
          "HOST_HELPER_OPERATION", upgradeHostId);
      return null;
    });
    k3sUpgradeHeaders.apply(given()).header("Idempotency-Key", "k3s-upgrade-active-gate-01")
        .post("/api/v1/hosts/" + upgradeHostId + "/operations")
        .then().statusCode(409).body("code", equalTo("K3S_UPGRADE_IN_PROGRESS"));
    store.transaction(c -> {
      Instant finishedAt = Instant.now();
      AuthStore.update(c, "UPDATE operation SET status='FAILED',finished_at=? WHERE id=?",
          java.sql.Timestamp.from(finishedAt), activeUpgradeId);
      AuthStore.update(c, "UPDATE kubernetes_cluster SET observed_at=? WHERE id=?",
          java.sql.Timestamp.from(finishedAt.minusSeconds(1)), upgradeClusterId);
      return null;
    });
    k3sUpgradeHeaders.apply(given()).header("Idempotency-Key", "k3s-upgrade-refresh-gate-01")
        .post("/api/v1/hosts/" + upgradeHostId + "/operations")
        .then().statusCode(409).body("code", equalTo("K3S_CLUSTER_HEALTH_GATE"));
    store.transaction(c -> {
      AuthStore.update(c, "UPDATE kubernetes_cluster SET observed_at=? WHERE id=?",
          java.sql.Timestamp.from(Instant.now()), upgradeClusterId);
      return null;
    });
    k3sUpgradeHeaders.apply(given()).header("Idempotency-Key", "k3s-upgrade-ready-gate-01")
        .post("/api/v1/hosts/" + upgradeHostId + "/operations")
        .then().statusCode(503).body("code", equalTo("AGENT_OFFLINE"));
    var invalidNetworkRequest = Map.of(
        "capability", "network.firewall.expose", "target", "ssh-test",
        "payload", Map.of("interfaceName", "eth0", "protocol", "tcp", "port", 2222,
            "rollbackSeconds", 301),
        "reason", "Test bounded rollback validation", "timeoutSeconds", 60,
        "expectedGeneration", 1);
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", "helper-network-invalid-01")
        .contentType("application/json").body(invalidNetworkRequest)
        .post("/api/v1/hosts/" + hostId + "/operations")
        .then().statusCode(422).body("code", equalTo("VALIDATION_FAILED"));
    var invalidContainerRequest = Map.of(
        "capability", "container.deploy", "target", "web-test",
        "payload", Map.of("image", "--privileged", "hostPort", 8080, "containerPort", 80),
        "reason", "Reject an invalid container image", "timeoutSeconds", 60,
        "expectedGeneration", 1);
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", "helper-container-invalid-01")
        .contentType("application/json").body(invalidContainerRequest)
        .post("/api/v1/hosts/" + hostId + "/operations")
        .then().statusCode(422).body("code", equalTo("VALIDATION_FAILED"));
    var invalidNetworkInterfaceRequest = Map.of(
        "capability", "network.firewall.expose", "target", "ssh-test",
        "payload", Map.of("interfaceName", "-eth0", "protocol", "tcp", "port", 2222,
            "rollbackSeconds", 90),
        "reason", "Reject an invalid interface name", "timeoutSeconds", 60,
        "expectedGeneration", 1);
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", "helper-network-interface-invalid-01")
        .contentType("application/json").body(invalidNetworkInterfaceRequest)
        .post("/api/v1/hosts/" + hostId + "/operations")
        .then().statusCode(422).body("code", equalTo("VALIDATION_FAILED"));
    var networkRequest = Map.of(
        "capability", "network.firewall.expose", "target", "ssh-test",
        "payload", Map.of("interfaceName", "eth0", "protocol", "tcp", "port", 2222,
            "rollbackSeconds", 90),
        "reason", "Test bounded network operation", "timeoutSeconds", 60,
        "expectedGeneration", 1);
    String networkOperationKey = "helper-network-valid-01";
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", networkOperationKey)
        .contentType("application/json").body(networkRequest)
        .post("/api/v1/hosts/" + hostId + "/operations")
        .then().statusCode(503).body("code", equalTo("EXECUTION_OUTCOME_UNKNOWN"));
    var afterNetworkDispatch = given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations?hostId=" + hostId)
        .then().statusCode(200).extract();
    String networkOperationId = afterNetworkDispatch.path(
        "items.find { it.idempotencyKey == '" + networkOperationKey + "' }.id");
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.of("agentId", agentId, "operationId", networkOperationId,
            "idempotencyKey", networkOperationKey, "status", "SUCCEEDED",
            "detail", "{\"verified\":true,\"automaticRollback\":true}"))
        .post("/internal/agent-control-results").then().statusCode(204)
        .header("X-NanPanel-Result-Ack", equalTo("true"));
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations/" + networkOperationId)
        .then().statusCode(200).body("capability", equalTo("network.firewall.expose"))
        .body("status", equalTo("SUCCEEDED"))
        .body("result.output", equalTo("{\"verified\":true,\"automaticRollback\":true}"));
    var staleGenerationRequest = new java.util.HashMap<>(packageRequest);
    staleGenerationRequest.put("expectedGeneration", 2);
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("Idempotency-Key", "helper-stale-generation-01")
        .contentType("application/json").body(staleGenerationRequest)
        .post("/api/v1/hosts/" + hostId + "/operations")
        .then().statusCode(412).body("code", equalTo("PRECONDITION_FAILED"));
    var services = given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/hosts/" + hostId + "/services")
        .then().statusCode(200)
        .body("items.size()", equalTo(1))
        .body("items[0].unitName", equalTo("ssh.service"))
        .body("items[0].activeState", equalTo("ACTIVE"))
        .body("items[0].observationStale", equalTo(false))
        .extract();
    String serviceId = services.path("items[0].id");
    var serviceDetail = given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/hosts/" + hostId + "/services/" + serviceId)
        .then().statusCode(200)
        .body("generation", equalTo(1))
        .body("subState", equalTo("running"))
        .body("supportedActions", hasItem("RESTART"))
        .body("supportedActions", hasItem("READ_JOURNAL"))
        .extract();
    String serviceEtag = serviceDetail.header("ETag");
    assertTrue(serviceEtag.matches("\"[a-f0-9]{64}\""));
    String operationKey = "systemd-test-key-01";
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("If-Match", serviceEtag)
        .header("Idempotency-Key", operationKey)
        .contentType("application/json")
        .body(Map.of("reason", "P1 systemd operation test", "timeoutSeconds", 60))
        .post("/api/v1/hosts/" + hostId + "/services/" + serviceId + "/restart")
        .then().statusCode(503)
        .body("code", equalTo("EXECUTION_OUTCOME_UNKNOWN"));
    var operations = given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations?hostId=" + hostId)
        .then().statusCode(200)
        .extract();
    String operationId = operations.path(
        "items.find { it.idempotencyKey == '" + operationKey + "' }.id");
    assertEquals("UNKNOWN", operations.path(
        "items.find { it.idempotencyKey == '" + operationKey + "' }.status"));
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/admin/audit-events?outcome=UNKNOWN")
        .then().statusCode(200)
        .body("items.find { it.action == 'systemd.restart' }.outcome", equalTo("UNKNOWN"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("If-Match", serviceEtag)
        .header("Idempotency-Key", operationKey)
        .contentType("application/json")
        .body(Map.of("reason", "P1 systemd operation test", "timeoutSeconds", 60))
        .post("/api/v1/hosts/" + hostId + "/services/" + serviceId + "/restart")
        .then().statusCode(202)
        .header("Idempotency-Replayed", equalTo("true"))
        .body("id", equalTo(operationId))
        .body("status", equalTo("UNKNOWN"));
    var uncertainResultReport = Map.of("agentId", agentId, "operationId", operationId,
        "idempotencyKey", operationKey, "status", "FAILED",
        "detail", "UNCERTAIN: result was not available during reconnect");
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(uncertainResultReport)
        .post("/internal/agent-control-results").then().statusCode(204)
        .header("X-NanPanel-Result-Ack", equalTo("true"));
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(uncertainResultReport)
        .post("/internal/agent-control-results").then().statusCode(204);
    var resultReport = Map.of("agentId", agentId, "operationId", operationId,
        "idempotencyKey", operationKey, "status", "SUCCEEDED",
        "detail", "{\"activeState\":\"ACTIVE\",\"evidence\":\"systemd is active\"}");
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(resultReport)
        .post("/internal/agent-control-results").then().statusCode(204);
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(resultReport)
        .post("/internal/agent-control-results").then().statusCode(204);
    var conflictingResult = new java.util.HashMap<>(resultReport);
    conflictingResult.put("status", "FAILED");
    conflictingResult.put("detail", "DENIED: conflicting duplicate");
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(conflictingResult)
        .post("/internal/agent-control-results").then().statusCode(409);
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations/" + operationId)
        .then().statusCode(200).body("status", equalTo("SUCCEEDED"));
    String journalKey = "systemd-journal-key-01";
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("If-Match", serviceEtag)
        .header("Idempotency-Key", journalKey)
        .contentType("application/json")
        .body(Map.of("reason", "P1 bounded journal read test"))
        .post("/api/v1/hosts/" + hostId + "/services/" + serviceId + "/journal")
        .then().statusCode(503).body("code", equalTo("EXECUTION_OUTCOME_UNKNOWN"));
    var journalOperations = given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations?hostId=" + hostId)
        .then().statusCode(200).extract();
    String journalOperationId = journalOperations.path(
        "items.find { it.idempotencyKey == '" + journalKey + "' }.id");
    var journalResultReport = Map.of("agentId", agentId, "operationId", journalOperationId,
        "idempotencyKey", journalKey, "status", "SUCCEEDED",
        "detail", "Oct 19 00:00:01 host service[123]: started\nOct 19 00:00:02 host service[123]: ready");
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(journalResultReport)
        .post("/internal/agent-control-results").then().statusCode(204)
        .header("X-NanPanel-Result-Ack", equalTo("true"));
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(journalResultReport)
        .post("/internal/agent-control-results").then().statusCode(204)
        .header("X-NanPanel-Result-Ack", equalTo("true"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("If-Match", serviceEtag)
        .header("Idempotency-Key", journalKey)
        .contentType("application/json")
        .body(Map.of("reason", "P1 bounded journal read test"))
        .post("/api/v1/hosts/" + hostId + "/services/" + serviceId + "/journal")
        .then().statusCode(200).header("Idempotency-Replayed", equalTo("true"))
        .body("id", equalTo(journalOperationId));
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations/" + journalOperationId)
        .then().statusCode(200).body("kind", equalTo("SYSTEMD_JOURNAL_READ"))
        .body("status", equalTo("SUCCEEDED"))
        .body("result.journal", containsString("service[123]: ready"));
    var journalUnsupportedHeartbeat = new java.util.HashMap<>(heartbeat);
    journalUnsupportedHeartbeat.put("capabilities", java.util.List.of(
        "host.inventory", "agent.heartbeat", "agent.self_update", "systemd.read",
        "systemd.restart", "network.inventory", "network.overlay.observe",
        "network.overlay.peer_paths.observe"));
    journalUnsupportedHeartbeat.put("systemdServices", java.util.List.of(Map.of(
        "unitName", "ssh.service", "displayName", "OpenSSH server",
        "activeState", "ACTIVE", "subState", "running")));
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(journalUnsupportedHeartbeat)
        .post("/internal/agent-heartbeats").then().statusCode(204);
    var journalUnsupportedService = given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/hosts/" + hostId + "/services/" + serviceId)
        .then().statusCode(200).body("supportedActions", not(hasItem("READ_JOURNAL")))
        .extract();
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("If-Match", journalUnsupportedService.header("ETag"))
        .header("Idempotency-Key", "journal-unsupported-key-01")
        .contentType("application/json")
        .body(Map.of("reason", "Reject journal read when Agent lacks the host capability"))
        .post("/api/v1/hosts/" + hostId + "/services/" + serviceId + "/journal")
        .then().statusCode(422).body("code", equalTo("OPERATION_UNSUPPORTED"));
    var staleInventoryHeartbeat = new java.util.HashMap<>(heartbeat);
    staleInventoryHeartbeat.put("systemdObservedAtEpochMs", System.currentTimeMillis() - 200_000);
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(staleInventoryHeartbeat)
        .post("/internal/agent-heartbeats").then().statusCode(204);
    var staleService = given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/hosts/" + hostId + "/services/" + serviceId)
        .then().statusCode(200)
        .body("observationStale", equalTo(true))
        .body("supportedActions", empty())
        .extract();
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("If-Match", staleService.header("ETag"))
        .header("Idempotency-Key", "stale-restart-key-01")
        .contentType("application/json")
        .body(Map.of("reason", "Reject stale observation", "timeoutSeconds", 60))
        .post("/api/v1/hosts/" + hostId + "/services/" + serviceId + "/restart")
        .then().statusCode(503).body("code", equalTo("SERVICE_OBSERVATION_STALE"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .header("If-Match", staleService.header("ETag"))
        .header("Idempotency-Key", "stale-journal-key-01")
        .contentType("application/json")
        .body(Map.of("reason", "Reject stale journal observation"))
        .post("/api/v1/hosts/" + hostId + "/services/" + serviceId + "/journal")
        .then().statusCode(503).body("code", equalTo("SERVICE_OBSERVATION_STALE"));
    var emptyInventoryHeartbeat = new java.util.HashMap<>(heartbeat);
    emptyInventoryHeartbeat.put("systemdObservedAtEpochMs", System.currentTimeMillis());
    emptyInventoryHeartbeat.put("systemdServices", java.util.List.of());
    given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json").body(emptyInventoryHeartbeat)
        .post("/internal/agent-heartbeats").then().statusCode(204);
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/hosts/" + hostId + "/services")
        .then().statusCode(200).body("items.size()", equalTo(1))
        .body("items[0].activeState", equalTo("UNKNOWN"))
        .body("items[0].supportedActions", empty());
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/agents/" + agentId)
        .then().statusCode(200)
        .body("status", equalTo("ONLINE"))
        .body("helperStatus", equalTo("AVAILABLE"))
        .body("capabilities", hasItems("agent.self_update"));
    given().cookie("nanpanel_session", restoredCookie)
        .header("X-CSRF-Token", restored.path("csrfToken"))
        .contentType("application/json")
        .body(Map.of())
        .post("/api/v1/agents/" + agentId + "/updates")
        .then().statusCode(503)
        .body("code", equalTo("GATEWAY_UNAVAILABLE"));
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/admin/audit-events?outcome=ACCEPTED&action=agent.update")
        .then().statusCode(200)
        .body("items[0].action", equalTo("agent.update"))
        .body("items[0].outcome", equalTo("ACCEPTED"));
    UUID uncertainInstall = UUID.randomUUID();
    Instant heartbeatEvidenceCreatedAt = Instant.now().minusSeconds(10);
    String adminId = store.transaction(c -> String.valueOf(AuthStore.query(c,
        "SELECT id FROM panel_user WHERE username='admin'").getFirst().get("id")));
    store.transaction(c -> {
      AuthStore.update(c,
          "INSERT INTO operation (id,idempotency_key,phase,status,error,created_at,updated_at," +
              "kind,host_id,agent_id,requested_by,reason,started_at,deadline_at,request_hash,target," +
              "capability,result_json,error_json) VALUES (?,?,'APPLY','UNKNOWN',NULL,?,?,?,?,?,?,?,?,?,?,?," +
              "'agent.install',NULL,?)",
          uncertainInstall, "ssh-agent-reconcile-" + UUID.randomUUID(),
          java.sql.Timestamp.from(heartbeatEvidenceCreatedAt), java.sql.Timestamp.from(Instant.now()),
          "SSH_AGENT_INSTALL", UUID.fromString(hostId), UUID.fromString(agentId), adminId,
          "Verify late mTLS heartbeat reconciliation", java.sql.Timestamp.from(heartbeatEvidenceCreatedAt),
          java.sql.Timestamp.from(heartbeatEvidenceCreatedAt.plusSeconds(360)), "a".repeat(64), agentId,
          "{\"code\":\"EXECUTION_OUTCOME_UNKNOWN\",\"message\":\"pending\",\"retryable\":false," +
              "\"executionOutcome\":\"UNCERTAIN\"}");
      return null;
    });
    given().cookie("nanpanel_session", restoredCookie)
        .get("/api/v1/operations/" + uncertainInstall)
        .then().statusCode(200).body("kind", equalTo("SSH_AGENT_INSTALL"))
        .body("status", equalTo("SUCCEEDED"))
        .body("result.agentId", equalTo(agentId));
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

  private void verifyBastionTerminal(String hostId, String cookie, String csrf) throws Exception {
    var generator = java.security.KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var serverKey = generator.generateKeyPair();
    org.apache.sshd.server.SshServer target = org.apache.sshd.server.SshServer
        .setUpDefaultServer();
    target.setHost("127.0.0.1");
    target.setPort(0);
    target.setKeyPairProvider(org.apache.sshd.common.keyprovider.KeyPairProvider.wrap(serverKey));
    target.setPasswordAuthenticator((username, password, session) ->
        "panel-test".equals(username) && "host-test-password-do-not-return".equals(password));
    target.setShellFactory(channel -> new EchoShellCommand());
    target.start();
    try {
      String password = "host-test-password-do-not-return";
      given().cookie("nanpanel_session", cookie).header("X-CSRF-Token", csrf)
          .contentType("application/json")
          .body(Map.of("address", "127.0.0.1", "port", target.getPort(),
              "username", "panel-test", "authMethod", "PASSWORD", "password", password))
          .put("/api/v1/hosts/" + hostId + "/ssh/config").then().statusCode(204);
      var saved = given().cookie("nanpanel_session", cookie)
          .get("/api/v1/hosts/" + hostId + "/ssh/config").then().statusCode(200).extract();
      assertEquals("127.0.0.1", saved.path("address"));
      assertEquals("PASSWORD", saved.path("authMethod"));
      assertTrue(Boolean.TRUE.equals(saved.path("credentialsConfigured")));
      assertFalse(saved.asString().contains(password));
      byte[] credentialCiphertext = (byte[])store.transaction(c -> AuthStore.query(c,
          "SELECT encrypted_credential FROM host_ssh_config WHERE host_id=?",
          UUID.fromString(hostId))).getFirst().get("encrypted_credential");
      assertFalse(new String(credentialCiphertext, StandardCharsets.ISO_8859_1)
          .contains(password));

      String fingerprint = given().cookie("nanpanel_session", cookie)
          .header("X-CSRF-Token", csrf).contentType("application/json")
          .body(Map.of("reason", "Verify bastion target host key"))
          .post("/api/v1/hosts/" + hostId + "/ssh/host-key/probe")
          .then().statusCode(200).extract().path("fingerprint");
      assertTrue(fingerprint.startsWith("SHA256:"));
      given().cookie("nanpanel_session", cookie).header("X-CSRF-Token", csrf)
          .contentType("application/json")
          .body(Map.of("fingerprint", fingerprint, "reason", "Verified from local test SSHD"))
          .post("/api/v1/hosts/" + hostId + "/ssh/host-key/trust")
          .then().statusCode(204);

      var created = given().cookie("nanpanel_session", cookie).header("X-CSRF-Token", csrf)
          .contentType("application/json")
          .body(Map.of("reason", "Verify audited interactive SSH shell", "columns", 100, "rows", 30))
          .post("/api/v1/hosts/" + hostId + "/ssh/sessions")
          .then().statusCode(201).extract();
      String sessionId = created.path("sessionId");
      String ticket = created.path("ticket");
      String marker = "nanpanel-bastion-e2e-" + UUID.randomUUID().toString().substring(0, 8);
      var frames = new java.util.concurrent.LinkedBlockingQueue<String>();
      var socketClosed = new java.util.concurrent.atomic.AtomicBoolean();
      var listener = new java.net.http.WebSocket.Listener() {
        @Override
        public void onOpen(java.net.http.WebSocket webSocket) {
          webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(
            java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
          frames.offer(data.toString());
          webSocket.request(1);
          return null;
        }

        @Override
        public void onError(java.net.http.WebSocket webSocket, Throwable error) {
          frames.offer("{\"type\":\"test-error\",\"message\":\"" +
              String.valueOf(error.getMessage()).replace("\"", "'") + "\"}");
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(
            java.net.http.WebSocket webSocket, int statusCode, String reason) {
          socketClosed.set(true);
          return null;
        }
      };
      int testPort = io.restassured.RestAssured.port;
      java.net.URI terminalUri = java.net.URI.create(
          "ws://localhost:" + testPort + "/api/v1/bastion-sessions/" + sessionId + "/terminal");
      var socket = java.net.http.HttpClient.newHttpClient().newWebSocketBuilder()
          .header("Origin", "http://localhost:" + testPort)
          .buildAsync(terminalUri, listener).get(10, TimeUnit.SECONDS);
      socket.sendText("{\"type\":\"auth\",\"ticket\":\"" + ticket +
          "\",\"columns\":100,\"rows\":30}", true).join();
      boolean ready = false;
      long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!ready && System.nanoTime() < readyDeadline) {
        String frame = frames.poll(1, TimeUnit.SECONDS);
        if (frame == null) continue;
        var event = new com.fasterxml.jackson.databind.ObjectMapper().readTree(frame);
        if ("test-error".equals(event.path("type").asText()))
          fail("WebSocket reported an error: " + frame);
        ready = "ready".equals(event.path("type").asText());
      }
      assertTrue(ready, "Bastion WebSocket did not authenticate the ticket");
      socket.sendText("{\"type\":\"input\",\"data\":\"echo " + marker + "\\r\"}", true).join();

      boolean observedMarker = false;
      StringBuilder remoteOutput = new StringBuilder();
      long outputDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
      while (!observedMarker && System.nanoTime() < outputDeadline) {
        String frame = frames.poll(1, TimeUnit.SECONDS);
        if (frame == null) continue;
        var event = new com.fasterxml.jackson.databind.ObjectMapper().readTree(frame);
        if ("test-error".equals(event.path("type").asText()))
          fail("WebSocket reported an error: " + frame);
        if ("output".equals(event.path("type").asText())) {
          byte[] output = java.util.Base64.getDecoder().decode(event.path("data").asText());
          remoteOutput.append(new String(output, StandardCharsets.UTF_8));
          observedMarker = remoteOutput.indexOf(marker) >= 0;
        }
      }
      assertTrue(observedMarker, "Remote shell did not return the submitted command output: " +
          remoteOutput + "; frames=" + frames);
      given().cookie("nanpanel_session", cookie).header("X-CSRF-Token", csrf)
          .contentType("application/json")
          .body(Map.of("reason", "Terminate the test shell after capturing output"))
          .post("/api/v1/bastion-sessions/" + sessionId + "/terminate")
          .then().statusCode(204);
      long socketCloseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!socketClosed.get() && System.nanoTime() < socketCloseDeadline)
        Thread.sleep(50);
      assertTrue(socketClosed.get(), "Force termination did not close the browser WebSocket");

      Map<String, Object> savedSession = Map.of();
      long closeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < closeDeadline) {
        var listed = given().cookie("nanpanel_session", cookie)
            .get("/api/v1/bastion-sessions?hostId=" + hostId)
            .then().statusCode(200).extract();
        java.util.List<Map<String, Object>> sessions = listed.jsonPath().getList("items");
        if (!sessions.isEmpty() && "FAILED".equals(sessions.getFirst().get("status")) &&
            "FORCE_TERMINATED".equals(sessions.getFirst().get("failureCode"))) {
          savedSession = sessions.getFirst();
          break;
        }
        Thread.sleep(100);
      }
      assertEquals(sessionId, savedSession.get("id"));
      assertTrue(((Number)savedSession.get("bytesIn")).longValue() > 0);
      assertTrue(((Number)savedSession.get("bytesOut")).longValue() > 0);

      var commands = given().cookie("nanpanel_session", cookie)
          .get("/api/v1/bastion-sessions/" + sessionId + "/commands")
          .then().statusCode(200).extract().jsonPath().getList("items");
      assertTrue(commands.stream().anyMatch(item ->
          String.valueOf(((Map<?, ?>)item).get("command")).contains("echo " + marker)));
      var recording = given().cookie("nanpanel_session", cookie)
          .get("/api/v1/bastion-sessions/" + sessionId + "/recording")
          .then().statusCode(200).extract();
      assertEquals("nanpanel-terminal-v1", recording.path("format"));
      var chunks = recording.jsonPath().getList("items");
      assertTrue(chunks.stream().anyMatch(item -> "INPUT".equals(((Map<?, ?>)item).get("direction"))));
      assertTrue(chunks.stream().anyMatch(item -> "OUTPUT".equals(((Map<?, ?>)item).get("direction"))));
      String recordedOutput = chunks.stream()
          .filter(item -> "OUTPUT".equals(((Map<?, ?>)item).get("direction")))
          .map(item -> java.util.Base64.getDecoder().decode(
              String.valueOf(((Map<?, ?>)item).get("data"))))
          .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
          .collect(java.util.stream.Collectors.joining());
      assertTrue(recordedOutput.contains(marker));

      var storedCommand = store.transaction(c -> AuthStore.query(c,
          "SELECT encrypted_command FROM bastion_command WHERE session_id=?",
          UUID.fromString(sessionId))).getFirst().get("encrypted_command");
      assertFalse(new String((byte[])storedCommand, StandardCharsets.ISO_8859_1)
          .contains("echo " + marker));
      var views = store.transaction(c -> AuthStore.query(c,
          "SELECT action FROM audit_event WHERE resource_id=? AND action IN " +
              "('BASTION_COMMANDS_VIEWED','BASTION_RECORDING_VIEWED'," +
              "'BASTION_SESSION_FORCE_TERMINATED')", sessionId));
      assertEquals(3, views.size());
    } finally {
      target.stop(true);
    }
  }

  private String createCsr(UUID agentId) throws Exception {
    Path directory = Files.createTempDirectory("nanpanel-agent-csr-test-");
    try {
      Path key = directory.resolve("agent.key");
      Path csr = directory.resolve("agent.csr");
      Process process = new ProcessBuilder("openssl", "req", "-new", "-newkey",
          "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes", "-keyout",
          key.toString(), "-out", csr.toString(), "-subj", "/CN=" + agentId)
          .redirectErrorStream(true).start();
      byte[] output = process.getInputStream().readAllBytes();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "OpenSSL CSR generation timed out");
      assertEquals(0, process.exitValue(), new String(output, StandardCharsets.UTF_8));
      return Files.readString(csr, StandardCharsets.US_ASCII);
    } finally {
      try (var paths = Files.walk(directory)) {
        paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
          try { Files.deleteIfExists(path); } catch (java.io.IOException ignored) { }
        });
      }
    }
  }
}
