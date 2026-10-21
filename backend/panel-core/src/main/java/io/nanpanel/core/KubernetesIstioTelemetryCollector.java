package io.nanpanel.core;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.nanpanel.cluster.KubernetesServiceDiscovery.EndpointObservation;
import io.nanpanel.cluster.KubernetesServiceDiscovery.IstioTelemetry;
import io.nanpanel.cluster.KubernetesServiceDiscovery.IstioTrafficEdge;
import io.nanpanel.cluster.KubernetesServiceDiscovery.ServiceObservation;
import io.nanpanel.cluster.KubernetesServiceDiscovery.Snapshot;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads bounded Istio Envoy metrics from private Pod IPs selected by the Kubernetes API. */
@ApplicationScoped
public class KubernetesIstioTelemetryCollector {
  private static final int MAX_PODS = 1024;
  private static final int MAX_SCRAPES = 24;
  private static final int MAX_METRIC_BYTES = 2 * 1024 * 1024;
  private static final int TIMEOUT_MILLIS = 500;
  private static final int MAX_TRAFFIC_EDGES = 1024;
  private static final Pattern METRIC_LINE = Pattern.compile(
      "^istio_requests_total\\{(.*)}\\s+([0-9.eE+-]+)(?:\\s+\\d+)?$");
  private static final Pattern LABEL = Pattern.compile(
      "([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:\\\\.|[^\"\\\\])*)\"");
  private static final Pattern DNS_NAME = Pattern.compile(
      "[A-Za-z0-9](?:[A-Za-z0-9_.-]{0,251}[A-Za-z0-9])?");

  public Snapshot collect(KubernetesClient client, Snapshot snapshot) {
    PodList podList = client.pods().inNamespace(snapshot.namespace())
        .list(new ListOptionsBuilder().withLimit((long) MAX_PODS + 1).build());
    if (podList == null || podList.getItems() == null
        || podList.getItems().size() > MAX_PODS
        || (podList.getMetadata() != null
        && podList.getMetadata().getContinue() != null
        && !podList.getMetadata().getContinue().isBlank())) {
      throw new IllegalStateException("Istio workload list is incomplete or exceeds its limit");
    }

    Map<String, PodObservation> podsByName = new HashMap<>();
    List<ProxyTarget> sidecars = new ArrayList<>();
    for (Pod pod : podList.getItems()) {
      if (pod == null || pod.getMetadata() == null
          || !snapshot.namespace().equals(pod.getMetadata().getNamespace())) continue;
      String name = pod.getMetadata().getName();
      if (name == null || !DNS_NAME.matcher(name).matches()) continue;
      PodObservation observation = observePod(pod);
      if (podsByName.putIfAbsent(name, observation) != null) {
        throw new IllegalStateException("Kubernetes Pod names are not unique in the namespace");
      }
      if (observation.proxyPresent()) sidecars.add(new ProxyTarget(name,
          observation.podIp(), observation.proxyReady()));
    }

    List<ServiceObservation> services = snapshot.services().stream().map(service ->
        withProxyReadiness(service, podsByName)).toList();
    long readySidecars = sidecars.stream().filter(ProxyTarget::ready).count();
    List<ProxyTarget> readyTargets = sidecars.stream()
        .filter(target -> target.ready() && privatePodAddress(target.address()))
        .sorted(Comparator.comparing(ProxyTarget::podName)).toList();
    int scrapeCount = Math.min(MAX_SCRAPES, readyTargets.size());
    Map<TrafficKey, Long> counts = new HashMap<>();
    int scrapeSuccesses = 0;
    Set<String> serviceNames = new HashSet<>();
    services.forEach(service -> serviceNames.add(service.serviceName()));
    for (int index = 0; index < scrapeCount; index++) {
      ProxyTarget target = readyTargets.get(index);
      try {
        String body = readMetrics(target.address());
        scrapeSuccesses++;
        collectRequestCounters(body, snapshot.namespace(), serviceNames, counts);
      } catch (IOException | RuntimeException ignored) {
        // One unavailable proxy must not erase the rest of the live namespace inventory.
      }
    }

    String status;
    if (sidecars.isEmpty()) status = "NOT_INSTALLED";
    else if (readySidecars == 0) status = "NOT_READY";
    else if (readyTargets.isEmpty() || scrapeSuccesses == 0) status = "UNAVAILABLE";
    else if (scrapeSuccesses == readyTargets.size() && readyTargets.size() == sidecars.size()
        && sidecars.size() <= MAX_SCRAPES) status = "LIVE";
    else status = "PARTIAL";
    List<IstioTrafficEdge> traffic = trafficEdges(counts);
    return new Snapshot(snapshot.sourceId(), snapshot.namespace(), snapshot.observedAt(), services,
        new IstioTelemetry(status, Instant.now(), sidecars.size(), Math.toIntExact(readySidecars),
            scrapeSuccesses, traffic));
  }

