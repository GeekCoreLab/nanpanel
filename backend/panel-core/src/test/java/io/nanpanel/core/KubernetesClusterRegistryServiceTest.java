package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KubernetesClusterRegistryServiceTest {
  @Test
  void cloudCoreStatusDistinguishesMissingAndUnhealthyPods() {
    assertEquals("NOT_OBSERVED", KubernetesClusterRegistryService.cloudCoreStatus(List.of()));
    assertEquals("HEALTHY", KubernetesClusterRegistryService.cloudCoreStatus(
        List.of(pod("Running", true), pod("Running", true))));
    assertEquals("DEGRADED", KubernetesClusterRegistryService.cloudCoreStatus(
        List.of(pod("Running", true), pod("Pending", false))));
    assertEquals("UNAVAILABLE", KubernetesClusterRegistryService.cloudCoreStatus(
        List.of(pod("Pending", false))));
  }

  @Test
  void edgeCoreRegistrationStatusUsesNodePresenceEdgeRoleAndReadiness() {
    assertEquals("NOT_EDGE_NODE",
        KubernetesClusterRegistryService.edgeCoreRegistrationStatus(false, true, true));
    assertEquals("NOT_PRESENT",
        KubernetesClusterRegistryService.edgeCoreRegistrationStatus(true, false, false));
    assertEquals("REGISTERED_NOT_READY",
        KubernetesClusterRegistryService.edgeCoreRegistrationStatus(true, true, false));
    assertEquals("REGISTERED_READY",
        KubernetesClusterRegistryService.edgeCoreRegistrationStatus(true, true, true));
  }

  @Test
  void edgeCoreProcessStatusRequiresFreshMappedHostObservation() {
    Instant now = Instant.parse("2026-10-21T12:00:00Z");
    UUID hostId = UUID.randomUUID();
    assertEquals("NOT_APPLICABLE", KubernetesClusterRegistryService.edgeCoreProcessStatus(
        false, null, null, null, null, now, 90));
    assertEquals("NOT_MAPPED", KubernetesClusterRegistryService.edgeCoreProcessStatus(
        true, null, null, null, null, now, 90));
    assertEquals("NOT_OBSERVED", KubernetesClusterRegistryService.edgeCoreProcessStatus(
        true, hostId, null, null, now, now, 90));
    assertEquals("ACTIVE", KubernetesClusterRegistryService.edgeCoreProcessStatus(
        true, hostId, "ACTIVE", now.minusSeconds(30), now, now, 90));
    assertEquals("INACTIVE", KubernetesClusterRegistryService.edgeCoreProcessStatus(
        true, hostId, "FAILED", now.minusSeconds(30), now, now, 90));
    assertEquals("STALE", KubernetesClusterRegistryService.edgeCoreProcessStatus(
        true, hostId, "ACTIVE", now.minusSeconds(91), now, now, 90));
    assertEquals("STALE", KubernetesClusterRegistryService.edgeCoreProcessStatus(
        true, hostId, "ACTIVE", now.minusSeconds(30), null, now, 90));
  }

  private static Pod pod(String phase, boolean ready) {
    return new PodBuilder().withStatus(new PodStatusBuilder().withPhase(phase)
        .withContainerStatuses(List.of(new ContainerStatusBuilder()
            .withName("cloudcore").withReady(ready).build())).build()).build();
  }
}
