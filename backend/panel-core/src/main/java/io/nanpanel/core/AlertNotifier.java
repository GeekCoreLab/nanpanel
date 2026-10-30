package io.nanpanel.core;

import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Delivers alert transitions to their channel. The panel only stores observations, so an
 * alert has no event of its own — this notifier derives one: it evaluates the rules on a
 * fixed interval and reports both edges, {@code alert.firing} when a subject starts alerting
 * and {@code alert.recovered} when it stops.
 *
 * <p>Deduplication lives in {@code alert_firing_state}: a still-failing subject is not paged
 * once per minute, a failed delivery is left unmarked so the next pass retries, and every
 * attempt is appended to {@code alert_event} so "was I told last night?" is answerable.
 */
@ApplicationScoped
public class AlertNotifier {
  static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(5);
  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject AuthStore store;

  /** Tests disable the timer and call {@link #runOnce()} directly, so no clock is involved. */
  @org.eclipse.microprofile.config.inject.ConfigProperty(
      name = "nanpanel.alerting.dispatch-enabled", defaultValue = "true")
  boolean dispatchEnabled;

  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public record Delivery(boolean succeeded, String error) {}

  /** One row of {@code alert_firing_state}, enough to build a recovery payload later. */
  private record Firing(UUID ruleId, UUID channelId, String ruleName, String severity,
                        String condition, String hostname, String detail, String key) {}

  @Scheduled(every = "60s", delayed = "20s")
  void tick() {
    if (!dispatchEnabled) return;
    try {
      runOnce();
    } catch (RuntimeException failure) {
      // A broken channel must never take the scheduler down; the next tick retries.
      System.getLogger(AlertNotifier.class.getName())
          .log(System.Logger.Level.WARNING, "alert dispatch failed", failure);
    }
  }

