package io.nanpanel.domain;

import java.util.UUID;

public record Service(UUID id, String name, Protocol protocol, String description) {
    public enum Protocol { TCP, UDP, HTTP, HTTPS }
}
