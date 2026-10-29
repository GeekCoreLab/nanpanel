package io.nanpanel.core;

import static io.nanpanel.core.AlertEvaluator.evaluate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nanpanel.core.AlertEvaluator.Alert;
import io.nanpanel.core.AlertEvaluator.HostObservation;
import io.nanpanel.core.AlertEvaluator.Rule;
import io.nanpanel.core.AlertEvaluator.ServiceObservation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AlertEvaluatorTest {

  private static final UUID HOST_A = UUID.randomUUID();
  private static final UUID HOST_B = UUID.randomUUID();

  private static Rule rule(String condition, UUID hostId, String unitName, boolean enabled) {
    return new Rule(UUID.randomUUID(), condition + "-rule", condition, hostId, unitName,
        "CRITICAL", enabled);
  }

  @Test
  void firesWhenAHostIsNotOnline() {
    var alerts = evaluate(
        List.of(rule(AlertEvaluator.HOST_NOT_ONLINE, null, null, true)),
        List.of(new HostObservation(HOST_A, "host-a", "OFFLINE", "AVAILABLE"),
            new HostObservation(HOST_B, "host-b", "ONLINE", "AVAILABLE")),
        List.of());
    assertEquals(1, alerts.size());
    assertEquals("host-a", alerts.get(0).hostname());
    assertEquals("status=OFFLINE", alerts.get(0).detail());
  }

  @Test
  void disabledRulesNeverFire() {
    var alerts = evaluate(
        List.of(rule(AlertEvaluator.HOST_NOT_ONLINE, null, null, false)),
        List.of(new HostObservation(HOST_A, "host-a", "OFFLINE", "AVAILABLE")),
        List.of());
    assertTrue(alerts.isEmpty());
  }

  @Test
  void aHostScopedRuleIgnoresOtherHosts() {
    var alerts = evaluate(
        List.of(rule(AlertEvaluator.HOST_NOT_ONLINE, HOST_B, null, true)),
        List.of(new HostObservation(HOST_A, "host-a", "OFFLINE", "AVAILABLE"),
            new HostObservation(HOST_B, "host-b", "OFFLINE", "AVAILABLE")),
        List.of());
    assertEquals(1, alerts.size());
    assertEquals("host-b", alerts.get(0).hostname());
  }

  @Test
  void firesWhenTheHelperIsUnavailableOrUnknown() {
    var alerts = evaluate(
        List.of(rule(AlertEvaluator.HELPER_UNAVAILABLE, null, null, true)),
        List.of(new HostObservation(HOST_A, "host-a", "ONLINE", "UNAVAILABLE"),
            new HostObservation(HOST_B, "host-b", "ONLINE", null)),
        List.of());
    assertEquals(2, alerts.size());
    assertEquals("helper=UNAVAILABLE", alerts.get(0).detail());
    assertEquals("helper=UNKNOWN", alerts.get(1).detail());
  }

  @Test
  void serviceInactiveMatchesOnlyTheRequestedUnit() {
    var unitScoped = new Rule(UUID.randomUUID(), "ssh", AlertEvaluator.SERVICE_INACTIVE, HOST_A,
        "sshd.service", "WARNING", true);
    var alerts = evaluate(List.of(unitScoped),
        List.of(new HostObservation(HOST_A, "host-a", "ONLINE", "AVAILABLE")),
        List.of(new ServiceObservation(HOST_A, "sshd.service", "failed"),
            new ServiceObservation(HOST_A, "nginx.service", "inactive"),
            new ServiceObservation(HOST_A, "containerd.service", "active")));
    assertEquals(1, alerts.size());
    assertEquals("sshd.service · failed", alerts.get(0).detail());
    assertEquals("host-a", alerts.get(0).hostname());
  }

  @Test
  void resultsAreSortedCriticalFirstThenByName() {
    var warning = new Rule(UUID.randomUUID(), "z-warning", AlertEvaluator.HOST_NOT_ONLINE, null,
        null, "WARNING", true);
    var critical = new Rule(UUID.randomUUID(), "a-critical", AlertEvaluator.HOST_NOT_ONLINE, null,
        null, "CRITICAL", true);
    var alerts = evaluate(List.of(warning, critical),
        List.of(new HostObservation(HOST_A, "host-a", "OFFLINE", "AVAILABLE"),
            new HostObservation(HOST_B, "host-b", "OFFLINE", "AVAILABLE")),
        List.of());
    assertEquals(List.of("CRITICAL", "CRITICAL", "WARNING", "WARNING"),
        alerts.stream().map(Alert::severity).toList());
  }

  @Test
  void unknownConditionsAreIgnoredDefensively() {
    var bogus = new Rule(UUID.randomUUID(), "bogus", "SOMETHING_ELSE", null, null, "CRITICAL", true);
    assertTrue(evaluate(List.of(bogus),
        List.of(new HostObservation(HOST_A, "host-a", "OFFLINE", "UNAVAILABLE")), List.of())
        .isEmpty());
  }
}
