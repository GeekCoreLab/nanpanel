package io.nanpanel.core;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Bounded, read-only namespace resource summaries for the application manager. */
@ApplicationScoped
public class KubernetesNamespaceResourceInventoryService {
  private static final int MAX_RESOURCES_PER_KIND = 512;
  private static final int MAX_TOTAL_RESOURCES = 8192;
  private static final int MAX_SUMMARY_LENGTH = 2048;
  private static final Pattern DNS_LABEL =
      Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Set<String> DISPLAY_LABELS = Set.of("app", "version",
      "app.kubernetes.io/name", "app.kubernetes.io/component", "app.kubernetes.io/version");
  private static final List<ResourceKind> RESOURCE_KINDS = List.of(
      new ResourceKind("v1", "Pod", false),
      new ResourceKind("v1", "Service", false),
      new ResourceKind("v1", "ConfigMap", false),
      new ResourceKind("v1", "ServiceAccount", false),
      new ResourceKind("v1", "Endpoints", false),
      new ResourceKind("v1", "PersistentVolumeClaim", false),
      new ResourceKind("v1", "ResourceQuota", false),
      new ResourceKind("v1", "LimitRange", false),
      new ResourceKind("rbac.authorization.k8s.io/v1", "Role", false),
      new ResourceKind("rbac.authorization.k8s.io/v1", "RoleBinding", false),
      new ResourceKind("apps/v1", "Deployment", false),
      new ResourceKind("apps/v1", "StatefulSet", false),
      new ResourceKind("apps/v1", "DaemonSet", false),
      new ResourceKind("apps/v1", "ReplicaSet", false),
      new ResourceKind("batch/v1", "Job", false),
      new ResourceKind("batch/v1", "CronJob", false),
      new ResourceKind("autoscaling/v2", "HorizontalPodAutoscaler", false),
      new ResourceKind("policy/v1", "PodDisruptionBudget", false),
      new ResourceKind("networking.k8s.io/v1", "Ingress", false),
      new ResourceKind("networking.k8s.io/v1", "NetworkPolicy", false),
      new ResourceKind("discovery.k8s.io/v1", "EndpointSlice", false),
      new ResourceKind("networking.istio.io/v1", "VirtualService", true),
      new ResourceKind("networking.istio.io/v1", "DestinationRule", true),
      new ResourceKind("networking.istio.io/v1", "Gateway", true),
      new ResourceKind("networking.istio.io/v1", "ServiceEntry", true),
      new ResourceKind("networking.istio.io/v1", "Sidecar", true),
      new ResourceKind("security.istio.io/v1", "AuthorizationPolicy", true),
      new ResourceKind("security.istio.io/v1", "PeerAuthentication", true),
      new ResourceKind("security.istio.io/v1", "RequestAuthentication", true));

  @Inject KubernetesClusterRegistryService clusters;

  public record Inventory(String clusterId, String namespace, Instant observedAt,
      List<ResourceSummary> items) {}

  public record ResourceSummary(String apiVersion, String kind, String name, String status,
      String summary, String createdAt, Map<String, String> labels) {}

  private record ResourceKind(String apiVersion, String kind, boolean optional) {}

