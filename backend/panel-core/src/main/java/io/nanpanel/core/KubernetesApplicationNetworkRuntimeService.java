package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthResource.number;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Applies an application's secondary Multus networks and reconciles their Service endpoints. */
@ApplicationScoped
public class KubernetesApplicationNetworkRuntimeService {
  private static final String NAD_API = "k8s.cni.cncf.io/v1";
  private static final String ENDPOINT_SLICE_API = "discovery.k8s.io/v1";
  private static final String MULTUS_NETWORKS = "k8s.v1.cni.cncf.io/networks";
  private static final String MULTUS_STATUS = "k8s.v1.cni.cncf.io/network-status";
  private static final String APP_LABEL = "nanpanel.io/application-id";
  private static final String RUNTIME_LABEL = "nanpanel.io/network-runtime";
  private static final String MANAGED_LABEL = "app.kubernetes.io/managed-by";
  private static final String SLICE_MANAGER = "nanpanel.io/application-network";
  private static final PatchContext MERGE_PATCH = PatchContext.of(PatchType.JSON_MERGE);

  @Inject AuthStore store;
  @Inject KubernetesClusterRegistryService clusters;
  @Inject HostHelperOperationResource helperOperations;
  @Inject ObjectMapper mapper;

  public record RuntimeActionRequest(String reason) {}
  public record RuntimeView(String applicationId, String revisionId, long generation,
      String clusterNodeUid, String hostId, String state, String detail,
      String helperOperationId, Instant updatedAt, Instant appliedAt) {}
  private record App(UUID id, UUID clusterId, String clusterKind, String namespace) {}
  private record NodeTarget(String uid, String name, UUID hostId, long hostGeneration) {}
  private record Attachment(String id, String resourceId, String nodeId, String devicePort,
      String direction, String cidr, String nadName, String bridgeName, String deploymentName) {}
  private record DeploymentBefore(String name, String uid, Map<String, String> nodeSelector,
      boolean nodeSelectorAbsent, Map<String, String> annotations, boolean annotationsAbsent,
      Map<String, String> selector, Map<String, String> podLabels) {}
  private record ServiceBefore(String name, String uid, Map<String, String> selector,
      boolean selectorAbsent) {}
  private record RuntimeSnapshot(String namespace, String nodeName, String hostnameLabel,
      JsonNode helperPayload,
      List<Attachment> attachments, List<DeploymentBefore> deployments,
      List<ServiceBefore> services) {}

  private final AtomicBoolean reconciling = new AtomicBoolean();

  public RuntimeView apply(String actor, String remoteAddress, UUID applicationId,
      RuntimeActionRequest input) {
    String reason = validateReason(input);
    clearCompletedRuntime(applicationId);
    App app = app(applicationId);
    Map<String, Object> topology = topology(applicationId);
    NodeTarget target = mappedNode(app);
    UUID revisionId = uuid(topology.get("revision_id"));
    long generation = number(topology, "generation");
    if (generation < 1) throw fail(409, "APPLICATION_NETWORK_TOPOLOGY_EMPTY",
        "请先保存包含虚拟设备与 Deployment 接入点的应用网络拓扑");

    RuntimeSnapshot snapshot;
    try (KubernetesClient client = clusters.openClientForOperations(app.clusterId())) {
      preflightApis(client, app.namespace());
      snapshot = prepareSnapshot(app, applicationId, revisionId, generation, target,
          String.valueOf(topology.get("snapshot")), client);
    } catch (RuntimeException failure) {
      releaseCidrs(applicationId);
      throw apiFailure(failure);
    }

    UUID operationKey = UUID.randomUUID();
    try {
      persistApply(actor, remoteAddress, app, target, revisionId, generation, snapshot);
    } catch (RuntimeException failure) {
      releaseCidrs(applicationId);
      throw failure;
    }
    try {
      UUID helperOperation = submitHelper(actor, remoteAddress, target, applicationId, revisionId, generation,
          "APPLY", reason, snapshot, operationKey);
      setHelperOperation(applicationId, helperOperation);
    } catch (RuntimeException failure) {
      UUID submitted = helperOperationForKey(operationKey);
      if (submitted != null) setHelperOperation(applicationId, submitted);
      markState(applicationId, "PARTIAL", "Helper 提交结果不确定；请检查 Helper 操作后再补偿");
      throw failure;
    }
    return get(applicationId);
  }

  public RuntimeView verify(String actor, String remoteAddress, UUID applicationId,
      RuntimeActionRequest input) {
    String reason = optionalReason(input, "验证应用虚拟网络");
    Map<String, Object> runtime = activeRuntime(applicationId);
    if (!Set.of("APPLIED", "VERIFY_FAILED").contains(String.valueOf(runtime.get("state")))) {
      throw fail(409, "APPLICATION_NETWORK_RUNTIME_STATE_CONFLICT",
          "只有已接入或验证失败的应用网络可以重新验证");
    }
    if (runtime.get("helper_operation_id") != null
        && Set.of("PENDING", "RUNNING", "UNKNOWN").contains(
            operationStatus(uuid(runtime.get("helper_operation_id"))))) {
      throw fail(409, "APPLICATION_NETWORK_OPERATION_IN_PROGRESS",
          "该应用的 Helper 操作仍在运行或状态未知");
    }
    RuntimeSnapshot snapshot = snapshot(runtime);
    NodeTarget target = targetFrom(runtime);
    UUID revisionId = uuid(runtime.get("revision_id"));
    long generation = number(runtime, "generation");
    UUID operationKey = UUID.randomUUID();
    markOperation(applicationId, "VERIFYING", "正在通过隔离 netns Helper 验证网络设备与 Pod 接入", null);
    try {
      UUID helperOperation = submitHelper(actor, remoteAddress, target, applicationId, revisionId, generation,
          "VERIFY", reason, snapshot, operationKey);
      setHelperOperation(applicationId, helperOperation);
    } catch (RuntimeException failure) {
      UUID submitted = helperOperationForKey(operationKey);
      if (submitted != null) setHelperOperation(applicationId, submitted);
      markState(applicationId, "VERIFY_FAILED", "无法提交 Helper 验证操作");
      throw failure;
    }
    return get(applicationId);
  }

  public RuntimeView compensate(String actor, String remoteAddress, UUID applicationId,
      RuntimeActionRequest input) {
    String reason = validateReason(input);
    Map<String, Object> runtime = activeRuntime(applicationId);
    String state = String.valueOf(runtime.get("state"));
    if (!Set.of("APPLIED", "VERIFY_FAILED", "PARTIAL", "FAILED").contains(state)) {
      throw fail(409, "APPLICATION_NETWORK_RUNTIME_STATE_CONFLICT",
          "当前运行时状态不能开始补偿；请先等待正在运行的 Apply 或 Verify 完成");
    }
    if (runtime.get("helper_operation_id") != null
        && Set.of("PENDING", "RUNNING", "UNKNOWN").contains(
            operationStatus(uuid(runtime.get("helper_operation_id"))))) {
      throw fail(409, "APPLICATION_NETWORK_OPERATION_IN_PROGRESS",
          "该应用的 Helper 操作仍在运行或状态未知，暂时不能补偿");
    }
    RuntimeSnapshot snapshot = snapshot(runtime);
    restoreKubernetes(runtime, snapshot);
    markOperation(applicationId, "COMPENSATING", "已恢复 Service selector 与 Deployment Pod 模板，等待次级网卡 Pod 退出", null);
    auditAction(actor, remoteAddress, applicationId, "COMPENSATING", reason);
    return get(applicationId);
  }

  public RuntimeView get(UUID applicationId) {
    Map<String, Object> row = store.transaction(connection -> query(connection,
        "SELECT * FROM kubernetes_application_network_runtime WHERE application_id=?",
        applicationId).stream().findFirst().orElse(null));
    if (row == null) throw fail(404, "APPLICATION_NETWORK_RUNTIME_NOT_FOUND",
        "此应用尚无网络运行时记录");
    return view(row);
  }

  @Scheduled(every = "10s", concurrentExecution = ConcurrentExecution.SKIP)
  void reconcile() {
    if (!reconciling.compareAndSet(false, true)) return;
    try {
      List<UUID> applications = store.transaction(connection -> query(connection,
          "SELECT application_id FROM kubernetes_application_network_runtime "
              + "WHERE state IN ('APPLYING','APPLIED','VERIFYING','VERIFY_FAILED','COMPENSATING') "
              + "ORDER BY updated_at LIMIT 50")
          .stream().map(row -> uuid(row.get("application_id"))).toList());
      for (UUID applicationId : applications) {
        try {
          reconcileOne(applicationId);
        } catch (RuntimeException failure) {
          markState(applicationId, "PARTIAL", safeDetail(failure));
        }
      }
    } finally {
      reconciling.set(false);
    }
  }

