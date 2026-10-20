package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthResource.number;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nanpanel.domain.NetworkGraph;
import io.nanpanel.domain.NetworkGraph.Direction;
import io.nanpanel.domain.NetworkGraph.Layer;
import io.nanpanel.domain.NetworkGraph.Link;
import io.nanpanel.domain.NetworkGraph.Node;
import io.nanpanel.domain.NetworkGraph.Port;
import io.nanpanel.domain.NetworkGraph.Protocol;
import io.nanpanel.network.NetworkGraphValidator;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Path("/api/v1/topology")
@Produces(MediaType.APPLICATION_JSON)
public class TopologyResource {
  private static final Pattern ETAG = Pattern.compile("\\\"topology-([0-9]+)\\\"");
  private static final Set<String> PORT_PROTOCOLS = Set.of(
      "ETHERNET", "IP", "TCP", "UDP", "SCTP", "HTTP", "HTTPS");

  @Inject AuthStore store;
  @Inject ObjectMapper mapper;
  @Context ContainerRequestContext request;

  public record Position(double x, double y) {}
  public record PortView(String id, String direction, String layer, List<String> protocols) {}
  public record NodeView(String id, String kind, String name, Position position,
                         int inputCount, int outputCount, List<PortView> ports) {}
  public record LinkView(String id, String sourceNodeId, String sourcePort, String targetNodeId,
                         String targetPort, String layer, String protocol, String status,
                         String state) {}
  public record DraftWrite(List<NodeView> nodes, List<LinkInput> links) {}
  public record LinkInput(String id, String sourceNodeId, String sourcePort, String targetNodeId,
                          String targetPort, String layer, String protocol, String status) {}
  public record DraftView(String revisionId, long generation, List<NodeView> nodes,
                          List<LinkView> links, String updatedAt, String updatedBy) {}
  public record RevisionList(List<RevisionSummary> items) {}
  public record RevisionSummary(String revisionId, long generation, int nodeCount, int linkCount,
                                String updatedAt, String updatedBy) {}

  private Map<String, Object> session() {
    @SuppressWarnings("unchecked")
    Map<String, Object> value = (Map<String, Object>)request.getProperty("session");
    if (value == null || value.get("user_id") == null) {
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    }
    return value;
  }

  @GET
  @Path("draft")
  public Response current() {
    session();
    Map<String, Object> row = store.transaction(c -> query(c, "SELECT * FROM topology_head WHERE id=1")
        .stream().findFirst().orElseThrow(() -> new IllegalStateException("Topology head is missing")));
    DraftView view = view(row);
    return Response.ok(view).header("ETag", etag(number(row, "generation")))
        .header("Cache-Control", "no-store").build();
  }

  @PUT
  @Path("draft")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response replace(JsonNode body, @HeaderParam("If-Match") String ifMatch) {
    Map<String, Object> actor = session();
    long expected = expectedGeneration(ifMatch);
    DraftWrite payload = parse(body);
    NetworkGraph graph = graph(payload);
    String invalid = NetworkGraphValidator.error(graph);
    if (invalid != null) {
      throw fail(422, "INVALID_TOPOLOGY", invalid);
    }
    String snapshot = encode(payload);
    UUID revisionId = UUID.randomUUID();
    long now = System.currentTimeMillis();
    Map<String, Object> row = store.transaction(c -> {
      Map<String, Object> head = query(c, "SELECT * FROM topology_head WHERE id=1").getFirst();
      long generation = number(head, "generation");
      if (generation != expected) {
        throw fail(412, "PRECONDITION_FAILED", "拓扑已被修改，请重新读取后确认再保存");
      }
      long next = generation + 1;
      int changed = update(c,
          "UPDATE topology_head SET revision_id=?,generation=?,snapshot=?,updated_by=?,updated_at=? "
              + "WHERE id=1 AND generation=?",
          revisionId.toString(), next, snapshot, actor.get("user_id"), now, generation);
      if (changed != 1) {
        throw fail(412, "PRECONDITION_FAILED", "拓扑已被其他请求修改，请重新读取后确认再保存");
      }
      update(c, "INSERT INTO topology_revision (id,generation,snapshot,updated_by,updated_at) "
              + "VALUES (?,?,?,?,?)",
          revisionId.toString(), next, snapshot, actor.get("user_id"), now);
      audit(c, String.valueOf(actor.get("user_id")), "TOPOLOGY_DRAFT_UPDATED", "topology",
          revisionId.toString(), "SUCCEEDED", null,
          "保存期望拓扑 Revision：" + payload.nodes().size() + " 个节点、"
              + payload.links().size() + " 条连接");
      return query(c, "SELECT * FROM topology_head WHERE id=1").getFirst();
    });
    return Response.ok(view(row)).header("ETag", etag(number(row, "generation")))
        .header("Cache-Control", "no-store").build();
  }

  @GET
  @Path("revisions")
  public RevisionList revisions(@QueryParam("limit") @DefaultValue("50") int limit) {
    session();
    if (limit < 1 || limit > 100) {
      throw fail(400, "VALIDATION_FAILED", "limit 必须在 1 到 100 之间");
    }
    List<Map<String, Object>> rows = store.transaction(c -> query(c,
        "SELECT id,generation,snapshot,updated_by,updated_at FROM topology_revision "
            + "ORDER BY generation DESC LIMIT ?", limit));
    return new RevisionList(rows.stream().map(row -> summary(row)).toList());
  }

