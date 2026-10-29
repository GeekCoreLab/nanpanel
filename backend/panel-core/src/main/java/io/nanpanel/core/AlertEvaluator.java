package io.nanpanel.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Evaluates alert rules against the observations NaNPanel already collects (host
 * status, helper availability, systemd unit state). Pure and side-effect free so the
 * matching semantics are unit-tested and the resource layer only loads and maps rows.
 *
 * A rule with {@code hostId == null} applies to every host; a rule with
 * {@code unitName != null} narrows {@code SERVICE_INACTIVE} to one unit.
 */
public final class AlertEvaluator {

  private AlertEvaluator() {}

  public static final String HOST_NOT_ONLINE = "HOST_NOT_ONLINE";
  public static final String HELPER_UNAVAILABLE = "HELPER_UNAVAILABLE";
  public static final String SERVICE_INACTIVE = "SERVICE_INACTIVE";
  public static final java.util.Set<String> CONDITIONS =
      java.util.Set.of(HOST_NOT_ONLINE, HELPER_UNAVAILABLE, SERVICE_INACTIVE);
  public static final java.util.Set<String> SEVERITIES = java.util.Set.of("WARNING", "CRITICAL");

  public record Rule(UUID id, String name, String condition, UUID hostId, String unitName,
                     String severity, boolean enabled) {}

  public record HostObservation(UUID hostId, String hostname, String status, String helperStatus) {}

  public record ServiceObservation(UUID hostId, String unitName, String activeState) {}

  public record Alert(UUID ruleId, String ruleName, String severity, String condition,
                      String hostname, String detail) {}

  public static List<Alert> evaluate(List<Rule> rules, List<HostObservation> hosts,
      List<ServiceObservation> services) {
    List<Alert> alerts = new ArrayList<>();
    for (Rule rule : rules) {
      if (!rule.enabled() || !CONDITIONS.contains(rule.condition())) continue;
      if (SERVICE_INACTIVE.equals(rule.condition())) {
        for (ServiceObservation service : services) {
          if (rule.hostId() != null && !rule.hostId().equals(service.hostId())) continue;
          if (rule.unitName() != null && !rule.unitName().equals(service.unitName())) continue;
          if ("active".equals(service.activeState())) continue;
          alerts.add(new Alert(rule.id(), rule.name(), rule.severity(), rule.condition(),
              hostName(hosts, service.hostId()),
              service.unitName() + " · " + service.activeState()));
        }
        continue;
      }
      for (HostObservation host : hosts) {
        if (rule.hostId() != null && !rule.hostId().equals(host.hostId())) continue;
        if (HOST_NOT_ONLINE.equals(rule.condition())) {
          if ("ONLINE".equals(host.status())) continue;
          alerts.add(new Alert(rule.id(), rule.name(), rule.severity(), rule.condition(),
              host.hostname(), "status=" + host.status()));
        } else if (HELPER_UNAVAILABLE.equals(rule.condition())) {
          if ("AVAILABLE".equals(host.helperStatus())) continue;
          alerts.add(new Alert(rule.id(), rule.name(), rule.severity(), rule.condition(),
              host.hostname(), "helper=" + (host.helperStatus() == null ? "UNKNOWN" : host.helperStatus())));
        }
      }
    }
    alerts.sort(Comparator.comparing((Alert alert) -> !"CRITICAL".equals(alert.severity()))
        .thenComparing(Alert::ruleName).thenComparing(Alert::hostname));
    return alerts;
  }

  private static String hostName(List<HostObservation> hosts, UUID hostId) {
    for (HostObservation host : hosts) {
      if (host.hostId().equals(hostId)) return host.hostname();
    }
    return String.valueOf(hostId);
  }
}
