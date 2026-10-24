package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;
import io.quarkus.scheduler.Scheduled;

/** Persists ordered blue/green releases for ordinary Docker or Podman hosts. */
@ApplicationScoped
public class HostApplicationReleaseService {
  private static final Logger LOG = Logger.getLogger(HostApplicationReleaseService.class);
  private static final Pattern NAME = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern HOSTNAME = Pattern.compile(
      "(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)*"
          + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
  private static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/:@+-]{0,511}");
  private static final Set<String> TERMINAL_OPERATIONS =
      Set.of("SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN");
  private static final String[] REQUIRED_CAPABILITIES = {
      "container.deploy", "container.start", "container.stop", "nginx.route.set"};

  @Inject AuthStore store;
  @Inject ObjectMapper mapper;
  @Inject HostHelperOperationResource helperOperations;

  public record TargetRequest(UUID hostId, String listenAddress, Long expectedGeneration) {}

  public record CreateRequest(String name, String stableImage, String image, Integer containerPort,
      String serverName, Integer listenPort, String healthPath, Integer batchSize,
      List<TargetRequest> targets, String reason) {}

  public record TargetView(int ordinal, String hostId, String listenAddress, String routeName,
      int stablePort, int candidatePort, String state, String detail, String operationId,
      Long expectedGeneration) {}

  public record PlanView(String id, String name, String stableImage, String image,
      int containerPort, String serverName, int listenPort, String healthPath, int batchSize,
      int activeOrdinal, String state, String reason, String errorCode, String errorMessage,
      String requestedBy, Instant createdAt, Instant startedAt, Instant finishedAt,
      List<TargetView> targets) {}

  private record Work(UUID planId, String actor, String applicationName, String stableImage,
      String image, int containerPort, String serverName, int listenPort, String healthPath,
      String reason, String planState, int activeOrdinal, int batchSize, int targetCount,
      int ordinal, UUID hostId, long generation, String listenAddress, String routeName,
      String stableContainer, int stablePort, String candidateContainer, int candidatePort,
      boolean stableExisted, String targetState, UUID operationId) {}

  public PlanView create(String actor, String remoteAddress, CreateRequest input) {
    validate(input);
    UUID planId = UUID.randomUUID();
    Instant now = Instant.now();
    String application = input.name().trim();
    String stableImage = input.stableImage().trim();
    String image = input.image().trim();
    String reason = input.reason().trim();
    List<TargetRequest> targets = input.targets();
    Set<UUID> uniqueHosts = new HashSet<>();
    for (TargetRequest target : targets) {
      if (target == null || target.hostId() == null || !uniqueHosts.add(target.hostId())
          || target.expectedGeneration() == null || target.expectedGeneration() < 1
          || !validHostAddress(target.listenAddress())) {
        throw fail(422, "VALIDATION_FAILED", "目标主机、监听地址或主机版本无效");
      }
    }
    store.transaction(connection -> {
      for (UUID hostId : targets.stream().map(TargetRequest::hostId).sorted().toList()) {
        query(connection, "SELECT id FROM host WHERE id=? FOR UPDATE", hostId);
      }
      update(connection,
          "INSERT INTO host_application_release_plan (id,application_name,stable_image,image,"
              + "container_port,server_name,listen_port,health_path,batch_size,active_ordinal,state,"
              + "reason,requested_by,created_at) VALUES (?,?,?,?,?,?,?,?,?,0,'DRAFT',?,?,?)",
          planId, application, stableImage, image, input.containerPort(), input.serverName().trim(),
          input.listenPort(), input.healthPath().trim(), input.batchSize(), reason, actor,
          Timestamp.from(now));
      String suffix = planId.toString().replace("-", "").substring(0, 10);
      for (int ordinal = 0; ordinal < targets.size(); ordinal++) {
        TargetRequest target = targets.get(ordinal);
        requireEligibleHost(connection, target.hostId(), target.expectedGeneration());
        var active = query(connection,
            "SELECT p.id FROM host_application_release_plan p "
                + "JOIN host_application_release_target t ON t.release_id=p.id "
                + "WHERE p.application_name=? AND t.host_id=? "
                + "AND p.state IN ('DRAFT','RUNNING','PAUSED','ROLLING_BACK','UNKNOWN') LIMIT 1",
            application, target.hostId());
        if (!active.isEmpty()) throw conflict("此应用在目标主机上已有未结束的发布计划");
        var existingRows = query(connection,
            "SELECT * FROM host_application_deployment WHERE host_id=? AND application_name=? FOR UPDATE",
            target.hostId(), application);
        Map<String, Object> existing = existingRows.isEmpty() ? null : existingRows.getFirst();
        boolean stableExisted = existing != null;
        String stableContainer = "npapp-" + suffix + "-" + ordinal + "-s";
        int stablePort;
        if (stableExisted) {
          if (!actor.equals(String.valueOf(existing.get("requested_by")))
              || !stableImage.equals(String.valueOf(existing.get("image")))) {
            throw conflict("稳定镜像或发布所有者与此主机上最近成功的应用部署不一致");
          }
          if (!application.equals(String.valueOf(existing.get("application_name")))
              || input.containerPort() != number(existing.get("container_port"), -1)
              || input.listenPort() != number(existing.get("listen_port"), -1)
              || !input.serverName().trim().equals(String.valueOf(existing.get("server_name")))
              || !input.healthPath().trim().equals(String.valueOf(existing.get("health_path")))
              || !target.listenAddress().equals(String.valueOf(existing.get("listen_address")))) {
            throw conflict("应用路由参数必须与当前稳定部署保持一致");
          }
          stableContainer = String.valueOf(existing.get("container_name"));
          stablePort = number(existing.get("host_port"), -1);
        } else {
          stablePort = allocatePort(connection, target.hostId(), planId, ordinal, Set.of());
        }
        int candidatePort = allocatePort(connection, target.hostId(), planId, ordinal,
            Set.of(stablePort));
        String routeName = routeName(target.hostId(), application);
        String candidateContainer = "npapp-" + suffix + "-" + ordinal + "-c";
        String state = stableExisted ? "BASELINE_START" : "BASELINE_DEPLOY";
        update(connection,
            "INSERT INTO host_application_release_target (release_id,ordinal,application_name,host_id,"
                + "expected_generation,listen_address,route_name,stable_container_name,stable_image,"
                + "stable_port,candidate_container_name,candidate_port,stable_existed,state,updated_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            planId, ordinal, application, target.hostId(), target.expectedGeneration(),
            target.listenAddress(), routeName, stableContainer, stableImage, stablePort,
            candidateContainer, candidatePort, stableExisted, state, Timestamp.from(now));
      }
      audit(connection, actor, "HOST_APPLICATION_RELEASE_CREATED", "host_application_release_plan",
          planId.toString(), "SUCCEEDED", remoteAddress,
          "创建普通主机蓝绿发布计划 " + application + "，目标主机数 " + targets.size());
      return null;
    });
    return get(planId, actor);
  }