  @GET
  @Path("revisions/{revisionId}")
  public DraftView revision(@PathParam("revisionId") String revisionId) {
    session();
    UUID id = uuid(revisionId);
    Map<String, Object> row = store.transaction(c -> {
      List<Map<String, Object>> rows = query(c,
          "SELECT id,generation,snapshot,updated_by,updated_at FROM topology_revision WHERE id=?",
          id.toString());
      if (rows.isEmpty()) {
        throw fail(404, "NOT_FOUND", "拓扑 Revision 不存在");
      }
      return rows.getFirst();
    });
    return view(row);
  }

  private long expectedGeneration(String value) {
    if (value == null || value.isBlank()) {
      throw fail(428, "PRECONDITION_REQUIRED", "保存拓扑草稿需要 If-Match");
    }
    Matcher matcher = ETAG.matcher(value);
    if (!matcher.matches()) {
      throw fail(400, "VALIDATION_FAILED", "If-Match 必须是读取草稿时返回的单个强 ETag");
    }
    try {
      return Long.parseLong(matcher.group(1));
    } catch (NumberFormatException failure) {
      throw fail(400, "VALIDATION_FAILED", "If-Match 中的拓扑版本无效");
    }
  }

  private DraftWrite parse(JsonNode body) {
    if (!objectWithKeys(body, Set.of("nodes", "links")) || !body.path("nodes").isArray()
        || !body.path("links").isArray()) {
      throw fail(422, "INVALID_TOPOLOGY", "拓扑请求必须只包含 nodes 与 links 数组");
    }
    if (body.path("nodes").size() > 100 || body.path("links").size() > 800) {
      throw fail(422, "INVALID_TOPOLOGY", "拓扑最多包含 100 个节点与 800 条连接");
    }
    for (JsonNode node : body.path("nodes")) {
      if (!objectWithKeys(node, Set.of("id", "kind", "name", "position", "inputCount",
          "outputCount", "ports"))) {
        throw fail(422, "INVALID_TOPOLOGY", "节点包含缺失字段或未定义字段");
      }
      if (!objectWithKeys(node.path("position"), Set.of("x", "y"))
          || !node.path("ports").isArray()
          || !node.path("id").isTextual() || !node.path("kind").isTextual()
          || !node.path("name").isTextual() || !node.path("position").path("x").isNumber()
          || !node.path("position").path("y").isNumber()
          || !node.path("inputCount").isIntegralNumber()
          || !node.path("outputCount").isIntegralNumber()) {
        throw fail(422, "INVALID_TOPOLOGY", "节点坐标或端口字段无效");
      }
      for (JsonNode port : node.path("ports")) {
        if (!objectWithKeys(port, Set.of("id", "direction", "layer", "protocols"))
            || !port.path("protocols").isArray() || !port.path("id").isTextual()
            || !port.path("direction").isTextual() || !port.path("layer").isTextual()) {
          throw fail(422, "INVALID_TOPOLOGY", "端口必须包含 id、direction、layer 与 protocols");
        }
        for (JsonNode protocol : port.path("protocols")) {
          if (!protocol.isTextual()) {
            throw fail(422, "INVALID_TOPOLOGY", "端口协议必须使用已定义的字符串枚举");
          }
        }
      }
    }
    for (JsonNode link : body.path("links")) {
      if (!objectWithKeys(link, Set.of("id", "sourceNodeId", "sourcePort", "targetNodeId",
          "targetPort", "layer", "protocol", "status"))
          || !link.path("id").isTextual() || !link.path("sourceNodeId").isTextual()
          || !link.path("sourcePort").isTextual() || !link.path("targetNodeId").isTextual()
          || !link.path("targetPort").isTextual() || !link.path("layer").isTextual()
          || !link.path("protocol").isTextual()
          || !"UNVERIFIED".equals(link.path("status").asText())) {
        throw fail(422, "INVALID_TOPOLOGY", "连接类型无效；只允许提交期望状态");
      }
    }
    try {
      DraftWrite value = mapper.treeToValue(body, DraftWrite.class);
      return normalize(value);
    } catch (JsonProcessingException | IllegalArgumentException failure) {
      throw fail(422, "INVALID_TOPOLOGY", "拓扑字段类型或枚举值无效");
    }
  }

