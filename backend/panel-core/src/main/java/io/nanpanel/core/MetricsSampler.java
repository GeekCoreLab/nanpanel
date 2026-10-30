package io.nanpanel.core;

import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Samples host metrics without any push channel: it asks every capable host for a
 * {@code metrics.read} snapshot on an interval, then stores whatever came back. Nothing is
 * invented — a host that is offline, lacks the capability, or reports a malformed snapshot
 * simply produces no sample.
 */
@ApplicationScoped
public class MetricsSampler {
  static final Duration RETENTION = Duration.ofDays(7);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String CAPABILITY = "metrics.read";
  private static final int MAX_HOSTS_PER_TICK = 200;
  private static final int MAX_HARVEST = 500;

  @Inject AuthStore store;
  @Inject HostHelperOperationResource helperOperations;

  /** Tests disable the timer and call {@link #runOnce()} directly. */
  @org.eclipse.microprofile.config.inject.ConfigProperty(
      name = "nanpanel.metrics.sampling-enabled", defaultValue = "true")
  boolean samplingEnabled;

  /** A snapshot exactly as the helper reported it. */
  record Snapshot(long cpuTotal, long cpuIdleTotal, long memTotalKib, long memAvailableKib,
                  double load1, long uptimeSeconds, long recordedAt) {}

  @Scheduled(every = "30s", delayed = "20s")
  void tick() {
    if (!samplingEnabled) return;
    try {
      runOnce();
    } catch (RuntimeException failure) {
      System.getLogger(MetricsSampler.class.getName())
          .log(System.Logger.Level.WARNING, "metrics sampling failed", failure);
    }
  }

  /** Returns the number of samples stored on this pass. */
  public synchronized int runOnce() {
    int stored = harvest();
    dispatch();
    prune();
    return stored;
  }

  /** Turns finished {@code metrics.read} operations into sample rows, once per operation. */
  int harvest() {
    var rows = store.transaction(c -> query(c, """
        SELECT o.id, o.host_id, o.result_json, o.finished_at, o.created_at
        FROM operation o
        WHERE o.capability = ? AND o.status = 'SUCCEEDED' AND o.result_json IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM host_metric_sample s WHERE s.operation_id = o.id)
        ORDER BY o.created_at LIMIT ?""", CAPABILITY, MAX_HARVEST));
    int stored = 0;
    for (var row : rows) {
      Snapshot snapshot = parse(resultOf(row.get("result_json")));
      if (snapshot == null) continue;
      UUID operationId = UUID.fromString(String.valueOf(row.get("id")));
      UUID hostId = UUID.fromString(String.valueOf(row.get("host_id")));
      Long finishedAt = toMillis(row.get("finished_at"));
      long recordedAt = finishedAt == null ? snapshot.recordedAt() : finishedAt;
      boolean inserted = store.transaction(c -> {
        var existing = query(c, "SELECT operation_id FROM host_metric_sample WHERE operation_id=?",
            operationId);
        if (!existing.isEmpty()) return false;
        update(c, "INSERT INTO host_metric_sample (host_id,recorded_at,operation_id,cpu_total,"
                + "cpu_idle_total,mem_total_kib,mem_available_kib,load1,uptime_seconds) "
                + "VALUES (?,?,?,?,?,?,?,?,?)", hostId, recordedAt, operationId,
            snapshot.cpuTotal(), snapshot.cpuIdleTotal(), snapshot.memTotalKib(),
            snapshot.memAvailableKib(), snapshot.load1(), snapshot.uptimeSeconds());
        return true;
      });
      if (inserted) stored++;
    }
    return stored;
  }

