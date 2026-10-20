package io.nanpanel.cluster;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServiceSpec;
import io.fabric8.kubernetes.api.model.discovery.v1.Endpoint;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointPort;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSliceList;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Read-only, namespace-scoped discovery of Kubernetes Services and EndpointSlices. */
public final class KubernetesServiceDiscovery {
  private static final int MAX_SERVICES = 512;
  private static final int MAX_ENDPOINT_SLICES = 1024;
  private static final int MAX_ENDPOINTS = 4096;
  private static final int MAX_ADDRESSES_PER_ENDPOINT = 8;
  private static final int MAX_PORTS_PER_SERVICE = 128;
  private static final Pattern DNS_LABEL =
      Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern DNS_NAME =
      Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*");
  private static final Pattern CLUSTER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

  public Snapshot discover(KubernetesClient client, String clusterId, String namespace) {
    Objects.requireNonNull(client, "client");
    String validClusterId = validateClusterId(clusterId);
    String validNamespace = validateNamespace(namespace);
    ServiceList serviceList = client.services().inNamespace(validNamespace)
        .list(new ListOptionsBuilder().withLimit((long) MAX_SERVICES + 1).build());
    EndpointSliceList endpointSliceList = client.discovery().v1().endpointSlices()
        .inNamespace(validNamespace)
        .list(new ListOptionsBuilder().withLimit((long) MAX_ENDPOINT_SLICES + 1).build());
    if (serviceList == null || endpointSliceList == null) {
      throw new IllegalArgumentException("Kubernetes returned an empty inventory response");
    }
    requireCompleteList("Service", serviceList.getMetadata() == null
        ? null : serviceList.getMetadata().getContinue(), serviceList.getItems(), MAX_SERVICES);
    requireCompleteList("EndpointSlice", endpointSliceList.getMetadata() == null
        ? null : endpointSliceList.getMetadata().getContinue(),
        endpointSliceList.getItems(), MAX_ENDPOINT_SLICES);
    return buildSnapshot(validClusterId, validNamespace, serviceList.getItems(),
        endpointSliceList.getItems(), Instant.now());
  }

  static Snapshot buildSnapshot(String clusterId, String namespace, List<Service> services,
                                List<EndpointSlice> endpointSlices, Instant observedAt) {
    String validClusterId = validateClusterId(clusterId);
    String validNamespace = validateNamespace(namespace);
    Objects.requireNonNull(services, "services");
    Objects.requireNonNull(endpointSlices, "endpointSlices");
    Objects.requireNonNull(observedAt, "observedAt");
    if (services.size() > MAX_SERVICES || endpointSlices.size() > MAX_ENDPOINT_SLICES) {
      throw new IllegalArgumentException("Kubernetes inventory exceeds the reporting limit");
    }

    Map<String, Service> serviceByName = new HashMap<>();
    for (Service service : services) {
      String name = metadataName(service, validNamespace, "Service");
      if (!serviceByName.containsKey(name)) {
        serviceByName.put(name, service);
      } else {
        throw new IllegalArgumentException("Kubernetes Service names are not unique");
      }
    }

    Map<String, List<EndpointObservation>> endpointsByService = new HashMap<>();
    int endpointCount = 0;
    for (EndpointSlice slice : endpointSlices) {
      requireSliceNamespace(slice, validNamespace);
      Map<String, String> labels = slice.getMetadata().getLabels();
      String serviceName = labels == null ? null : labels.get("kubernetes.io/service-name");
      if (serviceName == null || !serviceByName.containsKey(serviceName)) {
        continue;
      }
      String addressType = slice.getAddressType();
      if (!List.of("IPv4", "IPv6", "FQDN").contains(addressType)) {
        throw new IllegalArgumentException("EndpointSlice address type is unsupported");
      }
      List<EndpointPortObservation> ports = endpointPorts(slice.getPorts());
      for (Endpoint endpoint : nullToEmpty(slice.getEndpoints())) {
        List<EndpointObservation> observations = endpointObservations(
            endpoint, addressType, validNamespace, ports);
        endpointCount += observations.size();
        if (endpointCount > MAX_ENDPOINTS) {
          throw new IllegalArgumentException("Kubernetes endpoint count exceeds the reporting limit");
        }
        endpointsByService.computeIfAbsent(serviceName, ignored -> new ArrayList<>())
            .addAll(observations);
      }
    }

    List<ServiceObservation> observations = new ArrayList<>(serviceByName.size());
    for (Service service : serviceByName.values()) {
      String name = service.getMetadata().getName();
      ServiceSpec spec = service.getSpec();
      if (spec == null) {
        throw new IllegalArgumentException("Kubernetes Service spec is missing");
      }
      String type = validServiceType(spec.getType());
      List<String> clusterIps = serviceClusterIps(spec);
      String externalName = optionalDnsName(spec.getExternalName());
      if ("ExternalName".equals(type) != (externalName != null)) {
        throw new IllegalArgumentException("Kubernetes ExternalName service identity is invalid");
      }
      List<ServicePortObservation> servicePorts = servicePorts(spec.getPorts());
      List<EndpointObservation> endpoints = endpointsByService.getOrDefault(name, List.of())
          .stream().distinct()
          .sorted(Comparator.comparing(EndpointObservation::address)
              .thenComparing(value -> value.targetName() == null ? "" : value.targetName())
              .thenComparing(value -> value.ports().toString()))
          .toList();
      observations.add(new ServiceObservation(name, type, clusterIps, externalName,
          servicePorts, endpoints));
    }
    observations.sort(Comparator.comparing(ServiceObservation::serviceName));
    return new Snapshot("kubernetes:" + validClusterId, validNamespace, observedAt,
        observations);
  }

