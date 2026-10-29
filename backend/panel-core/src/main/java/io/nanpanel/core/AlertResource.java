package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.query;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Live alert evaluation: reads the observations NaNPanel already stores (host status,
 * latest helper status, systemd unit state) and applies the enabled rules. Nothing is
 * persisted, so an alert disappears as soon as the underlying observation recovers.
 */
@Path("/api/v1/alerts")
@Produces(MediaType.APPLICATION_JSON)
public class AlertResource {

  @Inject AuthStore store;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;

  @SuppressWarnings("unchecked")
  private void session() {
    Map<String, Object> value = (Map<String, Object>) request.getProperty("session");
    if (value == null || value.get("user_id") == null) {
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    }
  }

  @GET
  public Map<String, Object> alerts() {
    session();
    var hostRows = store.transaction(c -> query(c, """
        SELECT h.id, h.hostname, h.status,
               (SELECT a.helper_status FROM agent a WHERE a.host_id = h.id
                 ORDER BY a.last_seen DESC NULLS LAST, a.id LIMIT 1) AS helper_status
        FROM host h ORDER BY h.hostname LIMIT 1000"""));
    var serviceRows = store.transaction(c -> query(c,
        "SELECT host_id, unit_name, active_state FROM system_service_observation LIMIT 5000"));
    var ruleRows = store.transaction(c -> query(c, """
        SELECT rule_id, name, condition_kind, host_id, unit_name, severity
        FROM alert_rule WHERE enabled = TRUE LIMIT 500"""));

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
    for (var row : ruleRows) {
      rules.add(new AlertEvaluator.Rule(uuid(row.get("rule_id")), String.valueOf(row.get("name")),
          String.valueOf(row.get("condition_kind")),
          row.get("host_id") == null ? null : uuid(row.get("host_id")),
          text(row.get("unit_name")), String.valueOf(row.get("severity")), true));
    }

    var alerts = AlertEvaluator.evaluate(rules, hosts, services);
    var items = new ArrayList<Map<String, Object>>(alerts.size());
    for (var alert : alerts) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("ruleId", String.valueOf(alert.ruleId()));
      item.put("ruleName", alert.ruleName());
      item.put("severity", alert.severity());
      item.put("condition", alert.condition());
      item.put("hostname", alert.hostname());
      item.put("detail", alert.detail());
      items.add(item);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("generatedAt", Instant.now().toString());
    result.put("evaluatedRules", rules.size());
    result.put("alerts", items);
    return result;
  }

  private static String text(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static UUID uuid(Object value) {
    return UUID.fromString(String.valueOf(value));
  }
}
