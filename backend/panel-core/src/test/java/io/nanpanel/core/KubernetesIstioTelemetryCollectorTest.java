package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.PodBuilder;
import io.nanpanel.cluster.KubernetesServiceDiscovery.IstioTrafficEdge;
import java.util.HashMap;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KubernetesIstioTelemetryCollectorTest {
  @Test
  void detectsReadyIstioNativeSidecarFromInitContainerStatus() {
    var pod = new PodBuilder()
        .withNewMetadata().withName("mesh-http").withNamespace("nanpanel-mesh").endMetadata()
        .withNewSpec()
            .addNewContainer().withName("http").withImage("example/http:1").endContainer()
            .addNewInitContainer().withName("istio-init").withImage("istio/proxyv2:1.31.1")
                .endInitContainer()
            .addNewInitContainer().withName("istio-proxy").withImage("istio/proxyv2:1.31.1")
                .withRestartPolicy("Always").endInitContainer()
        .endSpec()
        .withNewStatus().withPodIP("10.42.0.10")
            .addNewInitContainerStatus().withName("istio-proxy").withReady(true)
                .endInitContainerStatus()
        .endStatus()
        .build();

    var observation = KubernetesIstioTelemetryCollector.observePod(pod);

    assertTrue(observation.proxyPresent());
    assertTrue(observation.proxyReady());
    assertEquals("1.31.1", observation.proxyVersion());
  }

  @Test
  void detectsReadyIstioRegularSidecarFromContainerStatus() {
    var pod = new PodBuilder()
        .withNewMetadata().withName("legacy-mesh-http").withNamespace("nanpanel-mesh")
            .endMetadata()
        .withNewSpec()
            .addNewContainer().withName("http").withImage("example/http:1").endContainer()
            .addNewContainer().withName("istio-proxy").withImage("istio/proxyv2:1.30.0")
                .endContainer()
        .endSpec()
        .withNewStatus()
            .addNewContainerStatus().withName("istio-proxy").withReady(true).endContainerStatus()
        .endStatus()
        .build();

    var observation = KubernetesIstioTelemetryCollector.observePod(pod);

    assertTrue(observation.proxyPresent());
    assertTrue(observation.proxyReady());
    assertEquals("1.30.0", observation.proxyVersion());
  }

  @Test
  void acceptsDestinationSideIstioCountersOnlyForKnownNamespaceServices() {
    String metrics = String.join("\n",
        "istio_requests_total{reporter=\"destination\",source_workload=\"mesh-client\","
            + "source_workload_namespace=\"nanpanel-mesh\",destination_workload=\"mesh-http\","
            + "destination_service_name=\"mesh-http\",destination_service_namespace=\"nanpanel-mesh\","
            + "response_code=\"200\",connection_security_policy=\"mutual_tls\"} 11",
        "istio_requests_total{reporter=\"destination\",source_workload=\"mesh-denied-client\","
            + "source_workload_namespace=\"nanpanel-mesh\",destination_workload=\"mesh-http\","
            + "destination_service_name=\"mesh-http\",destination_service_namespace=\"nanpanel-mesh\","
            + "response_code=\"403\",connection_security_policy=\"mutual_tls\"} 4",
        "istio_requests_total{reporter=\"source\",source_workload=\"mesh-client\","
            + "source_workload_namespace=\"nanpanel-mesh\",destination_workload=\"mesh-http\","
            + "destination_service_name=\"mesh-http\",destination_service_namespace=\"nanpanel-mesh\","
            + "response_code=\"200\",connection_security_policy=\"mutual_tls\"} 99",
        "istio_requests_total{reporter=\"destination\",source_workload=\"other-client\","
            + "source_workload_namespace=\"other\",destination_workload=\"mesh-http\","
            + "destination_service_name=\"mesh-http\",destination_service_namespace=\"nanpanel-mesh\","
            + "response_code=\"200\",connection_security_policy=\"none\"} 77",
        "istio_requests_total{reporter=\"destination\",source_workload=\"mesh-client\","
            + "source_workload_namespace=\"nanpanel-mesh\",destination_workload=\"external\","
            + "destination_service_name=\"outside\",destination_service_namespace=\"nanpanel-mesh\","
            + "response_code=\"200\",connection_security_policy=\"none\"} 55");
    var counts = new HashMap<KubernetesIstioTelemetryCollector.TrafficKey, Long>();

    KubernetesIstioTelemetryCollector.collectRequestCounters(metrics, "nanpanel-mesh",
        Set.of("mesh-http"), counts);
    var edges = KubernetesIstioTelemetryCollector.trafficEdges(counts);

    assertEquals(3, edges.size());
    assertEquals(new IstioTrafficEdge("other-client", "other", "mesh-http",
        "mesh-http", "200", "PLAINTEXT", 77), edges.getFirst());
    assertEquals(new IstioTrafficEdge("mesh-client", "nanpanel-mesh", "mesh-http",
        "mesh-http", "200", "MUTUAL_TLS", 11), edges.get(1));
    assertEquals(new IstioTrafficEdge("mesh-denied-client", "nanpanel-mesh", "mesh-http",
        "mesh-http", "403", "MUTUAL_TLS", 4), edges.get(2));
  }
}