  private DraftWrite normalize(DraftWrite input) {
    if (input.nodes() == null || input.links() == null) {
      throw fail(422, "INVALID_TOPOLOGY", "拓扑必须包含 nodes 与 links");
    }
    List<NodeView> nodes = input.nodes().stream().map(node -> {
      if (node == null || node.ports() == null || node.position() == null) {
        throw fail(422, "INVALID_TOPOLOGY", "节点字段不能为空");
      }
      List<PortView> ports = node.ports().stream().map(port -> {
        if (port == null || port.protocols() == null || port.protocols().isEmpty()) {
          throw fail(422, "INVALID_TOPOLOGY", "端口字段不能为空");
        }
        return new PortView(port.id(), port.direction(), port.layer(), List.copyOf(port.protocols()));
      }).toList();
      return new NodeView(node.id(), node.kind(), node.name() == null ? null : node.name().strip(),
          node.position(), node.inputCount(), node.outputCount(), ports);
    }).toList();
    List<LinkInput> links = input.links().stream().map(link -> {
      if (link == null) {
        throw fail(422, "INVALID_TOPOLOGY", "连接字段不能为空");
      }
      return link;
    }).toList();
    return new DraftWrite(nodes, links);
  }

  private NetworkGraph graph(DraftWrite value) {
    try {
      List<Node> nodes = new ArrayList<>();
      for (NodeView node : value.nodes()) {
        UUID id = uuid(node.id());
        List<Port> ports = new ArrayList<>();
        Set<String> uniquePortIds = new HashSet<>();
        for (PortView port : node.ports()) {
          if (!uniquePortIds.add(port.id())) {
            throw fail(422, "INVALID_TOPOLOGY", "节点端口 ID 重复");
          }
          Direction direction = Direction.valueOf(port.direction());
          Layer layer = Layer.valueOf(port.layer());
          Set<Protocol> protocols = new HashSet<>();
          for (String protocol : port.protocols()) {
            if (!PORT_PROTOCOLS.contains(protocol) || !protocols.add(Protocol.valueOf(protocol))) {
              throw fail(422, "INVALID_TOPOLOGY", "端口协议无效");
            }
          }
          ports.add(new Port(port.id(), direction, layer, protocols));
        }
        long inputs = ports.stream().filter(port -> port.direction() == Direction.INPUT).count();
        long outputs = ports.stream().filter(port -> port.direction() == Direction.OUTPUT).count();
        if (inputs != node.inputCount() || outputs != node.outputCount()) {
          throw fail(422, "INVALID_TOPOLOGY", "端口数组与入口、出口数量不一致");
        }
        nodes.add(new Node(id, node.kind(), node.name(),
            new NetworkGraph.Position(node.position().x(), node.position().y()), null, ports));
      }
      List<Link> links = value.links().stream().map(link -> new Link(uuid(link.id()),
          uuid(link.sourceNodeId()), link.sourcePort(), uuid(link.targetNodeId()), link.targetPort(),
          Layer.valueOf(link.layer()), Protocol.valueOf(link.protocol()))).toList();
      return new NetworkGraph(UUID.randomUUID(), 0, nodes, links);
    } catch (RuntimeException failure) {
      throw fail(422, "INVALID_TOPOLOGY", "节点、端口或连接枚举值无效");
    }
  }

  private String encode(DraftWrite value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("Validated topology could not be serialized", failure);
    }
  }

  private DraftView view(Map<String, Object> row) {
    DraftWrite payload;
    try {
      payload = mapper.readValue(String.valueOf(row.get("snapshot")), DraftWrite.class);
    } catch (IOException failure) {
      throw fail(500, "TOPOLOGY_CORRUPT", "保存的拓扑 Revision 无法读取");
    }
    String id = String.valueOf(row.get("revision_id") == null ? row.get("id") : row.get("revision_id"));
    long generation = number(row, "generation");
    List<LinkView> links = payload.links().stream().map(link -> new LinkView(link.id(),
        link.sourceNodeId(), link.sourcePort(), link.targetNodeId(), link.targetPort(),
        link.layer(), link.protocol(), link.status(), "PENDING")).toList();
    return new DraftView(id, generation, payload.nodes(), links,
        Instant.ofEpochMilli(number(row, "updated_at")).toString(),
        row.get("updated_by") == null ? null : String.valueOf(row.get("updated_by")));
  }

  private RevisionSummary summary(Map<String, Object> row) {
    DraftWrite payload;
    try {
      payload = mapper.readValue(String.valueOf(row.get("snapshot")), DraftWrite.class);
    } catch (IOException failure) {
      throw fail(500, "TOPOLOGY_CORRUPT", "保存的拓扑 Revision 无法读取");
    }
    return new RevisionSummary(String.valueOf(row.get("id")), number(row, "generation"),
        payload.nodes().size(), payload.links().size(),
        Instant.ofEpochMilli(number(row, "updated_at")).toString(),
        row.get("updated_by") == null ? null : String.valueOf(row.get("updated_by")));
  }

  private boolean objectWithKeys(JsonNode value, Set<String> keys) {
    if (value == null || !value.isObject() || value.size() != keys.size()) {
      return false;
    }
    var names = value.fieldNames();
    while (names.hasNext()) {
      if (!keys.contains(names.next())) {
        return false;
      }
    }
    return true;
  }

  private UUID uuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equalsIgnoreCase(value)) {
        throw new IllegalArgumentException("UUID is not canonical");
      }
      return parsed;
    } catch (RuntimeException failure) {
      throw fail(422, "INVALID_TOPOLOGY", "拓扑节点、连接或 Revision ID 必须是 UUID");
    }
  }

  private String etag(long generation) {
    return "\"topology-" + generation + "\"";
  }
}