  /** Returns the number of notifications delivered (firing plus recovery) on this pass. */
  public synchronized int runOnce() {
    var ruleRows = store.transaction(c -> query(c, """
        SELECT r.rule_id, r.name, r.condition_kind, r.host_id, r.unit_name, r.severity,
               r.channel_id
        FROM alert_rule r JOIN alert_channel ch ON ch.channel_id = r.channel_id
        WHERE r.enabled = TRUE AND ch.enabled = TRUE LIMIT 500"""));
    var hostRows = store.transaction(c -> query(c, """
        SELECT h.id, h.hostname, h.status,
               (SELECT a.helper_status FROM agent a WHERE a.host_id = h.id
                 ORDER BY a.last_seen DESC NULLS LAST, a.id LIMIT 1) AS helper_status
        FROM host h ORDER BY h.hostname LIMIT 1000"""));
    var serviceRows = store.transaction(c -> query(c,
        "SELECT host_id, unit_name, active_state FROM system_service_observation LIMIT 5000"));

    List<AlertEvaluator.HostObservation> hosts = new ArrayList<>(hostRows.size());
    for (var row : hostRows) {
      hosts.add(new AlertEvaluator.HostObservation(uuid(row.get("id")),
          String.valueOf(row.get("hostname")), text(row.get("status")),
          text(row.get("helper_status"))));
    }
    List<AlertEvaluator.ServiceObservation> services = new ArrayList<>(serviceRows.size());
    for (var row : serviceRows) {
      services.add(new AlertEvaluator.ServiceObservation(uuid(row.get("host_id")),
          String.valueOf(row.get("unit_name")), text(row.get("active_state"))));
    }
    List<AlertEvaluator.Rule> rules = new ArrayList<>(ruleRows.size());
    Map<UUID, UUID> channelByRule = new LinkedHashMap<>();
    for (var row : ruleRows) {
      UUID ruleId = uuid(row.get("rule_id"));
      channelByRule.put(ruleId, row.get("channel_id") == null ? null : uuid(row.get("channel_id")));
      rules.add(new AlertEvaluator.Rule(ruleId, String.valueOf(row.get("name")),
          String.valueOf(row.get("condition_kind")),
          row.get("host_id") == null ? null : uuid(row.get("host_id")),
          text(row.get("unit_name")), String.valueOf(row.get("severity")), true));
    }

    var alerts = AlertEvaluator.evaluate(rules, hosts, services);
    Map<UUID, Map<String, Object>> channelRows = channels();
    Set<String> firing = new LinkedHashSet<>();
    int delivered = 0;
    for (var alert : alerts) {
      String key = dedupeKey(alert);
      firing.add(key);
      UUID channelId = channelByRule.get(alert.ruleId());
      if (channelId == null) continue;
      var channel = channelRows.get(channelId);
      if (channel == null) continue;
      if (!state(key).isEmpty()) continue;

      Map<String, Object> payload = payload("alert.firing", alert);
      Delivery delivery = deliver(String.valueOf(channel.get("url")), payload);
      long now = System.currentTimeMillis();
      record(channelId, channel, alert, "FIRING", key, delivery);
      if (!delivery.succeeded()) continue;
      store.transaction(c -> {
        update(c, "INSERT INTO alert_firing_state (dedupe_key,rule_id,channel_id,hostname,since,"
            + "last_sent_at,rule_name,severity,condition_kind,detail) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?)", key, alert.ruleId(), channelId, alert.hostname(),
            now, now, alert.ruleName(), alert.severity(), alert.condition(), alert.detail());
        return null;
      });
      delivered++;
    }

    // Recovery: everything still marked as firing but no longer evaluated as such.
    for (Firing previous : firingState()) {
      if (firing.contains(previous.key())) continue;
      var channel = previous.channelId() == null ? null : channelRows.get(previous.channelId());
      if (channel != null && Boolean.TRUE.equals(channel.get("notify_recovery"))) {
        Map<String, Object> payload = payload("alert.recovered", previous);
        Delivery delivery = deliver(String.valueOf(channel.get("url")), payload);
        record(previous.channelId(), channel, previous, "RECOVERED", previous.key(), delivery);
        if (delivery.succeeded()) delivered++;
      }
      store.transaction(c -> update(c,
          "DELETE FROM alert_firing_state WHERE dedupe_key=?", previous.key()));
    }
    return delivered;
  }

