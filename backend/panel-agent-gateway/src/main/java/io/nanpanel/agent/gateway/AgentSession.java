package io.nanpanel.agent.gateway;

import java.time.Instant;
import java.util.UUID;
/** Session metadata; transport and mTLS enrollment are not implemented yet. */
public record AgentSession(UUID agentId, UUID hostId, String certificateIdentity, long heartbeatSequence, Instant lastSeen) {}