  private void reconcileOne(UUID applicationId) {
    Map<String, Object> row = activeRuntime(applicationId);
    String state = String.valueOf(row.get("state"));
    RuntimeSnapshot snapshot = snapshot(row);
    NodeTarget target = targetFrom(row);
    if (Set.of("APPLIED", "VERIFY_FAILED").contains(state)) {
      if ("VERIFY_FAILED".equals(state) && row.get("helper_operation_id") != null
          && !"SUCCEEDED".equals(operationStatus(uuid(row.get("helper_operation_id"))))) {
        try (KubernetesClient client = clusters.openClientForOperations(app(applicationId).clusterId())) {
          syncEndpointSlices(client, snapshot, applicationId, target.name());
        }
        return;
      }
      try (KubernetesClient client = clusters.openClientForOperations(app(applicationId).clusterId())) {
        syncEndpointSlices(client, snapshot, applicationId, target.name());
        boolean ready = readyWithSecondaryNetworks(client, snapshot, applicationId)
            && foreignSlicesEmpty(client, snapshot);
        markState(applicationId, ready ? "APPLIED" : "VERIFY_FAILED",
            ready ? "Multus 次级网卡已接入隔离虚拟拓扑，Service EndpointSlice 与 Ready Pod 已同步"
                : "等待全部附加 Pod Ready 或检测到 EndpointSlice 漂移");
      }
      return;
    }
    UUID helperOperation = row.get("helper_operation_id") == null
        ? null : uuid(row.get("helper_operation_id"));
    if ("COMPENSATING".equals(state)) {
      if (helperOperation == null) {
        if (!podsDetached(applicationId, snapshot)) return;
        try (KubernetesClient client = clusters.openClientForOperations(app(applicationId).clusterId())) {
          deleteOwnedSlices(client, snapshot, applicationId);
          deleteOwnedNads(client, snapshot, applicationId);
        }
        UUID operationKey = UUID.randomUUID();
        UUID operationId = submitHelper(String.valueOf(row.get("updated_by")), null, target, applicationId,
            uuid(row.get("revision_id")), number(row, "generation"), "COMPENSATE",
            "恢复应用虚拟网络运行时", snapshot, operationKey);
        setHelperOperation(applicationId, operationId);
        return;
      }
      String operationState = operationStatus(helperOperation);
      if ("SUCCEEDED".equals(operationState)) {
        releaseCidrs(applicationId);
        markState(applicationId, "COMPENSATED", "虚拟网络、次级网卡、Service EndpointSlice 与宿主隔离路由已恢复");
      } else if (Set.of("FAILED", "CANCELLED").contains(operationState)) {
        markState(applicationId, "PARTIAL", "Helper 补偿失败；保留 CIDR 预留以便安全重试");
      }
      return;
    }
    if (helperOperation == null) return;
    String operationState = operationStatus(helperOperation);
    if (Set.of("PENDING", "RUNNING", "UNKNOWN").contains(operationState)) return;
    if (!"SUCCEEDED".equals(operationState)) {
      if ("VERIFYING".equals(state)) {
        markState(applicationId, "VERIFY_FAILED", "Helper 未通过网络验证；可重新验证或补偿");
      } else {
        markState(applicationId, "FAILED", "隔离网络 Helper 操作失败；可检查后重试或补偿");
      }
      return;
    }
    if ("VERIFYING".equals(state)) {
      boolean verified;
      try (KubernetesClient client = clusters.openClientForOperations(app(applicationId).clusterId())) {
        verified = verifyKubernetes(client, snapshot, applicationId, target.name());
      }
      markState(applicationId, verified ? "APPLIED" : "VERIFY_FAILED",
          verified ? "Helper、Multus Pod 网卡、Service EndpointSlice 与隔离边界验证通过"
              : "Helper 已通过，但 Kubernetes 次级网络或 EndpointSlice 存在漂移");
      return;
    }
    if ("APPLYING".equals(state)) {
      try (KubernetesClient client = clusters.openClientForOperations(app(applicationId).clusterId())) {
        applyKubernetes(client, snapshot, applicationId, target.name());
        if (readyWithSecondaryNetworks(client, snapshot, applicationId)) {
          syncEndpointSlices(client, snapshot, applicationId, target.name());
          redirectServices(client, snapshot, applicationId);
          if (foreignSlicesEmpty(client, snapshot)) {
            markApplied(applicationId,
                "Multus 次级网卡已接入隔离虚拟拓扑，Service 已使用应用管理的 EndpointSlice");
          }
        } else if (System.currentTimeMillis() - number(row, "created_at") > 600_000) {
          markState(applicationId, "PARTIAL",
              "10 分钟内未能让全部附加 Pod 接入次级网络并 Ready；保留资源供检查或补偿");
        }
      }
    }
  }

