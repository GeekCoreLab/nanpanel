package io.nanpanel.provider.frp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nanpanel.plugin.api.NetworkProvider.ChangePlan;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrpRouteProviderTest {
  private static final String ORIGINAL = "serverAddr = \"frps.example.test\"\n"
      + "auth.token = \"private-token-for-fixture\"\n\n"
      + "[[proxies]]\nname = \"customer-web\"\ntype = \"tcp\"\n"
      + "localIP = \"127.0.0.1\"\nlocalPort = 8081\nremotePort = 19081\n"
      + "customOption = \"keep-this-unknown-field\"\n";
  @TempDir Path temporary;

  @Test
  void managedRouteLifecyclePreservesUnmanagedDataAndDoesNotPersistSecrets() throws Exception {
    FixtureControl control = new FixtureControl(ORIGINAL);
    FrpRouteProvider provider = new FrpRouteProvider(control);
    ChangePlan plan = plan(provider, "nanpanel-managed-demo", 19080);

    assertFalse(plan.configuration().contains("private-token-for-fixture"));
    assertTrue(provider.validate(plan).toCompletableFuture().join().successful());
    assertEquals(plan.id(), provider.apply(plan).toCompletableFuture().join());
    assertTrue(control.content.contains("customOption = \"keep-this-unknown-field\""));
    assertTrue(control.content.contains("auth.token = \"private-token-for-fixture\""));
    assertTrue(FrpConfig.routes(control.content).containsKey("nanpanel-managed-demo"));

    FrpRouteProvider restarted = new FrpRouteProvider(control);
    assertTrue(restarted.restoreOperation(plan, plan.id()).toCompletableFuture().join().successful());
    assertTrue(restarted.verify(plan.id()).toCompletableFuture().join().successful());
    assertTrue(restarted.compensate(plan.id()).toCompletableFuture().join().successful());
    assertEquals(ORIGINAL, control.content);
  }

  @Test
  void externalConfigDriftBlocksApplyAndCompensation() throws Exception {
    FixtureControl control = new FixtureControl(ORIGINAL);
    FrpRouteProvider provider = new FrpRouteProvider(control);
    ChangePlan plan = plan(provider, "nanpanel-managed-demo", 19080);
    control.content += "# external edit\n";
    assertFalse(provider.validate(plan).toCompletableFuture().join().successful());
    assertThrows(CompletionException.class, () -> provider.apply(plan).toCompletableFuture().join());

    control.content = ORIGINAL;
    provider.apply(plan).toCompletableFuture().join();
    control.content += "# drift after apply\n";
    assertFalse(provider.compensate(plan.id()).toCompletableFuture().join().successful());
    assertTrue(control.content.endsWith("# drift after apply\n"));
  }

  @Test
  void observedSnapshotAndPortConflictsAreEnforced() throws Exception {
    FixtureControl control = new FixtureControl(ORIGINAL);
    FrpRouteProvider provider = new FrpRouteProvider(control);
    String observed = provider.observe("nanpanel-managed-demo").toCompletableFuture().join();
    ChangePlan fromObservation = provider.plan(desired("nanpanel-managed-demo", 19082), observed)
        .toCompletableFuture().join();
    assertTrue(provider.validate(fromObservation).toCompletableFuture().join().successful());
    assertThrows(CompletionException.class,
        () -> provider.plan(desired("nanpanel-managed-demo", 19081), null).toCompletableFuture().join());
    assertThrows(CompletionException.class,
        () -> provider.observe("customer-web").toCompletableFuture().join());
  }

  @Test
  void translatorCarriesUnknownFieldsAndOnlyEditsManagedRoutes() throws Exception {
    FrpConfigTranslator translator = new FrpConfigTranslator();
    var normalized = translator.normalize(new io.nanpanel.plugin.api.ConfigTranslator.TranslationRequest(
        "frpc.toml", ORIGINAL, FrpConfigTranslator.NORMALIZED_FORMAT)).toCompletableFuture().join();
    var document = new ObjectMapper().readTree(normalized.snapshot());
    assertEquals(ORIGINAL, document.path("sourceSnapshot").asText());
    assertTrue(normalized.preservedFields().contains("customOption"));
    assertTrue(normalized.preservedFields().contains("auth.token"));

    var routes = (com.fasterxml.jackson.databind.node.ArrayNode)document.path("routes");
    var managed = routes.addObject();
    managed.put("name", "nanpanel-managed-new");
    managed.put("type", "tcp");
    managed.put("localIP", "127.0.0.1");
    managed.put("localPort", 8080);
    managed.put("remotePort", 19080);
    var rendered = translator.render(new io.nanpanel.plugin.api.ConfigTranslator.TranslationRequest(
        FrpConfigTranslator.NORMALIZED_FORMAT, new ObjectMapper().writeValueAsString(document), "frpc.toml"))
        .toCompletableFuture().join();
    assertTrue(rendered.snapshot().contains("auth.token = \"private-token-for-fixture\""));
    assertTrue(rendered.snapshot().contains("customOption = \"keep-this-unknown-field\""));
    assertTrue(FrpConfig.routes(rendered.snapshot()).containsKey("nanpanel-managed-new"));
    assertEquals(19081, FrpConfig.routes(rendered.snapshot()).get("customer-web").remotePort());
  }

  @Test
  void processControlAtomicallyWritesReloadsProbesAndCompensates() throws Exception {
    Path config = temporary.resolve("frpc.toml");
    Files.writeString(config, ORIGINAL);
    Path frpc = temporary.resolve("frpc-test-double");
    Files.writeString(frpc, "#!/bin/sh\ncase \"$1\" in\n  verify) test -f \"$3\" ;;\n  reload|status) exit 0 ;;\n  *) exit 2 ;;\nesac\n");
    Files.setPosixFilePermissions(frpc, Set.of(PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
    String expectedBody = "nanpanel-frp-provider-probe";
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/probe", exchange -> {
      byte[] bytes = expectedBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) { output.write(bytes); }
    });
    server.start();
    try {
      var control = new FrpRouteProvider.ProcessFrpControl(config.toString(), frpc.toString(), "true",
          "http://127.0.0.1:" + server.getAddress().getPort() + "/probe", expectedBody);
      FrpRouteProvider provider = new FrpRouteProvider(control);
      ChangePlan plan = plan(provider, "nanpanel-managed-process", 19083);
      assertTrue(provider.validate(plan).toCompletableFuture().join().successful());
      provider.apply(plan).toCompletableFuture().join();
      assertTrue(provider.verify(plan.id()).toCompletableFuture().join().successful());
      FrpRouteProvider restarted = new FrpRouteProvider(control);
      assertTrue(restarted.restoreOperation(plan, plan.id()).toCompletableFuture().join().successful());
      assertTrue(restarted.compensate(plan.id()).toCompletableFuture().join().successful());
      assertEquals(ORIGINAL, Files.readString(config));
    } finally {
      server.stop(0);
    }
  }

  private static ChangePlan plan(FrpRouteProvider provider, String name, int remotePort) {
    return provider.plan(desired(name, remotePort), null).toCompletableFuture().join();
  }

  private static String desired(String name, int remotePort) {
    return "{\"proxyName\":\"" + name + "\",\"localIP\":\"127.0.0.1\","
        + "\"localPort\":8080,\"remotePort\":" + remotePort + ",\"generation\":1}";
  }

  private static final class FixtureControl implements FrpRouteProvider.FrpControl {
    private String content;
    private FixtureControl(String content) { this.content = content; }
    @Override public String readConfig() { return content; }
    @Override public void verify(String candidate) { FrpConfig.routes(candidate); }
    @Override public void writeConfig(String candidate, String expectedHash) {
      assertEquals(expectedHash, FrpConfig.hash(content));
      content = candidate;
    }
    @Override public void reload() {}
    @Override public boolean status() { return true; }
    @Override public boolean probe() { return true; }
    @Override public boolean reloadEnabled() { return true; }
    @Override public boolean probeConfigured() { return true; }
    @Override public boolean configured() { return true; }
  }
}
