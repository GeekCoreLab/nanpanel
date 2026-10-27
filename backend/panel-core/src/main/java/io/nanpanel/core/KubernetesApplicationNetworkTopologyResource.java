package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthResource.number;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.container.ContainerRequestContext;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Per-application desired virtual-device graph. Runtime attachment is managed separately. */
@Path("/api/v1/kubernetes/applications/{applicationId}/network-topology")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class KubernetesApplicationNetworkTopologyResource {
  private static final Pattern ETAG = Pattern.compile("\\\"application-network-([0-9]+)\\\"");
  private static final String EMPTY_GRAPH = "{\"nodes\":[],\"links\":[],\"attachments\":[],\"trafficLinks\":[]}";

  @Inject AuthStore store;
  @Inject ObjectMapper mapper;
  @Inject TopologyResource topology;
  @Context ContainerRequestContext request;

  public record ApplicationAttachment(String id, String resourceId, String nodeId,
      String devicePort, String direction) {}
  public record ApplicationTrafficLink(String id, String type, String sourceId,
      String sourceHandle, String targetId, String targetHandle, String protocol,
      Integer servicePort) {}
  public record NetworkTopologyWrite(List<TopologyResource.NodeView> nodes,
      List<TopologyResource.LinkInput> links, List<ApplicationAttachment> attachments,
      List<ApplicationTrafficLink> trafficLinks) {}
  public record NetworkTopologyView(String revisionId, long generation,
      List<TopologyResource.NodeView> nodes, List<TopologyResource.LinkInput> links,
      List<ApplicationAttachment> attachments, List<ApplicationTrafficLink> trafficLinks,
      String updatedAt, String updatedBy) {}
  public record RevisionList(List<RevisionSummary> items) {}
  public record RevisionSummary(String revisionId, long generation, int nodeCount,
      int linkCount, String updatedAt, String updatedBy) {}

  @GET
  public Response current(@PathParam("applicationId") String applicationValue) {
    actor();
    return execute(() -> {
      UUID applicationId = uuid(applicationValue);
      requireApplication(applicationId);
      Map<String, Object> row = store.transaction(connection -> query(connection,
          "SELECT revision_id,generation,snapshot,updated_by,updated_at "
              + "FROM kubernetes_application_network_topology_head WHERE application_id=?",
          applicationId).stream().findFirst().orElse(null));
      NetworkTopologyView view = row == null ? emptyView() : view(row, "revision_id");
      return Response.ok(view).header("ETag", etag(view.generation())).build();
    });
  }

  @PUT
  public Response replace(@PathParam("applicationId") String applicationValue,
      @HeaderParam("If-Match") String ifMatch, JsonNode body) {
    String actor = actor();
    return execute(() -> {
      UUID applicationId = uuid(applicationValue);
      requireApplication(applicationId);
      boolean runtimeActive = store.transaction(connection -> !query(connection,
          "SELECT application_id FROM kubernetes_application_network_runtime "
              + "WHERE application_id=? AND state IN "
              + "('APPLYING','APPLIED','VERIFYING','VERIFY_FAILED','COMPENSATING','FAILED','PARTIAL')",
          applicationId).isEmpty() || !query(connection,
              "SELECT application_id FROM kubernetes_application_traffic_runtime "
                  + "WHERE application_id=? AND state IN "
                  + "('APPLYING','APPLIED','VERIFY_FAILED','COMPENSATING','FAILED','PARTIAL')",
              applicationId).isEmpty());
      if (runtimeActive) throw fail(409, "APPLICATION_NETWORK_RUNTIME_ACTIVE",
          "应用网络运行时已接入；请先补偿恢复 Deployment 与 Service 后再修改拓扑");
      long expected = expectedGeneration(ifMatch);
      NetworkTopologyWrite draft = parse(applicationId, body);
      TopologyResource.DraftWrite deviceGraph = new TopologyResource.DraftWrite(
          draft.nodes(), draft.links());
      String invalid = topology.applicationDraftError(deviceGraph);
      if (invalid != null) throw fail(422, "INVALID_APPLICATION_NETWORK_TOPOLOGY", invalid);
      invalid = attachmentError(applicationId, draft);
      if (invalid != null) throw fail(422, "INVALID_APPLICATION_NETWORK_TOPOLOGY", invalid);
      String snapshot = encode(draft);
      UUID revisionId = UUID.randomUUID();
      long now = System.currentTimeMillis();
      Map<String, Object> row = store.transaction(connection -> {
        query(connection, "SELECT id FROM kubernetes_application WHERE id=? FOR UPDATE", applicationId);
        boolean runtimeBlocked = !query(connection,
            "SELECT application_id FROM kubernetes_application_network_runtime "
                + "WHERE application_id=? AND state IN "
                + "('APPLYING','APPLIED','VERIFYING','VERIFY_FAILED','COMPENSATING','FAILED','PARTIAL') FOR UPDATE",
            applicationId).isEmpty() || !query(connection,
                "SELECT application_id FROM kubernetes_application_traffic_runtime "
                    + "WHERE application_id=? AND state IN "
                    + "('APPLYING','APPLIED','VERIFY_FAILED','COMPENSATING','FAILED','PARTIAL') FOR UPDATE",
                applicationId).isEmpty();
        if (runtimeBlocked) throw fail(409, "APPLICATION_NETWORK_RUNTIME_ACTIVE",
            "应用网络运行时已接入；请先补偿恢复 Deployment 与 Service 后再修改拓扑");
        update(connection,
            "INSERT INTO kubernetes_application_network_topology_head "
                + "(application_id,generation,snapshot,updated_at) VALUES (?,0,?,?) "
                + "ON CONFLICT (application_id) DO NOTHING",
            applicationId, EMPTY_GRAPH, now);
        Map<String, Object> head = query(connection,
            "SELECT revision_id,generation FROM kubernetes_application_network_topology_head "
                + "WHERE application_id=? FOR UPDATE", applicationId).getFirst();
        long generation = number(head, "generation");
        if (generation != expected) {
          throw fail(412, "PRECONDITION_FAILED",
              "该应用网络拓扑已修改，请重新读取后确认再保存");
        }
        long next = generation + 1;
        int changed = update(connection,
            "UPDATE kubernetes_application_network_topology_head SET revision_id=?,generation=? "
                + ",snapshot=?,updated_by=?,updated_at=? WHERE application_id=? AND generation=?",
            revisionId, next, snapshot, actor, now, applicationId, generation);
        if (changed != 1) {
          throw fail(412, "PRECONDITION_FAILED",
              "该应用网络拓扑已被其他请求修改，请重新读取后确认再保存");
        }
        update(connection,
            "INSERT INTO kubernetes_application_network_topology_revision "
                + "(application_id,id,generation,snapshot,updated_by,updated_at) "
                + "VALUES (?,?,?,?,?,?)",
            applicationId, revisionId, next, snapshot, actor, now);
        audit(connection, actor, "KUBERNETES_APPLICATION_NETWORK_TOPOLOGY_UPDATED",
            "kubernetes_application", applicationId.toString(), "SUCCEEDED", null,
            "保存应用虚拟设备拓扑 Revision：" + draft.nodes().size() + " 个设备、"
                + draft.links().size() + " 条设备连接、"
                + draft.attachments().size() + " 个应用工作负载接入、"
                + draft.trafficLinks().size() + " 条业务流量边");
        return query(connection,
            "SELECT revision_id,generation,snapshot,updated_by,updated_at "
                + "FROM kubernetes_application_network_topology_head WHERE application_id=?",
            applicationId).getFirst();
      });
      NetworkTopologyView result = view(row, "revision_id");
      return Response.ok(result).header("ETag", etag(result.generation())).build();
    });
  }

  @GET
  @Path("revisions")
  public RevisionList revisions(@PathParam("applicationId") String applicationValue,
      @QueryParam("limit") Integer requestedLimit) {
    actor();
    UUID applicationId = uuid(applicationValue);
    requireApplication(applicationId);
    int limit = requestedLimit == null ? 50 : requestedLimit;
    if (limit < 1 || limit > 100) throw fail(400, "VALIDATION_FAILED",
        "limit 必须在 1 到 100 之间");
    List<Map<String, Object>> rows = store.transaction(connection -> query(connection,
        "SELECT id,generation,snapshot,updated_by,updated_at "
            + "FROM kubernetes_application_network_topology_revision "
            + "WHERE application_id=? ORDER BY generation DESC LIMIT ?", applicationId, limit));
    return new RevisionList(rows.stream().map(this::summary).toList());
  }

  @GET
  @Path("revisions/{revisionId}")
  public NetworkTopologyView revision(@PathParam("applicationId") String applicationValue,
      @PathParam("revisionId") String revisionValue) {
    actor();
    UUID applicationId = uuid(applicationValue);
    UUID revisionId = uuid(revisionValue);
    requireApplication(applicationId);
    Map<String, Object> row = store.transaction(connection -> query(connection,
        "SELECT id,generation,snapshot,updated_by,updated_at "
            + "FROM kubernetes_application_network_topology_revision "
            + "WHERE application_id=? AND id=?", applicationId, revisionId)
        .stream().findFirst().orElse(null));
    if (row == null) throw fail(404, "NOT_FOUND", "应用网络拓扑 Revision 不存在");
    return view(row, "id");
  }

  private void requireApplication(UUID applicationId) {
    boolean exists = store.transaction(connection -> !query(connection,
        "SELECT id FROM kubernetes_application WHERE id=?", applicationId).isEmpty());
    if (!exists) throw fail(404, "KUBERNETES_APPLICATION_NOT_FOUND", "Kubernetes 应用不存在");
  }

  private NetworkTopologyView emptyView() {
    return new NetworkTopologyView(null, 0, List.of(), List.of(), List.of(), List.of(), null, null);
  }

  private NetworkTopologyView view(Map<String, Object> row, String idColumn) {
    NetworkTopologyWrite payload;
    try {
      payload = mapper.readValue(String.valueOf(row.get("snapshot")), NetworkTopologyWrite.class);
    } catch (IOException failure) {
      throw fail(500, "APPLICATION_NETWORK_TOPOLOGY_CORRUPT",
          "保存的应用网络拓扑 Revision 无法读取");
    }
    Object revisionId = row.get(idColumn);
    return new NetworkTopologyView(revisionId == null ? null : String.valueOf(revisionId),
        number(row, "generation"), payload.nodes(), payload.links(), payload.attachments(),
        payload.trafficLinks() == null ? List.of() : payload.trafficLinks(),
        Instant.ofEpochMilli(number(row, "updated_at")).toString(),
        row.get("updated_by") == null ? null : String.valueOf(row.get("updated_by")));
  }

  private RevisionSummary summary(Map<String, Object> row) {
    NetworkTopologyWrite payload;
    try {
      payload = mapper.readValue(String.valueOf(row.get("snapshot")), NetworkTopologyWrite.class);
    } catch (IOException failure) {
      throw fail(500, "APPLICATION_NETWORK_TOPOLOGY_CORRUPT",
          "保存的应用网络拓扑 Revision 无法读取");
    }
    return new RevisionSummary(String.valueOf(row.get("id")), number(row, "generation"),
        payload.nodes().size(), payload.links().size() + payload.attachments().size()
            + (payload.trafficLinks() == null ? 0 : payload.trafficLinks().size()),
        Instant.ofEpochMilli(number(row, "updated_at")).toString(),
        row.get("updated_by") == null ? null : String.valueOf(row.get("updated_by")));
  }

  private long expectedGeneration(String value) {
    if (value == null || value.isBlank()) {
      throw fail(428, "PRECONDITION_REQUIRED", "保存应用网络拓扑需要 If-Match");
    }
    Matcher matcher = ETAG.matcher(value);
    if (!matcher.matches()) throw fail(400, "VALIDATION_FAILED",
        "If-Match 必须是读取应用网络拓扑时返回的单个强 ETag");
    try {
      return Long.parseLong(matcher.group(1));
    } catch (NumberFormatException failure) {
      throw fail(400, "VALIDATION_FAILED", "If-Match 中的应用网络拓扑版本无效");
    }
  }

  private NetworkTopologyWrite parse(UUID applicationId, JsonNode body) {
    if (!objectHasRequiredKeys(body, Set.of("nodes", "links", "attachments"),
            Set.of("nodes", "links", "attachments", "trafficLinks"))
        || !body.path("attachments").isArray() || body.path("attachments").size() > 256) {
      throw fail(422, "INVALID_APPLICATION_NETWORK_TOPOLOGY",
          "应用网络拓扑必须包含 nodes、links 与 attachments 数组，trafficLinks 可选");
    }
    JsonNode trafficLinksNode = body.path("trafficLinks");
    if (!trafficLinksNode.isMissingNode()
        && (!trafficLinksNode.isArray() || trafficLinksNode.size() > 512)) {
      throw fail(422, "INVALID_APPLICATION_NETWORK_TOPOLOGY",
          "trafficLinks 必须是最多包含 512 条边的数组");
    }
    for (JsonNode attachment : body.path("attachments")) {
      if (!objectWithKeys(attachment,
          Set.of("id", "resourceId", "nodeId", "devicePort", "direction"))
          || !attachment.path("id").isTextual()
          || !attachment.path("resourceId").isTextual()
          || !attachment.path("nodeId").isTextual()
          || !attachment.path("devicePort").isTextual()
          || !attachment.path("direction").isTextual()) {
        throw fail(422, "INVALID_APPLICATION_NETWORK_TOPOLOGY",
            "工作负载接入必须包含 id、resourceId、nodeId、devicePort 与 direction");
      }
    }
    try {
      JsonNode graphBody = mapper.createObjectNode().set("nodes", body.path("nodes"));
      ((com.fasterxml.jackson.databind.node.ObjectNode)graphBody).set("links", body.path("links"));
      TopologyResource.DraftWrite graph = topology.parseApplicationDraft(graphBody);
      List<ApplicationAttachment> attachments = new ArrayList<>();
      for (JsonNode entry : body.path("attachments")) {
        attachments.add(new ApplicationAttachment(entry.path("id").asText(),
            entry.path("resourceId").asText(), entry.path("nodeId").asText(),
            entry.path("devicePort").asText(), entry.path("direction").asText()));
      }
      List<ApplicationTrafficLink> trafficLinks = new ArrayList<>();
      for (JsonNode entry : trafficLinksNode) {
        if (!objectWithKeys(entry, Set.of("id", "type", "sourceId", "sourceHandle",
                "targetId", "targetHandle", "protocol", "servicePort"))
            || !entry.path("id").isTextual() || !entry.path("type").isTextual()
            || !entry.path("sourceId").isTextual() || !entry.path("sourceHandle").isTextual()
            || !entry.path("targetId").isTextual() || !entry.path("targetHandle").isTextual()
            || !entry.path("protocol").isTextual()
            || (!entry.path("servicePort").isNull() && !entry.path("servicePort").canConvertToInt())) {
          throw fail(422, "INVALID_APPLICATION_NETWORK_TOPOLOGY", "业务流量边字段无效");
        }
        trafficLinks.add(new ApplicationTrafficLink(entry.path("id").asText(),
            entry.path("type").asText(), entry.path("sourceId").asText(),
            entry.path("sourceHandle").asText(), entry.path("targetId").asText(),
            entry.path("targetHandle").asText(), entry.path("protocol").asText(),
            entry.path("servicePort").isNull() ? null : entry.path("servicePort").asInt()));
      }
      return new NetworkTopologyWrite(graph.nodes(), graph.links(), List.copyOf(attachments),
          List.copyOf(trafficLinks));
    } catch (RuntimeException failure) {
      if (failure instanceof jakarta.ws.rs.WebApplicationException) throw failure;
      throw fail(422, "INVALID_APPLICATION_NETWORK_TOPOLOGY", "应用网络拓扑字段无效");
    }
  }

  private String attachmentError(UUID applicationId, NetworkTopologyWrite draft) {
    Set<String> ids = new HashSet<>();
    Set<String> attachedResources = new HashSet<>();
    Set<String> occupiedPorts = new HashSet<>();
    Map<String, TopologyResource.NodeView> nodes = new HashMap<>();
    draft.nodes().forEach(node -> nodes.put(node.id(), node));
    draft.links().forEach(link -> {
      ids.add(link.id());
      occupiedPorts.add(physicalPortKey(nodes.get(link.sourceNodeId()), link.sourcePort()));
      occupiedPorts.add(physicalPortKey(nodes.get(link.targetNodeId()), link.targetPort()));
    });
    Set<String> resources = store.transaction(connection -> query(connection,
        "SELECT 'Deployment/' || resource_name AS resource_id "
            + "FROM kubernetes_application_resource WHERE application_id=? "
            + "AND resource_kind='Deployment'", applicationId).stream()
        .map(row -> String.valueOf(row.get("resource_id"))).collect(java.util.stream.Collectors.toSet()));
    for (ApplicationAttachment attachment : draft.attachments()) {
      if (!ids.add(attachment.id())) return "连接和工作负载接入 ID 不能重复";
      if (!isCanonicalUuid(attachment.id()) || !resources.contains(attachment.resourceId())) {
        return "工作负载接入必须引用 UUID 和当前应用已登记的 Deployment";
      }
      if (!attachedResources.add(attachment.resourceId())) {
        return "每个 Deployment 当前只允许一个次级网络接入点";
      }
      if (!Set.of("RESOURCE_TO_DEVICE", "DEVICE_TO_RESOURCE").contains(attachment.direction())) {
        return "工作负载接入方向无效";
      }
      TopologyResource.NodeView node = nodes.get(attachment.nodeId());
      if (node == null) return "工作负载接入引用的虚拟设备不存在";
      String expectedDirection = "RESOURCE_TO_DEVICE".equals(attachment.direction())
          ? "INPUT" : "OUTPUT";
      boolean portExists = node.ports().stream().anyMatch(port ->
          port.id().equals(attachment.devicePort())
              && expectedDirection.equals(port.direction()));
      if (!portExists) return "工作负载接入的方向与设备端口方向不匹配";
      if (!occupiedPorts.add(physicalPortKey(node, attachment.devicePort()))) {
        return "每个设备端口只能连接一条拓扑链路或一个应用工作负载";
      }
    }
    return trafficLinkError(applicationId, draft, ids);
  }

  private String trafficLinkError(UUID applicationId, NetworkTopologyWrite draft,
      Set<String> existingIds) {
    Set<String> resourceIds = store.transaction(connection -> query(connection,
        "SELECT resource_kind || '/' || resource_name AS resource_id "
            + "FROM kubernetes_application_resource WHERE application_id=? "
            + "AND resource_kind IN ('Deployment','Service')", applicationId).stream()
        .map(row -> String.valueOf(row.get("resource_id")))
        .collect(java.util.stream.Collectors.toSet()));
    Map<String, TopologyResource.NodeView> devices = new HashMap<>();
    draft.nodes().forEach(node -> devices.put(node.id(), node));
    Set<String> ids = new HashSet<>(existingIds);
    Set<String> usedRouteOutputs = new HashSet<>();
    Map<String, Integer> routeEntries = new HashMap<>();
    Map<String, String> routeEntryServices = new HashMap<>();
    Map<String, Integer> routeBackends = new HashMap<>();
    Set<String> usedEntryPorts = new HashSet<>();
    Map<String, Set<String>> usedBackendServices = new HashMap<>();
    for (ApplicationTrafficLink link : draft.trafficLinks()) {
      if (!isCanonicalUuid(link.id()) || !ids.add(link.id()) || !"HTTP".equals(link.protocol())) {
        return "业务流量边必须有唯一 UUID，首个路由 Provider 只支持 HTTP";
      }
      if ("CALL".equals(link.type())) {
        if (!resourceIds.contains(link.sourceId()) || !link.sourceId().startsWith("Deployment/")
            || !resourceIds.contains(link.targetId()) || !link.targetId().startsWith("Service/")
            || !"call-output".equals(link.sourceHandle())
            || !link.targetHandle().equals("call-input-" + link.servicePort())
            || link.servicePort() == null || link.servicePort() < 1 || link.servicePort() > 65535) {
          return "调用边必须从应用 Deployment 指向应用 Service 和有效端口";
        }
        continue;
      }
      if ("ROUTE_ENTRY".equals(link.type())) {
        TopologyResource.NodeView lb = devices.get(link.targetId());
        if (!resourceIds.contains(link.sourceId()) || !link.sourceId().startsWith("Service/")
            || lb == null || !"LOAD_BALANCER".equals(lb.kind())
            || !link.sourceHandle().equals("route-output-" + link.servicePort())
            || !"route-input".equals(link.targetHandle())
            || link.servicePort() == null || link.servicePort() < 1 || link.servicePort() > 65535
            || routeEntries.putIfAbsent(link.targetId(), link.servicePort()) != null
            || !usedEntryPorts.add(link.sourceId() + ":" + link.servicePort())) {
          return "路由入口必须从一个稳定 Service 和端口连接到唯一流量分配器";
        }
        routeEntryServices.put(link.targetId(), link.sourceId());
        continue;
      }
      if ("ROUTE_BACKEND".equals(link.type())) {
        TopologyResource.NodeView lb = devices.get(link.sourceId());
        if (lb == null || !"LOAD_BALANCER".equals(lb.kind())
            || !resourceIds.contains(link.targetId()) || !link.targetId().startsWith("Service/")
            || !link.sourceHandle().matches("route-out-[1-9][0-9]*")
            || link.servicePort() == null || link.servicePort() < 1 || link.servicePort() > 65535
            || !link.targetHandle().equals("backend-input-" + link.servicePort())
            || !usedRouteOutputs.add(link.sourceId() + ":" + link.sourceHandle())) {
          return "路由后端必须从流量分配器的唯一出口连接到应用 Service";
        }
        if (!usedBackendServices.computeIfAbsent(link.sourceId(), ignored -> new HashSet<>())
            .add(link.targetId())) {
          return "同一流量分配器不能重复连接同一个后端 Service";
        }
        routeBackends.merge(link.sourceId(), 1, Integer::sum);
        continue;
      }
      return "未知的业务流量边类型";
    }
    for (Map.Entry<String, Integer> entry : routeEntries.entrySet()) {
      if (routeBackends.getOrDefault(entry.getKey(), 0) < 1) {
        return "每个流量分配器至少需要一个逻辑后端 Service";
      }
      TopologyResource.NodeView lb = devices.get(entry.getKey());
      List<String> outputPorts = lb.ports().stream().filter(port -> "OUTPUT".equals(port.direction()))
          .map(TopologyResource.PortView::id).toList();
      long configuredBackends = usedRouteOutputs.stream()
          .filter(value -> value.startsWith(entry.getKey() + ":"))
          .count();
      if (configuredBackends != routeBackends.get(entry.getKey())
          || usedRouteOutputs.stream().filter(value -> value.startsWith(entry.getKey() + ":"))
              .map(value -> value.substring(value.indexOf(':') + 1))
              .anyMatch(port -> !port.startsWith("route-out-")
                  || !outputPorts.contains("out-" + port.substring("route-out-".length())))) {
        return "路由后端出口必须匹配流量分配器端口";
      }
      if (routeBackends.get(entry.getKey()) < 2) {
        return "流量分配器至少要连接两个后端 Service 才能执行分流";
      }
      if (draft.trafficLinks().stream().anyMatch(link -> "ROUTE_BACKEND".equals(link.type())
          && entry.getKey().equals(link.sourceId())
          && routeEntryServices.get(entry.getKey()).equals(link.targetId()))) {
        return "稳定 Service 不能同时作为此路由的入口和后端，避免路由环路";
      }
    }
    for (String lbId : routeBackends.keySet()) {
      if (!routeEntries.containsKey(lbId)) return "流量分配器后端缺少稳定 Service 路由入口";
    }
    return null;
  }

  private String physicalPortKey(TopologyResource.NodeView node, String portId) {
    if (node != null && "SWITCH".equals(node.kind())
        && (portId.startsWith("in-") || portId.startsWith("out-"))) {
      return node.id() + ":interface-" + portId.substring(portId.indexOf('-') + 1);
    }
    return (node == null ? "" : node.id()) + ":" + portId;
  }

  private boolean isCanonicalUuid(String value) {
    try {
      return UUID.fromString(value).toString().equalsIgnoreCase(value);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  private boolean objectWithKeys(JsonNode value, Set<String> keys) {
    if (value == null || !value.isObject() || value.size() != keys.size()) return false;
    var fields = value.fieldNames();
    while (fields.hasNext()) if (!keys.contains(fields.next())) return false;
    return true;
  }

  private boolean objectHasRequiredKeys(JsonNode value, Set<String> required, Set<String> allowed) {
    if (value == null || !value.isObject() || !value.fieldNames().hasNext()) return false;
    Set<String> actual = new HashSet<>();
    value.fieldNames().forEachRemaining(actual::add);
    return actual.containsAll(required) && allowed.containsAll(actual);
  }

  private String encode(NetworkTopologyWrite draft) {
    try {
      return mapper.writeValueAsString(draft);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Validated application topology could not be serialized",
          failure);
    }
  }

  @SuppressWarnings("unchecked")
  private String actor() {
    Map<String, Object> session = (Map<String, Object>)request.getProperty("session");
    if (session == null || session.get("user_id") == null) {
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    }
    return String.valueOf(session.get("user_id"));
  }

  private UUID uuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw fail(404, "KUBERNETES_APPLICATION_NOT_FOUND", "Kubernetes 应用不存在");
    }
  }

  private Response execute(Supplier<Response> operation) {
    return Response.fromResponse(operation.get()).header("Cache-Control", "no-store").build();
  }

  private String etag(long generation) {
    return "\"application-network-" + generation + "\"";
  }
}
