package io.nanpanel.domain;

import java.time.Instant;
public record ResourceStatus(long generation, long observedGeneration, Health health, Instant observedAt) {
    public enum Health { UNKNOWN, PENDING, HEALTHY, DEGRADED, UNREACHABLE }
}