  private static void requireCompleteList(String resource, String continuation,
                                          List<?> items, int maximum) {
    if (items == null || items.size() > maximum ||
        (continuation != null && !continuation.isBlank())) {
      throw new IllegalArgumentException("Kubernetes " + resource + " inventory exceeds the reporting limit");
    }
  }

  private static String metadataName(Service service, String namespace, String kind) {
    if (service == null || service.getMetadata() == null ||
        !namespace.equals(service.getMetadata().getNamespace())) {
      throw new IllegalArgumentException("Kubernetes " + kind + " namespace is invalid");
    }
    String name = service.getMetadata().getName();
    if (name == null || name.length() > 63 || !DNS_LABEL.matcher(name).matches()) {
      throw new IllegalArgumentException("Kubernetes " + kind + " name is invalid");
    }
    return name;
  }

  private static void requireSliceNamespace(EndpointSlice slice, String namespace) {
    if (slice == null || slice.getMetadata() == null ||
        !namespace.equals(slice.getMetadata().getNamespace())) {
      throw new IllegalArgumentException("Kubernetes EndpointSlice namespace is invalid");
    }
  }

  private static List<ServicePortObservation> servicePorts(List<ServicePort> ports) {
    if (ports != null && ports.size() > MAX_PORTS_PER_SERVICE) {
      throw new IllegalArgumentException("Kubernetes Service port count exceeds the reporting limit");
    }
    List<ServicePortObservation> result = new ArrayList<>();
    for (ServicePort port : nullToEmpty(ports)) {
      if (port == null || port.getPort() == null || !validPort(port.getPort())) {
        throw new IllegalArgumentException("Kubernetes Service port is invalid");
      }
      String name = optionalPortName(port.getName());
      String protocol = validProtocol(port.getProtocol());
      String target = targetPort(port.getTargetPort(), port.getPort());
      String appProtocol = optionalToken(port.getAppProtocol(), 255);
      result.add(new ServicePortObservation(name, protocol, port.getPort(), target, appProtocol));
    }
    result.sort(Comparator.comparingInt(ServicePortObservation::port)
        .thenComparing(ServicePortObservation::protocol));
    return List.copyOf(result);
  }

