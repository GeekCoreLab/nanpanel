package io.nanpanel.domain;

import java.util.UUID;
public record Operation(UUID id, String idempotencyKey, Phase phase, Status status, String error) {
    public enum Phase { VALIDATE, PREPARE, APPLY, VERIFY, COMMIT, COMPENSATE }
    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }
}
