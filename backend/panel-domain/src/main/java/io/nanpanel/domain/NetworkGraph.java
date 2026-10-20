package io.nanpanel.domain;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Versioned desired network graph. Runtime observations are stored separately. */
public record NetworkGraph(UUID id, long generation, List<Node> nodes, List<Link> links) {
    public NetworkGraph {
        nodes = List.copyOf(nodes);
        links = List.copyOf(links);
    }

    public record Node(UUID id, String kind, String name, Position position, String providerId,
                       List<Port> ports) {
        public Node {
            ports = List.copyOf(ports);
        }
    }

    public record Position(double x, double y) {}

    public record Port(String id, Direction direction, Layer layer, Set<Protocol> protocols) {
        public Port {
            protocols = Set.copyOf(protocols);
        }
    }

    public record Link(UUID id, UUID sourceNodeId, String sourcePort, UUID targetNodeId,
                       String targetPort, Layer layer, Protocol protocol) {}

    public enum Layer { L2, L3, L4, L7 }

    public enum Direction { INPUT, OUTPUT }

    public enum Protocol { ETHERNET, IP, TCP, UDP, SCTP, HTTP, HTTPS }
}