  private RuntimeSnapshot prepareSnapshot(App app, UUID applicationId, UUID revisionId,
      long generation, NodeTarget target, String topologyJson, KubernetesClient client) {
    io.fabric8.kubernetes.api.model.Node liveNode = client.nodes().withName(target.name()).get();
    if (liveNode == null || liveNode.getMetadata() == null
        || !target.uid().equals(liveNode.getMetadata().getUid())
        || liveNode.getMetadata().getLabels() == null
        || liveNode.getMetadata().getLabels().get("kubernetes.io/hostname") == null
        || liveNode.getStatus() == null || liveNode.getStatus().getConditions() == null
        || liveNode.getStatus().getConditions().stream().noneMatch(condition ->
            "Ready".equals(condition.getType()) && "True".equalsIgnoreCase(condition.getStatus()))) {
      throw fail(409, "APPLICATION_NETWORK_NODE_CHANGED", "目标 Kubernetes Node UID、hostname 标签或 Ready 状态已变化");
    }
    String hostnameLabel = liveNode.getMetadata().getLabels().get("kubernetes.io/hostname");
    JsonNode graph;
    try {
      graph = mapper.readTree(topologyJson);
    } catch (IOException failure) {
      throw fail(500, "APPLICATION_NETWORK_TOPOLOGY_CORRUPT", "已保存的应用网络拓扑无法读取");
    }
    Set<String> nodeIds = new HashSet<>();
    for (JsonNode source : graph.path("nodes")) {
      String kind = source.path("kind").asText();
      if (!Set.of("SWITCH", "ROUTER", "FIREWALL", "PACKET_TAP").contains(kind)) {
        throw fail(422, "APPLICATION_NETWORK_DEVICE_UNSUPPORTED",
            "当前隔离 netns Provider 不能执行 " + kind + " 设备；可保存草稿，但不能 Apply");
      }
      String id = source.path("id").asText();
      try { UUID.fromString(id); } catch (IllegalArgumentException invalid) {
        throw fail(422, "INVALID_APPLICATION_NETWORK_TOPOLOGY", "虚拟设备 ID 无效");
      }
      nodeIds.add(id);
    }
    if (nodeIds.isEmpty()) throw fail(422, "APPLICATION_NETWORK_TOPOLOGY_EMPTY",
        "应用网络拓扑至少需要一个可执行虚拟设备");
    if (nodeIds.size() > 16 || graph.path("links").size() > 64
        || graph.path("attachments").size() > 64) {
      throw fail(422, "APPLICATION_NETWORK_PROVIDER_LIMIT",
          "首个隔离 Provider 限制为 16 个设备、64 条链路和 64 个 Pod 接入点");
    }

    ArrayNode helperLinks = mapper.createArrayNode();
    for (JsonNode source : graph.path("links")) {
      if (!"L2".equals(source.path("layer").asText())
          || !"ETHERNET".equals(source.path("protocol").asText())) {
        throw fail(422, "APPLICATION_NETWORK_LINK_UNSUPPORTED",
            "首个真实流量 Provider 只执行 L2 Ethernet 设备链路");
      }
      ObjectNode link = mapper.createObjectNode();
      for (String field : List.of("id", "sourceNodeId", "sourcePort", "targetNodeId",
          "targetPort", "layer", "protocol")) link.set(field, source.path(field));
      helperLinks.add(link);
    }

    List<Map<String, Object>> resources = store.transaction(connection -> query(connection,
        "SELECT resource_kind,resource_name,resource_uid FROM kubernetes_application_resource "
            + "WHERE application_id=? ORDER BY resource_kind,resource_name", applicationId));
    Map<String, Deployment> liveDeployments = new LinkedHashMap<>();
    Map<String, DeploymentBefore> deploymentBefore = new LinkedHashMap<>();
    Map<String, Service> liveServices = new LinkedHashMap<>();
    for (Map<String, Object> item : resources) {
      String kind = String.valueOf(item.get("resource_kind"));
      String name = String.valueOf(item.get("resource_name"));
      String uid = String.valueOf(item.get("resource_uid"));
      HasMetadata live = switch (kind) {
        case "Deployment" -> client.apps().deployments().inNamespace(app.namespace()).withName(name).get();
        case "Service" -> client.services().inNamespace(app.namespace()).withName(name).get();
        default -> null;
      };
      if (live == null || live.getMetadata() == null || !uid.equals(live.getMetadata().getUid())
          || !applicationId.toString().equals(label(live, APP_LABEL))) {
        throw fail(409, "APPLICATION_NETWORK_RESOURCE_OWNERSHIP_CONFLICT",
            kind + "/" + name + " 缺失或所有权标记已变化，已停止接入");
      }
      if (live instanceof Deployment deployment) {
        liveDeployments.put(name, deployment);
        var spec = deployment.getSpec();
        var template = spec == null ? null : spec.getTemplate();
        var templateSpec = template == null ? null : template.getSpec();
        var templateMeta = template == null ? null : template.getMetadata();
        if (spec == null || spec.getSelector() == null || spec.getSelector().getMatchLabels() == null
            || templateSpec == null || templateMeta == null || templateMeta.getLabels() == null) {
          throw fail(409, "APPLICATION_NETWORK_DEPLOYMENT_INVALID",
              "Deployment 必须包含 selector、Pod 模板和 Pod 标签");
        }
        if (templateSpec.getNodeName() != null && !target.name().equals(templateSpec.getNodeName())) {
          throw fail(409, "APPLICATION_NETWORK_NODE_CONFLICT",
              "Deployment 已固定到另一 Kubernetes Node");
        }
        String existingHostname = templateSpec.getNodeSelector() == null ? null
            : templateSpec.getNodeSelector().get("kubernetes.io/hostname");
        if (existingHostname != null && !hostnameLabel.equals(existingHostname)) {
          throw fail(409, "APPLICATION_NETWORK_NODE_CONFLICT",
              "Deployment nodeSelector 与唯一映射的 Ready Node 不匹配");
        }
        if (templateSpec.getAffinity() != null && templateSpec.getAffinity().getNodeAffinity() != null
            && templateSpec.getAffinity().getNodeAffinity().getRequiredDuringSchedulingIgnoredDuringExecution() != null) {
          throw fail(409, "APPLICATION_NETWORK_NODE_AFFINITY_CONFLICT",
              "Deployment 使用 required nodeAffinity；当前 Provider 不会覆盖用户调度策略");
        }
        deploymentBefore.put(name, new DeploymentBefore(name, uid,
            copyMap(templateSpec.getNodeSelector()), templateSpec.getNodeSelector() == null,
            copyMap(templateMeta.getAnnotations()), templateMeta.getAnnotations() == null,
            copyMap(spec.getSelector().getMatchLabels()), copyMap(templateMeta.getLabels())));
      } else if (live instanceof Service service) {
        liveServices.put(name, service);
      }
    }

    Map<String, JsonNode> attachmentInputs = new LinkedHashMap<>();
    graph.path("attachments").forEach(item -> attachmentInputs.put(item.path("resourceId").asText(), item));
    if (attachmentInputs.isEmpty()) throw fail(422, "APPLICATION_NETWORK_ATTACHMENT_REQUIRED",
        "至少接入一个应用 Deployment 后才能 Apply");
    List<Map<String, Object>> cidrs = reserveCidrs(applicationId, attachmentInputs.keySet().stream()
        .map(id -> UUID.fromString(attachmentInputs.get(id).path("id").asText())).toList());
    Map<UUID, String> cidrById = new HashMap<>();
    for (Map<String, Object> reservation : cidrs) {
      cidrById.put(uuid(reservation.get("attachment_id")), String.valueOf(reservation.get("cidr")));
    }

    List<Attachment> attachments = new ArrayList<>();
    ArrayNode helperAttachments = mapper.createArrayNode();
    for (Map.Entry<String, JsonNode> entry : attachmentInputs.entrySet()) {
      String resourceId = entry.getKey();
      String[] parts = resourceId.split("/", 2);
      if (parts.length != 2 || !"Deployment".equals(parts[0]) || !liveDeployments.containsKey(parts[1])) {
        throw fail(422, "APPLICATION_NETWORK_ATTACHMENT_INVALID", "工作负载接入必须引用应用 Deployment");
      }
      JsonNode input = entry.getValue();
      String attachmentId = input.path("id").asText();
      String nodeId = input.path("nodeId").asText();
      String devicePort = input.path("devicePort").asText();
      JsonNode targetNode = null;
      for (JsonNode node : graph.path("nodes")) if (nodeId.equals(node.path("id").asText())) targetNode = node;
      if (targetNode == null || !"SWITCH".equals(targetNode.path("kind").asText())
          || "OPENVSWITCH".equals(targetNode.path("switchBackend").asText())) {
        throw fail(422, "APPLICATION_NETWORK_ATTACHMENT_UNSUPPORTED",
            "Pod attachment 必须连接 Linux Bridge SWITCH 的空闲端口");
      }
      if (!nodeIds.contains(nodeId)) {
        throw fail(422, "APPLICATION_NETWORK_ATTACHMENT_INVALID", "工作负载接入引用的设备不存在");
      }
      String cidr = cidrById.get(UUID.fromString(attachmentId));
      String nad = "npnet-" + revisionId.toString().replace("-", "").substring(0, 8)
          + "-" + attachmentId.replace("-", "").substring(0, 8);
      String bridge = "npa" + revisionId.toString().replace("-", "").substring(0, 4)
          + attachmentId.replace("-", "").substring(0, 8);
      attachments.add(new Attachment(attachmentId, resourceId, nodeId, devicePort,
          input.path("direction").asText(), cidr, nad, bridge, parts[1]));
      helperAttachments.add(mapper.createObjectNode().put("id", attachmentId)
          .put("nodeId", nodeId).put("devicePort", devicePort).put("networkCidr", cidr));
    }

    List<ServiceBefore> serviceBefore = new ArrayList<>();
    Set<String> attachedDeployments = attachments.stream().map(Attachment::deploymentName)
        .collect(java.util.stream.Collectors.toSet());
    for (Service service : liveServices.values()) {
      Map<String, String> selector = service.getSpec().getSelector();
      if (selector == null || selector.isEmpty()) continue;
      Set<String> selectedDeployments = liveDeployments.values().stream()
          .filter(deployment -> matches(selector, deployment.getSpec().getTemplate().getMetadata().getLabels()))
          .map(deployment -> deployment.getMetadata().getName())
          .collect(java.util.stream.Collectors.toSet());
      if (selectedDeployments.isEmpty()) continue;
      if (!attachedDeployments.containsAll(selectedDeployments)) {
        throw fail(409, "APPLICATION_NETWORK_SERVICE_INCOMPLETE",
            "Service " + service.getMetadata().getName() + " 选择了未接入虚拟网络的应用 Deployment");
      }
      if (!"ClusterIP".equals(service.getSpec().getType()) && service.getSpec().getType() != null) {
        throw fail(422, "APPLICATION_NETWORK_SERVICE_TYPE_UNSUPPORTED",
            "当前 Provider 只接管 ClusterIP Service 的 EndpointSlice");
      }
      for (ServicePort port : service.getSpec().getPorts()) {
        if (port.getTargetPort() != null && port.getTargetPort().getStrVal() != null) {
          throw fail(422, "APPLICATION_NETWORK_NAMED_TARGET_PORT_UNSUPPORTED",
              "当前 Service 接入要求使用数字 targetPort");
        }
      }
      validateNoForeignPods(client, app.namespace(), applicationId, service, attachedDeployments,
          liveDeployments);
      serviceBefore.add(new ServiceBefore(service.getMetadata().getName(),
          service.getMetadata().getUid(), copyMap(selector), false));
    }

    ArrayNode helperNodes = mapper.createArrayNode();
    for (JsonNode node : graph.path("nodes")) {
      ObjectNode result = mapper.createObjectNode().put("id", node.path("id").asText())
          .put("kind", node.path("kind").asText());
      copyOptional(node, result, "switchBackend");
      copyOptional(node, result, "firewallPolicy");
      copyOptional(node, result, "routerConfiguration");
      helperNodes.add(result);
    }
    ObjectNode helperPayload = mapper.createObjectNode().put("action", "APPLY")
        .put("topologyId", app.id().toString()).put("revisionId", revisionId.toString())
        .put("generation", generation);
    helperPayload.set("nodes", helperNodes);
    helperPayload.set("links", helperLinks);
    helperPayload.set("attachments", helperAttachments);
    return new RuntimeSnapshot(app.namespace(), target.name(), hostnameLabel, helperPayload,
        List.copyOf(attachments),
        List.copyOf(deploymentBefore.values()), List.copyOf(serviceBefore));
  }

  private void persistApply(String actor, String remoteAddress, App app, NodeTarget target,
      UUID revisionId, long generation, RuntimeSnapshot snapshot) {
    long now = System.currentTimeMillis();
    String encoded = encode(snapshot);
    store.transaction(connection -> {
      Map<String, Object> application = query(connection,
          "SELECT state FROM kubernetes_application WHERE id=? FOR UPDATE", app.id())
          .stream().findFirst().orElseThrow(() -> fail(404,
              "KUBERNETES_APPLICATION_NOT_FOUND", "Kubernetes 应用不存在"));
      if (!"DEPLOYED".equals(application.get("state"))) throw fail(409,
          "KUBERNETES_APPLICATION_NOT_DEPLOYED", "只有已部署且稳定的应用可以 Apply 虚拟网络");
      Map<String, Object> currentTopology = query(connection,
          "SELECT revision_id,generation FROM kubernetes_application_network_topology_head "
              + "WHERE application_id=? FOR UPDATE", app.id()).stream().findFirst().orElse(null);
      if (currentTopology == null || !revisionId.equals(uuid(currentTopology.get("revision_id")))
          || generation != number(currentTopology, "generation")) {
        throw fail(409, "APPLICATION_NETWORK_TOPOLOGY_CHANGED",
            "申请 Provider 计划时拓扑已变化，请重新检查再 Apply");
      }
      var active = query(connection, "SELECT state FROM kubernetes_application_network_runtime "
          + "WHERE application_id=? FOR UPDATE", app.id());
      if (!active.isEmpty() && !"COMPENSATED".equals(active.getFirst().get("state"))) {
        throw fail(409, "APPLICATION_NETWORK_RUNTIME_ACTIVE", "应用已有未完成或已启用的网络运行时");
      }
      update(connection, "DELETE FROM kubernetes_application_network_runtime_cidr WHERE application_id=?",
          app.id());
      if (!active.isEmpty()) update(connection,
          "DELETE FROM kubernetes_application_network_runtime WHERE application_id=?", app.id());
      update(connection, "INSERT INTO kubernetes_application_network_runtime "
          + "(application_id,revision_id,generation,cluster_node_uid,host_id,state,helper_operation_id,"
          + "runtime_snapshot,detail,updated_by,created_at,updated_at) VALUES (?,?,?,?,?,'APPLYING',NULL,?,?,?,?,?)",
          app.id(), revisionId, generation, target.uid(), target.hostId(), encoded,
          "正在提交隔离网络 Helper Apply", actor, now, now);
      audit(connection, actor, "KUBERNETES_APPLICATION_NETWORK_RUNTIME_APPLY", "kubernetes_application",
          app.id().toString(), "ACCEPTED", remoteAddress,
          "显式启动应用虚拟网络运行时 Revision " + revisionId + "，单节点 " + target.name());
      return null;
    });
  }