  private static List<EndpointPortObservation> endpointPorts(List<EndpointPort> ports) {
    if (ports != null && ports.size() > MAX_PORTS_PER_SERVICE) {
      throw new IllegalArgumentException("Kubernetes EndpointSlice port count exceeds the reporting limit");
    }
    List<EndpointPortObservation> result = new ArrayList<>();
    for (EndpointPort port : nullToEmpty(ports)) {
      if (port == null || (port.getPort() != null && !validPort(port.getPort()))) {
        throw new IllegalArgumentException("Kubernetes EndpointSlice port is invalid");
      }
      result.add(new EndpointPortObservation(optionalPortName(port.getName()),
          validProtocol(port.getProtocol()), port.getPort(),
          optionalToken(port.getAppProtocol(), 255)));
    }
    result.sort(Comparator.comparing((EndpointPortObservation value) ->
            value.port() == null ? 0 : value.port())
        .thenComparing(EndpointPortObservation::protocol));
    return List.copyOf(result);
  }

  private static List<EndpointObservation> endpointObservations(Endpoint endpoint, String addressType,
      String namespace, List<EndpointPortObservation> ports) {
    if (endpoint == null || endpoint.getAddresses() == null || endpoint.getAddresses().isEmpty() ||
        endpoint.getAddresses().size() > MAX_ADDRESSES_PER_ENDPOINT) {
      throw new IllegalArgumentException("Kubernetes EndpointSlice address list is invalid");
    }
    var conditions = endpoint.getConditions();
    var target = endpoint.getTargetRef();
    String targetKind = null;
    String targetName = null;
    String targetNamespace = null;
    if (target != null && "Pod".equals(target.getKind())) {
      targetNamespace = target.getNamespace() == null ? namespace : target.getNamespace();
      if (!namespace.equals(targetNamespace)) {
        throw new IllegalArgumentException("Kubernetes Pod endpoint crosses namespace boundary");
      }
      targetKind = "Pod";
      targetName = optionalDnsName(target.getName());
      if (targetName == null) {
        throw new IllegalArgumentException("Kubernetes Pod endpoint identity is missing");
      }
    }
    String nodeName = optionalDnsName(endpoint.getNodeName());
    String zone = optionalToken(endpoint.getZone(), 253);
    List<EndpointObservation> result = new ArrayList<>();
    for (String address : endpoint.getAddresses()) {
      if (!validEndpointAddress(addressType, address)) {
        throw new IllegalArgumentException("Kubernetes endpoint address is invalid");
      }
      result.add(new EndpointObservation(addressType, address,
          conditions == null ? null : conditions.getReady(),
          conditions == null ? null : conditions.getServing(),
          conditions == null ? null : conditions.getTerminating(),
          targetKind, targetName, targetNamespace, nodeName, zone, ports));
    }
    return List.copyOf(result);
  }

  private static String targetPort(IntOrString targetPort, int servicePort) {
    if (targetPort == null) {
      return Integer.toString(servicePort);
    }
    if (targetPort.getIntVal() != null) {
      if (!validPort(targetPort.getIntVal())) {
        throw new IllegalArgumentException("Kubernetes numeric targetPort is invalid");
      }
      return Integer.toString(targetPort.getIntVal());
    }
    String name = targetPort.getStrVal();
    if (name == null || !name.matches("[a-z][a-z0-9-]{0,14}")) {
      throw new IllegalArgumentException("Kubernetes named targetPort is invalid");
    }
    return name;
  }

  private static String validServiceType(String type) {
    String value = type == null || type.isBlank() ? "ClusterIP" : type;
    if (!List.of("ClusterIP", "NodePort", "LoadBalancer", "ExternalName").contains(value)) {
      throw new IllegalArgumentException("Kubernetes Service type is unsupported");
    }
    return value;
  }

  private static String optionalClusterIp(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    if ("None".equals(value)) {
      return value;
    }
    if (!validIpAddress(value)) {
      throw new IllegalArgumentException("Kubernetes Service clusterIP is invalid");
    }
    return value;
  }

  private static List<String> serviceClusterIps(ServiceSpec spec) {
    List<String> values = spec.getClusterIPs();
    if (values == null || values.isEmpty()) {
      String legacyValue = optionalClusterIp(spec.getClusterIP());
      return legacyValue == null ? List.of() : List.of(legacyValue);
    }
    if (values.size() > 2) {
      throw new IllegalArgumentException("Kubernetes Service clusterIP count is invalid");
    }
    List<String> result = new ArrayList<>(values.size());
    for (String value : values) {
      String clusterIp = optionalClusterIp(value);
      if (clusterIp == null) {
        throw new IllegalArgumentException("Kubernetes Service clusterIP is missing");
      }
      if (!result.contains(clusterIp)) {
        result.add(clusterIp);
      }
    }
    return List.copyOf(result);
  }

