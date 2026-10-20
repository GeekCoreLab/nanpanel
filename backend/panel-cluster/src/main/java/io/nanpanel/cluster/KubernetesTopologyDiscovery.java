package io.nanpanel.cluster;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressList;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.IngressServiceBackend;
import io.fabric8.kubernetes.api.model.networking.v1.ServiceBackendPort;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Read-only, bounded discovery for the hierarchical Kubernetes topology view. */
public final class KubernetesTopologyDiscovery {
  private static final int MAX_NAMESPACES = 256;
  private static final int MAX_PODS = 2048;
  private static final int MAX_SERVICES = 512;
  private static final int MAX_INGRESSES = 512;
  private static final Pattern DNS_LABEL =
      Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern CLUSTER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

  public NamespaceSnapshot namespaces(KubernetesClient client, String clusterId) {
    Objects.requireNonNull(client, "client");
    String sourceId = sourceId(clusterId);
    var result = client.namespaces().list(new ListOptionsBuilder()
        .withLimit((long) MAX_NAMESPACES + 1).build());
    requireComplete("Namespace", result == null ? null : result.getMetadata() == null
        ? null : result.getMetadata().getContinue(), result == null ? null : result.getItems(),
        MAX_NAMESPACES);
    List<NamespaceObservation> items = result.getItems().stream().map(namespace -> {
      if (namespace == null || namespace.getMetadata() == null) {
        throw new IllegalArgumentException("Kubernetes Namespace metadata is missing");
      }
      String name = namespace.getMetadata().getName();
      validateNamespace(name);
      String phase = namespace.getStatus() == null || namespace.getStatus().getPhase() == null
          ? "UNKNOWN" : namespace.getStatus().getPhase();
      return new NamespaceObservation(name, phase);
    }).sorted(Comparator.comparing(NamespaceObservation::name)).toList();
    return new NamespaceSnapshot(sourceId, Instant.now(), items);
  }

  public NamespaceDetailSnapshot namespace(KubernetesClient client, String clusterId,
                                           String namespaceName) {
    Objects.requireNonNull(client, "client");
    String sourceId = sourceId(clusterId);
    String namespace = validateNamespace(namespaceName);
    Namespace found = client.namespaces().withName(namespace).get();
    if (found == null) {
      throw new NamespaceNotFoundException();
    }

    var podList = client.pods().inNamespace(namespace).list(new ListOptionsBuilder()
        .withLimit((long) MAX_PODS + 1).build());
    var serviceList = client.services().inNamespace(namespace).list(new ListOptionsBuilder()
        .withLimit((long) MAX_SERVICES + 1).build());
    IngressList ingressList = client.network().v1().ingresses().inNamespace(namespace)
        .list(new ListOptionsBuilder().withLimit((long) MAX_INGRESSES + 1).build());
    requireComplete("Pod", continuation(podList), podList == null ? null : podList.getItems(), MAX_PODS);
    requireComplete("Service", continuation(serviceList),
        serviceList == null ? null : serviceList.getItems(), MAX_SERVICES);
    requireComplete("Ingress", continuation(ingressList),
        ingressList == null ? null : ingressList.getItems(), MAX_INGRESSES);

    List<PodObservation> pods = podList.getItems().stream().map(pod -> podObservation(pod, namespace))
        .sorted(Comparator.comparing(PodObservation::name)).toList();
    List<ServiceObservation> services = serviceList.getItems().stream()
        .map(service -> serviceObservation(service, namespace))
        .sorted(Comparator.comparing(ServiceObservation::name)).toList();
    List<IngressObservation> ingresses = ingressList.getItems().stream()
        .map(ingress -> ingressObservation(ingress, namespace))
        .sorted(Comparator.comparing(IngressObservation::name)).toList();
    return new NamespaceDetailSnapshot(sourceId, namespace, Instant.now(), services, ingresses, pods);
  }

