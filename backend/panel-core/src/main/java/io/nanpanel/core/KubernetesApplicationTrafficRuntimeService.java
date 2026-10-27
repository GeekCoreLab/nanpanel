package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthResource.number;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Reconciles weighted application HTTP routes through an explicitly enabled Istio mesh. */
@ApplicationScoped
public class KubernetesApplicationTrafficRuntimeService {
  private static final String ISTIO_API = "networking.istio.io/v1";
  private static final String APP_LABEL = "nanpanel.io/application-id";
  private static final String MANAGED_LABEL = "app.kubernetes.io/managed-by";
  private static final String TRAFFIC_LABEL = "nanpanel.io/application-traffic-runtime";

  @Inject AuthStore store;
  @Inject KubernetesClusterRegistryService clusters;
  @Inject ObjectMapper mapper;

  public record RuntimeView(String applicationId, String revisionId, long generation,
      String state, String detail, Instant updatedAt, Instant appliedAt) {}
  private record App(UUID id, UUID clusterId, String namespace, String state) {}
  private record RoutePlan(String name, JsonNode resource) {}
  private record CreatedRoute(String name, String uid) {}
  private record RuntimeSnapshot(String namespace, String revisionId, long generation,
      List<RoutePlan> routes) {}
  private record RouteEntry(String serviceId, int port) {}
  private record Backend(String serviceName, int port, int weight, String outputPort) {}

  public RuntimeView apply(String actor, String remoteAddress, UUID applicationId) {
    App app = app(applicationId);
    if (!"DEPLOYED".equals(app.state())) {
      throw fail(409, "KUBERNETES_APPLICATION_NOT_DEPLOYED", "只有已部署应用可以启用 Service 流量路由");
    }
    Map<String, Object> head = topology(applicationId);
    UUID revisionId = uuid(head.get("revision_id"));
    long generation = number(head, "generation");
    if (generation < 1) throw fail(409, "APPLICATION_NETWORK_TOPOLOGY_EMPTY",
        "请先保存 Service 调用边和加权后端 Service 路由");

    RuntimeSnapshot snapshot;
    try (KubernetesClient client = clusters.openClientForOperations(app.clusterId())) {
      snapshot = plan(app, revisionId, generation, String.valueOf(head.get("snapshot")), client);
    }
    persistApply(actor, remoteAddress, applicationId, revisionId, generation, snapshot);
    List<CreatedRoute> createdRoutes = new ArrayList<>();
    try (KubernetesClient client = clusters.openClientForOperations(app.clusterId())) {
      for (RoutePlan route : snapshot.routes()) {
        GenericKubernetesResource current = virtualService(client, app.namespace(), route.name());
        if (current != null) throw fail(409, "APPLICATION_TRAFFIC_RESOURCE_CONFLICT",
            "Istio VirtualService/" + route.name() + " 已存在，NaNPanel 不会接管现有路由");
        GenericKubernetesResource desired = mapper.convertValue(route.resource(), GenericKubernetesResource.class);
        GenericKubernetesResource created = client.genericKubernetesResources(ISTIO_API, "VirtualService")
            .inNamespace(app.namespace()).resource(desired).create();
        String uid = created.getMetadata() == null ? null : created.getMetadata().getUid();
        createdRoutes.add(new CreatedRoute(route.name(), uid));
        if (uid == null || uid.isBlank()) throw fail(502,
            "APPLICATION_TRAFFIC_RESOURCE_UID_MISSING", "Istio VirtualService 创建后没有返回 UID");
        registerResource(applicationId, route.name(), uid);
      }
      boolean verified = verifySnapshot(client, applicationId, snapshot);
      if (!verified) throw fail(502, "APPLICATION_TRAFFIC_VERIFY_FAILED",
          "Istio VirtualService 创建成功但 Verify 未通过");
      markState(applicationId, "APPLIED",
          "Istio 已应用 HTTP 加权路由；只影响被网格 sidecar 接管且符合来源选择器的请求", true);
    } catch (RuntimeException failure) {
      boolean cleaned = cleanupCreated(applicationId, app.namespace(), app.clusterId(), createdRoutes);
      markState(applicationId, cleaned ? "FAILED" : "PARTIAL",
          cleaned ? safeDetail(failure) : "Apply 失败且无法确认所有新建 VirtualService 已删除；请执行 Compensate");
      throw failure;
    }
    auditAction(actor, remoteAddress, applicationId, "SUCCEEDED",
        "已应用应用 Service 的 Istio HTTP 加权路由 Revision " + revisionId);
    return get(applicationId);
  }

