package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(HostMetricsResourceTest.MetricsSeriesProfile.class)
class HostMetricsResourceTest {
  public static class MetricsSeriesProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:host_metrics_series;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
          // The sampler timer is off; each test drives harvest() explicitly.
          "nanpanel.metrics.sampling-enabled", "false");
    }
  }

  @jakarta.inject.Inject AuthStore store;
  @jakarta.inject.Inject MetricsSampler sampler;

  /** Tests share one database, so each starts from the same administrator state. */
  private void resetAdmin() {
    long now = System.currentTimeMillis();
    var rows = store.transaction(c -> AuthStore.query(c,
        "SELECT id FROM panel_user WHERE username='admin'"));
    store.transaction(c -> {
      if (rows.isEmpty()) {
        AuthStore.update(c, "INSERT INTO panel_user (id,username,display_name,password_hash,"
                + "must_change,created_at,updated_at,status,version) "
                + "VALUES (?,?,?,?,TRUE,?,?,'ACTIVE',0)",
            UUID.randomUUID().toString(), "admin", "管理员",
            AuthStore.hash("test-only-password"), now, now);
      } else {
        AuthStore.update(c, "UPDATE panel_user SET password_hash=?, must_change=TRUE, "
            + "status='ACTIVE' WHERE username='admin'", AuthStore.hash("test-only-password"));
        AuthStore.update(c, "DELETE FROM web_session");
      }
      return null;
    });
  }

  private record Session(String cookie, String token) {}

  private Session login() {
    var pre = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var login = given().cookie("nanpanel_session", pre.cookie("nanpanel_session"))
        .header("X-CSRF-Token", pre.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"test-only-password\"}")
        .post("/api/v1/auth/login").then().statusCode(200).extract();
    given().cookie("nanpanel_session", login.cookie("nanpanel_session"))
        .header("X-CSRF-Token", login.path("csrfToken"))
        .contentType("application/json")
        .body("{\"currentPassword\":\"test-only-password\","
            + "\"newPassword\":\"metrics-series-password-2026\"}")
        .put("/api/v1/auth/password").then().statusCode(204);
    var next = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var restored = given().cookie("nanpanel_session", next.cookie("nanpanel_session"))
        .header("X-CSRF-Token", next.path("csrfToken"))
        .contentType("application/json")
        .body("{\"username\":\"admin\",\"password\":\"metrics-series-password-2026\"}")
        .post("/api/v1/auth/login").then().statusCode(200).extract();
    return new Session(restored.cookie("nanpanel_session"), restored.path("csrfToken"));
  }

  private UUID seedHost(String hostname) {
    UUID hostId = UUID.randomUUID();
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO host (id,hostname,capabilities,status,created_at) "
          + "VALUES (?,?,'[\"metrics.read\"]','ONLINE',?)", hostId, hostname,
          Timestamp.from(Instant.now()));
      return null;
    });
    return hostId;
  }

  private void sample(UUID hostId, long recordedAt, UUID operationId, long cpuTotal,
      long cpuIdle, long memTotal, long memAvailable) {
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO host_metric_sample (host_id,recorded_at,operation_id,"
              + "cpu_total,cpu_idle_total,mem_total_kib,mem_available_kib,load1,uptime_seconds) "
              + "VALUES (?,?,?,?,?,?,?,?,?)", hostId, recordedAt, operationId, cpuTotal, cpuIdle,
          memTotal, memAvailable, 0.5, 1000);
      return null;
    });
  }

  @Test
  void the_series_derives_cpu_from_consecutive_samples() {
    given().get("/api/v1/hosts/" + UUID.randomUUID() + "/metrics").then().statusCode(401);
    resetAdmin();
    Session session = login();
    UUID hostId = seedHost("metrics-host-1");
    long base = 1_000_000L;
    sample(hostId, base, UUID.randomUUID(), 1000, 800, 1000, 400);
    sample(hostId, base + 30_000, UUID.randomUUID(), 1100, 850, 1000, 500);
    sample(hostId, base + 60_000, UUID.randomUUID(), 1300, 900, 1000, 250);

    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/hosts/" + hostId + "/metrics?limit=3").then().statusCode(200)
        .body("items", hasSize(3))
        .body("hostname", equalTo("metrics-host-1"))
        .body("retentionDays", equalTo(7))
        .body("items[0].cpuBusyPercent", equalTo(null))
        .body("items[0].memoryUsedPercent", equalTo(60.0f))
        .body("items[1].cpuBusyPercent", equalTo(50.0f))
        .body("items[1].memoryUsedPercent", equalTo(50.0f))
        .body("items[2].cpuBusyPercent", equalTo(75.0f))
        .body("items[2].memoryUsedPercent", equalTo(75.0f));

    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/hosts/" + UUID.randomUUID() + "/metrics").then().statusCode(404)
        .body("code", equalTo("HOST_NOT_FOUND"));
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/hosts/" + hostId + "/metrics?limit=1").then().statusCode(422);
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/hosts/" + hostId + "/metrics?limit=500").then().statusCode(422);
  }

  @Test
  void finished_operations_become_samples_exactly_once() {
    resetAdmin();
    Session session = login();
    UUID hostId = seedHost("metrics-host-2");
    UUID operationId = UUID.randomUUID();
    String output = "{\"cpu\":{\"user\":1,\"nice\":0,\"system\":1,\"idle\":8,\"iowait\":0,"
        + "\"irq\":0,\"softirq\":0,\"steal\":0,\"total\":10,\"idleTotal\":8},"
        + "\"memory\":{\"totalKib\":1000,\"availableKib\":250,\"freeKib\":100,\"buffersKib\":0,"
        + "\"cachedKib\":0},\"load\":{\"one\":1.5,\"five\":1.0,\"fifteen\":0.5},"
        + "\"uptimeSeconds\":4242}";
    String resultJson = "{\"output\":" + quote(output) + "}";
    store.transaction(c -> {
      AuthStore.update(c, "INSERT INTO operation (id,idempotency_key,phase,status,kind,host_id,"
              + "capability,target,result_json,created_at,updated_at,finished_at) "
              + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", operationId, "metrics-op-" + operationId,
          "VERIFY", "SUCCEEDED", "HOST_HELPER_OPERATION", hostId, "metrics.read", "", resultJson,
          Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
          Timestamp.from(Instant.now()));
      return null;
    });

    Assertions.assertEquals(1, sampler.harvest());
    Assertions.assertEquals(0, sampler.harvest(), "the same operation must not be sampled twice");
    var samples = sampler.recent(hostId, 10);
    Assertions.assertEquals(1, samples.size());
    Assertions.assertEquals(10, samples.getFirst().cpuTotal());
    Assertions.assertEquals(8, samples.getFirst().cpuIdleTotal());
    Assertions.assertEquals(1000, samples.getFirst().memTotalKib());
    Assertions.assertEquals(1.5, samples.getFirst().load1());

    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/hosts/" + hostId + "/metrics").then().statusCode(200)
        .body("items", hasSize(1))
        .body("items[0].cpuBusyPercent", equalTo(null))
        .body("items[0].memoryUsedPercent", equalTo(75.0f))
        .body("items[0].load1", equalTo(1.5f));
  }

  @Test
  void malformed_snapshots_are_rejected_instead_of_stored() {
    Assertions.assertNull(MetricsSampler.parse(null));
    Assertions.assertNull(MetricsSampler.parse(""));
    Assertions.assertNull(MetricsSampler.parse("not json"));
    // idleTotal larger than total is impossible.
    Assertions.assertNull(MetricsSampler.parse(
        "{\"cpu\":{\"total\":10,\"idleTotal\":11},\"memory\":{\"totalKib\":1000,"
            + "\"availableKib\":100},\"load\":{\"one\":1},\"uptimeSeconds\":5}"));
    // available memory larger than total is impossible.
    Assertions.assertNull(MetricsSampler.parse(
        "{\"cpu\":{\"total\":10,\"idleTotal\":5},\"memory\":{\"totalKib\":1000,"
            + "\"availableKib\":2000},\"load\":{\"one\":1},\"uptimeSeconds\":5}"));
    Assertions.assertNull(MetricsSampler.parse(
        "{\"cpu\":{\"total\":10,\"idleTotal\":5}}"));
    Assertions.assertNotNull(MetricsSampler.parse(
        "{\"cpu\":{\"total\":10,\"idleTotal\":5},\"memory\":{\"totalKib\":1000,"
            + "\"availableKib\":100},\"load\":{\"one\":0.2},\"uptimeSeconds\":5}"));
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
