package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Persistent first-server-then-agents K3s installation plans. */
@Path("/api/v1/kubernetes/installations")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class K3sInstallationResource {
  private static final Pattern VERSION =
      Pattern.compile("v([0-9]+)\\.([0-9]+)\\.([0-9]+)\\+k3s([0-9]+)");
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.:-]{16,512}");
  private static final Logger LOG = Logger.getLogger(K3sInstallationResource.class);
  private static final Set<String> TERMINAL_OPERATION_STATES =
      Set.of("SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN");

  @Inject AuthStore store;
  @Inject ObjectMapper mapper;
  @Inject KubernetesClusterRegistryService clusters;
  @Inject KubernetesClusterCredentialVault vault;
  @Inject HostHelperOperationResource helperOperations;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  @ConfigProperty(name = "nanpanel.kubernetes.installations.enabled", defaultValue = "true")
  boolean enabled;

  public record CreateRequest(String serverHostId, List<String> agentHostIds,
      String targetVersion, String serverUrl, String token, String reason) {}
  public record RegisterClusterRequest(String clusterId) {}

  private record StepWork(UUID planId, UUID hostId, String role, long generation,
      String idempotencyKey, String operationId, int ordinal, String state,
      UUID clusterId, String version, String reason, String actor, String serverUrl,
      byte[] encryptedToken) {}
  private record InstallHost(UUID hostId, String role, long generation) {}

  private String actor() {
    @SuppressWarnings("unchecked")
    var session = (Map<String, Object>)request.getProperty("session");
    if (session == null || session.get("user_id") == null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    return String.valueOf(session.get("user_id"));
  }

  @POST
  public Response create(CreateRequest input) {
    String actor = actor();
    if (!enabled) throw fail(503, "K3S_INSTALLATIONS_DISABLED", "K3s 安装计划未启用");
    if (input == null || input.serverHostId() == null || input.agentHostIds() == null
        || input.agentHostIds().size() > 99 || input.targetVersion() == null
        || !VERSION.matcher(input.targetVersion()).matches() || input.serverUrl() == null
        || input.serverUrl().trim().length() > 512
        || !helperOperations.validK3sServerUrl(input.serverUrl().trim()) || input.token() == null
        || !TOKEN.matcher(input.token()).matches() || input.token().startsWith("K10")
        || input.reason() == null || input.reason().trim().isEmpty()
        || input.reason().trim().length() > 500
        || input.reason().chars().anyMatch(Character::isISOControl)) {
      throw fail(422, "VALIDATION_FAILED", "Server、Agent 列表、固定版本、HTTPS API 地址、短令牌或操作原因无效");
    }
    UUID serverHostId = parseUuid(input.serverHostId());
    var hostIds = new ArrayList<UUID>();
    hostIds.add(serverHostId);
    for (String value : input.agentHostIds()) {
      UUID id = parseUuid(value);
      if (hostIds.contains(id)) throw fail(422, "VALIDATION_FAILED", "Server 与 Agent 主机必须互不重复");
      hostIds.add(id);
    }
    UUID planId = UUID.randomUUID();
    byte[] encryptedToken;
    try {
      encryptedToken = vault.encryptK3sInstallToken(planId, input.token());
    } catch (IllegalStateException unavailable) {
      throw fail(503, "KUBERNETES_CREDENTIAL_KEY_UNAVAILABLE", "K3s 安装令牌加密密钥未配置");
    }
    Instant now = Instant.now();
    String reason = input.reason().trim();
    store.transaction(connection -> {
      String capabilityColumn = connection.getMetaData().getDatabaseProductName()
          .equalsIgnoreCase("PostgreSQL") ? "h.capabilities::text" : "h.capabilities";
      var validatedHosts = new ArrayList<InstallHost>();
      for (int ordinal = 0; ordinal < hostIds.size(); ordinal++) {
        UUID hostId = hostIds.get(ordinal);
        var hosts = query(connection,
            "SELECT h.id,h.hostname,h.generation," + capabilityColumn + " AS capabilities,"
                + "(SELECT a.helper_status FROM agent a WHERE a.host_id=h.id "
                + "ORDER BY a.last_seen DESC NULLS LAST,a.id LIMIT 1) AS helper_status,"
                + "(SELECT a.last_seen FROM agent a WHERE a.host_id=h.id "
                + "ORDER BY a.last_seen DESC NULLS LAST,a.id LIMIT 1) AS last_seen "
                + "FROM host h WHERE h.id=?",
            hostId);
        if (hosts.isEmpty()) throw fail(404, "NOT_FOUND", "计划中的主机资产不存在");
        var host = hosts.getFirst();
        Instant lastSeen = instant(host.get("last_seen"));
        if (lastSeen == null || lastSeen.isBefore(now.minusSeconds(90))
            || !"AVAILABLE".equals(host.get("helper_status")))
          throw fail(409, "K3S_INSTALL_HELPER_UNAVAILABLE", "计划中的所有主机都必须在线且 Helper 可用");
        try {
          JsonNode capabilities = mapper.readTree(String.valueOf(host.get("capabilities")));
          if (!contains(capabilities, "k3s.install"))
            throw fail(409, "K3S_INSTALL_UNSUPPORTED_HOST", "计划中的所有主机都必须支持 k3s.install");
        } catch (java.io.IOException invalid) {
          throw new IllegalStateException("Stored Host capabilities are invalid", invalid);
        }
        var alreadyManaged = query(connection,
            "SELECT 1 AS present FROM kubernetes_cluster_node WHERE host_id=? "
                + "UNION ALL SELECT 1 AS present FROM host_kubernetes_runtime_discovery "
                + "WHERE host_id=? AND detected=TRUE",
            hostId, hostId);
        if (!alreadyManaged.isEmpty())
          throw fail(409, "K3S_INSTALL_HOST_ALREADY_MANAGED", "已纳入 Kubernetes 管理的主机不能用于首次安装计划");
        var activeOperation = query(connection,
            "SELECT id FROM operation WHERE host_id=? AND kind='HOST_HELPER_OPERATION' "
                + "AND status IN ('PENDING','RUNNING','UNKNOWN') LIMIT 1", hostId);
        if (!activeOperation.isEmpty())
          throw fail(409, "OPERATION_IN_PROGRESS", "计划中的主机已有未结束的 Helper 操作");
        validatedHosts.add(new InstallHost(hostId, ordinal == 0 ? "server" : "agent",
            ((Number)host.get("generation")).longValue()));
      }
      update(connection,
          "INSERT INTO k3s_install_plan (id,target_version,server_url,reason,state,active_ordinal,"
              + "encrypted_token,requested_by,created_at) VALUES (?,?,?,?, 'DRAFT',0,?,?,?)",
          planId, input.targetVersion(), input.serverUrl().trim(), reason, encryptedToken, actor,
          Timestamp.from(now));
      for (int ordinal = 0; ordinal < validatedHosts.size(); ordinal++) {
        InstallHost host = validatedHosts.get(ordinal);
        update(connection,
            "INSERT INTO k3s_install_step (plan_id,ordinal,host_id,role,expected_generation,state,"
                + "idempotency_key,created_at,updated_at) VALUES (?,?,?,?,?,'PENDING',?,?,?)",
            planId, ordinal, host.hostId(), host.role(), host.generation(),
            "k3s-install:" + planId + ":" + ordinal, Timestamp.from(now), Timestamp.from(now));
      }
      audit(connection, actor, "k3s.install.plan.create", "k3s_install_plan", planId.toString(),
          "ACCEPTED", clientIp(), "创建 K3s 多节点首次安装计划，节点数 " + hostIds.size());
      return null;
    });
    return noStore(Response.status(Response.Status.CREATED).entity(view(planId)));
  }

  @POST
  @Path("/{planId}/start")
  public Response start(@PathParam("planId") String planValue) {
    String actor = actor();
    UUID planId = parseUuid(planValue);
    store.transaction(connection -> {
      var plans = query(connection,
          "SELECT requested_by,state FROM k3s_install_plan WHERE id=? FOR UPDATE", planId);
      if (plans.isEmpty()) throw fail(404, "K3S_INSTALL_PLAN_NOT_FOUND", "安装计划不存在");
      if (!actor.equals(String.valueOf(plans.getFirst().get("requested_by"))))
        throw fail(403, "K3S_INSTALL_PLAN_FORBIDDEN", "只有计划创建者可以启动该计划");
      if (!"DRAFT".equals(plans.getFirst().get("state")))
        throw fail(409, "K3S_INSTALL_PLAN_STATE_CONFLICT", "只有草稿安装计划可以启动");
      Instant now = Instant.now();
      preflightHosts(connection, planId, now);
      update(connection, "UPDATE k3s_install_plan SET state='RUNNING',started_at=? WHERE id=?",
          Timestamp.from(now), planId);
      audit(connection, actor, "k3s.install.plan.start", "k3s_install_plan", planId.toString(),
          "ACCEPTED", clientIp(), "管理员显式启动 K3s 多节点安装计划");
      return null;
    });
    return noStore(Response.accepted(view(planId)));
  }

  @POST
  @Path("/{planId}/register-cluster")
  public Response registerCluster(@PathParam("planId") String planValue,
                                  RegisterClusterRequest input) {
    String actor = actor();
    UUID planId = parseUuid(planValue);
    UUID clusterId = input == null ? null : parseUuid(input.clusterId());
    var plan = store.transaction(connection -> query(connection,
        "SELECT server_host_id,state,requested_by FROM k3s_install_plan "
            + "JOIN (SELECT plan_id,host_id AS server_host_id FROM k3s_install_step "
            + "WHERE ordinal=0) s ON s.plan_id=k3s_install_plan.id WHERE id=?", planId));
    if (plan.isEmpty()) throw fail(404, "K3S_INSTALL_PLAN_NOT_FOUND", "安装计划不存在");
    if (!actor.equals(String.valueOf(plan.getFirst().get("requested_by"))))
      throw fail(403, "K3S_INSTALL_PLAN_FORBIDDEN", "只有计划创建者可以继续该计划");
    if (!"AWAITING_CLUSTER_REGISTRATION".equals(plan.getFirst().get("state")))
      throw fail(409, "K3S_INSTALL_PLAN_STATE_CONFLICT", "Server 安装完成后才能登记集群");
    try {
      clusters.refreshNodes(clusterId);
    } catch (RuntimeException unavailable) {
      throw fail(409, "K3S_INSTALL_CLUSTER_UNHEALTHY", "无法刷新待登记 K3s 集群");
    }
    Instant now = Instant.now();
    store.transaction(connection -> {
      var locked = query(connection, "SELECT state,requested_by FROM k3s_install_plan "
          + "WHERE id=? FOR UPDATE", planId);
      if (locked.isEmpty() || !actor.equals(String.valueOf(locked.getFirst().get("requested_by"))))
        throw fail(404, "K3S_INSTALL_PLAN_NOT_FOUND", "安装计划不存在");
      if (!"AWAITING_CLUSTER_REGISTRATION".equals(locked.getFirst().get("state")))
        throw fail(409, "K3S_INSTALL_PLAN_STATE_CONFLICT", "安装计划状态已变化");
      var clusterLock = query(connection,
          "SELECT id FROM kubernetes_cluster WHERE id=? FOR UPDATE", clusterId);
      if (clusterLock.isEmpty()) throw fail(404, "KUBERNETES_CLUSTER_NOT_FOUND", "集群未登记");
      var competingPlans = query(connection,
          "SELECT id FROM k3s_install_plan WHERE cluster_id=? AND id<>? "
              + "AND state IN ('RUNNING','AWAITING_CLUSTER_REGISTRATION','AWAITING_NODE_MAPPING')",
          clusterId, planId);
      if (!competingPlans.isEmpty())
        throw fail(409, "K3S_INSTALL_PLAN_IN_PROGRESS", "该 K3s 集群已有另一项安装计划");
      var registered = query(connection,
          "SELECT c.kind,c.health_status,c.observed_at,n.node_present,n.ready "
              + "FROM kubernetes_cluster c JOIN kubernetes_cluster_node n ON n.cluster_id=c.id "
              + "WHERE c.id=? AND n.host_id=? ORDER BY n.node_name LIMIT 1",
          clusterId, plan.getFirst().get("server_host_id"));
      if (registered.isEmpty() || !"K3S".equals(registered.getFirst().get("kind"))
          || !"HEALTHY".equals(registered.getFirst().get("health_status"))
          || !Boolean.TRUE.equals(registered.getFirst().get("node_present"))
          || !Boolean.TRUE.equals(registered.getFirst().get("ready"))
          || instant(registered.getFirst().get("observed_at")) == null
          || instant(registered.getFirst().get("observed_at")).isBefore(now.minusSeconds(120)))
        throw fail(409, "K3S_INSTALL_SERVER_MAPPING_REQUIRED",
            "导入 K3s 集群并将 Ready 的 Server Node 映射到计划中的 Server 主机后再继续");
      var next = query(connection,
          "SELECT ordinal FROM k3s_install_step WHERE plan_id=? AND ordinal>0 ORDER BY ordinal LIMIT 1",
          planId);
      if (next.isEmpty()) {
        update(connection, "UPDATE k3s_install_plan SET cluster_id=?,state='SUCCEEDED',"
            + "finished_at=?,encrypted_token=? WHERE id=?", clusterId, Timestamp.from(now),
            new byte[0], planId);
      } else {
        update(connection, "UPDATE k3s_install_plan SET cluster_id=?,state='RUNNING',active_ordinal=? "
            + "WHERE id=?", clusterId, next.getFirst().get("ordinal"), planId);
      }
      audit(connection, actor, "k3s.install.plan.register-cluster", "k3s_install_plan",
          planId.toString(), "ACCEPTED", clientIp(), "K3s Server Node 已映射，允许安装 Agent");
      return null;
    });
    return noStore(Response.ok(view(planId)));
  }

  @GET
  public Response list() {
    String actor = actor();
    List<String> ids = store.transaction(connection -> query(connection,
        "SELECT id FROM k3s_install_plan WHERE requested_by=? ORDER BY created_at DESC LIMIT 100",
        actor).stream().map(row -> String.valueOf(row.get("id"))).toList());
    return noStore(Response.ok(Map.of("items", ids.stream().map(id -> view(UUID.fromString(id))).toList())));
  }

  @GET
  @Path("/{planId}")
  public Response get(@PathParam("planId") String planValue) {
    String actor = actor();
    UUID planId = parseUuid(planValue);
    Map<String, Object> plan = view(planId);
    if (!actor.equals(plan.get("requestedBy")))
      throw fail(404, "K3S_INSTALL_PLAN_NOT_FOUND", "安装计划不存在");
    return noStore(Response.ok(plan));
  }

  @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void advancePlans() {
    if (!enabled) return;
    var ids = store.transaction(connection -> query(connection,
        "SELECT id FROM k3s_install_plan WHERE state IN ('RUNNING','AWAITING_NODE_MAPPING') "
            + "ORDER BY created_at LIMIT 20").stream().map(row -> (UUID)row.get("id")).toList());
    for (UUID id : ids) {
      try {
        advance(id);
      } catch (RuntimeException failure) {
        LOG.warnf("Unable to advance K3s installation %s (%s)", id,
            failure.getClass().getSimpleName());
      }
    }
  }

  private void advance(UUID planId) {
    StepWork work = loadWork(planId);
    if (work == null) return;
    if ("AWAITING_NODE_MAPPING".equals(work.state())) {
      verifyJoinedNode(work);
      return;
    }
    if ("PENDING".equals(work.state()) || "SUBMITTING".equals(work.state())) {
      if ("agent".equals(work.role())) {
        try {
          clusters.refreshNodes(work.clusterId());
        } catch (RuntimeException unavailable) {
          setPlanWaiting(work, "K3S_CLUSTER_OBSERVATION_PENDING", "安装前无法刷新集群 Node 清单");
          return;
        }
        if (!allMappedNodesReady(work.clusterId())) {
          setPlanWaiting(work, "K3S_CLUSTER_NODE_NOT_READY", "所有现存 Node 必须 Ready 并映射到主机后才能加入下一台 Agent");
          return;
        }
      }
      store.transaction(connection -> {
        update(connection, "UPDATE k3s_install_step SET state='SUBMITTING',updated_at=? "
            + "WHERE plan_id=? AND ordinal=? AND state='PENDING'",
            Timestamp.from(Instant.now()), work.planId(), work.ordinal());
        return null;
      });
      Response response;
      String token;
      try {
        token = vault.decryptK3sInstallToken(work.planId(), work.encryptedToken());
      } catch (RuntimeException unavailable) {
        failWork(work, "FAILED", "K3S_INSTALL_TOKEN_UNAVAILABLE", "安装令牌无法解密，计划已停止");
        return;
      }
      try {
        response = helperOperations.submitPlannedInstall(work.hostId(), work.idempotencyKey(),
            work.role(), work.version(), "agent".equals(work.role()) ? work.serverUrl() : null,
            token, work.generation(), operationReason(work), work.actor());
      } catch (RuntimeException failure) {
        String state = failure instanceof jakarta.ws.rs.WebApplicationException webFailure
            && webFailure.getResponse() != null && webFailure.getResponse().getStatus() < 500
                ? "FAILED" : "UNKNOWN";
        failWork(work, state, "K3S_INSTALL_SUBMISSION_" + state,
            state.equals("UNKNOWN") ? "安装下发结果未知，计划已停止" : "安装未通过操作准入，计划已停止");
        return;
      }
      @SuppressWarnings("unchecked")
      var operation = (Map<String, Object>)response.getEntity();
      String operationId = String.valueOf(operation.get("id"));
      store.transaction(connection -> {
        update(connection, "UPDATE k3s_install_step SET state='RUNNING',operation_id=?,updated_at=? "
            + "WHERE plan_id=? AND ordinal=? AND state IN ('SUBMITTING','RUNNING')",
            operationId, Timestamp.from(Instant.now()), work.planId(), work.ordinal());
        return null;
      });
      return;
    }
    var operationRows = store.transaction(connection -> query(connection,
        "SELECT id,status,error_json FROM operation WHERE id=? OR idempotency_key=? "
            + "ORDER BY created_at DESC LIMIT 1", work.operationId(), work.idempotencyKey()));
    if (operationRows.isEmpty()) return;
    String operationState = String.valueOf(operationRows.getFirst().get("status"));
    if (!TERMINAL_OPERATION_STATES.contains(operationState)) return;
    if (!"SUCCEEDED".equals(operationState)) {
      failWork(work, "UNKNOWN".equals(operationState) ? "UNKNOWN" : "FAILED",
          "K3S_INSTALL_OPERATION_" + operationState,
          summarize(operationRows.getFirst().get("error_json")));
      return;
    }
    if ("server".equals(work.role())) {
      Instant now = Instant.now();
      store.transaction(connection -> {
        update(connection, "UPDATE k3s_install_step SET state='SUCCEEDED',updated_at=?,finished_at=? "
            + "WHERE plan_id=? AND ordinal=?", Timestamp.from(now), Timestamp.from(now),
            work.planId(), work.ordinal());
        update(connection, "UPDATE k3s_install_plan SET state='AWAITING_CLUSTER_REGISTRATION',"
            + "error_code=NULL,error_message=NULL WHERE id=?", work.planId());
        return null;
      });
    } else {
      store.transaction(connection -> {
        update(connection, "UPDATE k3s_install_step SET state='VERIFYING',updated_at=? "
            + "WHERE plan_id=? AND ordinal=?", Timestamp.from(Instant.now()),
            work.planId(), work.ordinal());
        update(connection, "UPDATE k3s_install_plan SET state='AWAITING_NODE_MAPPING',"
            + "error_code='K3S_INSTALL_NODE_MAPPING_REQUIRED',"
            + "error_message='Agent 已安装；导入/刷新集群并将新 Ready Node 映射到该主机后继续' "
            + "WHERE id=?", work.planId());
        return null;
      });
    }
  }

  private void verifyJoinedNode(StepWork work) {
    try {
      clusters.refreshNodes(work.clusterId());
    } catch (RuntimeException unavailable) {
      return;
    }
    var mapped = store.transaction(connection -> query(connection,
        "SELECT n.node_present,n.ready,n.kubelet_version,c.health_status,c.observed_at "
            + "FROM kubernetes_cluster_node n JOIN kubernetes_cluster c ON c.id=n.cluster_id "
            + "WHERE n.cluster_id=? AND n.host_id=? ORDER BY n.node_name LIMIT 1",
        work.clusterId(), work.hostId()));
    if (mapped.isEmpty()) return;
    var node = mapped.getFirst();
    Instant observedAt = instant(node.get("observed_at"));
    if (!Boolean.TRUE.equals(node.get("node_present")) || !Boolean.TRUE.equals(node.get("ready"))
        || !work.version().equals(node.get("kubelet_version"))
        || !"HEALTHY".equals(node.get("health_status")) || observedAt == null
        || observedAt.isBefore(Instant.now().minusSeconds(120)) || !allMappedNodesReady(work.clusterId()))
      return;
    Instant now = Instant.now();
    store.transaction(connection -> {
      query(connection, "SELECT id FROM k3s_install_plan WHERE id=? FOR UPDATE", work.planId());
      update(connection, "UPDATE k3s_install_step SET state='SUCCEEDED',updated_at=?,finished_at=? "
          + "WHERE plan_id=? AND ordinal=? AND state='VERIFYING'",
          Timestamp.from(now), Timestamp.from(now), work.planId(), work.ordinal());
      var next = query(connection, "SELECT ordinal FROM k3s_install_step "
          + "WHERE plan_id=? AND ordinal>? ORDER BY ordinal LIMIT 1", work.planId(), work.ordinal());
      if (next.isEmpty()) {
        update(connection, "UPDATE k3s_install_plan SET state='SUCCEEDED',finished_at=?,"
            + "encrypted_token=? WHERE id=?", Timestamp.from(now), new byte[0], work.planId());
      } else {
        update(connection, "UPDATE k3s_install_plan SET state='RUNNING',active_ordinal=?,"
            + "error_code=NULL,error_message=NULL WHERE id=?", next.getFirst().get("ordinal"),
            work.planId());
      }
      return null;
    });
  }

  private StepWork loadWork(UUID planId) {
    return store.transaction(connection -> {
      var plans = query(connection,
          "SELECT id,target_version,server_url,reason,requested_by,state,active_ordinal,cluster_id,"
              + "encrypted_token FROM k3s_install_plan WHERE id=? FOR UPDATE", planId);
      if (plans.isEmpty()) return null;
      var plan = plans.getFirst();
      if (!Set.of("RUNNING", "AWAITING_NODE_MAPPING").contains(String.valueOf(plan.get("state"))))
        return null;
      int ordinal = ((Number)plan.get("active_ordinal")).intValue();
      var steps = query(connection,
          "SELECT ordinal,host_id,role,expected_generation,state,idempotency_key,operation_id "
              + "FROM k3s_install_step WHERE plan_id=? AND ordinal=? FOR UPDATE", planId, ordinal);
      if (steps.isEmpty()) return null;
      var step = steps.getFirst();
      return new StepWork(planId, (UUID)step.get("host_id"), String.valueOf(step.get("role")),
          ((Number)step.get("expected_generation")).longValue(),
          String.valueOf(step.get("idempotency_key")),
          step.get("operation_id") == null ? null : String.valueOf(step.get("operation_id")),
          ordinal, "AWAITING_NODE_MAPPING".equals(plan.get("state"))
              ? "AWAITING_NODE_MAPPING" : String.valueOf(step.get("state")),
          plan.get("cluster_id") == null ? null : (UUID)plan.get("cluster_id"),
          String.valueOf(plan.get("target_version")), String.valueOf(plan.get("reason")),
          String.valueOf(plan.get("requested_by")), String.valueOf(plan.get("server_url")),
          (byte[])plan.get("encrypted_token"));
    });
  }

  private void preflightHosts(java.sql.Connection connection, UUID planId, Instant now)
      throws java.sql.SQLException {
    var steps = query(connection,
        "SELECT s.host_id,s.expected_generation,h.generation,h.capabilities,"
            + "(SELECT a.helper_status FROM agent a WHERE a.host_id=h.id "
            + "ORDER BY a.last_seen DESC NULLS LAST,a.id LIMIT 1) AS helper_status,"
            + "(SELECT a.last_seen FROM agent a WHERE a.host_id=h.id "
            + "ORDER BY a.last_seen DESC NULLS LAST,a.id LIMIT 1) AS last_seen "
            + "FROM k3s_install_step s JOIN host h ON h.id=s.host_id WHERE s.plan_id=?",
        planId);
    if (steps.isEmpty()) throw fail(409, "K3S_INSTALL_PREFLIGHT_FAILED", "安装计划没有节点");
    for (var step : steps) {
      Instant lastSeen = instant(step.get("last_seen"));
      if (((Number)step.get("generation")).longValue()
              != ((Number)step.get("expected_generation")).longValue() || lastSeen == null
          || lastSeen.isBefore(now.minusSeconds(90)) || !"AVAILABLE".equals(step.get("helper_status")))
        throw fail(409, "K3S_INSTALL_PREFLIGHT_FAILED", "主机版本或 Agent Helper 状态已变化，请重新创建计划");
      try {
        if (!contains(mapper.readTree(String.valueOf(step.get("capabilities"))), "k3s.install"))
          throw fail(409, "K3S_INSTALL_PREFLIGHT_FAILED", "计划中的主机不再支持 k3s.install");
      } catch (java.io.IOException invalid) {
        throw new IllegalStateException("Stored Host capabilities are invalid", invalid);
      }
      var pending = query(connection,
          "SELECT id FROM operation WHERE host_id=? AND kind='HOST_HELPER_OPERATION' "
              + "AND status IN ('PENDING','RUNNING','UNKNOWN') LIMIT 1", step.get("host_id"));
      if (!pending.isEmpty()) throw fail(409, "K3S_INSTALL_PREFLIGHT_FAILED", "主机已有未结束的 Helper 操作");
      var managed = query(connection,
          "SELECT 1 AS present FROM kubernetes_cluster_node WHERE host_id=? "
              + "UNION ALL SELECT 1 AS present FROM host_kubernetes_runtime_discovery "
              + "WHERE host_id=? AND detected=TRUE",
          step.get("host_id"), step.get("host_id"));
      if (!managed.isEmpty())
        throw fail(409, "K3S_INSTALL_PREFLIGHT_FAILED", "计划主机已被 Kubernetes Node 或 Agent 运行时发现占用");
    }
  }

  private boolean allMappedNodesReady(UUID clusterId) {
    return store.transaction(connection -> {
      var status = query(connection,
          "SELECT c.health_status,c.observed_at,COUNT(n.node_uid) AS total,"
              + "SUM(CASE WHEN n.node_present=TRUE AND n.ready=TRUE AND n.host_id IS NOT NULL "
              + "THEN 1 ELSE 0 END) AS ready_mapped FROM kubernetes_cluster c "
              + "LEFT JOIN kubernetes_cluster_node n ON n.cluster_id=c.id "
              + "AND n.node_present=TRUE WHERE c.id=? GROUP BY c.health_status,c.observed_at", clusterId);
      if (status.isEmpty()) return false;
      var row = status.getFirst();
      Instant observed = instant(row.get("observed_at"));
      return "HEALTHY".equals(row.get("health_status")) && observed != null
          && !observed.isBefore(Instant.now().minusSeconds(120))
          && ((Number)row.get("total")).longValue() > 0
          && ((Number)row.get("total")).longValue()
              == ((Number)row.get("ready_mapped")).longValue();
    });
  }

  private void setPlanWaiting(StepWork work, String code, String message) {
    store.transaction(connection -> {
      update(connection, "UPDATE k3s_install_plan SET error_code=?,error_message=? WHERE id=?",
          code, message, work.planId());
      return null;
    });
  }

  private void failWork(StepWork work, String state, String code, String message) {
    Instant now = Instant.now();
    store.transaction(connection -> {
      update(connection, "UPDATE k3s_install_step SET state=?,error_code=?,error_message=?,"
          + "updated_at=?,finished_at=? WHERE plan_id=? AND ordinal=?",
          state, code, message, Timestamp.from(now), Timestamp.from(now), work.planId(), work.ordinal());
      update(connection, "UPDATE k3s_install_plan SET state=?,error_code=?,error_message=?,"
          + "finished_at=?,encrypted_token=? WHERE id=?", state, code, message, Timestamp.from(now),
          new byte[0], work.planId());
      return null;
    });
  }

  private String operationReason(StepWork work) {
    String prefix = "K3s installation " + work.planId() + " step " + (work.ordinal() + 1) + ": ";
    int detailLimit = 500 - prefix.length();
    return prefix + work.reason().substring(0, Math.min(work.reason().length(), detailLimit));
  }

  private Map<String, Object> view(UUID planId) {
    return store.transaction(connection -> {
      var plans = query(connection,
          "SELECT p.*,s.host_id AS server_host_id FROM k3s_install_plan p "
              + "JOIN k3s_install_step s ON s.plan_id=p.id AND s.ordinal=0 WHERE p.id=?", planId);
      if (plans.isEmpty()) throw fail(404, "K3S_INSTALL_PLAN_NOT_FOUND", "安装计划不存在");
      var plan = plans.getFirst();
      var value = new LinkedHashMap<String, Object>();
      value.put("id", planId.toString());
      value.put("serverHostId", String.valueOf(plan.get("server_host_id")));
      value.put("targetVersion", plan.get("target_version"));
      value.put("serverUrl", plan.get("server_url"));
      value.put("reason", plan.get("reason"));
      value.put("state", plan.get("state"));
      value.put("activeOrdinal", plan.get("active_ordinal"));
      value.put("clusterId", plan.get("cluster_id") == null ? null : String.valueOf(plan.get("cluster_id")));
      value.put("errorCode", plan.get("error_code"));
      value.put("errorMessage", plan.get("error_message"));
      value.put("requestedBy", String.valueOf(plan.get("requested_by")));
      value.put("createdAt", stamp(plan.get("created_at")));
      value.put("startedAt", stamp(plan.get("started_at")));
      value.put("finishedAt", stamp(plan.get("finished_at")));
      value.put("steps", query(connection,
          "SELECT ordinal,host_id,role,expected_generation,state,operation_id,error_code,"
              + "error_message,created_at,updated_at,finished_at FROM k3s_install_step "
              + "WHERE plan_id=? ORDER BY ordinal", planId).stream().map(row -> {
                var step = new LinkedHashMap<String, Object>();
                step.put("ordinal", row.get("ordinal"));
                step.put("hostId", String.valueOf(row.get("host_id")));
                step.put("role", row.get("role"));
                step.put("expectedGeneration", row.get("expected_generation"));
                step.put("state", row.get("state"));
                step.put("operationId", row.get("operation_id") == null ? null : String.valueOf(row.get("operation_id")));
                step.put("errorCode", row.get("error_code"));
                step.put("errorMessage", row.get("error_message"));
                step.put("createdAt", stamp(row.get("created_at")));
                step.put("updatedAt", stamp(row.get("updated_at")));
                step.put("finishedAt", stamp(row.get("finished_at")));
                return step;
              }).toList());
      return value;
    });
  }

  private static boolean contains(JsonNode array, String value) {
    if (array == null || !array.isArray()) return false;
    for (JsonNode item : array) if (item.isTextual() && value.equals(item.asText())) return true;
    return false;
  }

  private UUID parseUuid(String value) {
    try { return UUID.fromString(value); }
    catch (RuntimeException invalid) { throw fail(404, "K3S_INSTALL_PLAN_NOT_FOUND", "计划或集群不存在"); }
  }

  private String clientIp() {
    String forwarded = http.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",", 2)[0].trim();
    return http.remoteAddress() == null ? null : http.remoteAddress().hostAddress();
  }

  private static Instant instant(Object value) {
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof OffsetDateTime dateTime) return dateTime.toInstant();
    return null;
  }

  private static String stamp(Object value) {
    Instant instant = instant(value);
    return instant == null ? null : instant.toString();
  }

  private static String summarize(Object error) {
    if (error == null) return "Helper 安装操作未成功完成";
    String value = String.valueOf(error);
    return value.length() <= 1000 ? value : value.substring(0, 1000);
  }

  private Response noStore(Response.ResponseBuilder response) {
    return response.header("Cache-Control", "no-store").build();
  }
}
