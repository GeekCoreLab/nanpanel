package io.nanpanel.cluster;

import java.net.URI;
import java.util.UUID;
/** Credentials are stored separately; never place tokens in endpoint URIs. */
public record ClusterReference(UUID id, Kind kind, URI endpoint) {
    public enum Kind { K3S, KUBEEDGE, KUBERNETES }
}
