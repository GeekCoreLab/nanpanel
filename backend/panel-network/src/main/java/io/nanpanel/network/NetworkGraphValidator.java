package io.nanpanel.network;

import io.nanpanel.domain.NetworkGraph;
import io.nanpanel.domain.NetworkGraph.Direction;
import io.nanpanel.domain.NetworkGraph.Layer;
import io.nanpanel.domain.NetworkGraph.Link;
import io.nanpanel.domain.NetworkGraph.Node;
import io.nanpanel.domain.NetworkGraph.Port;
import io.nanpanel.domain.NetworkGraph.Protocol;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates the graph invariants shared by all network providers. */
public final class NetworkGraphValidator {
    private static final Pattern INPUT_PORT = Pattern.compile("in-([1-9]|1[0-6])");
    private static final Pattern OUTPUT_PORT = Pattern.compile("out-([1-9]|1[0-6])");

    private NetworkGraphValidator() {}

    public static String error(NetworkGraph graph) {
        if (graph == null || graph.nodes() == null || graph.links() == null) {
            return "拓扑必须包含 nodes 与 links";
        }
        if (graph.nodes().size() > 100 || graph.links().size() > 800) {
            return "拓扑最多包含 100 个节点与 800 条连接";
        }

        Map<java.util.UUID, Node> nodes = new HashMap<>();
        Map<java.util.UUID, Map<String, Port>> portsByNode = new HashMap<>();
        for (Node node : graph.nodes()) {
            if (node == null || node.id() == null || node.kind() == null || node.name() == null
                    || node.position() == null || node.ports() == null || nodes.putIfAbsent(node.id(), node) != null) {
                return "节点身份或字段无效";
            }
            String name = node.name().strip();
            if (name.isEmpty() || name.codePointCount(0, name.length()) > 100) {
                return "节点名称不能为空且最多 100 个 Unicode 字符";
            }
            if (!Double.isFinite(node.position().x()) || !Double.isFinite(node.position().y())
                    || Math.abs(node.position().x()) > 10000 || Math.abs(node.position().y()) > 10000) {
                return "节点坐标必须是 -10000 到 10000 内的有限数值";
            }
            String shapeError = shapeError(node);
            if (shapeError != null) {
                return shapeError;
            }
            Map<String, Port> ports = new HashMap<>();
            for (Port port : node.ports()) {
                if (port == null || port.id() == null || port.direction() == null || port.layer() == null
                        || port.protocols() == null || port.protocols().isEmpty()) {
                    return "端口类型或能力无效";
                }
                Pattern pattern = port.direction() == Direction.INPUT ? INPUT_PORT : OUTPUT_PORT;
                if (!pattern.matcher(port.id()).matches() || ports.putIfAbsent(port.id(), port) != null) {
                    return "端口 ID 必须唯一并符合方向编号格式";
                }
                if (port.protocols().stream().anyMatch(protocol -> !protocolMatches(port.layer(), protocol))) {
                    return "端口协议与网络层不兼容";
                }
            }
            if (ports.size() != node.ports().size()) {
                return "节点端口 ID 重复";
            }
            long inputCount = ports.values().stream().filter(port -> port.direction() == Direction.INPUT).count();
            long outputCount = ports.values().stream().filter(port -> port.direction() == Direction.OUTPUT).count();
            for (int index = 1; index <= inputCount; index++) {
                if (!ports.containsKey("in-" + index)) {
                    return "入口端口必须从 in-1 起连续编号";
                }
            }
            for (int index = 1; index <= outputCount; index++) {
                if (!ports.containsKey("out-" + index)) {
                    return "出口端口必须从 out-1 起连续编号";
                }
            }
            if ("FIREWALL".equals(node.kind()) || "PACKET_TAP".equals(node.kind())) {
                Port first = node.ports().getFirst();
                boolean consistent = node.ports().stream().allMatch(port ->
                        port.layer() == first.layer() && port.protocols().equals(first.protocols()));
                if (!consistent) {
                    return "防火墙与 Packet Tap 的所有端口必须声明相同的网络层和协议集合";
                }
            }
            portsByNode.put(node.id(), ports);
        }

        Set<java.util.UUID> linkIds = new HashSet<>();
        Set<String> occupied = new HashSet<>();
        Map<java.util.UUID, Set<java.util.UUID>> outgoing = new HashMap<>();
        for (Link link : graph.links()) {
            if (link == null || link.id() == null || !linkIds.add(link.id()) || link.sourceNodeId() == null
                    || link.targetNodeId() == null || link.sourcePort() == null || link.targetPort() == null
                    || link.layer() == null || link.protocol() == null) {
                return "连接 ID 或字段无效";
            }
            Node source = nodes.get(link.sourceNodeId());
            Node target = nodes.get(link.targetNodeId());
            if (source == null || target == null) {
                return "连接引用的节点不存在";
            }
            if (source.id().equals(target.id())) {
                return "不允许节点连接自身";
            }
            Port sourcePort = portsByNode.get(source.id()).get(link.sourcePort());
            Port targetPort = portsByNode.get(target.id()).get(link.targetPort());
            if (sourcePort == null || sourcePort.direction() != Direction.OUTPUT
                    || targetPort == null || targetPort.direction() != Direction.INPUT) {
                return "连接必须从源节点出口指向目标节点入口";
            }
            if (sourcePort.layer() != link.layer() || targetPort.layer() != link.layer()
                    || !sourcePort.protocols().contains(link.protocol())
                    || !targetPort.protocols().contains(link.protocol())
                    || !protocolMatches(link.layer(), link.protocol())) {
                return "连接的网络层与协议必须同时受两端口支持";
            }
            if (("LOAD_BALANCER".equals(source.kind()) || "LOAD_BALANCER".equals(target.kind()))
                    && link.layer() != Layer.L4 && link.layer() != Layer.L7) {
                return "流量分配器只支持 L4/L7 流量接口";
            }
            if (!occupied.add(source.id() + ":" + sourcePort.id())
                    || !occupied.add(target.id() + ":" + targetPort.id())) {
                return "每个端口只能连接一条链路";
            }
            outgoing.computeIfAbsent(source.id(), ignored -> new HashSet<>()).add(target.id());
        }

        if (containsCycle(nodes.keySet(), outgoing)) {
            return "拓扑连接不能形成环路";
        }
        return null;
    }

