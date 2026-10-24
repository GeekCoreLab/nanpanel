package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KubernetesApplicationReleaseServiceTest {
  private static KubernetesApplicationReleaseService.CreateRequest request(
      String name, String image, Integer replicas, Integer port, String hostname,
      BigDecimal maxErrorRate, String reason) {
    return new KubernetesApplicationReleaseService.CreateRequest(name,
        "registry.example.com/team/web:2.4.0", image, replicas, port,
        "public-gateway", hostname, "prometheus", maxErrorRate, reason);
  }

  @Test
  void acceptsAConstrainedReleaseRequest() {
    assertDoesNotThrow(() -> KubernetesApplicationReleaseService.validate("production",
        request("web-api", "registry.example.com/team/web:2.4.1", 3, 8080,
            "web.example.com", new BigDecimal("0.05"), "Release 2.4.1")));
  }

  @Test
  void rejectsInvalidNamesImagesPortsAndThresholds() {
    var cases = new KubernetesApplicationReleaseService.CreateRequest[] {
        request("Web API", "registry.example/web:2", 1, 8080, "web.example.com", null, "release"),
        request("web", "registry.example/web image:2", 1, 8080, "web.example.com", null, "release"),
        request("web", "registry.example/web:2", 1, 0, "web.example.com", null, "release"),
        request("web", "registry.example/web:2", 21, 8080, "web.example.com", null, "release"),
        request("web", "registry.example/web:2", 1, 8080, "WEB.example.com", null, "release"),
        request("web", "registry.example/web:2", 1, 8080, "web.example.com",
            new BigDecimal("0.005"), "release"),
        request("web", "registry.example/web:2", 1, 8080, "web.example.com",
            new BigDecimal("0.51"), "release"),
        request("web", "registry.example.com/team/web:2.4.0", 1, 8080,
            "web.example.com", null, "release"),
        request("web", "registry.example/web:2", 1, 8080, "web.example.com", null, "\n")
    };

    for (var invalid : cases) {
      var failure = assertThrows(KubernetesApplicationReleaseService.ReleaseException.class,
          () -> KubernetesApplicationReleaseService.validate("production", invalid));
      assertEquals("VALIDATION_FAILED", failure.code());
    }
  }

  @Test
  void rejectsAnInvalidNamespaceAndMissingRequest() {
    for (String namespace : new String[] {"", "Prod", "bad.namespace"}) {
      var failure = assertThrows(KubernetesApplicationReleaseService.ReleaseException.class,
          () -> KubernetesApplicationReleaseService.validate(namespace,
              request("web", "registry.example/web:2", 1, 8080,
                  "web.example.com", null, "release")));
      assertEquals("VALIDATION_FAILED", failure.code());
    }
    var missing = assertThrows(KubernetesApplicationReleaseService.ReleaseException.class,
        () -> KubernetesApplicationReleaseService.validate("production", null));
    assertEquals("VALIDATION_FAILED", missing.code());
  }

  @Test
  void mapsReleaseStatesToAllowedAuditOutcomes() {
    assertEquals("ACCEPTED", KubernetesApplicationReleaseService.auditOutcome("STARTING"));
    assertEquals("ACCEPTED", KubernetesApplicationReleaseService.auditOutcome("RUNNING"));
    assertEquals("ACCEPTED", KubernetesApplicationReleaseService.auditOutcome("PAUSED"));
    assertEquals("SUCCEEDED", KubernetesApplicationReleaseService.auditOutcome("ROLLED_BACK"));
    assertEquals("SUCCEEDED", KubernetesApplicationReleaseService.auditOutcome("SUCCEEDED"));
    assertEquals("FAILED", KubernetesApplicationReleaseService.auditOutcome("FAILED"));
    assertEquals("UNKNOWN", KubernetesApplicationReleaseService.auditOutcome("UNKNOWN"));
  }

  @Test
  void acceptsArgoAnalysisRunApplicationLabels() {
    assertEquals(true, KubernetesApplicationReleaseService.analysisRunApplicationMatches(
        "web-api", Map.of("app.kubernetes.io/name", "web-api")));
    assertEquals(true, KubernetesApplicationReleaseService.analysisRunApplicationMatches(
        "web-api", Map.of("app", "web-api")));
    assertEquals(false, KubernetesApplicationReleaseService.analysisRunApplicationMatches(
        "web-api", Map.of("app.kubernetes.io/name", "other")));
  }

  @Test
  void findsInlineAndBackgroundArgoAnalysisRuns() {
    assertEquals("inline-run", KubernetesApplicationReleaseService.backgroundAnalysisName(
        Map.of("canary", Map.of("currentStepAnalysisRunStatus",
            Map.of("name", "inline-run", "status", "Running")))));
    assertEquals("background-run", KubernetesApplicationReleaseService.backgroundAnalysisName(
        Map.of("canary", Map.of("currentBackgroundAnalysisRunStatus",
            Map.of("name", "background-run", "status", "Running")))));
  }
}
