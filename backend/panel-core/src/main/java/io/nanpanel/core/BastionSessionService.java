package io.nanpanel.core;

import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.quarkus.scheduler.Scheduled;

@ApplicationScoped
public class BastionSessionService {
  static final long MAX_RECORDING_BYTES = 8L * 1024 * 1024;
  static final int MAX_RECORDING_CHUNKS = 100_000;

  @Inject AuthStore store;
  @Inject HostSshCredentialService credentials;

  @Inject
  @ConfigProperty(name = "nanpanel.bastion.retention-days", defaultValue = "30")
  int retentionDays;

  public record StartRequest(String reason, Integer columns, Integer rows) {}
  public record SessionTicket(UUID sessionId, String ticket, Instant expiresAt,
                              String websocketPath) {}
  public record SessionAccess(UUID sessionId, UUID hostId, UUID actorId,
                              UUID webSessionId, String reason,
                              HostSshCredentialService.HostConfig hostConfig,
                              int columns, int rows) {}

  SessionTicket create(UUID hostId, UUID actorId, UUID webSessionId, String reason,
                       int columns, int rows, String clientIp) {
    var config = store.transaction(c -> credentials.find(c, hostId));
    if (config == null)
      throw AuthResource.fail(409, "SSH_CONFIG_REQUIRED", "请先为主机保存 SSH 地址和凭据");
    if (config.hostKeyFingerprint() == null)
      throw AuthResource.fail(409, "SSH_HOST_KEY_UNTRUSTED", "请先探测并确认目标 SSH host key 指纹");
    UUID sessionId = UUID.randomUUID();
    String ticket = AuthStore.token();
    Instant now = Instant.now();
    Instant expiresAt = now.plusSeconds(60);
    store.transaction(c -> {
      if (query(c, "SELECT id FROM host WHERE id=?", hostId).isEmpty())
        throw AuthResource.fail(404, "NOT_FOUND", "主机不存在");
      update(c, "INSERT INTO bastion_session (id,host_id,actor_id,web_session_id,reason," +
              "client_ip,target_address,target_port,target_username,target_fingerprint,status," +
              "ticket_hash,ticket_expires_at,started_at,last_activity_at,terminal_columns," +
              "terminal_rows) VALUES (?,?,?,?,?,?,?,?,?,?,'PENDING',?,?,?,?,?,?)",
          sessionId, hostId, actorId, webSessionId, reason, clientIp, config.address(),
          config.port(), config.username(), config.hostKeyFingerprint(), AuthStore.digest(ticket),
          Timestamp.from(expiresAt), Timestamp.from(now), Timestamp.from(now), columns, rows);
      audit(c, actorId.toString(), "BASTION_SESSION_CREATED", "host", hostId.toString(),
          "ACCEPTED", clientIp, "创建 SSH 堡垒终端会话；原因：" + reason);
      return null;
    });
    return new SessionTicket(sessionId, ticket, expiresAt,
        "/api/v1/bastion-sessions/" + sessionId + "/terminal");
  }

