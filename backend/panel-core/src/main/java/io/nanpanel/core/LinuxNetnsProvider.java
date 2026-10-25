package io.nanpanel.core;

import static io.nanpanel.core.AuthStore.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nanpanel.plugin.api.NetworkProvider;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/** Linux bridge/OVS, Firewall, Packet Tap, and Router Provider isolated in a dedicated netns. */
@RequestScoped
public class LinuxNetnsProvider implements NetworkProvider {
  public static final String ID = "linux-netns";
  private static final UUID TOPOLOGY_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final Set<String> OPERATIONS = Set.of(
      "PLAN", "VALIDATE", "APPLY", "VERIFY", "COMPENSATE");
  private static final Duration HELPER_WAIT = Duration.ofSeconds(24);
  private final Map<UUID, Configuration> configurations = new ConcurrentHashMap<>();

  @Inject AuthStore store;
  @Inject ObjectMapper mapper;
  @Inject HostHelperOperationResource helperOperations;
  @Context ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  private record HelperFirewallRule(String inputPort, String outputPort, String protocol,
                                    int destinationPort, String action) {}
  private record HelperFirewallPolicy(String defaultAction, List<HelperFirewallRule> rules) {}
  private record HelperRouterInterface(String port, String address) {}
  private record HelperStaticRoute(String destination, String gateway, String outputPort) {}
  private record HelperRouterConfiguration(List<HelperRouterInterface> interfaces,
                                           List<HelperStaticRoute> routes) {}
  private record HelperNode(String id, String kind, String switchBackend,
                            HelperFirewallPolicy firewallPolicy,
                            HelperRouterConfiguration routerConfiguration) {}
  private record HelperLink(String id, String sourceNodeId, String sourcePort,
                            String targetNodeId, String targetPort, String layer,
                            String protocol) {}
  private record HelperPayload(String action, String topologyId, String revisionId,
                               long generation, List<HelperNode> nodes,
                               List<HelperLink> links) {}
  private record Configuration(UUID hostId, long hostGeneration, String reason,
                               UUID revisionId, long topologyGeneration,
                               List<HelperNode> nodes, List<HelperLink> links) {}
  private record HostState(long generation, String capabilities, String helperStatus,
                           Instant lastSeen) {}
  private record HelperResult(UUID id, String status, String result, String error) {}

  @Override
  public String id() {
    return ID;
  }

  @Override
  public Set<String> capabilities() {
    return Set.of("linux.bridge", "linux.ovs", "linux.router.ipv4");
  }

  @Override
  public ProviderDescriptor descriptor() {
    return new ProviderDescriptor(ID, "Linux Bridge and OVS, IPv4 Router, Firewall and Packet Tap (isolated netns)", "0.5.0",
        "panel.io/plugins/v1");
  }

  @Override
  public Set<Capability> capabilityDescriptors() {
    return new LinkedHashSet<>(List.of(
        new Capability("linux.bridge", OPERATIONS, Set.of("L2"),
            Set.of("ETHERNET"), Set.of("SWITCH", "PACKET_TAP", "FIREWALL")),
        new Capability("linux.router.ipv4", OPERATIONS, Set.of("L2"),
            Set.of("ETHERNET"), Set.of("ROUTER")),
        new Capability("linux.ovs", OPERATIONS, Set.of("L2"),
            Set.of("ETHERNET"), Set.of("SWITCH"))));
  }

  @Override
  public CompletionStage<String> observe(String resourceKey) {
    try {
      UUID providerOperationId = UUID.fromString(resourceKey);
      List<Map<String, Object>> rows = store.transaction(connection -> query(connection,
          "SELECT plan_configuration,state FROM network_provider_operation "
              + "WHERE provider_id=? AND id=?",
          ID, providerOperationId));
      if (rows.isEmpty() || Set.of("COMPENSATED", "FAILED").contains(rows.getFirst().get("state"))) {
        throw new IllegalArgumentException("Provider operation has no observable topology");
      }
      Configuration configuration = mapper.readValue(
          String.valueOf(rows.getFirst().get("plan_configuration")), Configuration.class);
      HelperResult result = runHelper(null, configuration, "VERIFY",
          UUID.randomUUID().toString());
      requireSucceeded(result, "Linux bridge observation");
      return CompletableFuture.completedFuture(result.result());
    } catch (Exception failure) {
      return CompletableFuture.failedFuture(failure);
    }
  }

