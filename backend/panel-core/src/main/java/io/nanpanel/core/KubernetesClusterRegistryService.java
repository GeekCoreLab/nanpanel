package io.nanpanel.core;

import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.nanpanel.cluster.KubernetesClusterKubeConfig;
import io.nanpanel.cluster.KubernetesTopologyDiscovery;
import io.nanpanel.plugin.api.ClusterProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Timestamp;
import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.quarkus.scheduler.Scheduled;
import org.jboss.logging.Logger;

/** Registry for independently imported Kubernetes clusters and explicit Host mappings. */
@ApplicationScoped
public class KubernetesClusterRegistryService {
  private static final int MAX_NODES = 1_000;
  private static final long OBSERVATION_STALE_AFTER_SECONDS = 120;
  private static final Logger LOG = Logger.getLogger(KubernetesClusterRegistryService.class);
  private static final Pattern CLUSTER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_. -]{0,79}");
  private static final Pattern NODE_UID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");
  private static final String CLUSTER_COLUMNS = "id,name,kind,endpoint,context_name,health_status,"
      + "cloudcore_status,cloudcore_observed_at,observed_at,last_error_code,imported_by,created_at,"
      + "updated_at,credential_source,cluster_fingerprint,discovered_host_id";

  @Inject AuthStore store;
  @Inject KubernetesClusterCredentialVault vault;
  @Inject KubernetesClusterClientFactory clients;
  @Inject ObjectMapper mapper;
  private final KubernetesTopologyDiscovery topologyDiscovery = new KubernetesTopologyDiscovery();

  @ConfigProperty(name = "nanpanel.kubernetes.credential-key", defaultValue = "")
  String credentialKey;

  @ConfigProperty(name = "nanpanel.kubernetes.auto-enroll-local.enabled", defaultValue = "false")
  boolean autoEnrollLocalEnabled;

  public record ImportRequest(String name, String kind, String contextName, String kubeconfig) {}
  public record ClusterSummary(UUID id, String name, String kind, String endpoint,
      String contextName, String healthStatus, String cloudCoreStatus, Instant cloudCoreObservedAt,
      Instant observedAt, boolean observationsStale, String lastErrorCode, int nodeCount,
      Instant createdAt, String credentialSource, UUID discoveredHostId) {}
  public record ClusterList(List<ClusterSummary> items) {}
  public record ClusterNode(UUID clusterId, String nodeUid, String nodeName, List<String> roles,
      boolean nodePresent, boolean kubeedgeEdge, boolean ready, String kubeletVersion, String internalIp,
      String edgeCoreRegistrationStatus, String edgeCoreProcessStatus,
      Instant edgeCoreProcessObservedAt, String containerOwner, UUID hostId, Instant observedAt) {}
  public record ClusterNodeList(UUID clusterId, Instant observedAt, List<ClusterNode> items) {}

  record NodeObservation(String uid, String name, String roles, boolean kubeedgeEdge,
      boolean ready, String kubeletVersion, String internalIp, String containerOwner) {}
  private record CloudCoreObservation(String status, Instant observedAt) {}

  public ClusterList list() {
    return store.transaction(connection -> {
      var rows = query(connection, "SELECT c." + CLUSTER_COLUMNS + ","
          + "(SELECT COUNT(*) FROM kubernetes_cluster_node n WHERE n.cluster_id=c.id "
          + "AND n.node_present=TRUE) AS node_count "
          + "FROM kubernetes_cluster c ORDER BY c.name,c.id");
      return new ClusterList(rows.stream().map(KubernetesClusterRegistryService::summary).toList());
    });
  }

  public ClusterSummary get(UUID clusterId) {
    return store.transaction(connection -> {
      var rows = query(connection, "SELECT c." + CLUSTER_COLUMNS + ","
          + "(SELECT COUNT(*) FROM kubernetes_cluster_node n WHERE n.cluster_id=c.id "
          + "AND n.node_present=TRUE) AS node_count "
          + "FROM kubernetes_cluster c WHERE c.id=?", clusterId);
      if (rows.isEmpty()) throw new ClusterNotFoundException();
      return summary(rows.getFirst());
    });
  }

  /** Builds the restricted view exposed to cluster lifecycle plugins from cached Core inventory. */
  public ClusterProvider.ClusterSnapshot providerSnapshot(UUID clusterId) {
    return store.transaction(connection -> {
      var rows = query(connection, "SELECT c." + CLUSTER_COLUMNS + ","
          + "(SELECT COUNT(*) FROM kubernetes_cluster_node n WHERE n.cluster_id=c.id "
          + "AND n.node_present=TRUE) AS node_count FROM kubernetes_cluster c WHERE c.id=?",
          clusterId);
      if (rows.isEmpty()) throw new ClusterNotFoundException();
      Map<String, Object> row = rows.getFirst();
      ClusterSummary cluster = summary(row);
      Instant updatedAt = instant(row.get("updated_at"));
      ClusterNodeList inventory = readNodes(connection, clusterId, cluster.observedAt());
      List<ClusterProvider.NodeSnapshot> nodes = inventory.items().stream()
          .map(node -> new ClusterProvider.NodeSnapshot(node.nodeUid(), node.nodeName(),
              node.nodePresent(), node.kubeedgeEdge(), node.ready(), node.kubeletVersion(), node.hostId()))
          .toList();
      return new ClusterProvider.ClusterSnapshot(cluster.id(),
          updatedAt == null ? 0 : Math.max(0, updatedAt.toEpochMilli()), cluster.kind(),
          cluster.healthStatus(), cluster.cloudCoreStatus(), cluster.observedAt(), nodes);
    });
  }

  public KubernetesClient openClientForOperations(UUID clusterId) {
    return openClusterClient(clusterId, requireCluster(clusterId));
  }

  public KubernetesTopologyDiscovery.NamespaceSnapshot topologyNamespaces(UUID clusterId) {
    Map<String, Object> cluster = requireCluster(clusterId);
    try (KubernetesClient client = openClusterClient(clusterId, cluster)) {
      return topologyDiscovery.namespaces(client, clusterId.toString());
    } catch (KubernetesTopologyDiscovery.NamespaceNotFoundException missing) {
      throw missing;
    } catch (RuntimeException failure) {
      throw reportApiFailure(clusterId, failure);
    }
  }

  public KubernetesTopologyDiscovery.NamespaceDetailSnapshot topologyNamespace(
      UUID clusterId, String namespace) {
    Map<String, Object> cluster = requireCluster(clusterId);
    try (KubernetesClient client = openClusterClient(clusterId, cluster)) {
      return topologyDiscovery.namespace(client, clusterId.toString(), namespace);
    } catch (KubernetesTopologyDiscovery.NamespaceNotFoundException missing) {
      throw missing;
    } catch (RuntimeException failure) {
      throw reportApiFailure(clusterId, failure);
    }
  }

  public ClusterSummary importCluster(String actor, String remoteAddress, ImportRequest input) {
    if (input == null || input.name() == null || !CLUSTER_NAME.matcher(input.name().trim()).matches()
        || input.kind() == null || !Set.of("K3S", "KUBEEDGE", "KUBERNETES").contains(input.kind())
        || input.contextName() == null || input.kubeconfig() == null) {
      throw new IllegalArgumentException("Cluster name, type, kubeconfig, and context are required");
    }
    if (!configuredEncryptionKey()) {
      throw new CredentialKeyUnavailableException();
    }
    KubernetesClusterKubeConfig selected = KubernetesClusterKubeConfig.select(
        input.kubeconfig(), input.contextName());
    UUID clusterId = UUID.randomUUID();
    List<NodeObservation> nodes;
    CloudCoreObservation cloudCore;
    String fingerprint;
    try (KubernetesClient client = clients.open(selected)) {
      var namespaceList = client.namespaces().list(new ListOptionsBuilder().withLimit(1L).build());
      if (namespaceList == null || namespaceList.getItems() == null) {
        throw new IllegalStateException("Kubernetes Namespace response is incomplete");
      }
      fingerprint = clusterFingerprint(client);
      nodes = discoverNodes(client, input.kind());
      cloudCore = observeCloudCore(client, input.kind());
    } catch (RuntimeException failure) {
      LOG.warnf("Kubernetes API verification failed during cluster import (%s)",
          failure.getClass().getSimpleName());
      throw clusterFailure(failure);
    }
    byte[] encrypted = vault.encrypt(clusterId, selected.sanitizedYaml());
    Instant observedAt = Instant.now();
    String name = input.name().trim();
    try {
      return store.transaction(connection -> {
        if (!query(connection, "SELECT id FROM kubernetes_cluster WHERE cluster_fingerprint=?",
            fingerprint).isEmpty()) {
          throw new ClusterAlreadyExistsException();
        }
        if (!query(connection, "SELECT id FROM kubernetes_cluster WHERE name=?", name).isEmpty()) {
          throw new ClusterAlreadyExistsException();
        }
        update(connection, "INSERT INTO kubernetes_cluster (id,name,kind,endpoint,context_name,"
            + "encrypted_kubeconfig,health_status,cloudcore_status,cloudcore_observed_at,observed_at,"
            + "last_error_code,imported_by,created_at,updated_at,credential_source,cluster_fingerprint) "
            + "VALUES (?,?,?,?,?,?,'HEALTHY',?,?,?,NULL,?,?,?,'KUBECONFIG',?)", clusterId, name, input.kind(),
            selected.endpoint().toString(), selected.contextName(), encrypted, cloudCore.status(),
            cloudCore.observedAt() == null ? null : Timestamp.from(cloudCore.observedAt()),
            Timestamp.from(observedAt), actor, Timestamp.from(observedAt), Timestamp.from(observedAt),
            fingerprint);
        replaceNodes(connection, clusterId, nodes, observedAt);
        audit(connection, actor, "KUBERNETES_CLUSTER_IMPORTED", "kubernetes_cluster",
            clusterId.toString(), "SUCCEEDED", remoteAddress,
            "导入 Kubernetes 集群 " + name + "（类型 " + input.kind() + "，节点 " + nodes.size() + " 个）");
        return summary(query(connection, "SELECT c." + CLUSTER_COLUMNS + ","
            + "(SELECT COUNT(*) FROM kubernetes_cluster_node n WHERE n.cluster_id=c.id "
            + "AND n.node_present=TRUE) AS node_count "
            + "FROM kubernetes_cluster c WHERE c.id=?", clusterId).getFirst());
      });
    } finally {
      java.util.Arrays.fill(encrypted, (byte)0);
    }
  }

  public ClusterNodeList refreshNodes(UUID clusterId) {
    Map<String, Object> cluster = requireCluster(clusterId);
    List<NodeObservation> nodes;
    CloudCoreObservation cloudCore;
    String fingerprint;
    try (KubernetesClient client = openClusterClient(clusterId, cluster)) {
      nodes = discoverNodes(client, String.valueOf(cluster.get("kind")));
      cloudCore = observeCloudCore(client, String.valueOf(cluster.get("kind")));
      fingerprint = clusterFingerprint(client);
    } catch (RuntimeException failure) {
      throw reportApiFailure(clusterId, failure);
    }
    Instant observedAt = Instant.now();
    store.transaction(connection -> {
      claimFingerprint(connection, clusterId, fingerprint);
      replaceNodes(connection, clusterId, nodes, observedAt);
      update(connection, "UPDATE kubernetes_cluster SET health_status='HEALTHY',cloudcore_status=?,"
          + "cloudcore_observed_at=?,observed_at=?,last_error_code=NULL,updated_at=? WHERE id=?",
          cloudCore.status(), cloudCore.observedAt() == null ? null : Timestamp.from(cloudCore.observedAt()),
          Timestamp.from(observedAt), Timestamp.from(observedAt), clusterId);
      return null;
    });
    return readNodes(clusterId, observedAt);
  }

  /** Automatically enrolls only a K3s API server node that is part of this Core's in-cluster API. */
  public void autoEnrollLocalCluster(UUID hostId, String hostname) {
    if (!autoEnrollLocalEnabled || hostId == null || hostname == null
        || !hostname.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,252}")) return;
    boolean alreadyDiscovered = store.transaction(connection -> !query(connection,
        "SELECT id FROM kubernetes_cluster WHERE discovered_host_id=?", hostId).isEmpty());
    if (alreadyDiscovered) return;
    try (KubernetesClient client = clients.openInCluster()) {
      Node localNode = client.nodes().withName(hostname).get();
      if (localNode == null || localNode.getMetadata() == null
          || localNode.getMetadata().getUid() == null || !nodeReady(localNode)) return;
      String internalIp = nodeInternalIp(localNode);
      if (internalIp == null || !hostHasFreshAddress(hostId, internalIp)) return;
      String fingerprint = clusterFingerprint(client);
      String serviceIp = kubernetesServiceIp(client);
      UUID existingClusterId = findLocalCluster(fingerprint, serviceIp);
      List<NodeObservation> nodes = discoverNodes(client, "K3S");
      Instant observedAt = Instant.now();
      store.transaction(connection -> {
        UUID existingId = existingClusterId;
        if (existingId == null) {
          var identified = query(connection,
              "SELECT id FROM kubernetes_cluster WHERE cluster_fingerprint=?", fingerprint);
          if (!identified.isEmpty()) existingId = (UUID)identified.getFirst().get("id");
        }
        boolean created = existingId == null;
        UUID clusterId = created ? UUID.randomUUID() : existingId;
        if (created) {
          String name = "auto-k3s-" + hostId.toString().substring(0, 8);
          update(connection, "INSERT INTO kubernetes_cluster (id,name,kind,endpoint,context_name,"
              + "encrypted_kubeconfig,health_status,cloudcore_status,cloudcore_observed_at,observed_at,"
              + "last_error_code,imported_by,created_at,updated_at,credential_source,cluster_fingerprint,"
              + "discovered_host_id) VALUES (?,?,'K3S','https://kubernetes.default.svc','in-cluster',"
              + "NULL,'HEALTHY','NOT_OBSERVED',NULL,?,NULL,NULL,?,?,'IN_CLUSTER',?,?)",
              clusterId, name, Timestamp.from(observedAt), Timestamp.from(observedAt),
              Timestamp.from(observedAt), fingerprint, hostId);
          audit(connection, null, "KUBERNETES_CLUSTER_AUTO_ENROLLED", "kubernetes_cluster",
              clusterId.toString(), "SUCCEEDED", null,
              "Agent 在主机 " + hostname + " 检测到本地 K3s API，并通过 Core 的集群内只读身份自动纳管");
        } else {
          claimFingerprint(connection, clusterId, fingerprint);
          update(connection, "UPDATE kubernetes_cluster SET health_status='HEALTHY',"
              + "observed_at=?,last_error_code=NULL,updated_at=?,discovered_host_id=? "
              + "WHERE id=? AND discovered_host_id IS NULL",
              Timestamp.from(observedAt), Timestamp.from(observedAt), hostId, clusterId);
        }
        replaceNodes(connection, clusterId, nodes, observedAt);
        mapDiscoveredNode(connection, clusterId, localNode.getMetadata().getUid(), hostId);
        return null;
      });
    } catch (RuntimeException failure) {
      LOG.warnf("Automatic local K3s enrollment deferred for host %s (%s)", hostname,
          failure.getClass().getSimpleName());
    }
  }

  private String nodeInternalIp(Node node) {
    if (node.getStatus() == null || node.getStatus().getAddresses() == null) return null;
    return node.getStatus().getAddresses().stream()
        .filter(address -> address != null && "InternalIP".equals(address.getType()))
        .map(io.fabric8.kubernetes.api.model.NodeAddress::getAddress)
        .filter(address -> address != null && !address.isBlank() && address.length() <= 64)
        .findFirst().orElse(null);
  }

  private boolean hostHasFreshAddress(UUID hostId, String internalIp) {
    return store.transaction(connection -> {
      var rows = query(connection,
          "SELECT CAST(interfaces AS VARCHAR) AS interfaces_json,observed_at "
              + "FROM host_network_inventory WHERE host_id=?", hostId);
      if (rows.isEmpty()) return false;
      Object observedAtValue = rows.getFirst().get("observed_at");
      Instant observedAt = observedAtValue instanceof Timestamp timestamp ? timestamp.toInstant()
          : observedAtValue instanceof OffsetDateTime timestamp ? timestamp.toInstant()
          : observedAtValue instanceof Instant timestamp ? timestamp : null;
      if (observedAt == null || observedAt
          .isBefore(Instant.now().minusSeconds(OBSERVATION_STALE_AFTER_SECONDS))) return false;
      try {
        JsonNode interfaces = mapper.readTree(
            String.valueOf(rows.getFirst().get("interfaces_json")));
        if (!interfaces.isArray()) return false;
        for (JsonNode networkInterface : interfaces) {
          JsonNode addresses = networkInterface.path("addresses");
          if (!addresses.isArray()) continue;
          for (JsonNode address : addresses) {
            if (internalIp.equals(address.path("address").asText())) return true;
          }
        }
        return false;
      } catch (java.io.IOException | IllegalArgumentException failure) {
        LOG.debugf("Unable to verify Agent host address for automatic K3s enrollment (%s)",
            failure.getClass().getSimpleName());
        return false;
      }
    });
  }

  /** Maps a remote Agent only to one fresh, Ready K3s Node in a cluster already owned by Core. */
  public void autoMapKnownRemoteK3sNode(UUID hostId, String hostname) {
    if (!autoEnrollLocalEnabled || hostId == null || hostname == null
        || !hostname.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,252}")) return;
    try {
      store.transaction(connection -> {
        Timestamp cutoff = Timestamp.from(Instant.now()
            .minusSeconds(OBSERVATION_STALE_AFTER_SECONDS));
        var candidates = query(connection,
            "SELECT c.id AS cluster_id,n.node_uid,n.host_id FROM kubernetes_cluster c "
                + "JOIN kubernetes_cluster_node n ON n.cluster_id=c.id "
                + "WHERE c.kind='K3S' AND c.credential_source IN ('KUBECONFIG','IN_CLUSTER') "
                + "AND c.health_status='HEALTHY' AND c.observed_at>=? "
                + "AND n.node_name=? AND n.node_present=TRUE AND n.ready=TRUE "
                + "AND n.container_owner='KUBERNETES' AND n.observed_at>=?",
            cutoff, hostname, cutoff);
        if (candidates.size() != 1) return null;
        Map<String, Object> candidate = candidates.getFirst();
        UUID clusterId = (UUID)candidate.get("cluster_id");
        String nodeUid = String.valueOf(candidate.get("node_uid"));
        var hostMappings = query(connection,
            "SELECT cluster_id,node_uid FROM kubernetes_cluster_node WHERE host_id=?", hostId);
        if (!hostMappings.isEmpty()) return null;
        if (candidate.get("host_id") != null) return null;
        int changed = update(connection,
            "UPDATE kubernetes_cluster_node SET host_id=? WHERE cluster_id=? AND node_uid=? "
                + "AND node_name=? AND node_present=TRUE AND ready=TRUE "
                + "AND container_owner='KUBERNETES' AND host_id IS NULL "
                + "AND observed_at>=?",
            hostId, clusterId, nodeUid, hostname, cutoff);
        if (changed == 1) {
          audit(connection, null, "KUBERNETES_NODE_AUTO_MAPPED", "kubernetes_cluster_node",
              clusterId + ":" + nodeUid, "SUCCEEDED", null,
              "通过已登记 Agent 身份将远端主机 " + hostId + " 关联到唯一的新鲜 Ready K3s Node");
        }
        return null;
      });
    } catch (RuntimeException failure) {
      LOG.warnf("Automatic remote K3s Node mapping deferred for host %s (%s)", hostId,
          failure.getClass().getSimpleName());
    }
  }

  @Scheduled(every = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void refreshRegisteredClusters() {
    List<UUID> clusterIds = store.transaction(connection -> query(connection,
        "SELECT id FROM kubernetes_cluster ORDER BY name,id LIMIT 50").stream()
        .map(row -> (UUID)row.get("id")).toList());
    for (UUID clusterId : clusterIds) {
      try {
        refreshNodes(clusterId);
      } catch (RuntimeException failure) {
        LOG.warnf("Kubernetes cluster refresh failed for %s (%s)", clusterId,
            failure.getClass().getSimpleName());
      }
    }
  }

  public ClusterNode mapNode(String actor, String remoteAddress, UUID clusterId, String nodeUid,
                             UUID hostId) {
    validateNodeUid(nodeUid);
    return store.transaction(connection -> {
      var rows = query(connection, "SELECT n.cluster_id,n.node_uid,n.node_name,n.roles,"
          + "n.node_present,n.kubeedge_edge,n.ready,n.kubelet_version,n.internal_ip,n.container_owner,n.host_id,"
          + "n.observed_at FROM kubernetes_cluster_node n JOIN kubernetes_cluster c "
          + "ON c.id=n.cluster_id WHERE c.id=? AND n.node_uid=? FOR UPDATE", clusterId, nodeUid);
      if (rows.isEmpty()) throw new ClusterNodeNotFoundException();
      if (!Boolean.TRUE.equals(rows.getFirst().get("node_present"))) {
        throw new ClusterNodeNotFoundException();
      }
      if (query(connection, "SELECT id FROM host WHERE id=? FOR UPDATE", hostId).isEmpty()) {
        throw new HostNotFoundException();
      }
      var conflicting = query(connection, "SELECT cluster_id,node_uid FROM kubernetes_cluster_node "
          + "WHERE host_id=? AND NOT (cluster_id=? AND node_uid=?)", hostId, clusterId, nodeUid);
      if (!conflicting.isEmpty()) throw new HostAlreadyMappedException();
      update(connection, "UPDATE kubernetes_cluster_node SET host_id=? WHERE cluster_id=? AND node_uid=?",
          hostId, clusterId, nodeUid);
      audit(connection, actor, "KUBERNETES_CLUSTER_NODE_MAPPED", "kubernetes_cluster_node",
          clusterId + ":" + nodeUid, "SUCCEEDED", remoteAddress,
          "将集群节点 " + String.valueOf(rows.getFirst().get("node_name")) + " 映射到主机资产");
      var mapped = new java.util.LinkedHashMap<>(rows.getFirst());
      mapped.put("host_id", hostId);
      var hostObservation = query(connection,
          "SELECT (SELECT s.active_state FROM system_service_observation s "
              + "WHERE s.host_id=? AND LOWER(s.unit_name) IN ('edgecore.service','edgecore') "
              + "ORDER BY s.observed_at DESC LIMIT 1) AS edgecore_active_state,"
              + "(SELECT s.observed_at FROM system_service_observation s WHERE s.host_id=? "
              + "AND LOWER(s.unit_name) IN ('edgecore.service','edgecore') "
              + "ORDER BY s.observed_at DESC LIMIT 1) AS edgecore_observed_at,"
              + "(SELECT MAX(a.last_seen) FROM agent a WHERE a.host_id=?) AS host_agent_last_seen",
          hostId, hostId, hostId).getFirst();
      mapped.putAll(hostObservation);
      var settings = query(connection,
          "SELECT host_offline_after_seconds FROM platform_settings WHERE id=1");
      int staleAfterSeconds = settings.isEmpty() ? 90
          : ((Number)settings.getFirst().get("host_offline_after_seconds")).intValue();
      return node(mapped, Instant.now(), staleAfterSeconds);
    });
  }

  public void unmapNode(String actor, String remoteAddress, UUID clusterId, String nodeUid) {
    validateNodeUid(nodeUid);
    store.transaction(connection -> {
      var rows = query(connection, "SELECT node_name,node_present,host_id,container_owner FROM kubernetes_cluster_node "
          + "WHERE cluster_id=? AND node_uid=? FOR UPDATE", clusterId, nodeUid);
      if (rows.isEmpty()) throw new ClusterNodeNotFoundException();
      boolean nodePresent = Boolean.TRUE.equals(rows.getFirst().get("node_present"));
      if (rows.getFirst().get("host_id") == null) {
        if (!nodePresent) {
          update(connection, "DELETE FROM kubernetes_cluster_node WHERE cluster_id=? AND node_uid=?",
              clusterId, nodeUid);
        }
        return null;
      }
      if (nodePresent && Set.of("KUBERNETES", "KUBEEDGE").contains(rows.getFirst().get("container_owner"))) {
        throw new HostOwnershipManagedByKubernetesException();
      }
      if (nodePresent) {
        update(connection, "UPDATE kubernetes_cluster_node SET host_id=NULL "
            + "WHERE cluster_id=? AND node_uid=?", clusterId, nodeUid);
      } else {
        update(connection, "DELETE FROM kubernetes_cluster_node WHERE cluster_id=? AND node_uid=?",
            clusterId, nodeUid);
      }
      audit(connection, actor, "KUBERNETES_CLUSTER_NODE_UNMAPPED", "kubernetes_cluster_node",
          clusterId + ":" + nodeUid, "SUCCEEDED", remoteAddress,
          "移除集群节点 " + String.valueOf(rows.getFirst().get("node_name")) + " 的主机映射");
      return null;
    });
  }

  private ClusterNodeList readNodes(UUID clusterId, Instant observedAt) {
    return store.transaction(connection -> readNodes(connection, clusterId, observedAt));
  }

  private ClusterNodeList readNodes(Connection connection, UUID clusterId, Instant observedAt)
      throws java.sql.SQLException {
    var settings = query(connection,
        "SELECT host_offline_after_seconds FROM platform_settings WHERE id=1");
    int staleAfterSeconds = settings.isEmpty() ? 90
        : ((Number)settings.getFirst().get("host_offline_after_seconds")).intValue();
    var rows = query(connection, "SELECT n.cluster_id,n.node_uid,n.node_name,n.roles,n.node_present,"
        + "n.kubeedge_edge,n.ready,n.kubelet_version,n.internal_ip,n.container_owner,n.host_id,"
        + "n.observed_at,(SELECT s.active_state FROM system_service_observation s "
        + "WHERE s.host_id=n.host_id AND LOWER(s.unit_name) IN ('edgecore.service','edgecore') "
        + "ORDER BY s.observed_at DESC LIMIT 1) AS edgecore_active_state,"
        + "(SELECT s.observed_at FROM system_service_observation s WHERE s.host_id=n.host_id "
        + "AND LOWER(s.unit_name) IN ('edgecore.service','edgecore') "
        + "ORDER BY s.observed_at DESC LIMIT 1) AS edgecore_observed_at,"
        + "(SELECT MAX(a.last_seen) FROM agent a WHERE a.host_id=n.host_id) AS host_agent_last_seen "
        + "FROM kubernetes_cluster_node n WHERE n.cluster_id=? ORDER BY n.node_name,n.node_uid",
        clusterId);
    Instant newest = observedAt;
    Instant now = Instant.now();
    List<ClusterNode> nodes = rows.stream()
        .map(row -> node(row, now, staleAfterSeconds)).toList();
    return new ClusterNodeList(clusterId, newest, nodes);
  }

  private Map<String, Object> requireCluster(UUID clusterId) {
    return store.transaction(connection -> {
      var rows = query(connection, "SELECT " + CLUSTER_COLUMNS + ",encrypted_kubeconfig "
          + "FROM kubernetes_cluster WHERE id=?", clusterId);
      if (rows.isEmpty()) throw new ClusterNotFoundException();
      return new java.util.LinkedHashMap<>(rows.getFirst());
    });
  }

  private KubernetesClient openClusterClient(UUID clusterId, Map<String, Object> cluster) {
    if ("IN_CLUSTER".equals(cluster.get("credential_source"))) {
      return clients.openInCluster();
    }
    byte[] encrypted = (byte[])cluster.get("encrypted_kubeconfig");
    String kubeconfig;
    try {
      kubeconfig = vault.decrypt(clusterId, encrypted);
    } finally {
      java.util.Arrays.fill(encrypted, (byte)0);
    }
    return clients.open(KubernetesClusterKubeConfig.select(kubeconfig,
        String.valueOf(cluster.get("context_name"))));
  }

  private void markUnavailable(UUID clusterId, RuntimeException failure) {
    Instant now = Instant.now();
    String code = failure instanceof ClusterPermissionDeniedException ? "RBAC_DENIED"
        : failure instanceof ClusterCredentialRejectedException ? "CREDENTIALS_REJECTED"
        : "API_UNAVAILABLE";
    store.transaction(connection -> {
      update(connection, "UPDATE kubernetes_cluster SET health_status='UNAVAILABLE',"
          + "last_error_code=?,updated_at=? WHERE id=?", code, Timestamp.from(now), clusterId);
      return null;
    });
  }

  RuntimeException reportApiFailure(UUID clusterId, RuntimeException failure) {
    RuntimeException classified = clusterFailure(failure);
    markUnavailable(clusterId, classified);
    return classified;
  }

  private static RuntimeException clusterFailure(RuntimeException failure) {
    if (failure instanceof io.fabric8.kubernetes.client.KubernetesClientException clientFailure) {
      if (clientFailure.getCode() == 401) return new ClusterCredentialRejectedException();
      if (clientFailure.getCode() == 403) return new ClusterPermissionDeniedException();
    }
    return new ClusterApiUnavailableException();
  }

  private static List<NodeObservation> discoverNodes(KubernetesClient client, String kind) {
    NodeList response = client.nodes().list(new ListOptionsBuilder().withLimit(MAX_NODES + 1L).build());
    if (response == null || response.getItems() == null
        || response.getItems().size() > MAX_NODES
        || response.getMetadata() != null
        && response.getMetadata().getContinue() != null
        && !response.getMetadata().getContinue().isBlank()) {
      throw new IllegalStateException("Kubernetes Node inventory is incomplete");
    }
    return response.getItems().stream().map(node -> observeNode(node, kind))
        .sorted(Comparator.comparing(NodeObservation::name).thenComparing(NodeObservation::uid))
        .toList();
  }

  private static CloudCoreObservation observeCloudCore(KubernetesClient client, String kind) {
    if (!"KUBEEDGE".equals(kind)) return new CloudCoreObservation("NOT_OBSERVED", null);
    try {
      var response = client.pods().inNamespace("kubeedge").withLabel("kubeedge", "cloudcore")
          .list(new ListOptionsBuilder().withLimit(33L).build());
      if (response == null || response.getItems() == null || response.getItems().size() > 32
          || response.getMetadata() != null && response.getMetadata().getContinue() != null
          && !response.getMetadata().getContinue().isBlank()) {
        return new CloudCoreObservation("UNKNOWN", null);
      }
      Instant observedAt = Instant.now();
      List<Pod> pods = response.getItems();
      return new CloudCoreObservation(cloudCoreStatus(pods), observedAt);
    } catch (RuntimeException unavailable) {
      return new CloudCoreObservation("UNKNOWN", null);
    }
  }

  static String cloudCoreStatus(List<Pod> pods) {
    if (pods.isEmpty()) return "NOT_OBSERVED";
    long readyPods = pods.stream().filter(KubernetesClusterRegistryService::cloudCorePodReady).count();
    return readyPods == pods.size() ? "HEALTHY" : readyPods > 0 ? "DEGRADED" : "UNAVAILABLE";
  }

  private static boolean cloudCorePodReady(Pod pod) {
    if (pod == null || pod.getStatus() == null || !"Running".equals(pod.getStatus().getPhase())
        || pod.getStatus().getContainerStatuses() == null
        || pod.getStatus().getContainerStatuses().isEmpty()) return false;
    return pod.getStatus().getContainerStatuses().stream().allMatch(status ->
        status != null && Boolean.TRUE.equals(status.getReady()));
  }

  private static NodeObservation observeNode(Node node, String kind) {
    if (node == null || node.getMetadata() == null || blank(node.getMetadata().getUid())
        || blank(node.getMetadata().getName())) {
      throw new IllegalStateException("Kubernetes Node identity is incomplete");
    }
    var metadata = node.getMetadata();
    Map<String, String> labels = metadata.getLabels() == null ? Map.of() : metadata.getLabels();
    LinkedHashSet<String> roles = new LinkedHashSet<>();
    labels.keySet().stream().filter(key -> key.startsWith("node-role.kubernetes.io/"))
        .map(key -> key.substring("node-role.kubernetes.io/".length()))
        .filter(value -> !value.isBlank()).sorted().forEach(roles::add);
    boolean edge = kind.equals("KUBEEDGE") && (labels.keySet().stream()
        .anyMatch(key -> key.equals("node-role.kubernetes.io/edge")
            || key.startsWith("node-role.kubernetes.io/edge/"))
        || "edge".equalsIgnoreCase(labels.get("kubeedge.io/role"))
        || "edge".equalsIgnoreCase(labels.get("kubeedge.io/node-role")));
    boolean ready = node.getStatus() != null && node.getStatus().getConditions() != null
        && node.getStatus().getConditions().stream().anyMatch(condition -> condition != null
        && "Ready".equals(condition.getType()) && "True".equals(condition.getStatus()));
    String version = node.getStatus() == null || node.getStatus().getNodeInfo() == null
        ? null : bounded(node.getStatus().getNodeInfo().getKubeletVersion(), 128);
    String ip = node.getStatus() == null || node.getStatus().getAddresses() == null ? null
        : node.getStatus().getAddresses().stream().filter(address -> address != null
            && "InternalIP".equals(address.getType())).map(address -> address.getAddress())
            .filter(value -> value != null && value.length() <= 64).findFirst().orElse(null);
    return new NodeObservation(bounded(metadata.getUid(), 128), bounded(metadata.getName(), 253),
        String.join(",", roles), edge, ready, version, ip,
        edge ? "KUBEEDGE" : "KUBERNETES");
  }

  static void replaceNodes(java.sql.Connection connection, UUID clusterId,
                                   List<NodeObservation> nodes, Instant observedAt)
      throws java.sql.SQLException {
    boolean postgres = connection.getMetaData().getDatabaseProductName()
        .equalsIgnoreCase("PostgreSQL");
    for (NodeObservation node : nodes) {
      String containerOwner = query(connection,
          "SELECT container_owner FROM kubernetes_cluster_node WHERE cluster_id=? AND node_uid=?",
          clusterId, node.uid()).stream().findFirst()
          .map(row -> "KUBEEDGE".equals(row.get("container_owner"))
              ? "KUBEEDGE" : node.containerOwner())
          .orElse(node.containerOwner());
      if (postgres) {
        update(connection, "INSERT INTO kubernetes_cluster_node (cluster_id,node_uid,node_name,roles,"
            + "node_present,kubeedge_edge,ready,kubelet_version,internal_ip,container_owner,observed_at) "
            + "VALUES (?,?,?,?,TRUE,?,?,?,?,?,?) ON CONFLICT (cluster_id,node_uid) DO UPDATE SET "
            + "node_name=EXCLUDED.node_name,roles=EXCLUDED.roles,node_present=TRUE,"
            + "kubeedge_edge=EXCLUDED.kubeedge_edge,"
            + "ready=EXCLUDED.ready,kubelet_version=EXCLUDED.kubelet_version,internal_ip=EXCLUDED.internal_ip,"
            + "container_owner=EXCLUDED.container_owner,observed_at=EXCLUDED.observed_at",
            clusterId, node.uid(), node.name(), node.roles(), node.kubeedgeEdge(), node.ready(),
            node.kubeletVersion(), node.internalIp(), containerOwner, Timestamp.from(observedAt));
      } else {
        update(connection, "MERGE INTO kubernetes_cluster_node (cluster_id,node_uid,node_name,roles,"
            + "node_present,kubeedge_edge,ready,kubelet_version,internal_ip,container_owner,observed_at) "
            + "KEY(cluster_id,node_uid) VALUES (?,?,?,?,TRUE,?,?,?,?,?,?)", clusterId, node.uid(),
            node.name(), node.roles(), node.kubeedgeEdge(), node.ready(), node.kubeletVersion(),
            node.internalIp(), containerOwner, Timestamp.from(observedAt));
      }
    }
    String missingNodes = nodes.isEmpty() ? "" : " AND node_uid NOT IN (" + String.join(",",
        java.util.Collections.nCopies(nodes.size(), "?")) + ")";
    List<Object> missingParameters = new ArrayList<>();
    missingParameters.add(clusterId);
    nodes.stream().map(NodeObservation::uid).forEach(missingParameters::add);
    update(connection, "UPDATE kubernetes_cluster_node SET node_present=FALSE,ready=FALSE WHERE cluster_id=?"
        + missingNodes + " AND host_id IS NOT NULL",
        missingParameters.toArray());
    String deleteSql = "DELETE FROM kubernetes_cluster_node WHERE cluster_id=?";
    List<Object> parameters = new ArrayList<>();
    parameters.add(clusterId);
    if (!nodes.isEmpty()) {
      deleteSql += " AND node_uid NOT IN (" + String.join(",", java.util.Collections.nCopies(
          nodes.size(), "?")) + ")";
      nodes.stream().map(NodeObservation::uid).forEach(parameters::add);
    }
    deleteSql += " AND host_id IS NULL";
    update(connection, deleteSql, parameters.toArray());
    update(connection, "UPDATE kubernetes_cluster SET health_status='HEALTHY',observed_at=?,"
        + "last_error_code=NULL,updated_at=? WHERE id=?", Timestamp.from(observedAt),
        Timestamp.from(observedAt), clusterId);
  }

  private boolean configuredEncryptionKey() {
    try {
      byte[] bytes = java.util.Base64.getDecoder().decode(credentialKey);
      boolean valid = bytes.length == 32;
      java.util.Arrays.fill(bytes, (byte)0);
      return valid;
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private static String clusterFingerprint(KubernetesClient client) {
    Namespace namespace = client.namespaces().withName("kube-system").get();
    if (namespace == null || namespace.getMetadata() == null
        || namespace.getMetadata().getUid() == null
        || namespace.getMetadata().getUid().isBlank()
        || namespace.getMetadata().getUid().length() > 128) {
      throw new IllegalStateException("Kubernetes cluster identity is unavailable");
    }
    return namespace.getMetadata().getUid();
  }

  private static String kubernetesServiceIp(KubernetesClient client) {
    var service = client.services().inNamespace("default").withName("kubernetes").get();
    if (service == null || service.getSpec() == null || service.getSpec().getClusterIP() == null
        || service.getSpec().getClusterIP().isBlank()
        || "None".equalsIgnoreCase(service.getSpec().getClusterIP())) return null;
    return service.getSpec().getClusterIP();
  }

  private UUID findLocalCluster(String fingerprint, String serviceIp) {
    var identified = store.transaction(connection -> query(connection,
        "SELECT id FROM kubernetes_cluster WHERE cluster_fingerprint=?", fingerprint));
    if (!identified.isEmpty()) return (UUID)identified.getFirst().get("id");
    var candidates = store.transaction(connection -> query(connection,
        "SELECT id,endpoint,context_name,encrypted_kubeconfig,credential_source "
            + "FROM kubernetes_cluster WHERE kind='K3S' AND cluster_fingerprint IS NULL"));
    for (var candidate : candidates) {
      if (!"KUBECONFIG".equals(candidate.get("credential_source"))) continue;
      UUID candidateId = (UUID)candidate.get("id");
      try (KubernetesClient client = openClusterClient(candidateId, candidate)) {
        if (fingerprint.equals(clusterFingerprint(client))) {
          store.transaction(connection -> {
            claimFingerprint(connection, candidateId, fingerprint);
            return null;
          });
          return candidateId;
        }
      } catch (RuntimeException unavailable) {
        if (serviceIp != null && serviceIp.equals(endpointHost(
            String.valueOf(candidate.get("endpoint"))))) throw unavailable;
      }
    }
    return null;
  }

  private static String endpointHost(String endpoint) {
    try { return URI.create(endpoint).getHost(); }
    catch (IllegalArgumentException invalid) { return null; }
  }

  private static void claimFingerprint(java.sql.Connection connection, UUID clusterId,
                                       String fingerprint) throws java.sql.SQLException {
    if (query(connection,
        "SELECT id FROM kubernetes_cluster WHERE cluster_fingerprint=? AND id<>?",
        fingerprint, clusterId).isEmpty()) {
      update(connection, "UPDATE kubernetes_cluster SET cluster_fingerprint=? "
          + "WHERE id=? AND cluster_fingerprint IS NULL", fingerprint, clusterId);
    }
  }

  private static void mapDiscoveredNode(java.sql.Connection connection, UUID clusterId,
                                        String nodeUid, UUID hostId) throws java.sql.SQLException {
    if (nodeUid == null || query(connection,
        "SELECT node_uid FROM kubernetes_cluster_node WHERE host_id=?", hostId).isEmpty()) {
      update(connection, "UPDATE kubernetes_cluster_node SET host_id=? "
          + "WHERE cluster_id=? AND node_uid=? AND host_id IS NULL", hostId, clusterId, nodeUid);
    }
  }

  private static boolean nodeReady(Node node) {
    return node.getStatus() != null && node.getStatus().getConditions() != null
        && node.getStatus().getConditions().stream().anyMatch(condition -> condition != null
        && "Ready".equals(condition.getType()) && "True".equals(condition.getStatus()));
  }

  private static ClusterSummary summary(Map<String, Object> row) {
    Instant observedAt = instant(row.get("observed_at"));
    String healthStatus = String.valueOf(row.get("health_status"));
    boolean observationsStale = "UNAVAILABLE".equals(healthStatus) || observedAt == null
        || observedAt.isBefore(Instant.now().minusSeconds(OBSERVATION_STALE_AFTER_SECONDS));
    return new ClusterSummary((UUID)row.get("id"), String.valueOf(row.get("name")),
        String.valueOf(row.get("kind")), String.valueOf(row.get("endpoint")),
        String.valueOf(row.get("context_name")), healthStatus,
        String.valueOf(row.get("cloudcore_status")), instant(row.get("cloudcore_observed_at")),
        observedAt, observationsStale, (String)row.get("last_error_code"),
        ((Number)row.get("node_count")).intValue(), instant(row.get("created_at")),
        String.valueOf(row.get("credential_source")), (UUID)row.get("discovered_host_id"));
  }

  private static ClusterNode node(Map<String, Object> row, Instant now, int staleAfterSeconds) {
    String roles = String.valueOf(row.get("roles"));
    List<String> roleList = roles.isBlank() ? List.of() : List.of(roles.split(","));
    boolean nodePresent = Boolean.TRUE.equals(row.get("node_present"));
    boolean edgeNode = Boolean.TRUE.equals(row.get("kubeedge_edge"));
    boolean ready = Boolean.TRUE.equals(row.get("ready"));
    String edgeCoreRegistrationStatus = edgeCoreRegistrationStatus(edgeNode, nodePresent, ready);
    Instant edgeCoreObservedAt = instant(row.get("edgecore_observed_at"));
    String edgeCoreProcessStatus = edgeCoreProcessStatus(edgeNode,
        (UUID)row.get("host_id"), (String)row.get("edgecore_active_state"), edgeCoreObservedAt,
        instant(row.get("host_agent_last_seen")), now, staleAfterSeconds);
    return new ClusterNode((UUID)row.get("cluster_id"), String.valueOf(row.get("node_uid")),
        String.valueOf(row.get("node_name")), roleList, nodePresent,
        edgeNode, ready,
        (String)row.get("kubelet_version"), (String)row.get("internal_ip"),
        edgeCoreRegistrationStatus, edgeCoreProcessStatus, edgeCoreObservedAt,
        String.valueOf(row.get("container_owner")),
        (UUID)row.get("host_id"),
        instant(row.get("observed_at")));
  }

  static String edgeCoreProcessStatus(boolean edgeNode, UUID hostId, String activeState,
                                      Instant serviceObservedAt, Instant agentLastSeen,
                                      Instant now, int staleAfterSeconds) {
    if (!edgeNode) return "NOT_APPLICABLE";
    if (hostId == null) return "NOT_MAPPED";
    if (serviceObservedAt == null || activeState == null) return "NOT_OBSERVED";
    Instant staleBefore = now.minusSeconds(staleAfterSeconds);
    if (serviceObservedAt.isBefore(staleBefore) || agentLastSeen == null
        || agentLastSeen.isBefore(staleBefore)) return "STALE";
    return switch (activeState) {
      case "ACTIVE" -> "ACTIVE";
      case "INACTIVE", "FAILED" -> "INACTIVE";
      default -> "UNKNOWN";
    };
  }

  static String edgeCoreRegistrationStatus(boolean edgeNode, boolean nodePresent, boolean ready) {
    if (!edgeNode) return "NOT_EDGE_NODE";
    if (!nodePresent) return "NOT_PRESENT";
    return ready ? "REGISTERED_READY" : "REGISTERED_NOT_READY";
  }

  private static Instant instant(Object value) {
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof OffsetDateTime timestamp) return timestamp.toInstant();
    if (value instanceof Instant timestamp) return timestamp;
    return null;
  }

  private static void validateNodeUid(String nodeUid) {
    if (nodeUid == null || !NODE_UID.matcher(nodeUid).matches()) {
      throw new IllegalArgumentException("Kubernetes Node UID is invalid");
    }
  }

  private static String bounded(String value, int maximum) {
    if (value == null || value.isBlank() || value.length() > maximum
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalStateException("Kubernetes Node metadata is invalid");
    }
    return value;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  public static final class ClusterApiUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
  public static final class ClusterCredentialRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
  public static final class ClusterPermissionDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
  public static final class CredentialKeyUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
  public static final class ClusterAlreadyExistsException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
  public static final class ClusterNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
  public static final class ClusterNodeNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
  public static final class HostNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
  public static final class HostAlreadyMappedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
  public static final class HostOwnershipManagedByKubernetesException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