  SessionAccess consumeTicket(UUID sessionId, String ticket, int columns, int rows) {
    Instant now = Instant.now();
    long nowMs = System.currentTimeMillis();
    return store.transaction(c -> {
      var rowsFound = query(c, "SELECT id,host_id,actor_id,web_session_id,reason," +
          "target_address,target_port,target_username,target_fingerprint,status,ticket_hash," +
          "ticket_expires_at FROM bastion_session WHERE id=? FOR UPDATE", sessionId);
      if (rowsFound.isEmpty()) throw AuthResource.fail(404, "BASTION_SESSION_NOT_FOUND", "堡垒机会话不存在");
      var row = rowsFound.getFirst();
      Object expiry = row.get("ticket_expires_at");
      Instant ticketExpiresAt = expiry instanceof Timestamp timestamp ? timestamp.toInstant()
          : expiry instanceof java.time.OffsetDateTime timestamp ? timestamp.toInstant() : null;
      String storedHash = (String)row.get("ticket_hash");
      if (!"PENDING".equals(row.get("status")) || storedHash == null ||
          ticketExpiresAt == null || ticketExpiresAt.isBefore(now) ||
          !java.security.MessageDigest.isEqual(storedHash.getBytes(StandardCharsets.US_ASCII),
              AuthStore.digest(ticket).getBytes(StandardCharsets.US_ASCII)))
        throw AuthResource.fail(401, "BASTION_TICKET_INVALID", "堡垒终端票据无效、已过期或已使用");

      UUID actorId = UUID.fromString(String.valueOf(row.get("actor_id")));
      UUID webSessionId = (UUID)row.get("web_session_id");
      var webSessions = query(c, "SELECT id,user_id,idle_expires,absolute_expires " +
          "FROM web_session WHERE id=? AND user_id=? FOR UPDATE",
          webSessionId.toString(), actorId.toString());
      if (webSessions.isEmpty())
        throw AuthResource.fail(401, "BASTION_LOGIN_EXPIRED", "登录会话已撤销");
      var webSession = webSessions.getFirst();
      long idleExpires = ((Number)webSession.get("idle_expires")).longValue();
      long absoluteExpires = ((Number)webSession.get("absolute_expires")).longValue();
      if (idleExpires <= nowMs || absoluteExpires <= nowMs)
        throw AuthResource.fail(401, "BASTION_LOGIN_EXPIRED", "登录会话已过期");
      long nextExpiry = Math.min(nowMs + 1_800_000, absoluteExpires);
      update(c, "UPDATE web_session SET last_seen=?,idle_expires=? WHERE id=?",
          nowMs, nextExpiry, webSessionId.toString());

      UUID hostId = (UUID)row.get("host_id");
      HostSshCredentialService.HostConfig config = credentials.find(c, hostId);
      if (config == null || config.hostKeyFingerprint() == null ||
          !Objects.equals(String.valueOf(row.get("target_address")), config.address()) ||
          ((Number)row.get("target_port")).intValue() != config.port() ||
          !Objects.equals(String.valueOf(row.get("target_username")), config.username()) ||
          !Objects.equals(String.valueOf(row.get("target_fingerprint")), config.hostKeyFingerprint()))
        throw AuthResource.fail(409, "SSH_CONFIG_CHANGED", "SSH 配置在票据签发后已变化，请重新开启终端");
      update(c, "UPDATE bastion_session SET status='RUNNING',ticket_hash=NULL," +
              "ticket_used_at=?,last_activity_at=?,terminal_columns=?,terminal_rows=? WHERE id=?",
          Timestamp.from(now), Timestamp.from(now), columns, rows, sessionId);
      audit(c, actorId.toString(), "BASTION_SESSION_STARTED", "bastion_session",
          sessionId.toString(), "SUCCEEDED", (String)row.get("client_ip"),
          "开始 SSH 堡垒终端会话；目标：" + config.address() + ":" + config.port());
      return new SessionAccess(sessionId, hostId, actorId, webSessionId,
          String.valueOf(row.get("reason")), config, columns, rows);
    });
  }

  void touch(UUID sessionId, UUID webSessionId, UUID actorId) {
    long nowMs = System.currentTimeMillis();
    Instant now = Instant.now();
    store.transaction(c -> {
      var rows = query(c, "SELECT idle_expires,absolute_expires FROM web_session " +
          "WHERE id=? AND user_id=? FOR UPDATE", webSessionId.toString(), actorId.toString());
      if (rows.isEmpty()) throw AuthResource.fail(401, "BASTION_LOGIN_EXPIRED", "登录会话已撤销");
      long idleExpires = ((Number)rows.getFirst().get("idle_expires")).longValue();
      long absoluteExpires = ((Number)rows.getFirst().get("absolute_expires")).longValue();
      if (idleExpires <= nowMs || absoluteExpires <= nowMs)
        throw AuthResource.fail(401, "BASTION_LOGIN_EXPIRED", "登录会话已过期");
      update(c, "UPDATE web_session SET last_seen=?,idle_expires=? WHERE id=?",
          nowMs, Math.min(nowMs + 1_800_000, absoluteExpires), webSessionId.toString());
      update(c, "UPDATE bastion_session SET last_activity_at=? WHERE id=? AND actor_id=? AND status='RUNNING'",
          Timestamp.from(now), sessionId, actorId.toString());
      return null;
    });
  }

