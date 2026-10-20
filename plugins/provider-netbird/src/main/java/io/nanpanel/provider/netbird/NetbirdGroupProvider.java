package io.nanpanel.provider.netbird;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.nanpanel.plugin.api.NetworkProvider;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.pf4j.Extension;

/**
 * Reconciles peer membership in a dedicated, prefixed NetBird group.
 * Operation recovery across Core restarts is owned by the Core coordinator.
 */
@Extension
public final class NetbirdGroupProvider implements NetworkProvider {
  private static final int MAX_RESPONSE_BYTES = 1_048_576;
  private static final int MAX_PEERS = 512;
  private static final int MAX_GROUPS = 4096;
  private static final Pattern GROUP_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
  private static final Pattern PEER_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final URI apiRoot;
  private final String token;
  private final String authorizationScheme;
  private final String managedPrefix;
  private final HttpClient http;
  private final ConcurrentHashMap<UUID, AppliedOperation> operations = new ConcurrentHashMap<>();

  public NetbirdGroupProvider() {
    this(System.getenv("NETBIRD_API_URL"), System.getenv("NETBIRD_API_TOKEN"),
        System.getenv("NETBIRD_API_TOKEN_SCHEME"),
        System.getenv("NETBIRD_MANAGED_GROUP_PREFIX"), defaultClient());
  }

  NetbirdGroupProvider(String apiUrl, String token, String authorizationScheme,
                       String managedPrefix, HttpClient http) {
    this.apiRoot = parseApiRoot(apiUrl);
    this.token = token == null ? "" : token.trim();
    this.authorizationScheme = authorizationScheme == null || authorizationScheme.isBlank()
        ? "Token" : authorizationScheme.trim();
    if (!Set.of("Token", "Bearer").contains(this.authorizationScheme)) {
      throw new IllegalArgumentException("NetBird token scheme must be Token or Bearer");
    }
    this.managedPrefix = managedPrefix == null || managedPrefix.isBlank()
        ? "nanpanel-managed-" : managedPrefix.trim();
    if (!this.managedPrefix.matches("[a-z0-9][a-z0-9-]{0,50}-")) {
      throw new IllegalArgumentException("NetBird managed group prefix is invalid");
    }
    this.http = Objects.requireNonNull(http, "http");
  }

  @Override
  public String id() {
    return "netbird-groups";
  }

  @Override
  public Set<String> capabilities() {
    return Set.of("overlay.group-membership.reconcile");
  }

  @Override
  public CompletionStage<ChangePlan> plan(String desiredSnapshot, String observedSnapshot) {
    return CompletableFuture.supplyAsync(() -> {
      requireConfigured();
      Desired desired = parseDesired(desiredSnapshot);
      GroupState before = readGroup(desired.groupName());
      requireObservedPrecondition(observedSnapshot, desired.groupName(), before);
      String config = encode(new PlanConfiguration(desired, before));
      String key = sha256(desired.groupName() + "\n" + desired.generation() + "\n"
          + String.join("\n", desired.peerIds()) + "\n" + fingerprint(before));
      return new ChangePlan(UUID.randomUUID(), desired.generation(), key, config);
    });
  }