  public List<PlanView> list(String actor) {
    List<UUID> ids = store.transaction(connection -> query(connection,
        "SELECT id FROM host_application_release_plan WHERE requested_by=? "
            + "ORDER BY created_at DESC LIMIT 100", actor)
        .stream().map(row -> (UUID)row.get("id")).toList());
    return ids.stream().map(id -> get(id, actor)).toList();
  }

  public PlanView get(UUID id, String actor) {
    Map<String, Object> plan = store.transaction(connection -> {
      var rows = query(connection,
          "SELECT * FROM host_application_release_plan WHERE id=? AND requested_by=?", id, actor);
      return rows.isEmpty() ? null : rows.getFirst();
    });
    if (plan == null) throw missing();
    List<TargetView> targets = store.transaction(connection -> query(connection,
        "SELECT ordinal,host_id,listen_address,route_name,stable_port,candidate_port,state,detail,"
            + "operation_id,expected_generation FROM host_application_release_target "
            + "WHERE release_id=? ORDER BY ordinal", id).stream().map(row -> new TargetView(
                number(row.get("ordinal"), 0), String.valueOf(row.get("host_id")),
                String.valueOf(row.get("listen_address")), String.valueOf(row.get("route_name")),
                number(row.get("stable_port"), 0), number(row.get("candidate_port"), 0),
                String.valueOf(row.get("state")), nullableString(row.get("detail")),
                row.get("operation_id") == null ? null : String.valueOf(row.get("operation_id")),
                (long)number(row.get("expected_generation"), 0))).toList());
    return new PlanView(String.valueOf(plan.get("id")), String.valueOf(plan.get("application_name")),
        String.valueOf(plan.get("stable_image")), String.valueOf(plan.get("image")),
        number(plan.get("container_port"), 0), String.valueOf(plan.get("server_name")),
        number(plan.get("listen_port"), 0), String.valueOf(plan.get("health_path")),
        number(plan.get("batch_size"), 0), number(plan.get("active_ordinal"), 0),
        String.valueOf(plan.get("state")), String.valueOf(plan.get("reason")),
        nullableString(plan.get("error_code")), nullableString(plan.get("error_message")),
        actor, instant(plan.get("created_at")), instant(plan.get("started_at")),
        instant(plan.get("finished_at")), targets);
  }

  public PlanView start(UUID id, String actor, String remoteAddress) {
    changePlan(id, actor, remoteAddress, "DRAFT", "RUNNING", "HOST_APPLICATION_RELEASE_STARTED");
    return get(id, actor);
  }

  public PlanView pause(UUID id, String actor, String remoteAddress) {
    store.transaction(connection -> {
      Map<String, Object> plan = ownedPlan(connection, id, actor, true);
      if (!"RUNNING".equals(plan.get("state"))) throw conflict("仅运行中的主机发布可以暂停");
      var active = query(connection,
          "SELECT t.operation_id,o.status FROM host_application_release_target t "
              + "LEFT JOIN operation o ON o.id=t.operation_id WHERE t.release_id=? "
              + "AND t.ordinal=?", id, plan.get("active_ordinal"));
      if (!active.isEmpty() && active.getFirst().get("operation_id") != null
          && !TERMINAL_OPERATIONS.contains(String.valueOf(active.getFirst().get("status")))) {
        throw conflict("当前主机 Helper 操作尚未完成，不能暂停");
      }
      update(connection, "UPDATE host_application_release_plan SET state='PAUSED' WHERE id=?", id);
      audit(connection, actor, "HOST_APPLICATION_RELEASE_PAUSED", "host_application_release_plan",
          id.toString(), "SUCCEEDED", remoteAddress, "暂停主机蓝绿发布");
      return null;
    });
    return get(id, actor);
  }

  public PlanView resume(UUID id, String actor, String remoteAddress) {
    changePlan(id, actor, remoteAddress, "PAUSED", "RUNNING",
        "HOST_APPLICATION_RELEASE_RESUMED");
    return get(id, actor);
  }

  public PlanView reconcile(UUID id, String actor, String remoteAddress) {
    store.transaction(connection -> {
      Map<String, Object> plan = ownedPlan(connection, id, actor, true);
      if (!"UNKNOWN".equals(plan.get("state"))) throw conflict("仅结果未知的主机发布可以对账");
      update(connection, "UPDATE host_application_release_plan SET state='RUNNING' WHERE id=?", id);
      audit(connection, actor, "HOST_APPLICATION_RELEASE_RECONCILE_REQUESTED",
          "host_application_release_plan", id.toString(), "ACCEPTED", remoteAddress,
          "请求根据同一 Helper 操作 ID 对账");
      return null;
    });
    Work work = loadWork(id);
    if (work != null && isOperationStage(work.targetState())) submitStage(work);
    return get(id, actor);
  }