  private UUID submitHelper(String actor, String remoteAddress, NodeTarget target, UUID applicationId,
      UUID revisionId, long generation, String action, String reason, RuntimeSnapshot snapshot,
      UUID operationKey) {
    ObjectNode payload = snapshot.helperPayload().deepCopy();
    payload.put("action", action);
    var request = new HostHelperOperationResource.HelperOperationRequest(
        "network.topology.reconcile", applicationId.toString(), payload, reason, 300,
        target.hostGeneration());
    String key = "application-network-" + operationKey;
    try {
      jakarta.ws.rs.core.Response response = helperOperations.submitAs(target.hostId(), key, request,
          actor, remoteAddress);
      Object entity = response.getEntity();
      if (entity instanceof Map<?, ?> result && result.get("id") != null) {
        return uuid(result.get("id"));
      }
      UUID found = helperOperationForKey(operationKey);
      if (found != null) return found;
      throw fail(502, "APPLICATION_NETWORK_HELPER_OPERATION_MISSING",
          "Helper 已接受请求但没有返回可跟踪的操作 ID");
    } catch (RuntimeException failure) {
      UUID found = helperOperationForKey(operationKey);
      if (found != null) setHelperOperation(applicationId, found);
      throw failure;
    }
  }

  private UUID helperOperationForKey(UUID operationKey) {
    String key = "application-network-" + operationKey;
    Map<String, Object> row = store.transaction(connection -> query(connection,
        "SELECT id FROM operation WHERE idempotency_key=?", key).stream().findFirst().orElse(null));
    return row == null ? null : uuid(row.get("id"));
  }

  private void applyKubernetes(KubernetesClient client, RuntimeSnapshot snapshot,
      UUID applicationId, String nodeName) {
    preflightApis(client, snapshot.namespace());
    for (Attachment attachment : snapshot.attachments()) ensureNad(client, snapshot, applicationId, attachment);
    for (DeploymentBefore before : snapshot.deployments()) {
      if (snapshot.attachments().stream().noneMatch(value -> value.deploymentName().equals(before.name()))) continue;
      Deployment live = client.apps().deployments().inNamespace(snapshot.namespace()).withName(before.name()).get();
      requireResource(live, before.uid(), applicationId, "Deployment/" + before.name());
      Map<String, String> annotations = new LinkedHashMap<>(safeMap(
          live.getSpec().getTemplate().getMetadata().getAnnotations()));
      List<String> networks = new ArrayList<>();
      for (Attachment attachment : snapshot.attachments()) {
        if (attachment.deploymentName().equals(before.name())) networks.add(attachment.nadName());
      }
      annotations.put(MULTUS_NETWORKS, mergeNetworks(annotations.get(MULTUS_NETWORKS), networks));
      Map<String, String> selector = new LinkedHashMap<>(safeMap(live.getSpec().getTemplate().getSpec().getNodeSelector()));
      String prior = selector.put("kubernetes.io/hostname", snapshot.hostnameLabel());
      if (prior != null && !prior.equals(snapshot.hostnameLabel())) {
        throw fail(409, "APPLICATION_NETWORK_NODE_CONFLICT", "Deployment nodeSelector 与 Provider 目标节点冲突");
      }
      if (live.getSpec().getTemplate().getSpec().getNodeName() != null
          && !snapshot.nodeName().equals(live.getSpec().getTemplate().getSpec().getNodeName())) {
        throw fail(409, "APPLICATION_NETWORK_NODE_CONFLICT", "Deployment nodeName 与 Provider 目标节点冲突");
      }
      Map<String, Object> templateMeta = Map.of("annotations", annotations);
      Map<String, Object> templateSpec = Map.of("nodeSelector", selector);
      patchDeployment(client, snapshot.namespace(), before.name(), Map.of("spec", Map.of(
          "template", Map.of("metadata", templateMeta, "spec", templateSpec))));
    }
  }

  private void redirectServices(KubernetesClient client, RuntimeSnapshot snapshot,
      UUID applicationId) {
    for (ServiceBefore before : snapshot.services()) {
      Service live = client.services().inNamespace(snapshot.namespace()).withName(before.name()).get();
      requireResource(live, before.uid(), applicationId, "Service/" + before.name());
      Map<String, String> selector = live.getSpec().getSelector();
      if (selector == null || !applicationId.toString().equals(selector.get(RUNTIME_LABEL))) {
        patchService(client, snapshot.namespace(), before.name(), Map.of("spec", Map.of("selector",
            Map.of(RUNTIME_LABEL, applicationId.toString()))));
      }
    }
  }

  private void ensureNad(KubernetesClient client, RuntimeSnapshot snapshot, UUID applicationId,
      Attachment attachment) {
    String cniConfig;
    try {
      cniConfig = mapper.writeValueAsString(Map.of("cniVersion", "1.0.0", "name", attachment.nadName(),
          "type", "bridge", "bridge", attachment.bridgeName(), "isGateway", false,
          "ipMasq", false, "portIsolation", true,
          "ipam", Map.of("type", "host-local", "subnet", attachment.cidr(), "routes", List.of())));
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Validated Multus CNI configuration could not be serialized", failure);
    }
    Map<String, Object> resource = Map.of("apiVersion", NAD_API, "kind", "NetworkAttachmentDefinition",
        "metadata", Map.of("name", attachment.nadName(), "namespace", snapshot.namespace(),
            "labels", Map.of(APP_LABEL, applicationId.toString(), MANAGED_LABEL, "nanpanel"),
            "annotations", Map.of("nanpanel.io/topology-revision", String.valueOf(
                snapshot.helperPayload().path("revisionId").asText()))),
        "spec", Map.of("config", cniConfig));
    GenericKubernetesResource desired = mapper.convertValue(resource, GenericKubernetesResource.class);
    GenericKubernetesResource current = client.genericKubernetesResources(NAD_API,
        "NetworkAttachmentDefinition").inNamespace(snapshot.namespace()).withName(attachment.nadName()).get();
    if (current == null) {
      GenericKubernetesResource created = client.genericKubernetesResources(NAD_API,
          "NetworkAttachmentDefinition").inNamespace(snapshot.namespace()).resource(desired).create();
      registerRuntimeResource(applicationId, "NetworkAttachmentDefinition", attachment.nadName(), created);
    } else {
      requireRuntimeResource(current, applicationId, "NetworkAttachmentDefinition", attachment.nadName());
      client.genericKubernetesResources(NAD_API, "NetworkAttachmentDefinition")
          .inNamespace(snapshot.namespace()).withName(attachment.nadName())
          .patch(MERGE_PATCH, json(Map.of("spec", Map.of("config", cniConfig))));
    }
  }

  private void syncEndpointSlices(KubernetesClient client, RuntimeSnapshot snapshot,
      UUID applicationId, String nodeName) {
    Map<String, Attachment> attachments = new HashMap<>();
    snapshot.attachments().forEach(attachment -> attachments.put(attachment.deploymentName(), attachment));
    for (ServiceBefore before : snapshot.services()) {
      Service service = client.services().inNamespace(snapshot.namespace()).withName(before.name()).get();
      requireResource(service, before.uid(), applicationId, "Service/" + before.name());
      List<Map<String, Object>> endpoints = new ArrayList<>();
      for (DeploymentBefore deployment : snapshot.deployments()) {
        if (!matches(before.selector(), deployment.podLabels())) continue;
        Attachment attachment = attachments.get(deployment.name());
        if (attachment == null) throw fail(409, "APPLICATION_NETWORK_SERVICE_INCOMPLETE",
            "Service selector 命中未接入的 Deployment");
        Deployment liveDeployment = client.apps().deployments().inNamespace(snapshot.namespace())
            .withName(deployment.name()).get();
        requireResource(liveDeployment, deployment.uid(), applicationId,
            "Deployment/" + deployment.name());
        List<Pod> pods = client.pods().inNamespace(snapshot.namespace())
            .withLabels(liveDeployment.getSpec().getSelector().getMatchLabels()).list().getItems();
        for (Pod pod : pods) {
          if (!applicationId.toString().equals(label(pod, APP_LABEL))) {
            throw fail(409, "APPLICATION_NETWORK_POD_OWNERSHIP_CONFLICT",
                "Deployment selector 命中应用外 Pod，拒绝写入 EndpointSlice");
          }
          if (!isReady(pod)) continue;
          String ip = secondaryIp(pod, snapshot.namespace(), attachment.nadName());
          if (ip == null) continue;
          Map<String, Object> endpoint = new LinkedHashMap<>();
          endpoint.put("addresses", List.of(ip));
          endpoint.put("conditions", Map.of("ready", true, "serving", true, "terminating", false));
          endpoint.put("nodeName", nodeName);
          endpoint.put("targetRef", Map.of("apiVersion", "v1", "kind", "Pod",
              "namespace", snapshot.namespace(), "name", pod.getMetadata().getName(),
              "uid", pod.getMetadata().getUid()));
          endpoints.add(endpoint);
        }
      }
      String sliceName = sliceName(applicationId, before.name());
      List<Map<String, Object>> ports = endpointPorts(service);
      ObjectMeta meta = new ObjectMetaBuilder().withName(sliceName).withNamespace(snapshot.namespace())
          .withLabels(Map.of("kubernetes.io/service-name", before.name(),
              "endpointslice.kubernetes.io/managed-by", SLICE_MANAGER,
              APP_LABEL, applicationId.toString())).withOwnerReferences(List.of(
                  new io.fabric8.kubernetes.api.model.OwnerReferenceBuilder().withApiVersion("v1")
                      .withKind("Service").withName(before.name()).withUid(before.uid())
                      .withController(true).withBlockOwnerDeletion(true).build())).build();
      GenericKubernetesResource slice = new GenericKubernetesResource();
      slice.setApiVersion(ENDPOINT_SLICE_API);
      slice.setKind("EndpointSlice");
      slice.setMetadata(meta);
      slice.setAdditionalProperty("addressType", "IPv4");
      slice.setAdditionalProperty("ports", ports);
      slice.setAdditionalProperty("endpoints", endpoints);
      GenericKubernetesResource current = client.genericKubernetesResources(ENDPOINT_SLICE_API,
          "EndpointSlice").inNamespace(snapshot.namespace()).withName(sliceName).get();
      if (current == null) {
        GenericKubernetesResource created = client.genericKubernetesResources(ENDPOINT_SLICE_API,
            "EndpointSlice").inNamespace(snapshot.namespace()).resource(slice).create();
        registerRuntimeResource(applicationId, "EndpointSlice", sliceName, created);
      } else {
        requireRuntimeResource(current, applicationId, "EndpointSlice", sliceName);
        client.genericKubernetesResources(ENDPOINT_SLICE_API, "EndpointSlice")
            .inNamespace(snapshot.namespace()).withName(sliceName)
            .patch(MERGE_PATCH, json(Map.of("ports", ports, "endpoints", endpoints)));
      }
    }
  }