  /** Asks every online, capable host for a snapshot unless one is already in flight. */
  void dispatch() {
    var hosts = store.transaction(c -> query(c, """
        SELECT h.id, h.generation FROM host h
        WHERE h.status = 'ONLINE' AND h.capabilities LIKE ?
          AND NOT EXISTS (SELECT 1 FROM operation o WHERE o.host_id = h.id
            AND o.capability = ? AND o.status IN ('PENDING','RUNNING'))
        ORDER BY h.hostname LIMIT ?""", "%" + CAPABILITY + "%", CAPABILITY, MAX_HOSTS_PER_TICK));
    if (hosts.isEmpty()) return;
    String actor = store.transaction(c -> query(c,
        "SELECT id FROM panel_user ORDER BY created_at LIMIT 1")).stream()
        .findFirst().map(row -> String.valueOf(row.get("id"))).orElse(null);
    if (actor == null) return; // no account to attribute the operation to yet
    for (var host : hosts) {
      UUID hostId = UUID.fromString(String.valueOf(host.get("id")));
      long generation = ((Number) host.get("generation")).longValue();
      // One dispatch per host per 30s window: a retry inside the window is deduplicated.
      String key = "metrics-" + hostId + "-" + (System.currentTimeMillis() / 30_000);
      var request = new HostHelperOperationResource.HelperOperationRequest(
          CAPABILITY, "", null, "采集主机指标", 30, generation);
      try {
        helperOperations.submitAs(hostId, key, request, actor, null);
      } catch (RuntimeException failure) {
        // Offline or refusing hosts are expected; the next tick tries again.
        continue;
      }
    }
  }

  void prune() {
    long cutoff = System.currentTimeMillis() - RETENTION.toMillis();
    store.transaction(c -> update(c, "DELETE FROM host_metric_sample WHERE recorded_at < ?",
        cutoff));
  }

  /** {@code finished_at} is a timestamptz in production and H2 hands back different types. */
  static Long toMillis(Object value) {
    if (value == null) return null;
    if (value instanceof Number number) return number.longValue();
    if (value instanceof java.sql.Timestamp stamp) return stamp.getTime();
    if (value instanceof java.time.OffsetDateTime time) return time.toInstant().toEpochMilli();
    if (value instanceof java.time.Instant instant) return instant.toEpochMilli();
    return null;
  }

  /** Helper results are stored as {@code {"output": "<text>"}}; the text is our JSON. */
  static String resultOf(Object storedResult) {
    if (storedResult == null) return null;
    try {
      JsonNode result = JSON.readTree(String.valueOf(storedResult));
      JsonNode output = result.path("output");
      return output.isMissingNode() || output.isNull() ? null : output.asText();
    } catch (Exception invalid) {
      return null;
    }
  }

  /** Parses a helper snapshot; anything malformed or self-contradictory yields no sample. */
  static Snapshot parse(String output) {
    if (output == null || output.isBlank()) return null;
    try {
      JsonNode root = JSON.readTree(output);
      long total = root.path("cpu").path("total").asLong(-1);
      long idleTotal = root.path("cpu").path("idleTotal").asLong(-1);
      long memTotal = root.path("memory").path("totalKib").asLong(-1);
      long memAvailable = root.path("memory").path("availableKib").asLong(-1);
      double load1 = root.path("load").path("one").asDouble(-1);
      long uptime = root.path("uptimeSeconds").asLong(-1);
      if (total <= 0 || idleTotal < 0 || idleTotal > total) return null;
      if (memTotal <= 0 || memAvailable < 0 || memAvailable > memTotal) return null;
      if (load1 < 0 || uptime < 0) return null;
      return new Snapshot(total, idleTotal, memTotal, memAvailable, load1, uptime,
          System.currentTimeMillis());
    } catch (Exception invalid) {
      return null;
    }
  }

  /** Exposed for tests that need the parsed rows without going through the scheduler. */
  List<Snapshot> recent(UUID hostId, int limit) {
    var rows = store.transaction(c -> query(c,
        "SELECT * FROM host_metric_sample WHERE host_id=? ORDER BY recorded_at DESC LIMIT ?",
        hostId, limit));
    List<Snapshot> samples = new ArrayList<>(rows.size());
    for (var row : rows) {
      samples.add(new Snapshot(((Number) row.get("cpu_total")).longValue(),
          ((Number) row.get("cpu_idle_total")).longValue(),
          ((Number) row.get("mem_total_kib")).longValue(),
          ((Number) row.get("mem_available_kib")).longValue(),
          ((Number) row.get("load1")).doubleValue(),
          ((Number) row.get("uptime_seconds")).longValue(),
          ((Number) row.get("recorded_at")).longValue()));
    }
    return samples;
  }
}
