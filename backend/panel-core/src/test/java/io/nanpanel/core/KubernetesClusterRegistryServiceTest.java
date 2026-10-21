package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import java.util.List;
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

  private static Pod pod(String phase, boolean ready) {
    return new PodBuilder().withStatus(new PodStatusBuilder().withPhase(phase)
        .withContainerStatuses(List.of(new ContainerStatusBuilder()
            .withName("cloudcore").withReady(ready).build())).build()).build();
  }
}