  public RuntimeView verify(String actor, String remoteAddress, UUID applicationId) {
    Map<String, Object> row = runtime(applicationId);
    if (!Set.of("APPLIED", "VERIFY_FAILED").contains(String.valueOf(row.get("state")))) {
      throw fail(409, "APPLICATION_TRAFFIC_RUNTIME_STATE_CONFLICT",
          "只有已应用或验证失败的 Service 路由可以重新 Verify");
    }
    RuntimeSnapshot snapshot = snapshot(row);
    boolean verified;
    try (KubernetesClient client = clusters.openClientForOperations(app(applicationId).clusterId())) {
      verified = verifySnapshot(client, applicationId, snapshot);
    }
    markState(applicationId, verified ? "APPLIED" : "VERIFY_FAILED",
        verified ? "Istio VirtualService UID、所有权标签和路由规则验证通过"
            : "Istio VirtualService 缺失、所有权变化或规则与当前 Revision 不一致");
    auditAction(actor, remoteAddress, applicationId, verified ? "SUCCEEDED" : "FAILED",
        verified ? "验证应用 Service 流量路由" : "应用 Service 流量路由验证失败");
    return get(applicationId);
  }

  public RuntimeView compensate(String actor, String remoteAddress, UUID applicationId) {
    Map<String, Object> row = runtime(applicationId);
    if (!Set.of("APPLYING", "APPLIED", "VERIFY_FAILED", "FAILED", "PARTIAL").contains(
        String.valueOf(row.get("state")))) {
      throw fail(409, "APPLICATION_TRAFFIC_RUNTIME_STATE_CONFLICT",
          "当前 Service 路由状态不能开始 Compensate");
    }
    markState(applicationId, "COMPENSATING", "正在删除本应用创建且 UID 匹配的 Istio VirtualService");
    boolean removed = cleanupCreated(applicationId, app(applicationId).namespace(),
        app(applicationId).clusterId());
    markState(applicationId, removed ? "COMPENSATED" : "PARTIAL",
        removed ? "NaNPanel 管理的 Istio VirtualService 已删除，原生 Service 路由已恢复"
            : "有 Istio VirtualService 的 UID 或所有权校验未通过；资源保留供检查");
    auditAction(actor, remoteAddress, applicationId, removed ? "SUCCEEDED" : "FAILED",
        "Compensate 应用 Service 流量路由");
    return get(applicationId);
  }

  public RuntimeView get(UUID applicationId) {
    Map<String, Object> row = store.transaction(connection -> query(connection,
        "SELECT * FROM kubernetes_application_traffic_runtime WHERE application_id=?",
        applicationId).stream().findFirst().orElse(null));
    if (row == null) throw fail(404, "APPLICATION_TRAFFIC_RUNTIME_NOT_FOUND",
        "此应用尚无 Service 流量路由运行时记录");
    return view(row);
  }