  public PlanView rollback(UUID id, String actor, String remoteAddress) {
    store.transaction(connection -> {
      Map<String, Object> plan = ownedPlan(connection, id, actor, true);
      String state = String.valueOf(plan.get("state"));
      if (!Set.of("PAUSED", "SUCCEEDED", "FAILED").contains(state)) {
        throw conflict("回滚前需暂停发布或等待发布结束");
      }
      var pending = query(connection,
          "SELECT o.id FROM host_application_release_target t JOIN operation o ON o.id=t.operation_id "
              + "WHERE t.release_id=? AND o.status IN ('PENDING','RUNNING','UNKNOWN') LIMIT 1", id);
      if (!pending.isEmpty()) throw conflict("目标主机仍有未确认的 Helper 操作，暂不能回滚");
      var last = query(connection,
          "SELECT MAX(ordinal) AS ordinal FROM host_application_release_target "
              + "WHERE release_id=? AND state='SUCCEEDED'", id);
      int ordinal = last.isEmpty() || last.getFirst().get("ordinal") == null
          ? -1 : number(last.getFirst().get("ordinal"), -1);
      if (ordinal < 0) {
        update(connection,
            "UPDATE host_application_release_plan SET state='ROLLED_BACK',finished_at=? WHERE id=?",
            Timestamp.from(Instant.now()), id);
      } else {
        update(connection,
            "UPDATE host_application_release_plan SET state='ROLLING_BACK',active_ordinal=?,"
                + "error_code=NULL,error_message=NULL,finished_at=NULL WHERE id=?",
            ordinal, id);
      }
      audit(connection, actor, "HOST_APPLICATION_RELEASE_ROLLBACK_REQUESTED",
          "host_application_release_plan", id.toString(), "ACCEPTED", remoteAddress,
          "请求按逆序恢复此前已成功的主机路由");
      return null;
    });
    return get(id, actor);
  }

  @Scheduled(every = "5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void advancePlans() {
    List<UUID> ids = store.transaction(connection -> query(connection,
        "SELECT id FROM host_application_release_plan WHERE state IN ('RUNNING','ROLLING_BACK','UNKNOWN') "
            + "ORDER BY created_at LIMIT 40").stream()
        .map(row -> (UUID)row.get("id")).toList());
    for (UUID id : ids) {
      try {
        advance(id);
      } catch (RuntimeException error) {
        LOG.warnf("Unable to advance host application release %s (%s)", id,
            error.getClass().getSimpleName());
      }
    }
  }

  private void advance(UUID planId) {
    Work work = loadWork(planId);
    if (work == null) return;
    if ("ROLLING_BACK".equals(work.planState())) {
      if (!work.targetState().startsWith("MANUAL_ROLLBACK_")) {
        updateTarget(planId, work.ordinal(), "MANUAL_ROLLBACK_START", null,
            "按逆序恢复最近成功的稳定实例");
        work = loadWork(planId);
        if (work == null) return;
      }
    } else if (!"RUNNING".equals(work.planState()) && !"UNKNOWN".equals(work.planState())) {
      return;
    }

    if (!isOperationStage(work.targetState())) {
      return;
    }
    if (work.operationId() == null) {
      submitStage(work);
      return;
    }
    UUID operationId = work.operationId();
    Map<String, Object> operation = store.transaction(connection -> {
      var rows = query(connection, "SELECT id,status,error_json,result_json FROM operation WHERE id=?",
          operationId);
      return rows.isEmpty() ? null : rows.getFirst();
    });
    if (operation == null) return;
    String operationState = String.valueOf(operation.get("status"));
    if (!TERMINAL_OPERATIONS.contains(operationState)) return;
    if ("UNKNOWN".equals(operationState)) {
      if (!"UNKNOWN".equals(work.planState())) {
        markUnknown(work, "Helper 操作结果未知；保留当前路由和两个实例，等待同一操作 ID 对账");
      }
      return;
    }
    if ("UNKNOWN".equals(work.planState())) {
      UUID resolvedPlanId = work.planId();
      store.transaction(connection -> {
        update(connection,
            "UPDATE host_application_release_plan SET state='RUNNING',error_code=NULL,"
                + "error_message=NULL WHERE id=? AND state='UNKNOWN'",
            resolvedPlanId);
        return null;
      });
    }
    if ("SUCCEEDED".equals(operationState)) {
      Integer routeStatus = routeStatus(work, nullableString(operation.get("result_json")));
      if (isRouteStage(work.targetState()) && routeStatus == null) {
        if ("BASELINE_ROUTE".equals(work.targetState())) {
          saveError(work.planId(), "BASELINE_ROUTE_PROOF_INVALID",
              "稳定路由操作缺少匹配的 HTTP 健康证据；重新应用稳定路由");
          updateTarget(work.planId(), work.ordinal(), "AUTO_ROLLBACK_START", null,
              "稳定路由证据无效；重新启动稳定实例并恢复稳定路由");
        } else {
          operationFailed(work,
              "Nginx Helper operation did not return matching route and HTTP health evidence");
        }
      } else {
        operationSucceeded(work, routeStatus);
      }
    } else {
      operationFailed(work, nullableString(operation.get("error_json")));
    }
  }

