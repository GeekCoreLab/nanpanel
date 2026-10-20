package io.nanpanel.provider.frp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nanpanel.plugin.api.NetworkProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.pf4j.Extension;

/** Manages only nanpanel-managed-* TCP proxy blocks in one explicitly configured TOML file. */
@Extension
public final class FrpRouteProvider implements NetworkProvider {
  private static final int MAX_CONFIG_BYTES = 1_048_576;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Set<String> LAYERS = Set.of("L4");
  private static final Set<String> PROTOCOLS = Set.of("TCP");

  private final FrpControl control;
  private final ConcurrentHashMap<UUID, AppliedOperation> operations = new ConcurrentHashMap<>();

  public FrpRouteProvider() {
    this(new ProcessFrpControl(System.getenv("NANPANEL_FRPC_CONFIG"),
        System.getenv("NANPANEL_FRPC_BINARY"), System.getenv("NANPANEL_FRPC_RELOAD"),
        System.getenv("NANPANEL_FRP_PROBE_URL"), System.getenv("NANPANEL_FRP_PROBE_BODY")));
  }

  FrpRouteProvider(FrpControl control) {
    this.control = Objects.requireNonNull(control, "control");
  }

  @Override
  public String id() {
    return "frp-routes";
  }

  @Override
  public NetworkProvider.ProviderDescriptor descriptor() {
    return new ProviderDescriptor(id(), "FRP TCP Routes", "0.1.0", "panel.io/plugins/v1");
  }

  @Override
  public Set<String> capabilities() {
    return Set.of(control.configured() && control.reloadEnabled() && control.probeConfigured()
        ? "frp.proxy.tcp.reconcile" : "frp.proxy.tcp.unconfigured",
        control.configured() && control.reloadEnabled() && control.probeConfigured()
        ? "frp.reload.hot" : "frp.reload.restart-required");
  }

  @Override
  public Set<NetworkProvider.Capability> capabilityDescriptors() {
    Set<String> operations = control.configured() && control.reloadEnabled() && control.probeConfigured()
        ? Set.of("OBSERVE", "PLAN", "VALIDATE", "HOT_RELOAD", "VERIFY", "COMPENSATE")
        : Set.of("UNCONFIGURED", "UNSUPPORTED");
    return Set.of(new Capability("frp.proxy.tcp.reconcile", operations, LAYERS, PROTOCOLS));
  }