  private RuntimeSnapshot plan(App app, UUID revisionId, long generation, String json,
      KubernetesClient client) {
    preflightMesh(client, app.namespace());
    JsonNode graph;
    try {
      graph = mapper.readTree(json);
    } catch (IOException failure) {
      throw fail(500, "APPLICATION_NETWORK_TOPOLOGY_CORRUPT", "应用网络拓扑无法读取");
    }
    Map<String, JsonNode> devices = new HashMap<>();
    graph.path("nodes").forEach(node -> devices.put(node.path("id").asText(), node));
    Map<String, RouteEntry> entries = new LinkedHashMap<>();
    Map<String, List<JsonNode>> routeLinks = new LinkedHashMap<>();
    Map<String, Set<String>> callers = new HashMap<>();
    graph.path("trafficLinks").forEach(link -> {
      String type = link.path("type").asText();
      if ("CALL".equals(type)) {
        if ("HTTP".equals(link.path("protocol").asText())) {
          callers.computeIfAbsent(link.path("targetId").asText() + ":"
              + link.path("servicePort").asInt(), ignored -> new HashSet<>())
              .add(link.path("sourceId").asText());
        }
      } else if ("ROUTE_ENTRY".equals(type)) {
        String lbId = link.path("targetId").asText();
          entries.put(lbId, new RouteEntry(link.path("sourceId").asText(),
              link.path("servicePort").asInt()));
      } else if ("ROUTE_BACKEND".equals(type)) {
        routeLinks.computeIfAbsent(link.path("sourceId").asText(), ignored -> new ArrayList<>())
            .add(link);
      }
    });
    if (entries.isEmpty()) throw fail(422, "APPLICATION_TRAFFIC_ROUTE_EMPTY",
        "拓扑中没有 Service → LOAD_BALANCER → Service 路由");

    Map<String, Map<String, Object>> resources = applicationResources(app.id());
    Map<String, Service> liveServices = new HashMap<>();
    Map<String, Deployment> liveDeployments = new HashMap<>();
    Map<String, List<Map<String, Object>>> httpRulesByService = new LinkedHashMap<>();
    Map<String, String> serviceNames = new HashMap<>();
    Set<String> policyKeys = new HashSet<>();
    for (Map.Entry<String, RouteEntry> route : entries.entrySet()) {
      String lbId = route.getKey();
      JsonNode lb = devices.get(lbId);
      RouteEntry entry = route.getValue();
      if (lb == null || !"LOAD_BALANCER".equals(lb.path("kind").asText())) {
        throw fail(422, "APPLICATION_TRAFFIC_ROUTE_UNSUPPORTED", "当前路由 Provider 只支持 HTTP LOAD_BALANCER");
      }
      String entryId = entry.serviceId();
      String entryName = resourceName(entryId, "Service");
      Service entryService = liveServices.computeIfAbsent(entryId,
          ignored -> loadService(client, app, resources, entryName));
      ServicePort entryPort = httpPort(entryService, entry.port());
      serviceNames.put(entryId, entryName);
      String policyKey = entryId + ":" + entry.port();
      if (!policyKeys.add(policyKey)) throw fail(422, "APPLICATION_TRAFFIC_ROUTE_DUPLICATE",
          "同一稳定 Service 端口只能有一个加权路由入口");

      List<JsonNode> outputLinks = routeLinks.getOrDefault(lbId, List.of());
      if (outputLinks.size() < 2) throw fail(422, "APPLICATION_TRAFFIC_BACKENDS_REQUIRED",
          "每个流量分配器至少要连接两个后端 Service");
      Map<String, Integer> configuredWeights = weights(lb.path("loadBalancerConfiguration"));
      List<Backend> backends = new ArrayList<>();
      Set<String> backendServices = new HashSet<>();
      for (JsonNode link : outputLinks) {
        String backendId = link.path("targetId").asText();
        if (entryId.equals(backendId) || !backendServices.add(backendId)) {
          throw fail(422, "APPLICATION_TRAFFIC_ROUTE_LOOP", "稳定 Service 不能作为同一条路由的后端");
        }
        String outputHandle = link.path("sourceHandle").asText();
        String outputPort = outputHandle.startsWith("route-")
            ? outputHandle.substring("route-".length()) : "";
        Integer weight = configuredWeights.get(outputPort);
        if (weight == null) throw fail(422, "APPLICATION_TRAFFIC_WEIGHT_INVALID",
            "后端连线没有匹配到 LOAD_BALANCER 出口权重");
        String backendName = resourceName(backendId, "Service");
        Service backendService = liveServices.computeIfAbsent(backendId,
            ignored -> loadService(client, app, resources, backendName));
        int selectedPort = link.path("servicePort").asInt(-1);
        ServicePort backendPort = httpPort(backendService, selectedPort);
        backends.add(new Backend(backendName, backendPort.getPort(), weight, outputPort));
      }
      Map<String, Integer> normalized = normalize(backends);
      Map<String, Object> match = new LinkedHashMap<>();
      match.put("port", entry.port());
      Set<String> callerIds = callers.getOrDefault(policyKey, Set.of());
      if (!callerIds.isEmpty()) {
        String selectorNamespace = app.namespace();
        match.put("sourceNamespace", selectorNamespace);
        // A VirtualService match block is ORed with the others. Add one block per caller so
        // services that share an entry can retain their own caller scope.
        List<Map<String, Object>> matches = new ArrayList<>();
        Map<String, Map<String, String>> selectors = new LinkedHashMap<>();
        for (String callerId : callerIds.stream().sorted().toList()) {
          String callerName = resourceName(callerId, "Deployment");
          Deployment caller = liveDeployments.computeIfAbsent(callerId,
              ignored -> loadCaller(client, app, resources, callerName));
          Map<String, String> labels = caller.getSpec().getSelector().getMatchLabels();
          if (labels == null || labels.isEmpty()) throw fail(422,
              "APPLICATION_TRAFFIC_CALLER_SELECTOR_INVALID", "调用方 Deployment 缺少 matchLabels");
          for (Map.Entry<String, Map<String, String>> selector : selectors.entrySet()) {
            boolean canOverlap = labels.entrySet().stream().allMatch(label ->
                !selector.getValue().containsKey(label.getKey())
                    || selector.getValue().get(label.getKey()).equals(label.getValue()));
            if (canOverlap) throw fail(422, "APPLICATION_TRAFFIC_CALLER_SELECTOR_AMBIGUOUS",
                "同一稳定 Service 端口的多个调用方 Deployment selector 可能匹配相同 Pod，无法确定路由策略");
          }
          selectors.put(callerId, labels);
          Map<String, Object> callerMatch = new LinkedHashMap<>();
          callerMatch.put("port", entry.port());
          callerMatch.put("sourceNamespace", selectorNamespace);
          Map<String, String> sourceLabels = new LinkedHashMap<>(labels);
          sourceLabels.put(APP_LABEL, app.id().toString());
          callerMatch.put("sourceLabels", sourceLabels);
          matches.add(callerMatch);
        }
        match.clear();
        String routeName = "flow-" + lbId.replace("-", "");
        httpRulesByService.computeIfAbsent(entryId, ignored -> new ArrayList<>()).add(
            Map.of("name", routeName, "match", matches,
                "route", destinations(backends, normalized, app.namespace())));
      } else {
        String routeName = "flow-" + lbId.replace("-", "");
        httpRulesByService.computeIfAbsent(entryId, ignored -> new ArrayList<>()).add(
            Map.of("name", routeName, "match", List.of(match),
                "route", destinations(backends, normalized, app.namespace())));
      }
    }

    List<RoutePlan> plans = new ArrayList<>();
    for (Map.Entry<String, List<Map<String, Object>>> entry : httpRulesByService.entrySet()) {
      String serviceId = entry.getKey();
      String serviceName = serviceNames.get(serviceId);
      String name = routeResourceName(app.id(), serviceName);
      Map<String, Object> desired = Map.of("apiVersion", ISTIO_API, "kind", "VirtualService",
          "metadata", Map.of("name", name, "namespace", app.namespace(),
              "labels", Map.of(APP_LABEL, app.id().toString(), MANAGED_LABEL, "nanpanel",
                  TRAFFIC_LABEL, app.id().toString()),
              "annotations", Map.of("nanpanel.io/topology-revision", revisionId.toString(),
                  "nanpanel.io/topology-generation", String.valueOf(generation))),
          "spec", Map.of("hosts", List.of(host(serviceName, app.namespace())),
              "gateways", List.of("mesh"), "http", entry.getValue()));
      plans.add(new RoutePlan(name, mapper.valueToTree(desired)));
    }
    return new RuntimeSnapshot(app.namespace(), revisionId.toString(), generation, List.copyOf(plans));
  }

