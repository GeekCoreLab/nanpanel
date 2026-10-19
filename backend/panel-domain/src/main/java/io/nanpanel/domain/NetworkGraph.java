package io.nanpanel.domain;

import java.util.List;
import java.util.UUID;

/** Desired topology. UI layout and observed health are stored separately. */
public record NetworkGraph(UUID id, long generation, List<Node> nodes, List<Link> links) {
    public NetworkGraph { nodes = List.copyOf(nodes); links = List.copyOf(links); }
    public record Node(UUID id, String kind, String providerId, List<Port> ports) {
        public Node { ports = List.copyOf(ports); }
    }
    public record Port(UUID id, Layer layer, Direction direction) {}
    public record Link(UUID id, UUID sourcePort, UUID targetPort, Service.Protocol transport) {}
    public enum Layer { L2, L3, L4, L7 }
    public enum Direction { INPUT, OUTPUT, BIDIRECTIONAL }
}