  static List<IstioTrafficEdge> trafficEdges(Map<TrafficKey, Long> counts) {
    return counts.entrySet().stream()
        .sorted(Map.Entry.<TrafficKey, Long>comparingByValue().reversed()
            .thenComparing(entry -> entry.getKey().destinationService())
            .thenComparing(entry -> entry.getKey().sourceWorkload())
            .thenComparing(entry -> entry.getKey().responseCode()))
        .limit(MAX_TRAFFIC_EDGES)
        .map(entry -> {
          TrafficKey key = entry.getKey();
          return new IstioTrafficEdge(key.sourceWorkload(), key.sourceNamespace(),
              key.destinationWorkload(), key.destinationService(), key.responseCode(),
              key.securityPolicy(), entry.getValue());
        }).toList();
  }

  static PodObservation observePod(Pod pod) {
    var spec = pod.getSpec();
    var status = pod.getStatus();
    String podIp = status == null ? null : status.getPodIP();
    Container proxy = spec == null ? null : spec.getContainers() == null ? null
        : spec.getContainers().stream()
            .filter(container -> container != null && "istio-proxy".equals(container.getName()))
            .findFirst().orElse(null);
    boolean nativeSidecar = false;
    if (proxy == null && spec != null && spec.getInitContainers() != null) {
      proxy = spec.getInitContainers().stream().filter(container -> container != null
          && "istio-proxy".equals(container.getName())
          && "Always".equals(container.getRestartPolicy())).findFirst().orElse(null);
      nativeSidecar = proxy != null;
    }
    if (proxy == null) return new PodObservation(podIp, false, false, null);
    String version = imageVersion(proxy.getImage());
    boolean ready = status != null && (nativeSidecar
        ? isReady(status.getInitContainerStatuses())
        : isReady(status.getContainerStatuses()));
    return new PodObservation(podIp, true, ready, version);
  }

  private static boolean isReady(List<ContainerStatus> statuses) {
    return statuses != null && statuses.stream().filter(java.util.Objects::nonNull)
        .anyMatch(containerStatus -> "istio-proxy".equals(containerStatus.getName())
            && Boolean.TRUE.equals(containerStatus.getReady()));
  }

  private static String imageVersion(String image) {
    if (image == null || image.isBlank()) return null;
    String name = image.substring(0, image.indexOf('@') < 0 ? image.length() : image.indexOf('@'));
    int separator = name.lastIndexOf(':');
    return separator > name.lastIndexOf('/') ? name.substring(separator + 1) : null;
  }

  private static ServiceObservation withProxyReadiness(ServiceObservation service,
      Map<String, PodObservation> podsByName) {
    List<EndpointObservation> endpoints = service.endpoints().stream().map(endpoint -> {
      if (!"Pod".equals(endpoint.targetKind()) || endpoint.targetName() == null) return endpoint;
      PodObservation pod = podsByName.get(endpoint.targetName());
      if (pod == null) return new EndpointObservation(endpoint.addressType(), endpoint.address(),
          endpoint.ready(), endpoint.serving(), endpoint.terminating(), endpoint.targetKind(),
          endpoint.targetName(), endpoint.targetNamespace(), endpoint.nodeName(), endpoint.zone(),
          endpoint.ports(), "UNKNOWN", null);
      String proxyStatus = !pod.proxyPresent() ? "NOT_INJECTED"
          : pod.proxyReady() ? "READY" : "NOT_READY";
      return new EndpointObservation(endpoint.addressType(), endpoint.address(), endpoint.ready(),
          endpoint.serving(), endpoint.terminating(), endpoint.targetKind(), endpoint.targetName(),
          endpoint.targetNamespace(), endpoint.nodeName(), endpoint.zone(), endpoint.ports(),
          proxyStatus, pod.proxyVersion());
    }).toList();
    return new ServiceObservation(service.serviceName(), service.type(), service.clusterIps(),
        service.externalName(), service.ports(), endpoints);
  }

