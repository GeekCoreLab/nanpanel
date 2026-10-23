package io.nanpanel.core;

import static io.restassured.RestAssured.given;
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
  @Inject KubernetesClusterCredentialVault vault;

  private KubernetesMockServer server;
  private UUID hostId;
  private UUID agentId;
  private String certificateFingerprint;

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
    agentId = UUID.randomUUID();
    certificateFingerprint = "a".repeat(64);
    Instant createdAt = Instant.now();
    store.transaction(connection -> {
      AuthStore.update(connection,
          "INSERT INTO host (id,hostname,os,arch,capabilities,generation,observed_generation,"
              + "status,created_at) VALUES (?,?,?,?,'[]',1,1,'ONLINE',?)",
          hostId, "auto-enroll-" + hostId.toString().substring(0, 8), "Linux", "x86_64",
          Timestamp.from(createdAt));
      AuthStore.update(connection,
          "INSERT INTO agent (id,host_id,cert_identity,version,last_seen,cert_fingerprint,helper_status) "
              + "VALUES (?,?,?,'test',?,?,'AVAILABLE')",
          agentId, hostId, "auto-enroll-test-" + agentId, Timestamp.from(createdAt),
          certificateFingerprint);
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

    postRuntimeDiscoveryHeartbeat(hostname);

    var enrolled = enrolledCluster();
    assertNotNull(enrolled);
    assertEquals("IN_CLUSTER", enrolled.get("credential_source"));
    assertEquals("HEALTHY", enrolled.get("health_status"));
    assertEquals(hostId, enrolled.get("discovered_host_id"));
    assertEquals("cluster-uid-" + hostId.toString().substring(0, 8),
        enrolled.get("cluster_fingerprint"));
    assertEquals(1, mappedNodeCount());
    assertEquals(true, runtimeDiscovery().get("detected"));
    assertEquals(true, runtimeDiscovery().get("kubelet_running"));
    assertEquals(true, runtimeDiscovery().get("api_server_local"));
    assertEquals("K3S", runtimeDiscovery().get("runtime_kind"));

    postRuntimeDiscoveryHeartbeat(hostname);

    assertEquals(1, enrolledClusterCount());
    assertEquals(1, mappedNodeCount());
  }

  @Test
  void rejectsRuntimeDiscoveryFromAnUnregisteredCertificateBeforePersistingIt() {
    String hostname = hostname();
    seedClusterNode("K3S", hostname, Instant.now(), true, null);
    assertEquals(403, postRuntimeDiscoveryHeartbeat(hostname, "b".repeat(64), false));
    assertEquals(0, enrolledClusterCount());
    assertEquals(0, mappedNodeCount());
    assertEquals(0, runtimeDiscoveryCount());
  }

  @Test
  void defersLocalEnrollmentWhenAgentAddressDoesNotMatchCoreNode() {
    String hostname = hostname();
    expectSuccessfulDiscovery(hostname, readyNode(hostname));

    postRuntimeDiscoveryHeartbeat(hostname, certificateFingerprint, true, "10.0.0.21");

    assertEquals(0, enrolledClusterCount());
    assertEquals(0, mappedNodeCount());
  }

  @Test
  void defersNotReadyNodeAndRetriesEnrollmentAfterItBecomesReady() {
    String hostname = hostname();
    expectNodeLookup(hostname, node(hostname, false));

    postRuntimeDiscoveryHeartbeat(hostname);

    assertEquals(0, enrolledClusterCount());

    expectSuccessfulDiscovery(hostname, readyNode(hostname));
    postRuntimeDiscoveryHeartbeat(hostname);

    assertEquals("IN_CLUSTER", enrolledCluster().get("credential_source"));
    assertEquals(1, mappedNodeCount());
  }

  @Test
  void retriesEnrollmentAfterLocalKubernetesApiRecovers() {
    String hostname = hostname();
    server.expect().get().withPath("/api/v1/nodes/" + hostname)
        .andReturn(503, "Kubernetes API temporarily unavailable").once();

    postRuntimeDiscoveryHeartbeat(hostname);

    assertEquals(0, enrolledClusterCount());
    assertEquals(0, mappedNodeCount());

    expectSuccessfulDiscovery(hostname, readyNode(hostname));
    postRuntimeDiscoveryHeartbeat(hostname);

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

    postRuntimeDiscoveryHeartbeat(hostname);

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

  @Test
  void mapsRemoteAgentOnlyToOneFreshReadyNodeInAnAlreadyImportedK3sCluster() {
    String hostname = hostname();
    UUID clusterId = seedClusterNode("K3S", hostname, Instant.now(), true, null);

    postRemoteRuntimeDiscoveryHeartbeat(hostname);

    assertEquals(1, mappedNodeCount());
    assertEquals(clusterId, mappedNode().get("cluster_id"));
    assertEquals("node-uid-" + hostname, mappedNode().get("node_uid"));
    assertEquals(false, runtimeDiscovery().get("api_server_local"));

    postRemoteRuntimeDiscoveryHeartbeat(hostname);
    assertEquals(1, mappedNodeCount());
  }

  @Test
  void doesNotMapRemoteAgentWhenReadyNodeNameIsAmbiguous() {
    String hostname = hostname();
    seedClusterNode("K3S", hostname, Instant.now(), true, null);
    seedClusterNode("K3S", hostname, Instant.now(), true, null);
    postRemoteRuntimeDiscoveryHeartbeat(hostname);
    assertEquals(0, mappedNodeCount());
  }

  @Test
  void doesNotMapRemoteAgentToSameNameNodeWhenInternalIpDiffers() {
    String hostname = hostname();
    seedClusterNode("K3S", hostname, Instant.now(), true, null);

    postRuntimeDiscoveryHeartbeat(hostname, certificateFingerprint, false, "10.0.0.21");

    assertEquals(0, mappedNodeCount());
  }

  @Test
  void doesNotMapRemoteAgentFromStaleClusterObservation() {
    String hostname = hostname();
    seedClusterNode("K3S", hostname,
        Instant.now().minusSeconds(180), true, null);
    postRemoteRuntimeDiscoveryHeartbeat(hostname);
    assertEquals(0, mappedNodeCount());
  }

  @Test
  void doesNotMapRemoteAgentToANotReadyNode() {
    String hostname = hostname();
    seedClusterNode("K3S", hostname, Instant.now(), false, null);
    postRemoteRuntimeDiscoveryHeartbeat(hostname);
    assertEquals(0, mappedNodeCount());
  }

  @Test
  void doesNotOverrideANodeAlreadyMappedToAnotherHost() {
    String hostname = hostname();
    UUID otherHostId = UUID.randomUUID();
    Instant now = Instant.now();
    store.transaction(connection -> {
      AuthStore.update(connection,
          "INSERT INTO host (id,hostname,os,arch,capabilities,generation,observed_generation,"
              + "status,created_at) VALUES (?,?,?,?,'[]',1,1,'ONLINE',?)",
          otherHostId, "already-mapped", "Linux", "x86_64", Timestamp.from(now));
      return null;
    });
    seedClusterNode("K3S", hostname, now, true, otherHostId);
    postRemoteRuntimeDiscoveryHeartbeat(hostname);
    assertEquals(0, mappedNodeCount());
  }

  @Test
  void doesNotRemapAHostAlreadyMappedToAnotherNode() {
    String hostname = hostname();
    Instant now = Instant.now();
    seedClusterNode("K3S", "already-mapped-" + hostname, now, true, hostId);
    seedClusterNode("K3S", hostname, now, true, null);
    postRemoteRuntimeDiscoveryHeartbeat(hostname);
    assertEquals(1, mappedNodeCount());
    assertEquals("node-uid-already-mapped-" + hostname, mappedNode().get("node_uid"));
  }

  @Test
  void doesNotMapKubeEdgeNodeFromK3sRuntimeReport() {
    String hostname = hostname();
    seedClusterNode("KUBEEDGE", hostname, Instant.now(), true, null);
    postRemoteRuntimeDiscoveryHeartbeat(hostname);
    assertEquals(0, mappedNodeCount());
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

  private void postRuntimeDiscoveryHeartbeat(String hostname) {
    assertEquals(204, postRuntimeDiscoveryHeartbeat(hostname, certificateFingerprint));
  }

  private int postRuntimeDiscoveryHeartbeat(String hostname, String fingerprint) {
    return postRuntimeDiscoveryHeartbeat(hostname, fingerprint, true);
  }

  private int postRemoteRuntimeDiscoveryHeartbeat(String hostname) {
    return postRuntimeDiscoveryHeartbeat(hostname, certificateFingerprint, false);
  }

  private int postRuntimeDiscoveryHeartbeat(String hostname, String fingerprint,
      boolean apiServerLocal) {
    return postRuntimeDiscoveryHeartbeat(hostname, fingerprint, apiServerLocal, "10.0.0.20");
  }

  private int postRuntimeDiscoveryHeartbeat(String hostname, String fingerprint,
      boolean apiServerLocal, String hostAddress) {
    long observedAt = Instant.now().toEpochMilli();
    return given().header("X-NanPanel-Gateway-Token", "test-gateway-token")
        .contentType("application/json")
        .body(Map.ofEntries(
            Map.entry("agentId", agentId.toString()),
            Map.entry("version", "test"),
            Map.entry("certificateFingerprint", fingerprint),
            Map.entry("hostname", hostname),
            Map.entry("os", "Linux"),
            Map.entry("arch", "x86_64"),
            Map.entry("capabilities", List.of("host.inventory", "agent.heartbeat",
                "agent.self_update", "kubernetes.runtime.detect", "network.inventory")),
            Map.entry("helperStatus", "AVAILABLE"),
            Map.entry("networkObservedAtEpochMs", observedAt),
            Map.entry("networkInterfaces", List.of(Map.of(
                "name", "eth0", "macAddress", "02:00:00:00:00:01", "mtu", 1500,
                "state", "UP", "addresses", List.of(Map.of("family", "inet",
                    "address", hostAddress, "prefixLength", 24, "scope", "global"))))),
            Map.entry("networkRoutes", List.of()),
            Map.entry("kubernetesRuntime", Map.of(
                "detected", true,
                "kubeletRunning", true,
                "apiServerLocal", apiServerLocal,
                "runtimeKind", "K3S",
                "observedAtEpochMs", observedAt))))
        .post("/internal/agent-heartbeats")
        .statusCode();
  }

  private UUID seedClusterNode(String kind, String nodeName, Instant observedAt,
      boolean ready, UUID mappedHostId) {
    UUID clusterId = UUID.randomUUID();
    store.transaction(connection -> {
      AuthStore.update(connection,
          "INSERT INTO kubernetes_cluster (id,name,kind,endpoint,context_name,"
              + "encrypted_kubeconfig,health_status,observed_at,updated_at) "
              + "VALUES (?,?,?,?,'test',?,'HEALTHY',?,?)",
          clusterId, "remote-auto-map-" + clusterId.toString().substring(0, 8), kind,
          "https://cluster-" + clusterId.toString().substring(0, 8) + ".test:6443",
          new byte[] {1}, Timestamp.from(observedAt), Timestamp.from(observedAt));
      boolean edge = "KUBEEDGE".equals(kind);
      AuthStore.update(connection,
          "INSERT INTO kubernetes_cluster_node (cluster_id,node_uid,node_name,roles,node_present,"
              + "kubeedge_edge,ready,internal_ip,container_owner,host_id,observed_at) "
              + "VALUES (?,?,?,'worker',TRUE,?,?,?,?,?,?)",
          clusterId, "node-uid-" + nodeName, nodeName, edge, ready,
          "10.0.0.20", edge ? "KUBEEDGE" : "KUBERNETES", mappedHostId,
          Timestamp.from(observedAt));
      return null;
    });
    return clusterId;
  }

  private Map<String, Object> mappedNode() {
    return store.transaction(connection -> AuthStore.query(connection,
        "SELECT cluster_id,node_uid FROM kubernetes_cluster_node WHERE host_id=?", hostId)
        .stream().findFirst().orElse(null));
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

  private int runtimeDiscoveryCount() {
    return store.transaction(connection -> AuthStore.query(connection,
        "SELECT host_id FROM host_kubernetes_runtime_discovery WHERE host_id=?", hostId).size());
  }

  private int mappedNodeCount() {
    return store.transaction(connection -> AuthStore.query(connection,
        "SELECT COUNT(*) AS count FROM kubernetes_cluster_node WHERE host_id=?", hostId)
        .getFirst().get("count") instanceof Number count ? count.intValue() : 0);
  }

  private Map<String, Object> runtimeDiscovery() {
    return store.transaction(connection -> AuthStore.query(connection,
        "SELECT detected,kubelet_running,api_server_local,runtime_kind "
            + "FROM host_kubernetes_runtime_discovery WHERE host_id=?", hostId).getFirst());
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