  private void preflightMesh(KubernetesClient client, String namespace) {
    try {
      var target = client.namespaces().withName(namespace).get();
      Map<String, String> labels = target == null || target.getMetadata() == null
          ? null : target.getMetadata().getLabels();
      if (labels == null || !("enabled".equals(labels.get("istio-injection"))
          || labels.get("istio.io/rev") != null)) {
        throw fail(422, "APPLICATION_TRAFFIC_MESH_DISABLED",
            "应用 Namespace 未启用 Istio sidecar 注入；NaNPanel 不会自动标记 Namespace 或重启 Pod");
      }
      client.genericKubernetesResources(ISTIO_API, "VirtualService").inNamespace(namespace).list();
    } catch (RuntimeException failure) {
      if (failure instanceof jakarta.ws.rs.WebApplicationException) throw failure;
      throw fail(422, "APPLICATION_TRAFFIC_ISTIO_UNAVAILABLE",
          "需要可读写 networking.istio.io/v1 VirtualService 的 Istio Provider 权限");
    }
  }

  private Map<String, Map<String, Object>> applicationResources(UUID applicationId) {
    List<Map<String, Object>> rows = store.transaction(connection -> query(connection,
        "SELECT resource_kind,resource_name,resource_uid FROM kubernetes_application_resource "
            + "WHERE application_id=?", applicationId));
    Map<String, Map<String, Object>> result = new HashMap<>();
    for (Map<String, Object> row : rows) {
      String id = row.get("resource_kind") + "/" + row.get("resource_name");
      result.put(id, row);
    }
    return result;
  }

  private Service loadService(KubernetesClient client, App app,
      Map<String, Map<String, Object>> resources, String name) {
    String resourceId = "Service/" + name;
    Map<String, Object> record = resources.get(resourceId);
    if (record == null) throw fail(422, "APPLICATION_TRAFFIC_RESOURCE_INVALID",
        resourceId + " 不是当前应用已登记的 Service");
    Service service = client.services().inNamespace(app.namespace()).withName(name).get();
    requireOwned(service, String.valueOf(record.get("resource_uid")), app.id(), resourceId);
    if (service.getSpec() == null || service.getSpec().getPorts() == null
        || service.getSpec().getPorts().isEmpty()
        || "None".equalsIgnoreCase(service.getSpec().getClusterIP())
        || service.getSpec().getExternalName() != null
        || (service.getSpec().getType() != null && !"ClusterIP".equals(service.getSpec().getType()))) {
      throw fail(422, "APPLICATION_TRAFFIC_SERVICE_UNSUPPORTED",
          resourceId + " 必须是具有端口的 ClusterIP Service");
    }
    return service;
  }

