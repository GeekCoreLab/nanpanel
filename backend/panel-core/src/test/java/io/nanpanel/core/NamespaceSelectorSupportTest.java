package io.nanpanel.core;

import static io.nanpanel.core.KubernetesNamespaceResourceInventoryService.podLabelsFor;
import static io.nanpanel.core.KubernetesNamespaceResourceInventoryService.selectorFor;
import static io.nanpanel.core.KubernetesNamespaceResourceInventoryService.ingressRoutesFor;
import static io.nanpanel.core.KubernetesNamespaceResourceInventoryService.stringMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NamespaceSelectorSupportTest {

  @Test
  void serviceSelectorIsExposedAndOtherKindsAreEmpty() {
    Map<String, Object> spec = Map.of("selector", Map.of("app", "web", "tier", "front"));
    assertEquals(Map.of("app", "web", "tier", "front"), selectorFor("Service", spec));
    assertTrue(selectorFor("Deployment", spec).isEmpty());
    assertTrue(selectorFor("Service", Map.of()).isEmpty());
  }

  @Test
  void workloadPodTemplateLabelsAreExposed() {
    Map<String, Object> spec = Map.of("template",
        Map.of("metadata", Map.of("labels", Map.of("app", "orders", "version", "v2"))));
    assertEquals(Map.of("app", "orders", "version", "v2"), podLabelsFor("Deployment", spec, null));
    assertEquals(Map.of("app", "orders", "version", "v2"), podLabelsFor("StatefulSet", spec, null));
    assertTrue(podLabelsFor("Service", spec, null).isEmpty());
  }

  @Test
  void podUsesItsOwnMetadataLabels() {
    assertEquals(Map.of("app", "web"), podLabelsFor("Pod", Map.of(), Map.of("app", "web")));
    assertTrue(podLabelsFor("Pod", Map.of(), null).isEmpty());
  }

  @Test
  void stringMapBoundsCountAndSkipsInvalidEntries() {
    assertEquals(Map.of(), stringMap(null));
    Map<String, Object> tooMany = new LinkedHashMap<>();
    for (int i = 0; i < 40; i++) tooMany.put("k" + i, "v" + i);
    assertEquals(32, stringMap(tooMany).size());
    Map<String, Object> mixed = new LinkedHashMap<>();
    mixed.put("ok", "value");
    mixed.put("", "blank-key");
    mixed.put("null-value", null);
    mixed.put("x".repeat(300), "long-key");
    mixed.put("long-value", "y".repeat(200));
    assertEquals(Map.of("ok", "value"), stringMap(mixed));
  }

  @Test
  void ingressRulesYieldStructuredServiceRoutes() {
    Map<String, Object> spec = Map.of("rules", List.of(
        Map.of("host", "shop.example.test", "http", Map.of("paths", List.of(
            Map.of("path", "/", "backend", Map.of("service",
                Map.of("name", "shop", "port", Map.of("number", 80)))),
            Map.of("path", "/api", "backend", Map.of("service",
                Map.of("name", "api", "port", Map.of("number", 8080))))))),
        Map.of("http", Map.of("paths", List.of(
            Map.of("path", "/health", "backend", Map.of("service",
                Map.of("name", "health", "port", Map.of("number", 9000)))))))));

    var routes = ingressRoutesFor("Ingress", spec);
    assertEquals(3, routes.size());
    assertEquals("shop.example.test", routes.get(0).host());
    assertEquals("/", routes.get(0).path());
    assertEquals("shop", routes.get(0).serviceName());
    assertEquals(80, routes.get(0).port());
    assertEquals("/api", routes.get(1).path());
    assertEquals("", routes.get(2).host());
    assertEquals("health", routes.get(2).serviceName());
  }

  @Test
  void ingressDefaultBackendBecomesAWildcardRoute() {
    Map<String, Object> spec = Map.of("defaultBackend", Map.of("service",
        Map.of("name", "fallback", "port", Map.of("number", 8080))));
    var routes = ingressRoutesFor("Ingress", spec);
    assertEquals(1, routes.size());
    assertEquals("", routes.get(0).host());
    assertEquals("*", routes.get(0).path());
    assertEquals("fallback", routes.get(0).serviceName());
    assertEquals(8080, routes.get(0).port());
  }

  @Test
  void nonIngressKindsAndMalformedRulesYieldNothing() {
    assertTrue(ingressRoutesFor("Service", Map.of("rules", List.of())).isEmpty());
    assertTrue(ingressRoutesFor("Ingress", Map.of()).isEmpty());
    assertTrue(ingressRoutesFor("Ingress", Map.of("rules", List.of(
        Map.of("host", "x.test", "http", Map.of("paths", List.of(
            Map.of("path", "/", "backend", Map.of("service", Map.of("port", Map.of("number", 80))))))))))
        .isEmpty());
  }

  @Test
  void ingressRoutesAreBounded() {
    var paths = new java.util.ArrayList<Map<String, Object>>();
    for (int i = 0; i < 40; i++) {
      paths.add(Map.of("path", "/p" + i, "backend", Map.of("service",
          Map.of("name", "svc" + i, "port", Map.of("number", 80)))));
    }
    var routes = ingressRoutesFor("Ingress", Map.of("rules", List.of(
        Map.of("host", "x.test", "http", Map.of("paths", paths)))));
    assertEquals(32, routes.size());
  }
}
