package io.nanpanel.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServiceListBuilder;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSliceBuilder;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSliceListBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class KubernetesServiceDiscoveryTest {
  private static final String NAMESPACE = "nanpanel-mesh";

  @Test
  void discoversServicePortsAndReadyPodEndpointsWithinTheRequestedNamespace() throws Exception {
    KubernetesMockServer server = new KubernetesMockServer(true);
    server.init();
    try (KubernetesClient client = server.createClient()) {
      server.expect().get()
          .withPath("/api/v1/namespaces/" + NAMESPACE + "/services?limit=513")
          .andReturn(200, new ServiceListBuilder().withItems(service("mesh-http", NAMESPACE)).build())
          .once();
      server.expect().get()
          .withPath("/apis/discovery.k8s.io/v1/namespaces/" + NAMESPACE +
              "/endpointslices?limit=1025")
          .andReturn(200, new EndpointSliceListBuilder()
              .withItems(endpointSlice(NAMESPACE)).build())
          .once();

      var snapshot = new KubernetesServiceDiscovery().discover(client, "test-cluster", NAMESPACE);

      assertEquals("kubernetes:test-cluster", snapshot.sourceId());
      assertEquals(NAMESPACE, snapshot.namespace());
      assertFalse(snapshot.observedAt().isAfter(Instant.now()));
      assertEquals(1, snapshot.services().size());
      var service = snapshot.services().getFirst();
      assertEquals("mesh-http", service.serviceName());
      assertEquals("ClusterIP", service.type());
      assertEquals("10.43.0.20", service.clusterIps().getFirst());
      assertEquals(1, service.ports().size());
      assertEquals(80, service.ports().getFirst().port());
      assertEquals("http", service.ports().getFirst().targetPort());
      assertEquals(2, service.endpoints().size());
      assertEquals("10.42.0.10", service.endpoints().getFirst().address());
      assertTrue(service.endpoints().getFirst().ready());
      assertEquals("mesh-http-pod", service.endpoints().getFirst().targetName());
      assertEquals(8080, service.endpoints().getFirst().ports().getFirst().port());

      int requestCount = server.getRequestCount();
      boolean sawScopedServiceList = false;
      boolean sawScopedEndpointSliceList = false;
      for (int index = 0; index < requestCount; index++) {
        var request = server.takeRequest(1, TimeUnit.SECONDS);
        if (request == null) {
          break;
        }
        String path = request.getPath();
        sawScopedServiceList |= "GET".equals(request.getMethod()) &&
            path.startsWith("/api/v1/namespaces/" + NAMESPACE + "/services");
        sawScopedEndpointSliceList |= "GET".equals(request.getMethod()) &&
            path.startsWith("/apis/discovery.k8s.io/v1/namespaces/" + NAMESPACE + "/endpointslices");
      }
      assertTrue(sawScopedServiceList);
      assertTrue(sawScopedEndpointSliceList);
    } finally {
      server.destroy();
    }
  }

  @Test
  void rejectsUnsafeNamespaceAndMalformedEndpointIdentity() {
    KubernetesMockServer server = new KubernetesMockServer(true);
    server.init();
    try (KubernetesClient client = server.createClient()) {
      assertThrows(IllegalArgumentException.class,
          () -> new KubernetesServiceDiscovery().discover(client, "cluster-a", "team/../prod"));
    } finally {
      server.destroy();
    }

    EndpointSlice malformed = endpointSlice(NAMESPACE).edit()
        .editFirstEndpoint()
        .withAddresses("10.999.0.10")
        .endEndpoint()
        .build();
    assertThrows(IllegalArgumentException.class,
        () -> KubernetesServiceDiscovery.buildSnapshot("cluster-a", NAMESPACE,
            List.of(service("mesh-http", NAMESPACE)), List.of(malformed), Instant.now()));
  }

  @Test
  void rejectsSnapshotsThatExceedTheBoundedInventoryLimit() {
    List<Service> services = java.util.stream.IntStream.range(0, 513)
        .mapToObj(index -> service("svc-" + index, NAMESPACE))
        .toList();
    assertThrows(IllegalArgumentException.class,
        () -> KubernetesServiceDiscovery.buildSnapshot("cluster-a", NAMESPACE,
            services, List.of(), Instant.now()));
  }

  private static Service service(String name, String namespace) {
    return new ServiceBuilder()
        .withNewMetadata().withName(name).withNamespace(namespace).endMetadata()
        .withNewSpec()
        .withType("ClusterIP")
        .withClusterIP("10.43.0.20")
        .withSelector(Map.of("app", "mesh-http"))
        .addNewPort().withName("http").withProtocol("TCP").withPort(80)
        .withTargetPort(new IntOrString("http")).endPort()
        .endSpec()
        .build();
  }

  private static EndpointSlice endpointSlice(String namespace) {
    return new EndpointSliceBuilder()
        .withNewMetadata().withName("mesh-http-slice").withNamespace(namespace)
        .addToLabels("kubernetes.io/service-name", "mesh-http").endMetadata()
        .withAddressType("IPv4")
        .addNewPort().withName("http").withProtocol("TCP").withPort(8080).endPort()
        .addNewEndpoint()
        .withAddresses("10.42.0.10", "10.42.0.11")
        .withNewConditions().withReady(true).withServing(true).endConditions()
        .withNewTargetRef().withApiVersion("v1").withKind("Pod")
        .withName("mesh-http-pod").withNamespace(namespace).endTargetRef()
        .endEndpoint()
        .build();
  }
}