  private void submitStage(Work work) {
    String stage = work.targetState();
    String capability;
    String target;
    JsonNode payload = null;
    switch (stage) {
      case "BASELINE_DEPLOY" -> {
        capability = "container.deploy";
        target = work.stableContainer();
        payload = mapper.createObjectNode().put("image", work.stableImage())
            .put("hostPort", work.stablePort()).put("containerPort", work.containerPort());
      }
      case "BASELINE_START", "AUTO_ROLLBACK_START", "MANUAL_ROLLBACK_START" -> {
        capability = "container.start";
        target = work.stableContainer();
      }
      case "BASELINE_ROUTE", "AUTO_ROLLBACK_ROUTE", "MANUAL_ROLLBACK_ROUTE" -> {
        capability = "nginx.route.set";
        target = work.routeName();
        payload = routePayload(work, 100, 0);
      }
      case "CANDIDATE_DEPLOY" -> {
        capability = "container.deploy";
        target = work.candidateContainer();
        payload = mapper.createObjectNode().put("image", work.image())
            .put("hostPort", work.candidatePort()).put("containerPort", work.containerPort());
      }
      case "CANDIDATE_ROUTE" -> {
        capability = "nginx.route.set";
        target = work.routeName();
        payload = routePayload(work, 0, 100);
      }
      case "STABLE_STOP", "AUTO_ROLLBACK_STOP", "MANUAL_ROLLBACK_STOP" -> {
        capability = "container.stop";
        target = stage.equals("STABLE_STOP") ? work.stableContainer() : work.candidateContainer();
      }
      default -> { return; }
    }
    String key = "np-hostrel:" + work.planId() + ":" + work.ordinal() + ":" + stage.toLowerCase();
    try {
      Response response = helperOperations.submitAs(work.hostId(), key,
          new HostHelperOperationResource.HelperOperationRequest(capability, target, payload,
              "主机应用发布 " + work.applicationName() + "：" + work.reason(), 120,
              work.generation()), work.actor(), null, true);
      @SuppressWarnings("unchecked")
      Map<String, Object> body = (Map<String, Object>)response.getEntity();
      UUID operationId = UUID.fromString(String.valueOf(body.get("id")));
      store.transaction(connection -> {
        update(connection,
            "UPDATE host_application_release_target SET operation_id=?,updated_at=? "
                + "WHERE release_id=? AND ordinal=? AND state=? AND operation_id IS NULL",
            operationId, Timestamp.from(Instant.now()), work.planId(), work.ordinal(), stage);
        return null;
      });
    } catch (RuntimeException rejected) {
      if (rejected instanceof jakarta.ws.rs.WebApplicationException web
          && web.getResponse() != null && web.getResponse().getStatus() < 500) {
        operationFailed(work, "Helper operation admission failed with HTTP "
            + web.getResponse().getStatus());
      } else {
        String operationKey = key;
        UUID operationId = store.transaction(connection -> {
          var rows = query(connection, "SELECT id FROM operation WHERE idempotency_key=?", operationKey);
          return rows.isEmpty() ? null : (UUID)rows.getFirst().get("id");
        });
        if (operationId != null) {
          store.transaction(connection -> {
            update(connection,
                "UPDATE host_application_release_target SET operation_id=?,updated_at=? "
                    + "WHERE release_id=? AND ordinal=? AND state=? AND operation_id IS NULL",
                operationId, Timestamp.from(Instant.now()), work.planId(), work.ordinal(), stage);
            return null;
          });
        }
        markUnknown(work, "Agent Gateway 未确认主机操作结果；保留当前路由和实例");
      }
    }
  }

  private JsonNode routePayload(Work work, int stableWeight, int candidateWeight) {
    var payload = mapper.createObjectNode()
        .put("listenAddress", work.listenAddress())
        .put("listenPort", work.listenPort())
        .put("serverName", work.serverName())
        .put("healthPath", work.healthPath());
    var backends = payload.putArray("backends");
    backends.addObject().put("name", "stable").put("port", work.stablePort())
        .put("weight", stableWeight);
    backends.addObject().put("name", "canary").put("port", work.candidatePort())
        .put("weight", candidateWeight);
    return payload;
  }

  private void operationSucceeded(Work work, Integer routeStatus) {
    switch (work.targetState()) {
      case "BASELINE_DEPLOY", "BASELINE_START" -> updateTarget(work.planId(), work.ordinal(),
          "BASELINE_ROUTE", null, "稳定实例已就绪；将先验证稳定路由");
      case "BASELINE_ROUTE" -> updateTarget(work.planId(), work.ordinal(), "CANDIDATE_DEPLOY",
          null, "稳定路由 HTTP " + routeStatus + " 健康；开始部署候选实例");
      case "CANDIDATE_DEPLOY" -> updateTarget(work.planId(), work.ordinal(), "CANDIDATE_ROUTE",
          null, "候选实例健康；将切换 HTTP 路由");
      case "CANDIDATE_ROUTE" -> updateTarget(work.planId(), work.ordinal(), "STABLE_STOP",
          null, "候选路由 HTTP " + routeStatus + " 健康；停止旧稳定实例");
      case "STABLE_STOP" -> completeTarget(work, true, null);
      case "AUTO_ROLLBACK_START" -> updateTarget(work.planId(), work.ordinal(),
          "AUTO_ROLLBACK_ROUTE", null, "稳定实例已启动；恢复稳定路由");
      case "AUTO_ROLLBACK_ROUTE" -> updateTarget(work.planId(), work.ordinal(),
          "AUTO_ROLLBACK_STOP", null, "稳定路由 HTTP " + routeStatus + " 已恢复；停止失败候选实例");
      case "AUTO_ROLLBACK_STOP" -> completeTarget(work, false,
          "候选发布失败；稳定路由已恢复");
      case "MANUAL_ROLLBACK_START" -> updateTarget(work.planId(), work.ordinal(),
          "MANUAL_ROLLBACK_ROUTE", null, "原稳定实例已启动；恢复旧版本路由");
      case "MANUAL_ROLLBACK_ROUTE" -> updateTarget(work.planId(), work.ordinal(),
          "MANUAL_ROLLBACK_STOP", null, "旧版本路由 HTTP " + routeStatus + " 已验证；停止候选实例");
      case "MANUAL_ROLLBACK_STOP" -> completeManualRollback(work);
      default -> { }
    }
  }