  private static PodObservation podObservation(Pod pod, String namespace) {
    if (pod == null || pod.getMetadata() == null
        || !namespace.equals(pod.getMetadata().getNamespace())) {
      throw new IllegalArgumentException("Kubernetes Pod namespace is invalid");
    }
    String name = pod.getMetadata().getName();
    if (name == null || name.length() > 253) {
      throw new IllegalArgumentException("Kubernetes Pod name is invalid");
    }
    var status = pod.getStatus();
    boolean ready = status != null && status.getConditions() != null
        && status.getConditions().stream().anyMatch(condition -> condition != null
        && "Ready".equals(condition.getType()) && "True".equals(condition.getStatus()));
    String phase = status == null || status.getPhase() == null ? "UNKNOWN" : status.getPhase();
    String ip = status == null ? null : status.getPodIP();
    String nodeName = pod.getSpec() == null ? null : pod.getSpec().getNodeName();
    List<ContainerObservation> containers = pod.getSpec() == null || pod.getSpec().getContainers() == null
        ? List.of() : pod.getSpec().getContainers().stream()
            .map(container -> containerObservation(container, status)).toList();
    return new PodObservation(name, phase, ready, ip, nodeName,
        pod.getMetadata().getCreationTimestamp(), containers);
  }

  private static ContainerObservation containerObservation(Container container,
                                                            io.fabric8.kubernetes.api.model.PodStatus status) {
    if (container == null || container.getName() == null) {
      throw new IllegalArgumentException("Kubernetes Pod container name is missing");
    }
    List<ContainerPortObservation> ports = container.getPorts() == null ? List.of()
        : container.getPorts().stream().map(KubernetesTopologyDiscovery::containerPort).toList();
    ContainerStatus observation = status == null || status.getContainerStatuses() == null ? null
        : status.getContainerStatuses().stream().filter(item -> item != null
            && container.getName().equals(item.getName())).findFirst().orElse(null);
    return new ContainerObservation(container.getName(), container.getImage(),
        observation != null && Boolean.TRUE.equals(observation.getReady()),
        observation == null || observation.getRestartCount() == null ? 0 : observation.getRestartCount(),
        ports);
  }

  private static ContainerPortObservation containerPort(ContainerPort port) {
    if (port == null || port.getContainerPort() == null) {
      throw new IllegalArgumentException("Kubernetes Pod container port is invalid");
    }
    return new ContainerPortObservation(port.getName(), port.getContainerPort(),
        port.getProtocol() == null ? "TCP" : port.getProtocol());
  }

  private static ServiceObservation serviceObservation(Service service, String namespace) {
    if (service == null || service.getMetadata() == null || service.getSpec() == null
        || !namespace.equals(service.getMetadata().getNamespace())) {
      throw new IllegalArgumentException("Kubernetes Service namespace or spec is invalid");
    }
    var spec = service.getSpec();
    List<String> clusterIps = spec.getClusterIPs() == null
        ? spec.getClusterIP() == null ? List.of() : List.of(spec.getClusterIP())
        : spec.getClusterIPs().stream().filter(Objects::nonNull).toList();
    List<ServicePortObservation> ports = spec.getPorts() == null ? List.of()
        : spec.getPorts().stream().map(port -> new ServicePortObservation(port.getName(),
            port.getPort(), port.getProtocol() == null ? "TCP" : port.getProtocol(),
            targetPort(port.getTargetPort()))).toList();
    return new ServiceObservation(service.getMetadata().getName(),
        spec.getType() == null ? "ClusterIP" : spec.getType(), clusterIps,
        spec.getExternalName(), ports);
  }

