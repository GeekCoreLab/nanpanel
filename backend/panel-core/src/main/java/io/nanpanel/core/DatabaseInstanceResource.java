package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 数据库实例：在受管主机上跑一个容器化的数据库，并记住它的端口、数据卷与库名。
 *
 * <p>路径是 <code>/api/v1/database-instances</code>：<code>/api/v1/databases</code> 已经是「从已部署容器里
 * 识别出来的只读数据库视图」，两者不合并——一个是本面板托管并负责的实例，一个是别人的容器。
 *
 * <p>**口令不入库**。创建时由面板生成、随 202 响应返回**一次**（随后无从取回），因此面板数据库、
 * 审计与日志里都只有「生成了口令」这一事实。容器只发布到 <code>127.0.0.1</code>（Agent 侧固定），
 * 不对公网开放；数据卷是命名卷，删除实例不会删除数据。
 */
@Path("/api/v1/database-instances")
@Produces(MediaType.APPLICATION_JSON)
public class DatabaseInstanceResource {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String ALPHABET =
      "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private static final String DATABASE_NAME = "nanpanel";

  /** Pinned images only: an unpinned tag would silently change the server under a running instance. */
  public record Engine(String id, String image, String version, int containerPort,
      String dataPath, String user) {}

  private static final Map<String, Engine> ENGINES = Map.of(
      "mysql", new Engine("mysql", "mysql:8.4", "8.4", 3306, "/var/lib/mysql", "root"),
      "mariadb", new Engine("mariadb", "mariadb:11.4", "11.4", 3306, "/var/lib/mysql", "root"),
      "postgres", new Engine("postgres", "postgres:16", "16", 5432,
          "/var/lib/postgresql/data", "postgres"));

  public record CreateRequest(String hostId, String engine, Integer port, Integer memoryMb) {}