  @Override
  public CompletionStage<ValidationResult> validate(ChangePlan plan) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        requireConfigured();
        PlanConfiguration configuration = parsePlan(plan);
        GroupState current = readGroup(configuration.desired().groupName());
        if (!Objects.equals(fingerprint(current), fingerprint(configuration.before()))) {
          return new ValidationResult(false, "NetBird group changed after planning");
        }
        return new ValidationResult(true, "NetBird managed group precondition is current");
      } catch (RuntimeException failure) {
        return new ValidationResult(false, safeMessage(failure));
      }
    });
  }

  @Override
  public CompletionStage<UUID> apply(ChangePlan plan) {
    return CompletableFuture.supplyAsync(() -> {
      requireConfigured();
      PlanConfiguration configuration = parsePlan(plan);
      GroupState current = readGroup(configuration.desired().groupName());
      if (!Objects.equals(fingerprint(current), fingerprint(configuration.before()))) {
        throw new IllegalStateException("NetBird group changed after planning");
      }
      GroupState applied = writeDesired(configuration.desired(), current);
      operations.put(plan.id(), new AppliedOperation(configuration, applied, false));
      return plan.id();
    });
  }

  @Override
  public CompletionStage<ValidationResult> verify(UUID operationId) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        requireConfigured();
        AppliedOperation operation = requireOperation(operationId);
        if (operation.alreadyCompensated()) {
          return new ValidationResult(false, "NetBird operation has already been compensated");
        }
        GroupState current = readGroup(operation.configuration().desired().groupName());
        if (current == null || !Objects.equals(fingerprint(current), fingerprint(operation.applied()))) {
          return new ValidationResult(false, "NetBird group drifted after apply");
        }
        return new ValidationResult(true, "NetBird managed group membership verified");
      } catch (RuntimeException failure) {
        return new ValidationResult(false, safeMessage(failure));
      }
    });
  }

  @Override
  public CompletionStage<ValidationResult> compensate(UUID operationId) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        requireConfigured();
        AppliedOperation operation = requireOperation(operationId);
        if (operation.alreadyCompensated()) {
          operations.remove(operationId);
          return new ValidationResult(true, "NetBird compensation was already completed");
        }
        Desired desired = operation.configuration().desired();
        GroupState current = readGroup(desired.groupName());
        if (!Objects.equals(fingerprint(current), fingerprint(operation.applied()))) {
          return new ValidationResult(false,
              "NetBird group drifted after apply; compensation was not applied");
        }
        GroupState before = operation.configuration().before();
        if (before == null) {
          deleteGroup(current);
        } else {
          replaceGroup(before);
        }
        operations.remove(operationId);
        return new ValidationResult(true, "NetBird managed group was compensated");
      } catch (RuntimeException failure) {
        return new ValidationResult(false, safeMessage(failure));
      }
    });
  }

  @Override
  public CompletionStage<ValidationResult> restoreOperation(ChangePlan plan, UUID operationId) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        requireConfigured();
        if (plan == null || plan.id() == null || !plan.id().equals(operationId)) {
          return new ValidationResult(false, "NetBird operation identity does not match its plan");
        }
        PlanConfiguration configuration = parsePlan(plan);
        GroupState current = readGroup(configuration.desired().groupName());
        if (isExpectedApplied(configuration, current)) {
          operations.put(operationId, new AppliedOperation(configuration, current, false));
          return new ValidationResult(true, "NetBird operation state restored from the persisted plan");
        }
        if (matchesBefore(configuration, current)) {
          operations.put(operationId, new AppliedOperation(configuration, null, true));
          return new ValidationResult(true, "NetBird compensation is already reflected externally");
        }
        return new ValidationResult(false, "NetBird operation state cannot be safely restored");
      } catch (RuntimeException failure) {
        return new ValidationResult(false, safeMessage(failure));
      }
    });
  }

  private boolean isExpectedApplied(PlanConfiguration configuration, GroupState current) {
    if (current == null || !current.peerIds().equals(configuration.desired().peerIds())) {
      return false;
    }
    GroupState before = configuration.before();
    if (before == null) {
      return current.resources().isArray() && current.resources().isEmpty();
    }
    return before.id().equals(current.id())
        && Objects.equals(before.resources(), current.resources());
  }

  private boolean matchesBefore(PlanConfiguration configuration, GroupState current) {
    GroupState before = configuration.before();
    return before == null ? current == null
        : current != null && Objects.equals(fingerprint(before), fingerprint(current));
  }

  private GroupState writeDesired(Desired desired, GroupState current) {
    ObjectNode body = MAPPER.createObjectNode();
    body.put("name", desired.groupName());
    ArrayNode peers = body.putArray("peers");
    desired.peerIds().forEach(peers::add);
    ArrayNode resources = body.putArray("resources");
    if (current != null && current.resources() != null && current.resources().isArray()) {
      current.resources().forEach(resource -> resources.add(resource.deepCopy()));
    }
    JsonNode response;
    if (current == null) {
      response = send("POST", "/api/groups", body);
    } else {
      response = send("PUT", "/api/groups/" + encodePath(current.id()), body);
    }
    GroupState state = parseGroup(response);
    if (!desired.groupName().equals(state.name()) || !desired.peerIds().equals(state.peerIds())) {
      throw new IllegalStateException("NetBird API response did not match the applied group");
    }
    return state;
  }

  private void replaceGroup(GroupState state) {
    ObjectNode body = MAPPER.createObjectNode();
    body.put("name", state.name());
    ArrayNode peers = body.putArray("peers");
    state.peerIds().forEach(peers::add);
    ArrayNode resources = body.putArray("resources");
    if (state.resources() != null && state.resources().isArray()) {
      state.resources().forEach(resource -> resources.add(resource.deepCopy()));
    }
    GroupState restored = parseGroup(send("PUT", "/api/groups/" + encodePath(state.id()), body));
    if (!Objects.equals(fingerprint(restored), fingerprint(state))) {
      throw new IllegalStateException("NetBird API did not restore the previous group state");
    }
  }

  private void deleteGroup(GroupState group) {
    ApiResponse response = sendRaw("DELETE", "/api/groups/" + encodePath(group.id()), null);
    if (response.statusCode() != 204 && response.statusCode() != 200) {
      throw apiFailure(response.statusCode());
    }
    if (readGroup(group.name()) != null) {
      throw new IllegalStateException("NetBird API did not confirm group deletion");
    }
  }

  private GroupState readGroup(String name) {
    String query = "?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8);
    ApiResponse filtered = sendRaw("GET", "/api/groups" + query, null);
    if (filtered.statusCode() == 404) {
      return findGroup(send("GET", "/api/groups", null), name);
    }
    if (filtered.statusCode() < 200 || filtered.statusCode() >= 300) {
      throw apiFailure(filtered.statusCode());
    }
    JsonNode response = parseJson(filtered.body());
    if (!response.isArray() || response.size() > 1) {
      throw new IllegalStateException("NetBird group lookup response is invalid or ambiguous");
    }
    if (response.isEmpty()) {
      return null;
    }
    GroupState state = parseGroup(response.get(0));
    if (!name.equals(state.name()) || !isManagedName(name)) {
      throw new IllegalStateException("NetBird group is outside the managed namespace");
    }
    return state;
  }

  private GroupState findGroup(JsonNode response, String name) {
    if (!response.isArray() || response.size() > MAX_GROUPS) {
      throw new IllegalStateException("NetBird group list response is invalid or too large");
    }
    GroupState match = null;
    for (JsonNode group : response) {
      if (!group.isObject() || !group.path("name").isTextual()) {
        throw new IllegalStateException("NetBird group list response is incomplete");
      }
      if (name.equals(group.path("name").asText())) {
        if (match != null) {
          throw new IllegalStateException("NetBird group lookup response is ambiguous");
        }
        match = parseGroup(group);
      }
    }
    if (match != null && !isManagedName(match.name())) {
      throw new IllegalStateException("NetBird group is outside the managed namespace");
    }
    return match;
  }

  private JsonNode send(String method, String path, JsonNode body) {
    ApiResponse response = sendRaw(method, path,
        body == null ? null : encode(body));
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw apiFailure(response.statusCode());
    }
    return parseJson(response.body());
  }

  private static JsonNode parseJson(String body) {
    try {
      return MAPPER.readTree(body);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("NetBird API returned invalid JSON");
    }
  }

  private ApiResponse sendRaw(String method, String path, String body) {
    requireConfigured();
    HttpRequest.Builder request = HttpRequest.newBuilder(apiRoot.resolve(path))
        .timeout(Duration.ofSeconds(12))
        .header("Accept", "application/json")
        .header("Authorization", authorizationScheme + " " + token);
    if (body == null) {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      request.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    try {
      HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream stream = response.body()) {
        byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
          throw new IllegalStateException("NetBird API response exceeded the size limit");
        }
        return new ApiResponse(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
      }
    } catch (IOException failure) {
      throw new IllegalStateException("NetBird API request failed");
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("NetBird API request was interrupted");
    }
  }

  private static GroupState parseGroup(JsonNode value) {
    if (!value.isObject() || !value.path("id").isTextual() ||
        !value.path("name").isTextual()) {
      throw new IllegalStateException("NetBird group response is incomplete");
    }
    String id = value.path("id").asText();
    String name = value.path("name").asText();
    if (!PEER_ID.matcher(id).matches() || !GROUP_NAME.matcher(name).matches()) {
      throw new IllegalStateException("NetBird group identity is invalid");
    }
    List<String> peers = new ArrayList<>();
    JsonNode peerValues = value.path("peers");
    if (!peerValues.isMissingNode() && !peerValues.isNull() && !peerValues.isArray()) {
      throw new IllegalStateException("NetBird group peers are invalid");
    }
    for (JsonNode peer : peerValues) {
      JsonNode peerId = peer.isTextual() ? peer : peer.get("id");
      if (peerId == null || !peerId.isTextual() || !PEER_ID.matcher(peerId.asText()).matches()) {
        throw new IllegalStateException("NetBird group contains an invalid peer identity");
      }
      peers.add(peerId.asText());
    }
    peers.sort(Comparator.naturalOrder());
    JsonNode resources = value.path("resources");
    if (!resources.isMissingNode() && !resources.isNull() && !resources.isArray()) {
      throw new IllegalStateException("NetBird group resources are invalid");
    }
    return new GroupState(id, name, List.copyOf(peers),
        resources.isMissingNode() || resources.isNull()
        ? MAPPER.createArrayNode() : resources.deepCopy());
  }

  private Desired parseDesired(String json) {
    try {
      JsonNode root = MAPPER.readTree(json);
      if (root == null || !root.isObject() || !root.path("groupName").isTextual()
          || !root.path("generation").canConvertToLong() || !root.path("peerIds").isArray()) {
        throw new IllegalArgumentException("Desired NetBird group snapshot is invalid");
      }
      String name = root.path("groupName").asText();
      if (!isManagedName(name)) {
        throw new IllegalArgumentException("Desired NetBird group name is outside the managed namespace");
      }
      List<String> peers = new ArrayList<>();
      Set<String> unique = new HashSet<>();
      for (JsonNode peer : root.path("peerIds")) {
        if (!peer.isTextual() || !PEER_ID.matcher(peer.asText()).matches()
            || !unique.add(peer.asText())) {
          throw new IllegalArgumentException("Desired NetBird peer IDs must be valid and unique");
        }
        peers.add(peer.asText());
      }
      if (peers.size() > MAX_PEERS || root.path("generation").longValue() < 1) {
        throw new IllegalArgumentException("Desired NetBird group is outside the supported limits");
      }
      peers.sort(Comparator.naturalOrder());
      return new Desired(name, root.path("generation").longValue(), List.copyOf(peers));
    } catch (JsonProcessingException failure) {
      throw new IllegalArgumentException("Desired NetBird group snapshot is invalid");
    }
  }

  private PlanConfiguration parsePlan(ChangePlan plan) {
    if (plan == null || plan.id() == null || plan.generation() < 1 ||
        plan.idempotencyKey() == null || !plan.idempotencyKey().matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("NetBird change plan identity is invalid");
    }
    try {
      PlanConfiguration configuration = MAPPER.readValue(plan.configuration(), PlanConfiguration.class);
      if (configuration.desired() == null || configuration.desired().generation() != plan.generation()
          || !isManagedName(configuration.desired().groupName())) {
        throw new IllegalArgumentException("NetBird change plan configuration is invalid");
      }
      return configuration;
    } catch (IOException failure) {
      throw new IllegalArgumentException("NetBird change plan configuration is invalid");
    }
  }

  private AppliedOperation requireOperation(UUID operationId) {
    AppliedOperation operation = operations.get(operationId);
    if (operation == null) {
      throw new IllegalStateException("NetBird operation is unavailable in this Provider process");
    }
    return operation;
  }

  private void requireObservedPrecondition(String snapshot, String expectedName, GroupState current) {
    if (snapshot == null || snapshot.isBlank()) {
      return;
    }
    try {
      JsonNode observed = MAPPER.readTree(snapshot);
      if (observed == null || !observed.isObject()
          || !observed.path("groupName").isTextual()
          || !observed.path("fingerprint").isTextual()
          || !expectedName.equals(observed.path("groupName").asText())
          || !fingerprint(current).equals(observed.path("fingerprint").asText())) {
        throw new IllegalArgumentException("Observed NetBird snapshot is stale or invalid");
      }
    } catch (JsonProcessingException failure) {
      throw new IllegalArgumentException("Observed NetBird snapshot is invalid");
    }
  }

  /** Stable observed token for callers that need an explicit drift precondition. */
  public CompletionStage<String> observe(String groupName) {
    return CompletableFuture.supplyAsync(() -> {
      requireConfigured();
      if (!isManagedName(groupName)) {
        throw new IllegalArgumentException("NetBird group name is outside the managed namespace");
      }
      GroupState group = readGroup(groupName);
      ObjectNode snapshot = MAPPER.createObjectNode();
      snapshot.put("groupName", groupName);
      snapshot.put("fingerprint", fingerprint(group));
      snapshot.put("exists", group != null);
      if (group != null) {
        snapshot.put("groupId", group.id());
        snapshot.set("peerIds", MAPPER.valueToTree(group.peerIds()));
        snapshot.put("resourceCount", group.resources().size());
      }
      return encode(snapshot);
    });
  }

  private boolean isManagedName(String name) {
    return name != null && name.startsWith(managedPrefix) && GROUP_NAME.matcher(name).matches();
  }

  private void requireConfigured() {
    if (token.isBlank()) {
      throw new IllegalStateException("NetBird Provider API token is not configured");
    }
  }

  private String safeMessage(RuntimeException failure) {
    return failure instanceof IllegalArgumentException
        ? "NetBird request or snapshot validation failed" : "NetBird API observation failed";
  }

  private static IllegalStateException apiFailure(int status) {
    return new IllegalStateException("NetBird API rejected the request with HTTP " + status);
  }

  private static String fingerprint(GroupState state) {
    if (state == null) {
      return "absent";
    }
    return sha256(state.id() + "\n" + state.name() + "\n"
        + String.join("\n", state.peerIds()) + "\n" + state.resources().toString());
  }

  private static URI parseApiRoot(String value) {
    String candidate = value == null || value.isBlank() ? "https://api.netbird.io" : value.trim();
    URI uri = URI.create(candidate);
    boolean localHttp = "http".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
        && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
            || "::1".equals(uri.getHost()));
    if (!("https".equalsIgnoreCase(uri.getScheme()) || localHttp) || uri.getHost() == null
        || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
        || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
      throw new IllegalArgumentException("NetBird API URL must be an HTTPS origin");
    }
    return uri.resolve("/");
  }

  private static String encode(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Unable to encode NetBird request");
    }
  }

  private static String encodePath(String segment) {
    return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable");
    }
  }

  private static HttpClient defaultClient() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
  }

  public record Desired(String groupName, long generation, List<String> peerIds) {
    public Desired {
      peerIds = List.copyOf(peerIds);
    }
  }

  public record GroupState(String id, String name, List<String> peerIds, JsonNode resources) {
    public GroupState {
      peerIds = List.copyOf(peerIds);
      resources = resources == null ? MAPPER.createArrayNode() : resources.deepCopy();
    }
  }

  public record PlanConfiguration(Desired desired, GroupState before) {}

  private record AppliedOperation(PlanConfiguration configuration, GroupState applied,
                                  boolean alreadyCompensated) {}

  private record ApiResponse(int statusCode, String body) {}
}