  private Deployment loadCaller(KubernetesClient client, App app,
      Map<String, Map<String, Object>> resources, String name) {
    String resourceId = "Deployment/" + name;
    Map<String, Object> record = resources.get(resourceId);
    if (record == null) throw fail(422, "APPLICATION_TRAFFIC_CALLER_INVALID",
        resourceId + " 不是当前应用已登记的 Deployment");
    Deployment deployment = client.apps().deployments().inNamespace(app.namespace()).withName(name).get();
    requireOwned(deployment, String.valueOf(record.get("resource_uid")), app.id(), resourceId);
    if (deployment.getSpec() == null || deployment.getSpec().getSelector() == null
        || deployment.getSpec().getSelector().getMatchLabels() == null
        || deployment.getSpec().getSelector().getMatchLabels().isEmpty()
        || deployment.getSpec().getTemplate() == null
        || deployment.getSpec().getTemplate().getMetadata() == null
        || deployment.getSpec().getTemplate().getMetadata().getLabels() == null
        || !app.id().toString().equals(deployment.getSpec().getTemplate().getMetadata()
            .getLabels().get(APP_LABEL))) {
      throw fail(422, "APPLICATION_TRAFFIC_CALLER_INVALID", resourceId + " 缺少 Pod selector");
    }
    int desired = deployment.getSpec().getReplicas() == null ? 1 : deployment.getSpec().getReplicas();
    List<Pod> pods = client.pods().inNamespace(app.namespace())
        .withLabels(deployment.getSpec().getSelector().getMatchLabels()).list().getItems();
    long readyInjected = pods.stream().filter(this::ready).filter(this::hasReadySidecar).count();
    if (desired < 1 || readyInjected < desired) throw fail(409,
        "APPLICATION_TRAFFIC_CALLER_NOT_MESH_READY",
        resourceId + " 必须有 Ready 且包含 istio-proxy 的 Pod；NaNPanel 不会注入或重启工作负载");
    return deployment;
  }

  private ServicePort httpPort(Service service, int port) {
    ServicePort value = service.getSpec().getPorts().stream()
        .filter(item -> item.getPort() != null && item.getPort() == port
            && (item.getProtocol() == null || "TCP".equals(item.getProtocol())))
        .findFirst().orElse(null);
    if (value == null || !isHttpPort(value)) throw fail(422,
        "APPLICATION_TRAFFIC_HTTP_PORT_UNSUPPORTED",
        "HTTP 路由入口端口必须是 TCP 且服务端口名以 http 开头，或端口为 80/8080");
    return value;
  }

  private boolean isHttpPort(ServicePort port) {
    String name = port.getName() == null ? "" : port.getName().toLowerCase(java.util.Locale.ROOT);
    return name.startsWith("http") || port.getPort() == 80 || port.getPort() == 8080;
  }

  private Map<String, Integer> weights(JsonNode configuration) {
    Map<String, Integer> result = new HashMap<>();
    configuration.path("outputWeights").forEach(item -> {
      String output = item.path("outputPort").asText();
      int weight = item.path("weight").asInt(-1);
      if (output.matches("out-[1-9][0-9]*") && weight >= 0 && weight <= 100) {
        result.put(output, weight);
      }
    });
    return result;
  }

  private Map<String, Integer> normalize(List<Backend> backends) {
    int total = backends.stream().mapToInt(Backend::weight).sum();
    if (total <= 0) throw fail(422, "APPLICATION_TRAFFIC_WEIGHTS_ZERO",
        "所有已连接后端权重都是 0，无法 Apply；至少一个后端权重需要大于 0");
    Map<String, Integer> result = new LinkedHashMap<>();
    List<Map.Entry<String, Integer>> remainders = new ArrayList<>();
    int assigned = 0;
    for (Backend backend : backends) {
      int scaled = backend.weight() * 100;
      int base = scaled / total;
      result.put(backend.outputPort(), base);
      remainders.add(Map.entry(backend.outputPort(), scaled % total));
      assigned += base;
    }
    remainders.sort(Map.Entry.<String, Integer>comparingByValue().reversed()
        .thenComparing(Map.Entry::getKey));
    for (int index = 0; index < 100 - assigned; index++) {
      String output = remainders.get(index % remainders.size()).getKey();
      result.compute(output, (ignored, weight) -> weight + 1);
    }
    return result;
  }

