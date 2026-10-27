package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthResource.number;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import io.quarkus.scheduler.Scheduled;

/** Starts bounded captures on supported wires and stores metadata-bearing PCAPNG artifacts. */
@ApplicationScoped
public class KubernetesApplicationPacketCaptureService {
  private static final int MAX_PCAP_BYTES = 32 * 1024;
  private static final int MAX_PCAPNG_BYTES = 64 * 1024;
  private static final long RETENTION_MILLIS = 24 * 60 * 60 * 1000L;
  private static final Pattern INTERFACE = Pattern.compile("[A-Za-z0-9_.-]{1,15}");

  @Inject AuthStore store;
  @Inject ObjectMapper mapper;
  @Inject HostHelperOperationResource helperOperations;

  public record CaptureRequest(String linkId) {}
  public record CaptureView(String captureId, String applicationId, String revisionId,
      long generation, String linkId, String capturePoint, String state, Integer packetCount,
      Integer capturedBytes, boolean truncated, String detail, Instant startedAt,
      Instant completedAt, Instant expiresAt) {}
  public record CaptureList(boolean supported, List<CaptureView> items) {}
  public record CaptureArtifact(byte[] bytes, CaptureView metadata) {}
  public record CaptureResult(String captureId, boolean complete) {}
  private record Converted(byte[] pcapng, int packetCount, int capturedBytes,
      boolean packetTruncated) {}