  private List<Map<String, Object>> endpointPorts(Service service) {
    List<Map<String, Object>> ports = new ArrayList<>();
    for (ServicePort servicePort : service.getSpec().getPorts()) {
      int targetPort = servicePort.getTargetPort() == null ? servicePort.getPort()
          : servicePort.getTargetPort().getIntVal();
      Map<String, Object> port = new LinkedHashMap<>();
      port.put("name", servicePort.getName());
      port.put("protocol", servicePort.getProtocol() == null ? "TCP" : servicePort.getProtocol());
      port.put("port", targetPort);
      ports.add(port);
    }
    return ports;
  }

  private boolean readyWithSecondaryNetworks(KubernetesClient client, RuntimeSnapshot snapshot,
      UUID applicationId) {
    for (DeploymentBefore before : snapshot.deployments()) {
      boolean attached = snapshot.attachments().stream()
          .anyMatch(value -> value.deploymentName().equals(before.name()));
      if (!attached) continue;
      Deployment deployment = client.apps().deployments().inNamespace(snapshot.namespace())
          .withName(before.name()).get();
      requireResource(deployment, before.uid(), applicationId, "Deployment/" + before.name());
      int desired = deployment.getSpec().getReplicas() == null ? 1 : deployment.getSpec().getReplicas();
      List<Pod> pods = client.pods().inNamespace(snapshot.namespace())
          .withLabels(deployment.getSpec().getSelector().getMatchLabels()).list().getItems();
      if (pods.stream().anyMatch(pod -> !applicationId.toString().equals(label(pod, APP_LABEL)))) {
        throw fail(409, "APPLICATION_NETWORK_POD_OWNERSHIP_CONFLICT",
            "Deployment selector 命中应用外 Pod，停止接入");
      }
      List<Pod> active = pods.stream().filter(pod -> pod.getMetadata() != null
          && pod.getMetadata().getDeletionTimestamp() == null && pod.getStatus() != null
          && "Running".equals(pod.getStatus().getPhase())).toList();
      if (active.size() < desired || active.stream().filter(this::isReady).count() < desired) return false;
      Attachment attachment = snapshot.attachments().stream()
          .filter(value -> value.deploymentName().equals(before.name())).findFirst().orElseThrow();
      for (Pod pod : active) if (secondaryIp(pod, snapshot.namespace(), attachment.nadName()) == null) return false;
    }
    return true;
  }

  private boolean verifyKubernetes(KubernetesClient client, RuntimeSnapshot snapshot,
      UUID applicationId, String nodeName) {
    try {
      if (!readyWithSecondaryNetworks(client, snapshot, applicationId)) return false;
      for (Attachment attachment : snapshot.attachments()) {
        GenericKubernetesResource nad = client.genericKubernetesResources(NAD_API,
            "NetworkAttachmentDefinition").inNamespace(snapshot.namespace()).withName(attachment.nadName()).get();
        requireResource(nad, null, applicationId, "NetworkAttachmentDefinition/" + attachment.nadName());
      }
      for (ServiceBefore service : snapshot.services()) {
        Service live = client.services().inNamespace(snapshot.namespace()).withName(service.name()).get();
        requireResource(live, service.uid(), applicationId, "Service/" + service.name());
        if (live.getSpec().getSelector() == null
            || !applicationId.toString().equals(live.getSpec().getSelector().get(RUNTIME_LABEL))) return false;
        GenericKubernetesResource slice = client.genericKubernetesResources(ENDPOINT_SLICE_API,
            "EndpointSlice").inNamespace(snapshot.namespace()).withName(sliceName(applicationId, service.name())).get();
        if (slice == null || !applicationId.toString().equals(label(slice, APP_LABEL))) return false;
        Set<String> expectedIps = expectedEndpointIps(client, snapshot, applicationId, service);
        JsonNode endpoints = mapper.valueToTree(slice.getAdditionalProperties() == null ? null
            : slice.getAdditionalProperties().get("endpoints"));
        Set<String> actualIps = new HashSet<>();
        if (endpoints != null && endpoints.isArray()) {
          for (JsonNode endpoint : endpoints) {
            for (JsonNode address : endpoint.path("addresses")) {
              if (address.isTextual()) actualIps.add(address.asText());
            }
          }
        }
        if (!expectedIps.equals(actualIps)) return false;
      }
      return foreignSlicesEmpty(client, snapshot) && nodeName != null;
    } catch (RuntimeException failure) {
      return false;
    }
  }

  private Set<String> expectedEndpointIps(KubernetesClient client, RuntimeSnapshot snapshot,
      UUID applicationId, ServiceBefore service) {
    Map<String, Attachment> attachments = new HashMap<>();
    snapshot.attachments().forEach(attachment -> attachments.put(attachment.deploymentName(), attachment));
    Set<String> addresses = new HashSet<>();
    for (DeploymentBefore before : snapshot.deployments()) {
      if (!matches(service.selector(), before.podLabels())) continue;
      Attachment attachment = attachments.get(before.name());
      if (attachment == null) return Set.of();
      Deployment deployment = client.apps().deployments().inNamespace(snapshot.namespace())
          .withName(before.name()).get();
      requireResource(deployment, before.uid(), applicationId, "Deployment/" + before.name());
      List<Pod> pods = client.pods().inNamespace(snapshot.namespace())
          .withLabels(deployment.getSpec().getSelector().getMatchLabels()).list().getItems();
      for (Pod pod : pods) {
        if (isReady(pod)) {
          String address = secondaryIp(pod, snapshot.namespace(), attachment.nadName());
          if (address != null) addresses.add(address);
        }
      }
    }
    return Set.copyOf(addresses);
  }

  private boolean foreignSlicesEmpty(KubernetesClient client, RuntimeSnapshot snapshot) {
    for (ServiceBefore service : snapshot.services()) {
      List<GenericKubernetesResource> slices = client.genericKubernetesResources(ENDPOINT_SLICE_API,
          "EndpointSlice").inNamespace(snapshot.namespace())
          .withLabel("kubernetes.io/service-name", service.name()).list().getItems();
      for (GenericKubernetesResource slice : slices) {
        if (SLICE_MANAGER.equals(label(slice, "endpointslice.kubernetes.io/managed-by"))) continue;
        JsonNode endpoints = mapper.valueToTree(slice.getAdditionalProperties().get("endpoints"));
        if (endpoints != null && endpoints.isArray()) {
          for (JsonNode endpoint : endpoints) {
            if (endpoint.path("conditions").path("ready").asBoolean(true)) return false;
          }
        }
      }
    }
    return true;
  }

