package io.nanpanel.provider.frp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nanpanel.plugin.api.NetworkProvider.ChangePlan;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Runs against a real FRPC process only from tests/p3/frp-provider-live.sh. */
@EnabledIfEnvironmentVariable(named = "NANPANEL_FRP_LIVE_TEST", matches = "1")
class FrpRouteProviderLiveTest {
  @Test
  void realFrpcAppliesVerifiesAndCompensatesManagedRoute() throws Exception {
    Path configPath = Path.of(System.getenv("NANPANEL_FRPC_CONFIG"));
    String original = Files.readString(configPath);
    FrpRouteProvider provider = new FrpRouteProvider(new FrpRouteProvider.ProcessFrpControl(
        configPath.toString(), System.getenv("NANPANEL_FRPC_BINARY"),
        System.getenv("NANPANEL_FRPC_RELOAD"), System.getenv("NANPANEL_FRP_PROBE_URL"),
        System.getenv("NANPANEL_FRP_PROBE_BODY")));

    ChangePlan plan = provider.plan(
        "{\"proxyName\":\"nanpanel-managed-live\",\"localIP\":\"http-service\","
            + "\"localPort\":8080,\"remotePort\":18082,\"generation\":1}", null)
        .toCompletableFuture().join();
    assertFalse(plan.configuration().contains("nanpanel-p3-frp-fixture-token"));
    assertTrue(provider.validate(plan).toCompletableFuture().join().successful());
    assertEquals(plan.id(), provider.apply(plan).toCompletableFuture().join());
    assertTrue(provider.verify(plan.id()).toCompletableFuture().join().successful());

    FrpRouteProvider restarted = new FrpRouteProvider(new FrpRouteProvider.ProcessFrpControl(
        configPath.toString(), System.getenv("NANPANEL_FRPC_BINARY"),
        System.getenv("NANPANEL_FRPC_RELOAD"), System.getenv("NANPANEL_FRP_PROBE_URL"),
        System.getenv("NANPANEL_FRP_PROBE_BODY")));
    assertTrue(restarted.restoreOperation(plan, plan.id()).toCompletableFuture().join().successful());
    assertTrue(restarted.compensate(plan.id()).toCompletableFuture().join().successful());
    assertEquals(original, Files.readString(configPath));
  }
}
