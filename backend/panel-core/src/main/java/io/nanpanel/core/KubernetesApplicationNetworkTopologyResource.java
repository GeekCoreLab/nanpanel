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
  private static final String EMPTY_GRAPH = "{\"nodes\":[],\"links\":[],\"attachments\":[]}";

  @Inject AuthStore store;
  @Inject ObjectMapper mapper;
  @Inject TopologyResource topology;
  @Context ContainerRequestContext request;

  public record ApplicationAttachment(String id, String resourceId, String nodeId,
      String devicePort, String direction) {}
  public record NetworkTopologyWrite(List<TopologyResource.NodeView> nodes,
      List<TopologyResource.LinkInput> links, List<ApplicationAttachment> attachments) {}
  public record NetworkTopologyView(String revisionId, long generation,
      List<TopologyResource.NodeView> nodes, List<TopologyResource.LinkInput> links,
      List<ApplicationAttachment> attachments,
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
                + draft.attachments().size() + " 个应用工作负载接入");
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
    return new NetworkTopologyView(null, 0, List.of(), List.of(), List.of(), null, null);
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
        payload.nodes().size(), payload.links().size() + payload.attachments().size(),
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
    if (!objectWithKeys(body, Set.of("nodes", "links", "attachments"))
        || !body.path("attachments").isArray() || body.path("attachments").size() > 256) {
      throw fail(422, "INVALID_APPLICATION_NETWORK_TOPOLOGY",
          "应用网络拓扑必须包含 nodes、links 与 attachments 数组");
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
      return new NetworkTopologyWrite(graph.nodes(), graph.links(), List.copyOf(attachments));
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
      occupiedPorts.add(link.sourceNodeId() + ":" + link.sourcePort());
      occupiedPorts.add(link.targetNodeId() + ":" + link.targetPort());
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
      if (!occupiedPorts.add(node.id() + ":" + attachment.devicePort())) {
        return "每个设备端口只能连接一条拓扑链路或一个应用工作负载";
      }
    }
    return null;
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
