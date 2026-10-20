package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.net.URI;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.OpenOption;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import org.eclipse.microprofile.context.ManagedExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Fixed SSH operations using encrypted host-asset credentials and a pinned host key. */
@jakarta.ws.rs.Path("/api/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class HostSshAccessResource {
  private static final java.util.regex.Pattern UNIT =
      java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.@:-]{0,247}\\.service");
  private static final String SSH_RESTART_KIND = "SSH_SYSTEMD_RESTART";
  private static final String SSH_AGENT_INSTALL_KIND = "SSH_AGENT_INSTALL";
  private static final int MAX_INSTALLER_BYTES = 64 * 1024;
  private static final int MAX_SSH_CAPTURE_BYTES = 24 * 1024;
  private static final Semaphore SSH_BOOTSTRAP_SLOTS = new Semaphore(2);
  private static final Semaphore SSH_COMMAND_SLOTS = new Semaphore(4);

  @Inject AuthStore store;
  @Inject ObjectMapper mapper;
  @Inject ManagedExecutor managedExecutor;
  @Inject AgentCertificateAuthority certificateAuthority;
  @Inject RelayFallbackSigner relayFallbackSigner;
  @Inject HostSshCredentialService sshCredentials;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  @Inject
  @ConfigProperty(name = "nanpanel.agent.installer", defaultValue = "/work/agent-install.sh")
  String installerPath;

  @Inject
  @ConfigProperty(name = "nanpanel.agent.public-origin", defaultValue = "disabled")
  String publicOrigin;

  @Inject
  @ConfigProperty(name = "nanpanel.agent.gateway-port", defaultValue = "30444")
  int gatewayPort;

  @Inject
  @ConfigProperty(name = "nanpanel.agent.relay-endpoints", defaultValue = "disabled")
  String relayEndpointConfiguration;

  public record ProbeRequest(String reason) {}
  public record RestartRequest(String unitName, String reason) {}
  public record UnitDiagnosticRequest(String unitName, String reason) {}
  public record JournalReadRequest(String unitName, int lines, String reason) {}
  public record AgentInstallRequest(UUID enrollmentId, UUID agentId, String token, String reason) {}
  private record ProcessResult(int exitCode, boolean timedOut, String output,
                               boolean truncated, boolean outputFailed) {
    private ProcessResult(int exitCode, boolean timedOut) {
      this(exitCode, timedOut, "", false, false);
    }
  }
  private record OperationDecision(Map<String, Object> operation, int status, boolean replayed,
                                   UUID operationId) {}

  @POST
  @jakarta.ws.rs.Path("hosts/{hostId}/ssh/probe")
  public Response probe(@PathParam("hostId") String hostValue, ProbeRequest input) {
    UUID hostId = uuid(hostValue);
    String actor = actorId();
    String reason = input == null || input.reason() == null ? "" : input.reason().trim();
    if (reason.isEmpty() || reason.length() > 500 ||
        reason.chars().anyMatch(Character::isISOControl))
      throw fail(422, "VALIDATION_FAILED", "SSH 操作原因无效");
    HostSshCredentialService.HostConfig config = requireAssetSsh(hostId, actor, "SSH_ACCESS_PROBED");
    ProcessResult probeResult = runDirectSsh(config, List.of("true"), 10, 1024);
    boolean succeeded = !probeResult.timedOut() && probeResult.exitCode() == 0 &&
        !probeResult.outputFailed();
    recordAudit(actor, hostId, "SSH_ACCESS_PROBED", succeeded ? "SUCCEEDED" : "FAILED",
        "固定 SSH host-key 探测 " + config.address() + ":" + config.port() + "：" +
            (succeeded ? "成功" : "失败") + "；原因：" + reason);
    if (!succeeded)
      throw fail(502, "SSH_PROBE_FAILED", "SSH 连接或 host key 校验失败；检查凭据、网络和已确认的 host key");

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("hostId", hostId.toString());
    response.put("address", config.address());
    response.put("port", config.port());
    response.put("status", "SUCCEEDED");
    response.put("verifiedAt", Instant.now().toString());
    response.put("evidence", "已确认的 host key 匹配，固定 true 命令返回 0");
    return Response.ok(response).build();
  }

  @POST
  @jakarta.ws.rs.Path("hosts/{hostId}/ssh/systemd/restart")
  public Response restartSystemd(
      @PathParam("hostId") String hostValue,
      @HeaderParam("Idempotency-Key") String idempotencyKey,
      RestartRequest input) {
    UUID hostId = uuid(hostValue);
    String actor = actorId();
    String key = validateIdempotencyKey(idempotencyKey);
    String unit = input == null || input.unitName() == null ? "" : input.unitName().trim();
    String reason = input == null || input.reason() == null ? "" : input.reason().trim();
    if (!UNIT.matcher(unit).matches() || reason.isEmpty() || reason.length() > 500 ||
        reason.chars().anyMatch(Character::isISOControl))
      throw fail(422, "VALIDATION_FAILED", "SSH systemd unit 或操作原因无效");

    HostSshCredentialService.HostConfig sshConfig =
        requireAssetSsh(hostId, actor, SSH_RESTART_KIND);

    String requestHash = AuthStore.digest(String.join("\n", "POST",
        "/api/v1/hosts/" + hostId + "/ssh/systemd/restart", unit, reason));
    Instant now = Instant.now();
    UUID operationId = UUID.randomUUID();
    OperationDecision decision = store.transaction(c -> {
      query(c, "SELECT id FROM platform_settings WHERE id=1 FOR UPDATE");
      var existing = query(c, operationSelect() + " WHERE o.idempotency_key=?", key);
      if (!existing.isEmpty()) {
        var row = existing.getFirst();
        if (!Objects.equals(row.get("requested_by"), actor) ||
            !Objects.equals(row.get("host_id"), hostId) ||
            !Objects.equals(row.get("kind"), SSH_RESTART_KIND) ||
            !Objects.equals(row.get("target"), unit) ||
            !MessageDigest.isEqual(String.valueOf(row.get("request_hash")).getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII)))
          throw fail(409, "IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同请求");
        String status = String.valueOf(row.get("status"));
        return new OperationDecision(operationView(row),
            Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status) ? 200 : 202,
            true, (UUID)row.get("id"));
      }
      var hosts = query(c, "SELECT id FROM host WHERE id=? FOR UPDATE", hostId);
      if (hosts.isEmpty())
        throw fail(404, "NOT_FOUND", "主机不存在");
      var pending = query(c,
          "SELECT o.id FROM operation o LEFT JOIN system_service_observation s " +
              "ON s.id=o.service_id WHERE o.host_id=? " +
              "AND o.status IN ('PENDING','RUNNING','UNKNOWN') AND " +
              "((o.kind=? AND o.target=?) OR (o.kind='SYSTEM_SERVICE_RESTART' " +
              "AND s.unit_name=?)) LIMIT 1",
          hostId, SSH_RESTART_KIND, unit, unit);
      if (!pending.isEmpty())
        throw fail(409, "OPERATION_IN_PROGRESS", "该主机已有 SSH 恢复操作等待完成或对账");
      update(c,
          "INSERT INTO operation (id,idempotency_key,phase,status,error,created_at,updated_at," +
              "kind,host_id,requested_by,reason,started_at,deadline_at,request_hash,target,capability) " +
              "VALUES (?,?,?,'RUNNING',NULL,?,?,?,?,?,?,?,?,?,?,?)",
          operationId, key, "APPLY", java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
          SSH_RESTART_KIND, hostId, actor, reason, java.sql.Timestamp.from(now),
          java.sql.Timestamp.from(now.plusSeconds(30)), requestHash, unit, "systemd.restart");
      audit(c, actor, SSH_RESTART_KIND, "host", hostId.toString(), "ACCEPTED",
          http.remoteAddress() == null ? null : http.remoteAddress().host(),
          "接受 SSH systemd 恢复操作：" + unit + "；原因：" + reason);
      return new OperationDecision(Map.of("id", operationId.toString(), "kind", SSH_RESTART_KIND,
          "hostId", hostId.toString(), "capability", "systemd.restart", "idempotencyKey", key,
          "target", unit, "reason", reason, "status", "RUNNING", "phase", "APPLY"),
          202, false, operationId);
    });

    if (!decision.replayed()) {
      ProcessResult restart = runDirectSsh(sshConfig,
          List.of("sudo", "-n", "/usr/bin/systemctl", "restart", "--", unit), 20, 1024);
      ProcessResult verification = restart.timedOut() || restart.exitCode() != 0
          ? restart
          : runDirectSsh(sshConfig,
              List.of("sudo", "-n", "/usr/bin/systemctl", "is-active", "--quiet", "--", unit), 10, 1024);
      boolean succeeded = !restart.timedOut() && restart.exitCode() == 0 &&
          !verification.timedOut() && verification.exitCode() == 0;
      boolean unknown = restart.timedOut() || restart.exitCode() == 255 ||
          (restart.exitCode() == 0 && (verification.timedOut() || verification.exitCode() == 255));
      String resultJson = succeeded ? successJson(unit) : null;
      String errorJson = succeeded ? null : errorJson(unknown ? "EXECUTION_OUTCOME_UNKNOWN"
          : "SSH_SYSTEMD_RESTART_FAILED", unknown
              ? "SSH command result is uncertain; do not retry with a new idempotency key"
              : "SSH systemd restart or active-state verification failed",
          unknown ? "UNCERTAIN" : "VERIFIED_FAILED");
      Instant finishedAt = unknown ? null : Instant.now();
      String finalStatus = succeeded ? "SUCCEEDED" : unknown ? "UNKNOWN" : "FAILED";
      String finalPhase = unknown ? "APPLY" : "VERIFY";
      store.transaction(c -> {
        update(c,
            "UPDATE operation SET status=?,phase=?,result_json=?,error_json=?,updated_at=?,finished_at=? " +
                "WHERE id=? AND status='RUNNING'",
            finalStatus, finalPhase, resultJson, errorJson, java.sql.Timestamp.from(Instant.now()),
            finishedAt == null ? null : java.sql.Timestamp.from(finishedAt), decision.operationId());
        audit(c, actor, SSH_RESTART_KIND, "host", hostId.toString(), finalStatus,
            http.remoteAddress() == null ? null : http.remoteAddress().host(),
            "SSH systemd 恢复操作 " + unit + "：" + finalStatus + "；原因：" + reason);
        return null;
      });
    }
    Map<String, Object> operation = loadOperation(decision.operationId());
    String status = String.valueOf(operation.get("status"));
    int httpStatus = Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status) ? 200 : 202;
    return Response.status(httpStatus).entity(operation)
        .header("Location", "/api/v1/operations/" + decision.operationId())
        .header("Retry-After", "2")
        .header("Idempotency-Replayed", Boolean.toString(decision.replayed())).build();
  }

  @POST
  @jakarta.ws.rs.Path("hosts/{hostId}/ssh/systemd/status")
  public Response readSystemdStatus(@PathParam("hostId") String hostValue,
                                    UnitDiagnosticRequest input) {
    UUID hostId = uuid(hostValue);
    String actor = actorId();
    String unit = validateDiagnostic(input == null ? null : input.unitName(),
        input == null ? null : input.reason());
    String reason = input.reason().trim();
    HostSshCredentialService.HostConfig sshConfig =
        requireAssetSsh(hostId, actor, "SSH_SYSTEMD_STATUS_READ");
    ProcessResult status = runBoundedSsh(sshConfig,
        List.of("sudo", "-n", "/usr/bin/systemctl", "show", "--no-pager",
            "--property=LoadState,ActiveState,SubState", "--value", "--", unit), 10, 4096);
    String[] values = status.output().strip().split("\\R", -1);
    boolean validResponse = values.length == 3;
    boolean succeeded = !status.timedOut() && status.exitCode() == 0 &&
        !status.outputFailed() && validResponse;
    recordAudit(actor, hostId, "SSH_SYSTEMD_STATUS_READ", succeeded ? "SUCCEEDED" : "FAILED",
        "通过资产 SSH 读取 systemd 状态 " + unit + "；原因：" + reason);
    if (!succeeded) throw fail(502, validResponse ? "SSH_SYSTEMD_STATUS_FAILED"
        : "SSH_SYSTEMD_STATUS_INVALID", validResponse ? "SSH systemd 状态读取失败"
            : "远程 systemd 状态响应格式无效");
    return Response.ok(Map.of("hostId", hostId.toString(), "unitName", unit,
        "loadState", values[0], "activeState", values[1], "subState", values[2],
        "observedAt", Instant.now().toString())).build();
  }

  @POST
  @jakarta.ws.rs.Path("hosts/{hostId}/ssh/systemd/journal")
  public Response readSystemdJournal(@PathParam("hostId") String hostValue,
                                     JournalReadRequest input) {
    UUID hostId = uuid(hostValue);
    String actor = actorId();
    String unit = validateDiagnostic(input == null ? null : input.unitName(),
        input == null ? null : input.reason());
    if (input.lines() < 1 || input.lines() > 100)
      throw fail(422, "VALIDATION_FAILED", "SSH journal 行数须为 1–100");
    String reason = input.reason().trim();
    HostSshCredentialService.HostConfig sshConfig =
        requireAssetSsh(hostId, actor, "SSH_SYSTEMD_JOURNAL_READ");
    ProcessResult journal = runBoundedSsh(sshConfig,
        List.of("sudo", "-n", "/usr/bin/journalctl", "--no-pager",
            "--output=short-iso", "--lines=" + input.lines(), "--unit=" + unit), 12,
        MAX_SSH_CAPTURE_BYTES);
    boolean succeeded = !journal.timedOut() && journal.exitCode() == 0 && !journal.outputFailed();
    recordAudit(actor, hostId, "SSH_SYSTEMD_JOURNAL_READ", succeeded ? "SUCCEEDED" : "FAILED",
        "通过资产 SSH 读取 systemd 日志 " + unit + "，最多 " + input.lines() +
            " 行；原因：" + reason);
    if (!succeeded) throw fail(502, "SSH_SYSTEMD_JOURNAL_FAILED", "SSH systemd 日志读取失败");
    return Response.ok(Map.of("hostId", hostId.toString(), "unitName", unit,
        "requestedLines", input.lines(), "output", journal.output(),
        "truncated", journal.truncated(), "observedAt", Instant.now().toString())).build();
  }

  private String validateDiagnostic(String unitValue, String reasonValue) {
    String unit = unitValue == null ? "" : unitValue.trim();
    String reason = reasonValue == null ? "" : reasonValue.trim();
    if (!UNIT.matcher(unit).matches() || reason.isEmpty() || reason.length() > 500 ||
        reason.chars().anyMatch(Character::isISOControl))
      throw fail(422, "VALIDATION_FAILED", "SSH systemd unit 或操作原因无效");
    return unit;
  }

  private HostSshCredentialService.HostConfig requireAssetSsh(
      UUID hostId, String actor, String action) {
    HostSshCredentialService.HostConfig config = store.transaction(c -> sshCredentials.find(c, hostId));
    if (config == null || config.hostKeyFingerprint() == null || config.encryptedCredential() == null) {
      recordAudit(actor, hostId, action, "DENIED",
          "SSH 资产配置缺失或 host key 尚未确认");
      throw fail(422, "SSH_HOST_KEY_UNTRUSTED", "请先配置 SSH 凭据并确认目标 host key 指纹");
    }
    return config;
  }

  private ProcessResult runBoundedSsh(HostSshCredentialService.HostConfig config,
      List<String> command, int timeoutSeconds, int outputLimit) {
    if (!SSH_COMMAND_SLOTS.tryAcquire())
      throw fail(503, "SSH_CAPACITY_EXCEEDED", "SSH 诊断并发容量已满");
    try {
      return runDirectSsh(config, command, timeoutSeconds, outputLimit);
    } finally {
      SSH_COMMAND_SLOTS.release();
    }
  }

  private ProcessResult runDirectSsh(HostSshCredentialService.HostConfig config,
      List<String> command, int timeoutSeconds, int outputLimit) {
    try {
      HostSshCredentialService.CommandResult result = sshCredentials.execute(
          config, command.stream().map(HostSshAccessResource::shellQuote)
              .collect(java.util.stream.Collectors.joining(" ")),
          timeoutSeconds, outputLimit);
      return new ProcessResult(result.exitCode(), result.timedOut(), result.output(),
          result.truncated(), false);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new ProcessResult(124, true, "", false, true);
    } catch (Exception failure) {
      return new ProcessResult(255, false, "", false, true);
    }
  }

  @POST
  @jakarta.ws.rs.Path("hosts/{hostId}/ssh/agent/install")
  public Response installAgentOverSsh(
      @PathParam("hostId") String hostValue,
      @HeaderParam("Idempotency-Key") String idempotencyKey,
      AgentInstallRequest input) {
    UUID hostId = uuid(hostValue);
    String actor = actorId();
    String key = validateIdempotencyKey(idempotencyKey);
    String token = input == null || input.token() == null ? "" : input.token().trim();
    String reason = input == null || input.reason() == null ? "" : input.reason().trim();
    if (input == null || input.enrollmentId() == null || input.agentId() == null ||
        !token.matches("[A-Za-z0-9_-]{40,128}") ||
        reason.isEmpty() || reason.length() > 500 ||
        reason.chars().anyMatch(Character::isISOControl) || reason.contains(token))
      throw fail(422, "VALIDATION_FAILED", "接入码、Agent ID 或操作原因无效");

    HostSshCredentialService.HostConfig directConfig =
        requireAssetSsh(hostId, actor, SSH_AGENT_INSTALL_KIND);
    byte[] installer;
    URI origin;
    String relayEndpoints;
    RelayFallbackSigner.Manifest relayFallback;
    try {
      installer = readTrustedInstaller(Path.of(installerPath));
      origin = trustedHttpsOrigin(publicOrigin);
      relayEndpoints = String.join(",",
          AgentEnrollmentResource.parseRelayEndpoints(relayEndpointConfiguration));
      relayFallback = relayFallbackSigner.manifest();
      if (gatewayPort < 1 || gatewayPort > 65535)
        throw fail(503, "SSH_INSTALLER_UNSAFE", "Agent Gateway 端口配置无效");
    } catch (jakarta.ws.rs.WebApplicationException e) {
      recordAudit(actor, hostId, SSH_AGENT_INSTALL_KIND, "FAILED", "SSH 安装配置不可用或不安全");
      throw e;
    } catch (IllegalArgumentException | UnsupportedOperationException e) {
      recordAudit(actor, hostId, SSH_AGENT_INSTALL_KIND, "FAILED", "SSH 安装配置不可用或不安全");
      throw fail(503, "SSH_CONFIGURATION_UNAVAILABLE", "Agent SSH 安装配置不可用");
    }

    String tokenDigest = AuthStore.digest(token);
    String requestHash = AuthStore.digest(String.join("\n", "POST",
        "/api/v1/hosts/" + hostId + "/ssh/agent/install",
        input.enrollmentId().toString(), input.agentId().toString(),
        directConfig.address() + ":" + directConfig.port(), tokenDigest, reason));
    Instant now = Instant.now();
    String remoteAddress = http.remoteAddress() == null ? null : http.remoteAddress().host();
    UUID operationId = UUID.randomUUID();
    OperationDecision decision = store.transaction(c -> {
      query(c, "SELECT id FROM platform_settings WHERE id=1 FOR UPDATE");
      var existing = query(c, operationSelect() + " WHERE o.idempotency_key=?", key);
      if (!existing.isEmpty()) {
        var row = existing.getFirst();
        if (!Objects.equals(row.get("requested_by"), actor) ||
            !Objects.equals(row.get("host_id"), hostId) ||
            !Objects.equals(row.get("kind"), SSH_AGENT_INSTALL_KIND) ||
            !Objects.equals(row.get("target"), input.agentId().toString()) ||
            !MessageDigest.isEqual(String.valueOf(row.get("request_hash")).getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII)))
          throw fail(409, "IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同请求");
        String status = String.valueOf(row.get("status"));
        return new OperationDecision(operationView(row),
            Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status) ? 200 : 202,
            true, (UUID)row.get("id"));
      }
      var hosts = query(c, "SELECT id FROM host WHERE id=? FOR UPDATE", hostId);
      if (hosts.isEmpty()) throw fail(404, "NOT_FOUND", "主机不存在");
      var enrollments = query(c,
          "SELECT id FROM agent_enrollment WHERE id=? AND host_id=? AND agent_id=? " +
              "AND token_hash=? AND expires_at>? AND consumed_at IS NULL FOR UPDATE",
          input.enrollmentId(), hostId, input.agentId(), tokenDigest, java.sql.Timestamp.from(now));
      if (enrollments.isEmpty())
        throw fail(409, "ENROLLMENT_UNAVAILABLE", "接入码无效、已使用或已过期");
      var pending = query(c,
          "SELECT id FROM operation WHERE host_id=? AND kind=? " +
              "AND status IN ('PENDING','RUNNING','UNKNOWN') LIMIT 1",
          hostId, SSH_AGENT_INSTALL_KIND);
      if (!pending.isEmpty())
        throw fail(409, "OPERATION_IN_PROGRESS", "该主机已有 Agent SSH 初始化等待完成或对账");
      update(c,
          "INSERT INTO operation (id,idempotency_key,phase,status,error,created_at,updated_at," +
              "kind,host_id,agent_id,requested_by,reason,started_at,deadline_at,request_hash,target,capability) " +
              "VALUES (?,?,?,'RUNNING',NULL,?,?,?,?,?,?,?,?,?,?,?,?)",
          operationId, key, "APPLY", java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
          SSH_AGENT_INSTALL_KIND, hostId, input.agentId(), actor, reason, java.sql.Timestamp.from(now),
          java.sql.Timestamp.from(now.plusSeconds(360)), requestHash, input.agentId().toString(), "agent.install");
      audit(c, actor, SSH_AGENT_INSTALL_KIND, "host", hostId.toString(), "ACCEPTED",
          remoteAddress,
          "接受通过 SSH 初始化 Agent；原因：" + reason);
      return new OperationDecision(Map.of("id", operationId.toString(), "kind", SSH_AGENT_INSTALL_KIND,
          "hostId", hostId.toString(), "agentId", input.agentId().toString(),
          "capability", "agent.install", "idempotencyKey", key, "target", input.agentId().toString(),
          "reason", reason, "status", "RUNNING", "phase", "APPLY"), 202, false, operationId);
    });

    if (!decision.replayed()) {
      String normalizedOrigin = origin.toString().replaceAll("/$", "");
      byte[] installerBytes = installer;
      try {
        managedExecutor.execute(() -> {
          if (!SSH_BOOTSTRAP_SLOTS.tryAcquire()) {
            finishAgentInstall(operationId, actor, hostId, input.agentId(), reason,
                "FAILED", "APPLY", "SSH_WORKER_CAPACITY", "SSH 初始化并发容量已满", "NOT_EXECUTED",
                remoteAddress);
            return;
          }
          try {
            runAgentInstall(operationId, actor, hostId, input.agentId(), reason,
                directConfig, token, normalizedOrigin, installerBytes, relayEndpoints,
                relayFallback, remoteAddress);
          } finally {
            SSH_BOOTSTRAP_SLOTS.release();
          }
        });
      } catch (RejectedExecutionException e) {
        finishAgentInstall(operationId, actor, hostId, input.agentId(), reason,
            "FAILED", "APPLY", "SSH_WORKER_UNAVAILABLE", "SSH 初始化任务无法启动", "VERIFIED_FAILED",
            remoteAddress);
      }
    }
    Map<String, Object> operation = decision.replayed() ? decision.operation() : loadOperation(decision.operationId());
    String status = String.valueOf(operation.get("status"));
    int httpStatus = Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status) ? 200 : 202;
    return Response.status(httpStatus).entity(operation)
        .header("Location", "/api/v1/operations/" + decision.operationId())
        .header("Retry-After", "2")
        .header("Idempotency-Replayed", Boolean.toString(decision.replayed())).build();
  }

  static URI trustedHttpsOrigin(String value) {
    try {
      URI origin = URI.create(value);
      if (!"https".equalsIgnoreCase(origin.getScheme()) || origin.getHost() == null ||
          origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null ||
          !(origin.getPath().isEmpty() || origin.getPath().equals("/")))
        throw new IllegalArgumentException();
      return new URI("https", null, origin.getHost(), origin.getPort(), null, null, null);
    } catch (Exception e) {
      throw fail(503, "SSH_INSTALLER_UNSAFE", "Agent 安装仅允许配置为固定 HTTPS 控制器 Origin");
    }
  }

  private void runAgentInstall(UUID operationId, String actor, UUID hostId, UUID agentId,
      String reason, HostSshCredentialService.HostConfig directConfig, String token, String origin,
      byte[] installer, String relayEndpoints, RelayFallbackSigner.Manifest relayFallback,
      String remoteAddress) {
    ProcessResult result;
    try {
      HostSshCredentialService.HostConfig current = store.transaction(c ->
          sshCredentials.find(c, hostId));
      if (current == null || !Objects.equals(current.address(), directConfig.address()) ||
          current.port() != directConfig.port() ||
          !Objects.equals(current.hostKeyFingerprint(), directConfig.hostKeyFingerprint()) ||
          !MessageDigest.isEqual(current.encryptedCredential(), directConfig.encryptedCredential()))
        throw new IllegalStateException("Stored SSH connection changed before execution");
      result = invokeInstallerDirect(current, installer, origin, hostId, agentId, token,
          certificateAuthority.controllerFingerprint(), gatewayPort, relayEndpoints, relayFallback);
    } catch (Exception e) {
      result = new ProcessResult(127, false);
    }

    boolean consumed = enrollmentConsumed(agentId);
    Instant heartbeat = result.exitCode() == 0 || consumed
        ? awaitAgentHeartbeat(agentId, hostId, 90) : null;
    String status;
    String phase;
    String code;
    String message;
    String outcome;
    if (heartbeat != null) {
      status = "SUCCEEDED"; phase = "VERIFY"; code = ""; message = ""; outcome = "APPLIED";
    } else if (result.timedOut() || consumed) {
      status = "UNKNOWN"; phase = "APPLY"; code = "EXECUTION_OUTCOME_UNKNOWN";
      message = "SSH initialization may have consumed the enrollment token; reconcile the Agent before retrying";
      outcome = "UNCERTAIN";
    } else {
      status = "FAILED"; phase = "APPLY"; code = "SSH_AGENT_INSTALL_FAILED";
      message = "SSH installer failed before consuming the enrollment token";
      outcome = "VERIFIED_FAILED";
    }
    finishAgentInstall(operationId, actor, hostId, agentId, reason,
        status, phase, code, message, outcome, remoteAddress);
  }

  private byte[] readTrustedInstaller(Path installer) {
    requireTrustedFile(installer, "Agent 固定安装脚本");
    try {
      var before = Files.readAttributes(installer,
          java.nio.file.attribute.PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (before.size() > MAX_INSTALLER_BYTES)
        throw fail(503, "SSH_INSTALLER_UNSAFE", "Agent 固定安装脚本超过大小限制");
      var output = new ByteArrayOutputStream((int) before.size());
      var options = Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
      try (SeekableByteChannel channel = Files.newByteChannel(installer, options)) {
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        int total = 0;
        while (true) {
          int read = channel.read(buffer);
          if (read < 0) break;
          total += read;
          if (total > MAX_INSTALLER_BYTES)
            throw fail(503, "SSH_INSTALLER_UNSAFE", "Agent 固定安装脚本超过大小限制");
          buffer.flip();
          output.write(buffer.array(), 0, buffer.remaining());
          buffer.clear();
        }
      }
      var after = Files.readAttributes(installer,
          java.nio.file.attribute.PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (Files.isSymbolicLink(installer) || !"root".equals(after.owner().getName()) ||
          !Objects.equals(before.fileKey(), after.fileKey()) || after.size() != output.size())
        throw fail(503, "SSH_INSTALLER_UNSAFE", "Agent 固定安装脚本在读取期间发生变化");
      return output.toByteArray();
    } catch (java.io.IOException | UnsupportedOperationException e) {
      throw fail(503, "SSH_CONFIGURATION_UNAVAILABLE", "Agent 固定安装脚本不可用");
    }
  }

  private ProcessResult invokeInstallerDirect(HostSshCredentialService.HostConfig config,
      byte[] installer, String origin, UUID hostId, UUID agentId, String token,
      String fingerprint, int port, String relayEndpoints,
      RelayFallbackSigner.Manifest relayFallback) throws Exception {
    byte[] payload = installerPayload(installer, origin, hostId, agentId, token, fingerprint,
        port, relayEndpoints, relayFallback);
    try (var connection = sshCredentials.connect(config);
         var channel = connection.session().createExecChannel("sudo -n /usr/bin/bash -s")) {
      channel.setIn(new java.io.ByteArrayInputStream(payload));
      channel.setOut(OutputStream.nullOutputStream());
      channel.setErr(OutputStream.nullOutputStream());
      channel.open().verify(15_000);
      var events = channel.waitFor(java.util.EnumSet.of(
          org.apache.sshd.client.channel.ClientChannelEvent.CLOSED),
          TimeUnit.SECONDS.toMillis(240));
      if (!events.contains(org.apache.sshd.client.channel.ClientChannelEvent.CLOSED)) {
        channel.close(true);
        return new ProcessResult(124, true);
      }
      Integer exitStatus = channel.getExitStatus();
      return new ProcessResult(exitStatus == null ? 255 : exitStatus, false);
    } finally {
      java.util.Arrays.fill(payload, (byte)0);
    }
  }

  private byte[] installerPayload(byte[] installer, String origin, UUID hostId,
      UUID agentId, String token, String fingerprint, int port, String relayEndpoints,
      RelayFallbackSigner.Manifest relayFallback) throws Exception {
    var payload = new ByteArrayOutputStream(installer.length + 1024);
    String bootstrapArguments = "set -- " + shellQuote(origin) + " " + shellQuote(hostId.toString()) +
        " " + shellQuote(agentId.toString()) + " " + shellQuote(token) + " " +
        shellQuote(fingerprint) + " " + shellQuote(Integer.toString(port)) + " " +
        shellQuote(relayEndpoints) + " " + shellQuote(String.join(",", relayFallback.endpoints())) +
        " " + shellQuote(relayFallback.signature()) + " " + shellQuote(relayFallback.publicKey()) + "\n";
    payload.write(bootstrapArguments.getBytes(StandardCharsets.UTF_8));
    payload.write(installer);
    return payload.toByteArray();
  }

  static String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  private boolean enrollmentConsumed(UUID agentId) {
    return store.transaction(c -> !query(c,
        "SELECT id FROM agent_enrollment WHERE agent_id=? AND consumed_at IS NOT NULL", agentId).isEmpty());
  }

  private Instant awaitAgentHeartbeat(UUID agentId, UUID hostId, int seconds) {
    Instant deadline = Instant.now().plusSeconds(seconds);
    while (Instant.now().isBefore(deadline)) {
      Instant seen = store.transaction(c -> {
        var rows = query(c, "SELECT last_seen FROM agent WHERE id=? AND host_id=? AND cert_fingerprint IS NOT NULL",
            agentId, hostId);
        if (rows.isEmpty() || rows.getFirst().get("last_seen") == null) return null;
        Object value = rows.getFirst().get("last_seen");
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime timestamp) return timestamp.toInstant();
        return null;
      });
      if (seen != null) return seen;
      try {
        Thread.sleep(3_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
    return null;
  }

  private void finishAgentInstall(UUID operationId, String actor, UUID hostId, UUID agentId,
      String reason, String status, String phase, String code, String message, String outcome,
      String remoteAddress) {
    String resultJson = null;
    String errorJson = null;
    try {
      if (status.equals("SUCCEEDED"))
        resultJson = mapper.writeValueAsString(Map.of("agentId", agentId.toString(),
            "status", "ONLINE", "verifiedAt", Instant.now().toString()));
      else
        errorJson = errorJson(code, message, outcome);
    } catch (Exception e) {
      status = "UNKNOWN"; phase = "APPLY";
      errorJson = errorJson("RESULT_ENCODING_FAILED", "SSH result could not be encoded", "UNCERTAIN");
    }
    String finalStatus = status;
    String finalPhase = phase;
    String finalResult = resultJson;
    String finalError = errorJson;
    Instant finishedAt = status.equals("UNKNOWN") ? null : Instant.now();
    store.transaction(c -> {
      update(c, "UPDATE operation SET status=?,phase=?,result_json=?,error_json=?,updated_at=?,finished_at=? " +
              "WHERE id=? AND status='RUNNING'",
          finalStatus, finalPhase, finalResult, finalError, java.sql.Timestamp.from(Instant.now()),
          finishedAt == null ? null : java.sql.Timestamp.from(finishedAt), operationId);
      audit(c, actor, SSH_AGENT_INSTALL_KIND, "host", hostId.toString(), finalStatus,
          remoteAddress,
          "SSH Agent 初始化 " + agentId + "：" + finalStatus + "；原因：" + reason);
      return null;
    });
  }

  private String operationSelect() {
    return "SELECT o.*,u.username AS requested_by_name FROM operation o " +
        "LEFT JOIN panel_user u ON u.id=o.requested_by";
  }

  private Map<String, Object> loadOperation(UUID operationId) {
    return store.transaction(c -> {
      var rows = query(c, operationSelect() + " WHERE o.id=?", operationId);
      if (rows.isEmpty()) throw fail(404, "NOT_FOUND", "SSH 操作不存在");
      return operationView(rows.getFirst());
    });
  }

  private Map<String, Object> operationView(Map<String, Object> row) {
    var value = new LinkedHashMap<String, Object>();
    value.put("id", String.valueOf(row.get("id")));
    value.put("kind", row.get("kind"));
    value.put("hostId", row.get("host_id") == null ? null : String.valueOf(row.get("host_id")));
    value.put("agentId", row.get("agent_id") == null ? null : String.valueOf(row.get("agent_id")));
    value.put("serviceId", row.get("service_id") == null ? null : String.valueOf(row.get("service_id")));
    value.put("capability", row.get("capability"));
    value.put("target", row.get("target"));
    value.put("idempotencyKey", row.get("idempotency_key"));
    value.put("requestedBy", row.get("requested_by_name"));
    value.put("reason", row.get("reason"));
    value.put("expectedGeneration", row.get("expected_generation"));
    value.put("phase", row.get("phase"));
    value.put("status", row.get("status"));
    value.put("createdAt", stamp(row.get("created_at")));
    value.put("startedAt", stamp(row.get("started_at")));
    value.put("updatedAt", stamp(row.get("updated_at")));
    value.put("deadlineAt", stamp(row.get("deadline_at")));
    value.put("finishedAt", stamp(row.get("finished_at")));
    value.put("result", storedJson(row.get("result_json")));
    value.put("error", storedJson(row.get("error_json")));
    return value;
  }

  private Object storedJson(Object value) {
    if (value == null) return null;
    try {
      return mapper.readValue(String.valueOf(value), Object.class);
    } catch (Exception e) {
      throw new IllegalStateException("Stored SSH operation JSON is invalid", e);
    }
  }

  private String errorJson(String code, String message, String executionOutcome) {
    try {
      return mapper.writeValueAsString(Map.of("code", code, "message", message,
          "retryable", false, "executionOutcome", executionOutcome));
    } catch (Exception e) {
      throw new IllegalStateException("Unable to encode SSH operation failure", e);
    }
  }

  private String successJson(String unit) {
    try {
      return mapper.writeValueAsString(Map.of("unitName", unit, "active", true,
          "verifiedAt", Instant.now().toString()));
    } catch (Exception e) {
      throw new IllegalStateException("Unable to encode SSH operation result", e);
    }
  }

  private String stamp(Object value) {
    if (value == null) return null;
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().toString();
    if (value instanceof java.time.OffsetDateTime timestamp) return timestamp.toInstant().toString();
    if (value instanceof Instant timestamp) return timestamp.toString();
    if (value instanceof java.util.Date timestamp) return timestamp.toInstant().toString();
    throw new IllegalStateException("Unsupported SSH operation timestamp value");
  }

  private String validateIdempotencyKey(String key) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}"))
      throw fail(400, "VALIDATION_FAILED", "Idempotency-Key 格式无效");
    return key;
  }

  private void requireTrustedFile(Path path, String label) {
    try {
      if (!path.isAbsolute() || Files.isSymbolicLink(path) ||
          !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        throw fail(503, "SSH_CONFIGURATION_UNAVAILABLE", label + "不可用");
      var attributes = Files.readAttributes(path, java.nio.file.attribute.PosixFileAttributes.class,
          LinkOption.NOFOLLOW_LINKS);
      Set<PosixFilePermission> permissions = attributes.permissions();
      if (!attributes.owner().getName().equals("root") ||
          permissions.contains(PosixFilePermission.GROUP_WRITE) ||
          permissions.contains(PosixFilePermission.OTHERS_WRITE))
        throw fail(503, "SSH_CONFIGURATION_UNSAFE", label + "必须由 root 管理且不可被其他用户写入");
    } catch (java.io.IOException | UnsupportedOperationException e) {
      throw fail(503, "SSH_CONFIGURATION_UNAVAILABLE", label + "不可用");
    }
  }

  private void recordAudit(String actor, UUID hostId, String action, String outcome, String summary) {
    store.transaction(c -> {
      if (query(c, "SELECT id FROM host WHERE id=?", hostId).isEmpty())
        throw fail(404, "NOT_FOUND", "主机不存在");
      audit(c, actor, action, "host", hostId.toString(), outcome,
          http.remoteAddress() == null ? null : http.remoteAddress().host(), summary);
      return null;
    });
  }

  private String actorId() {
    @SuppressWarnings("unchecked")
    var session = (Map<String, Object>) request.getProperty("session");
    if (session == null || session.get("user_id") == null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    return String.valueOf(session.get("user_id"));
  }

  private UUID uuid(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException e) {
      throw fail(400, "VALIDATION_FAILED", "hostId 必须为 UUID");
    }
  }
}
