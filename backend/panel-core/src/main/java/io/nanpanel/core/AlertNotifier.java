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
 * Delivers firing alerts to their channel. The panel only ever stores observations, so an
 * alert has no "event" of its own — this notifier derives one: it evaluates the rules on a
 * fixed interval, and only dispatches subjects that were not already firing (deduped in
 * {@code alert_firing_state}), so a still-offline host does not page once per minute.
 *
 * <p>Delivery is a plain HTTPS POST with a short timeout. A failed delivery is recorded on
 * the channel and retried on the next tick, because the subject stays in the firing set
 * until it recovers.
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

  /** Runs on a fixed interval; the test calls it directly so no clock is involved. */
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

  /** Returns the number of notifications delivered on this pass. */
  public synchronized int runOnce() {
    var ruleRows = store.transaction(c -> query(c, """
        SELECT r.rule_id, r.name, r.condition_kind, r.host_id, r.unit_name, r.severity,
               r.channel_id
        FROM alert_rule r JOIN alert_channel ch ON ch.channel_id = r.channel_id
        WHERE r.enabled = TRUE AND ch.enabled = TRUE LIMIT 500"""));
    if (ruleRows.isEmpty()) {
      clearState(Set.of());
      return 0;
    }
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
    Map<String, Map<String, Object>> channelRows = channels();
    Set<String> firing = new LinkedHashSet<>();
    int delivered = 0;
    for (var alert : alerts) {
      String key = dedupeKey(alert);
      firing.add(key);
      UUID channelId = channelByRule.get(alert.ruleId());
      if (channelId == null) continue;
      var channel = channelRows.get(channelId.toString());
      if (channel == null) continue;
      boolean alreadyFiring = store.transaction(c -> !query(c,
          "SELECT dedupe_key FROM alert_firing_state WHERE dedupe_key=?", key).isEmpty());
      if (alreadyFiring) continue;

      Map<String, Object> payload = payload(alert);
      Delivery delivery = deliver(String.valueOf(channel.get("url")), payload);
      long now = System.currentTimeMillis();
      store.transaction(c -> {
        update(c, "UPDATE alert_channel SET last_status=?, last_at=?, last_error=?, updated_at=? "
            + "WHERE channel_id=?", delivery.succeeded() ? "SUCCEEDED" : "FAILED", now,
            truncate(delivery.error()), now, channelId);
        if (delivery.succeeded()) {
          update(c, "INSERT INTO alert_firing_state (dedupe_key,rule_id,channel_id,hostname,"
              + "since,last_sent_at) VALUES (?,?,?,?,?,?)", key, alert.ruleId(), channelId,
              alert.hostname(), now, now);
          return null;
        }
        // Delivery failed: leave the subject out of the state table so the next tick retries.
        return null;
      });
      if (delivery.succeeded()) delivered++;
    }
    // Anything that stopped firing is dropped so a later relapse notifies again.
    clearState(firing);
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

  static Map<String, Object> payload(AlertEvaluator.Alert alert) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("event", "alert.firing");
    payload.put("ruleId", String.valueOf(alert.ruleId()));
    payload.put("ruleName", alert.ruleName());
    payload.put("severity", alert.severity());
    payload.put("condition", alert.condition());
    payload.put("hostname", alert.hostname());
    payload.put("detail", alert.detail());
    payload.put("firedAt", Instant.now().toString());
    payload.put("source", "nanpanel");
    return payload;
  }

  private Map<String, Map<String, Object>> channels() {
    var rows = store.transaction(c -> query(c,
        "SELECT channel_id, name, kind, url, enabled FROM alert_channel WHERE enabled = TRUE"));
    Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
    for (var row : rows) {
      byId.put(String.valueOf(row.get("channel_id")), row);
    }
    return byId;
  }

  private void clearState(Set<String> keep) {
    var rows = store.transaction(c -> query(c, "SELECT dedupe_key FROM alert_firing_state"));
    for (var row : rows) {
      String key = String.valueOf(row.get("dedupe_key"));
      if (keep.contains(key)) continue;
      store.transaction(c -> update(c,
          "DELETE FROM alert_firing_state WHERE dedupe_key=?", key));
    }
  }

  private static String truncate(String value) {
    if (value == null) return null;
    return value.length() <= 300 ? value : value.substring(0, 300);
  }

  private static UUID uuid(Object value) {
    return value == null ? null : UUID.fromString(String.valueOf(value));
  }

  private static String text(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