  boolean recordChunk(UUID sessionId, String direction, byte[] payload) {
    if (!"INPUT".equals(direction) && !"OUTPUT".equals(direction))
      throw new IllegalArgumentException("Invalid terminal recording direction");
    if (payload.length == 0) return true;
    Instant now = Instant.now();
    return store.transaction(c -> {
      var sessions = query(c, "SELECT started_at,bytes_in,bytes_out,recording_chunks " +
          "FROM bastion_session " +
          "WHERE id=? AND status='RUNNING' FOR UPDATE", sessionId);
      if (sessions.isEmpty()) return false;
      var session = sessions.getFirst();
      long bytesIn = ((Number)session.get("bytes_in")).longValue();
      long bytesOut = ((Number)session.get("bytes_out")).longValue();
      int chunks = ((Number)session.get("recording_chunks")).intValue();
      if (bytesIn + bytesOut + payload.length > MAX_RECORDING_BYTES ||
          chunks >= MAX_RECORDING_CHUNKS) return false;
      Object started = session.get("started_at");
      Instant startedAt = started instanceof Timestamp timestamp ? timestamp.toInstant()
          : ((java.time.OffsetDateTime)started).toInstant();
      long offset = Math.max(0, now.toEpochMilli() - startedAt.toEpochMilli());
      long sequence = ((Number)query(c,
          "SELECT COALESCE(MAX(sequence),0)+1 AS next_sequence FROM " +
              "bastion_recording_chunk WHERE session_id=?", sessionId)
          .getFirst().get("next_sequence")).longValue();
      byte[] encrypted = credentials.encryptAudit(sessionId, sequence, direction, payload);
      try {
        update(c, "INSERT INTO bastion_recording_chunk " +
                "(session_id,sequence,offset_ms,direction,payload) VALUES (?,?,?,?,?)",
            sessionId, sequence, offset, direction, encrypted);
      } finally {
        java.util.Arrays.fill(encrypted, (byte)0);
      }
      String column = "INPUT".equals(direction) ? "bytes_in" : "bytes_out";
      update(c, "UPDATE bastion_session SET " + column + "=" + column + "+?," +
              "recording_chunks=recording_chunks+1,last_activity_at=? WHERE id=?",
          payload.length, Timestamp.from(now), sessionId);
      return true;
    });
  }

  void recordCommand(UUID sessionId, int sequence, String command) {
    if (command == null || command.isBlank()) return;
    String normalized = command.length() > 4096 ? command.substring(0, 4096) : command;
    byte[] cleartext = normalized.getBytes(StandardCharsets.UTF_8);
    byte[] encrypted = credentials.encryptAudit(sessionId, sequence, "COMMAND", cleartext);
    java.util.Arrays.fill(cleartext, (byte)0);
    try {
      store.transaction(c -> {
        update(c, "INSERT INTO bastion_command " +
                "(session_id,sequence,occurred_at,encrypted_command) VALUES (?,?,?,?)",
            sessionId, sequence, Timestamp.from(Instant.now()), encrypted);
        return null;
      });
    } finally {
      java.util.Arrays.fill(encrypted, (byte)0);
    }
  }

  void finish(UUID sessionId, String status, String failureCode, Integer exitCode) {
    if (!List.of("CLOSED", "FAILED").contains(status))
      throw new IllegalArgumentException("Invalid bastion session status");
    Instant now = Instant.now();
    store.transaction(c -> {
      var rows = query(c, "SELECT started_at,host_id,actor_id FROM bastion_session " +
          "WHERE id=? AND status IN ('PENDING','RUNNING') FOR UPDATE", sessionId);
      if (rows.isEmpty()) return null;
      var row = rows.getFirst();
      Object started = row.get("started_at");
      Instant startedAt = started instanceof Timestamp timestamp ? timestamp.toInstant()
          : ((java.time.OffsetDateTime)started).toInstant();
      long duration = Math.max(0, now.toEpochMilli() - startedAt.toEpochMilli());
      update(c, "UPDATE bastion_session SET status=?,failure_code=?,exit_code=?," +
              "finished_at=?,duration_ms=?,ticket_hash=NULL WHERE id=?",
          status, failureCode, exitCode, Timestamp.from(now), duration, sessionId);
      audit(c, String.valueOf(row.get("actor_id")), "BASTION_SESSION_" + status,
          "bastion_session", sessionId.toString(), status.equals("CLOSED") ? "SUCCEEDED" : "FAILED",
          null, "SSH 堡垒终端会话结束；耗时毫秒：" + duration +
              (failureCode == null ? "" : "；错误类型：" + failureCode));
      return null;
    });
  }

