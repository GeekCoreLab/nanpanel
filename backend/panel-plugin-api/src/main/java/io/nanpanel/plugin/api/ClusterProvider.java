package io.nanpanel.plugin.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.pf4j.ExtensionPoint;

/**
 * Cluster-scoped lifecycle planning contract. Host-side steps are descriptions for Core to dispatch
 * through Agent/Helper; providers never receive a raw join token or kubeconfig in this API.
 */
public interface ClusterProvider extends ExtensionPoint {
    String id();
    Set<String> clusterKinds();
    Set<String> operations();

    default ProviderDescriptor descriptor() {
        return new ProviderDescriptor(id(), id(), "0.2.0", "panel.io/cluster-providers/v2");
    }

    /**
     * Reads a Core-owned, credential-free snapshot. Providers never receive a Kubernetes client,
     * kubeconfig, ServiceAccount token, or raw Kubernetes resource payload.
     */
    CompletionStage<ClusterObservation> observe(ClusterReference cluster, ClusterAccess access);

    CompletionStage<ClusterPlan> plan(
            ClusterReference cluster, LifecycleRequest request, ClusterObservation observed);

    CompletionStage<ValidationResult> validate(
            ClusterReference cluster, ClusterPlan plan, ClusterAccess access);

    /** Providers must not claim recovery across Core restarts unless they can rebuild safe state. */
    default CompletionStage<ValidationResult> restorePlan(
            ClusterReference cluster, ClusterPlan plan, ClusterAccess access) {
        return CompletableFuture.completedFuture(
                new ValidationResult(false, "RESTORE_UNSUPPORTED", "Provider plan restoration is unsupported"));
    }

    record ClusterReference(UUID id, String kind, long generation) {
        public ClusterReference {
            if (id == null || kind == null || !kind.matches("[A-Z][A-Z0-9_-]{1,31}") || generation < 0) {
                throw new IllegalArgumentException("cluster reference is invalid");
            }
        }
    }

    /** References are opaque IDs into Core-owned credential storage; they never contain secret bytes. */
    record LifecycleRequest(String operation, String component, UUID hostId, String targetVersion,
                            UUID credentialReference) {
        public LifecycleRequest {
            requireName(operation, "operation");
            requireName(component, "component");
            if (targetVersion != null && targetVersion.isBlank()) {
                throw new IllegalArgumentException("targetVersion must be null or nonblank");
            }
        }
    }

    record ClusterObservation(UUID clusterId, long generation, String status,
                              String fingerprint, List<String> conditions) {
        public ClusterObservation {
            if (clusterId == null || generation < 0 || status == null || status.isBlank()) {
                throw new IllegalArgumentException("cluster observation is invalid");
            }
            conditions = List.copyOf(conditions);
        }
    }

    /** Restricted read-only view built by Core from the registered cluster inventory. */
    @FunctionalInterface
    interface ClusterAccess {
        CompletionStage<ClusterSnapshot> snapshot();
    }

    record ClusterSnapshot(UUID clusterId, long generation, String kind, String healthStatus,
                           String cloudCoreStatus, Instant observedAt, List<NodeSnapshot> nodes) {
        public ClusterSnapshot {
            if (clusterId == null || generation < 0 || kind == null
                    || !kind.matches("[A-Z][A-Z0-9_-]{1,31}")
                    || healthStatus == null || healthStatus.isBlank()
                    || cloudCoreStatus == null || cloudCoreStatus.isBlank()) {
                throw new IllegalArgumentException("cluster snapshot is invalid");
            }
            nodes = List.copyOf(nodes);
        }
    }

    record NodeSnapshot(String uid, String name, boolean present, boolean edge,
                        boolean ready, String kubeletVersion, UUID hostId) {
        public NodeSnapshot {
            if (uid == null || uid.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("node snapshot is invalid");
            }
        }
    }

    record ClusterPlan(UUID id, UUID clusterId, long generation, String operation,
                       String idempotencyKey, List<PlanStep> steps) {
        public ClusterPlan {
            if (id == null || clusterId == null || generation < 0 || idempotencyKey == null
                    || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("cluster plan identity is invalid");
            }
            requireName(operation, "operation");
            steps = List.copyOf(steps);
            for (int index = 0; index < steps.size(); index++) {
                if (steps.get(index).ordinal() != index) {
                    throw new IllegalArgumentException("plan steps must be ordered from zero");
                }
            }
        }
    }

    enum ExecutionTarget { KUBERNETES_API, AGENT_HELPER }

    /**
     * A step contains only non-secret parameters and opaque credential references. Core owns dispatch,
     * persistence, audit redaction, and operation reconciliation.
     */
    record PlanStep(int ordinal, ExecutionTarget target, UUID hostId, String capability,
                    Map<String, String> parameters, List<UUID> credentialReferences) {
        public PlanStep {
            if (ordinal < 0 || target == null || capability == null
                    || !capability.matches("[a-z][a-z0-9_.-]{0,79}")) {
                throw new IllegalArgumentException("plan step is invalid");
            }
            if ((target == ExecutionTarget.AGENT_HELPER) != (hostId != null)) {
                throw new IllegalArgumentException("only Agent Helper steps must identify a Host");
            }
            parameters = Map.copyOf(parameters);
            if (parameters.keySet().stream().anyMatch(ClusterProvider::looksSecretLike)) {
                throw new IllegalArgumentException("secret material must be represented by a credential reference");
            }
            credentialReferences = List.copyOf(credentialReferences);
        }
    }

    record ValidationResult(boolean successful, String code, String detail) {
        public ValidationResult {
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,79}")
                    || detail == null || detail.isBlank()) {
                throw new IllegalArgumentException("validation result is invalid");
            }
        }
    }

    record ProviderDescriptor(String id, String name, String version, String apiVersion) {}

    private static void requireName(String value, String field) {
        if (value == null || !value.matches("[a-z][a-z0-9_.-]{0,79}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static boolean looksSecretLike(String key) {
        return key == null || key.isBlank()
                || key.toLowerCase(java.util.Locale.ROOT).matches(".*(token|password|secret|credential|kubeconfig|private.?key).*");
    }
}
