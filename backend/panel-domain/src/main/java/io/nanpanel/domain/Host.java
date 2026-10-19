package io.nanpanel.domain;

import java.util.Set;
import java.util.UUID;
public record Host(UUID id, String hostname, String os, String arch, Set<String> capabilities) {
    public Host { capabilities = Set.copyOf(capabilities); }
}