  void forceTerminate(UUID sessionId, UUID operatorId, String reason, String clientIp) {
    Instant now = Instant.now();
    store.transaction(c -> {
      var rows = query(c, "SELECT status,started_at FROM bastion_session WHERE id=? FOR UPDATE",
          sessionId);
      if (rows.isEmpty())
        throw AuthResource.fail(404, "BASTION_SESSION_NOT_FOUND", "堡垒机会话不存在");
      var row = rows.getFirst();
      String status = String.valueOf(row.get("status"));
      if (!List.of("PENDING", "RUNNING").contains(status))
        throw AuthResource.fail(409, "BASTION_SESSION_NOT_ACTIVE", "堡垒会话已经结束");
      Object started = row.get("started_at");
      Instant startedAt = started instanceof Timestamp timestamp ? timestamp.toInstant()
          : ((java.time.OffsetDateTime)started).toInstant();
      long duration = "PENDING".equals(status) ? 0
          : Math.max(0, now.toEpochMilli() - startedAt.toEpochMilli());
      update(c, "UPDATE bastion_session SET status='FAILED',failure_code='FORCE_TERMINATED'," +
              "finished_at=?,duration_ms=?,ticket_hash=NULL WHERE id=?",
          Timestamp.from(now), duration, sessionId);
      audit(c, operatorId.toString(), "BASTION_SESSION_FORCE_TERMINATED", "bastion_session",
          sessionId.toString(), "SUCCEEDED", clientIp,
          "管理员强制阻断 SSH 堡垒会话；原因：" + reason);
      return null;
    });
  }

  List<Map<String, Object>> list(UUID hostId, int limit) {
    return store.transaction(c -> {
      String sql = "SELECT s.id,s.host_id,h.hostname,s.actor_id,s.reason,s.target_address," +
          "s.target_port,s.target_username,s.target_fingerprint,s.status,s.started_at," +
          "s.finished_at,s.duration_ms,s.bytes_in,s.bytes_out,s.failure_code " +
          "FROM bastion_session s JOIN host h ON h.id=s.host_id " +
          (hostId == null ? "" : "WHERE s.host_id=? ") +
          "ORDER BY s.started_at DESC,s.id DESC LIMIT ?";
      var values = hostId == null ? new Object[] {limit} : new Object[] {hostId, limit};
      var rows = query(c, sql, values);
      return rows.stream().map(this::sessionView).toList();
    });
  }

  List<Map<String, Object>> recording(UUID sessionId, UUID actorId, String clientIp) {
    return store.transaction(c -> {
      if (query(c, "SELECT id FROM bastion_session WHERE id=?", sessionId).isEmpty())
        throw AuthResource.fail(404, "BASTION_SESSION_NOT_FOUND", "堡垒机会话不存在");
      audit(c, actorId.toString(), "BASTION_RECORDING_VIEWED", "bastion_session",
          sessionId.toString(), "SUCCEEDED", clientIp, "读取 SSH 堡垒终端录像");
      var chunks = query(c, "SELECT sequence,offset_ms,direction,payload " +
          "FROM bastion_recording_chunk WHERE session_id=? ORDER BY sequence", sessionId);
      return chunks.stream().map(chunk -> {
        long sequence = ((Number)chunk.get("sequence")).longValue();
        String direction = String.valueOf(chunk.get("direction"));
        byte[] cleartext = credentials.decryptAudit(sessionId, sequence, direction,
            (byte[])chunk.get("payload"));
        try {
          return Map.<String, Object>of("sequence", sequence,
              "offsetMs", ((Number)chunk.get("offset_ms")).longValue(),
              "direction", direction,
              "data", Base64.getEncoder().encodeToString(cleartext));
        } finally {
          java.util.Arrays.fill(cleartext, (byte)0);
        }
      }).toList();
    });
  }

