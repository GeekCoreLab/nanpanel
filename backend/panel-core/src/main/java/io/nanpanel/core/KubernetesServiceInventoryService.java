package io.nanpanel.core;

import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.nanpanel.cluster.KubernetesServiceDiscovery;
import io.nanpanel.cluster.KubernetesTopologyDiscovery;
import io.nanpanel.cluster.KubernetesServiceDiscovery.EndpointObservation;
import io.nanpanel.cluster.KubernetesServiceDiscovery.EndpointPortObservation;
import io.nanpanel.cluster.KubernetesServiceDiscovery.Snapshot;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class KubernetesServiceInventoryService {
  private static final Logger LOG = Logger.getLogger(KubernetesServiceInventoryService.class);
  private static final int MAX_PROBES_PER_REFRESH = 32;
  private static final int PROBE_TIMEOUT_MILLIS = 350;

  @Inject AuthStore store;
  @Inject ObjectMapper mapper;

  private final KubernetesServiceDiscovery discovery = new KubernetesServiceDiscovery();
  private final KubernetesTopologyDiscovery topologyDiscovery = new KubernetesTopologyDiscovery();
  private final AtomicInteger probeOffset = new AtomicInteger();

  @Inject
  @ConfigProperty(name = "nanpanel.kubernetes.discovery.enabled", defaultValue = "false")
  boolean enabled;

  @Inject
  @ConfigProperty(name = "nanpanel.kubernetes.discovery.cluster-id", defaultValue = "disabled")
  String clusterId;

  @Inject
  @ConfigProperty(name = "nanpanel.kubernetes.discovery.namespace", defaultValue = "disabled")
  String namespace;

  @Inject
  @ConfigProperty(name = "nanpanel.kubernetes.service-probes.enabled", defaultValue = "false")
  boolean serviceProbesEnabled;

  public boolean isEnabled() {
    return enabled;
  }

  public boolean isConfigured() {
    return isClusterConfigured() && !namespace.isBlank()
        && !"disabled".equals(namespace)
        && namespace.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  }

  public boolean isClusterConfigured() {
    return clusterId != null && !"disabled".equals(clusterId)
        && clusterId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
  }

  public KubernetesTopologyDiscovery.NamespaceSnapshot topologyNamespaces() {
    ensureClusterConfigured();
    try (var client = new KubernetesClientBuilder().build()) {
      return topologyDiscovery.namespaces(client, clusterId);
    }
  }

  public KubernetesTopologyDiscovery.NamespaceDetailSnapshot topologyNamespace(String name) {
    ensureClusterConfigured();
    try (var client = new KubernetesClientBuilder().build()) {
      return topologyDiscovery.namespace(client, clusterId, name);
    }
  }

  public Snapshot latest() {
    ensureConfigured();
    String sourceId = sourceId();
    var rows = store.transaction(c -> query(c,
        "SELECT inventory_json,observed_at FROM kubernetes_service_inventory WHERE source_id=?",
        sourceId));
    if (rows.isEmpty()) {
      return new Snapshot(sourceId, namespace, null, List.of());
    }
    try {
      Snapshot stored = mapper.readValue(
          String.valueOf(rows.getFirst().get("inventory_json")), Snapshot.class);
      Instant observedAt = instant(rows.getFirst().get("observed_at"));
      if (observedAt == null) {
        throw new IllegalStateException("Stored Kubernetes inventory timestamp is invalid");
      }
      // Keep the public inventory timestamp identical to PostgreSQL's persisted
      // microsecond timestamp, which is also referenced by stored probe rows.
      return new Snapshot(stored.sourceId(), stored.namespace(), observedAt,
          stored.services());
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("Unable to decode stored Kubernetes inventory", failure);
    }
  }

  public Snapshot refresh() {
    ensureConfigured();
    Snapshot snapshot;
    try (var client = new KubernetesClientBuilder().build()) {
      snapshot = discovery.discover(client, clusterId, namespace);
    }
    persist(snapshot);
    if (serviceProbesEnabled) {
      try {
        probe(snapshot);
      } catch (RuntimeException failure) {
        LOG.warnf("Kubernetes service probes failed (%s)",
            failure.getClass().getSimpleName());
      }
    }
    return snapshot;
  }

  public ProbeSnapshot latestProbes() {
    ensureConfigured();
    var rows = store.transaction(c -> query(c,
        "SELECT service_name,endpoint_address,port,protocol,outcome,inventory_observed_at,observed_at "
            + "FROM kubernetes_service_probe WHERE source_id=? AND observed_at>=? "
            + "ORDER BY observed_at DESC,service_name,endpoint_address,port LIMIT 4096",
        sourceId(), Timestamp.from(Instant.now().minusSeconds(7L * 24 * 60 * 60))));
    List<ProbeObservation> observations = new ArrayList<>(rows.size());
    Instant latest = null;
    for (var row : rows) {
      Instant observedAt = instant(row.get("observed_at"));
      if (observedAt != null && (latest == null || observedAt.isAfter(latest))) {
        latest = observedAt;
      }
      observations.add(new ProbeObservation(
          String.valueOf(row.get("service_name")),
          String.valueOf(row.get("endpoint_address")),
          ((Number)row.get("port")).intValue(),
          String.valueOf(row.get("protocol")),
          String.valueOf(row.get("outcome")),
          instant(row.get("inventory_observed_at")), observedAt));
    }
    return new ProbeSnapshot(sourceId(), serviceProbesEnabled, latest, observations);
  }

  @Scheduled(every = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void refreshScheduled() {
    if (!enabled || !isConfigured()) {
      return;
    }
    try {
      refresh();
    } catch (RuntimeException failure) {
      LOG.warnf("Kubernetes service inventory refresh failed (%s)",
          failure.getClass().getSimpleName());
    }
  }

  @Scheduled(every = "24h")
  void pruneExpiredProbes() {
    try {
      store.transaction(c -> update(c,
          "DELETE FROM kubernetes_service_probe WHERE observed_at<?",
          Timestamp.from(Instant.now().minusSeconds(7L * 24 * 60 * 60))));
    } catch (RuntimeException failure) {
      LOG.warnf("Expired Kubernetes service probe cleanup failed (%s)",
          failure.getClass().getSimpleName());
    }
  }

  private void persist(Snapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    String json;
    try {
      json = mapper.writeValueAsString(snapshot);
    } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
      throw new IllegalStateException("Unable to encode Kubernetes inventory", failure);
    }
    store.transaction(c -> {
      boolean postgres = c.getMetaData().getDatabaseProductName()
          .equalsIgnoreCase("PostgreSQL");
      if (postgres) {
        update(c, "INSERT INTO kubernetes_service_inventory "
            + "(source_id,namespace,observed_at,inventory_json) VALUES (?,?,?,?) "
            + "ON CONFLICT (source_id) DO UPDATE SET namespace=EXCLUDED.namespace, "
            + "observed_at=EXCLUDED.observed_at, inventory_json=EXCLUDED.inventory_json",
            snapshot.sourceId(), snapshot.namespace(), Timestamp.from(snapshot.observedAt()), json);
      } else {
        update(c, "MERGE INTO kubernetes_service_inventory "
            + "(source_id,namespace,observed_at,inventory_json) KEY(source_id) VALUES (?,?,?,?)",
            snapshot.sourceId(), snapshot.namespace(), Timestamp.from(snapshot.observedAt()), json);
      }
      return null;
    });
  }

  private void probe(Snapshot snapshot) {
    List<ProbeTarget> candidates = new ArrayList<>();
    for (var service : snapshot.services()) {
      for (EndpointObservation endpoint : service.endpoints()) {
        if (!Boolean.TRUE.equals(endpoint.ready()) ||
            !("IPv4".equals(endpoint.addressType()) || "IPv6".equals(endpoint.addressType()))) {
          continue;
        }
        for (EndpointPortObservation port : endpoint.ports()) {
          if ("TCP".equals(port.protocol()) && port.port() != null) {
            candidates.add(new ProbeTarget(service.serviceName(), endpoint.address(), port.port()));
          }
        }
      }
    }
    candidates.sort(java.util.Comparator.comparing(ProbeTarget::serviceName)
        .thenComparing(ProbeTarget::address).thenComparingInt(ProbeTarget::port));
    if (candidates.isEmpty()) {
      persistProbes(snapshot.sourceId(), List.of());
      return;
    }
    int count = Math.min(MAX_PROBES_PER_REFRESH, candidates.size());
    int start = Math.floorMod(probeOffset.getAndAdd(count), candidates.size());
    List<ProbeObservation> observations = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      ProbeTarget target = candidates.get((start + index) % candidates.size());
      observations.add(probeOne(snapshot.observedAt(), target));
    }
    persistProbes(snapshot.sourceId(), observations);
  }

  private ProbeObservation probeOne(Instant inventoryObservedAt, ProbeTarget target) {
    Instant observedAt = Instant.now();
    String outcome;
    try {
      InetAddress address = InetAddress.getByName(target.address());
      if (!privateAddress(address)) {
        outcome = "SKIPPED";
      } else {
        try (Socket socket = new Socket()) {
          socket.connect(new InetSocketAddress(address, target.port()), PROBE_TIMEOUT_MILLIS);
          outcome = "SUCCEEDED";
        } catch (SocketTimeoutException failure) {
          outcome = "TIMED_OUT";
        } catch (IOException failure) {
          outcome = "FAILED";
        }
      }
    } catch (IOException failure) {
      outcome = "FAILED";
    }
    return new ProbeObservation(target.serviceName(), target.address(), target.port(),
        "TCP", outcome, inventoryObservedAt, observedAt);
  }

  private void persistProbes(String source, List<ProbeObservation> observations) {
    store.transaction(c -> {
      boolean postgres = c.getMetaData().getDatabaseProductName()
          .equalsIgnoreCase("PostgreSQL");
      for (ProbeObservation observation : observations) {
        String key = probeKey(source, observation);
        if (postgres) {
          update(c, "INSERT INTO kubernetes_service_probe "
              + "(probe_key,source_id,service_name,endpoint_address,port,protocol,outcome,inventory_observed_at,observed_at) "
              + "VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (probe_key) DO UPDATE SET "
              + "outcome=EXCLUDED.outcome,inventory_observed_at=EXCLUDED.inventory_observed_at,observed_at=EXCLUDED.observed_at "
              + "WHERE EXCLUDED.observed_at >= kubernetes_service_probe.observed_at",
              key, source, observation.serviceName(), observation.address(), observation.port(),
              observation.protocol(), observation.outcome(),
              Timestamp.from(observation.inventoryObservedAt()), Timestamp.from(observation.observedAt()));
        } else {
          update(c, "MERGE INTO kubernetes_service_probe "
              + "(probe_key,source_id,service_name,endpoint_address,port,protocol,outcome,inventory_observed_at,observed_at) "
              + "KEY(probe_key) VALUES (?,?,?,?,?,?,?,?,?)",
              key, source, observation.serviceName(), observation.address(), observation.port(),
              observation.protocol(), observation.outcome(),
              Timestamp.from(observation.inventoryObservedAt()), Timestamp.from(observation.observedAt()));
        }
      }
      update(c, "DELETE FROM kubernetes_service_probe WHERE source_id=? AND observed_at<?",
          source, Timestamp.from(Instant.now().minusSeconds(7L * 24 * 60 * 60)));
      return null;
    });
  }

  private static boolean privateAddress(InetAddress address) {
    if (address.isAnyLocalAddress() || address.isLoopbackAddress()
        || address.isLinkLocalAddress() || address.isMulticastAddress()) {
      return false;
    }
    if (address instanceof Inet6Address) {
      byte first = address.getAddress()[0];
      return (first & 0xfe) == 0xfc;
    }
    return address.isSiteLocalAddress();
  }

  private static String probeKey(String source, ProbeObservation observation) {
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      String value = source + "\u0000" + observation.serviceName() + "\u0000"
          + observation.address() + "\u0000" + observation.port() + "\u0000"
          + observation.protocol();
      return java.util.HexFormat.of().formatHex(digest.digest(
          value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  private static Instant instant(Object value) {
    if (value instanceof Timestamp timestamp) {
      return timestamp.toInstant();
    }
    if (value instanceof OffsetDateTime timestamp) {
      return timestamp.toInstant();
    }
    if (value instanceof Instant timestamp) {
      return timestamp;
    }
    return null;
  }

  private void ensureConfigured() {
    if (!enabled) {
      throw new IllegalStateException("Kubernetes service discovery is disabled");
    }
    if (!isConfigured()) {
      throw new IllegalStateException("Kubernetes service discovery is misconfigured");
    }
  }

  private void ensureClusterConfigured() {
    if (!enabled || !isClusterConfigured()) {
      throw new IllegalStateException("Kubernetes cluster discovery is not configured");
    }
  }

  private String sourceId() {
    return "kubernetes:" + clusterId;
  }

  private record ProbeTarget(String serviceName, String address, int port) {}

  public record ProbeSnapshot(String sourceId, boolean enabled, Instant observedAt,
                              List<ProbeObservation> probes) {
    public ProbeSnapshot {
      probes = List.copyOf(probes);
    }
  }

  public record ProbeObservation(String serviceName, String address, int port,
                                 String protocol, String outcome,
                                 Instant inventoryObservedAt, Instant observedAt) {}
}