  public CaptureView start(String actor, String remoteAddress, UUID applicationId,
      String linkValue) {
    UUID linkId = uuid(linkValue, "链路 ID");
    Map<String, Object> runtime = store.transaction(connection -> query(connection,
        "SELECT a.state AS application_state,r.revision_id,r.generation,r.host_id,r.state AS runtime_state,"
            + "r.runtime_snapshot,r.cluster_node_uid FROM kubernetes_application a "
            + "JOIN kubernetes_application_network_runtime r ON r.application_id=a.id WHERE a.id=?",
        applicationId).stream().findFirst().orElse(null));
    if (runtime == null || !"APPLIED".equals(runtime.get("runtime_state"))
        || !"DEPLOYED".equals(runtime.get("application_state"))) {
      throw fail(409, "APPLICATION_PACKET_CAPTURE_RUNTIME_REQUIRED",
          "抓包要求应用处于已部署状态且虚拟网络 Runtime 为 APPLIED");
    }
    UUID revisionId = uuid(runtime.get("revision_id"), "拓扑 Revision");
    long generation = number(runtime, "generation");
    Map<String, Object> head = store.transaction(connection -> query(connection,
        "SELECT revision_id,generation FROM kubernetes_application_network_topology_head "
            + "WHERE application_id=?", applicationId).stream().findFirst().orElse(null));
    if (head == null || !revisionId.equals(uuid(head.get("revision_id"), "拓扑 Revision"))
        || generation != number(head, "generation")) {
      throw fail(409, "APPLICATION_PACKET_CAPTURE_REVISION_STALE",
          "当前已 Apply 的网络 Revision 与画布拓扑不一致，请先重新 Apply");
    }

    JsonNode helperPayload = helperPayload(runtime);
    JsonNode selected = null;
    for (JsonNode link : helperPayload.path("links")) {
      if (linkId.toString().equalsIgnoreCase(link.path("id").asText())) selected = link;
    }
    if (selected == null || !"L2".equals(selected.path("layer").asText())
        || !"ETHERNET".equals(selected.path("protocol").asText())) {
      throw fail(422, "APPLICATION_PACKET_CAPTURE_LINK_UNSUPPORTED",
          "只能抓取当前 Provider 实际执行的 L2 Ethernet 虚拟网线；业务调用边暂不提供抓包点");
    }

    UUID captureId = UUID.randomUUID();
    String idempotencyKey = "app-packet-capture-" + captureId;
    long startedAt = System.currentTimeMillis();
    long expiresAt = startedAt + RETENTION_MILLIS;
    store.transaction(connection -> {
      if (!query(connection, "SELECT capture_id FROM kubernetes_application_packet_capture "
          + "WHERE application_id=? AND state='CAPTURING' FOR UPDATE", applicationId).isEmpty()) {
        throw fail(409, "APPLICATION_PACKET_CAPTURE_BUSY", "此应用已有正在执行的链路抓包");
      }
      update(connection, "INSERT INTO kubernetes_application_packet_capture "
          + "(capture_id,application_id,revision_id,generation,link_id,idempotency_key,state,detail,"
          + "created_by,started_at,expires_at) VALUES (?,?,?,?,?,?,'CAPTURING',?,?,?,?)",
          captureId, applicationId, revisionId, generation, linkId, idempotencyKey,
          "Helper 正在执行最多 5 秒或 128 个数据包的短时抓取", actor, startedAt, expiresAt);
      audit(connection, actor, "KUBERNETES_APPLICATION_PACKET_CAPTURE_STARTED",
          "kubernetes_application", applicationId.toString(), "ACCEPTED", remoteAddress,
          "对虚拟网络链路 " + linkId + " 发起有时长、包数和文件大小上限的临时抓包");
      return null;
    });

    JsonNode requestPayload = helperPayload.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode)requestPayload)
        .put("action", "CAPTURE").put("captureLinkId", linkId.toString());
    UUID hostId = uuid(runtime.get("host_id"), "Agent 主机 ID");
    long hostGeneration = hostGeneration(hostId);
    try {
      var response = helperOperations.submitAs(hostId, idempotencyKey,
          new HostHelperOperationResource.HelperOperationRequest("network.topology.capture",
              applicationId.toString(), requestPayload,
              "抓取应用虚拟网络链路 " + linkId, 30, hostGeneration), actor, remoteAddress);
      Object entity = response.getEntity();
      if (entity instanceof Map<?, ?> result && result.get("id") != null) {
        setOperation(captureId, uuid(result.get("id"), "Helper 操作 ID"));
      } else {
        UUID operationId = operationByKey(idempotencyKey);
        if (operationId != null) setOperation(captureId, operationId);
      }
    } catch (RuntimeException failure) {
      UUID operationId = operationByKey(idempotencyKey);
      if (operationId != null) {
        setOperation(captureId, operationId);
      } else {
        markCapture(captureId, "FAILED", "抓包任务未能提交到目标 Helper", System.currentTimeMillis());
      }
      throw failure;
    }
    return get(applicationId, captureId);
  }

  public CaptureList list(UUID applicationId, int limit) {
    if (limit < 1 || limit > 20) throw fail(400, "VALIDATION_FAILED", "抓包记录 limit 必须为 1 到 20");
    Map<String, Object> host = store.transaction(connection -> query(connection,
        "SELECT h.capabilities FROM kubernetes_application_network_runtime r "
            + "JOIN host h ON h.id=r.host_id WHERE r.application_id=?", applicationId)
        .stream().findFirst().orElse(null));
    List<Map<String, Object>> rows = store.transaction(connection -> query(connection,
        "SELECT * FROM kubernetes_application_packet_capture WHERE application_id=? "
            + "ORDER BY started_at DESC LIMIT ?", applicationId, limit));
    boolean supported = false;
    if (host != null) {
      try {
        JsonNode capabilities = mapper.readTree(String.valueOf(host.get("capabilities")));
        supported = capabilities.isArray() && java.util.stream.StreamSupport
            .stream(capabilities.spliterator(), false)
            .anyMatch(item -> "network.topology.capture".equals(item.asText()));
      } catch (IOException ignored) {
        supported = false;
      }
    }
    return new CaptureList(supported, rows.stream().map(this::view).toList());
  }

  public CaptureView get(UUID applicationId, UUID captureId) {
    Map<String, Object> row = store.transaction(connection -> query(connection,
        "SELECT * FROM kubernetes_application_packet_capture WHERE application_id=? AND capture_id=?",
        applicationId, captureId).stream().findFirst().orElse(null));
    if (row == null) throw fail(404, "APPLICATION_PACKET_CAPTURE_NOT_FOUND", "抓包记录不存在");
    return view(row);
  }

  public CaptureArtifact download(UUID applicationId, UUID captureId) {
    Map<String, Object> row = store.transaction(connection -> query(connection,
        "SELECT * FROM kubernetes_application_packet_capture WHERE application_id=? AND capture_id=?",
        applicationId, captureId).stream().findFirst().orElse(null));
    if (row == null) throw fail(404, "APPLICATION_PACKET_CAPTURE_NOT_FOUND", "抓包记录不存在");
    if (!"COMPLETE".equals(row.get("state")) || !(row.get("pcapng") instanceof byte[] artifact)
        || number(row, "expires_at") <= System.currentTimeMillis()) {
      throw fail(409, "APPLICATION_PACKET_CAPTURE_ARTIFACT_UNAVAILABLE",
          "抓包尚未完成、已过期或没有可下载的 PCAPNG 文件");
    }
    return new CaptureArtifact(artifact, view(row));
  }

  /** Stores and sanitizes the bounded Helper result inside the Agent callback transaction. */
  public CaptureResult recordAgentResult(java.sql.Connection connection, String idempotencyKey,
      String status, String detail, UUID operationId) throws Exception {
    Map<String, Object> row = query(connection,
        "SELECT capture_id,application_id,revision_id,generation,link_id,started_at "
            + "FROM kubernetes_application_packet_capture WHERE idempotency_key=? FOR UPDATE",
        idempotencyKey).stream().findFirst().orElse(null);
    if (row == null) return null;
    UUID captureId = uuid(row.get("capture_id"), "抓包 ID");
    long completedAt = System.currentTimeMillis();
    if (!"SUCCEEDED".equals(status)) {
      String safe = detail.length() > 240 ? detail.substring(0, 240) : detail;
      update(connection, "UPDATE kubernetes_application_packet_capture SET state='FAILED',detail=?,"
          + "operation_id=?,completed_at=? WHERE capture_id=? AND state='CAPTURING'",
          "Helper 抓包失败：" + safe, operationId, completedAt, captureId);
      return new CaptureResult(captureId.toString(), false);
    }
    try {
      JsonNode payload = mapper.readTree(detail);
      String capturePoint = payload.path("capturePoint").asText();
      if (!"PCAP_V1".equals(payload.path("format").asText())
          || !INTERFACE.matcher(capturePoint.replaceFirst("^source-side interface ", "")).matches()
          || !capturePoint.startsWith("source-side interface ")
          || !"BOTH".equals(payload.path("direction").asText())
          || payload.path("packetLimit").asInt(-1) != 128
          || payload.path("snapLength").asInt(-1) != 128
          || !payload.path("durationLimitReached").isBoolean()
          || !payload.path("pcapBase64").isTextual()
          || payload.path("pcapBase64").asText().length() > 44_000) {
        throw new IllegalArgumentException("Helper capture output metadata is invalid");
      }
      byte[] pcap = Base64.getDecoder().decode(payload.path("pcapBase64").asText());
      if (pcap.length > MAX_PCAP_BYTES) throw new IllegalArgumentException("capture exceeded its byte limit");
      Converted converted = convert(pcap, capturePoint, row, completedAt);
      boolean truncated = converted.packetTruncated()
          || payload.path("durationLimitReached").asBoolean()
          || converted.packetCount() >= payload.path("packetLimit").asInt();
      byte[] artifact = converted.pcapng();
      if (artifact.length > MAX_PCAPNG_BYTES) {
        throw new IllegalArgumentException("PCAPNG artifact exceeded its byte limit");
      }
      String summary = converted.packetCount() == 0
          ? "抓包完成：所选链路上没有观察到数据包"
          : "抓包完成：" + converted.packetCount() + " 帧，数据 "
              + converted.capturedBytes() + " 字节";
      if (truncated) summary += "；达到时长、包数或逐包截断边界";
      update(connection, "UPDATE kubernetes_application_packet_capture SET state='COMPLETE',"
          + "capture_point=?,operation_id=?,pcapng=?,packet_count=?,captured_bytes=?,truncated=?,"
          + "detail=?,completed_at=? WHERE capture_id=? AND state='CAPTURING'",
          capturePoint, operationId, artifact, converted.packetCount(), converted.capturedBytes(),
          truncated, summary, completedAt, captureId);
      return new CaptureResult(captureId.toString(), true);
    } catch (RuntimeException | IOException invalid) {
      update(connection, "UPDATE kubernetes_application_packet_capture SET state='FAILED',"
          + "operation_id=?,detail=?,completed_at=? WHERE capture_id=? AND state='CAPTURING'",
          operationId, "Helper 返回的 PCAP 数据未通过格式和大小校验", completedAt, captureId);
      return new CaptureResult(captureId.toString(), false);
    }
  }

  @Scheduled(every = "1h", concurrentExecution = io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP)
  void expireArtifacts() {
    long now = System.currentTimeMillis();
    store.transaction(connection -> {
      update(connection, "UPDATE kubernetes_application_packet_capture SET state='EXPIRED',"
          + "pcapng=NULL,detail='PCAPNG 已超过 24 小时保留期',completed_at=COALESCE(completed_at,?) "
          + "WHERE expires_at<=? AND pcapng IS NOT NULL", now, now);
      update(connection, "UPDATE kubernetes_application_packet_capture SET state='FAILED',"
          + "detail='抓包任务超过保留时间仍未返回结果',completed_at=? "
          + "WHERE expires_at<=? AND state='CAPTURING'", now, now);
      return null;
    });
  }

  private Converted convert(byte[] pcap, String capturePoint, Map<String, Object> metadata,
      long completedAt)
      throws IOException {
    if (pcap.length < 24) throw new IllegalArgumentException("PCAP header is truncated");
    ByteOrder order;
    boolean nanoSeconds;
    byte[] magic = java.util.Arrays.copyOfRange(pcap, 0, 4);
    if (java.util.Arrays.equals(magic, new byte[] {(byte)0xd4, (byte)0xc3, (byte)0xb2, (byte)0xa1})) {
      order = ByteOrder.LITTLE_ENDIAN;
      nanoSeconds = false;
    } else if (java.util.Arrays.equals(magic, new byte[] {(byte)0xa1, (byte)0xb2, (byte)0xc3, (byte)0xd4})) {
      order = ByteOrder.BIG_ENDIAN;
      nanoSeconds = false;
    } else if (java.util.Arrays.equals(magic, new byte[] {(byte)0x4d, (byte)0x3c, (byte)0xb2, (byte)0xa1})) {
      order = ByteOrder.LITTLE_ENDIAN;
      nanoSeconds = true;
    } else if (java.util.Arrays.equals(magic, new byte[] {(byte)0xa1, (byte)0xb2, (byte)0x3c, (byte)0x4d})) {
      order = ByteOrder.BIG_ENDIAN;
      nanoSeconds = true;
    } else {
      throw new IllegalArgumentException("PCAP byte order marker is invalid");
    }
    ByteBuffer input = ByteBuffer.wrap(pcap).order(order);
    if (Short.toUnsignedInt(input.getShort(4)) != 2 || Short.toUnsignedInt(input.getShort(6)) != 4) {
      throw new IllegalArgumentException("PCAP version is unsupported");
    }
    long sourceSnapLength = Integer.toUnsignedLong(input.getInt(16));
    int linkType = (int)(Integer.toUnsignedLong(input.getInt(20)) & 0xffff);
    if (sourceSnapLength < 1 || sourceSnapLength > MAX_PCAP_BYTES || linkType != 1) {
      throw new IllegalArgumentException("PCAP interface metadata is invalid");
    }
    long resolution = nanoSeconds ? 1_000_000_000L : 1_000_000L;
    ByteArrayOutputStream pcapng = new ByteArrayOutputStream();
    byte[] comment = ("application=" + metadata.get("application_id")
        + ";revision=" + metadata.get("revision_id")
        + ";generation=" + metadata.get("generation")
        + ";link=" + metadata.get("link_id")
        + ";capturePoint=" + capturePoint
        + ";direction=BOTH;filter=none"
        + ";startedAt=" + Instant.ofEpochMilli(number(metadata, "started_at"))
        + ";completedAt=" + Instant.ofEpochMilli(completedAt))
        .getBytes(StandardCharsets.UTF_8);
    writeSectionHeader(pcapng, comment);
    writeInterface(pcapng, linkType, (int)Math.min(sourceSnapLength, 128), capturePoint, resolution);

    int offset = 24;
    int packetCount = 0;
    int capturedBytes = 0;
    boolean packetTruncated = false;
    while (offset < pcap.length) {
      if (pcap.length - offset < 16) throw new IllegalArgumentException("PCAP packet header is truncated");
      long seconds = Integer.toUnsignedLong(input.getInt(offset));
      long fraction = Integer.toUnsignedLong(input.getInt(offset + 4));
      long capturedLong = Integer.toUnsignedLong(input.getInt(offset + 8));
      long originalLong = Integer.toUnsignedLong(input.getInt(offset + 12));
      if (capturedLong > sourceSnapLength || capturedLong > pcap.length - offset - 16
          || originalLong < capturedLong || fraction >= resolution) {
        throw new IllegalArgumentException("PCAP packet lengths or timestamp are invalid");
      }
      int captured = (int)capturedLong;
      long timestamp = seconds * resolution + fraction;
      writeEnhancedPacket(pcapng, timestamp, originalLong, pcap, offset + 16, captured);
      packetCount++;
      capturedBytes += captured;
      packetTruncated |= originalLong > capturedLong;
      offset += 16 + captured;
    }
    if (pcapng.size() > MAX_PCAPNG_BYTES) {
      throw new IllegalArgumentException("PCAPNG conversion exceeded its byte limit");
    }
    return new Converted(pcapng.toByteArray(), packetCount, capturedBytes, packetTruncated);
  }

  private void writeSectionHeader(ByteArrayOutputStream output, byte[] comment) {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    writeInt(body, 0x1a2b3c4d);
    writeShort(body, 1);
    writeShort(body, 0);
    writeLong(body, -1L);
    writeOption(body, 1, comment);
    writeOption(body, 0, new byte[0]);
    writeBlock(output, 0x0a0d0d0a, body.toByteArray());
  }

  private void writeInterface(ByteArrayOutputStream output, int linkType, int snapLength,
      String capturePoint, long resolution) {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    writeShort(body, linkType);
    writeShort(body, 0);
    writeInt(body, snapLength);
    writeOption(body, 2, capturePoint.getBytes(StandardCharsets.UTF_8));
    writeOption(body, 3, "Source side of the selected NaNPanel virtual wire".getBytes(StandardCharsets.UTF_8));
    writeOption(body, 9, new byte[] {(byte)(resolution == 1_000_000_000L ? 9 : 6)});
    writeOption(body, 0, new byte[0]);
    writeBlock(output, 1, body.toByteArray());
  }

  private void writeEnhancedPacket(ByteArrayOutputStream output, long timestamp, long originalLength,
      byte[] pcap, int packetOffset, int capturedLength) {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    writeInt(body, 0);
    writeInt(body, (int)(timestamp >>> 32));
    writeInt(body, (int)timestamp);
    writeInt(body, capturedLength);
    writeInt(body, (int)originalLength);
    body.write(pcap, packetOffset, capturedLength);
    pad(body, capturedLength);
    writeBlock(output, 6, body.toByteArray());
  }

  private void writeBlock(ByteArrayOutputStream output, int type, byte[] body) {
    int length = 12 + body.length;
    writeInt(output, type);
    writeInt(output, length);
    output.writeBytes(body);
    writeInt(output, length);
  }

  private void writeOption(ByteArrayOutputStream output, int code, byte[] value) {
    writeShort(output, code);
    writeShort(output, value.length);
    output.writeBytes(value);
    pad(output, value.length);
  }

  private void pad(ByteArrayOutputStream output, int length) {
    for (int index = length; index % 4 != 0; index++) output.write(0);
  }

  private void writeShort(ByteArrayOutputStream output, int value) {
    output.write(value & 0xff);
    output.write((value >>> 8) & 0xff);
  }

  private void writeInt(ByteArrayOutputStream output, int value) {
    output.write(value & 0xff);
    output.write((value >>> 8) & 0xff);
    output.write((value >>> 16) & 0xff);
    output.write((value >>> 24) & 0xff);
  }

  private void writeLong(ByteArrayOutputStream output, long value) {
    writeInt(output, (int)value);
    writeInt(output, (int)(value >>> 32));
  }

  private JsonNode helperPayload(Map<String, Object> runtime) {
    try {
      JsonNode snapshot = mapper.readTree(String.valueOf(runtime.get("runtime_snapshot")));
      JsonNode helperPayload = snapshot.path("helperPayload");
      if (!helperPayload.isObject()) throw new IllegalArgumentException();
      return helperPayload;
    } catch (IOException | IllegalArgumentException invalid) {
      throw fail(500, "APPLICATION_NETWORK_RUNTIME_CORRUPT", "已 Apply 的 Helper 拓扑快照无法读取");
    }
  }

  private long hostGeneration(UUID hostId) {
    Map<String, Object> host = store.transaction(connection -> query(connection,
        "SELECT generation FROM host WHERE id=?", hostId).stream().findFirst().orElse(null));
    if (host == null) throw fail(409, "APPLICATION_PACKET_CAPTURE_HOST_MISSING", "目标 Helper 主机映射已不存在");
    return number(host, "generation");
  }

  private UUID operationByKey(String key) {
    Map<String, Object> operation = store.transaction(connection -> query(connection,
        "SELECT id FROM operation WHERE idempotency_key=?", key).stream().findFirst().orElse(null));
    return operation == null ? null : uuid(operation.get("id"), "Helper 操作 ID");
  }

  private void setOperation(UUID captureId, UUID operationId) {
    store.transaction(connection -> {
      update(connection, "UPDATE kubernetes_application_packet_capture SET operation_id=? "
          + "WHERE capture_id=?", operationId, captureId);
      return null;
    });
  }

  private void markCapture(UUID captureId, String state, String detail, long completedAt) {
    store.transaction(connection -> {
      update(connection, "UPDATE kubernetes_application_packet_capture SET state=?,detail=?,completed_at=? "
          + "WHERE capture_id=? AND state='CAPTURING'", state, detail, completedAt, captureId);
      return null;
    });
  }

  private CaptureView view(Map<String, Object> row) {
    return new CaptureView(String.valueOf(row.get("capture_id")),
        String.valueOf(row.get("application_id")), String.valueOf(row.get("revision_id")),
        number(row, "generation"), String.valueOf(row.get("link_id")),
        row.get("capture_point") == null ? null : String.valueOf(row.get("capture_point")),
        String.valueOf(row.get("state")), integer(row.get("packet_count")),
        integer(row.get("captured_bytes")), Boolean.TRUE.equals(row.get("truncated")),
        row.get("detail") == null ? null : String.valueOf(row.get("detail")),
        Instant.ofEpochMilli(number(row, "started_at")), timestamp(row.get("completed_at")),
        Instant.ofEpochMilli(number(row, "expires_at")));
  }

  private Integer integer(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }

  private Instant timestamp(Object value) {
    if (value == null) return null;
    return Instant.ofEpochMilli(value instanceof Number number ? number.longValue()
        : ((java.sql.Timestamp)value).getTime());
  }

  private UUID uuid(Object value, String field) {
    try {
      UUID parsed = UUID.fromString(String.valueOf(value));
      if (!parsed.toString().equalsIgnoreCase(String.valueOf(value))) throw new IllegalArgumentException();
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw fail(422, "VALIDATION_FAILED", field + " 必须是规范 UUID");
    }
  }
}