  private void operationFailed(Work work, String detail) {
    String message = safeDetail(detail);
    switch (work.targetState()) {
      case "BASELINE_DEPLOY" -> finishPlanFailure(work, "BASELINE_DEPLOY_FAILED",
          "稳定实例部署失败：" + message);
      case "BASELINE_ROUTE" -> finishPlanFailure(work, "BASELINE_ROUTE_FAILED",
          "稳定路由未通过健康验证：" + message);
      case "AUTO_ROLLBACK_START", "AUTO_ROLLBACK_ROUTE", "MANUAL_ROLLBACK_START",
          "MANUAL_ROLLBACK_ROUTE" -> markUnknown(work,
              "路由补偿步骤失败，当前业务可用性未确认；" + message);
      case "AUTO_ROLLBACK_STOP" -> completeTarget(work, false,
          "稳定路由已恢复；候选实例清理失败：" + message);
      case "MANUAL_ROLLBACK_STOP" -> completeManualRollback(work,
          "旧版本路由已恢复；候选实例清理失败：" + message);
      case "BASELINE_START" -> markUnknown(work,
          "稳定实例无法启动，现有路由可用性未确认：" + message);
      case "STABLE_STOP", "CANDIDATE_ROUTE", "CANDIDATE_DEPLOY" -> {
        saveError(work.planId(), "HOST_APPLICATION_RELEASE_FAILED",
            "候选发布步骤失败，开始恢复稳定路由：" + message);
        updateTarget(work.planId(), work.ordinal(), "AUTO_ROLLBACK_START", null,
            "候选步骤失败；开始恢复稳定实例和路由");
      }
      default -> finishPlanFailure(work, "HOST_APPLICATION_RELEASE_FAILED", message);
    }
  }

  private void completeTarget(Work work, boolean success, String detail) {
    Instant now = Instant.now();
    store.transaction(connection -> {
      if (success) {
        upsertDeployment(connection, work, true);
        update(connection,
            "UPDATE host_application_release_target SET state='SUCCEEDED',operation_id=NULL,"
                + "detail=?,updated_at=? WHERE release_id=? AND ordinal=?",
            "候选实例与路由健康，旧实例已停止", Timestamp.from(now), work.planId(), work.ordinal());
        int next = work.ordinal() + 1;
        String nextState = next >= work.targetCount() ? "SUCCEEDED"
            : next % work.batchSize() == 0 ? "PAUSED" : "RUNNING";
        update(connection,
            "UPDATE host_application_release_plan SET active_ordinal=?,state=?,finished_at=? "
                + "WHERE id=?",
            next, nextState, "SUCCEEDED".equals(nextState) ? Timestamp.from(now) : null,
            work.planId());
        audit(connection, work.actor(), "HOST_APPLICATION_RELEASE_HOST_SUCCEEDED",
            "host_application_release_target", work.planId() + ":" + work.ordinal(), "SUCCEEDED",
            null, "主机 " + work.hostId() + " 已切换到候选镜像 " + work.image());
      } else {
        upsertDeployment(connection, work, false);
        update(connection,
            "UPDATE host_application_release_target SET state='FAILED',operation_id=NULL,"
                + "detail=?,updated_at=? WHERE release_id=? AND ordinal=?",
            detail, Timestamp.from(now), work.planId(), work.ordinal());
        update(connection,
            "UPDATE host_application_release_plan SET state='FAILED',finished_at=? WHERE id=?",
            Timestamp.from(now), work.planId());
        audit(connection, work.actor(), "HOST_APPLICATION_RELEASE_HOST_ROLLED_BACK",
            "host_application_release_target", work.planId() + ":" + work.ordinal(), "FAILED",
            null, detail == null ? "稳定路由已恢复，候选发布失败" : detail);
      }
      return null;
    });
  }

  private void completeManualRollback(Work work) {
    completeManualRollback(work, "已恢复稳定镜像和路由");
  }

  private void completeManualRollback(Work work, String detail) {
    Instant now = Instant.now();
    store.transaction(connection -> {
      upsertDeployment(connection, work, false);
      update(connection,
          "UPDATE host_application_release_target SET state='ROLLED_BACK',operation_id=NULL,"
              + "detail=?,updated_at=? WHERE release_id=? AND ordinal=?",
          detail, Timestamp.from(now), work.planId(), work.ordinal());
      var previous = query(connection,
          "SELECT MAX(ordinal) AS ordinal FROM host_application_release_target "
              + "WHERE release_id=? AND ordinal<? AND state='SUCCEEDED'",
          work.planId(), work.ordinal());
      int next = previous.isEmpty() || previous.getFirst().get("ordinal") == null
          ? -1 : number(previous.getFirst().get("ordinal"), -1);
      if (next < 0) {
        update(connection,
            "UPDATE host_application_release_plan SET state='ROLLED_BACK',active_ordinal=0,"
                + "finished_at=? WHERE id=?", Timestamp.from(now), work.planId());
      } else {
        update(connection,
            "UPDATE host_application_release_plan SET active_ordinal=?,state='ROLLING_BACK' WHERE id=?",
            next, work.planId());
      }
      audit(connection, work.actor(), "HOST_APPLICATION_RELEASE_HOST_ROLLED_BACK",
          "host_application_release_target", work.planId() + ":" + work.ordinal(), "SUCCEEDED",
          null, detail);
      return null;
    });
  }

  private void finishPlanFailure(Work work, String code, String message) {
    Instant now = Instant.now();
    store.transaction(connection -> {
      update(connection,
          "UPDATE host_application_release_target SET state='FAILED',operation_id=NULL,detail=?,updated_at=? "
              + "WHERE release_id=? AND ordinal=?",
          message, Timestamp.from(now), work.planId(), work.ordinal());
      update(connection,
          "UPDATE host_application_release_plan SET state=?,error_code=?,error_message=?,finished_at=? "
              + "WHERE id=?",
          "FAILED", code, message, Timestamp.from(now), work.planId());
      audit(connection, work.actor(), "HOST_APPLICATION_RELEASE_FAILED",
          "host_application_release_plan", work.planId().toString(), "FAILED", null, message);
      return null;
    });
  }