    private static String shapeError(Node node) {
        long inputs = node.ports().stream().filter(port -> port != null && port.direction() == Direction.INPUT).count();
        long outputs = node.ports().stream().filter(port -> port != null && port.direction() == Direction.OUTPUT).count();
        boolean valid = switch (node.kind()) {
            case "GATEWAY" -> inputs == 0 && outputs >= 1 && outputs <= 16;
            // A folder is a navigable boundary in the editor. Its ports describe
            // external connections; the child graph is validated separately.
            case "FOLDER" -> inputs >= 1 && inputs <= 16 && outputs >= 1 && outputs <= 16;
            case "SERVICE" -> inputs >= 1 && inputs <= 16 && outputs == 0;
            case "HOST" -> inputs >= 1 && inputs <= 16 && outputs >= 1 && outputs <= 16;
            case "ROUTER", "FIREWALL" -> inputs >= 1 && inputs <= 16 && outputs >= 1 && outputs <= 16;
            case "MERGE" -> inputs >= 2 && inputs <= 16 && outputs == 1;
            case "SPLIT" -> inputs == 1 && outputs >= 2 && outputs <= 16;
            case "LOAD_BALANCER" -> inputs >= 1 && inputs <= 16 && outputs >= 2 && outputs <= 16;
            case "MIRROR", "PACKET_TAP" -> inputs == 1 && outputs >= 2 && outputs <= 16;
            case "CUSTOM" -> inputs >= 1 && inputs <= 16 && outputs >= 1 && outputs <= 16;
            case "SWITCH", "FORWARDER" -> inputs >= 2 && inputs <= 16 && outputs >= 2 && outputs <= 16;
            default -> false;
        };
        return valid ? null : "节点种类或端口数量不符合约束";
    }

    private static boolean protocolMatches(Layer layer, Protocol protocol) {
        return switch (layer) {
            case L2 -> protocol == Protocol.ETHERNET;
            case L3 -> protocol == Protocol.IP;
            case L4 -> protocol == Protocol.TCP || protocol == Protocol.UDP || protocol == Protocol.SCTP;
            case L7 -> protocol == Protocol.HTTP || protocol == Protocol.HTTPS;
        };
    }

    private static boolean containsCycle(Set<java.util.UUID> nodes,
                                         Map<java.util.UUID, Set<java.util.UUID>> outgoing) {
        Set<java.util.UUID> visiting = new HashSet<>();
        Set<java.util.UUID> visited = new HashSet<>();
        for (java.util.UUID node : nodes) {
            if (!visited.contains(node) && visit(node, outgoing, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean visit(java.util.UUID node, Map<java.util.UUID, Set<java.util.UUID>> outgoing,
                                 Set<java.util.UUID> visiting, Set<java.util.UUID> visited) {
        if (visiting.contains(node)) {
            return true;
        }
        if (!visited.add(node)) {
            return false;
        }
        visiting.add(node);
        for (java.util.UUID next : outgoing.getOrDefault(node, Set.of())) {
            if (visit(next, outgoing, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(node);
        return false;
    }
}