  private List<Map<String, Object>> destinations(List<Backend> backends,
      Map<String, Integer> normalized, String namespace) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (Backend backend : backends) {
      Map<String, Object> destination = Map.of("host", host(backend.serviceName(), namespace),
          "port", Map.of("number", backend.port()));
      result.add(Map.of("destination", destination,
          "weight", normalized.get(backend.outputPort())));
    }
    return result;
  }

  private String host(String serviceName, String namespace) {
    return namespace.isBlank() ? serviceName : serviceName + "." + namespace + ".svc.cluster.local";
  }

  private String routeResourceName(UUID applicationId, String serviceName) {
    UUID stable = UUID.nameUUIDFromBytes(serviceName.getBytes(StandardCharsets.UTF_8));
    return "np-route-" + applicationId.toString().replace("-", "").substring(0, 8)
        + "-" + stable.toString().replace("-", "").substring(0, 12);
  }

  private String resourceName(String resourceId, String kind) {
    String prefix = kind + "/";
    if (resourceId == null || !resourceId.startsWith(prefix) || resourceId.length() <= prefix.length()) {
      throw fail(422, "APPLICATION_TRAFFIC_RESOURCE_INVALID", "业务流量边必须引用应用 " + kind);
    }
    return resourceId.substring(prefix.length());
  }

  private void requireOwned(io.fabric8.kubernetes.api.model.HasMetadata resource, String uid,
      UUID applicationId, String resourceId) {
    if (resource == null || resource.getMetadata() == null || !uid.equals(resource.getMetadata().getUid())
        || resource.getMetadata().getLabels() == null
        || !applicationId.toString().equals(resource.getMetadata().getLabels().get(APP_LABEL))) {
      throw fail(409, "APPLICATION_TRAFFIC_RESOURCE_OWNERSHIP_CONFLICT",
          resourceId + " 缺失或 Kubernetes UID/应用所有权标签已变化");
    }
  }

  private boolean ready(Pod pod) {
    return pod.getMetadata() != null && pod.getMetadata().getDeletionTimestamp() == null
        && pod.getStatus() != null && "Running".equals(pod.getStatus().getPhase())
        && pod.getStatus().getConditions() != null
        && pod.getStatus().getConditions().stream().anyMatch(condition ->
            "Ready".equals(condition.getType()) && "True".equalsIgnoreCase(condition.getStatus()));
  }

  private boolean hasReadySidecar(Pod pod) {
    if (pod.getStatus().getContainerStatuses() == null) return false;
    return pod.getStatus().getContainerStatuses().stream().anyMatch(status ->
        "istio-proxy".equals(status.getName()) && Boolean.TRUE.equals(status.getReady()));
  }

  private GenericKubernetesResource virtualService(KubernetesClient client, String namespace,
      String name) {
    return client.genericKubernetesResources(ISTIO_API, "VirtualService")
        .inNamespace(namespace).withName(name).get();
  }

  private boolean verifySnapshot(KubernetesClient client, UUID applicationId,
      RuntimeSnapshot snapshot) {
    Map<String, String> registered = store.transaction(connection -> query(connection,
        "SELECT resource_name,resource_uid FROM kubernetes_application_traffic_runtime_resource "
            + "WHERE application_id=?", applicationId).stream().collect(
                java.util.stream.Collectors.toMap(row -> String.valueOf(row.get("resource_name")),
                    row -> String.valueOf(row.get("resource_uid")))));
    if (registered.size() != snapshot.routes().size()) return false;
    for (RoutePlan route : snapshot.routes()) {
      GenericKubernetesResource live = virtualService(client, snapshot.namespace(), route.name());
      String uid = registered.get(route.name());
      if (live == null || live.getMetadata() == null || uid == null
          || !uid.equals(live.getMetadata().getUid()) || live.getMetadata().getLabels() == null
          || !applicationId.toString().equals(live.getMetadata().getLabels().get(APP_LABEL))
          || !"nanpanel".equals(live.getMetadata().getLabels().get(MANAGED_LABEL))
          || !applicationId.toString().equals(live.getMetadata().getLabels().get(TRAFFIC_LABEL))) {
        return false;
      }
      JsonNode actual = mapper.valueToTree(live);
      if (!route.resource().path("spec").equals(actual.path("spec"))) return false;
    }
    return true;
  }

  private boolean cleanupCreated(UUID applicationId, String namespace, UUID clusterId) {
    return cleanupCreated(applicationId, namespace, clusterId, List.of());
  }

  private boolean cleanupCreated(UUID applicationId, String namespace, UUID clusterId,
      List<CreatedRoute> createdRoutes) {
    try (KubernetesClient client = clusters.openClientForOperations(clusterId)) {
      Map<String, String> ownedUids = new LinkedHashMap<>();
      createdRoutes.forEach(route -> {
        if (route.uid() != null && !route.uid().isBlank()) ownedUids.put(route.name(), route.uid());
      });
      List<Map<String, Object>> owned = store.transaction(connection -> query(connection,
          "SELECT resource_name,resource_uid FROM kubernetes_application_traffic_runtime_resource "
              + "WHERE application_id=? ORDER BY resource_name", applicationId));
      for (Map<String, Object> resource : owned) {
        ownedUids.put(String.valueOf(resource.get("resource_name")),
            String.valueOf(resource.get("resource_uid")));
      }
      // The saved plan lets compensation recover a create that succeeded just before Core stopped,
      // before its returned UID could be recorded in the database.
      RuntimeSnapshot snapshot = snapshot(runtime(applicationId));
      Map<String, RoutePlan> plannedRoutes = new LinkedHashMap<>();
      snapshot.routes().forEach(route -> plannedRoutes.put(route.name(), route));
      Set<String> names = new HashSet<>(plannedRoutes.keySet());
      names.addAll(ownedUids.keySet());
      for (String name : names) {
        String expectedUid = ownedUids.get(name);
        GenericKubernetesResource live = virtualService(client, namespace, name);
        if (live == null) {
          forgetResource(applicationId, name);
          continue;
        }
        boolean knownUid = expectedUid != null && !expectedUid.isBlank()
            && expectedUid.equals(live.getMetadata() == null ? null : live.getMetadata().getUid());
        RoutePlan planned = plannedRoutes.get(name);
        boolean recoverableOrphan = expectedUid == null && planned != null
            && matchesPlannedOrphan(live, applicationId, snapshot, planned);
        if ((!knownUid && !recoverableOrphan) || !hasTrafficOwnership(live, applicationId)) {
          return false;
        }
        if (live.getMetadata().getResourceVersion() == null
            || live.getMetadata().getResourceVersion().isBlank()) return false;
        client.genericKubernetesResources(ISTIO_API, "VirtualService")
            .inNamespace(namespace).withName(name)
            .lockResourceVersion(live.getMetadata().getResourceVersion()).delete();
        if (virtualService(client, namespace, name) != null) return false;
        forgetResource(applicationId, name);
      }
      return true;
    } catch (RuntimeException failure) {
      return false;
    }
  }

  private boolean matchesPlannedOrphan(GenericKubernetesResource live, UUID applicationId,
      RuntimeSnapshot snapshot, RoutePlan planned) {
    // An unregistered resource is removable only when its app labels, revision and spec all match
    // the exact route plan; the delete also carries the fetched resourceVersion as a precondition.
    if (!hasTrafficOwnership(live, applicationId) || live.getMetadata().getAnnotations() == null
        || !snapshot.revisionId().equals(live.getMetadata().getAnnotations()
            .get("nanpanel.io/topology-revision"))
        || !String.valueOf(snapshot.generation()).equals(live.getMetadata().getAnnotations()
            .get("nanpanel.io/topology-generation"))) return false;
    return planned.resource().path("spec").equals(mapper.valueToTree(live).path("spec"));
  }

  private boolean hasTrafficOwnership(GenericKubernetesResource live, UUID applicationId) {
    Map<String, String> labels = live.getMetadata() == null ? null : live.getMetadata().getLabels();
    return labels != null && applicationId.toString().equals(labels.get(APP_LABEL))
        && "nanpanel".equals(labels.get(MANAGED_LABEL))
        && applicationId.toString().equals(labels.get(TRAFFIC_LABEL));
  }

  private void persistApply(String actor, String remoteAddress, UUID applicationId,
      UUID revisionId, long generation, RuntimeSnapshot snapshot) {
    long now = System.currentTimeMillis();
    String encoded;
    try {
      encoded = mapper.writeValueAsString(snapshot);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Validated traffic route plan could not be serialized", failure);
    }
    store.transaction(connection -> {
      Map<String, Object> application = query(connection,
          "SELECT state FROM kubernetes_application WHERE id=? FOR UPDATE", applicationId)
          .stream().findFirst().orElseThrow(() -> fail(404,
              "KUBERNETES_APPLICATION_NOT_FOUND", "Kubernetes 应用不存在"));
      if (!"DEPLOYED".equals(application.get("state"))) throw fail(409,
          "KUBERNETES_APPLICATION_NOT_DEPLOYED", "只有已部署应用可以启用 Service 流量路由");
      Map<String, Object> head = query(connection,
          "SELECT revision_id,generation FROM kubernetes_application_network_topology_head "
              + "WHERE application_id=? FOR UPDATE", applicationId).stream().findFirst().orElse(null);
      if (head == null || !revisionId.equals(uuid(head.get("revision_id")))
          || generation != number(head, "generation")) throw fail(409,
              "APPLICATION_NETWORK_TOPOLOGY_CHANGED", "保存路由计划时应用拓扑已变化");
      List<Map<String, Object>> active = query(connection,
          "SELECT state FROM kubernetes_application_traffic_runtime WHERE application_id=? FOR UPDATE",
          applicationId);
      if (!active.isEmpty() && !Set.of("COMPENSATED", "FAILED").contains(
          String.valueOf(active.getFirst().get("state")))) {
        throw fail(409, "APPLICATION_TRAFFIC_RUNTIME_ACTIVE",
            "已有未补偿的 Service 路由；请先 Verify 或 Compensate");
      }
      if (!active.isEmpty()) {
        if (!query(connection, "SELECT resource_name FROM kubernetes_application_traffic_runtime_resource "
            + "WHERE application_id=? FOR UPDATE", applicationId).isEmpty()) {
          throw fail(409, "APPLICATION_TRAFFIC_RESOURCES_REMAIN",
              "仍有 NaNPanel 管理的 Istio VirtualService；请先检查并补偿后重试");
        }
        update(connection, "DELETE FROM kubernetes_application_traffic_runtime WHERE application_id=?",
            applicationId);
      }
      update(connection, "INSERT INTO kubernetes_application_traffic_runtime "
          + "(application_id,revision_id,generation,state,runtime_snapshot,detail,updated_by,created_at,updated_at) "
          + "VALUES (?,?,?,'APPLYING',?,? ,?,?,?)", applicationId, revisionId, generation,
          encoded, "正在创建 Istio VirtualService", actor, now, now);
      audit(connection, actor, "KUBERNETES_APPLICATION_TRAFFIC_APPLY", "kubernetes_application",
          applicationId.toString(), "ACCEPTED", remoteAddress,
          "显式 Apply Istio HTTP 流量路由 Revision " + revisionId);
      return null;
    });
  }

  private void registerResource(UUID applicationId, String name, String uid) {
    store.transaction(connection -> {
      update(connection, "INSERT INTO kubernetes_application_traffic_runtime_resource "
          + "(application_id,resource_name,resource_uid) VALUES (?,?,?)", applicationId, name, uid);
      return null;
    });
  }

  private void forgetResource(UUID applicationId, String name) {
    store.transaction(connection -> {
      update(connection, "DELETE FROM kubernetes_application_traffic_runtime_resource "
          + "WHERE application_id=? AND resource_name=?", applicationId, name);
      return null;
    });
  }

  private void markState(UUID applicationId, String state, String detail, boolean applied) {
    long now = System.currentTimeMillis();
    store.transaction(connection -> {
      update(connection, "UPDATE kubernetes_application_traffic_runtime SET state=?,detail=?,updated_at=?,"
          + "applied_at=CASE WHEN ? THEN ? ELSE applied_at END WHERE application_id=?",
          state, detail, now, applied, applied ? now : null, applicationId);
      return null;
    });
  }

  private void markState(UUID applicationId, String state, String detail) {
    markState(applicationId, state, detail, false);
  }

  private void auditAction(String actor, String remoteAddress, UUID applicationId,
      String outcome, String detail) {
    store.transaction(connection -> {
      audit(connection, actor, "KUBERNETES_APPLICATION_TRAFFIC_RUNTIME", "kubernetes_application",
          applicationId.toString(), outcome, remoteAddress, detail);
      return null;
    });
  }

  private RuntimeSnapshot snapshot(Map<String, Object> row) {
    try {
      return mapper.readValue(String.valueOf(row.get("runtime_snapshot")), RuntimeSnapshot.class);
    } catch (IOException failure) {
      throw fail(500, "APPLICATION_TRAFFIC_RUNTIME_CORRUPT", "保存的 Service 路由快照无法读取");
    }
  }

  private Map<String, Object> runtime(UUID applicationId) {
    Map<String, Object> row = store.transaction(connection -> query(connection,
        "SELECT * FROM kubernetes_application_traffic_runtime WHERE application_id=?",
        applicationId).stream().findFirst().orElse(null));
    if (row == null) throw fail(404, "APPLICATION_TRAFFIC_RUNTIME_NOT_FOUND",
        "此应用尚无 Service 流量路由运行时记录");
    return row;
  }

  private RuntimeView view(Map<String, Object> row) {
    return new RuntimeView(String.valueOf(row.get("application_id")),
        String.valueOf(row.get("revision_id")), number(row, "generation"),
        String.valueOf(row.get("state")), row.get("detail") == null ? null : String.valueOf(row.get("detail")),
        Instant.ofEpochMilli(number(row, "updated_at")), row.get("applied_at") == null ? null
            : Instant.ofEpochMilli(number(row, "applied_at")));
  }

  private App app(UUID applicationId) {
    Map<String, Object> row = store.transaction(connection -> query(connection,
        "SELECT id,cluster_id,namespace,state FROM kubernetes_application WHERE id=?",
        applicationId).stream().findFirst().orElse(null));
    if (row == null) throw fail(404, "KUBERNETES_APPLICATION_NOT_FOUND", "Kubernetes 应用不存在");
    return new App(uuid(row.get("id")), uuid(row.get("cluster_id")),
        String.valueOf(row.get("namespace")), String.valueOf(row.get("state")));
  }

  private Map<String, Object> topology(UUID applicationId) {
    Map<String, Object> row = store.transaction(connection -> query(connection,
        "SELECT revision_id,generation,snapshot FROM kubernetes_application_network_topology_head "
            + "WHERE application_id=?", applicationId).stream().findFirst().orElse(null));
    if (row == null) throw fail(409, "APPLICATION_NETWORK_TOPOLOGY_EMPTY",
        "请先保存应用 Service 流量图");
    return row;
  }

  private UUID uuid(Object value) {
    try { return UUID.fromString(String.valueOf(value)); }
    catch (IllegalArgumentException invalid) { throw fail(500, "APPLICATION_TRAFFIC_RUNTIME_CORRUPT", "应用路由 UUID 无效"); }
  }

  private String safeDetail(RuntimeException failure) {
    if (failure instanceof jakarta.ws.rs.WebApplicationException) return failure.getMessage();
    return "Istio 流量路由 Apply 失败；检查 Provider 权限、CRD 和目标 Service 后重试或 Compensate";
  }
}
