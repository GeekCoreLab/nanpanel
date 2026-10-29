package io.nanpanel.core;

import static io.nanpanel.core.KubernetesNamespaceResourceInventoryService.podLabelsFor;
import static io.nanpanel.core.KubernetesNamespaceResourceInventoryService.selectorFor;
import static io.nanpanel.core.KubernetesNamespaceResourceInventoryService.stringMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
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
}