  private void markUnknown(Work work, String detail) {
    Instant now = Instant.now();
    store.transaction(connection -> {
      update(connection,
          "UPDATE host_application_release_plan SET state='UNKNOWN',error_code='EXECUTION_OUTCOME_UNKNOWN',"
              + "error_message=?,finished_at=NULL WHERE id=?", detail, work.planId());
      update(connection,
          "UPDATE host_application_release_target SET detail=?,updated_at=? "
              + "WHERE release_id=? AND ordinal=?", detail, Timestamp.from(now),
          work.planId(), work.ordinal());
      audit(connection, work.actor(), "HOST_APPLICATION_RELEASE_OUTCOME_UNKNOWN",
          "host_application_release_plan", work.planId().toString(), "UNKNOWN", null, detail);
      return null;
    });
  }

  private void saveError(UUID planId, String code, String message) {
    store.transaction(connection -> {
      update(connection,
          "UPDATE host_application_release_plan SET error_code=?,error_message=? WHERE id=?",
          code, message, planId);
      return null;
    });
  }

  private void updateTarget(UUID planId, int ordinal, String state, UUID operationId, String detail) {
    store.transaction(connection -> {
      update(connection,
          "UPDATE host_application_release_target SET state=?,operation_id=?,detail=?,updated_at=? "
              + "WHERE release_id=? AND ordinal=?",
          state, operationId, detail, Timestamp.from(Instant.now()), planId, ordinal);
      return null;
    });
  }

  private void upsertDeployment(java.sql.Connection connection, Work work, boolean candidate)
      throws java.sql.SQLException {
    String image = candidate ? work.image() : work.stableImage();
    String container = candidate ? work.candidateContainer() : work.stableContainer();
    int port = candidate ? work.candidatePort() : work.stablePort();
    var existing = query(connection,
        "SELECT host_id FROM host_application_deployment WHERE host_id=? AND application_name=? FOR UPDATE",
        work.hostId(), work.applicationName());
    if (existing.isEmpty()) {
      update(connection,
          "INSERT INTO host_application_deployment (host_id,application_name,requested_by,image,"
              + "container_name,host_port,container_port,route_name,listen_address,listen_port,"
              + "server_name,health_path,generation,release_id,updated_at) "
              + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          work.hostId(), work.applicationName(), work.actor(), image, container, port,
          work.containerPort(), work.routeName(), work.listenAddress(), work.listenPort(),
          work.serverName(), work.healthPath(), work.generation(), work.planId(),
          Timestamp.from(Instant.now()));
    } else {
      update(connection,
          "UPDATE host_application_deployment SET requested_by=?,image=?,container_name=?,host_port=?,"
              + "container_port=?,route_name=?,listen_address=?,listen_port=?,server_name=?,health_path=?,"
              + "generation=?,release_id=?,updated_at=? WHERE host_id=? AND application_name=?",
          work.actor(), image, container, port, work.containerPort(), work.routeName(),
          work.listenAddress(), work.listenPort(), work.serverName(), work.healthPath(),
          work.generation(), work.planId(), Timestamp.from(Instant.now()),
          work.hostId(), work.applicationName());
    }
  }

  private Work loadWork(UUID planId) {
    return store.transaction(connection -> {
      var plans = query(connection, "SELECT * FROM host_application_release_plan WHERE id=?", planId);
      if (plans.isEmpty()) return null;
      Map<String, Object> plan = plans.getFirst();
      String planState = String.valueOf(plan.get("state"));
      int ordinal = number(plan.get("active_ordinal"), 0);
      List<Map<String, Object>> targets = query(connection,
          "SELECT * FROM host_application_release_target WHERE release_id=? AND ordinal=?",
          planId, ordinal);
      if (targets.isEmpty()) return null;
      Map<String, Object> target = targets.getFirst();
      return new Work(planId, String.valueOf(plan.get("requested_by")),
          String.valueOf(plan.get("application_name")), String.valueOf(plan.get("stable_image")),
          String.valueOf(plan.get("image")), number(plan.get("container_port"), 0),
          String.valueOf(plan.get("server_name")), number(plan.get("listen_port"), 0),
          String.valueOf(plan.get("health_path")), String.valueOf(plan.get("reason")), planState,
          ordinal, number(plan.get("batch_size"), 1),
          number(query(connection,
              "SELECT COUNT(*) AS count FROM host_application_release_target WHERE release_id=?", planId)
              .getFirst().get("count"), 0),
          ordinal, (UUID)target.get("host_id"), number(target.get("expected_generation"), 0),
          String.valueOf(target.get("listen_address")), String.valueOf(target.get("route_name")),
          String.valueOf(target.get("stable_container_name")), number(target.get("stable_port"), 0),
          String.valueOf(target.get("candidate_container_name")),
          number(target.get("candidate_port"), 0), Boolean.TRUE.equals(target.get("stable_existed")),
          String.valueOf(target.get("state")), target.get("operation_id") == null
              ? null : (UUID)target.get("operation_id"));
    });
  }

  private boolean isOperationStage(String state) {
    return Set.of("BASELINE_DEPLOY", "BASELINE_START", "BASELINE_ROUTE", "CANDIDATE_DEPLOY",
        "CANDIDATE_ROUTE", "STABLE_STOP", "AUTO_ROLLBACK_START", "AUTO_ROLLBACK_ROUTE",
        "AUTO_ROLLBACK_STOP", "MANUAL_ROLLBACK_START", "MANUAL_ROLLBACK_ROUTE",
        "MANUAL_ROLLBACK_STOP").contains(state);
  }

  private boolean isRouteStage(String state) {
    return Set.of("BASELINE_ROUTE", "CANDIDATE_ROUTE", "AUTO_ROLLBACK_ROUTE",
        "MANUAL_ROLLBACK_ROUTE").contains(state);
  }

