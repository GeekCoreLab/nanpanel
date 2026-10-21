package io.nanpanel.core;

import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Namespace-scoped CRUD for Deployments created and registered by this control plane. */
@ApplicationScoped
public class KubernetesManagedDeploymentService {
  private static final int MAX_REPLICAS = 20;
  private static final int MAX_MANAGED_DEPLOYMENTS = 256;
  private static final String MANAGED_BY = "app.kubernetes.io/managed-by";
  private static final String MANAGED_LABEL = "nanpanel.io/managed";
  private static final String MANAGED_ID = "nanpanel.io/managed-id";
  private static final Pattern DNS_LABEL =
      Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern IMAGE =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/:@+-]{0,511}");

  @Inject AuthStore store;
  @Inject KubernetesClusterRegistryService clusters;

  public record CreateRequest(String name, String image, Integer replicas, Integer containerPort) {}
  public record ScaleRequest(Integer replicas) {}
  public record DeploymentList(UUID clusterId, String namespace, Instant observedAt,
      List<ManagedDeployment> items) {}
  public record ManagedDeployment(String name, String uid, String image, Integer replicas,
      Integer readyReplicas, Integer containerPort, String state) {}

  private record Ownership(String uid, String token) {}

  public DeploymentList list(UUID clusterId, String namespace) {
    validateNamespace(namespace);
    Instant observedAt = Instant.now();
    List<Map<String, Object>> rows = store.transaction(connection -> query(connection,
        "SELECT deployment_name,deployment_uid,ownership_token FROM kubernetes_managed_deployment "
            + "WHERE cluster_id=? AND namespace=? ORDER BY deployment_name LIMIT ?",
        clusterId, namespace, MAX_MANAGED_DEPLOYMENTS + 1));
    if (rows.size() > MAX_MANAGED_DEPLOYMENTS) throw new ResourceLimitException();
    try (KubernetesClient client = clusters.openClientForOperations(clusterId)) {
      List<ManagedDeployment> items = rows.stream().map(row -> {
        String name = String.valueOf(row.get("deployment_name"));
        String uid = String.valueOf(row.get("deployment_uid"));
        String token = String.valueOf(row.get("ownership_token"));
        Deployment deployment = client.apps().deployments().inNamespace(namespace)
            .withName(name).get();
        if (deployment == null) {
          return new ManagedDeployment(name, uid, null, null, null, null, "MISSING");
        }
        if (!owned(deployment, new Ownership(uid, token))) {
          return new ManagedDeployment(name, uid, null, null, null, null, "OWNERSHIP_CONFLICT");
        }
        return view(deployment);
      }).toList();
      return new DeploymentList(clusterId, namespace, observedAt, items);
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw missing;
    } catch (RuntimeException failure) {
      throw apiFailure(clusterId, failure);
    }
  }

  public ManagedDeployment create(String actor, String remoteAddress, UUID clusterId,
      String namespace, CreateRequest input) {
    validateNamespace(namespace);
    validateCreate(input);
    UUID ownershipToken = UUID.randomUUID();
    Instant now = Instant.now();
    try (KubernetesClient client = clusters.openClientForOperations(clusterId)) {
      List<Map<String, Object>> existing = store.transaction(connection -> query(connection,
          "SELECT deployment_name FROM kubernetes_managed_deployment "
              + "WHERE cluster_id=? AND namespace=? AND deployment_name=?",
          clusterId, namespace, input.name()));
      if (!existing.isEmpty()) throw new ResourceAlreadyManagedException();
      Deployment desired = deployment(namespace, input, ownershipToken.toString());
      Deployment created = client.apps().deployments().inNamespace(namespace).resource(desired).create();
      if (created == null || created.getMetadata() == null
          || created.getMetadata().getUid() == null || created.getMetadata().getResourceVersion() == null) {
        throw new ClusterApiUnavailableException();
      }
      try {
        store.transaction(connection -> {
          update(connection, "INSERT INTO kubernetes_managed_deployment "
                  + "(cluster_id,namespace,deployment_name,deployment_uid,ownership_token,created_by,created_at,updated_at) "
                  + "VALUES (?,?,?,?,?,?,?,?)",
              clusterId, namespace, input.name(), created.getMetadata().getUid(),
              ownershipToken.toString(), actor, Timestamp.from(now), Timestamp.from(now));
          audit(connection, actor, "KUBERNETES_DEPLOYMENT_CREATED", "kubernetes_managed_deployment",
              resourceId(clusterId, namespace, input.name()), "SUCCEEDED", remoteAddress,
              "创建托管 Deployment " + input.name() + "，期望副本数 " + input.replicas());
          return null;
        });
      } catch (RuntimeException persistenceFailure) {
        deleteCreated(client, namespace, created);
        throw new RegistryUnavailableException();
      }
      return view(created);
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw missing;
    } catch (ResourceAlreadyManagedException | RegistryUnavailableException
             | ClusterApiUnavailableException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      if (alreadyExists(failure)) throw new ResourceConflictException();
      throw apiFailure(clusterId, failure);
    }
  }

  public ManagedDeployment scale(String actor, String remoteAddress, UUID clusterId,
      String namespace, String name, ScaleRequest input) {
    validateNamespace(namespace);
    validateName(name);
    if (input == null || input.replicas() == null || input.replicas() < 0
        || input.replicas() > MAX_REPLICAS) {
      throw new IllegalArgumentException("replicas must be between 0 and 20");
    }
    Ownership ownership = ownership(clusterId, namespace, name);
    try (KubernetesClient client = clusters.openClientForOperations(clusterId)) {
      var resource = client.apps().deployments().inNamespace(namespace).withName(name);
      Deployment current = resource.get();
      requireOwned(current, ownership);
      Deployment scaled = resource.scale(input.replicas());
      if (scaled == null) throw new ClusterApiUnavailableException();
      Instant now = Instant.now();
      store.transaction(connection -> {
        update(connection, "UPDATE kubernetes_managed_deployment SET updated_at=? "
            + "WHERE cluster_id=? AND namespace=? AND deployment_name=? AND deployment_uid=?",
            Timestamp.from(now), clusterId, namespace, name, ownership.uid());
        audit(connection, actor, "KUBERNETES_DEPLOYMENT_SCALED", "kubernetes_managed_deployment",
            resourceId(clusterId, namespace, name), "SUCCEEDED", remoteAddress,
            "将托管 Deployment " + name + " 的期望副本数设为 " + input.replicas());
        return null;
      });
      return view(scaled);
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw missing;
    } catch (RuntimeException failure) {
      if (failure instanceof ResourceNotManagedException
          || failure instanceof OwnershipConflictException
          || failure instanceof ResourceConflictException
          || failure instanceof ClusterApiUnavailableException) throw failure;
      throw mutationFailure(clusterId, failure);
    }
  }

  public void delete(String actor, String remoteAddress, UUID clusterId,
      String namespace, String name) {
    validateNamespace(namespace);
    validateName(name);
    Ownership ownership = ownership(clusterId, namespace, name);
    try (KubernetesClient client = clusters.openClientForOperations(clusterId)) {
      var resource = client.apps().deployments().inNamespace(namespace).withName(name);
      Deployment current = resource.get();
      if (current == null) {
        removeOwnership(actor, remoteAddress, clusterId, namespace, name, ownership.uid(),
            "已清理缺失 Deployment 的托管记录");
        return;
      }
      requireOwned(current, ownership);
      String resourceVersion = current.getMetadata().getResourceVersion();
      if (resourceVersion == null || resourceVersion.isBlank()) {
        throw new ClusterApiUnavailableException();
      }
      resource.lockResourceVersion(resourceVersion).delete();
      removeOwnership(actor, remoteAddress, clusterId, namespace, name, ownership.uid(),
          "删除托管 Deployment " + name);
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw missing;
    } catch (RuntimeException failure) {
      if (failure instanceof ResourceNotManagedException
          || failure instanceof OwnershipConflictException
          || failure instanceof ResourceConflictException
          || failure instanceof ClusterApiUnavailableException) throw failure;
      throw mutationFailure(clusterId, failure);
    }
  }

  private Ownership ownership(UUID clusterId, String namespace, String name) {
    List<Map<String, Object>> rows = store.transaction(connection -> query(connection,
        "SELECT deployment_uid,ownership_token FROM kubernetes_managed_deployment "
            + "WHERE cluster_id=? AND namespace=? AND deployment_name=?",
        clusterId, namespace, name));
    if (rows.isEmpty()) throw new ResourceNotManagedException();
    return new Ownership(String.valueOf(rows.getFirst().get("deployment_uid")),
        String.valueOf(rows.getFirst().get("ownership_token")));
  }

  private void removeOwnership(String actor, String remoteAddress, UUID clusterId,
      String namespace, String name, String uid, String detail) {
    store.transaction(connection -> {
      update(connection, "DELETE FROM kubernetes_managed_deployment WHERE cluster_id=? "
          + "AND namespace=? AND deployment_name=? AND deployment_uid=?",
          clusterId, namespace, name, uid);
      audit(connection, actor, "KUBERNETES_DEPLOYMENT_DELETED", "kubernetes_managed_deployment",
          resourceId(clusterId, namespace, name), "SUCCEEDED", remoteAddress, detail);
      return null;
    });
  }

  private static Deployment deployment(String namespace, CreateRequest input, String ownershipToken) {
    String workloadLabel = input.name();
    return new DeploymentBuilder()
        .withNewMetadata().withName(input.name()).withNamespace(namespace)
          .addToLabels(MANAGED_BY, "nanpanel").addToLabels(MANAGED_LABEL, "true")
          .addToLabels("app.kubernetes.io/name", workloadLabel)
          .addToAnnotations(MANAGED_ID, ownershipToken)
        .endMetadata()
        .withNewSpec().withReplicas(input.replicas())
          .withNewSelector().addToMatchLabels("app.kubernetes.io/name", workloadLabel).endSelector()
          .withNewTemplate()
            .withNewMetadata().addToLabels("app.kubernetes.io/name", workloadLabel).endMetadata()
            .withNewSpec().addNewContainer().withName("app").withImage(input.image())
              .withImagePullPolicy("IfNotPresent")
              .addNewPort().withName("app").withContainerPort(input.containerPort())
                .withProtocol("TCP").endPort()
              .endContainer().endSpec()
          .endTemplate()
        .endSpec().build();
  }

  private static ManagedDeployment view(Deployment deployment) {
    var metadata = deployment.getMetadata();
    var spec = deployment.getSpec();
    var template = spec == null ? null : spec.getTemplate();
    var podSpec = template == null ? null : template.getSpec();
    var containers = podSpec == null ? List.<io.fabric8.kubernetes.api.model.Container>of()
        : podSpec.getContainers() == null ? List.<io.fabric8.kubernetes.api.model.Container>of()
        : podSpec.getContainers();
    var first = containers.isEmpty() ? null : containers.getFirst();
    int replicas = spec == null || spec.getReplicas() == null ? 0 : spec.getReplicas();
    int ready = deployment.getStatus() == null || deployment.getStatus().getReadyReplicas() == null
        ? 0 : deployment.getStatus().getReadyReplicas();
    String state = ready >= replicas ? "READY" : "PROGRESSING";
    Integer port = first == null || first.getPorts() == null || first.getPorts().isEmpty()
        ? null : first.getPorts().getFirst().getContainerPort();
    return new ManagedDeployment(metadata.getName(), metadata.getUid(),
        first == null ? null : first.getImage(), replicas, ready, port, state);
  }

  private static boolean owned(Deployment deployment, Ownership ownership) {
    if (deployment.getMetadata() == null) return false;
    var metadata = deployment.getMetadata();
    Map<String, String> labels = metadata.getLabels();
    Map<String, String> annotations = metadata.getAnnotations();
    return ownership.uid().equals(metadata.getUid()) && labels != null
        && "nanpanel".equals(labels.get(MANAGED_BY)) && "true".equals(labels.get(MANAGED_LABEL))
        && annotations != null && ownership.token().equals(annotations.get(MANAGED_ID));
  }

  private static void requireOwned(Deployment deployment, Ownership ownership) {
    if (deployment == null) throw new ResourceNotManagedException();
    if (!owned(deployment, ownership)) throw new OwnershipConflictException();
  }

  private static void deleteCreated(KubernetesClient client, String namespace, Deployment created) {
    try {
      var metadata = created.getMetadata();
      if (metadata != null && metadata.getName() != null && metadata.getResourceVersion() != null) {
        client.apps().deployments().inNamespace(namespace).withName(metadata.getName())
            .lockResourceVersion(metadata.getResourceVersion()).delete();
      }
    } catch (RuntimeException ignored) {
      // The API has accepted the workload; keep its ownership marker for manual recovery.
    }
  }

  private static void validateCreate(CreateRequest input) {
    if (input == null) throw new IllegalArgumentException("Deployment request is required");
    validateName(input.name());
    if (input.image() == null || input.image().length() > 512
        || !IMAGE.matcher(input.image()).matches()) {
      throw new IllegalArgumentException("container image reference is invalid");
    }
    if (input.replicas() == null || input.replicas() < 1 || input.replicas() > MAX_REPLICAS) {
      throw new IllegalArgumentException("replicas must be between 1 and 20");
    }
    if (input.containerPort() == null || input.containerPort() < 1 || input.containerPort() > 65535) {
      throw new IllegalArgumentException("containerPort must be between 1 and 65535");
    }
  }

  private static void validateNamespace(String namespace) {
    validateName(namespace);
  }

  private static void validateName(String name) {
    if (name == null || name.length() > 63 || !DNS_LABEL.matcher(name).matches()) {
      throw new IllegalArgumentException("Kubernetes namespace and Deployment names must be DNS labels");
    }
  }

  private static boolean alreadyExists(RuntimeException failure) {
    return failure instanceof io.fabric8.kubernetes.client.KubernetesClientException clientFailure
        && clientFailure.getCode() == 409;
  }

  private RuntimeException apiFailure(UUID clusterId, RuntimeException failure) {
    if (failure instanceof io.fabric8.kubernetes.client.KubernetesClientException) {
      RuntimeException reported = clusters.reportApiFailure(clusterId, failure);
      if (reported instanceof KubernetesClusterRegistryService.ClusterCredentialRejectedException) {
        return new CredentialRejectedException();
      }
      if (reported instanceof KubernetesClusterRegistryService.ClusterPermissionDeniedException) {
        return new PermissionDeniedException();
      }
    }
    return new ClusterApiUnavailableException();
  }

  private RuntimeException mutationFailure(UUID clusterId, RuntimeException failure) {
    if (failure instanceof io.fabric8.kubernetes.client.KubernetesClientException clientFailure
        && clientFailure.getCode() == 409) {
      return new ResourceConflictException();
    }
    return apiFailure(clusterId, failure);
  }

  private static String resourceId(UUID clusterId, String namespace, String name) {
    return clusterId + ":" + namespace + "/" + name;
  }

  public static final class ClusterApiUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
  public static final class ResourceAlreadyManagedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
  public static final class ResourceConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
  public static final class ResourceNotManagedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
  public static final class OwnershipConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
  public static final class ResourceLimitException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
  public static final class PermissionDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
  public static final class CredentialRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
  public static final class RegistryUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