  /** Sends one JSON body to a channel; used by both the notifier and the channel test button. */
  public Delivery deliver(String url, Map<String, Object> payload) {
    try {
      String body = JSON.writeValueAsString(payload);
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .timeout(DELIVERY_TIMEOUT)
          .header("Content-Type", "application/json")
          .header("User-Agent", "NaNPanel")
          .header("X-NaNPanel-Event", String.valueOf(payload.getOrDefault("event", "alert.firing")))
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        return new Delivery(true, null);
      }
      return new Delivery(false, "HTTP " + response.statusCode());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new Delivery(false, "投递被中断");
    } catch (Exception failure) {
      String message = failure.getMessage();
      return new Delivery(false, message == null ? failure.getClass().getSimpleName() : message);
    }
  }

  static String dedupeKey(AlertEvaluator.Alert alert) {
    return alert.ruleId() + "|" + alert.hostname() + "|" + alert.detail();
  }

  static Map<String, Object> payload(String event, AlertEvaluator.Alert alert) {
    Map<String, Object> payload = basePayload(event, alert.ruleId(), alert.ruleName(),
        alert.severity(), alert.condition(), alert.hostname(), alert.detail());
    payload.put("at", Instant.now().toString());
    return payload;
  }

  static Map<String, Object> payload(String event, Firing firing) {
    Map<String, Object> payload = basePayload(event, firing.ruleId(), firing.ruleName(),
        firing.severity(), firing.condition(), firing.hostname(), firing.detail());
    payload.put("at", Instant.now().toString());
    return payload;
  }

  private static Map<String, Object> basePayload(String event, UUID ruleId, String ruleName,
      String severity, String condition, String hostname, String detail) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("event", event);
    payload.put("ruleId", ruleId == null ? null : ruleId.toString());
    payload.put("ruleName", ruleName);
    payload.put("severity", severity);
    payload.put("condition", condition);
    payload.put("hostname", hostname);
    payload.put("detail", detail);
    payload.put("source", "nanpanel");
    return payload;
  }

  private void record(UUID channelId, Map<String, Object> channel, AlertEvaluator.Alert alert,
      String kind, String key, Delivery delivery) {
    long now = System.currentTimeMillis();
    store.transaction(c -> {
      if (channelId != null) {
        update(c, "UPDATE alert_channel SET last_status=?, last_at=?, last_error=?, updated_at=? "
            + "WHERE channel_id=?", delivery.succeeded() ? "SUCCEEDED" : "FAILED", now,
            truncate(delivery.error(), 300), now, channelId);
      }
      update(c, "INSERT INTO alert_event (event_id,dedupe_key,rule_id,rule_name,channel_id,"
              + "channel_name,kind,severity,condition_kind,hostname,detail,status,error,created_at) "
              + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(), key, alert.ruleId(), alert.ruleName(), channelId,
          channel == null ? null : String.valueOf(channel.get("name")), kind, alert.severity(),
          alert.condition(), alert.hostname(), truncate(alert.detail(), 500),
          delivery.succeeded() ? "SUCCEEDED" : "FAILED", truncate(delivery.error(), 300), now);
      return null;
    });
  }

  private void record(UUID channelId, Map<String, Object> channel, Firing firing,
      String kind, String key, Delivery delivery) {
    long now = System.currentTimeMillis();
    store.transaction(c -> {
      if (channelId != null) {
        update(c, "UPDATE alert_channel SET last_status=?, last_at=?, last_error=?, updated_at=? "
            + "WHERE channel_id=?", delivery.succeeded() ? "SUCCEEDED" : "FAILED", now,
            truncate(delivery.error(), 300), now, channelId);
      }
      update(c, "INSERT INTO alert_event (event_id,dedupe_key,rule_id,rule_name,channel_id,"
              + "channel_name,kind,severity,condition_kind,hostname,detail,status,error,created_at) "
              + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(), key, firing.ruleId(), firing.ruleName(), channelId,
          channel == null ? null : String.valueOf(channel.get("name")), kind, firing.severity(),
          firing.condition(), firing.hostname(), truncate(firing.detail(), 500),
          delivery.succeeded() ? "SUCCEEDED" : "FAILED", truncate(delivery.error(), 300), now);
      return null;
    });
  }

  private Map<UUID, Map<String, Object>> channels() {
    var rows = store.transaction(c -> query(c,
        "SELECT channel_id, name, kind, url, enabled, notify_recovery FROM alert_channel "
            + "WHERE enabled = TRUE"));
    Map<UUID, Map<String, Object>> byId = new LinkedHashMap<>();
    for (var row : rows) byId.put(uuid(row.get("channel_id")), row);
    return byId;
  }

  private List<Firing> firingState() {
    var rows = store.transaction(c -> query(c, """
        SELECT dedupe_key, rule_id, channel_id, rule_name, severity, condition_kind, hostname,
               detail FROM alert_firing_state"""));
    List<Firing> state = new ArrayList<>(rows.size());
    for (var row : rows) {
      state.add(new Firing(uuid(row.get("rule_id")), uuid(row.get("channel_id")),
          text(row.get("rule_name")), text(row.get("severity")), text(row.get("condition_kind")),
          text(row.get("hostname")), text(row.get("detail")),
          String.valueOf(row.get("dedupe_key"))));
    }
    return state;
  }

  private List<Map<String, Object>> state(String key) {
    return store.transaction(c -> query(c,
        "SELECT dedupe_key FROM alert_firing_state WHERE dedupe_key=?", key));
  }

  private static String truncate(String value, int max) {
    if (value == null) return null;
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static UUID uuid(Object value) {
    return value == null ? null : UUID.fromString(String.valueOf(value));
  }

  private static String text(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
