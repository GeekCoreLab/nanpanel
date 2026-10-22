package io.nanpanel.plugin.runtime.poc;

import io.nanpanel.plugin.api.ClusterProvider;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.pf4j.Extension;

@Extension
public final class PocClusterProvider implements ClusterProvider {
    @Override
    public String id() {
        return "p0-lifecycle-cluster-provider";
    }

    @Override
    public Set<String> clusterKinds() {
        return Set.of("KUBEEDGE");
    }

    @Override
    public Set<String> operations() {
        return Set.of("REGISTER_EDGE");
    }

    @Override
    public CompletionStage<ClusterObservation> observe(ClusterReference cluster, ClusterAccess access) {
        return access.snapshot().thenApply(snapshot -> new ClusterObservation(
                cluster.id(), cluster.generation(), snapshot.healthStatus(), "fixture-fingerprint",
                List.of("nodes=" + snapshot.nodes().size(), "cloudcore=" + snapshot.cloudCoreStatus())));
    }

    @Override
    public CompletionStage<ClusterPlan> plan(
            ClusterReference cluster, LifecycleRequest request, ClusterObservation observed) {
        var step = new PlanStep(0, ExecutionTarget.AGENT_HELPER, request.hostId(), "kubeedge.join",
                Map.of("component", request.component()),
                request.credentialReference() == null ? List.of() : List.of(request.credentialReference()));
        return CompletableFuture.completedFuture(new ClusterPlan(UUID.randomUUID(), cluster.id(),
                cluster.generation(), request.operation(), "fixture-idempotency-key", List.of(step)));
    }

    @Override
    public CompletionStage<ValidationResult> validate(
            ClusterReference cluster, ClusterPlan plan, ClusterAccess access) {
        return access.snapshot().thenApply(snapshot -> new ValidationResult(
                snapshot.clusterId().equals(cluster.id()) && snapshot.generation() == plan.generation(),
                "VALID", "fixture plan valid"));
    }

    @Override
    public CompletionStage<ValidationResult> restorePlan(
            ClusterReference cluster, ClusterPlan plan, ClusterAccess access) {
        return CompletableFuture.completedFuture(new ValidationResult(
                cluster.id().equals(plan.clusterId()), "RESTORED", "fixture plan restored"));
    }
}
