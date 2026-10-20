package io.nanpanel.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.NamespaceListBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServiceListBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressListBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

class KubernetesTopologyDiscoveryTest {
  private static final String NAMESPACE = "nanpanel-mesh";

  @Test
  void listsNamespacesAndReadsIngressServicePodAndContainerTopology() throws Exception {
    KubernetesMockServer server = new KubernetesMockServer(true);
    server.init();
    try (KubernetesClient client = server.createClient()) {
      server.expect().get().withPath("/api/v1/namespaces?limit=257")
          .andReturn(200, new NamespaceListBuilder()
              .withItems(new NamespaceBuilder().withNewMetadata().withName(NAMESPACE)
                  .endMetadata().withNewStatus().withPhase("Active").endStatus().build())
              .build()).once();
      server.expect().get().withPath("/api/v1/namespaces/" + NAMESPACE)
          .andReturn(200, new NamespaceBuilder().withNewMetadata().withName(NAMESPACE)
              .endMetadata().withNewStatus().withPhase("Active").endStatus().build()).once();
      server.expect().get().withPath("/api/v1/namespaces/" + NAMESPACE + "/pods?limit=2049")
          .andReturn(200, new PodListBuilder().withItems(new PodBuilder()
              .withNewMetadata().withName("api-0").withNamespace(NAMESPACE).endMetadata()
              .withNewSpec().addNewContainer().withName("api").withImage("example/api:1")
              .addNewPort().withName("http").withContainerPort(8080).withProtocol("TCP")
              .endPort().endContainer().endSpec()
              .withNewStatus().withPhase("Running").withPodIP("10.42.0.10")
              .addNewCondition().withType("Ready").withStatus("True").endCondition()
              .addNewContainerStatus().withName("api").withReady(true).withRestartCount(2)
              .endContainerStatus().endStatus().build()).build()).once();
      server.expect().get().withPath("/api/v1/namespaces/" + NAMESPACE + "/services?limit=513")
          .andReturn(200, new ServiceListBuilder().withItems(new ServiceBuilder()
              .withNewMetadata().withName("api").withNamespace(NAMESPACE).endMetadata()
              .withNewSpec().withType("ClusterIP").withClusterIP("10.43.0.10")
              .addNewPort().withName("http").withPort(80).withProtocol("TCP")
              .withTargetPort(new IntOrString(8080)).endPort()
              .endSpec().build()).build()).once();
      server.expect().get().withPath("/apis/networking.k8s.io/v1/namespaces/" + NAMESPACE
              + "/ingresses?limit=513")
          .andReturn(200, new IngressListBuilder().withItems(new IngressBuilder()
              .withNewMetadata().withName("public-api").withNamespace(NAMESPACE).endMetadata()
              .withNewSpec().withIngressClassName("traefik")
              .addNewRule().withHost("api.example.test").withNewHttp()
              .addNewPath().withPath("/").withPathType("Prefix").withNewBackend()
              .withNewService().withName("api").withNewPort().withNumber(80).endPort()
              .endService().endBackend().endPath().endHttp().endRule()
              .endSpec().build()).build()).once();

      var discovery = new KubernetesTopologyDiscovery();
      var namespaces = discovery.namespaces(client, "test-cluster");
      var detail = discovery.namespace(client, "test-cluster", NAMESPACE);

      assertEquals("kubernetes:test-cluster", namespaces.sourceId());
      assertEquals(NAMESPACE, namespaces.namespaces().getFirst().name());
      assertEquals("api", detail.services().getFirst().name());
      assertEquals("api", detail.ingresses().getFirst().routes().getFirst().serviceName());
      assertEquals("api-0", detail.pods().getFirst().name());
      assertTrue(detail.pods().getFirst().ready());
      assertEquals(2, detail.pods().getFirst().containers().getFirst().restartCount());
      assertEquals(8080,
          detail.pods().getFirst().containers().getFirst().ports().getFirst().containerPort());
    } finally {
      server.destroy();
    }
  }
}
