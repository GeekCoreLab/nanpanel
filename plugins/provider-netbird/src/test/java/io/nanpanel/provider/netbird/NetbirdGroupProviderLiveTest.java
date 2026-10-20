package io.nanpanel.provider.netbird;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nanpanel.plugin.api.NetworkProvider.ChangePlan;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class NetbirdGroupProviderLiveTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @EnabledIfEnvironmentVariable(named = "NANPANEL_P2_NETBIRD_LIVE_TEST", matches = "true")
  void reconcilesAndCompensatesAGroupOnTheDisposableTenant() throws Exception {
    String groupName = System.getenv("NANPANEL_P2_NETBIRD_LIVE_GROUP_NAME");
    assertTrue(groupName != null && groupName.matches("nanpanel-managed-p2-[a-z0-9-]{8,40}"),
        "live test must use a run-scoped group under its dedicated prefix");
    String peerId = System.getenv("NANPANEL_P2_NETBIRD_LIVE_PEER_ID");
    assertTrue(peerId != null && peerId.matches("[A-Za-z0-9_-]{8,64}"),
        "live test must use an existing peer from its disposable tenant");

    NetbirdGroupProvider provider = new NetbirdGroupProvider();
    NetbirdGroupProvider activeProvider = provider;
    UUID operationId = null;
    try {
      String before = provider.observe(groupName).toCompletableFuture().join();
      assertFalse(MAPPER.readTree(before).path("exists").asBoolean(),
          "run-scoped managed group must be absent before the test");

      var desired = MAPPER.createObjectNode();
      desired.put("groupName", groupName);
      desired.put("generation", 1);
      desired.putArray("peerIds").add(peerId);
      ChangePlan plan = provider.plan(desired.toString(), before).toCompletableFuture().join();
      var validation = provider.validate(plan).toCompletableFuture().join();
      assertTrue(validation.successful(), validation.detail());

      operationId = provider.apply(plan).toCompletableFuture().join();
      assertTrue(provider.verify(operationId).toCompletableFuture().join().successful());

      NetbirdGroupProvider restarted = new NetbirdGroupProvider();
      var restored = restarted.restoreOperation(plan, operationId).toCompletableFuture().join();
      assertTrue(restored.successful(), restored.detail());
      assertTrue(restarted.verify(operationId).toCompletableFuture().join().successful());
      var observedApplied = MAPPER.readTree(
          restarted.observe(groupName).toCompletableFuture().join()).path("peerIds");
      assertEquals(1, observedApplied.size());
      assertEquals(peerId, observedApplied.get(0).asText());
      activeProvider = restarted;

      var compensation = restarted.compensate(operationId).toCompletableFuture().join();
      assertTrue(compensation.successful(), compensation.detail());
      operationId = null;
      assertFalse(MAPPER.readTree(restarted.observe(groupName).toCompletableFuture().join())
          .path("exists").asBoolean(), "compensation must remove the run-scoped group");
    } finally {
      if (operationId != null) {
        activeProvider.compensate(operationId).toCompletableFuture().join();
      }
    }
  }
}