  private void restoreKubernetes(Map<String, Object> runtime, RuntimeSnapshot snapshot) {
    UUID applicationId = uuid(runtime.get("application_id"));
    try (KubernetesClient client = clusters.openClientForOperations(app(applicationId).clusterId())) {
      for (DeploymentBefore before : snapshot.deployments()) {
        if (snapshot.attachments().stream().noneMatch(value -> value.deploymentName().equals(before.name()))) continue;
        Deployment live = client.apps().deployments().inNamespace(snapshot.namespace()).withName(before.name()).get();
        requireResource(live, before.uid(), applicationId, "Deployment/" + before.name());
        Map<String, String> liveSelector = safeMap(live.getSpec().getTemplate().getSpec().getNodeSelector());
        String expectedHostSelector = snapshot.hostnameLabel();
        Map<String, String> currentAnnotations = new LinkedHashMap<>(safeMap(
            live.getSpec().getTemplate().getMetadata().getAnnotations()));
        String networkAnnotation = currentAnnotations.get(MULTUS_NETWORKS);
        if (java.util.Objects.equals(before.nodeSelector().get("kubernetes.io/hostname"),
                liveSelector.get("kubernetes.io/hostname"))
            && java.util.Objects.equals(before.annotations().get(MULTUS_NETWORKS), networkAnnotation)) {
          continue;
        }
        if (!expectedHostSelector.equals(liveSelector.get("kubernetes.io/hostname"))) {
          throw fail(409, "APPLICATION_NETWORK_DEPLOYMENT_DRIFT",
              "Deployment nodeSelector 已在运行时接入后被其他操作者修改");
        }
        for (Attachment attachment : snapshot.attachments()) {
          if (attachment.deploymentName().equals(before.name())
              && (networkAnnotation == null || !networkAnnotation.contains(attachment.nadName()))) {
            throw fail(409, "APPLICATION_NETWORK_DEPLOYMENT_DRIFT",
                "Deployment Multus annotation 已在运行时接入后被修改");
          }
        }
        Map<String, Object> templateSpec = new LinkedHashMap<>();
        if (before.nodeSelectorAbsent()) {
          templateSpec.put("nodeSelector", null);
        } else {
          Map<String, Object> selectorPatch = new LinkedHashMap<>();
          selectorPatch.put("kubernetes.io/hostname",
              before.nodeSelector().get("kubernetes.io/hostname"));
          templateSpec.put("nodeSelector", selectorPatch);
        }
        Map<String, Object> templateMeta = new LinkedHashMap<>();
        if (before.annotationsAbsent()) {
          templateMeta.put("annotations", null);
        } else {
          Map<String, Object> annotationPatch = new LinkedHashMap<>();
          annotationPatch.put(MULTUS_NETWORKS, before.annotations().get(MULTUS_NETWORKS));
          templateMeta.put("annotations", annotationPatch);
        }
        patchDeployment(client, snapshot.namespace(), before.name(), Map.of("spec", Map.of(
            "template", Map.of("metadata", templateMeta, "spec", templateSpec))));
      }
      for (ServiceBefore before : snapshot.services()) {
        Service live = client.services().inNamespace(snapshot.namespace()).withName(before.name()).get();
        requireResource(live, before.uid(), applicationId, "Service/" + before.name());
        if (java.util.Objects.equals(before.selector(), live.getSpec().getSelector())) continue;
        if (live.getSpec().getSelector() == null
            || !applicationId.toString().equals(live.getSpec().getSelector().get(RUNTIME_LABEL))) {
          throw fail(409, "APPLICATION_NETWORK_SERVICE_DRIFT",
              "Service selector 已在运行时接入后被其他操作者修改");
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        if (before.selectorAbsent()) spec.put("selector", null);
        else spec.put("selector", before.selector());
        patchService(client, snapshot.namespace(), before.name(), Map.of("spec", spec));
      }
    } catch (RuntimeException failure) {
      markState(applicationId, "PARTIAL", "恢复 Kubernetes 配置失败：" + safeDetail(failure));
      throw failure;
    }
  }

  private boolean podsDetached(UUID applicationId, RuntimeSnapshot snapshot) {
    try (KubernetesClient client = clusters.openClientForOperations(app(applicationId).clusterId())) {
      for (DeploymentBefore before : snapshot.deployments()) {
        Deployment live = client.apps().deployments().inNamespace(snapshot.namespace()).withName(before.name()).get();
        if (live == null) continue;
        if (snapshot.attachments().stream().anyMatch(value -> value.deploymentName().equals(before.name()))) {
          int desired = live.getSpec().getReplicas() == null ? 1 : live.getSpec().getReplicas();
          if (live.getStatus() == null || live.getStatus().getObservedGeneration() == null
              || live.getStatus().getObservedGeneration() < live.getMetadata().getGeneration()
              || (live.getStatus().getUpdatedReplicas() == null ? 0
                  : live.getStatus().getUpdatedReplicas()) < desired
              || (live.getStatus().getReadyReplicas() == null ? 0
                  : live.getStatus().getReadyReplicas()) < desired) return false;
        }
        List<Pod> pods = client.pods().inNamespace(snapshot.namespace())
            .withLabels(live.getSpec().getSelector().getMatchLabels()).list().getItems();
        for (Pod pod : pods) {
          JsonNode status = annotationJson(pod, MULTUS_STATUS);
          for (Attachment attachment : snapshot.attachments()) {
            if (attachment.deploymentName().equals(before.name()) && hasNetwork(status, snapshot.namespace(), attachment.nadName())) {
              return false;
            }
          }
        }
      }
      return true;
    }
  }

  private void deleteOwnedSlices(KubernetesClient client, RuntimeSnapshot snapshot, UUID applicationId) {
    for (ServiceBefore service : snapshot.services()) {
      String name = sliceName(applicationId, service.name());
      GenericKubernetesResource slice = client.genericKubernetesResources(ENDPOINT_SLICE_API,
          "EndpointSlice").inNamespace(snapshot.namespace()).withName(name).get();
      if (slice == null) {
        forgetRuntimeResource(applicationId, "EndpointSlice", name);
        continue;
      }
      requireRuntimeResource(slice, applicationId, "EndpointSlice", name);
      client.genericKubernetesResources(ENDPOINT_SLICE_API, "EndpointSlice")
          .inNamespace(snapshot.namespace()).withName(name).delete();
      if (client.genericKubernetesResources(ENDPOINT_SLICE_API, "EndpointSlice")
          .inNamespace(snapshot.namespace()).withName(name).get() != null) {
        throw fail(502, "APPLICATION_NETWORK_CLEANUP_UNCONFIRMED",
            "EndpointSlice/" + name + " 删除尚未确认");
      }
      forgetRuntimeResource(applicationId, "EndpointSlice", name);
    }
  }

  private void deleteOwnedNads(KubernetesClient client, RuntimeSnapshot snapshot, UUID applicationId) {
    for (Attachment attachment : snapshot.attachments()) {
      GenericKubernetesResource nad = client.genericKubernetesResources(NAD_API,
          "NetworkAttachmentDefinition").inNamespace(snapshot.namespace()).withName(attachment.nadName()).get();
      if (nad == null) {
        forgetRuntimeResource(applicationId, "NetworkAttachmentDefinition", attachment.nadName());
        continue;
      }
      requireRuntimeResource(nad, applicationId, "NetworkAttachmentDefinition", attachment.nadName());
      client.genericKubernetesResources(NAD_API, "NetworkAttachmentDefinition")
          .inNamespace(snapshot.namespace()).withName(attachment.nadName()).delete();
      if (client.genericKubernetesResources(NAD_API, "NetworkAttachmentDefinition")
          .inNamespace(snapshot.namespace()).withName(attachment.nadName()).get() != null) {
        throw fail(502, "APPLICATION_NETWORK_CLEANUP_UNCONFIRMED",
            "NetworkAttachmentDefinition/" + attachment.nadName() + " 删除尚未确认");
      }
      forgetRuntimeResource(applicationId, "NetworkAttachmentDefinition", attachment.nadName());
    }
  }

  private void preflightApis(KubernetesClient client, String namespace) {
    try {
      client.genericKubernetesResources(NAD_API, "NetworkAttachmentDefinition")
          .inNamespace(namespace).list();
      client.genericKubernetesResources(ENDPOINT_SLICE_API, "EndpointSlice")
          .inNamespace(namespace).list();
    } catch (RuntimeException failure) {
      throw fail(422, "APPLICATION_NETWORK_CNI_UNAVAILABLE",
          "集群必须预装 Multus NetworkAttachmentDefinition CRD，并允许读写 EndpointSlice；不会自动安装 CNI");
    }
  }

  private void validateNoForeignPods(KubernetesClient client, String namespace, UUID applicationId,
      Service service, Set<String> attachedDeployments, Map<String, Deployment> deployments) {
    Map<String, String> selector = service.getSpec().getSelector();
    List<Pod> selected = client.pods().inNamespace(namespace).withLabels(selector).list().getItems();
    for (Pod pod : selected) {
      Map<String, String> labels = pod.getMetadata() == null ? null : pod.getMetadata().getLabels();
      boolean belongsToAttachedDeployment = labels != null && attachedDeployments.stream().anyMatch(name -> {
        Deployment deployment = deployments.get(name);
        return matches(selector, labels)
            && matches(deployment.getSpec().getSelector().getMatchLabels(), labels);
      });
      if (!applicationId.toString().equals(label(pod, APP_LABEL)) || !belongsToAttachedDeployment) {
        throw fail(409, "APPLICATION_NETWORK_SERVICE_FOREIGN_PODS",
            "Service " + service.getMetadata().getName() + " 当前选择了应用外 Pod，不能安全接管 EndpointSlice");
      }
    }
  }

  private List<Map<String, Object>> reserveCidrs(UUID applicationId, List<UUID> attachmentIds) {
    return store.transaction(connection -> {
      Map<String, Object> allocator = query(connection,
          "SELECT next_index FROM kubernetes_application_network_cidr_allocator WHERE id=1 FOR UPDATE")
          .getFirst();
      int next = ((Number)allocator.get("next_index")).intValue();
      Set<String> used = new HashSet<>();
      query(connection, "SELECT cidr FROM kubernetes_application_network_runtime_cidr")
          .forEach(row -> used.add(String.valueOf(row.get("cidr"))));
      List<Map<String, Object>> result = new ArrayList<>();
      for (UUID attachmentId : attachmentIds) {
        int selected = -1;
        for (int offset = 0; offset < 512; offset++) {
          int candidate = (next + offset) % 512;
          String cidr = cidr(candidate);
          if (!used.contains(cidr)) {
            selected = candidate;
            next = (candidate + 1) % 512;
            used.add(cidr);
            break;
          }
        }
        if (selected < 0) throw fail(409, "APPLICATION_NETWORK_CIDR_POOL_EXHAUSTED",
            "隔离次级网络 CIDR 池 198.18.0.0/15 已耗尽");
        String cidr = cidr(selected);
        update(connection, "INSERT INTO kubernetes_application_network_runtime_cidr "
            + "(application_id,attachment_id,cidr) VALUES (?,?,?)", applicationId, attachmentId, cidr);
        result.add(Map.of("attachment_id", attachmentId, "cidr", cidr));
      }
      update(connection, "UPDATE kubernetes_application_network_cidr_allocator SET next_index=? WHERE id=1", next);
      return result;
    });
  }

  private void releaseCidrs(UUID applicationId) {
    store.transaction(connection -> {
      update(connection, "DELETE FROM kubernetes_application_network_runtime_cidr WHERE application_id=?",
          applicationId);
      return null;
    });
  }

  private NodeTarget mappedNode(App app) {
    if (!"K3S".equals(app.clusterKind())) throw fail(422,
        "APPLICATION_NETWORK_CLUSTER_UNSUPPORTED", "首个真实流量 Provider 当前只允许 K3s 单节点接入");
    List<Map<String, Object>> nodes = store.transaction(connection -> {
      String capabilities = connection.getMetaData().getDatabaseProductName()
          .equalsIgnoreCase("PostgreSQL") ? "h.capabilities::text" : "h.capabilities";
      return query(connection,
        "SELECT n.node_uid,n.node_name,n.host_id,n.ready,n.node_present,n.observed_at,"
            + "c.health_status,c.observed_at AS cluster_observed_at,h.generation," + capabilities + " AS capabilities,"
            + "(SELECT a.helper_status FROM agent a WHERE a.host_id=h.id ORDER BY a.last_seen DESC NULLS LAST LIMIT 1) AS helper_status,"
            + "(SELECT a.last_seen FROM agent a WHERE a.host_id=h.id ORDER BY a.last_seen DESC NULLS LAST LIMIT 1) AS agent_last_seen "
            + "FROM kubernetes_cluster_node n JOIN kubernetes_cluster c ON c.id=n.cluster_id "
            + "JOIN host h ON h.id=n.host_id WHERE n.cluster_id=? AND n.node_present=TRUE "
            + "AND n.host_id IS NOT NULL", app.clusterId());
    });
    if (nodes.size() != 1) throw fail(409, "APPLICATION_NETWORK_SINGLE_NODE_REQUIRED",
        "当前 Provider 要求集群只有一个已映射且 Ready 的 Kubernetes Node");
    Map<String, Object> node = nodes.getFirst();
    Instant observed = instant(node.get("cluster_observed_at"));
    Instant agentSeen = instant(node.get("agent_last_seen"));
    if (!Boolean.TRUE.equals(node.get("ready")) || !"HEALTHY".equals(node.get("health_status")) || observed == null
        || observed.isBefore(Instant.now().minusSeconds(120)) || agentSeen == null
        || agentSeen.isBefore(Instant.now().minusSeconds(90)) || !"AVAILABLE".equals(node.get("helper_status"))) {
      throw fail(409, "APPLICATION_NETWORK_HOST_UNHEALTHY",
          "目标 Node、Agent 或 Privileged Helper 未通过近期健康检查");
    }
    JsonNode capabilities;
    try { capabilities = mapper.readTree(String.valueOf(node.get("capabilities"))); }
    catch (IOException failure) { throw new IllegalStateException("Host capabilities are invalid", failure); }
    if (capabilities == null || !capabilities.isArray()) throw fail(409,
        "APPLICATION_NETWORK_PROVIDER_UNAVAILABLE", "主机未声明隔离网络 Provider 能力");
    boolean supported = false;
    for (JsonNode capability : capabilities) {
      if ("network.topology.reconcile".equals(capability.asText())) supported = true;
    }
    if (!supported) throw fail(409, "APPLICATION_NETWORK_PROVIDER_UNAVAILABLE",
        "目标主机没有声明 network.topology.reconcile Helper 能力");
    UUID hostId = uuid(node.get("host_id"));
    return new NodeTarget(String.valueOf(node.get("node_uid")), String.valueOf(node.get("node_name")),
        hostId, number(node, "generation"));
  }

  private App app(UUID applicationId) {
    Map<String, Object> row = store.transaction(connection -> query(connection,
        "SELECT a.id,a.cluster_id,c.kind AS cluster_kind,a.namespace FROM kubernetes_application a "
            + "JOIN kubernetes_cluster c ON c.id=a.cluster_id WHERE a.id=?", applicationId)
        .stream().findFirst().orElse(null));
    if (row == null) throw fail(404, "KUBERNETES_APPLICATION_NOT_FOUND", "Kubernetes 应用不存在");
    return new App(uuid(row.get("id")), uuid(row.get("cluster_id")),
        String.valueOf(row.get("cluster_kind")), String.valueOf(row.get("namespace")));
  }

  private Map<String, Object> topology(UUID applicationId) {
    Map<String, Object> row = store.transaction(connection -> query(connection,
        "SELECT revision_id,generation,snapshot FROM kubernetes_application_network_topology_head "
            + "WHERE application_id=?", applicationId).stream().findFirst().orElse(null));
    if (row == null || row.get("revision_id") == null) throw fail(409,
        "APPLICATION_NETWORK_TOPOLOGY_EMPTY", "请先保存应用网络拓扑 Revision");
    return row;
  }

  private Map<String, Object> activeRuntime(UUID applicationId) {
    Map<String, Object> row = store.transaction(connection -> query(connection,
        "SELECT * FROM kubernetes_application_network_runtime WHERE application_id=? FOR UPDATE",
        applicationId).stream().findFirst().orElse(null));
    if (row == null) throw fail(404, "APPLICATION_NETWORK_RUNTIME_NOT_FOUND", "此应用尚无网络运行时记录");
    return row;
  }

  private void clearCompletedRuntime(UUID applicationId) {
    store.transaction(connection -> {
      Map<String, Object> current = query(connection,
          "SELECT state FROM kubernetes_application_network_runtime WHERE application_id=? FOR UPDATE",
          applicationId).stream().findFirst().orElse(null);
      if (current == null) return null;
      String state = String.valueOf(current.get("state"));
      if ("COMPENSATED".equals(state)) {
        update(connection, "DELETE FROM kubernetes_application_network_runtime_cidr WHERE application_id=?",
            applicationId);
        update(connection, "DELETE FROM kubernetes_application_network_runtime WHERE application_id=?",
            applicationId);
        return null;
      }
      throw fail(409, "APPLICATION_NETWORK_RUNTIME_ACTIVE", "应用已有未完成或已启用的网络运行时");
    });
  }

  private RuntimeSnapshot snapshot(Map<String, Object> row) {
    try { return mapper.readValue(String.valueOf(row.get("runtime_snapshot")), RuntimeSnapshot.class); }
    catch (IOException failure) { throw fail(500, "APPLICATION_NETWORK_RUNTIME_CORRUPT", "应用网络运行时快照无法读取"); }
  }

  private NodeTarget targetFrom(Map<String, Object> row) {
    UUID hostId = uuid(row.get("host_id"));
    Map<String, Object> node = store.transaction(connection -> query(connection,
        "SELECT n.node_uid,n.node_name,h.generation FROM kubernetes_cluster_node n "
            + "JOIN host h ON h.id=n.host_id WHERE n.node_uid=? AND n.host_id=?",
        row.get("cluster_node_uid"), hostId).stream().findFirst().orElse(null));
    if (node == null) throw fail(409, "APPLICATION_NETWORK_TARGET_MISSING", "原目标 Node 与主机映射已不存在");
    return new NodeTarget(String.valueOf(node.get("node_uid")), String.valueOf(node.get("node_name")),
        hostId, number(node, "generation"));
  }

  private void markOperation(UUID applicationId, String state, String detail, UUID helperOperation) {
    store.transaction(connection -> {
      update(connection, "UPDATE kubernetes_application_network_runtime SET state=?,detail=?,"
          + "helper_operation_id=?,updated_at=? WHERE application_id=?", state, detail,
          helperOperation, System.currentTimeMillis(), applicationId);
      return null;
    });
  }

  private void setHelperOperation(UUID applicationId, UUID operationId) {
    store.transaction(connection -> {
      update(connection, "UPDATE kubernetes_application_network_runtime SET helper_operation_id=?,"
          + "updated_at=? WHERE application_id=?", operationId, System.currentTimeMillis(), applicationId);
      return null;
    });
  }

  private void markState(UUID applicationId, String state, String detail) {
    long now = System.currentTimeMillis();
    store.transaction(connection -> {
      Map<String, Object> current = query(connection,
          "SELECT state,detail FROM kubernetes_application_network_runtime WHERE application_id=? FOR UPDATE",
          applicationId).stream().findFirst().orElse(null);
      if (current == null || (state.equals(current.get("state"))
          && java.util.Objects.equals(detail, current.get("detail")))) return null;
      update(connection, "UPDATE kubernetes_application_network_runtime SET state=?,detail=?,"
          + "updated_at=?,applied_at=CASE WHEN ?='APPLIED' THEN COALESCE(applied_at,?) ELSE applied_at END "
          + "WHERE application_id=?", state, detail, now, state, now, applicationId);
      return null;
    });
  }

  private void markApplied(UUID applicationId, String detail) {
    markState(applicationId, "APPLIED", detail);
  }

  private String operationStatus(UUID operationId) {
    Map<String, Object> row = store.transaction(connection -> query(connection,
        "SELECT status FROM operation WHERE id=?", operationId).stream().findFirst().orElse(null));
    return row == null ? "UNKNOWN" : String.valueOf(row.get("status"));
  }

  private void auditAction(String actor, String remoteAddress, UUID applicationId,
      String action, String detail) {
    store.transaction(connection -> {
      audit(connection, actor, "KUBERNETES_APPLICATION_NETWORK_RUNTIME_" + action,
          "kubernetes_application", applicationId.toString(), action, remoteAddress, detail);
      return null;
    });
  }

  private RuntimeView view(Map<String, Object> row) {
    return new RuntimeView(String.valueOf(row.get("application_id")),
        String.valueOf(row.get("revision_id")), number(row, "generation"),
        String.valueOf(row.get("cluster_node_uid")), String.valueOf(row.get("host_id")),
        String.valueOf(row.get("state")), String.valueOf(row.get("detail")),
        row.get("helper_operation_id") == null ? null : String.valueOf(row.get("helper_operation_id")),
        Instant.ofEpochMilli(number(row, "updated_at")), row.get("applied_at") == null ? null
            : Instant.ofEpochMilli(number(row, "applied_at")));
  }

  private String validateReason(RuntimeActionRequest input) {
    if (input == null || input.reason() == null || input.reason().isBlank()
        || input.reason().trim().length() > 500
        || input.reason().chars().anyMatch(Character::isISOControl)) {
      throw fail(422, "VALIDATION_FAILED", "Apply 或 Compensate 必须提供不超过 500 字的操作原因");
    }
    return input.reason().trim();
  }

  private String optionalReason(RuntimeActionRequest input, String fallback) {
    if (input == null || input.reason() == null || input.reason().isBlank()) return fallback;
    return validateReason(input);
  }

  private void patchDeployment(KubernetesClient client, String namespace, String name,
      Map<String, Object> patch) {
    client.apps().deployments().inNamespace(namespace).withName(name).patch(MERGE_PATCH, json(patch));
  }

  private void patchService(KubernetesClient client, String namespace, String name,
      Map<String, Object> patch) {
    client.services().inNamespace(namespace).withName(name).patch(MERGE_PATCH, json(patch));
  }

  private String mergeNetworks(String existing, List<String> names) {
    if (existing == null || existing.isBlank()) return String.join(",", names);
    try {
      JsonNode parsed = mapper.readTree(existing);
      if (!parsed.isArray()) throw new IllegalArgumentException("not array");
      ArrayNode result = (ArrayNode)parsed.deepCopy();
      for (String name : names) if (result.findValuesAsText("name").stream()
          .noneMatch(value -> value.endsWith("/" + name) || value.equals(name))) result.add(name);
      return result.toString();
    } catch (IOException | IllegalArgumentException notJsonArray) {
      List<String> result = new ArrayList<>(List.of(existing.split(",")));
      for (String name : names) if (result.stream().noneMatch(value ->
          value.trim().endsWith("/" + name) || value.trim().equals(name))) result.add(name);
      return String.join(",", result);
    }
  }

  private String secondaryIp(Pod pod, String namespace, String nadName) {
    JsonNode status = annotationJson(pod, MULTUS_STATUS);
    if (status == null || !status.isArray()) return null;
    for (JsonNode network : status) {
      String name = network.path("name").asText();
      if (!(name.equals(nadName) || name.equals(namespace + "/" + nadName))) continue;
      JsonNode ips = network.path("ips");
      if (ips.isArray()) for (JsonNode value : ips) if (validIpv4(value.asText())) return value.asText();
      String ip = network.path("ips").asText();
      if (validIpv4(ip)) return ip;
    }
    return null;
  }

  private JsonNode annotationJson(Pod pod, String key) {
    String value = pod.getMetadata() == null || pod.getMetadata().getAnnotations() == null
        ? null : pod.getMetadata().getAnnotations().get(key);
    if (value == null) return null;
    try { return mapper.readTree(value); }
    catch (IOException invalid) { return null; }
  }

  private boolean hasNetwork(JsonNode status, String namespace, String nadName) {
    if (status == null || !status.isArray()) return false;
    for (JsonNode network : status) {
      String name = network.path("name").asText();
      if (name.equals(nadName) || name.equals(namespace + "/" + nadName)) return true;
    }
    return false;
  }

  private boolean isReady(Pod pod) {
    if (pod.getStatus() == null || pod.getStatus().getConditions() == null) return false;
    return pod.getStatus().getConditions().stream().anyMatch(condition ->
        "Ready".equals(condition.getType()) && "True".equalsIgnoreCase(condition.getStatus()));
  }

  private boolean validIpv4(String value) {
    if (value == null || !value.matches("[0-9.]+")) return false;
    String[] octets = value.split("\\.", -1);
    if (octets.length != 4) return false;
    for (String octet : octets) {
      try {
        int number = Integer.parseInt(octet);
        if (number < 0 || number > 255) return false;
      } catch (NumberFormatException invalid) {
        return false;
      }
    }
    return true;
  }

  private void requireResource(HasMetadata current, String expectedUid, UUID applicationId, String name) {
    if (current == null || current.getMetadata() == null
        || (expectedUid != null && !expectedUid.equals(current.getMetadata().getUid()))
        || !applicationId.toString().equals(label(current, APP_LABEL))) {
      throw fail(409, "APPLICATION_NETWORK_RESOURCE_OWNERSHIP_CONFLICT",
          name + " 缺失、UID 已变化或不属于此 NaNPanel 应用");
    }
  }

  private void registerRuntimeResource(UUID applicationId, String kind, String name,
      GenericKubernetesResource resource) {
    String uid = resource == null || resource.getMetadata() == null
        ? null : resource.getMetadata().getUid();
    if (uid == null || uid.isBlank()) throw fail(502,
        "APPLICATION_NETWORK_RESOURCE_UID_MISSING", kind + "/" + name + " 创建后未返回 UID");
    store.transaction(connection -> {
      update(connection, "INSERT INTO kubernetes_application_network_runtime_resource "
          + "(application_id,resource_kind,resource_name,resource_uid) VALUES (?,?,?,?)",
          applicationId, kind, name, uid);
      return null;
    });
  }

  private void requireRuntimeResource(GenericKubernetesResource resource, UUID applicationId,
      String kind, String name) {
    Map<String, Object> expected = store.transaction(connection -> query(connection,
        "SELECT resource_uid FROM kubernetes_application_network_runtime_resource "
            + "WHERE application_id=? AND resource_kind=? AND resource_name=?",
        applicationId, kind, name).stream().findFirst().orElse(null));
    String expectedUid = expected == null ? null : String.valueOf(expected.get("resource_uid"));
    if (expectedUid == null || resource.getMetadata() == null
        || !expectedUid.equals(resource.getMetadata().getUid())
        || !applicationId.toString().equals(label(resource, APP_LABEL))) {
      throw fail(409, "APPLICATION_NETWORK_RESOURCE_OWNERSHIP_CONFLICT",
          kind + "/" + name + " 的 UID 或应用所有权记录不匹配");
    }
  }

  private void forgetRuntimeResource(UUID applicationId, String kind, String name) {
    store.transaction(connection -> {
      update(connection, "DELETE FROM kubernetes_application_network_runtime_resource "
          + "WHERE application_id=? AND resource_kind=? AND resource_name=?",
          applicationId, kind, name);
      return null;
    });
  }

  private String label(HasMetadata resource, String name) {
    return resource.getMetadata() == null || resource.getMetadata().getLabels() == null
        ? null : resource.getMetadata().getLabels().get(name);
  }

  private String label(Pod resource, String name) {
    return resource.getMetadata() == null || resource.getMetadata().getLabels() == null
        ? null : resource.getMetadata().getLabels().get(name);
  }

  private boolean matches(Map<String, String> selector, Map<String, String> labels) {
    return selector != null && labels != null && !selector.isEmpty()
        && selector.entrySet().stream().allMatch(entry -> entry.getValue().equals(labels.get(entry.getKey())));
  }

  private Map<String, String> copyMap(Map<String, String> source) {
    return source == null ? Map.of() : Map.copyOf(source);
  }

  private Map<String, String> safeMap(Map<String, String> source) {
    return source == null ? Map.of() : source;
  }

  private void copyOptional(JsonNode source, ObjectNode target, String property) {
    if (!source.path(property).isMissingNode()) target.set(property, source.path(property));
  }

  private String sliceName(UUID applicationId, String serviceName) {
    String suffix = applicationId.toString().replace("-", "").substring(0, 12);
    String safe = serviceName.replaceAll("[^a-z0-9-]", "-");
    return "npas-" + suffix + "-" + safe.substring(0, Math.min(35, safe.length()));
  }

  private String cidr(int index) {
    return "198." + (18 + index / 256) + "." + (index % 256) + ".0/24";
  }

  private String encode(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch (JsonProcessingException failure) { throw new IllegalStateException("Runtime snapshot could not be serialized", failure); }
  }

  private String json(Object value) {
    try { return mapper.writeValueAsString(value); }
    catch (JsonProcessingException failure) { throw new IllegalStateException("Kubernetes patch could not be serialized", failure); }
  }

  private RuntimeException apiFailure(RuntimeException failure) {
    if (failure instanceof jakarta.ws.rs.WebApplicationException) return failure;
    if (failure instanceof io.fabric8.kubernetes.client.KubernetesClientException clientFailure) {
      if (clientFailure.getCode() == 403) return fail(403, "KUBERNETES_RBAC_DENIED",
          "Kubernetes 凭据缺少 Multus、Pod、Deployment、Service 或 EndpointSlice 所需权限");
      if (clientFailure.getCode() == 404) return fail(422, "APPLICATION_NETWORK_CNI_UNAVAILABLE",
          "集群中未发现 Multus NetworkAttachmentDefinition API");
    }
    return failure;
  }

  private String safeDetail(RuntimeException failure) {
    if (failure instanceof jakarta.ws.rs.WebApplicationException web) {
      return "运行时协调未完成，HTTP " + web.getResponse().getStatus();
    }
    return "运行时协调未完成；保留现有资源和 CIDR 预留，需对账后补偿";
  }

  private UUID uuid(Object value) {
    if (value instanceof UUID id) return id;
    try { return UUID.fromString(String.valueOf(value)); }
    catch (IllegalArgumentException invalid) { throw fail(500, "APPLICATION_NETWORK_RUNTIME_CORRUPT", "应用网络运行时 ID 无效"); }
  }

  private Instant instant(Object value) {
    if (value == null) return null;
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof Instant instant) return instant;
    if (value instanceof Number number) return Instant.ofEpochMilli(number.longValue());
    return Instant.parse(String.valueOf(value));
  }
}