  @Override
  public CompletionStage<ChangePlan> plan(String desiredSnapshot, String observedSnapshot) {
    Configuration configuration = parseConfiguration(desiredSnapshot);
    UUID id = UUID.randomUUID();
    ChangePlan plan = new ChangePlan(id, configuration.topologyGeneration(),
        UUID.randomUUID().toString(), encode(configuration));
    return CompletableFuture.completedFuture(plan);
  }

  @Override
  public CompletionStage<ValidationResult> validate(ChangePlan plan) {
    Configuration configuration = readConfiguration(plan);
    String invalid = configurationError(configuration);
    return CompletableFuture.completedFuture(new ValidationResult(invalid == null,
        invalid == null ? "Topology revision, host generation, and Helper capability are current"
            : invalid));
  }

  @Override
  public CompletionStage<UUID> apply(ChangePlan plan) {
    Configuration configuration = readConfiguration(plan);
    String invalid = configurationError(configuration);
    if (invalid != null) throw new IllegalStateException(invalid);
    HelperResult result = runHelper(plan, configuration, "APPLY", plan.idempotencyKey());
    requireSucceeded(result, "Linux bridge apply");
    configurations.put(result.id(), configuration);
    return CompletableFuture.completedFuture(result.id());
  }

  @Override
  public CompletionStage<ValidationResult> verify(UUID operationId) {
    Configuration configuration = configurations.get(operationId);
    if (configuration == null) {
      return CompletableFuture.completedFuture(
          new ValidationResult(false, "Provider operation state was not restored"));
    }
    UUID providerOperationId = providerOperationId(operationId, configuration);
    HelperResult result = runHelper(null, configuration, "VERIFY",
        helperKey(providerOperationId, "verify"));
    if (!"SUCCEEDED".equals(result.status())) {
      return CompletableFuture.completedFuture(new ValidationResult(false,
          bounded(result.error() == null ? "Helper verification failed" : result.error())));
    }
    try {
      JsonNode report = mapper.readTree(result.result());
      boolean verified = report.path("verified").asBoolean(false)
          && TOPOLOGY_ID.toString().equals(report.path("topologyId").asText())
          && configuration.revisionId().toString().equals(report.path("revisionId").asText())
          && report.path("generation").asLong() == configuration.topologyGeneration();
      return CompletableFuture.completedFuture(new ValidationResult(verified,
          verified ? "Linux bridges, OVS bridges, Packet Tap mirror filters, and veth resources match in the isolated netns; default route count is zero"
              : "Helper reported that the isolated topology differs from the saved revision"));
    } catch (Exception failure) {
      return CompletableFuture.completedFuture(
          new ValidationResult(false, "Helper verification result was invalid"));
    }
  }

  @Override
  public CompletionStage<ValidationResult> compensate(UUID operationId) {
    Configuration configuration = configurations.get(operationId);
    if (configuration == null) {
      return CompletableFuture.completedFuture(
          new ValidationResult(false, "Provider operation state was not restored"));
    }
    UUID providerOperationId = providerOperationId(operationId, configuration);
    HelperResult result = runHelper(null, configuration, "COMPENSATE",
        helperKey(providerOperationId, "compensate"));
    if (!"SUCCEEDED".equals(result.status())) {
      return CompletableFuture.completedFuture(new ValidationResult(false,
          bounded(result.error() == null ? "Helper compensation failed" : result.error())));
    }
    configurations.remove(operationId);
    return CompletableFuture.completedFuture(new ValidationResult(true,
        "The isolated namespace for this topology revision was removed"));
  }