  private static String optionalDnsName(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    if (value.length() > 253 || !DNS_NAME.matcher(value).matches()) {
      throw new IllegalArgumentException("Kubernetes DNS identity is invalid");
    }
    return value;
  }

  private static String optionalPortName(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    if (!value.matches("[a-z][a-z0-9-]{0,14}")) {
      throw new IllegalArgumentException("Kubernetes port name is invalid");
    }
    return value;
  }

  private static String optionalToken(String value, int maximum) {
    if (value == null || value.isBlank()) {
      return null;
    }
    if (value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Kubernetes inventory text value is invalid");
    }
    return value;
  }

  private static String validProtocol(String protocol) {
    String value = protocol == null || protocol.isBlank() ? "TCP" : protocol;
    if (!List.of("TCP", "UDP", "SCTP").contains(value)) {
      throw new IllegalArgumentException("Kubernetes port protocol is unsupported");
    }
    return value;
  }

  private static boolean validEndpointAddress(String addressType, String address) {
    if (address == null || address.isBlank() || address.length() > 253) {
      return false;
    }
    return switch (addressType) {
      case "IPv4", "IPv6" -> validIpAddress(address) &&
          ("IPv4".equals(addressType) == (address.indexOf(':') < 0));
      case "FQDN" -> DNS_NAME.matcher(address).matches();
      default -> false;
    };
  }

  private static boolean validIpAddress(String value) {
    if (value.indexOf(':') < 0) {
      String[] octets = value.split("\\.", -1);
      if (octets.length != 4) {
        return false;
      }
      for (String octet : octets) {
        if (!octet.matches("0|[1-9][0-9]{0,2}")) {
          return false;
        }
        try {
          if (Integer.parseInt(octet) > 255) {
            return false;
          }
        } catch (NumberFormatException e) {
          return false;
        }
      }
      return true;
    }
    if (!value.matches("[A-Fa-f0-9:.]+")) {
      return false;
    }
    try {
      return InetAddress.getByName(value) instanceof Inet6Address;
    } catch (UnknownHostException e) {
      return false;
    }
  }

  private static boolean validPort(int value) {
    return value >= 1 && value <= 65535;
  }

  private static String validateNamespace(String namespace) {
    if (namespace == null || namespace.length() > 63 || !DNS_LABEL.matcher(namespace).matches()) {
      throw new IllegalArgumentException("Kubernetes namespace must be a DNS label");
    }
    return namespace;
  }

  private static String validateClusterId(String clusterId) {
    if (clusterId == null || !CLUSTER_ID.matcher(clusterId).matches()) {
      throw new IllegalArgumentException("Kubernetes cluster identity is invalid");
    }
    return clusterId;
  }

  private static <T> List<T> nullToEmpty(List<T> values) {
    return values == null ? List.of() : values;
  }

  public record Snapshot(String sourceId, String namespace, Instant observedAt,
                         List<ServiceObservation> services) {
    public Snapshot {
      services = List.copyOf(services);
    }
  }

  public record ServiceObservation(String serviceName, String type, List<String> clusterIps,
                                   String externalName,
                                   List<ServicePortObservation> ports,
                                   List<EndpointObservation> endpoints) {
    public ServiceObservation {
      clusterIps = List.copyOf(clusterIps);
      ports = List.copyOf(ports);
      endpoints = List.copyOf(endpoints);
    }
  }

  public record ServicePortObservation(String name, String protocol, int port,
                                       String targetPort, String appProtocol) {}

  public record EndpointPortObservation(String name, String protocol, Integer port,
                                        String appProtocol) {}

  public record EndpointObservation(String addressType, String address, Boolean ready,
                                    Boolean serving, Boolean terminating, String targetKind,
                                    String targetName, String targetNamespace, String nodeName,
                                    String zone, List<EndpointPortObservation> ports) {
    public EndpointObservation {
      ports = List.copyOf(ports);
    }
  }
}