  private static String readMetrics(String address) throws IOException {
    String host = address.contains(":") ? "[" + address + "]" : address;
    URI uri = URI.create("http://" + host + ":15090/stats/prometheus");
    HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
    connection.setConnectTimeout(TIMEOUT_MILLIS);
    connection.setReadTimeout(TIMEOUT_MILLIS);
    connection.setInstanceFollowRedirects(false);
    connection.setRequestMethod("GET");
    connection.setRequestProperty("Accept", "text/plain; version=0.0.4");
    connection.setRequestProperty("Accept-Encoding", "identity");
    try {
      if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
        throw new IOException("Istio proxy returned a non-success status");
      }
      int contentLength = connection.getContentLength();
      if (contentLength > MAX_METRIC_BYTES) throw new IOException("Istio metrics exceed the size limit");
      try (var input = connection.getInputStream();
           var output = new ByteArrayOutputStream(Math.max(contentLength, 1024))) {
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
          total += read;
          if (total > MAX_METRIC_BYTES) throw new IOException("Istio metrics exceed the size limit");
          output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8);
      }
    } finally {
      connection.disconnect();
    }
  }

  static void collectRequestCounters(String metrics, String namespace,
      Set<String> serviceNames, Map<TrafficKey, Long> counts) {
    for (String line : metrics.split("\\n")) {
      Matcher metric = METRIC_LINE.matcher(line);
      if (!metric.matches()) continue;
      Map<String, String> labels = labels(metric.group(1));
      if (!"destination".equals(labels.get("reporter"))
          || !namespace.equals(labels.get("destination_service_namespace"))) continue;
      String destinationService = validName(labels.get("destination_service_name"));
      if (destinationService == null || !serviceNames.contains(destinationService)) continue;
      String sourceNamespace = validName(labels.get("source_workload_namespace"));
      String sourceWorkload = validName(labels.get("source_workload"));
      String destinationWorkload = validName(labels.get("destination_workload"));
      String responseCode = validResponseCode(labels.get("response_code"));
      String securityPolicy = validSecurityPolicy(labels.get("connection_security_policy"));
      if (sourceNamespace == null || sourceWorkload == null || destinationWorkload == null
          || responseCode == null) continue;
      double value;
      try {
        value = Double.parseDouble(metric.group(2));
      } catch (NumberFormatException invalid) {
        continue;
      }
      if (!Double.isFinite(value) || value < 0 || value > Long.MAX_VALUE) continue;
      long requestCount = (long)value;
      if (requestCount == 0) continue;
      TrafficKey key = new TrafficKey(sourceWorkload, sourceNamespace, destinationWorkload,
          destinationService, responseCode, securityPolicy);
      counts.merge(key, requestCount, (left, right) ->
          Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right);
    }
  }

  private static Map<String, String> labels(String encoded) {
    Map<String, String> labels = new HashMap<>();
    Matcher matcher = LABEL.matcher(encoded);
    while (matcher.find()) {
      String value = matcher.group(2).replace("\\n", "\n").replace("\\\"", "\"")
          .replace("\\\\", "\\");
      if (labels.putIfAbsent(matcher.group(1), value) != null) return Map.of();
    }
    return labels;
  }

  private static String validName(String value) {
    return value != null && value.length() <= 253 && DNS_NAME.matcher(value).matches()
        ? value : null;
  }

  private static String validResponseCode(String value) {
    if (value == null || !value.matches("[1-5][0-9]{2}")) return null;
    return value;
  }

  private static String validSecurityPolicy(String value) {
    if ("mutual_tls".equals(value)) return "MUTUAL_TLS";
    if ("none".equals(value) || "".equals(value)) return "PLAINTEXT";
    return "UNKNOWN";
  }

  private static boolean privatePodAddress(String value) {
    if (value == null || value.isBlank()
        || !value.matches("[0-9a-fA-F:.]+")) return false;
    try {
      InetAddress address = InetAddress.getByName(value);
      if (address.isAnyLocalAddress() || address.isLoopbackAddress()
          || address.isLinkLocalAddress() || address.isMulticastAddress()) return false;
      if (address instanceof Inet6Address) {
        return (address.getAddress()[0] & 0xfe) == 0xfc;
      }
      return address.isSiteLocalAddress();
    } catch (IOException invalid) {
      return false;
    }
  }

  record PodObservation(String podIp, boolean proxyPresent, boolean proxyReady,
                        String proxyVersion) {}
  private record ProxyTarget(String podName, String address, boolean ready) {}
  record TrafficKey(String sourceWorkload, String sourceNamespace,
                    String destinationWorkload, String destinationService,
                    String responseCode, String securityPolicy) {}
}