  private Integer routeStatus(Work work, String resultJson) {
    if (!isRouteStage(work.targetState()) || resultJson == null) return null;
    try {
      JsonNode result = mapper.readTree(resultJson);
      int stableWeight = "CANDIDATE_ROUTE".equals(work.targetState()) ? 0 : 100;
      int candidateWeight = "CANDIDATE_ROUTE".equals(work.targetState()) ? 100 : 0;
      JsonNode routeResult = unwrapRouteOperationResult(result, mapper);
      if (validRouteOperationResult(routeResult, work.routeName(), work.listenAddress(),
          work.listenPort(), stableWeight, candidateWeight)) {
        return routeResult.path("routedHttpStatus").intValue();
      }
    } catch (java.io.IOException ignored) {
      return null;
    }
    return null;
  }

  static JsonNode unwrapRouteOperationResult(JsonNode result, ObjectMapper mapper)
      throws java.io.IOException {
    if (result == null || !result.isObject()) return null;
    if (!result.has("capability") && !result.has("target") && !result.has("output")) return result;
    if (!"nginx.route.set".equals(result.path("capability").asText())
        || !result.path("target").isTextual() || !result.path("output").isTextual()) return null;
    return mapper.readTree(result.path("output").asText());
  }

  static boolean validRouteOperationResult(JsonNode result, String routeName,
      String listenAddress, int listenPort, int stableWeight, int candidateWeight) {
    if (result == null || !result.isObject() || !routeName.equals(result.path("route").asText())
        || !listenAddress.equals(result.path("listenAddress").asText())
        || result.path("listenPort").intValue() != listenPort
        || !result.path("configSha256").asText().matches("[a-f0-9]{64}")) return false;
    int status = result.path("routedHttpStatus").intValue();
    JsonNode weights = result.path("backendWeights");
    if (status < 200 || status >= 300 || !weights.isArray() || weights.size() != 2) return false;
    boolean foundStable = false;
    boolean foundCandidate = false;
    for (JsonNode backend : weights) {
      if (!backend.isArray() || backend.size() != 2 || !backend.get(0).isTextual()
          || !backend.get(1).isIntegralNumber() || !backend.get(1).canConvertToInt()) return false;
      String name = backend.get(0).asText();
      int weight = backend.get(1).intValue();
      if ("stable".equals(name) && weight == stableWeight) foundStable = true;
      else if ("canary".equals(name) && weight == candidateWeight) foundCandidate = true;
      else return false;
    }
    return foundStable && foundCandidate;
  }

  private void requireEligibleHost(java.sql.Connection connection, UUID hostId, long generation)
      throws java.sql.SQLException {
    String capabilitiesColumn = connection.getMetaData().getDatabaseProductName()
        .equalsIgnoreCase("PostgreSQL") ? "h.capabilities::text" : "h.capabilities";
    var rows = query(connection,
        "SELECT h.id,h.generation," + capabilitiesColumn + " AS capabilities,"
            + "(SELECT a.helper_status FROM agent a WHERE a.host_id=h.id "
            + "ORDER BY a.last_seen DESC NULLS LAST,a.id LIMIT 1) AS helper_status,"
            + "(SELECT a.last_seen FROM agent a WHERE a.host_id=h.id "
            + "ORDER BY a.last_seen DESC NULLS LAST,a.id LIMIT 1) AS last_seen "
            + "FROM host h WHERE h.id=?", hostId);
    if (rows.isEmpty()) throw new ReleaseException(404, "HOST_NOT_FOUND", "发布目标主机不存在");
    Map<String, Object> host = rows.getFirst();
    if (number(host.get("generation"), -1) != generation)
      throw new ReleaseException(412, "PRECONDITION_FAILED", "目标主机版本已变化，请刷新后重试");
    if (host.get("last_seen") == null
        || instant(host.get("last_seen")).isBefore(Instant.now().minusSeconds(90))
        || !"AVAILABLE".equals(host.get("helper_status"))) {
      throw new ReleaseException(409, "HOST_HELPER_UNAVAILABLE", "所有发布目标必须在线且 root Helper 可用");
    }
    var mapped = query(connection,
        "SELECT host_id FROM kubernetes_cluster_node WHERE host_id=? LIMIT 1", hostId);
    var discovered = query(connection,
        "SELECT host_id FROM host_kubernetes_runtime_discovery WHERE host_id=? AND detected=TRUE LIMIT 1",
        hostId);
    if (!mapped.isEmpty() || !discovered.isEmpty())
      throw new ReleaseException(409, "KUBERNETES_CONTAINER_OWNED",
          "Kubernetes/KubeEdge 管理或已发现 Kubernetes Runtime 的主机不能使用宿主机容器发布");
    JsonNode capabilities;
    try {
      capabilities = mapper.readTree(String.valueOf(host.get("capabilities")));
    } catch (Exception invalid) {
      throw new IllegalStateException("Stored host capabilities are invalid", invalid);
    }
    for (String capability : REQUIRED_CAPABILITIES) {
      if (!contains(capabilities, capability))
        throw new ReleaseException(409, "HOST_RELEASE_CAPABILITY_MISSING",
            "所有目标主机都必须报告容器与 Nginx 路由 Helper 能力");
    }
    return;
  }

