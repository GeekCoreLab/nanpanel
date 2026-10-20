package io.nanpanel.core.p3;

import io.nanpanel.plugin.api.NetworkProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.pf4j.Extension;

@Extension
public final class P3FixtureNetworkProvider implements NetworkProvider {
  @Override
  public String id() {
    return "p3-fixture-provider";
  }

  @Override
  public Set<String> capabilities() {
    return Set.of("p3.fixture.tcp");
  }

  @Override
  public Set<Capability> capabilityDescriptors() {
    return Set.of(new Capability("p3.fixture.tcp",
        Set.of("OBSERVE", "PLAN", "VALIDATE", "APPLY", "VERIFY", "COMPENSATE"),
        Set.of("L4"), Set.of("TCP")));
  }

  @Override
  public CompletionStage<String> observe(String resourceKey) {
    return CompletableFuture.completedFuture("observed:" + resourceKey);
  }

  @Override
  public CompletionStage<ChangePlan> plan(String desiredSnapshot, String observedSnapshot) {
    try {
      String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
          (desiredSnapshot + "\n" + String.valueOf(observedSnapshot))
              .getBytes(StandardCharsets.UTF_8)));
      return CompletableFuture.completedFuture(new ChangePlan(
          UUID.randomUUID(), 1, key, desiredSnapshot));
    } catch (java.security.NoSuchAlgorithmException failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  @Override
  public CompletionStage<ValidationResult> validate(ChangePlan plan) {
    return success("fixture validated");
  }

  @Override
  public CompletionStage<UUID> apply(ChangePlan plan) {
    return CompletableFuture.completedFuture(plan.id());
  }

  @Override
  public CompletionStage<ValidationResult> verify(UUID operationId) {
    return success("fixture verified");
  }

  @Override
  public CompletionStage<ValidationResult> compensate(UUID operationId) {
    return success("fixture compensated");
  }

  @Override
  public CompletionStage<ValidationResult> restoreOperation(ChangePlan plan, UUID operationId) {
    return success("fixture operation restored");
  }

  private static CompletionStage<ValidationResult> success(String detail) {
    return CompletableFuture.completedFuture(new ValidationResult(true, detail));
  }
}