  List<Map<String, Object>> commands(UUID sessionId, UUID actorId, String clientIp) {
    return store.transaction(c -> {
      if (query(c, "SELECT id FROM bastion_session WHERE id=?", sessionId).isEmpty())
        throw AuthResource.fail(404, "BASTION_SESSION_NOT_FOUND", "堡垒机会话不存在");
      audit(c, actorId.toString(), "BASTION_COMMANDS_VIEWED", "bastion_session",
          sessionId.toString(), "SUCCEEDED", clientIp, "读取 SSH 堡垒命令行历史");
      return query(c, "SELECT sequence,occurred_at,encrypted_command FROM bastion_command " +
          "WHERE session_id=? ORDER BY sequence", sessionId).stream().map(row -> {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("sequence", ((Number)row.get("sequence")).intValue());
        command.put("occurredAt", timestamp(row.get("occurred_at")));
        command.put("command", decodeCommand(sessionId,
            ((Number)row.get("sequence")).longValue(), (byte[])row.get("encrypted_command")));
        return command;
      }).toList();
    });
  }

  @Scheduled(every = "1h")
  void purgeExpired() {
    int days = Math.max(1, Math.min(3650, retentionDays));
    Timestamp cutoff = Timestamp.from(Instant.now().minusSeconds(days * 86_400L));
    store.transaction(c -> {
      update(c, "DELETE FROM bastion_session WHERE status IN ('CLOSED','FAILED') " +
          "AND started_at<?", cutoff);
      return null;
    });
  }

  @Scheduled(every = "1m")
  void expireUnusedTickets() {
    Instant now = Instant.now();
    store.transaction(c -> {
      update(c, "UPDATE bastion_session SET status='FAILED',failure_code='TICKET_EXPIRED'," +
              "ticket_hash=NULL,finished_at=?,duration_ms=0 WHERE status='PENDING' " +
              "AND ticket_expires_at<?",
          Timestamp.from(now), Timestamp.from(now));
      return null;
    });
  }

  private Map<String, Object> sessionView(Map<String, Object> row) {
    var view = new LinkedHashMap<String, Object>();
    for (String field : List.of("id", "host_id", "hostname", "actor_id", "reason",
        "target_address", "target_port", "target_username", "target_fingerprint", "status",
        "duration_ms", "bytes_in", "bytes_out", "failure_code")) {
      String name = switch (field) {
        case "id" -> "id";
        case "host_id" -> "hostId";
        case "hostname" -> "hostname";
        case "actor_id" -> "actorId";
        case "target_address" -> "targetAddress";
        case "target_port" -> "targetPort";
        case "target_username" -> "targetUsername";
        case "target_fingerprint" -> "targetFingerprint";
        case "duration_ms" -> "durationMs";
        case "bytes_in" -> "bytesIn";
        case "bytes_out" -> "bytesOut";
        case "failure_code" -> "failureCode";
        default -> field;
      };
      Object value = row.get(field);
      view.put(name, value instanceof UUID ? value.toString() : value);
    }
    view.put("startedAt", timestamp(row.get("started_at")));
    view.put("finishedAt", timestamp(row.get("finished_at")));
    return view;
  }

  private String timestamp(Object value) {
    if (value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
    if (value instanceof java.time.OffsetDateTime timestamp) return timestamp.toInstant().toString();
    return null;
  }

  private String decodeCommand(UUID sessionId, long sequence, byte[] encrypted) {
    byte[] cleartext = credentials.decryptAudit(sessionId, sequence, "COMMAND", encrypted);
    try {
      return new String(cleartext, StandardCharsets.UTF_8);
    } finally {
      java.util.Arrays.fill(cleartext, (byte)0);
    }
  }
}