  @Override
  public CompletionStage<ValidationResult> restoreOperation(ChangePlan plan, UUID operationId) {
    try {
      Configuration configuration = readConfiguration(plan);
      UUID.fromString(operationId.toString());
      configurations.put(operationId, configuration);
      return CompletableFuture.completedFuture(
          new ValidationResult(true, "Provider plan restored from Core storage"));
    } catch (RuntimeException failure) {
      return CompletableFuture.completedFuture(
          new ValidationResult(false, "Provider plan could not be restored"));
    }
  }

  private Configuration parseConfiguration(String snapshot) {
    try {
      JsonNode root = mapper.readTree(snapshot);
      JsonNode hostId = root == null ? null : root.get("hostId");
      JsonNode hostGeneration = root == null ? null : root.get("hostGeneration");
      JsonNode reason = root == null ? null : root.get("reason");
      JsonNode revisionClaim = root == null ? null : root.get("revisionId");
      JsonNode generationClaim = root == null ? null : root.get("generation");
      if (root == null || !root.isObject() || root.size() != 5 || hostId == null
          || !hostId.isTextual() || hostGeneration == null || !hostGeneration.isIntegralNumber()
          || !hostGeneration.canConvertToLong() || hostGeneration.longValue() < 1
          || reason == null || !reason.isTextual() || revisionClaim == null
          || !revisionClaim.isTextual() || generationClaim == null
          || !generationClaim.isIntegralNumber() || !generationClaim.canConvertToLong()
          || generationClaim.longValue() < 1) {
        throw new IllegalArgumentException("Provider plan requires the expected Revision, execution host, and audit reason");
      }
      UUID selectedHost = UUID.fromString(hostId.asText());
      String explanation = reason.asText().strip();
      if (explanation.isEmpty() || explanation.length() > 500
          || explanation.chars().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("Provider operation reason is invalid");
      }
      Map<String, Object> head = store.transaction(connection -> {
        var rows = query(connection,
            "SELECT revision_id,generation,snapshot FROM topology_head WHERE id=1");
        return rows.isEmpty() ? null : rows.getFirst();
      });
      if (head == null) throw new IllegalArgumentException("Saved topology revision is unavailable");
      UUID revision = UUID.fromString(String.valueOf(head.get("revision_id")));
      long generation = ((Number)head.get("generation")).longValue();
      if (!revision.toString().equalsIgnoreCase(revisionClaim.asText())
          || generation != generationClaim.longValue()) {
        throw new IllegalArgumentException("Current topology Revision changed before the plan was created");
      }
      JsonNode savedTopology = mapper.readTree(String.valueOf(head.get("snapshot")));
      JsonNode nodes = savedTopology.path("nodes");
      JsonNode links = savedTopology.path("links");
      if (generation < 1 || !nodes.isArray() || !links.isArray()) {
        throw new IllegalArgumentException("Saved topology revision is invalid");
      }
      Set<String> selectedNodeIds = new HashSet<>();
      Map<String, String> selectedNodeKinds = new HashMap<>();
      List<HelperNode> helperNodes = new ArrayList<>();
      for (JsonNode node : nodes) {
        if (!ID.equals(node.path("providerId").asText())) continue;
        String kind = node.path("kind").asText();
        if (!Set.of("SWITCH", "PACKET_TAP", "FIREWALL", "ROUTER").contains(kind)
            || !node.path("id").isTextual()
            || node.hasNonNull("parentId")) {
          throw new IllegalArgumentException(
              "Linux netns Provider accepts only root SWITCH, PACKET_TAP, FIREWALL, and ROUTER devices");
        }
        String switchBackend = null;
        JsonNode backendNode = node.get("switchBackend");
        if ("SWITCH".equals(kind)) {
          switchBackend = backendNode == null || backendNode.isNull()
              ? "BRIDGE" : backendNode.asText();
          if (!Set.of("BRIDGE", "OPENVSWITCH").contains(switchBackend)) {
            throw new IllegalArgumentException("SWITCH backend must be BRIDGE or OPENVSWITCH");
          }
        } else if (backendNode != null && !backendNode.isNull()) {
          throw new IllegalArgumentException("Only SWITCH can select a switch backend");
        }
        UUID id = UUID.fromString(node.path("id").asText());
        if (!selectedNodeIds.add(id.toString())) {
          throw new IllegalArgumentException("Provider device IDs must be unique");
        }
        JsonNode policyNode = node.get("firewallPolicy");
        HelperFirewallPolicy policy = null;
        if ("FIREWALL".equals(kind)) {
          if (policyNode == null || policyNode.isNull()) {
            throw new IllegalArgumentException("FIREWALL requires a typed policy");
          }
          policy = mapper.treeToValue(policyNode, HelperFirewallPolicy.class);
        } else if (policyNode != null && !policyNode.isNull()) {
          throw new IllegalArgumentException("Only FIREWALL can include a firewall policy");
        }
        JsonNode routerNode = node.get("routerConfiguration");
        HelperRouterConfiguration routerConfiguration = null;
        if ("ROUTER".equals(kind)) {
          if (routerNode == null || routerNode.isNull()) {
            throw new IllegalArgumentException("ROUTER requires typed IPv4 interface and route configuration");
          }
          routerConfiguration = mapper.treeToValue(routerNode, HelperRouterConfiguration.class);
        } else if (routerNode != null && !routerNode.isNull()) {
          throw new IllegalArgumentException("Only ROUTER can include a router configuration");
        }
        selectedNodeKinds.put(id.toString(), kind);
        helperNodes.add(new HelperNode(id.toString(), kind, switchBackend, policy,
            routerConfiguration));
      }
      if (helperNodes.isEmpty() || helperNodes.size() > 16 || links.size() > 64) {
        throw new IllegalArgumentException("Linux netns plan needs 1 to 16 devices and at most 64 wires");
      }
      int totalRouterRoutes = helperNodes.stream()
          .filter(node -> "ROUTER".equals(node.kind()))
          .map(HelperNode::routerConfiguration)
          .filter(Objects::nonNull)
          .filter(configuration -> configuration.routes() != null)
          .mapToInt(configuration -> configuration.routes().size())
          .sum();
      if (totalRouterRoutes > 64) {
        throw new IllegalArgumentException("Linux netns topology supports at most 64 static routes across all Routers");
      }
      boolean usesOvs = helperNodes.stream().anyMatch(node -> "OPENVSWITCH".equals(node.switchBackend()));
      if (usesOvs && helperNodes.stream().anyMatch(node ->
          "FIREWALL".equals(node.kind()) || "PACKET_TAP".equals(node.kind()))) {
        throw new IllegalArgumentException(
            "OVS topology plans currently cannot include Firewall or Packet Tap devices");
      }
      List<HelperLink> helperLinks = new ArrayList<>();
      for (JsonNode link : links) {
        String source = link.path("sourceNodeId").asText();
        String target = link.path("targetNodeId").asText();
        boolean sourceSelected = selectedNodeIds.contains(source);
        boolean targetSelected = selectedNodeIds.contains(target);
        if (sourceSelected != targetSelected) {
          throw new IllegalArgumentException("A Provider wire cannot end at an unmanaged device");
        }
        if (!sourceSelected) continue;
        String sourceKind = selectedNodeKinds.get(source);
        String targetKind = selectedNodeKinds.get(target);
        if (!Set.of("SWITCH", "PACKET_TAP", "FIREWALL", "ROUTER").contains(sourceKind)
            || !Set.of("SWITCH", "PACKET_TAP", "FIREWALL", "ROUTER").contains(targetKind)
            || !"L2".equals(link.path("layer").asText())
            || !"ETHERNET".equals(link.path("protocol").asText())) {
          throw new IllegalArgumentException("Linux netns wires must join supported L2 Ethernet devices");
        }
        helperLinks.add(new HelperLink(UUID.fromString(link.path("id").asText()).toString(),
            UUID.fromString(source).toString(), link.path("sourcePort").asText(),
            UUID.fromString(target).toString(), link.path("targetPort").asText(), "L2", "ETHERNET"));
      }
      String packetTapError = packetTapShapeError(helperNodes, helperLinks);
      if (packetTapError != null) throw new IllegalArgumentException(packetTapError);
      String firewallError = firewallPolicyShapeError(helperNodes, helperLinks);
      if (firewallError != null) throw new IllegalArgumentException(firewallError);
      String routerError = routerConfigurationError(helperNodes, helperLinks);
      if (routerError != null) throw new IllegalArgumentException(routerError);
      return new Configuration(selectedHost, hostGeneration.longValue(), explanation, revision,
          generation, List.copyOf(helperNodes), List.copyOf(helperLinks));
    } catch (Exception failure) {
      if (failure instanceof IllegalArgumentException invalid) throw invalid;
      throw new IllegalArgumentException("Provider plan snapshot is invalid", failure);
    }
  }

