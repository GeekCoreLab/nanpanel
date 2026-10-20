package io.nanpanel.plugin.api;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.pf4j.ExtensionPoint;
public interface NetworkProvider extends ExtensionPoint {
    String id();
    Set<String> capabilities();
    default ProviderDescriptor descriptor() {
        return new ProviderDescriptor(id(), id(), "0.1.0", "panel.io/plugins/v1");
    }
    default Set<Capability> capabilityDescriptors() {
        return capabilities().stream().map(value -> new Capability(value, Set.of(), Set.of(), Set.of()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    default CompletionStage<String> observe(String resourceKey) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Provider observation is unsupported"));
    }
    CompletionStage<ChangePlan> plan(String desiredSnapshot, String observedSnapshot);
    CompletionStage<ValidationResult> validate(ChangePlan plan);
    CompletionStage<UUID> apply(ChangePlan plan);
    CompletionStage<ValidationResult> verify(UUID operationId);
    CompletionStage<ValidationResult> compensate(UUID operationId);
    /** Rebuilds ephemeral provider state from the Core-persisted plan after a restart. */
    default CompletionStage<ValidationResult> restoreOperation(ChangePlan plan, UUID operationId) {
        return CompletableFuture.completedFuture(
                new ValidationResult(false, "Provider operation restoration is unsupported"));
    }
    record ChangePlan(UUID id, long generation, String idempotencyKey, String configuration) {}
    record ValidationResult(boolean successful, String detail) {}
    record ProviderDescriptor(String id, String name, String version, String apiVersion) {}
    record Capability(String id, Set<String> operations, Set<String> layers, Set<String> protocols) {
        public Capability {
            operations = Set.copyOf(operations);
            layers = Set.copyOf(layers);
            protocols = Set.copyOf(protocols);
        }
    }
}
