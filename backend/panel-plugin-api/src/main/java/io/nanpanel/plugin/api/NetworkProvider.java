package io.nanpanel.plugin.api;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.pf4j.ExtensionPoint;
public interface NetworkProvider extends ExtensionPoint {
    String id();
    Set<String> capabilities();
    CompletionStage<ChangePlan> plan(String desiredSnapshot, String observedSnapshot);
    CompletionStage<ValidationResult> validate(ChangePlan plan);
    CompletionStage<UUID> apply(ChangePlan plan);
    CompletionStage<ValidationResult> verify(UUID operationId);
    CompletionStage<ValidationResult> compensate(UUID operationId);
    record ChangePlan(UUID id, long generation, String idempotencyKey, String configuration) {}
    record ValidationResult(boolean successful, String detail) {}
}