  private String firewallPolicyShapeError(List<HelperNode> nodes, List<HelperLink> links) {
    for (HelperNode node : nodes) {
      if (!"FIREWALL".equals(node.kind())) continue;
      HelperFirewallPolicy policy = node.firewallPolicy();
      if (policy == null || policy.rules() == null || policy.rules().size() > 64
          || !("ACCEPT".equals(policy.defaultAction())
              || "DROP".equals(policy.defaultAction()))) {
        return "FIREWALL policy must define an ACCEPT/DROP default and up to 64 rules";
      }
      Set<String> inputs = new HashSet<>();
      Set<String> outputs = new HashSet<>();
      for (HelperLink link : links) {
        if (node.id().equals(link.targetNodeId())) inputs.add(link.targetPort());
        if (node.id().equals(link.sourceNodeId())) outputs.add(link.sourcePort());
      }
      if (inputs.isEmpty() || outputs.isEmpty()) {
        return "FIREWALL requires at least one connected input and output";
      }
      Set<String> matches = new HashSet<>();
      for (HelperFirewallRule rule : policy.rules()) {
        if (rule == null || !inputs.contains(rule.inputPort()) || !outputs.contains(rule.outputPort())
            || !Set.of("TCP", "UDP", "SCTP").contains(rule.protocol())
            || rule.destinationPort() < 1 || rule.destinationPort() > 65535
            || !Set.of("ACCEPT", "DROP").contains(rule.action())) {
          return "FIREWALL rules must reference connected ports and valid transport matches";
        }
        String key = rule.inputPort() + ":" + rule.outputPort() + ":" + rule.protocol()
            + ":" + rule.destinationPort();
        if (!matches.add(key)) return "FIREWALL match conditions cannot be duplicated";
      }
    }
    return null;
  }

