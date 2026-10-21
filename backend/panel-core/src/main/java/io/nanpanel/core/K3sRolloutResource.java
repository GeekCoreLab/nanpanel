package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import org.jboss.logging.Logger;

/** Persistent, serialized K3s node upgrade plans. */
@Path("/api/v1/kubernetes/rollouts")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class K3sRolloutResource {
  private static final Pattern VERSION = Pattern.compile("v([0-9]+)\\.([0-9]+)\\.([0-9]+)\\+k3s([0-9]+)");
  private static final Logger LOG = Logger.getLogger(K3sRolloutResource.class);
  private static final Set<String> TERMINAL_OPERATION_STATES =
      Set.of("SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN");

  @Inject AuthStore store;
  @Inject ObjectMapper mapper;
  @Inject KubernetesClusterRegistryService clusters;
  @Inject HostHelperOperationResource helperOperations;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  @ConfigProperty(name = "nanpanel.kubernetes.rollouts.enabled", defaultValue = "true")
  boolean enabled;

  public record CreateRequest(String clusterId, String targetVersion, String reason) {}

  private record PlannedNode(int ordinal, UUID hostId, String nodeUid, String nodeName,
      String role, long generation, String idempotencyKey) {}

  private record Work(UUID planId, UUID clusterId, UUID hostId, String nodeUid, String nodeName,
      String role, String version, String reason, String actor, String key, long generation, int ordinal,
      String stepState, UUID operationId) {}

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
    if (!enabled) throw fail(503, "K3S_ROLLOUTS_DISABLED", "K3s 多节点升级计划未启用");
    if (input == null || input.clusterId() == null || input.targetVersion() == null
        || !VERSION.matcher(input.targetVersion()).matches() || input.reason() == null
        || input.reason().trim().isEmpty() || input.reason().trim().length() > 500
        || input.reason().chars().anyMatch(Character::isISOControl)) {
      throw fail(422, "VALIDATION_FAILED", "集群、固定 K3s 版本和操作原因无效");
    }
    UUID clusterId = parseUuid(input.clusterId());
    try {
      clusters.refreshNodes(clusterId);
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw fail(404, "KUBERNETES_CLUSTER_NOT_FOUND", "集群未登记");
    } catch (RuntimeException unavailable) {
      throw fail(409, "K3S_ROLLOUT_CLUSTER_UNHEALTHY", "无法刷新集群 Node 清单");
    }
    String reason = input.reason().trim();
    UUID planId = UUID.randomUUID();
    Instant now = Instant.now();
    store.transaction(connection -> {
        var clusterRows = query(connection,
            "SELECT kind,health_status,observed_at FROM kubernetes_cluster WHERE id=? FOR UPDATE",
            clusterId);
        if (clusterRows.isEmpty()) throw fail(404, "KUBERNETES_CLUSTER_NOT_FOUND", "集群未登记");
        var cluster = clusterRows.getFirst();
        Instant observedAt = instant(cluster.get("observed_at"));
        if (!"K3S".equals(cluster.get("kind")) || !"HEALTHY".equals(cluster.get("health_status"))
            || observedAt == null || observedAt.isBefore(now.minusSeconds(120))) {
          throw fail(409, "K3S_ROLLOUT_CLUSTER_UNHEALTHY", "计划仅允许最近 120 秒内健康的 K3s 集群");
        }
        var existing = query(connection,
            "SELECT id FROM k3s_rollout_plan WHERE cluster_id=? "
                + "AND state IN ('RUNNING','VERIFYING') FOR UPDATE", clusterId);
        if (!existing.isEmpty())
          throw fail(409, "K3S_ROLLOUT_IN_PROGRESS", "该集群已有未结束的升级计划");
        String capabilityColumn = connection.getMetaData().getDatabaseProductName()
            .equalsIgnoreCase("PostgreSQL") ? "h.capabilities::text" : "h.capabilities";
        var rows = query(connection,
            "SELECT n.node_uid,n.node_name,n.roles,n.node_present,n.ready,n.kubelet_version,n.host_id,"
                + "h.generation," + capabilityColumn + " AS capabilities,"
                + "(SELECT a.helper_status FROM agent a WHERE a.host_id=h.id "
                + "ORDER BY a.last_seen DESC NULLS LAST,a.id LIMIT 1) AS helper_status,"
                + "(SELECT a.last_seen FROM agent a WHERE a.host_id=h.id "
                + "ORDER BY a.last_seen DESC NULLS LAST,a.id LIMIT 1) AS last_seen "
                + "FROM kubernetes_cluster_node n LEFT JOIN host h ON h.id=n.host_id "
                + "WHERE n.cluster_id=? AND n.node_present=TRUE ORDER BY n.node_name,n.node_uid",
            clusterId);
        if (rows.isEmpty()) throw fail(409, "K3S_ROLLOUT_NO_NODES", "集群没有可升级的 Node");
        var nodes = new ArrayList<PlannedNode>();
        for (var row : rows) {
          if (!Boolean.TRUE.equals(row.get("ready")) || row.get("host_id") == null
              || row.get("generation") == null || row.get("last_seen") == null) {
            throw fail(409, "K3S_ROLLOUT_NODE_NOT_READY",
                "所有存在的 K3s Node 都必须 Ready、映射到 Host 且 Agent 在线");
          }
          Instant lastSeen = instant(row.get("last_seen"));
          if (lastSeen == null || lastSeen.isBefore(now.minusSeconds(90))
              || !"AVAILABLE".equals(row.get("helper_status"))) {
            throw fail(409, "K3S_ROLLOUT_HELPER_UNAVAILABLE", "计划中的 Agent Helper 必须在线且可用");
          }
          try {
            JsonNode capabilities = mapper.readTree(String.valueOf(row.get("capabilities")));
            if (!contains(capabilities, "k3s.upgrade"))
              throw fail(409, "K3S_ROLLOUT_UNSUPPORTED_NODE", "计划中的所有主机都必须支持 k3s.upgrade");
          } catch (java.io.IOException invalid) {
            throw new IllegalStateException("Stored Host capabilities are invalid", invalid);
          }
          String roles = String.valueOf(row.get("roles"));
          if (!compatibleUpgrade(String.valueOf(row.get("kubelet_version")), input.targetVersion()))
            throw fail(409, "K3S_ROLLOUT_VERSION_UNSUPPORTED",
                "目标版本必须不低于当前版本，且不能跨越一个 Kubernetes minor 版本");
          String role = roles.contains("control-plane") || roles.contains("master") ? "server" : "agent";
          int ordinal = nodes.size();
          nodes.add(new PlannedNode(ordinal, (UUID)row.get("host_id"),
              String.valueOf(row.get("node_uid")), String.valueOf(row.get("node_name")),
              role, ((Number)row.get("generation")).longValue(),
              "k3s-rollout:" + planId + ":" + ordinal));
        }
        nodes.sort(Comparator.comparingInt((PlannedNode node) -> "server".equals(node.role()) ? 0 : 1)
            .thenComparing(PlannedNode::nodeName).thenComparing(PlannedNode::nodeUid));
        var indexed = new ArrayList<PlannedNode>();
        for (int ordinal = 0; ordinal < nodes.size(); ordinal++) {
          var node = nodes.get(ordinal);
          indexed.add(new PlannedNode(ordinal, node.hostId(), node.nodeUid(), node.nodeName(),
              node.role(), node.generation(), "k3s-rollout:" + planId + ":" + ordinal));
        }
        update(connection,
            "INSERT INTO k3s_rollout_plan (id,cluster_id,target_version,reason,state,"
                + "active_ordinal,requested_by,created_at) VALUES (?,?,?,?, 'DRAFT',0,?,?)",
            planId, clusterId, input.targetVersion(), reason, actor, Timestamp.from(now));
        for (var node : indexed) {
          update(connection,
              "INSERT INTO k3s_rollout_step (plan_id,ordinal,host_id,node_uid,node_name,role,"
                  + "expected_generation,state,idempotency_key,created_at,updated_at) "
                  + "VALUES (?,?,?,?,?,?,?,'PENDING',?,?,?)",
              planId, node.ordinal(), node.hostId(), node.nodeUid(), node.nodeName(), node.role(),
              node.generation(), node.idempotencyKey(), Timestamp.from(now), Timestamp.from(now));
        }
        audit(connection, actor, "k3s.rollout.create", "kubernetes_cluster", clusterId.toString(),
            "ACCEPTED", clientIp(), "创建逐节点 K3s 升级计划，节点数 " + indexed.size());
        return indexed;
      });
    return noStore(Response.status(Response.Status.CREATED).entity(view(planId)));
  }

  @POST
  @Path("/{planId}/start")
  public Response start(@PathParam("planId") String planValue) {
    String actor = actor();
    UUID planId = parseUuid(planValue);
    var ownerRows = store.transaction(connection -> query(connection,
        "SELECT cluster_id,requested_by,state FROM k3s_rollout_plan WHERE id=?", planId));
    if (ownerRows.isEmpty()) throw fail(404, "K3S_ROLLOUT_NOT_FOUND", "升级计划不存在");
    var owner = ownerRows.getFirst();
    if (!actor.equals(String.valueOf(owner.get("requested_by"))))
      throw fail(403, "K3S_ROLLOUT_FORBIDDEN", "只有计划创建者可以启动该计划");
    if (!"DRAFT".equals(owner.get("state")))
      throw fail(409, "K3S_ROLLOUT_STATE_CONFLICT", "只有草稿计划可以启动");
    UUID clusterId = (UUID)owner.get("cluster_id");
    try {
      clusters.refreshNodes(clusterId);
    } catch (RuntimeException unavailable) {
      throw fail(409, "K3S_ROLLOUT_CLUSTER_UNHEALTHY", "启动前无法刷新 K3s Node 状态");
    }
    Instant now = Instant.now();
    store.transaction(connection -> {
      var plans = query(connection, "SELECT cluster_id,state,requested_by FROM k3s_rollout_plan "
          + "WHERE id=? FOR UPDATE", planId);
      if (plans.isEmpty()) throw fail(404, "K3S_ROLLOUT_NOT_FOUND", "升级计划不存在");
      var plan = plans.getFirst();
      if (!actor.equals(String.valueOf(plan.get("requested_by"))))
        throw fail(403, "K3S_ROLLOUT_FORBIDDEN", "只有计划创建者可以启动该计划");
      if (!"DRAFT".equals(plan.get("state")))
        throw fail(409, "K3S_ROLLOUT_STATE_CONFLICT", "只有草稿计划可以启动");
      var clusters = query(connection,
          "SELECT kind,health_status,observed_at FROM kubernetes_cluster WHERE id=? FOR UPDATE",
          plan.get("cluster_id"));
      if (clusters.isEmpty() || !"K3S".equals(clusters.getFirst().get("kind"))
          || !"HEALTHY".equals(clusters.getFirst().get("health_status")))
        throw fail(409, "K3S_ROLLOUT_CLUSTER_UNHEALTHY", "启动前集群必须仍处于健康状态");
      Instant observedAt = instant(clusters.getFirst().get("observed_at"));
      if (observedAt == null || observedAt.isBefore(now.minusSeconds(120)))
        throw fail(409, "K3S_ROLLOUT_CLUSTER_UNHEALTHY", "启动前必须在 120 秒内刷新集群状态");
      preflightSteps(connection, planId, (UUID)plan.get("cluster_id"), now);
      var active = query(connection,
          "SELECT id FROM k3s_rollout_plan WHERE cluster_id=? AND state IN ('RUNNING','VERIFYING') FOR UPDATE",
          plan.get("cluster_id"));
      if (!active.isEmpty()) throw fail(409, "K3S_ROLLOUT_IN_PROGRESS", "该集群已有运行中的升级计划");
      update(connection, "UPDATE k3s_rollout_plan SET state='RUNNING',started_at=? WHERE id=?",
          Timestamp.from(now), planId);
      audit(connection, actor, "k3s.rollout.start", "k3s_rollout_plan", planId.toString(),
          "ACCEPTED", clientIp(), "管理员显式启动 K3s 逐节点升级计划");
      return null;
    });
    return noStore(Response.accepted(view(planId)));
  }

  private void preflightSteps(java.sql.Connection connection, UUID planId, UUID clusterId, Instant now)
      throws java.sql.SQLException {
    var planned = query(connection,
        "SELECT s.host_id,s.node_uid,s.expected_generation,h.generation,h.capabilities,"
            + "n.node_present,n.ready,"
            + "(SELECT a.helper_status FROM agent a WHERE a.host_id=h.id "
            + "ORDER BY a.last_seen DESC NULLS LAST,a.id LIMIT 1) AS helper_status,"
            + "(SELECT a.last_seen FROM agent a WHERE a.host_id=h.id "
            + "ORDER BY a.last_seen DESC NULLS LAST,a.id LIMIT 1) AS last_seen "
            + "FROM k3s_rollout_step s JOIN host h ON h.id=s.host_id "
            + "LEFT JOIN kubernetes_cluster_node n ON n.cluster_id=? AND n.node_uid=s.node_uid "
            + "AND n.host_id=s.host_id WHERE s.plan_id=? ORDER BY s.ordinal",
        clusterId, planId);
    var presentNodes = query(connection,
        "SELECT node_uid FROM kubernetes_cluster_node WHERE cluster_id=? AND node_present=TRUE",
        clusterId);
    if (planned.isEmpty() || planned.size() != presentNodes.size())
      throw fail(409, "K3S_ROLLOUT_MEMBERSHIP_CHANGED", "Node 清单已变化，请重新创建升级计划");
    for (var step : planned) {
      long generation = ((Number)step.get("generation")).longValue();
      long expectedGeneration = ((Number)step.get("expected_generation")).longValue();
      Instant lastSeen = instant(step.get("last_seen"));
      if (generation != expectedGeneration || !Boolean.TRUE.equals(step.get("node_present"))
          || !Boolean.TRUE.equals(step.get("ready")) || lastSeen == null
          || lastSeen.isBefore(now.minusSeconds(90)) || !"AVAILABLE".equals(step.get("helper_status")))
        throw fail(409, "K3S_ROLLOUT_PREFLIGHT_FAILED",
            "Node、Host 版本或 Agent Helper 状态已变化，请重新检查计划");
      try {
        JsonNode capabilities = mapper.readTree(String.valueOf(step.get("capabilities")));
        if (!contains(capabilities, "k3s.upgrade"))
          throw fail(409, "K3S_ROLLOUT_PREFLIGHT_FAILED", "计划中的 Host 已不支持 k3s.upgrade");
      } catch (java.io.IOException invalid) {
        throw new IllegalStateException("Stored Host capabilities are invalid", invalid);
      }
    }
    var notReady = query(connection,
        "SELECT node_uid FROM kubernetes_cluster_node WHERE cluster_id=? "
            + "AND node_present=TRUE AND (ready=FALSE OR host_id IS NULL)", clusterId);
    if (!notReady.isEmpty())
      throw fail(409, "K3S_ROLLOUT_PREFLIGHT_FAILED", "所有 K3s Node 必须 Ready 且映射到 Host");
  }

  @GET
  public Response list() {
    String actor = actor();
    List<String> ids = store.transaction(connection -> query(connection,
        "SELECT id FROM k3s_rollout_plan WHERE requested_by=? ORDER BY created_at DESC LIMIT 100",
        actor).stream().map(row -> String.valueOf(row.get("id"))).toList());
    return noStore(Response.ok(Map.of("items", ids.stream().map(id -> view(UUID.fromString(id))).toList())));
  }

  @GET
  @Path("/{planId}")
  public Response get(@PathParam("planId") String planValue) {
    String actor = actor();
    UUID planId = parseUuid(planValue);
    Map<String, Object> plan = view(planId);
    if (!actor.equals(plan.get("requestedBy"))) throw fail(404, "K3S_ROLLOUT_NOT_FOUND", "升级计划不存在");
    return noStore(Response.ok(plan));
  }

  @Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void advancePlans() {
    if (!enabled) return;
    var ids = store.transaction(connection -> query(connection,
        "SELECT id FROM k3s_rollout_plan WHERE state IN ('RUNNING','VERIFYING') "
            + "ORDER BY created_at LIMIT 20").stream()
        .map(row -> (UUID)row.get("id")).toList());
    for (UUID planId : ids) {
      try {
        advance(planId);
      } catch (RuntimeException failure) {
        // A later scheduled pass reconciles persisted work using its idempotency key.
        LOG.warnf("Unable to advance K3s rollout %s (%s)", planId,
            failure.getClass().getSimpleName());
      }
    }
  }

  private void advance(UUID planId) {
    Work work = loadWork(planId);
    if (work == null) return;
    if ("PENDING".equals(work.stepState()) || "SUBMITTING".equals(work.stepState())) {
      store.transaction(connection -> {
        update(connection, "UPDATE k3s_rollout_step SET state='SUBMITTING',updated_at=? "
            + "WHERE plan_id=? AND ordinal=? AND state='PENDING'",
            Timestamp.from(Instant.now()), planId, work.ordinal());
        return null;
      });
      Response response;
      try {
        response = helperOperations.submitPlannedUpgrade(work.hostId(), work.key(),
            work.role(), work.version(), work.generation(),
            operationReason(work), work.actor());
      } catch (RuntimeException failure) {
        String state = failure instanceof jakarta.ws.rs.WebApplicationException webFailure
            && webFailure.getResponse() != null && webFailure.getResponse().getStatus() < 500
                ? "FAILED" : "UNKNOWN";
        finishPlannedFailure(work, state,
            state.equals("UNKNOWN") ? "EXECUTION_OUTCOME_UNKNOWN" : "K3S_UPGRADE_REJECTED",
            state.equals("UNKNOWN") ? "升级任务下发结果未知，计划已停止" : "升级步骤未通过操作准入，计划已停止");
        return;
      }
      @SuppressWarnings("unchecked")
      var operation = (Map<String, Object>)response.getEntity();
      UUID operationId = UUID.fromString(String.valueOf(operation.get("id")));
      store.transaction(connection -> {
        update(connection, "UPDATE k3s_rollout_step SET state='RUNNING',operation_id=?,updated_at=? "
            + "WHERE plan_id=? AND ordinal=? AND state IN ('SUBMITTING','RUNNING')",
            operationId, Timestamp.from(Instant.now()), planId, work.ordinal());
        return null;
      });
      return;
    }
    var operationRows = store.transaction(connection -> query(connection,
        "SELECT id,status,finished_at,error_json FROM operation WHERE id=? OR idempotency_key=? "
            + "ORDER BY created_at DESC LIMIT 1", work.operationId(), work.key()));
    if (operationRows.isEmpty()) return;
    var operation = operationRows.getFirst();
    String state = String.valueOf(operation.get("status"));
    if (!TERMINAL_OPERATION_STATES.contains(state)) return;
    if (!"SUCCEEDED".equals(state)) {
      finishWithOperationState(work, state, operation.get("error_json"));
      return;
    }
    setVerifying(work);
    try {
      clusters.refreshNodes(work.clusterId());
    } catch (RuntimeException unavailable) {
      markVerificationPending(work);
      return;
    }
    verifyAndAdvance(work, instant(operation.get("finished_at")));
  }

  private Work loadWork(UUID planId) {
    return store.transaction(connection -> {
      var plans = query(connection,
          "SELECT id,cluster_id,target_version,reason,requested_by,state,active_ordinal "
              + "FROM k3s_rollout_plan WHERE id=? FOR UPDATE", planId);
      if (plans.isEmpty()) return null;
      var plan = plans.getFirst();
      int ordinal = ((Number)plan.get("active_ordinal")).intValue();
      var steps = query(connection,
          "SELECT ordinal,host_id,node_uid,node_name,role,expected_generation,state,"
              + "idempotency_key,operation_id FROM k3s_rollout_step "
              + "WHERE plan_id=? AND ordinal=? FOR UPDATE", planId, ordinal);
      if (steps.isEmpty()) {
        update(connection, "UPDATE k3s_rollout_plan SET state='SUCCEEDED',finished_at=? "
            + "WHERE id=? AND state IN ('RUNNING','VERIFYING')", Timestamp.from(Instant.now()), planId);
        return null;
      }
      var step = steps.getFirst();
      if (Set.of("SUCCEEDED","FAILED","UNKNOWN").contains(String.valueOf(step.get("state"))))
        return null;
      return new Work(planId, (UUID)plan.get("cluster_id"), (UUID)step.get("host_id"),
          String.valueOf(step.get("node_uid")), String.valueOf(step.get("node_name")),
          String.valueOf(step.get("role")), String.valueOf(plan.get("target_version")),
          String.valueOf(plan.get("reason")), String.valueOf(plan.get("requested_by")),
          String.valueOf(step.get("idempotency_key")),
          ((Number)step.get("expected_generation")).longValue(), ordinal,
          String.valueOf(step.get("state")), (UUID)step.get("operation_id"));
    });
  }

  private void verifyAndAdvance(Work work, Instant operationFinishedAt) {
    var observations = store.transaction(connection -> query(connection,
        "SELECT c.health_status,c.observed_at,n.node_present,n.ready,n.kubelet_version "
            + "FROM kubernetes_cluster c JOIN kubernetes_cluster_node n ON n.cluster_id=c.id "
            + "WHERE c.id=? AND n.host_id=?", work.clusterId(), work.hostId()));
    if (observations.isEmpty()) return;
    var observation = observations.getFirst();
    Instant observedAt = instant(observation.get("observed_at"));
    if (!"HEALTHY".equals(observation.get("health_status")) || observedAt == null
        || operationFinishedAt == null || observedAt.isBefore(operationFinishedAt)
        || !Boolean.TRUE.equals(observation.get("node_present"))
        || !Boolean.TRUE.equals(observation.get("ready"))) return;
    if (!work.version().equals(observation.get("kubelet_version"))) {
      if (Instant.now().isBefore(operationFinishedAt.plusSeconds(120))) {
        markVersionVerificationPending(work, observation.get("kubelet_version"));
      } else {
        finishPlannedFailure(work, "FAILED", "K3S_ROLLOUT_VERSION_UNVERIFIED",
            "Node Ready 后未在 120 秒内观测到目标 kubelet 版本");
      }
      return;
    }
    long notReadyNodes = store.transaction(connection -> {
      var counts = query(connection, "SELECT COUNT(*) AS count FROM kubernetes_cluster_node "
          + "WHERE cluster_id=? AND node_present=TRUE AND ready=FALSE", work.clusterId());
      return ((Number)counts.getFirst().get("count")).longValue();
    });
    if (notReadyNodes != 0) return;
    store.transaction(connection -> {
      query(connection, "SELECT id FROM k3s_rollout_plan WHERE id=? FOR UPDATE", work.planId());
      Instant now = Instant.now();
      update(connection, "UPDATE k3s_rollout_step SET state='SUCCEEDED',updated_at=?,finished_at=? "
          + "WHERE plan_id=? AND ordinal=? AND state='VERIFYING'",
          Timestamp.from(now), Timestamp.from(now), work.planId(), work.ordinal());
      var next = query(connection, "SELECT ordinal FROM k3s_rollout_step "
          + "WHERE plan_id=? AND ordinal>? ORDER BY ordinal LIMIT 1", work.planId(), work.ordinal());
      if (next.isEmpty()) {
        update(connection, "UPDATE k3s_rollout_plan SET state='SUCCEEDED',finished_at=? "
            + "WHERE id=? AND active_ordinal=?", Timestamp.from(now), work.planId(), work.ordinal());
      } else {
        update(connection, "UPDATE k3s_rollout_plan SET state='RUNNING',active_ordinal=? "
            + "WHERE id=? AND active_ordinal=?", next.getFirst().get("ordinal"),
            work.planId(), work.ordinal());
      }
      return null;
    });
  }

  private void setVerifying(Work work) {
    store.transaction(connection -> {
      update(connection, "UPDATE k3s_rollout_plan SET state='VERIFYING' WHERE id=? "
          + "AND state IN ('RUNNING','VERIFYING')", work.planId());
      update(connection, "UPDATE k3s_rollout_step SET state='VERIFYING',updated_at=? "
          + "WHERE plan_id=? AND ordinal=? AND state='RUNNING'",
          Timestamp.from(Instant.now()), work.planId(), work.ordinal());
      return null;
    });
  }

  private void markVerificationPending(Work work) {
    store.transaction(connection -> {
      update(connection, "UPDATE k3s_rollout_plan SET state='VERIFYING',"
          + "error_code='K3S_CLUSTER_OBSERVATION_PENDING',"
          + "error_message='等待恢复 Kubernetes API 并刷新所有 Node 健康状态' WHERE id=?",
          work.planId());
      return null;
    });
  }

  private void markVersionVerificationPending(Work work, Object observedVersion) {
    String detail = observedVersion == null ? "尚无 kubelet 版本观测"
        : "当前 Node 版本为 " + observedVersion + "，目标为 " + work.version();
    store.transaction(connection -> {
      update(connection, "UPDATE k3s_rollout_plan SET state='VERIFYING',"
          + "error_code='K3S_ROLLOUT_VERSION_PENDING',error_message=? WHERE id=?",
          detail, work.planId());
      return null;
    });
  }

  private void finishWithOperationState(Work work, String state, Object errorJson) {
    String rolloutState = "UNKNOWN".equals(state) ? "UNKNOWN" : "FAILED";
    store.transaction(connection -> {
      update(connection, "UPDATE k3s_rollout_step SET state=?,error_code=?,error_message=?,"
          + "updated_at=?,finished_at=? WHERE plan_id=? AND ordinal=?",
          rolloutState, "K3S_UPGRADE_" + state, summarize(errorJson), Timestamp.from(Instant.now()),
          Timestamp.from(Instant.now()), work.planId(), work.ordinal());
      update(connection, "UPDATE k3s_rollout_plan SET state=?,error_code=?,error_message=?,"
          + "finished_at=? WHERE id=?", rolloutState, "K3S_UPGRADE_" + state,
          summarize(errorJson), Timestamp.from(Instant.now()), work.planId());
      return null;
    });
  }

  private void finishPlannedFailure(Work work, String state, String code, String message) {
    store.transaction(connection -> {
      Instant now = Instant.now();
      update(connection, "UPDATE k3s_rollout_step SET state=?,error_code=?,error_message=?,"
          + "updated_at=?,finished_at=? WHERE plan_id=? AND ordinal=?",
          state, code, message, Timestamp.from(now), Timestamp.from(now), work.planId(), work.ordinal());
      update(connection, "UPDATE k3s_rollout_plan SET state=?,error_code=?,error_message=?,"
          + "finished_at=? WHERE id=?", state, code, message, Timestamp.from(now), work.planId());
      return null;
    });
  }

  private Map<String, Object> view(UUID planId) {
    return store.transaction(connection -> {
      var plans = query(connection,
          "SELECT p.*,c.name AS cluster_name FROM k3s_rollout_plan p "
              + "JOIN kubernetes_cluster c ON c.id=p.cluster_id WHERE p.id=?", planId);
      if (plans.isEmpty()) throw fail(404, "K3S_ROLLOUT_NOT_FOUND", "升级计划不存在");
      var plan = plans.getFirst();
      var value = new LinkedHashMap<String, Object>();
      value.put("id", planId.toString());
      value.put("clusterId", String.valueOf(plan.get("cluster_id")));
      value.put("clusterName", plan.get("cluster_name"));
      value.put("targetVersion", plan.get("target_version"));
      value.put("reason", plan.get("reason"));
      value.put("state", plan.get("state"));
      value.put("activeOrdinal", plan.get("active_ordinal"));
      value.put("errorCode", plan.get("error_code"));
      value.put("errorMessage", plan.get("error_message"));
      value.put("requestedBy", String.valueOf(plan.get("requested_by")));
      value.put("createdAt", stamp(plan.get("created_at")));
      value.put("startedAt", stamp(plan.get("started_at")));
      value.put("finishedAt", stamp(plan.get("finished_at")));
      value.put("steps", query(connection,
          "SELECT ordinal,host_id,node_uid,node_name,role,expected_generation,state,operation_id,"
              + "error_code,error_message,created_at,updated_at,finished_at "
              + "FROM k3s_rollout_step WHERE plan_id=? ORDER BY ordinal", planId).stream()
          .map(K3sRolloutResource::stepView).toList());
      return value;
    });
  }

  private static Map<String, Object> stepView(Map<String, Object> row) {
    var value = new LinkedHashMap<String, Object>();
    value.put("ordinal", row.get("ordinal"));
    value.put("hostId", String.valueOf(row.get("host_id")));
    value.put("nodeUid", row.get("node_uid"));
    value.put("nodeName", row.get("node_name"));
    value.put("role", row.get("role"));
    value.put("expectedGeneration", row.get("expected_generation"));
    value.put("state", row.get("state"));
    value.put("operationId", row.get("operation_id") == null ? null : String.valueOf(row.get("operation_id")));
    value.put("errorCode", row.get("error_code"));
    value.put("errorMessage", row.get("error_message"));
    value.put("createdAt", stamp(row.get("created_at")));
    value.put("updatedAt", stamp(row.get("updated_at")));
    value.put("finishedAt", stamp(row.get("finished_at")));
    return value;
  }

  private static String summarize(Object error) {
    if (error == null) return "升级操作没有成功完成";
    String value = String.valueOf(error);
    return value.length() <= 1000 ? value : value.substring(0, 1000);
  }

  private boolean contains(com.fasterxml.jackson.databind.JsonNode array, String value) {
    if (array == null || !array.isArray()) return false;
    for (var item : array) if (item.isTextual() && value.equals(item.asText())) return true;
    return false;
  }

  static boolean compatibleUpgrade(String currentVersion, String targetVersion) {
    var current = VERSION.matcher(currentVersion);
    var target = VERSION.matcher(targetVersion);
    if (!current.matches() || !target.matches()) return false;
    try {
      int currentMajor = Integer.parseInt(current.group(1));
      int currentMinor = Integer.parseInt(current.group(2));
      int currentPatch = Integer.parseInt(current.group(3));
      int currentK3sRevision = Integer.parseInt(current.group(4));
      int targetMajor = Integer.parseInt(target.group(1));
      int targetMinor = Integer.parseInt(target.group(2));
      int targetPatch = Integer.parseInt(target.group(3));
      int targetK3sRevision = Integer.parseInt(target.group(4));
      if (targetMajor != currentMajor || targetMinor < currentMinor
          || targetMinor > currentMinor + 1) return false;
      return targetMinor > currentMinor || targetPatch > currentPatch
          || targetPatch == currentPatch && targetK3sRevision >= currentK3sRevision;
    } catch (NumberFormatException invalid) {
      return false;
    }
  }

  private UUID parseUuid(String value) {
    try { return UUID.fromString(value); }
    catch (IllegalArgumentException invalid) { throw fail(404, "K3S_ROLLOUT_NOT_FOUND", "升级计划或集群不存在"); }
  }

  private String clientIp() {
    return http == null || http.remoteAddress() == null ? null : http.remoteAddress().host();
  }

  private static String operationReason(Work work) {
    String prefix = "K3s rollout " + work.planId() + " step " + (work.ordinal() + 1) + ": ";
    int reasonLimit = 500 - prefix.length();
    String detail = work.reason().substring(0, Math.min(work.reason().length(), reasonLimit));
    return prefix + detail;
  }

  private Response noStore(Response.ResponseBuilder response) {
    return response.header("Cache-Control", "no-store").build();
  }

  private static Instant instant(Object value) {
    if (value == null) return null;
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof OffsetDateTime timestamp) return timestamp.toInstant();
    if (value instanceof Instant timestamp) return timestamp;
    if (value instanceof java.util.Date timestamp) return timestamp.toInstant();
    if (value instanceof Number timestamp) return Instant.ofEpochMilli(timestamp.longValue());
    throw new IllegalStateException("Unsupported timestamp value");
  }

  private static String stamp(Object value) {
    Instant timestamp = instant(value);
    return timestamp == null ? null : timestamp.toString();
  }
}
