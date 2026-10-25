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
import io.nanpanel.plugin.api.NetworkProvider;
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
import java.util.HashMap;
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
  @Inject NetworkProviderRegistry providers;
  @Context ContainerRequestContext request;

  public record Position(double x, double y) {}
  public record PortView(String id, String direction, String layer, List<String> protocols) {}
  public record FirewallRuleView(String inputPort, String outputPort, String protocol,
                                 int destinationPort, String action) {}
  public record FirewallPolicyView(String defaultAction, List<FirewallRuleView> rules) {}
  public record RouterInterfaceAddressView(String port, String address) {}
  public record RouterStaticRouteView(String destination, String gateway, String outputPort) {}
  public record RouterConfigurationView(List<RouterInterfaceAddressView> interfaces,
                                        List<RouterStaticRouteView> routes) {}
  public record NodeView(String id, String kind, String name, Position position,
                         int inputCount, int outputCount, List<PortView> ports,
                         String parentId, String assetId, String clusterId, String ipAddress,
                         String description, String providerId, String switchBackend,
                         FirewallPolicyView firewallPolicy,
                         RouterConfigurationView routerConfiguration) {}
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
    String hierarchyError = hierarchyError(payload);
    if (hierarchyError != null) {
      throw fail(422, "INVALID_TOPOLOGY", hierarchyError);
    }
    NetworkGraph graph = graph(payload);
    String invalid = NetworkGraphValidator.error(graph);
    if (invalid != null) {
      throw fail(422, "INVALID_TOPOLOGY", invalid);
    }
    String providerInvalid = providerError(payload);
    if (providerInvalid != null) {
      throw fail(422, "INVALID_TOPOLOGY", providerInvalid);
    }
    String snapshot = encode(payload);
    UUID revisionId = UUID.randomUUID();
    long now = System.currentTimeMillis();
    Map<String, Object> row = store.transaction(c -> {
      Set<String> linkedAssets = new HashSet<>();
      for (NodeView node : payload.nodes()) {
        if (node.assetId() == null) {
          continue;
        }
        UUID assetId = uuid(node.assetId());
        if (!("HOST".equals(node.kind()) || "GATEWAY".equals(node.kind()))
            || !linkedAssets.add(assetId.toString())) {
          throw fail(422, "INVALID_TOPOLOGY", "主机资产只能由唯一主机或 Gateway 节点绑定");
        }
        if (query(c, "SELECT id FROM host WHERE id=?", assetId.toString()).isEmpty()) {
          throw fail(422, "INVALID_TOPOLOGY", "拓扑引用的主机资产不存在");
        }
      }
      for (NodeView node : payload.nodes()) {
        if (node.clusterId() == null) continue;
        UUID clusterId = uuid(node.clusterId());
        if (!"FOLDER".equals(node.kind())) {
          throw fail(422, "INVALID_TOPOLOGY", "只有文件夹节点可以关联 Kubernetes 集群");
        }
        if (query(c, "SELECT id FROM kubernetes_cluster WHERE id=?", clusterId).isEmpty()) {
          throw fail(422, "INVALID_TOPOLOGY", "拓扑引用的 Kubernetes 集群不存在");
        }
      }
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
      if (!objectWithNodeKeys(node)) {
        throw fail(422, "INVALID_TOPOLOGY", "节点包含缺失字段或未定义字段");
      }
      if (!objectWithKeys(node.path("position"), Set.of("x", "y"))
          || !node.path("ports").isArray()
          || !node.path("id").isTextual() || !node.path("kind").isTextual()
          || !node.path("name").isTextual() || !node.path("position").path("x").isNumber()
          || !node.path("position").path("y").isNumber()
          || !node.path("inputCount").isIntegralNumber()
          || !node.path("outputCount").isIntegralNumber()
          || node.has("ipAddress") && !node.get("ipAddress").isNull()
              && !node.get("ipAddress").isTextual()
          || node.has("providerId") && !node.get("providerId").isNull()
              && !node.get("providerId").isTextual()
          || node.has("switchBackend") && !node.get("switchBackend").isNull()
              && !node.get("switchBackend").isTextual()
          || node.has("firewallPolicy") && !node.get("firewallPolicy").isNull()
              && !validFirewallPolicyShape(node.get("firewallPolicy"))
          || node.has("routerConfiguration") && !node.get("routerConfiguration").isNull()
              && !validRouterConfigurationShape(node.get("routerConfiguration"))) {
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
      if (node.description() != null && node.description().codePointCount(0, node.description().length()) > 500) {
        throw fail(422, "INVALID_TOPOLOGY", "节点用途说明最多 500 个 Unicode 字符");
      }
      List<PortView> ports = node.ports().stream().map(port -> {
        if (port == null || port.protocols() == null || port.protocols().isEmpty()) {
          throw fail(422, "INVALID_TOPOLOGY", "端口字段不能为空");
        }
        return new PortView(port.id(), port.direction(), port.layer(), List.copyOf(port.protocols()));
      }).toList();
      return new NodeView(node.id(), node.kind(), node.name() == null ? null : node.name().strip(),
          node.position(), node.inputCount(), node.outputCount(), ports, node.parentId(),
          node.assetId(), node.clusterId(), node.ipAddress() == null || node.ipAddress().isBlank()
              ? null : node.ipAddress().strip(), node.description() == null || node.description().isBlank()
                  ? null : node.description().strip(), node.providerId() == null
                      || node.providerId().isBlank() ? null : node.providerId().strip(),
          node.switchBackend(), node.firewallPolicy(), node.routerConfiguration());
    }).toList();
    List<LinkInput> links = input.links().stream().map(link -> {
      if (link == null) {
        throw fail(422, "INVALID_TOPOLOGY", "连接字段不能为空");
      }
      return link;
    }).toList();
    return new DraftWrite(nodes, links);
  }

  private String hierarchyError(DraftWrite value) {
    Map<String, NodeView> byId = new LinkedHashMap<>();
    for (NodeView node : value.nodes()) {
      UUID id;
      try {
        id = uuid(node.id());
      } catch (RuntimeException failure) {
        return "节点 ID 必须是 UUID";
      }
      byId.put(id.toString(), node);
      if (node.parentId() != null) {
        try {
          uuid(node.parentId());
        } catch (RuntimeException failure) {
          return "父文件夹 ID 无效";
        }
      }
      if (node.providerId() != null && !node.providerId().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) {
        return "网络 Provider ID 格式无效";
      }
      if (node.assetId() != null) {
        try {
          uuid(node.assetId());
        } catch (RuntimeException failure) {
          return "主机资产 ID 无效";
        }
        if (!("HOST".equals(node.kind()) || "GATEWAY".equals(node.kind()))) {
          return "只有主机或 Gateway 节点可以绑定主机资产";
        }
      }
      if (node.clusterId() != null) {
        try {
          uuid(node.clusterId());
        } catch (RuntimeException failure) {
          return "Kubernetes 集群 ID 无效";
        }
        if (!"FOLDER".equals(node.kind())) {
          return "只有文件夹节点可以关联 Kubernetes 集群";
        }
      }
      if (node.ipAddress() != null && (!"ROUTER".equals(node.kind())
          || !validIpAddress(node.ipAddress()))) {
        return "下一跳 IP 只能设置在 Router 节点上，且必须是有效 IPv4/IPv6 地址";
      }
      if (node.switchBackend() != null
          && (!"SWITCH".equals(node.kind())
              || !Set.of("BRIDGE", "OPENVSWITCH").contains(node.switchBackend()))) {
        return "交换机后端只能为 SWITCH 选择 BRIDGE 或 OPENVSWITCH";
      }
      String firewallError = firewallPolicyError(node);
      if (firewallError != null) return firewallError;
      String routerError = routerConfigurationError(node, value.links());
      if (routerError != null) return routerError;
    }
    for (NodeView node : value.nodes()) {
      String parentId = node.parentId();
      Set<String> seen = new HashSet<>();
      seen.add(node.id());
      int depth = 0;
      while (parentId != null) {
        NodeView parent = byId.get(parentId);
        if (parent == null || !"FOLDER".equals(parent.kind())) {
          return "父节点必须是存在的文件夹";
        }
        if (!seen.add(parentId)) {
          return "文件夹层级不能形成循环";
        }
        parentId = parent.parentId();
        if (++depth > 8) {
          return "文件夹最多嵌套 8 层";
        }
      }
    }
    for (LinkInput link : value.links()) {
      NodeView source = byId.get(link.sourceNodeId());
      NodeView target = byId.get(link.targetNodeId());
      if (source != null && target != null
          && !java.util.Objects.equals(source.parentId(), target.parentId())) {
        return "不同拓扑层级之间不能直接连接";
      }
    }
    return null;
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
            new NetworkGraph.Position(node.position().x(), node.position().y()), node.providerId(), ports));
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

  private String providerError(DraftWrite value) {
    for (NodeView node : value.nodes()) {
      if (node.providerId() == null) continue;
      NetworkProvider provider = providers.provider(node.providerId()).orElse(null);
      if (provider == null) continue;
      boolean supported = provider.capabilityDescriptors().stream().anyMatch(capability ->
          capability.deviceKinds().contains(node.kind())
              && capability.layers().containsAll(node.ports().stream().map(PortView::layer).toList())
              && capability.protocols().containsAll(node.ports().stream()
                  .flatMap(port -> port.protocols().stream()).toList())
              && capability.operations().containsAll(Set.of(
                  "PLAN", "VALIDATE", "APPLY", "VERIFY", "COMPENSATE")));
      if (!supported) {
        return "Provider " + node.providerId()
            + " 未声明此设备、全部接口层/协议及完整变更生命周期能力";
      }
    }
    return null;
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

  private boolean objectWithNodeKeys(JsonNode value) {
    Set<String> allowed = Set.of("id", "kind", "name", "position", "inputCount",
        "outputCount", "ports", "parentId", "assetId", "clusterId", "ipAddress",
        "description", "providerId", "switchBackend", "firewallPolicy", "routerConfiguration");
    Set<String> required = Set.of("id", "kind", "name", "position", "inputCount",
        "outputCount", "ports");
    if (value == null || !value.isObject() || value.size() < required.size()
        || value.size() > allowed.size()) {
      return false;
    }
    var names = value.fieldNames();
    Set<String> found = new HashSet<>();
    while (names.hasNext()) {
      String name = names.next();
      if (!allowed.contains(name)) {
        return false;
      }
      found.add(name);
    }
    return found.containsAll(required);
  }

  private boolean validFirewallPolicyShape(JsonNode policy) {
    if (!objectWithKeys(policy, Set.of("defaultAction", "rules"))
        || !policy.path("defaultAction").isTextual() || !policy.path("rules").isArray()
        || policy.path("rules").size() > 64) return false;
    for (JsonNode rule : policy.path("rules")) {
      if (!objectWithKeys(rule, Set.of("inputPort", "outputPort", "protocol", "destinationPort", "action"))
          || !rule.path("inputPort").isTextual() || !rule.path("outputPort").isTextual()
          || !rule.path("protocol").isTextual() || !rule.path("destinationPort").isIntegralNumber()
          || !rule.path("action").isTextual()) return false;
    }
    return true;
  }

  private String firewallPolicyError(NodeView node) {
    FirewallPolicyView policy = node.firewallPolicy();
    if (policy == null) return null;
    if (!"FIREWALL".equals(node.kind())) return "只有 Firewall 节点可以设置防火墙策略";
    if (policy.rules() == null || policy.rules().size() > 64
        || !("ACCEPT".equals(policy.defaultAction()) || "DROP".equals(policy.defaultAction()))) {
      return "防火墙默认动作无效或规则数量超过 64 条";
    }
    Set<String> inputs = node.ports().stream().filter(port -> "INPUT".equals(port.direction()))
        .map(PortView::id).collect(java.util.stream.Collectors.toSet());
    Set<String> outputs = node.ports().stream().filter(port -> "OUTPUT".equals(port.direction()))
        .map(PortView::id).collect(java.util.stream.Collectors.toSet());
    Set<String> matches = new HashSet<>();
    for (FirewallRuleView rule : policy.rules()) {
      if (rule == null || !inputs.contains(rule.inputPort()) || !outputs.contains(rule.outputPort())
          || !("TCP".equals(rule.protocol()) || "UDP".equals(rule.protocol())
              || "SCTP".equals(rule.protocol()))
          || rule.destinationPort() < 1 || rule.destinationPort() > 65535
          || !("ACCEPT".equals(rule.action()) || "DROP".equals(rule.action()))) {
        return "防火墙规则必须引用有效的入/出端口、TCP/UDP/SCTP 端口及动作";
      }
      String key = rule.inputPort() + ":" + rule.outputPort() + ":" + rule.protocol()
          + ":" + rule.destinationPort();
      if (!matches.add(key)) return "防火墙规则匹配条件不能重复";
    }
    return null;
  }

  private boolean validRouterConfigurationShape(JsonNode configuration) {
    if (!objectWithKeys(configuration, Set.of("interfaces", "routes"))
        || !configuration.path("interfaces").isArray()
        || configuration.path("interfaces").size() > 16
        || !configuration.path("routes").isArray()
        || configuration.path("routes").size() > 64) return false;
    for (JsonNode iface : configuration.path("interfaces")) {
      if (!objectWithKeys(iface, Set.of("port", "address"))
          || !iface.path("port").isTextual() || !iface.path("address").isTextual()) return false;
    }
    for (JsonNode route : configuration.path("routes")) {
      if (!objectWithKeys(route, Set.of("destination", "gateway", "outputPort"))
          || !route.path("destination").isTextual()
          || !route.has("gateway") || !route.get("gateway").isNull() && !route.get("gateway").isTextual()
          || !route.path("outputPort").isTextual()) return false;
    }
    return true;
  }

  private String routerConfigurationError(NodeView node, List<LinkInput> links) {
    RouterConfigurationView configuration = node.routerConfiguration();
    if (configuration == null) return null;
    if (!"ROUTER".equals(node.kind()) || configuration.interfaces() == null
        || configuration.routes() == null || configuration.interfaces().size() > 16
        || configuration.routes().size() > 64) return "Router 配置字段或节点种类无效";
    Set<String> connectedPorts = new HashSet<>();
    for (LinkInput link : links) {
      if (node.id().equals(link.sourceNodeId())) connectedPorts.add(link.sourcePort());
      if (node.id().equals(link.targetNodeId())) connectedPorts.add(link.targetPort());
    }
    Map<String, Ipv4Cidr> configured = new HashMap<>();
    for (RouterInterfaceAddressView iface : configuration.interfaces()) {
      if (iface == null || iface.port() == null || iface.address() == null
          || node.ports().stream().noneMatch(port -> iface.port().equals(port.id()))
          || !connectedPorts.contains(iface.port()) || configured.containsKey(iface.port())) {
        return "Router 接口地址必须引用唯一且已连接的端口";
      }
      Ipv4Cidr address = parseIpv4Cidr(iface.address());
      if (address == null) return "Router 接口地址必须是有效 IPv4 CIDR";
      configured.put(iface.port(), address);
    }
    if (!configuration.interfaces().isEmpty() && !configured.keySet().containsAll(connectedPorts)) {
      return "Router 的每个已连接接口都必须配置 IPv4 地址";
    }
    Set<String> destinations = new HashSet<>();
    for (RouterStaticRouteView route : configuration.routes()) {
      if (route == null || route.destination() == null || route.outputPort() == null
          || node.ports().stream().noneMatch(port -> route.outputPort().equals(port.id())
              && "OUTPUT".equals(port.direction()))
          || !configured.containsKey(route.outputPort())) return "Router 静态路由必须引用已配置的出口接口";
      Ipv4Cidr destination = parseIpv4Cidr(route.destination());
      if (destination == null || !destination.canonicalAddress()
          || !destinations.add(route.destination())) return "Router 静态路由目标必须是唯一的规范 IPv4 网段";
      if (configured.values().stream().anyMatch(address -> address.network() == destination.network()
          && address.prefix() == destination.prefix())) return "静态路由不能覆盖 Router 接口的直连网段";
      if (route.gateway() != null) {
        Long gateway = parseIpv4(route.gateway());
        Ipv4Cidr output = configured.get(route.outputPort());
        if (gateway == null || (gateway & maskForPrefix(output.prefix())) != output.network()) {
          return "Router 下一跳必须位于所选接口的直连网段";
        }
      }
    }
    return null;
  }

  private record Ipv4Cidr(long address, long network, int prefix, boolean canonicalAddress) {}

  private Ipv4Cidr parseIpv4Cidr(String value) {
    String[] parts = value.split("/", -1);
    if (parts.length != 2) return null;
    Long address = parseIpv4(parts[0]);
    if (address == null || !parts[1].matches("0|[1-9]|[12][0-9]|3[0-2]")) return null;
    int prefix = Integer.parseInt(parts[1]);
    long mask = maskForPrefix(prefix);
    return new Ipv4Cidr(address, address & mask, prefix, address == (address & mask));
  }

  private Long parseIpv4(String value) {
    if (value == null || !value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return null;
    long address = 0;
    for (String octet : value.split("\\.")) {
      int part = Integer.parseInt(octet);
      if (part > 255) return null;
      address = (address << 8) | part;
    }
    return address;
  }

  private long maskForPrefix(int prefix) {
    return prefix == 0 ? 0 : (0xffff_ffffL << (32 - prefix)) & 0xffff_ffffL;
  }

  private boolean validIpAddress(String value) {
    if (value == null || value.isBlank() || value.length() > 45
        || !value.matches("[0-9A-Fa-f:.]+")) return false;
    try {
      java.net.InetAddress address = java.net.InetAddress.getByName(value);
      return value.contains(":") ? address instanceof java.net.Inet6Address
          : address instanceof java.net.Inet4Address;
    } catch (java.net.UnknownHostException invalid) {
      return false;
    }
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