  private static IngressObservation ingressObservation(Ingress ingress, String namespace) {
    if (ingress == null || ingress.getMetadata() == null
        || !namespace.equals(ingress.getMetadata().getNamespace())) {
      throw new IllegalArgumentException("Kubernetes Ingress namespace is invalid");
    }
    var spec = ingress.getSpec();
    List<IngressRouteObservation> routes = spec == null || spec.getRules() == null ? List.of()
        : spec.getRules().stream().flatMap(rule -> ingressRoutes(rule).stream()).toList();
    List<String> addresses = ingress.getStatus() == null
        || ingress.getStatus().getLoadBalancer() == null
        || ingress.getStatus().getLoadBalancer().getIngress() == null ? List.of()
        : ingress.getStatus().getLoadBalancer().getIngress().stream()
            .map(item -> item.getIp() == null ? item.getHostname() : item.getIp())
            .filter(Objects::nonNull).toList();
    return new IngressObservation(ingress.getMetadata().getName(),
        spec == null ? null : spec.getIngressClassName(), addresses, routes);
  }

  private static List<IngressRouteObservation> ingressRoutes(IngressRule rule) {
    if (rule == null || rule.getHttp() == null || rule.getHttp().getPaths() == null) {
      return List.of();
    }
    return rule.getHttp().getPaths().stream().map(path -> ingressRoute(rule.getHost(), path)).toList();
  }

  private static IngressRouteObservation ingressRoute(String host, HTTPIngressPath path) {
    if (path == null || path.getBackend() == null || path.getBackend().getService() == null) {
      throw new IllegalArgumentException("Kubernetes Ingress service backend is missing");
    }
    IngressServiceBackend service = path.getBackend().getService();
    ServiceBackendPort port = service.getPort();
    return new IngressRouteObservation(host, path.getPath(), path.getPathType(), service.getName(),
        port == null ? null : port.getNumber(), port == null ? null : port.getName());
  }

  private static String targetPort(IntOrString value) {
    if (value == null) return null;
    return value.getIntVal() == null ? value.getStrVal() : String.valueOf(value.getIntVal());
  }

  private static String continuation(io.fabric8.kubernetes.api.model.KubernetesResourceList<?> value) {
    return value == null || value.getMetadata() == null ? null : value.getMetadata().getContinue();
  }

  private static void requireComplete(String kind, String continuation, List<?> items, int maximum) {
    if (items == null || items.size() > maximum
        || (continuation != null && !continuation.isBlank())) {
      throw new IllegalArgumentException("Kubernetes " + kind + " inventory exceeds the reporting limit");
    }
  }

  private static String sourceId(String clusterId) {
    if (clusterId == null || !CLUSTER_ID.matcher(clusterId).matches()) {
      throw new IllegalArgumentException("Kubernetes cluster identity is invalid");
    }
    return "kubernetes:" + clusterId;
  }

  private static String validateNamespace(String namespace) {
    if (namespace == null || namespace.length() > 63 || !DNS_LABEL.matcher(namespace).matches()) {
      throw new IllegalArgumentException("Kubernetes namespace must be a DNS label");
    }
    return namespace;
  }

  public record NamespaceSnapshot(String sourceId, Instant observedAt,
                                  List<NamespaceObservation> namespaces) {}
  public record NamespaceObservation(String name, String phase) {}
  public record NamespaceDetailSnapshot(String sourceId, String namespace, Instant observedAt,
      List<ServiceObservation> services, List<IngressObservation> ingresses,
      List<PodObservation> pods) {}
  public record ServiceObservation(String name, String type, List<String> clusterIps,
                                   String externalName, List<ServicePortObservation> ports) {}
  public record ServicePortObservation(String name, int port, String protocol, String targetPort) {}
  public record IngressObservation(String name, String ingressClassName,
      List<String> loadBalancerAddresses, List<IngressRouteObservation> routes) {}
  public record IngressRouteObservation(String host, String path, String pathType,
      String serviceName, Integer servicePort, String servicePortName) {}
  public record PodObservation(String name, String phase, boolean ready, String podIp,
                               String nodeName, String createdAt,
                               List<ContainerObservation> containers) {}
  public record ContainerObservation(String name, String image, boolean ready, int restartCount,
                                     List<ContainerPortObservation> ports) {}
  public record ContainerPortObservation(String name, int containerPort, String protocol) {}

  public static final class NamespaceNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
  }
}