  private int allocatePort(java.sql.Connection connection, UUID hostId, UUID planId, int ordinal,
      Set<Integer> excluded) throws java.sql.SQLException {
    Set<Integer> used = new HashSet<>(excluded);
    for (Map<String, Object> row : query(connection,
        "SELECT host_port AS port FROM host_application_deployment WHERE host_id=? "
            + "UNION SELECT t.stable_port AS port FROM host_application_release_target t "
            + "JOIN host_application_release_plan p ON p.id=t.release_id WHERE t.host_id=? "
            + "AND p.state IN ('DRAFT','RUNNING','PAUSED','ROLLING_BACK','UNKNOWN') "
            + "UNION SELECT t.candidate_port AS port FROM host_application_release_target t "
            + "JOIN host_application_release_plan p ON p.id=t.release_id WHERE t.host_id=? "
            + "AND p.state IN ('DRAFT','RUNNING','PAUSED','ROLLING_BACK','UNKNOWN')",
        hostId, hostId, hostId)) {
      used.add(number(row.get("port"), -1));
    }
    long seed = planId.getMostSignificantBits() ^ planId.getLeastSignificantBits()
        ^ hostId.getLeastSignificantBits() ^ ordinal;
    int start = 30_000 + (int)Math.floorMod(seed, 25_000);
    for (int offset = 0; offset < 25_000; offset++) {
      int candidate = 30_000 + (start - 30_000 + offset) % 25_000;
      if (!used.contains(candidate)) return candidate;
    }
    throw new ReleaseException(409, "HOST_RELEASE_PORTS_EXHAUSTED", "目标主机没有可分配的发布端口");
  }

  private String routeName(UUID hostId, String application) {
    UUID name = UUID.nameUUIDFromBytes((hostId + ":" + application).getBytes(StandardCharsets.UTF_8));
    return "nanpanel-app-" + name.toString().replace("-", "").substring(0, 24);
  }

  static void validate(CreateRequest input) {
    if (input == null || input.name() == null || !NAME.matcher(input.name().trim()).matches()
        || input.stableImage() == null || !IMAGE.matcher(input.stableImage().trim()).matches()
        || input.image() == null || !IMAGE.matcher(input.image().trim()).matches()
        || input.stableImage().trim().equals(input.image().trim())
        || input.containerPort() == null || input.containerPort() < 1 || input.containerPort() > 65535
        || input.serverName() == null || !HOSTNAME.matcher(input.serverName().trim()).matches()
        || input.listenPort() == null || input.listenPort() < 1024 || input.listenPort() > 65535
        || input.healthPath() == null || !input.healthPath().matches("/[A-Za-z0-9/._~-]*")
        || input.healthPath().length() > 256 || input.healthPath().contains("..")
        || input.batchSize() == null || input.batchSize() < 1 || input.batchSize() > 50
        || input.targets() == null || input.targets().isEmpty() || input.targets().size() > 50
        || input.batchSize() > input.targets().size()
        || input.reason() == null || input.reason().trim().isEmpty()
        || input.reason().trim().length() > 500
        || input.reason().chars().anyMatch(Character::isISOControl)) {
      throw fail(422, "VALIDATION_FAILED", "主机发布镜像、路由、批次或操作原因无效");
    }
  }

  static boolean validHostAddress(String value) {
    if (value == null || value.length() > 64 || value.contains("%")) return false;
    try {
      InetAddress address;
      if (value.matches("[0-9.]+")) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        byte[] bytes = new byte[4];
        for (int i = 0; i < parts.length; i++) {
          int octet = Integer.parseInt(parts[i]);
          if (octet < 0 || octet > 255) return false;
          bytes[i] = (byte)octet;
        }
        address = InetAddress.getByAddress(bytes);
      } else if (value.contains(":")) {
        address = InetAddress.getByName(value);
        if (!(address instanceof Inet6Address)) return false;
      } else return false;
      return !address.isAnyLocalAddress() && !address.isMulticastAddress();
    } catch (Exception invalid) {
      return false;
    }
  }

  private boolean contains(JsonNode capabilities, String expected) {
    if (capabilities == null || !capabilities.isArray()) return false;
    for (JsonNode capability : capabilities) {
      if (capability.isTextual() && expected.equals(capability.asText())) return true;
    }
    return false;
  }

  private int number(Object value, int fallback) {
    return value instanceof Number number ? number.intValue() : fallback;
  }

  private String nullableString(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private Instant instant(Object value) {
    if (value == null) return null;
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof OffsetDateTime timestamp) return timestamp.toInstant();
    if (value instanceof Instant timestamp) return timestamp;
    if (value instanceof java.util.Date timestamp) return timestamp.toInstant();
    if (value instanceof Number timestamp) return Instant.ofEpochMilli(timestamp.longValue());
    throw new IllegalStateException("Unsupported database timestamp value");
  }

  private String safeDetail(String value) {
    if (value == null) return "操作未成功";
    String result = value.replaceAll("[\\p{Cntrl}]", " ");
    return result.length() <= 900 ? result : result.substring(0, 900);
  }

  private void changePlan(UUID id, String actor, String remoteAddress, String expected,
      String next, String action) {
    store.transaction(connection -> {
      ownedPlan(connection, id, actor, true);
      int changed = update(connection,
          "UPDATE host_application_release_plan SET state=?,started_at=COALESCE(started_at,?) "
              + "WHERE id=? AND requested_by=? AND state=?",
          next, Timestamp.from(Instant.now()), id, actor, expected);
      if (changed != 1) throw conflict("发布计划状态已变化，请刷新后重试");
      audit(connection, actor, action, "host_application_release_plan", id.toString(), "ACCEPTED",
          remoteAddress, "主机蓝绿发布状态变为 " + next);
      return null;
    });
  }

  private Map<String, Object> ownedPlan(java.sql.Connection connection, UUID id, String actor,
      boolean lock) throws java.sql.SQLException {
    var rows = query(connection,
        "SELECT * FROM host_application_release_plan WHERE id=? AND requested_by=?"
            + (lock ? " FOR UPDATE" : ""), id, actor);
    if (rows.isEmpty()) throw missing();
    return rows.getFirst();
  }

  private ReleaseException conflict(String message) {
    return new ReleaseException(409, "HOST_APPLICATION_RELEASE_CONFLICT", message);
  }

  private ReleaseException missing() {
    return new ReleaseException(404, "HOST_APPLICATION_RELEASE_NOT_FOUND", "主机发布计划不存在");
  }

  public static final class ReleaseException extends RuntimeException {
    private final int status;
    private final String code;

    public ReleaseException(int status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
  }
}
