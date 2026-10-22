package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.NodeListBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.nanpanel.cluster.KubernetesClusterKubeConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(KubernetesAutoEnrollmentTest.AutoEnrollmentProfile.class)
class KubernetesAutoEnrollmentTest {
  public static class AutoEnrollmentProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "nanpanel.kubernetes.auto-enroll-local.enabled", "true",
          "quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:kubernetes_auto_enrollment;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    }
  }

  @Inject AuthStore store;
  @Inject KubernetesClusterRegistryService clusters;
  @Inject KubernetesClusterCredentialVault vault;

  private KubernetesMockServer server;
  private UUID hostId;

  @BeforeEach
  void startApiFixture() {
    server = new KubernetesMockServer(true);
    server.init();
    QuarkusMock.installMockForType(new KubernetesClusterClientFactory() {
      @Override
      public KubernetesClient openInCluster() {
        return server.createClient();
      }

      @Override
      public KubernetesClient open(KubernetesClusterKubeConfig kubeconfig) {
        return server.createClient();
      }
    }, KubernetesClusterClientFactory.class);
    hostId = UUID.randomUUID();
    Instant createdAt = Instant.now();
    store.transaction(connection -> {
      AuthStore.update(connection,
          "INSERT INTO host (id,hostname,os,arch,capabilities,generation,observed_generation,"
              + "status,created_at) VALUES (?,?,?,?,'[]',1,1,'ONLINE',?)",
          hostId, "auto-enroll-" + hostId.toString().substring(0, 8), "Linux", "x86_64",
          Timestamp.from(createdAt));
      return null;
    });
  }

  @AfterEach
  void stopApiFixture() {
    if (server != null) server.destroy();
  }

  @Test
  void enrollsNewLocalClusterWithInClusterCredentialAndIsIdempotent() {
    String hostname = hostname();
    expectSuccessfulDiscovery(hostname, readyNode(hostname));

    clusters.autoEnrollLocalCluster(hostId, hostname);

    var enrolled = enrolledCluster();
    assertNotNull(enrolled);
    assertEquals("IN_CLUSTER", enrolled.get("credential_source"));
    assertEquals("HEALTHY", enrolled.get("health_status"));
    assertEquals(hostId, enrolled.get("discovered_host_id"));
    assertEquals("cluster-uid-" + hostId.toString().substring(0, 8),
        enrolled.get("cluster_fingerprint"));
    assertEquals(1, mappedNodeCount());

    clusters.autoEnrollLocalCluster(hostId, hostname);

    assertEquals(1, enrolledClusterCount());
    assertEquals(1, mappedNodeCount());
  }

  @Test
  void defersNotReadyNodeAndRetriesEnrollmentAfterItBecomesReady() {
    String hostname = hostname();
    expectNodeLookup(hostname, node(hostname, false));

    clusters.autoEnrollLocalCluster(hostId, hostname);

    assertEquals(0, enrolledClusterCount());

    expectSuccessfulDiscovery(hostname, readyNode(hostname));
    clusters.autoEnrollLocalCluster(hostId, hostname);

    assertEquals("IN_CLUSTER", enrolledCluster().get("credential_source"));
    assertEquals(1, mappedNodeCount());
  }

  @Test
  void retriesEnrollmentAfterLocalKubernetesApiRecovers() {
    String hostname = hostname();
    server.expect().get().withPath("/api/v1/nodes/" + hostname)
        .andReturn(503, "Kubernetes API temporarily unavailable").once();

    clusters.autoEnrollLocalCluster(hostId, hostname);

    assertEquals(0, enrolledClusterCount());
    assertEquals(0, mappedNodeCount());

    expectSuccessfulDiscovery(hostname, readyNode(hostname));
    clusters.autoEnrollLocalCluster(hostId, hostname);

    assertEquals("IN_CLUSTER", enrolledCluster().get("credential_source"));
    assertEquals(1, enrolledClusterCount());
    assertEquals(1, mappedNodeCount());
  }

  @Test
  void adoptsMatchingImportedClusterWithoutReplacingItsCredentialSource() {
    String hostname = hostname();
    UUID importedClusterId = UUID.randomUUID();
    String kubeconfig = """
        apiVersion: v1
        kind: Config
        clusters:
        - name: test
          cluster:
            server: https://k3s.example.test
        contexts:
        - name: test
          context:
            cluster: test
            user: test
        current-context: test
        users:
        - name: test
          user:
            token: test-token
        """;
    byte[] encryptedKubeconfig = vault.encrypt(importedClusterId, kubeconfig);
    Instant createdAt = Instant.now();
    store.transaction(connection -> {
      AuthStore.update(connection,
          "INSERT INTO kubernetes_cluster (id,name,kind,endpoint,context_name,"
              + "encrypted_kubeconfig,health_status,imported_by,created_at,updated_at) "
              + "VALUES (?,?, 'K3S', ?, 'test', ?, 'HEALTHY', NULL, ?, ?)",
          importedClusterId, "imported-auto-enroll-" + hostId.toString().substring(0, 8),
          "https://k3s.example.test", encryptedKubeconfig,
          Timestamp.from(createdAt), Timestamp.from(createdAt));
      return null;
    });
    expectSuccessfulDiscovery(hostname, readyNode(hostname));

    clusters.autoEnrollLocalCluster(hostId, hostname);

    var adopted = enrolledCluster();
    assertNotNull(adopted);
    assertEquals("KUBECONFIG", adopted.get("credential_source"));
    assertEquals("HEALTHY", adopted.get("health_status"));
    assertEquals(hostId, adopted.get("discovered_host_id"));
    assertEquals("cluster-uid-" + hostId.toString().substring(0, 8),
        adopted.get("cluster_fingerprint"));
    assertEquals(1, mappedNodeCount());
    assertEquals(1, enrolledClusterCount());
  }

  private void expectSuccessfulDiscovery(String hostname, Node localNode) {
    expectNodeLookup(hostname, localNode);
    server.expect().get().withPath("/api/v1/namespaces/kube-system")
        .andReturn(200, new NamespaceBuilder().withNewMetadata()
            .withName("kube-system")
            .withUid("cluster-uid-" + hostId.toString().substring(0, 8))
            .endMetadata().build()).always();
    server.expect().get().withPath("/api/v1/namespaces/default/services/kubernetes")
        .andReturn(200, new ServiceBuilder().withNewMetadata().withName("kubernetes")
            .withNamespace("default").endMetadata().withNewSpec()
            .withClusterIP("10.43.0.1").endSpec().build()).always();
    server.expect().get().withPath("/api/v1/nodes?limit=1001")
        .andReturn(200, new NodeListBuilder().withItems(localNode).build()).always();
  }

  private void expectNodeLookup(String hostname, Node localNode) {
    server.expect().get().withPath("/api/v1/nodes/" + hostname)
        .andReturn(200, localNode).once();
  }

  private Map<String, Object> enrolledCluster() {
    return store.transaction(connection -> {
      var rows = AuthStore.query(connection,
          "SELECT credential_source,health_status,discovered_host_id,cluster_fingerprint "
              + "FROM kubernetes_cluster WHERE discovered_host_id=?", hostId);
      return rows.isEmpty() ? null : rows.getFirst();
    });
  }

  private int enrolledClusterCount() {
    return store.transaction(connection -> AuthStore.query(connection,
        "SELECT COUNT(*) AS count FROM kubernetes_cluster WHERE discovered_host_id=?", hostId)
        .getFirst().get("count") instanceof Number count ? count.intValue() : 0);
  }

  private int mappedNodeCount() {
    return store.transaction(connection -> AuthStore.query(connection,
        "SELECT COUNT(*) AS count FROM kubernetes_cluster_node WHERE host_id=?", hostId)
        .getFirst().get("count") instanceof Number count ? count.intValue() : 0);
  }

  private String hostname() {
    return "auto-enroll-" + hostId.toString().substring(0, 8);
  }

  private static Node readyNode(String hostname) {
    return node(hostname, true);
  }

  private static Node node(String hostname, boolean ready) {
    return new NodeBuilder().withNewMetadata().withName(hostname)
        .withUid("node-uid-" + hostname).endMetadata().withNewStatus()
        .addNewCondition().withType("Ready").withStatus(ready ? "True" : "False")
        .endCondition().withNodeInfo(new io.fabric8.kubernetes.api.model.NodeSystemInfoBuilder()
            .withKubeletVersion("v1.33.5+k3s1").build())
        .withAddresses(List.of(new io.fabric8.kubernetes.api.model.NodeAddressBuilder()
            .withType("InternalIP").withAddress("10.0.0.20").build()))
        .endStatus().build();
  }
}
