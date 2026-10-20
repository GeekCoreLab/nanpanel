package io.nanpanel.provider.netbird;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.nanpanel.plugin.api.NetworkProvider.ChangePlan;
import io.nanpanel.plugin.api.NetworkProvider.ValidationResult;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NetbirdGroupProviderTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String GROUP = "nanpanel-managed-app";
  private static final String PEER_A = "peer00001";
  private static final String PEER_B = "peer00002";

  private HttpServer server;
  private GroupFixture fixture;
  private NetbirdGroupProvider provider;

  @BeforeEach
  void startServer() throws IOException {
    fixture = new GroupFixture();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/groups", exchange -> fixture.handle(exchange));
    server.start();
    provider = new NetbirdGroupProvider("http://127.0.0.1:" + server.getAddress().getPort(),
        "test-secret", "Token", "nanpanel-managed-", HttpClient.newHttpClient());
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void createsVerifiesAndCompensatesManagedGroup() {
    ChangePlan plan = plan(GROUP, 1, List.of(PEER_A), "");
    assertTrue(result(provider.validate(plan)).successful());

    var operation = provider.apply(plan).toCompletableFuture().join();
    assertEquals(plan.id(), operation);
    assertTrue(result(provider.verify(operation)).successful());
    assertEquals(List.of(PEER_A), fixture.state.peerIds);

    assertTrue(result(provider.compensate(operation)).successful());
    assertNull(fixture.state);
  }

  @Test
  void handlesSelfHostedEmptyGroupResponse() {
    fixture.missingFilteredGroupReturnsNotFound = true;
    fixture.nullEmptyGroupCollections = true;
    ChangePlan plan = plan(GROUP, 6, List.of(PEER_A), "");

    var operation = provider.apply(plan).toCompletableFuture().join();

    assertTrue(result(provider.verify(operation)).successful());
    assertTrue(result(provider.compensate(operation)).successful());
    assertNull(fixture.state);
  }

  @Test
  void preservesResourcesAndRestoresPreviousMembership() {
    ArrayNode resources = MAPPER.createArrayNode();
    resources.addObject().put("id", "resource0001");
    fixture.state = new GroupStateFixture("group00000001", GROUP,
        List.of(PEER_A), resources);
    ChangePlan plan = plan(GROUP, 2, List.of(PEER_B), provider.observe(GROUP).toCompletableFuture().join());

    var operation = provider.apply(plan).toCompletableFuture().join();
    assertEquals(List.of(PEER_B), fixture.state.peerIds);
    assertEquals("resource0001", fixture.state.resources.get(0).path("id").asText());
    assertTrue(result(provider.compensate(operation)).successful());
    assertEquals(List.of(PEER_A), fixture.state.peerIds);
    assertEquals("resource0001", fixture.state.resources.get(0).path("id").asText());
  }

  @Test
  void rejectsExternalDriftBeforeApplyAndBeforeCompensation() {
    ChangePlan stale = plan(GROUP, 3, List.of(PEER_B), "");
    fixture.state = new GroupStateFixture("group00000001", GROUP, List.of(PEER_A), MAPPER.createArrayNode());
    assertThrows(CompletionException.class, () -> provider.apply(stale).toCompletableFuture().join());

    ChangePlan current = plan(GROUP, 4, List.of(PEER_B), "");
    var operation = provider.apply(current).toCompletableFuture().join();
    fixture.state.peerIds = List.of(PEER_A);
    ValidationResult compensation = result(provider.compensate(operation));
    assertFalse(compensation.successful());
    assertTrue(compensation.detail().contains("drifted"));
    assertEquals(List.of(PEER_A), fixture.state.peerIds);
  }

  @Test
  void restoresOperationContextAfterProviderRestart() {
    fixture.state = new GroupStateFixture("group00000001", GROUP,
        List.of(PEER_A), MAPPER.createArrayNode());
    ChangePlan plan = plan(GROUP, 5, List.of(PEER_B), "");
    var operation = provider.apply(plan).toCompletableFuture().join();

    NetbirdGroupProvider restarted = new NetbirdGroupProvider(
        "http://127.0.0.1:" + server.getAddress().getPort(), "test-secret", "Token",
        "nanpanel-managed-", HttpClient.newHttpClient());
    assertTrue(result(restarted.restoreOperation(plan, operation)).successful());
    assertTrue(result(restarted.verify(operation)).successful());
    assertTrue(result(restarted.compensate(operation)).successful());
    assertEquals(List.of(PEER_A), fixture.state.peerIds);
  }

  @Test
  void rejectsNonManagedGroupsAndStaleObservedSnapshot() {
    assertThrows(CompletionException.class,
        () -> plan("customer-app", 1, List.of(PEER_A), ""));
    assertThrows(CompletionException.class,
        () -> plan(GROUP, 1, List.of(PEER_A), "{\"groupName\":\"" + GROUP
            + "\",\"fingerprint\":\"stale\"}"));
    assertThrows(CompletionException.class,
        () -> plan(GROUP, 1, List.of(PEER_A, PEER_A), ""));
  }

  private ChangePlan plan(String groupName, long generation, List<String> peers, String observed) {
    ObjectNode desired = MAPPER.createObjectNode();
    desired.put("groupName", groupName);
    desired.put("generation", generation);
    var peerArray = desired.putArray("peerIds");
    peers.forEach(peerArray::add);
    return provider.plan(desired.toString(), observed).toCompletableFuture().join();
  }

  private static ValidationResult result(java.util.concurrent.CompletionStage<ValidationResult> stage) {
    return stage.toCompletableFuture().join();
  }

  private final class GroupFixture {
    private GroupStateFixture state;
    private boolean missingFilteredGroupReturnsNotFound;
    private boolean nullEmptyGroupCollections;

    void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
      if (!"Token test-secret".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
        respond(exchange, 401, "{}");
        return;
      }
      String method = exchange.getRequestMethod();
      String path = exchange.getRequestURI().getPath();
      if ("/api/groups".equals(path) && "GET".equals(method)) {
        String query = exchange.getRequestURI().getQuery();
        boolean missingFilteredGroup = query != null && query.startsWith("name=")
            && (state == null || !query.substring("name=".length()).equals(state.name));
        if (missingFilteredGroupReturnsNotFound && missingFilteredGroup) {
          respond(exchange, 404, "{}");
        } else {
          respond(exchange, 200, state == null ? "[]" : "["
              + state.toJson(nullEmptyGroupCollections) + "]");
        }
      } else if ("/api/groups".equals(path) && "POST".equals(method)) {
        state = GroupStateFixture.from(readBody(exchange));
        state.id = "group00000001";
        respond(exchange, 201, state.toJson(nullEmptyGroupCollections));
      } else if (path.equals("/api/groups/group00000001") && "PUT".equals(method)) {
        GroupStateFixture updated = GroupStateFixture.from(readBody(exchange));
        updated.id = "group00000001";
        state = updated;
        respond(exchange, 200, state.toJson(nullEmptyGroupCollections));
      } else if (path.equals("/api/groups/group00000001") && "DELETE".equals(method)) {
        state = null;
        respond(exchange, 204, "");
      } else {
        respond(exchange, 404, "{}");
      }
    }

    private String readBody(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
      return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
        throws IOException {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      if (status == 204) {
        exchange.sendResponseHeaders(status, -1);
      } else {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
      }
      exchange.close();
    }
  }

  private static final class GroupStateFixture {
    private String id;
    private final String name;
    private List<String> peerIds;
    private final JsonNode resources;

    private GroupStateFixture(String id, String name, List<String> peerIds, JsonNode resources) {
      this.id = id;
      this.name = name;
      this.peerIds = new ArrayList<>(peerIds);
      this.resources = resources.deepCopy();
    }

    static GroupStateFixture from(String body) throws IOException {
      JsonNode node = MAPPER.readTree(body);
      List<String> peerIds = new ArrayList<>();
      node.path("peers").forEach(peer -> peerIds.add(peer.asText()));
      return new GroupStateFixture(null, node.path("name").asText(), peerIds,
          node.path("resources"));
    }

    String toJson(boolean nullEmptyCollections) throws IOException {
      ObjectNode group = MAPPER.createObjectNode();
      group.put("id", id);
      group.put("name", name);
      if (nullEmptyCollections && peerIds.isEmpty()) {
        group.putNull("peers");
      } else {
        var peers = group.putArray("peers");
        peerIds.forEach(peers::add);
      }
      if (nullEmptyCollections && resources.isEmpty()) {
        group.putNull("resources");
      } else {
        group.set("resources", resources);
      }
      return MAPPER.writeValueAsString(group);
    }
  }
}