  private String packetTapShapeError(List<HelperNode> nodes, List<HelperLink> links) {
    for (HelperNode node : nodes) {
      if (!"PACKET_TAP".equals(node.kind())) continue;
      long connected = links.stream().filter(link -> node.id().equals(link.sourceNodeId())
          || node.id().equals(link.targetNodeId())).count();
      boolean input = links.stream().anyMatch(link -> node.id().equals(link.targetNodeId())
          && "in-1".equals(link.targetPort()));
      boolean business = links.stream().anyMatch(link -> node.id().equals(link.sourceNodeId())
          && "out-1".equals(link.sourcePort()));
      boolean monitor = links.stream().anyMatch(link -> node.id().equals(link.sourceNodeId())
          && "out-2".equals(link.sourcePort()));
      if (connected != 3 || !input || !business || !monitor) {
        return "PACKET_TAP requires one connected input, one business output, and one monitor output";
      }
    }
    return null;
  }

  private String routerConfigurationError(List<HelperNode> nodes, List<HelperLink> links) {
    for (HelperNode node : nodes) {
      if (!"ROUTER".equals(node.kind())) continue;
      HelperRouterConfiguration configuration = node.routerConfiguration();
      if (configuration == null || configuration.interfaces() == null
          || configuration.routes() == null || configuration.interfaces().isEmpty()
          || configuration.interfaces().size() > 16 || configuration.routes().size() > 64) {
        return "ROUTER requires 1 to 16 IPv4 interface addresses and at most 64 static routes";
      }
      Set<String> connected = new HashSet<>();
      Set<String> outputPorts = new HashSet<>();
      for (HelperLink link : links) {
        if (node.id().equals(link.sourceNodeId())) {
          connected.add(link.sourcePort());
          outputPorts.add(link.sourcePort());
        }
        if (node.id().equals(link.targetNodeId())) connected.add(link.targetPort());
      }
      Map<String, Ipv4Cidr> configured = new HashMap<>();
      Set<Long> assignedAddresses = new HashSet<>();
      List<Ipv4Cidr> connectedNetworks = new ArrayList<>();
      for (HelperRouterInterface iface : configuration.interfaces()) {
        Ipv4Cidr address = iface == null ? null : parseIpv4Cidr(iface.address());
        if (iface == null || iface.port() == null || address == null
            || !connected.contains(iface.port()) || configured.putIfAbsent(iface.port(), address) != null) {
          return "ROUTER interface addresses must reference unique connected ports and valid IPv4 CIDRs";
        }
        long last = address.network() | (~ipv4Mask(address.prefix()) & 0xffff_ffffL);
        boolean overlaps = connectedNetworks.stream().anyMatch(existing -> {
          long existingLast = existing.network() | (~ipv4Mask(existing.prefix()) & 0xffff_ffffL);
          return address.network() <= existingLast && existing.network() <= last;
        });
        if ((address.prefix() <= 30
            && (address.address() == address.network() || address.address() == last))
            || !assignedAddresses.add(address.address()) || overlaps) {
          return "ROUTER interface addresses must be unique usable hosts on non-overlapping networks";
        }
        connectedNetworks.add(address);
      }
      if (!configured.keySet().containsAll(connected)) {
        return "ROUTER requires an IPv4 address on every connected interface";
      }
      Set<String> destinations = new HashSet<>();
      for (HelperStaticRoute route : configuration.routes()) {
        Ipv4Cidr destination = route == null ? null : parseIpv4Cidr(route.destination());
        Ipv4Cidr output = route == null ? null : configured.get(route.outputPort());
        if (route == null || destination == null || !destination.canonical()
            || output == null || !outputPorts.contains(route.outputPort())
            || !destinations.add(route.destination())) {
          return "ROUTER static routes must use unique canonical IPv4 networks and configured output interfaces";
        }
        if (!"0.0.0.0/0".equals(route.destination())
            && connectedNetworks.stream().anyMatch(address -> {
              long destinationLast = destination.network()
                  | (~ipv4Mask(destination.prefix()) & 0xffff_ffffL);
              long connectedLast = address.network()
                  | (~ipv4Mask(address.prefix()) & 0xffff_ffffL);
              return destination.network() <= connectedLast && address.network() <= destinationLast;
            })) {
          return "ROUTER static routes cannot overlap directly connected networks";
        }
        if (route.gateway() != null) {
          Long gateway = parseIpv4(route.gateway());
          long last = output.network() | (~ipv4Mask(output.prefix()) & 0xffff_ffffL);
          if (gateway == null || (gateway & ipv4Mask(output.prefix())) != output.network()
              || gateway == output.address()
              || (output.prefix() <= 30 && (gateway == output.network() || gateway == last))) {
            return "ROUTER next hops must belong to the selected output interface subnet";
          }
        }
      }
    }
    return null;
  }

