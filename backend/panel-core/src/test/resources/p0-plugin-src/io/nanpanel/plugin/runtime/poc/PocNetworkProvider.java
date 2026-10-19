package io.nanpanel.plugin.runtime.poc;

import io.nanpanel.plugin.api.NetworkProvider;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.pf4j.Extension;

@Extension
public final class PocNetworkProvider implements NetworkProvider {
  @Override public String id() { return "p0-lifecycle-provider"; }
  @Override public Set<String> capabilities() { return Set.of("p0.lifecycle"); }
  @Override public CompletionStage<ChangePlan> plan(String desired, String observed) {
    return CompletableFuture.completedFuture(new ChangePlan(UUID.randomUUID(), 1, "p0", desired));
  }
  @Override public CompletionStage<ValidationResult> validate(ChangePlan plan) {
    return CompletableFuture.completedFuture(new ValidationResult(true, "valid"));
  }
  @Override public CompletionStage<UUID> apply(ChangePlan plan) {
    return CompletableFuture.completedFuture(plan.id());
  }
  @Override public CompletionStage<ValidationResult> verify(UUID operationId) {
    return CompletableFuture.completedFuture(new ValidationResult(true, operationId.toString()));
  }
  @Override public CompletionStage<ValidationResult> compensate(UUID operationId) {
    return CompletableFuture.completedFuture(new ValidationResult(true, operationId.toString()));
  }
}