  @Inject AuthStore store;
  @Inject HostHelperOperationResource helperOperations;
  @Context ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  @GET
  @Path("engines")
  public Map<String, Object> engines() {
    session();
    List<Map<String, Object>> items = new ArrayList<>();
    for (Engine engine : List.of(ENGINES.get("mysql"), ENGINES.get("mariadb"),
        ENGINES.get("postgres"))) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", engine.id());
      item.put("image", engine.image());
      item.put("version", engine.version());
      item.put("containerPort", engine.containerPort());
      items.add(item);
    }
    return Map.of("items", items);
  }

  @GET
  public Map<String, Object> list() {
    session();
    var rows = store.transaction(c -> query(c, """
        SELECT d.instance_id, d.host_id, d.engine, d.engine_version, d.container_name,
               d.host_port, d.data_volume, d.database_name, d.state, d.operation_id,
               d.created_at, d.updated_at, h.hostname, o.status AS operation_status
        FROM database_instance d JOIN host h ON h.id = d.host_id
        LEFT JOIN operation o ON o.id = d.operation_id
        ORDER BY d.created_at DESC LIMIT 200"""));
    List<Map<String, Object>> items = new ArrayList<>(rows.size());
    for (var row : rows) items.add(view(row));
    return Map.of("items", items);
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response create(@HeaderParam("Idempotency-Key") String idempotencyKey,
      CreateRequest input) {
    var actor = session();
    if (input == null) throw fail(422, "VALIDATION_FAILED", "数据库参数缺失");
    UUID hostId = uuid(input.hostId(), "hostId");
    String engineId = input.engine() == null ? "" : input.engine().trim().toLowerCase(Locale.ROOT);
    Engine engine = ENGINES.get(engineId);
    if (engine == null)
      throw fail(422, "VALIDATION_FAILED", "不支持的数据库类型：" + engineId);
    int port = input.port() == null ? defaultPort(engine) : input.port();
    if (port < 1024 || port > 65535)
      throw fail(422, "VALIDATION_FAILED", "发布端口必须在 1024–65535");
    int memoryMb = input.memoryMb() == null ? 512 : input.memoryMb();
    Map<String, Object> host = host(hostId);
    if (host == null) throw fail(404, "HOST_NOT_FOUND", "主机未登记");
    if (portTaken(hostId, port))
      throw fail(409, "PORT_IN_USE", "该主机上这个端口已被其它实例占用");

    UUID instanceId = UUID.randomUUID();
    String containerName = "npdb-" + instanceId.toString().substring(0, 8);
    String password = password();
    ObjectNode payload = JSON.createObjectNode();
    payload.put("image", engine.image());
    payload.put("hostPort", port);
    payload.put("containerPort", engine.containerPort());
    ArrayNode environment = payload.putArray("environment");
    environment.add(passwordVariable(engine) + "=" + password);
    environment.add(databaseVariable(engine) + "=" + DATABASE_NAME);
    ArrayNode volumes = payload.putArray("volumes");
    ObjectNode volume = volumes.addObject();
    volume.put("name", "npdb-" + instanceId.toString().substring(0, 8));
    volume.put("mountPath", engine.dataPath());
    payload.put("memoryMb", memoryMb);
    payload.put("stateful", true);
    helperOperations.validateTargetAndPayload("container.deploy", containerName, payload);

    long now = System.currentTimeMillis();
    store.transaction(c -> {
      update(c, "INSERT INTO database_instance (instance_id,host_id,engine,engine_version,"
              + "container_name,host_port,data_volume,database_name,state,created_by,created_at,"
              + "updated_at) VALUES (?,?,?,?,?,?,?,?,'DRAFT',?,?,?)",
          instanceId, hostId, engine.id(), engine.version(), containerName, port,
          String.valueOf(volume.get("name")), DATABASE_NAME, actor.get("user_id"), now, now);
      audit(c, String.valueOf(actor.get("user_id")), "database.create", "database_instance",
          instanceId.toString(), "SUCCEEDED", clientIp(),
          "创建 " + engine.id() + " 实例 " + containerName + "，端口 " + port);
      return null;
    });

    var helperRequest = new HostHelperOperationResource.HelperOperationRequest(
        "container.deploy", containerName, payload,
        "部署数据库 " + engine.id() + " " + engine.version(), 300,
        ((Number) host.get("generation")).longValue());
    Response submitted;
    try {
      submitted = helperOperations.submitAs(hostId, idempotencyKey, helperRequest,
          String.valueOf(actor.get("user_id")), clientIp());
    } catch (RuntimeException failure) {
      store.transaction(c -> {
        update(c, "DELETE FROM database_instance WHERE instance_id=?", instanceId);
        return null;
      });
      throw failure;
    }
    UUID operationId = operationId(submitted);
    store.transaction(c -> {
      update(c, "UPDATE database_instance SET operation_id=?, updated_at=? WHERE instance_id=?",
          operationId, System.currentTimeMillis(), instanceId);
      return null;
    });

    Map<String, Object> body = new LinkedHashMap<>(record(instanceId));
    // Returned here and nowhere else: the password is never stored, so a lost response means
    // the instance must be recreated or the password reset on the host.
    body.put("credentials", Map.of(
        "host", "127.0.0.1",
        "port", port,
        "username", engine.user(),
        "database", DATABASE_NAME,
        "password", password,
        "note", "口令只显示这一次，面板不保存；请立即保存到密码管理器。"));
    return Response.status(202).entity(body).build();
  }

  /** Connection details, deliberately without the password. */
  @GET
  @Path("{instanceId}/connection")
  public Map<String, Object> connection(@PathParam("instanceId") String instanceValue) {
    session();
    Map<String, Object> row = record(uuid(instanceValue, "instanceId"));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("engine", row.get("engine"));
    body.put("host", "127.0.0.1");
    body.put("hostname", row.get("hostname"));
    body.put("port", row.get("port"));
    body.put("database", row.get("databaseName"));
    body.put("username", ENGINES.get(String.valueOf(row.get("engine"))).user());
    body.put("passwordStored", false);
    body.put("note", "面板不保存口令：忘记时请在主机上重置，或删除实例后用新口令重建。");
    return body;
  }

  @DELETE
  @Path("{instanceId}")
  public Response delete(@PathParam("instanceId") String instanceValue,
      @HeaderParam("Idempotency-Key") String idempotencyKey) {
    var actor = session();
    UUID instanceId = uuid(instanceValue, "instanceId");
    Map<String, Object> row = record(instanceId);
    String container = String.valueOf(row.get("containerName"));
    UUID hostId = UUID.fromString(String.valueOf(row.get("hostId")));
    var helperRequest = new HostHelperOperationResource.HelperOperationRequest(
        "container.remove", container, null, "删除数据库容器 " + container, 120,
        hostGeneration(hostId));
    // A missing agent must not remove the record: the container would keep running unattended.
    helperOperations.submitAs(hostId, idempotencyKey, helperRequest,
        String.valueOf(actor.get("user_id")), clientIp());
    store.transaction(c -> {
      update(c, "DELETE FROM database_instance WHERE instance_id=?", instanceId);
      audit(c, String.valueOf(actor.get("user_id")), "database.delete", "database_instance",
          instanceId.toString(), "SUCCEEDED", clientIp(),
          "删除数据库实例 " + container + "；数据卷 " + row.get("dataVolume") + " 保留");
      return null;
    });
    return Response.noContent().build();
  }

  private Map<String, Object> record(UUID instanceId) {
    var rows = store.transaction(c -> query(c, """
        SELECT d.instance_id, d.host_id, d.engine, d.engine_version, d.container_name,
               d.host_port, d.data_volume, d.database_name, d.state, d.operation_id,
               d.created_at, d.updated_at, h.hostname, o.status AS operation_status
        FROM database_instance d JOIN host h ON h.id = d.host_id
        LEFT JOIN operation o ON o.id = d.operation_id WHERE d.instance_id=?""", instanceId));
    if (rows.isEmpty()) throw fail(404, "DATABASE_NOT_FOUND", "数据库实例不存在");
    return view(rows.getFirst());
  }

  private static Map<String, Object> view(Map<String, Object> row) {
    Map<String, Object> item = new LinkedHashMap<>();
    String operationStatus = row.get("operation_status") == null
        ? null : String.valueOf(row.get("operation_status"));
    String stored = String.valueOf(row.get("state"));
    item.put("id", String.valueOf(row.get("instance_id")));
    item.put("hostId", String.valueOf(row.get("host_id")));
    item.put("hostname", row.get("hostname") == null ? null
        : String.valueOf(row.get("hostname")));
    item.put("engine", String.valueOf(row.get("engine")));
    item.put("version", String.valueOf(row.get("engine_version")));
    item.put("containerName", String.valueOf(row.get("container_name")));
    item.put("port", ((Number) row.get("host_port")).intValue());
    item.put("dataVolume", String.valueOf(row.get("data_volume")));
    item.put("databaseName", String.valueOf(row.get("database_name")));
    item.put("state", ReverseProxySiteResource.reportedState(stored, operationStatus));
    item.put("operationStatus", operationStatus);
    item.put("createdAt", ((Number) row.get("created_at")).longValue());
    item.put("updatedAt", ((Number) row.get("updated_at")).longValue());
    return item;
  }

  private boolean portTaken(UUID hostId, int port) {
    var rows = store.transaction(c -> query(c,
        "SELECT 1 FROM database_instance WHERE host_id=? AND host_port=?", hostId, port));
    return !rows.isEmpty();
  }

  private Map<String, Object> host(UUID hostId) {
    var rows = store.transaction(c -> query(c,
        "SELECT id, hostname, generation FROM host WHERE id=?", hostId));
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private long hostGeneration(UUID hostId) {
    Map<String, Object> host = host(hostId);
    if (host == null) throw fail(404, "HOST_NOT_FOUND", "主机未登记");
    return ((Number) host.get("generation")).longValue();
  }

  private static int defaultPort(Engine engine) {
    return switch (engine.id()) {
      case "postgres" -> 15432;
      case "mariadb" -> 13307;
      default -> 13306;
    };
  }

  private static String passwordVariable(Engine engine) {
    return engine.id().equals("postgres") ? "POSTGRES_PASSWORD" : "MYSQL_ROOT_PASSWORD";
  }

  private static String databaseVariable(Engine engine) {
    return engine.id().equals("postgres") ? "POSTGRES_DB" : "MYSQL_DATABASE";
  }

  /** 24 characters from a set without look-alikes; ~140 bits, fine for a database password. */
  static String password() {
    StringBuilder builder = new StringBuilder(24);
    for (int index = 0; index < 24; index++)
      builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    return builder.toString();
  }

  private static UUID operationId(Response submitted) {
    Object entity = submitted.getEntity();
    if (entity instanceof Map<?, ?> result && result.get("id") != null)
      return UUID.fromString(String.valueOf(result.get("id")));
    return null;
  }

  private static UUID uuid(String value, String field) {
    try {
      return UUID.fromString(value);
    } catch (RuntimeException invalid) {
      throw fail(422, "VALIDATION_FAILED", "无效的 " + field);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> session() {
    Map<String, Object> value = (Map<String, Object>) request.getProperty("session");
    if (value == null || value.get("user_id") == null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    return value;
  }

  private String clientIp() {
    return http.remoteAddress() == null ? null : http.remoteAddress().host();
  }
}