  private record Ipv4Cidr(long address, long network, int prefix, boolean canonical) {}

  private Ipv4Cidr parseIpv4Cidr(String value) {
    if (value == null) return null;
    String[] parts = value.split("/", -1);
    if (parts.length != 2 || !parts[1].matches("0|[1-9]|[12][0-9]|3[0-2]")) return null;
    Long address = parseIpv4(parts[0]);
    if (address == null) return null;
    int prefix = Integer.parseInt(parts[1]);
    long network = address & ipv4Mask(prefix);
    return new Ipv4Cidr(address, network, prefix, address == network);
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

  private long ipv4Mask(int prefix) {
    return prefix == 0 ? 0 : (0xffff_ffffL << (32 - prefix)) & 0xffff_ffffL;
  }

  private String configurationError(Configuration configuration) {
    return store.transaction(connection -> {
      var topologyRows = query(connection,
          "SELECT revision_id,generation FROM topology_head WHERE id=1");
      if (topologyRows.isEmpty()
          || !configuration.revisionId().toString().equals(String.valueOf(topologyRows.getFirst().get("revision_id")))
          || ((Number)topologyRows.getFirst().get("generation")).longValue()
              != configuration.topologyGeneration()) {
        return "Saved topology revision changed after this Provider plan was created";
      }
      String capabilityColumn = connection.getMetaData().getDatabaseProductName()
          .equalsIgnoreCase("PostgreSQL") ? "h.capabilities::text" : "h.capabilities";
      var hostRows = query(connection,
          "SELECT h.generation," + capabilityColumn + " AS capabilities,"
              + "(SELECT a.helper_status FROM agent a WHERE a.host_id=h.id "
              + "ORDER BY a.last_seen DESC NULLS LAST,a.id LIMIT 1) AS helper_status,"
              + "(SELECT a.last_seen FROM agent a WHERE a.host_id=h.id "
              + "ORDER BY a.last_seen DESC NULLS LAST,a.id LIMIT 1) AS last_seen "
              + "FROM host h WHERE h.id=?",
          configuration.hostId());
      if (hostRows.isEmpty()) return "Selected execution host no longer exists";
      Map<String, Object> row = hostRows.getFirst();
      long hostGeneration = ((Number)row.get("generation")).longValue();
      if (hostGeneration != configuration.hostGeneration()) {
        return "Execution host generation changed after this Provider plan was created";
      }
      JsonNode capabilities;
      try {
        capabilities = mapper.readTree(String.valueOf(row.get("capabilities")));
      } catch (Exception failure) {
        return "Execution host capability report is invalid";
      }
      boolean capable = capabilities != null && capabilities.isArray();
      boolean ovsRequired = configuration.nodes().stream()
          .anyMatch(node -> "OPENVSWITCH".equals(node.switchBackend()));
      boolean vrfRequired = configuration.nodes().stream()
          .anyMatch(node -> "ROUTER".equals(node.kind()));
      boolean ovsCapable = false;
      boolean vrfCapable = false;
      if (capable) {
        capable = false;
        for (JsonNode value : capabilities) {
          if (value.isTextual() && "network.topology.reconcile".equals(value.asText())) {
            capable = true;
          }
          if (value.isTextual() && "network.topology.ovs".equals(value.asText())) {
            ovsCapable = true;
          }
          if (value.isTextual() && "network.topology.vrf".equals(value.asText())) {
            vrfCapable = true;
          }
        }
      }
      if (!capable) return "Execution host Helper does not report isolated topology support";
      if (ovsRequired && !ovsCapable) {
        return "Execution host Helper does not report isolated OVS topology support";
      }
      if (vrfRequired && !vrfCapable) {
        return "Execution host Helper does not report isolated Router VRF support";
      }
      if (!"AVAILABLE".equals(row.get("helper_status"))) {
        return "Execution host Helper is unavailable";
      }
      Instant lastSeen = instant(row.get("last_seen"));
      var settings = query(connection,
          "SELECT host_offline_after_seconds FROM platform_settings WHERE id=1");
      int offlineAfter = settings.isEmpty() ? 90
          : ((Number)settings.getFirst().get("host_offline_after_seconds")).intValue();
      if (lastSeen == null || lastSeen.isBefore(Instant.now().minusSeconds(offlineAfter))) {
        return "Execution host Agent is offline";
      }
      var pending = query(connection,
          "SELECT id FROM operation WHERE host_id=? AND kind='HOST_HELPER_OPERATION' "
              + "AND status IN ('PENDING','RUNNING','UNKNOWN') LIMIT 1",
          configuration.hostId());
      return pending.isEmpty() ? null : "Selected execution host has an unresolved Helper operation";
    });
  }

  private HelperResult runHelper(ChangePlan plan, Configuration configuration,
                                 String action, String key) {
    HelperPayload payload = new HelperPayload(action, TOPOLOGY_ID.toString(),
        configuration.revisionId().toString(), configuration.topologyGeneration(),
        configuration.nodes(), configuration.links());
    JsonNode payloadNode = mapper.valueToTree(payload);
    HostHelperOperationResource.HelperOperationRequest helperRequest =
        new HostHelperOperationResource.HelperOperationRequest(
            "network.topology.reconcile", TOPOLOGY_ID.toString(), payloadNode,
            configuration.reason(), 25, configuration.hostGeneration());
    String actor = actorId();
    String remoteAddress = http.remoteAddress() == null ? null : http.remoteAddress().host();
    Response accepted = helperOperations.submitAs(configuration.hostId(), key, helperRequest,
        actor, remoteAddress);
    if (!(accepted.getEntity() instanceof Map<?, ?> entity)
        || entity.get("id") == null) {
      throw new IllegalStateException("Core did not return a Helper operation ID");
    }
    UUID helperOperationId = UUID.fromString(String.valueOf(entity.get("id")));
    HelperResult result = waitForHelper(helperOperationId);
    if (plan != null && "APPLY".equals(action)) configurations.put(helperOperationId, configuration);
    return result;
  }

  private HelperResult waitForHelper(UUID operationId) {
    long deadline = System.nanoTime() + HELPER_WAIT.toNanos();
    while (System.nanoTime() < deadline) {
      List<Map<String, Object>> rows = store.transaction(connection -> query(connection,
          "SELECT id,status,result_json,error_json FROM operation WHERE id=?", operationId));
      if (!rows.isEmpty()) {
        Map<String, Object> row = rows.getFirst();
        String status = String.valueOf(row.get("status"));
        if (Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status)) {
          return new HelperResult(operationId, status, jsonText(row.get("result_json")),
              jsonText(row.get("error_json")));
        }
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for the Helper", failure);
      }
    }
    throw new IllegalStateException("Helper operation did not finish before the Provider deadline");
  }

