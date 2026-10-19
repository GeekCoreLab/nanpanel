package io.nanpanel.core;

import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class CloudflareDnsCoordinator {
  private static final Logger LOG = Logger.getLogger(CloudflareDnsCoordinator.class);
  private static final int RECORD_TTL = 60;
  private static final Duration STATUS_MAX_AGE = Duration.ofSeconds(35);
  private static final Duration HOST_MAX_AGE = Duration.ofSeconds(120);
  private static final Duration LEASE_DURATION = Duration.ofSeconds(120);
  private static final Duration SWITCH_COOLDOWN = Duration.ofSeconds(120);
  private static final Duration STATUS_CONFIRMATION_MAX_AGE = Duration.ofSeconds(35);

  @Inject AuthStore store;
  @Inject CloudflareDnsClient cloudflare;

  @Inject
  @ConfigProperty(name = "nanpanel.dns.enabled", defaultValue = "false")
  boolean enabled;
  @Inject
  @ConfigProperty(name = "nanpanel.dns.cloudflare-token", defaultValue = "disabled")
  String token;
  @Inject
  @ConfigProperty(name = "nanpanel.dns.zone-id", defaultValue = "disabled")
  String zoneId;
  @Inject
  @ConfigProperty(name = "nanpanel.dns.zone-name", defaultValue = "disabled")
  String zoneName;
  @Inject
  @ConfigProperty(name = "nanpanel.dns.managed-suffix", defaultValue = "disabled")
  String managedSuffix;
  @Inject
  @ConfigProperty(name = "nanpanel.gateway.cluster-id", defaultValue = "disabled")
  String clusterId;

  private final String ownerId = UUID.randomUUID().toString();
  private ScheduledExecutorService executor;

  @PostConstruct
  void start() {
    if (!enabled) return;
    validateConfiguration();
    executor = Executors.newSingleThreadScheduledExecutor(task -> {
      Thread thread = new Thread(task, "nanpanel-cloudflare-dns");
      thread.setDaemon(true);
      return thread;
    });
    executor.scheduleWithFixedDelay(this::syncSafely, 0, 20, TimeUnit.SECONDS);
  }

  @PreDestroy
  void stop() {
    if (executor != null) executor.shutdownNow();
  }

  private void syncSafely() {
    try {
      synchronizeOnce();
    } catch (Exception failure) {
      LOG.warn("Cloudflare DNS reconciliation failed; existing records were retained", failure);
    }
  }

  void synchronizeOnce() throws IOException, InterruptedException {
    validateConfiguration();
    Long epoch = claimLease();
    if (epoch == null) return;

    List<String> candidates = healthyRelayTargets();
    if (candidates.isEmpty()) return;
    String target = preferredTarget(candidates);
    ensureRecord(apiRecordName(), target, null, epoch);

    for (UUID hostId : activeHosts()) {
      ensureRecord(hostRecordName(hostId), target, hostId, epoch);
    }
  }

  Long claimLease() {
    Instant now = Instant.now();
    return store.transaction(connection -> {
      List<Map<String, Object>> rows = query(connection,
          "SELECT owner_id,epoch,lease_until FROM dns_writer_lease WHERE id=1 FOR UPDATE");
      if (rows.isEmpty()) throw new IllegalStateException("DNS writer lease row is missing");
      Map<String, Object> row = rows.getFirst();
      String currentOwner = row.get("owner_id") == null ? null : String.valueOf(row.get("owner_id"));
      long currentEpoch = ((Number)row.get("epoch")).longValue();
      Instant expires = toInstant(row.get("lease_until"));
      if (ownerId.equals(currentOwner) && expires != null && expires.isAfter(now)) {
        update(connection,
            "UPDATE dns_writer_lease SET lease_until=? WHERE id=1 AND owner_id=? AND epoch=?",
            Timestamp.from(now.plus(LEASE_DURATION)), ownerId, currentEpoch);
        return currentEpoch;
      }
      if (expires != null && expires.isAfter(now)) return null;
      long nextEpoch = currentEpoch + 1;
      update(connection,
          "UPDATE dns_writer_lease SET owner_id=?,epoch=?,lease_until=? WHERE id=1",
          ownerId, nextEpoch, Timestamp.from(now.plus(LEASE_DURATION)));
      return nextEpoch;
    });
  }

  boolean refreshLease(long epoch) {
    Instant now = Instant.now();
    return store.transaction(connection -> {
      int refreshed = update(connection,
          "UPDATE dns_writer_lease SET lease_until=? " +
              "WHERE id=1 AND owner_id=? AND epoch=? AND lease_until>?",
          Timestamp.from(now.plus(LEASE_DURATION)), ownerId, epoch, Timestamp.from(now));
      return refreshed == 1;
    });
  }

  private List<String> healthyRelayTargets() {
    Instant freshAfter = Instant.now().minus(STATUS_MAX_AGE);
    List<Map<String, Object>> rows = store.transaction(connection -> query(connection,
        "SELECT DISTINCT target_name FROM agent_relay_status " +
            "WHERE cluster_id=? AND healthy=? AND healthy_count>=3 AND last_seen>=?",
        clusterId, true, Timestamp.from(freshAfter)));
    Set<String> targets = new HashSet<>();
    for (Map<String, Object> row : rows) {
      String target = String.valueOf(row.get("target_name")).toLowerCase(Locale.ROOT);
      if (validDnsName(target) && !isIpLiteral(target)) targets.add(target);
    }
    return targets.stream().sorted().toList();
  }

  String preferredTarget(List<String> candidates) {
    List<Map<String, Object>> rows = store.transaction(connection -> query(connection,
        "SELECT content FROM dns_managed_record WHERE record_name=? AND state='MANAGED'",
        apiRecordName()));
    if (!rows.isEmpty()) {
      String current = normalizeDnsName(String.valueOf(rows.getFirst().get("content")));
      if (candidates.contains(current)) return current;
      Instant staleAfter = Instant.now().minus(STATUS_CONFIRMATION_MAX_AGE);
      List<Map<String, Object>> status = store.transaction(connection -> query(connection,
          "SELECT healthy,unhealthy_count,last_seen FROM agent_relay_status " +
              "WHERE cluster_id=? AND target_name=? ORDER BY last_seen DESC LIMIT 1",
          clusterId, current));
      if (!status.isEmpty()) {
        Map<String, Object> latest = status.getFirst();
        Instant seen = toInstant(latest.get("last_seen"));
        int failures = ((Number)latest.get("unhealthy_count")).intValue();
        if (Boolean.FALSE.equals(latest.get("healthy")) && seen != null &&
            seen.isAfter(staleAfter) && failures < 3) return current;
      }
    }
    return candidates.stream().min(Comparator.naturalOrder()).orElseThrow();
  }

  private List<UUID> activeHosts() {
    Instant freshAfter = Instant.now().minus(HOST_MAX_AGE);
    List<Map<String, Object>> rows = store.transaction(connection -> query(connection,
        "SELECT DISTINCT h.id FROM host h JOIN agent a ON a.host_id=h.id " +
            "WHERE a.last_seen>=? ORDER BY h.id",
        Timestamp.from(freshAfter)));
    List<UUID> result = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Object id = row.get("id");
      if (id instanceof UUID uuid) result.add(uuid);
      else result.add(UUID.fromString(String.valueOf(id)));
    }
    return result;
  }

  void ensureRecord(String name, String target, UUID hostId, long epoch)
      throws IOException, InterruptedException {
    if (!refreshLease(epoch)) throw new IllegalStateException("DNS writer lease was lost");
    StoredRecord stored = storedRecord(name);
    List<CloudflareDnsClient.Record> records = exactRecords(cloudflare.listRecords(zoneId, name), name);

    if (stored != null && "CONFLICT".equals(stored.state())) {
      throw new IllegalStateException("DNS record is marked conflicted: " + name);
    }
    if (records.size() > 1) {
      recordConflict(name, hostId, records.getFirst(), "multiple Cloudflare records share the managed name", epoch);
      throw new IllegalStateException("multiple Cloudflare records share the managed name: " + name);
    }
    if (stored == null && !records.isEmpty()) {
      recordConflict(name, hostId, records.getFirst(),
          "record existed before NaNPanel ownership was recorded", epoch);
      throw new IllegalStateException("refusing to adopt pre-existing DNS record: " + name);
    }

    CloudflareDnsClient.Record current = records.isEmpty() ? null : records.getFirst();
    if (stored != null && current != null &&
        (!stored.recordId().equals(current.id()) || !"CNAME".equals(current.type()) ||
            !normalizeDnsName(current.content()).equals(normalizeDnsName(stored.content())) ||
            current.ttl() != stored.ttl() || current.proxied() != stored.proxied())) {
      recordConflict(name, hostId, current,
          "Cloudflare record changed outside the managed state", epoch);
      throw new IllegalStateException("managed Cloudflare record changed outside NaNPanel: " + name);
    }

    if (current != null && (!"CNAME".equals(current.type()) || current.ttl() != RECORD_TTL ||
        current.proxied() || !normalizeDnsName(current.content()).equals(target))) {
      if (stored == null) {
        recordConflict(name, hostId, current, "unmanaged record occupies the desired name", epoch);
        throw new IllegalStateException("refusing to overwrite unmanaged DNS record: " + name);
      }
      if (stored != null && stored.switchCount() > 0 &&
          !normalizeDnsName(stored.content()).equals(target) &&
          stored.contentChangedAt() != null &&
          stored.contentChangedAt().isAfter(Instant.now().minus(SWITCH_COOLDOWN))) {
        return;
      }
      if (!refreshLease(epoch)) throw new IllegalStateException("DNS writer lease was lost");
      writeAndVerify(name, target, hostId, epoch, stored, current);
      return;
    }

    if (current == null) {
      if (!refreshLease(epoch)) throw new IllegalStateException("DNS writer lease was lost");
      writeAndVerify(name, target, hostId, epoch, stored, null);
      return;
    }
    saveManaged(name, hostId, current, epoch);
  }

  private void writeAndVerify(String name, String target, UUID hostId, long epoch,
      StoredRecord stored, CloudflareDnsClient.Record current)
      throws IOException, InterruptedException {
    CloudflareDnsClient.Record written = null;
    IOException writeFailure = null;
    try {
      written = current == null
          ? cloudflare.createRecord(zoneId, name, target)
          : cloudflare.updateRecord(zoneId, current.id(), name, target);
    } catch (IOException failure) {
      writeFailure = failure;
    }

    List<CloudflareDnsClient.Record> readBack = exactRecords(cloudflare.listRecords(zoneId, name), name);
    if (readBack.size() != 1) {
      if (writeFailure != null) throw writeFailure;
      throw new IOException("Cloudflare DNS write did not produce one exact record for " + name);
    }
    CloudflareDnsClient.Record verified = readBack.getFirst();
    if (!matchesDesired(verified, name, target) ||
        (written != null && !written.id().equals(verified.id())) ||
        (stored != null && current != null && !current.id().equals(verified.id()))) {
      if (writeFailure != null) throw writeFailure;
      throw new IOException("Cloudflare DNS read-back did not match the requested record for " + name);
    }
    if (!refreshLease(epoch)) throw new IllegalStateException("DNS writer lease was lost after Cloudflare write");
    saveManaged(name, hostId, verified, epoch);
  }

  private void saveManaged(String name, UUID hostId, CloudflareDnsClient.Record record, long epoch) {
    store.transaction(connection -> {
      requireLease(connection, epoch);
      int updated = update(connection,
          "UPDATE dns_managed_record SET host_id=?,record_id=?,content=?,ttl=?,proxied=?," +
          "state='MANAGED',detail=NULL,last_verified_at=?,content_changed_at=" +
              "CASE WHEN content<>? THEN ? ELSE COALESCE(content_changed_at,updated_at) END," +
              "switch_count=switch_count+CASE WHEN content<>? THEN 1 ELSE 0 END," +
              "updated_at=? WHERE record_name=?",
          hostId, record.id(), record.content(), record.ttl(), record.proxied(),
          Timestamp.from(Instant.now()), record.content(), Timestamp.from(Instant.now()), record.content(),
          Timestamp.from(Instant.now()), name);
      if (updated == 0) {
        update(connection,
            "INSERT INTO dns_managed_record " +
                "(record_name,host_id,record_id,content,ttl,proxied,state,last_verified_at,content_changed_at,updated_at) " +
                "VALUES (?,?,?,?,?,?,'MANAGED',?,?,?)",
            name, hostId, record.id(), record.content(), record.ttl(), record.proxied(),
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
      }
      return null;
    });
  }

  private void recordConflict(String name, UUID hostId, CloudflareDnsClient.Record observed,
      String detail, long epoch) {
    store.transaction(connection -> {
      requireLease(connection, epoch);
      int updated = update(connection,
          "UPDATE dns_managed_record SET state='CONFLICT',detail=?,updated_at=? " +
              "WHERE record_name=?",
          detail, Timestamp.from(Instant.now()), name);
      if (updated == 0) {
        update(connection,
            "INSERT INTO dns_managed_record " +
                "(record_name,host_id,record_id,content,ttl,proxied,state,detail,updated_at) " +
                "VALUES (?,?,?,?,?,?,'CONFLICT',?,?)",
            name, hostId, observed.id(), observed.content(), observed.ttl(), observed.proxied(),
            detail, Timestamp.from(Instant.now()));
      }
      return null;
    });
  }

  private StoredRecord storedRecord(String name) {
    List<Map<String, Object>> rows = store.transaction(connection -> query(connection,
        "SELECT record_id,content,ttl,proxied,state,content_changed_at,switch_count " +
            "FROM dns_managed_record WHERE record_name=?",
        name));
    if (rows.isEmpty()) return null;
    Map<String, Object> row = rows.getFirst();
    return new StoredRecord(String.valueOf(row.get("record_id")),
        String.valueOf(row.get("content")), ((Number)row.get("ttl")).intValue(),
        Boolean.TRUE.equals(row.get("proxied")), String.valueOf(row.get("state")),
        row.get("content_changed_at") == null ? null : toInstant(row.get("content_changed_at")),
        ((Number)row.get("switch_count")).intValue());
  }

  private List<CloudflareDnsClient.Record> exactRecords(
      List<CloudflareDnsClient.Record> records, String expectedName) {
    String normalized = normalizeDnsName(expectedName);
    return records.stream().filter(record ->
        normalizeDnsName(record.name()).equals(normalized)).toList();
  }

  private boolean matchesDesired(CloudflareDnsClient.Record record, String name, String target) {
    return "CNAME".equals(record.type()) &&
        normalizeDnsName(record.name()).equals(normalizeDnsName(name)) &&
        normalizeDnsName(record.content()).equals(target) && record.ttl() == RECORD_TTL &&
        !record.proxied();
  }

  private void requireLease(java.sql.Connection connection, long epoch) throws Exception {
    List<Map<String, Object>> rows = query(connection,
        "SELECT owner_id,epoch,lease_until FROM dns_writer_lease WHERE id=1");
    if (rows.isEmpty()) throw new IllegalStateException("DNS writer lease row is missing");
    Map<String, Object> row = rows.getFirst();
    Instant expires = toInstant(row.get("lease_until"));
    if (!ownerId.equals(String.valueOf(row.get("owner_id"))) ||
        ((Number)row.get("epoch")).longValue() != epoch || expires == null ||
        !expires.isAfter(Instant.now()))
      throw new IllegalStateException("DNS writer lease was lost");
  }

  String apiRecordName() {
    return "api." + managedSuffix;
  }

  private String hostRecordName(UUID hostId) {
    return "h-" + hostId.toString().replace("-", "") + "." + managedSuffix;
  }

  private void validateConfiguration() {
    if (!enabled) throw new IllegalStateException("Cloudflare DNS coordination is disabled");
    String suffix = normalizeDnsName(managedSuffix);
    String zone = normalizeDnsName(zoneName);
    if (token == null || token.isBlank() || token.equalsIgnoreCase("disabled") ||
        zoneId == null || zoneId.isBlank() || zoneId.equalsIgnoreCase("disabled") ||
        clusterId == null || clusterId.isBlank() || clusterId.equalsIgnoreCase("disabled") ||
        !validDnsName(zone) || !validDnsName(suffix) ||
        !(suffix.equals(zone) || suffix.endsWith("." + zone)))
      throw new IllegalStateException("Cloudflare DNS configuration is incomplete or outside its Zone");
  }

  private static boolean validDnsName(String value) {
    if (value == null || value.isBlank() || value.length() > 253 || value.endsWith(".")) return false;
    for (String label : value.split("\\.")) {
      if (label.isEmpty() || label.length() > 63 || !label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))
        return false;
    }
    return true;
  }

  private static boolean isIpLiteral(String value) {
    if (value.indexOf(':') >= 0) return true;
    return value.matches("[0-9.]+");
  }

  private static String normalizeDnsName(String value) {
    if (value == null) return "";
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    while (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
    return normalized;
  }

  private static Instant toInstant(Object value) {
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
    if (value instanceof Instant instant) return instant;
    if (value == null) return null;
    return Instant.parse(String.valueOf(value));
  }

  private record StoredRecord(String recordId, String content, int ttl,
                              boolean proxied, String state, Instant contentChangedAt,
                              int switchCount) {}
}