  public static final class NamespaceNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }

  public Inventory list(UUID clusterId, String namespace) {
    if (clusterId == null || !validNamespace(namespace)) {
      throw new IllegalArgumentException("集群 ID 或 Namespace 无效");
    }
    try (KubernetesClient client = clusters.openClientForOperations(clusterId)) {
      if (client.namespaces().withName(namespace).get() == null) {
        throw new NamespaceNotFoundException();
      }
      List<ResourceSummary> items = new ArrayList<>();
      for (ResourceKind kind : RESOURCE_KINDS) {
        for (GenericKubernetesResource resource : listKind(client, namespace, kind)) {
          items.add(summarize(kind, resource));
        }
      }
      if (items.size() > MAX_TOTAL_RESOURCES) {
        throw new IllegalStateException("Namespace 资源数量超过清单上限");
      }
      items.sort(Comparator.comparing(ResourceSummary::kind)
          .thenComparing(ResourceSummary::name));
      return new Inventory(clusterId.toString(), namespace, Instant.now(), List.copyOf(items));
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException
        | KubernetesClusterRegistryService.ClusterCredentialRejectedException
        | KubernetesClusterRegistryService.ClusterPermissionDeniedException
        | KubernetesClusterRegistryService.ClusterApiUnavailableException
        | NamespaceNotFoundException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw clusters.reportApiFailure(clusterId, failure);
    }
  }

  private static List<GenericKubernetesResource> listKind(KubernetesClient client,
      String namespace, ResourceKind kind) {
    try {
      var result = client.genericKubernetesResources(kind.apiVersion(), kind.kind())
          .inNamespace(namespace).list(new ListOptionsBuilder()
              .withLimit((long) MAX_RESOURCES_PER_KIND + 1).build());
      if (result == null || result.getItems() == null
          || result.getItems().size() > MAX_RESOURCES_PER_KIND
          || result.getMetadata() != null && result.getMetadata().getContinue() != null
              && !result.getMetadata().getContinue().isBlank()) {
        throw new IllegalStateException(kind.kind() + " 资源数量超过清单上限");
      }
      return result.getItems();
    } catch (KubernetesClientException unavailable) {
      if (kind.optional() && unavailable.getCode() == 404) return List.of();
      throw unavailable;
    }
  }

  private static ResourceSummary summarize(ResourceKind descriptor,
      GenericKubernetesResource resource) {
    ObjectMeta metadata = resource == null ? null : resource.getMetadata();
    if (metadata == null || metadata.getName() == null || metadata.getName().length() > 253) {
      throw new IllegalStateException(descriptor.kind() + " 资源元数据无效");
    }
    Map<String, Object> fields = resource.getAdditionalProperties() == null
        ? Map.of() : resource.getAdditionalProperties();
    Map<String, Object> spec = childMap(fields.get("spec"));
    Map<String, Object> statusFields = childMap(fields.get("status"));
    String state = state(descriptor.kind(), spec, statusFields);
    String summary = summary(descriptor.kind(), spec, statusFields, fields);
    return new ResourceSummary(descriptor.apiVersion(), descriptor.kind(), metadata.getName(),
        state, bounded(summary), metadata.getCreationTimestamp(), safeLabels(metadata.getLabels()));
  }

  private static String state(String kind, Map<String, Object> spec,
      Map<String, Object> status) {
    return switch (kind) {
      case "Pod" -> value(status.get("phase"), "UNKNOWN");
      case "Deployment", "StatefulSet" -> {
        int desired = number(spec.get("replicas"), 1);
        int ready = number(status.get("readyReplicas"), 0);
        yield ready >= desired ? "READY" : "PROGRESSING";
      }
      case "DaemonSet" -> number(status.get("numberReady"), 0)
          >= number(status.get("desiredNumberScheduled"), 0) ? "READY" : "PROGRESSING";
      case "Job" -> number(status.get("succeeded"), 0) > 0 ? "SUCCEEDED"
          : number(status.get("failed"), 0) > 0 ? "FAILED" : "RUNNING";
      case "PersistentVolumeClaim" -> value(status.get("phase"), "UNKNOWN");
      case "Service", "ConfigMap", "ServiceAccount", "Endpoints", "ReplicaSet", "CronJob",
          "ResourceQuota", "LimitRange", "Role", "RoleBinding", "HorizontalPodAutoscaler",
          "PodDisruptionBudget", "Ingress", "NetworkPolicy", "EndpointSlice", "VirtualService",
          "DestinationRule", "Gateway", "ServiceEntry", "Sidecar", "AuthorizationPolicy",
          "PeerAuthentication", "RequestAuthentication" -> "CONFIGURED";
      default -> "UNKNOWN";
    };
  }

  private static String summary(String kind, Map<String, Object> spec, Map<String, Object> status,
      Map<String, Object> fields) {
    return switch (kind) {
      case "Pod" -> podSummary(spec, status);
      case "Service" -> serviceSummary(spec);
      case "Deployment", "StatefulSet" -> workloadSummary(kind, spec, status);
      case "DaemonSet" -> daemonSetSummary(status);
      case "ReplicaSet" -> workloadSummary(kind, spec, status);
      case "Job" -> jobSummary(status);
      case "CronJob" -> "schedule " + value(spec.get("schedule"), "unknown");
      case "ConfigMap" -> configMapSummary(fields);
      case "ServiceAccount" -> serviceAccountSummary(spec);
      case "PersistentVolumeClaim" -> pvcSummary(spec, status);
      case "Endpoints" -> endpointsSummary(fields);
      case "Ingress" -> ingressSummary(spec);
      case "NetworkPolicy" -> networkPolicySummary(spec);
      case "EndpointSlice" -> endpointSliceSummary(fields);
      case "VirtualService" -> virtualServiceSummary(spec);
      case "DestinationRule" -> destinationRuleSummary(spec);
      case "Gateway" -> gatewaySummary(spec);
      case "HorizontalPodAutoscaler" -> "min " + value(spec.get("minReplicas"), "1")
          + " · max " + value(spec.get("maxReplicas"), "unknown") + " replicas";
      case "PodDisruptionBudget" -> "min available " + value(spec.get("minAvailable"), "unset")
          + " · max unavailable " + value(spec.get("maxUnavailable"), "unset");
      case "ResourceQuota" -> "resource quota metadata";
      case "LimitRange" -> "limit range metadata";
      case "Role", "RoleBinding" -> "RBAC metadata";
      case "ServiceEntry", "Sidecar", "AuthorizationPolicy", "PeerAuthentication",
          "RequestAuthentication" -> "Istio " + kind;
      default -> "Kubernetes " + kind;
    };
  }

  private static String podSummary(Map<String, Object> spec, Map<String, Object> status) {
    List<String> images = containers(spec).stream().map(container -> value(container.get("image"), ""))
        .filter(image -> !image.isBlank()).distinct().toList();
    int ready = 0;
    for (Object item : list(status.get("containerStatuses"))) {
      if (Boolean.TRUE.equals(childMap(item).get("ready"))) ready++;
    }
    String node = value(spec.get("nodeName"), "node pending");
    String ip = value(status.get("podIP"), "no pod IP");
    return ready + "/" + containers(spec).size() + " containers ready · " + node + " · " + ip
        + imageSuffix(images);
  }

  private static String serviceSummary(Map<String, Object> spec) {
    String type = value(spec.get("type"), "ClusterIP");
    String ip = value(spec.get("clusterIP"), value(spec.get("externalName"), "no cluster IP"));
    List<String> ports = list(spec.get("ports")).stream().map(KubernetesNamespaceResourceInventoryService::childMap)
        .map(port -> value(port.get("port"), "?") + "/"
            + value(port.get("protocol"), "TCP") + " → "
            + value(port.get("targetPort"), value(port.get("port"), "?")))
        .toList();
    return type + " · " + ip + (ports.isEmpty() ? "" : " · " + String.join(", ", ports));
  }

  private static String workloadSummary(String kind, Map<String, Object> spec,
      Map<String, Object> status) {
    int desired = number(spec.get("replicas"), 1);
    int ready = number(status.get("readyReplicas"), 0);
    List<String> images = containers(childMap(spec.get("template"))
        .get("spec")).stream().map(container -> value(container.get("image"), ""))
        .filter(image -> !image.isBlank()).distinct().toList();
    return ready + "/" + desired + " ready" + imageSuffix(images);
  }

  private static String daemonSetSummary(Map<String, Object> status) {
    return number(status.get("numberReady"), 0) + "/"
        + number(status.get("desiredNumberScheduled"), 0) + " nodes ready";
  }

  private static String jobSummary(Map<String, Object> status) {
    return number(status.get("succeeded"), 0) + " succeeded · "
        + number(status.get("active"), 0) + " active · "
        + number(status.get("failed"), 0) + " failed";
  }

  private static String configMapSummary(Map<String, Object> fields) {
    List<String> keys = new ArrayList<>();
    keys.addAll(childMap(fields.get("data")).keySet());
    keys.addAll(childMap(fields.get("binaryData")).keySet());
    return keys.isEmpty() ? "no keys" : "keys: " + String.join(", ", keys.stream().sorted().toList());
  }

  private static String serviceAccountSummary(Map<String, Object> spec) {
    return list(spec.get("imagePullSecrets")).size() + " image pull secrets · token data hidden";
  }

  private static String pvcSummary(Map<String, Object> spec, Map<String, Object> status) {
    String capacity = value(childMap(status.get("capacity")).get("storage"), "capacity pending");
    String storageClass = value(spec.get("storageClassName"), "default storage class");
    return capacity + " · " + storageClass;
  }

  private static String endpointsSummary(Map<String, Object> fields) {
    int addresses = 0;
    for (Object subset : list(fields.get("subsets"))) {
      Map<String, Object> values = childMap(subset);
      addresses += list(values.get("addresses")).size() + list(values.get("notReadyAddresses")).size();
    }
    return addresses + " endpoint addresses";
  }

  private static String ingressSummary(Map<String, Object> spec) {
    List<String> hosts = list(spec.get("rules")).stream()
        .map(KubernetesNamespaceResourceInventoryService::childMap)
        .map(rule -> value(rule.get("host"), "*"))
        .distinct().toList();
    return hosts.isEmpty() ? "no host rules" : "hosts: " + String.join(", ", hosts);
  }

  private static String networkPolicySummary(Map<String, Object> spec) {
    return list(spec.get("ingress")).size() + " ingress rules · "
        + list(spec.get("egress")).size() + " egress rules";
  }

  private static String endpointSliceSummary(Map<String, Object> fields) {
    int endpoints = list(fields.get("endpoints")).size();
    String addressType = value(fields.get("addressType"), "unknown address type");
    return endpoints + " endpoints · " + addressType;
  }

  private static String virtualServiceSummary(Map<String, Object> spec) {
    List<String> hosts = strings(spec.get("hosts"));
    List<String> destinations = new ArrayList<>();
    for (Object httpValue : list(spec.get("http"))) {
      for (Object routeValue : list(childMap(httpValue).get("route"))) {
        Map<String, Object> route = childMap(routeValue);
        Map<String, Object> destination = childMap(route.get("destination"));
        String host = value(destination.get("host"), "unknown destination");
        String weight = route.containsKey("weight") ? value(route.get("weight"), "0") + "%" : "weight unspecified";
        destinations.add(weight + " → " + host);
      }
    }
    return "hosts: " + (hosts.isEmpty() ? "none" : String.join(", ", hosts))
        + (destinations.isEmpty() ? "" : " · " + String.join("; ", destinations));
  }

  private static String destinationRuleSummary(Map<String, Object> spec) {
    List<String> subsets = list(spec.get("subsets")).stream()
        .map(KubernetesNamespaceResourceInventoryService::childMap)
        .map(item -> value(item.get("name"), "unknown"))
        .toList();
    return "host: " + value(spec.get("host"), "unknown")
        + (subsets.isEmpty() ? "" : " · subsets: " + String.join(", ", subsets));
  }

  private static String gatewaySummary(Map<String, Object> spec) {
    List<String> servers = new ArrayList<>();
    for (Object serverValue : list(spec.get("servers"))) {
      Map<String, Object> server = childMap(serverValue);
      Map<String, Object> port = childMap(server.get("port"));
      servers.add(value(port.get("number"), "?") + "/" + value(port.get("protocol"), "unknown")
          + " · " + String.join(", ", strings(server.get("hosts"))));
    }
    return servers.isEmpty() ? "no servers" : String.join("; ", servers);
  }

  private static List<Map<String, Object>> containers(Object value) {
    return list(childMap(value).get("containers")).stream()
        .map(KubernetesNamespaceResourceInventoryService::childMap).toList();
  }

  private static String imageSuffix(List<String> images) {
    return images.isEmpty() ? "" : " · " + String.join(", ", images);
  }

  private static Map<String, String> safeLabels(Map<String, String> labels) {
    if (labels == null || labels.isEmpty()) return Map.of();
    Map<String, String> selected = new LinkedHashMap<>();
    for (String key : DISPLAY_LABELS) {
      String value = labels.get(key);
      if (value != null && !value.isBlank() && value.length() <= 128) selected.put(key, value);
    }
    return Map.copyOf(selected);
  }

  private static Map<String, Object> childMap(Object value) {
    if (!(value instanceof Map<?, ?> map)) return Map.of();
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach((key, item) -> {
      if (key instanceof String stringKey && item != null) result.put(stringKey, item);
    });
    return result;
  }

  private static List<?> list(Object value) {
    return value instanceof List<?> items ? items : List.of();
  }

  private static List<String> strings(Object value) {
    return list(value).stream().filter(String.class::isInstance).map(String.class::cast)
        .filter(item -> !item.isBlank()).toList();
  }

  private static int number(Object value, int fallback) {
    return value instanceof Number number ? Math.max(0, number.intValue()) : fallback;
  }

  private static String value(Object value, String fallback) {
    return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
  }

  private static String bounded(String value) {
    if (value == null || value.isBlank()) return "";
    String sanitized = value.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ").trim();
    return sanitized.length() <= MAX_SUMMARY_LENGTH
        ? sanitized : sanitized.substring(0, MAX_SUMMARY_LENGTH);
  }

  private static boolean validNamespace(String namespace) {
    return namespace != null && namespace.length() <= 63 && DNS_LABEL.matcher(namespace).matches();
  }
}
