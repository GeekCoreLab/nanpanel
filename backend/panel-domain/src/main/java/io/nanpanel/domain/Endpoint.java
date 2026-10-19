package io.nanpanel.domain;

import java.util.UUID;

public record Endpoint(UUID id, UUID serviceId, String address, int port, String scheme) {
    public Endpoint {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be in 1..65535");
    }
}
