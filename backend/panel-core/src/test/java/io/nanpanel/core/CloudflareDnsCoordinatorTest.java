package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CloudflareDnsCoordinatorTest {
  private static final String DNS_SUFFIX = "p1-coordinator.test.example";
  private static final String API_NAME = "api." + DNS_SUFFIX;
  private static final String RELAY_A = "relay-a." + DNS_SUFFIX;
  private static final String RELAY_B = "relay-b." + DNS_SUFFIX;

  @Inject AuthStore store;
  @Inject CloudflareDnsCoordinator coordinator;

  @Test
  void expiredWriterCannotRenewAfterFencingEpochChanges() {
    Long firstEpoch = coordinator.claimLease();
    assertNotNull(firstEpoch);

    store.transaction(connection -> {
      AuthStore.update(connection,
          "UPDATE dns_writer_lease SET lease_until=? WHERE id=1",
          Timestamp.from(Instant.now().minusSeconds(1)));
      return null;
    });

    Long nextEpoch = coordinator.claimLease();
    assertNotNull(nextEpoch);
    assertEquals(firstEpoch + 1, nextEpoch);
    assertFalse(coordinator.refreshLease(firstEpoch));
    assertTrue(coordinator.refreshLease(nextEpoch));
  }

  @Test
  void keepsCurrentTargetUntilThreeSpacedFailureReportsAndDoesNotPreferRecovery() {
    String apiName = coordinator.apiRecordName();
    assertEquals("api.test.example", apiName);
    store.transaction(connection -> {
      AuthStore.update(connection, "DELETE FROM dns_managed_record WHERE record_name=?", apiName);
      AuthStore.update(connection, "DELETE FROM agent_relay_status WHERE relay_id IN (?,?)",
          "hysteresis-relay-a", "hysteresis-relay-b");
      AuthStore.update(connection,
          "INSERT INTO dns_managed_record " +
              "(record_name,record_id,content,ttl,proxied,state,content_changed_at,switch_count,updated_at) " +
              "VALUES (?,?,?,60,FALSE,'MANAGED',?,1,?)",
          apiName, "record-a", "relay-a.example", Timestamp.from(Instant.now()),
          Timestamp.from(Instant.now()));
      AuthStore.update(connection,
          "INSERT INTO agent_relay_status " +
              "(relay_id,cluster_id,target_name,epoch,healthy,healthy_count,unhealthy_count,last_seen,last_counted_at,updated_at) " +
              "VALUES (?,?,?,1,FALSE,0,2,?,?,?)",
          "hysteresis-relay-a", "test-cluster", "relay-a.example",
          Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
      return null;
    });

    var status = store.transaction(connection -> AuthStore.query(connection,
        "SELECT healthy,unhealthy_count,last_seen FROM agent_relay_status WHERE cluster_id=? AND target_name=?",
        "test-cluster", "relay-a.example"));
    assertEquals(1, status.size(), status.toString());
    assertEquals(Boolean.FALSE, status.getFirst().get("healthy"), status.toString());
    assertEquals(2, ((Number)status.getFirst().get("unhealthy_count")).intValue(), status.toString());
    assertEquals("relay-a.example", coordinator.preferredTarget(List.of("relay-b.example")));
    store.transaction(connection -> {
      AuthStore.update(connection,
          "UPDATE agent_relay_status SET unhealthy_count=3 WHERE relay_id=?",
          "hysteresis-relay-a");
      return null;
    });
    assertEquals("relay-b.example", coordinator.preferredTarget(List.of("relay-b.example")));

    store.transaction(connection -> {
      AuthStore.update(connection,
          "UPDATE agent_relay_status SET healthy=TRUE,healthy_count=3,unhealthy_count=0 WHERE relay_id=?",
          "hysteresis-relay-a");
      return null;
    });
    assertEquals("relay-b.example", coordinator.preferredTarget(List.of("relay-b.example")));
  }

  @Test
  void createsOnlyVerifiedDnsOnlyCnameAndRecordsOwnership() throws Exception {
    String name = API_NAME;
    clearRecordAndExpireLease(name);
    try (DnsApiStub api = new DnsApiStub(null)) {
      CloudflareDnsCoordinator testCoordinator = configuredCoordinator(api.origin());
      long epoch = testCoordinator.claimLease();

      testCoordinator.ensureRecord(name, RELAY_A, null, epoch);

      assertEquals(1, api.writes.get());
      assertEquals("POST", api.lastWriteMethod.get());
      JsonNode body = api.lastBody.get();
      assertEquals("CNAME", body.path("type").asText());
      assertEquals(name, body.path("name").asText());
      assertEquals(RELAY_A, body.path("content").asText());
      assertEquals(60, body.path("ttl").asInt());
      assertFalse(body.path("proxied").asBoolean());

      var stored = store.transaction(connection -> AuthStore.query(connection,
          "SELECT record_id,content,ttl,proxied,state FROM dns_managed_record WHERE record_name=?",
          name).getFirst());
      assertEquals("record-1", stored.get("record_id"));
      assertEquals(RELAY_A, stored.get("content"));
      assertEquals(60, ((Number)stored.get("ttl")).intValue());
      assertFalse(Boolean.TRUE.equals(stored.get("proxied")));
      assertEquals("MANAGED", stored.get("state"));
    }
  }

  @Test
  void refusesToAdoptAnExistingCloudflareRecordAndKeepsItUntouched() throws Exception {
    String name = API_NAME;
    clearRecordAndExpireLease(name);
    CloudflareDnsClient.Record existing = new CloudflareDnsClient.Record(
        "record-existing", "CNAME", name, "operator." + DNS_SUFFIX, 300, false);
    try (DnsApiStub api = new DnsApiStub(existing)) {
      CloudflareDnsCoordinator testCoordinator = configuredCoordinator(api.origin());
      long epoch = testCoordinator.claimLease();

      assertThrows(IllegalStateException.class, () -> testCoordinator.ensureRecord(
          name, RELAY_A, null, epoch));

      assertEquals(0, api.writes.get());
      assertEquals(existing, api.record.get());
      var stored = store.transaction(connection -> AuthStore.query(connection,
          "SELECT record_id,content,state FROM dns_managed_record WHERE record_name=?", name)
          .getFirst());
      assertEquals("record-existing", stored.get("record_id"));
      assertEquals("operator." + DNS_SUFFIX, stored.get("content"));
      assertEquals("CONFLICT", stored.get("state"));
    }
  }

  @Test
  void preservesManagedRecordDuringCooldownThenUpdatesAfterCooldownExpires() throws Exception {
    String name = API_NAME;
    CloudflareDnsClient.Record existing = new CloudflareDnsClient.Record(
        "record-managed", "CNAME", name, RELAY_A, 60, false);
    clearRecordAndExpireLease(name);
    store.transaction(connection -> {
      AuthStore.update(connection,
          "INSERT INTO dns_managed_record " +
              "(record_name,record_id,content,ttl,proxied,state,content_changed_at,switch_count,updated_at) " +
              "VALUES (?,?,?,60,FALSE,'MANAGED',?,1,?)",
          name, existing.id(), existing.content(), Timestamp.from(Instant.now()),
          Timestamp.from(Instant.now()));
      return null;
    });
    try (DnsApiStub api = new DnsApiStub(existing)) {
      CloudflareDnsCoordinator testCoordinator = configuredCoordinator(api.origin());
      long epoch = testCoordinator.claimLease();

      testCoordinator.ensureRecord(name, RELAY_B, null, epoch);

      assertEquals(0, api.writes.get());
      assertEquals(existing, api.record.get());
      var unchanged = store.transaction(connection -> AuthStore.query(connection,
          "SELECT content,switch_count FROM dns_managed_record WHERE record_name=?", name)
          .getFirst());
      assertEquals(existing.content(), unchanged.get("content"));
      assertEquals(1, ((Number)unchanged.get("switch_count")).intValue());

      store.transaction(connection -> {
        AuthStore.update(connection,
            "UPDATE dns_managed_record SET content_changed_at=? WHERE record_name=?",
            Timestamp.from(Instant.now().minusSeconds(121)), name);
        return null;
      });
      testCoordinator.ensureRecord(name, RELAY_B, null, epoch);

      assertEquals(1, api.writes.get());
      assertEquals("PATCH", api.lastWriteMethod.get());
      assertEquals(RELAY_B, api.record.get().content());
      var updated = store.transaction(connection -> AuthStore.query(connection,
          "SELECT content,switch_count,state FROM dns_managed_record WHERE record_name=?", name)
          .getFirst());
      assertEquals(RELAY_B, updated.get("content"));
      assertEquals(2, ((Number)updated.get("switch_count")).intValue());
      assertEquals("MANAGED", updated.get("state"));
    }
  }

  private CloudflareDnsCoordinator configuredCoordinator(URI apiOrigin) {
    CloudflareDnsCoordinator configured = new CloudflareDnsCoordinator();
    configured.store = store;
    configured.cloudflare = new CloudflareDnsClient(new ObjectMapper(), "test-token", apiOrigin);
    configured.enabled = true;
    configured.token = "test-token";
    configured.zoneId = "zone-1";
    configured.zoneName = "test.example";
    configured.managedSuffix = DNS_SUFFIX;
    configured.clusterId = "p1-coordinator-test";
    return configured;
  }

  private void clearRecordAndExpireLease(String name) {
    store.transaction(connection -> {
      AuthStore.update(connection, "DELETE FROM dns_managed_record WHERE record_name=?", name);
      AuthStore.update(connection, "UPDATE dns_writer_lease SET owner_id=NULL,lease_until=? WHERE id=1",
          Timestamp.from(Instant.now().minusSeconds(1)));
      return null;
    });
  }

  private static final class DnsApiStub implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final AtomicReference<CloudflareDnsClient.Record> record;
    private final AtomicReference<String> lastWriteMethod = new AtomicReference<>();
    private final AtomicReference<JsonNode> lastBody = new AtomicReference<>();
    private final AtomicInteger writes = new AtomicInteger();

    private DnsApiStub(CloudflareDnsClient.Record initialRecord) throws IOException {
      record = new AtomicReference<>(initialRecord);
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/zones/zone-1/dns_records", this::handle);
      server.start();
    }

    private URI origin() {
      return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void handle(HttpExchange exchange) throws IOException {
      ObjectNode response = mapper.createObjectNode().put("success", true);
      if ("GET".equals(exchange.getRequestMethod())) {
        ArrayNode records = mapper.createArrayNode();
        CloudflareDnsClient.Record current = record.get();
        if (current != null) records.add(recordJson(current));
        response.set("result", records);
      } else {
        lastWriteMethod.set(exchange.getRequestMethod());
        JsonNode body = mapper.readTree(exchange.getRequestBody().readAllBytes());
        lastBody.set(body);
        CloudflareDnsClient.Record current = record.get();
        String id = current == null ? "record-1" : current.id();
        CloudflareDnsClient.Record updated = new CloudflareDnsClient.Record(
            id, body.path("type").asText(), body.path("name").asText(),
            body.path("content").asText(), body.path("ttl").asInt(),
            body.path("proxied").asBoolean());
        record.set(updated);
        writes.incrementAndGet();
        response.set("result", recordJson(updated));
      }
      byte[] bytes = mapper.writeValueAsBytes(response);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    }

    private ObjectNode recordJson(CloudflareDnsClient.Record value) {
      return mapper.createObjectNode().put("id", value.id()).put("type", value.type())
          .put("name", value.name()).put("content", value.content()).put("ttl", value.ttl())
          .put("proxied", value.proxied());
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