  @Override
  public CompletionStage<String> observe(String resourceKey) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        requireManagedName(resourceKey);
        String source = control.readConfig();
        FrpConfig.Route route = FrpConfig.routes(source).get(resourceKey);
        boolean processResponding = control.status();
        return writeJson(new Observation(resourceKey, route, FrpConfig.hash(source), processResponding));
      } catch (Exception failure) {
        throw new IllegalStateException("FRP route observation failed", failure);
      }
    });
  }

  @Override
  public CompletionStage<ChangePlan> plan(String desiredSnapshot, String observedSnapshot) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        Desired desired = readJson(desiredSnapshot, Desired.class);
        FrpConfig.Route route = FrpConfig.desired(desired.proxyName(), desired.localIP(),
            desired.localPort(), desired.remotePort());
        if (desired.generation() < 1 || desired.generation() > 1_000_000_000_000L) {
          throw new IllegalArgumentException("generation is out of range");
        }
        String source = control.readConfig();
        control.verify(source);
        String beforeHash = FrpConfig.hash(source);
        requireObservedPrecondition(observedSnapshot, desired.proxyName(), beforeHash);
        FrpConfig.Route before = FrpConfig.routes(source).get(desired.proxyName());
        if (before != null && (!"tcp".equals(before.type()) || before.localIp() == null
            || before.localPort() < 1 || before.remotePort() < 1)) {
          throw new IllegalArgumentException("managed FRP proxy is not TCP");
        }
        String after = FrpConfig.patch(source, route.name(), route);
        if (after.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
          throw new IllegalArgumentException("resulting FRP config exceeds 1 MiB");
        }
        String afterHash = FrpConfig.hash(after);
        PlanConfiguration configuration = new PlanConfiguration(route, before, beforeHash, afterHash);
        String key = FrpConfig.hash(route.name() + "\n" + desired.generation() + "\n"
            + route.localIp() + "\n" + route.localPort() + "\n" + route.remotePort()
            + "\n" + beforeHash);
        return new ChangePlan(UUID.randomUUID(), desired.generation(), key, writeJson(configuration));
      } catch (Exception failure) {
        throw new IllegalStateException("FRP route plan was rejected", failure);
      }
    });
  }

  @Override
  public CompletionStage<ValidationResult> validate(ChangePlan plan) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        requireApplyReady();
        PlanConfiguration configuration = parsePlan(plan);
        String source = control.readConfig();
        if (!FrpConfig.hash(source).equals(configuration.beforeHash())) {
          return new ValidationResult(false, "FRP configuration drifted after planning");
        }
        String candidate = FrpConfig.patch(source, configuration.desired().name(), configuration.desired());
        if (!FrpConfig.hash(candidate).equals(configuration.afterHash())) {
          return new ValidationResult(false, "FRP planned output no longer matches");
        }
        control.verify(candidate);
        return new ValidationResult(true, "FRP configuration precondition and syntax are valid");
      } catch (Exception failure) {
        return new ValidationResult(false, "FRP configuration could not be validated safely");
      }
    });
  }

  @Override
  public CompletionStage<UUID> apply(ChangePlan plan) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        requireApplyReady();
        PlanConfiguration configuration = parsePlan(plan);
        String source = control.readConfig();
        if (!FrpConfig.hash(source).equals(configuration.beforeHash())) {
          throw new IllegalStateException("FRP configuration drifted after planning");
        }
        String candidate = FrpConfig.patch(source, configuration.desired().name(), configuration.desired());
        if (!FrpConfig.hash(candidate).equals(configuration.afterHash())) {
          throw new IllegalStateException("FRP candidate differs from the reviewed plan");
        }
        control.verify(candidate);
        operations.put(plan.id(), new AppliedOperation(configuration, false));
        control.writeConfig(candidate, configuration.beforeHash());
        if (!FrpConfig.hash(control.readConfig()).equals(configuration.afterHash())) {
          throw new IllegalStateException("FRP configuration changed during apply");
        }
        control.reload();
        return plan.id();
      } catch (Exception failure) {
        throw new IllegalStateException("FRP route apply did not complete", failure);
      }
    });
  }

  @Override
  public CompletionStage<ValidationResult> verify(UUID operationId) {
    return CompletableFuture.supplyAsync(() -> {
      AppliedOperation operation = operations.get(operationId);
      if (operation == null || operation.compensated()) {
        return new ValidationResult(false, "FRP operation state is unavailable");
      }
      try {
        PlanConfiguration configuration = operation.configuration();
        if (!FrpConfig.hash(control.readConfig()).equals(configuration.afterHash())) {
          return new ValidationResult(false, "FRP configuration drifted after apply");
        }
        if (!control.status()) return new ValidationResult(false, "FRP client status is unavailable");
        if (!control.probe()) return new ValidationResult(false, "FRP end-to-end route probe failed");
        return new ValidationResult(true, "FRP managed route and HTTP probe are verified");
      } catch (Exception failure) {
        return new ValidationResult(false, "FRP route could not be verified");
      }
    });
  }

  @Override
  public CompletionStage<ValidationResult> compensate(UUID operationId) {
    return CompletableFuture.supplyAsync(() -> {
      AppliedOperation operation = operations.get(operationId);
      if (operation == null) return new ValidationResult(false, "FRP operation state is unavailable");
      if (operation.compensated()) return new ValidationResult(true, "FRP compensation is already complete");
      try {
        requireApplyReady();
        PlanConfiguration configuration = operation.configuration();
        String source = control.readConfig();
        if (!FrpConfig.hash(source).equals(configuration.afterHash())) {
          return new ValidationResult(false, "FRP configuration drifted; compensation was refused");
        }
        String restored = FrpConfig.patch(source, configuration.desired().name(), configuration.before());
        if (!FrpConfig.hash(restored).equals(configuration.beforeHash())) {
          return new ValidationResult(false, "FRP preserved configuration changed; compensation was refused");
        }
        control.verify(restored);
        control.writeConfig(restored, configuration.afterHash());
        if (!FrpConfig.hash(control.readConfig()).equals(configuration.beforeHash())) {
          return new ValidationResult(false, "FRP configuration changed during compensation");
        }
        control.reload();
        operations.put(operationId, new AppliedOperation(configuration, true));
        return new ValidationResult(true, "FRP managed route was compensated; unmanaged data was preserved");
      } catch (Exception failure) {
        return new ValidationResult(false, "FRP compensation could not complete safely");
      }
    });
  }

  @Override
  public CompletionStage<ValidationResult> restoreOperation(ChangePlan plan, UUID operationId) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        if (plan == null || !Objects.equals(plan.id(), operationId)) {
          return new ValidationResult(false, "FRP operation identity does not match its plan");
        }
        PlanConfiguration configuration = parsePlan(plan);
        String currentHash = FrpConfig.hash(control.readConfig());
        if (currentHash.equals(configuration.afterHash())) {
          operations.put(operationId, new AppliedOperation(configuration, false));
          return new ValidationResult(true, "FRP operation restored from persisted hashes");
        }
        if (currentHash.equals(configuration.beforeHash())) {
          operations.put(operationId, new AppliedOperation(configuration, true));
          return new ValidationResult(true, "FRP compensation is already reflected in the configuration");
        }
        return new ValidationResult(false, "FRP configuration drift prevents safe operation restoration");
      } catch (Exception failure) {
        return new ValidationResult(false, "FRP operation could not be restored safely");
      }
    });
  }

  private void requireApplyReady() {
    if (!control.configured()) {
      throw new IllegalStateException("FRP configuration is unavailable");
    }
    if (!control.reloadEnabled()) {
      throw new IllegalStateException("FRP reload is unavailable; restart is required");
    }
    if (!control.probeConfigured()) {
      throw new IllegalStateException("FRP end-to-end probe is not configured");
    }
  }

  private static PlanConfiguration parsePlan(ChangePlan plan) {
    if (plan == null || plan.id() == null || plan.generation() < 1
        || plan.idempotencyKey() == null || plan.configuration() == null
        || plan.configuration().length() > 65536) {
      throw new IllegalArgumentException("invalid persisted FRP plan");
    }
    PlanConfiguration configuration = readJson(plan.configuration(), PlanConfiguration.class);
    if (configuration.desired() == null || configuration.beforeHash() == null
        || configuration.afterHash() == null
        || !configuration.beforeHash().matches("[a-f0-9]{64}")
        || !configuration.afterHash().matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("invalid FRP plan configuration");
    }
    FrpConfig.desired(configuration.desired().name(), configuration.desired().localIp(),
        configuration.desired().localPort(), configuration.desired().remotePort());
    return configuration;
  }

  private static void requireManagedName(String name) {
    if (name == null || !name.matches("nanpanel-managed-[a-z0-9][a-z0-9-]{0,50}")) {
      throw new IllegalArgumentException("resource name is outside the managed prefix");
    }
  }

  private static void requireObservedPrecondition(String snapshot, String name, String currentHash) {
    if (snapshot == null) return;
    Observation observation = readJson(snapshot, Observation.class);
    if (!Objects.equals(observation.resourceKey(), name)
        || !Objects.equals(observation.configHash(), currentHash)) {
      throw new IllegalArgumentException("observed FRP snapshot is stale or belongs to another route");
    }
  }

  private static String writeJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (IOException failure) {
      throw new IllegalStateException("could not encode FRP operation", failure);
    }
  }

  private static <T> T readJson(String value, Class<T> type) {
    try {
      if (value == null || value.length() > MAX_CONFIG_BYTES) throw new IllegalArgumentException("invalid JSON input");
      return MAPPER.readValue(value, type);
    } catch (IOException failure) {
      throw new IllegalArgumentException("invalid JSON input", failure);
    }
  }

  record Desired(String proxyName, String localIP, int localPort, int remotePort, long generation) {}
  record Observation(String resourceKey, FrpConfig.Route route, String configHash, boolean processResponding) {}
  record PlanConfiguration(FrpConfig.Route desired, FrpConfig.Route before,
                           String beforeHash, String afterHash) {}
  record AppliedOperation(PlanConfiguration configuration, boolean compensated) {}

  interface FrpControl {
    String readConfig() throws Exception;
    void verify(String candidate) throws Exception;
    void writeConfig(String content, String expectedCurrentHash) throws Exception;
    void reload() throws Exception;
    boolean status() throws Exception;
    boolean probe() throws Exception;
    boolean reloadEnabled();
    boolean probeConfigured();
    boolean configured();
  }

  static final class ProcessFrpControl implements FrpControl {
    private final Path configPath;
    private final String binary;
    private final boolean reloadEnabled;
    private final URI probeUri;
    private final String expectedBody;
    private final String configurationError;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    ProcessFrpControl(String configPath, String binary, String reload, String probeUrl, String expectedBody) {
      Path parsedConfig = null;
      URI parsedProbe = null;
      String error = null;
      try {
        if (configPath == null || configPath.isBlank()) {
          error = "NANPANEL_FRPC_CONFIG is not configured";
        } else {
          parsedConfig = Path.of(configPath).toAbsolutePath().normalize();
          if (!Path.of(configPath).isAbsolute()) error = "FRP config path must be absolute";
          else if (!parsedConfig.toString().endsWith(".toml")) error = "FRP config must be TOML";
        }
        if (probeUrl != null && !probeUrl.isBlank()) parsedProbe = parseProbe(probeUrl);
      } catch (RuntimeException failure) {
        error = "FRP Provider configuration is invalid";
        parsedConfig = null;
        parsedProbe = null;
      }
      this.configPath = parsedConfig;
      this.binary = binary == null || binary.isBlank() ? "frpc" : binary.trim();
      this.reloadEnabled = "true".equalsIgnoreCase(reload);
      this.probeUri = parsedProbe;
      this.expectedBody = expectedBody;
      this.configurationError = error;
    }

    @Override
    public String readConfig() throws Exception {
      requireConfigPath();
      if (Files.isSymbolicLink(configPath)
          || !Files.isRegularFile(configPath, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("FRP config must be a regular, non-symlink file");
      }
      long size = Files.size(configPath);
      if (size > MAX_CONFIG_BYTES) throw new IOException("FRP config exceeds 1 MiB");
      byte[] bytes;
      try (var stream = Files.newInputStream(configPath)) {
        bytes = stream.readNBytes(MAX_CONFIG_BYTES + 1);
      }
      if (bytes.length > MAX_CONFIG_BYTES) throw new IOException("FRP config exceeds 1 MiB");
      try {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
      } catch (CharacterCodingException failure) {
        throw new IOException("FRP config is not valid UTF-8", failure);
      }
    }

    @Override
    public void verify(String candidate) throws Exception {
      requireConfigPath();
      Path temporary = Files.createTempFile(configPath.getParent(), ".nanpanel-frpc-", ".toml");
      try {
        Files.writeString(temporary, candidate, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        run(binary, "verify", "-c", temporary.toString());
      } finally {
        Files.deleteIfExists(temporary);
      }
    }

    @Override
    public synchronized void writeConfig(String content, String expectedCurrentHash) throws Exception {
      requireConfigPath();
      if (!FrpConfig.hash(readConfig()).equals(expectedCurrentHash)) {
        throw new IOException("FRP config changed before atomic replacement");
      }
      Principal owner = Files.getOwner(configPath, LinkOption.NOFOLLOW_LINKS);
      String username = System.getProperty("user.name", "");
      if (!owner.getName().equals(username) && !owner.getName().endsWith("\\" + username)) {
        throw new IOException("FRP config must be owned by the Core process user");
      }
      Path temporary = Files.createTempFile(configPath.getParent(), ".nanpanel-frpc-", ".toml");
      try {
        copyPermissions(configPath, temporary);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING)) {
          ByteBuffer buffer = StandardCharsets.UTF_8.encode(content);
          while (buffer.hasRemaining()) channel.write(buffer);
          channel.force(true);
        }
        copyPermissions(configPath, temporary);
        if (Files.isSymbolicLink(configPath)
            || !FrpConfig.hash(readConfig()).equals(expectedCurrentHash)) {
          throw new IOException("FRP config changed before atomic replacement");
        }
        try {
          Files.move(temporary, configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException failure) {
          throw new IOException("FRP config filesystem does not support atomic replacement", failure);
        }
      } finally {
        Files.deleteIfExists(temporary);
      }
    }

    @Override
    public void reload() throws Exception {
      requireConfigPath();
      if (!reloadEnabled) throw new IOException("FRP hot reload is not enabled");
      run(binary, "reload", "-c", configPath.toString());
    }

    @Override
    public boolean status() throws Exception {
      requireConfigPath();
      run(binary, "status", "-c", configPath.toString());
      return true;
    }

    @Override
    public boolean probe() throws Exception {
      if (!probeConfigured()) return false;
      HttpRequest request = HttpRequest.newBuilder(probeUri).timeout(Duration.ofSeconds(5)).GET().build();
      HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (java.io.InputStream body = response.body()) {
        byte[] bytes = body.readNBytes(8193);
        return response.statusCode() == 200 && bytes.length <= 8192
            && expectedBody.equals(new String(bytes, StandardCharsets.UTF_8));
      }
    }

    @Override
    public boolean reloadEnabled() {
      return reloadEnabled;
    }

    @Override
    public boolean probeConfigured() {
      return probeUri != null && expectedBody != null && expectedBody.length() <= 8192;
    }

    @Override
    public boolean configured() {
      return configPath != null && configurationError == null;
    }

    private void requireConfigPath() throws IOException {
      if (!configured()) throw new IOException(configurationError == null
          ? "NANPANEL_FRPC_CONFIG is not configured" : configurationError);
    }

    private static URI parseProbe(String value) {
      if (value == null || value.isBlank()) return null;
      URI uri = URI.create(value);
      if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
          || uri.getUserInfo() != null || uri.getFragment() != null) {
        throw new IllegalArgumentException("FRP probe URL must be an HTTP(S) URL without credentials");
      }
      return uri;
    }

    private static void run(String... command) throws Exception {
      Process process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .redirectError(ProcessBuilder.Redirect.DISCARD).start();
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException("FRP command timed out");
      }
      if (process.exitValue() != 0) throw new IOException("FRP command failed");
    }

    private static void copyPermissions(Path source, Path destination) throws IOException {
      try {
        var attributes = Files.readAttributes(source,
            java.nio.file.attribute.PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Files.setAttribute(destination, "posix:group", attributes.group(), LinkOption.NOFOLLOW_LINKS);
        Files.setPosixFilePermissions(destination, attributes.permissions());
      } catch (UnsupportedOperationException ignored) {
        // The temporary file keeps the platform's restrictive createTempFile permissions.
      }
    }
  }
}