  private void requireSucceeded(HelperResult result, String operation) {
    if (!"SUCCEEDED".equals(result.status())) {
      throw new IllegalStateException(operation + " failed: "
          + bounded(result.error() == null ? "Helper reported a failure" : result.error()));
    }
  }

  private String actorId() {
    @SuppressWarnings("unchecked")
    Map<String, Object> session = (Map<String, Object>)request.getProperty("session");
    if (session == null || session.get("user_id") == null) {
      throw new IllegalStateException("Authenticated Provider session is unavailable");
    }
    return String.valueOf(session.get("user_id"));
  }

  private Configuration readConfiguration(ChangePlan plan) {
    try {
      Configuration configuration = mapper.readValue(plan.configuration(), Configuration.class);
      if (plan.generation() != configuration.topologyGeneration()) {
        throw new IllegalArgumentException("Provider plan generation does not match its snapshot");
      }
      return configuration;
    } catch (Exception failure) {
      throw new IllegalArgumentException("Provider plan configuration is invalid", failure);
    }
  }

  private String encode(Configuration configuration) {
    try {
      return mapper.writeValueAsString(configuration);
    } catch (Exception failure) {
      throw new IllegalStateException("Validated Provider plan could not be serialized", failure);
    }
  }

  private UUID providerOperationId(UUID helperOperationId, Configuration configuration) {
    return UUID.nameUUIDFromBytes((helperOperationId + configuration.revisionId().toString())
        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private String helperKey(UUID providerOperationId, String action) {
    return providerOperationId + "-" + action;
  }

  private String jsonText(Object value) {
    if (value == null) return null;
    if (value instanceof String text) return text;
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception failure) {
      return null;
    }
  }

  private String bounded(String value) {
    if (value == null || value.isBlank()) return "Provider operation failed";
    return value.length() <= 512 ? value : value.substring(0, 512);
  }

  private Instant instant(Object value) {
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof OffsetDateTime offset) return offset.toInstant();
    if (value instanceof Instant instant) return instant;
    return null;
  }
}
