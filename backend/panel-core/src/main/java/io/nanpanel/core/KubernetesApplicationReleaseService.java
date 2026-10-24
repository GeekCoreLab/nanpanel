package io.nanpanel.core;

import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/** Owns namespaced Argo Rollouts and Gateway API resources for application releases. */
@ApplicationScoped
public class KubernetesApplicationReleaseService {
  private static final Logger LOG = Logger.getLogger(KubernetesApplicationReleaseService.class);
  private static final String ROLLOUT_API = "argoproj.io/v1alpha1";
  private static final String ROUTE_API = "gateway.networking.k8s.io/v1";
  private static final String RELEASE_LABEL = "nanpanel.io/release-id";
  private static final String MANAGED_LABEL = "app.kubernetes.io/managed-by";
  private static final Pattern DNS_LABEL =
      Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern HOSTNAME = Pattern.compile(
      "(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/:@+-]{0,511}");
  private static final PatchContext MERGE_PATCH = PatchContext.of(PatchType.JSON_MERGE);

  @Inject AuthStore store;
  @Inject KubernetesClusterRegistryService clusters;
  @Inject ObjectMapper mapper;

  public record CreateRequest(String name, String stableImage, String image, Integer replicas, Integer containerPort,
      String gatewayName, String hostname, String prometheusService, BigDecimal maxErrorRate,
      String reason) {}

  public record StageView(int ordinal, String stage, String state, Integer desiredCanaryWeight,
      Integer observedCanaryWeight, Integer observedStableWeight, String analysisRunName,
      String detail, Instant startedAt, Instant finishedAt) {}

  public record MeasurementView(String analysisRunName, String metricName, int measurementOrdinal,
      String phase, BigDecimal value, String detail, Instant sampledAt) {}

  public record ReleaseView(String id, String clusterId, String namespace, String name,
      String rolloutName, String routeName, String hostname, String stableImage, String image, int containerPort,
      int replicas, BigDecimal maxErrorRate, String reason, String state, Integer stableWeight,
      Integer canaryWeight, String rolloutPhase, String currentStepMessage, String errorCode,
      String errorMessage, String requestedBy, Instant createdAt, Instant startedAt,
      Instant observedAt, Instant finishedAt, List<StageView> stages,
      List<MeasurementView> measurements) {}

  private record Plan(UUID id, UUID clusterId, String namespace, String applicationName,
      String rolloutName, String routeName, String analysisName, String stableService,
      String canaryService, String gatewayName, String hostname, String prometheusService,
      String stableImage, String image, int containerPort, int replicas, BigDecimal maxErrorRate, String reason,
      String state, String requestedBy, String rolloutUid, String routeUid,
      Integer stableWeight, Integer canaryWeight, String rolloutPhase, String currentStepMessage,
      String errorCode, String errorMessage, Instant createdAt, Instant startedAt,
      Instant observedAt, Instant finishedAt) {}

  public ReleaseView create(String actor, String remoteAddress, UUID clusterId,
      String namespace, CreateRequest input) {
    validate(namespace, input);
    UUID id = UUID.randomUUID();
    String suffix = id.toString().replace("-", "").substring(0, 12);
    String prefix = "np-" + suffix;
    Instant now = Instant.now();
    BigDecimal threshold = input.maxErrorRate() == null
        ? new BigDecimal("0.05000") : input.maxErrorRate().stripTrailingZeros();
    store.transaction(connection -> {
      var active = query(connection,
          "SELECT id FROM application_release_plan WHERE cluster_id=? AND namespace=? "
              + "AND application_name=? AND state IN ('DRAFT','STARTING','RUNNING','PAUSED','UNKNOWN') "
              + "FOR UPDATE", clusterId, namespace, input.name());
      if (!active.isEmpty()) throw new ReleaseException(409, "APPLICATION_RELEASE_IN_PROGRESS",
          "此集群 Namespace 中已有同名应用发布正在运行");
      var previous = query(connection,
          "SELECT * FROM application_release_plan WHERE cluster_id=? AND namespace=? "
              + "AND application_name=? AND state='SUCCEEDED' "
              + "ORDER BY finished_at DESC LIMIT 1",
          clusterId, namespace, input.name());
      String rolloutName = prefix;
      String routeName = prefix + "-route";
      String stableService = prefix + "-stable";
      String canaryService = prefix + "-canary";
      if (!previous.isEmpty()) {
        Map<String, Object> last = previous.getFirst();
        if (!actor.equals(string(last.get("requested_by")))
            || !input.stableImage().equals(string(last.get("image")))
            || !input.gatewayName().equals(string(last.get("gateway_name")))
            || !input.hostname().equals(string(last.get("hostname")))
            || !input.prometheusService().equals(string(last.get("prometheus_service_name")))
            || input.containerPort() != number(last.get("container_port"), -1)) {
          throw new ReleaseException(409, "APPLICATION_RELEASE_STABLE_BASELINE_MISMATCH",
              "已存在的 NaNPanel 应用必须以最近成功的镜像、Gateway、主机名、Prometheus 和容器端口作为稳定基线");
        }
        rolloutName = string(last.get("rollout_name"));
        routeName = string(last.get("route_name"));
        stableService = string(last.get("stable_service_name"));
        canaryService = string(last.get("canary_service_name"));
      }
      String analysisName = prefix + "-analysis";
      update(connection,
          "INSERT INTO application_release_plan (id,cluster_id,namespace,application_name,"
              + "rollout_name,route_name,analysis_name,stable_service_name,canary_service_name,"
              + "gateway_name,hostname,prometheus_service_name,stable_image,image,container_port,replicas,"
              + "max_error_rate,reason,state,requested_by,created_at) "
              + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?)",
          id, clusterId, namespace, input.name(), rolloutName, routeName, analysisName,
          stableService, canaryService, input.gatewayName(), input.hostname(),
          input.prometheusService(), input.stableImage(), input.image(), input.containerPort(), input.replicas(),
          threshold, input.reason().trim(), actor, Timestamp.from(now));
      update(connection,
          "INSERT INTO application_release_stage (release_id,ordinal,stage,state,detail,started_at) "
              + "VALUES (?,0,'DRAFT','OBSERVED','发布草稿已持久化',?)",
          id, Timestamp.from(now));
      audit(connection, actor, "KUBERNETES_APPLICATION_RELEASE_CREATED",
          "application_release_plan", id.toString(), "SUCCEEDED", remoteAddress,
          "创建应用发布草稿 " + namespace + "/" + input.name());
      return null;
    });
    return view(load(id, actor));
  }

  public List<ReleaseView> list(String actor, UUID clusterId, String namespace) {
    var ids = store.transaction(connection -> query(connection,
        "SELECT id FROM application_release_plan WHERE requested_by=? AND cluster_id=? "
            + "AND namespace=? ORDER BY created_at DESC LIMIT 100", actor, clusterId, namespace));
    var items = new ArrayList<ReleaseView>();
    for (var row : ids) {
      UUID id = uuid(row.get("id"));
      try {
        refresh(id, actor);
      } catch (RuntimeException unavailable) {
        LOG.debugf("Application release %s remains at its last persisted observation", id);
      }
      items.add(view(load(id, actor)));
    }
    return List.copyOf(items);
  }

  public ReleaseView get(UUID id, String actor) {
    refresh(id, actor);
    return view(load(id, actor));
  }

  public ReleaseView start(String actor, String remoteAddress, UUID id) {
    Plan plan = load(id, actor);
    boolean draft = "DRAFT".equals(plan.state());
    boolean reconcilable = draft || "STARTING".equals(plan.state()) || "UNKNOWN".equals(plan.state());
    if (!reconcilable) {
      throw new ReleaseException(409, "APPLICATION_RELEASE_STATE_CONFLICT",
          "只有 DRAFT、STARTING 或 UNKNOWN 发布可以启动或对账");
    }
    if (draft) {
      setState(plan, "STARTING", "", "", actor, remoteAddress,
          "开始检查控制器并创建发布资源");
    }
    boolean creationStarted = false;
    try (KubernetesClient client = clusters.openClientForOperations(plan.clusterId())) {
      preflight(client, plan);
      var resources = releaseResources(plan);
      creationStarted = true;
      for (GenericKubernetesResource resource : resources) {
        createOwned(client, resource, plan);
      }
      GenericKubernetesResource rollout = getCustom(client, ROLLOUT_API, "Rollout",
          plan.namespace(), plan.rolloutName());
      GenericKubernetesResource route = getCustom(client, ROUTE_API, "HTTPRoute",
          plan.namespace(), plan.routeName());
      if (rollout == null || route == null) {
        throw new ReleaseException(502, "APPLICATION_RELEASE_CREATE_UNCONFIRMED",
            "Kubernetes 已接受写入请求，但无法读取新建 Rollout 或 HTTPRoute");
      }
      requireOwned(rollout, plan);
      requireOwned(route, plan);
      requireIdentity(rollout, plan.rolloutUid());
      requireIdentity(route, plan.routeUid());
      persistResourceUids(plan, rollout, route);
      setState(plan, "RUNNING", "", "", actor, remoteAddress,
          "Argo Rollout、HTTPRoute、Services 与 AnalysisTemplate 已创建");
    } catch (ReleaseException rejected) {
      String state = creationStarted ? "UNKNOWN" : (draft ? "DRAFT" : "UNKNOWN");
      setState(plan, state, rejected.code(), rejected.getMessage(), actor, remoteAddress,
          creationStarted ? "资源创建期间发生冲突，需要按发布 ID 对账"
              : "前置条件未满足；未创建发布资源，可以修复后重试");
      throw rejected;
    } catch (RuntimeException uncertain) {
      setState(plan, "UNKNOWN", "APPLICATION_RELEASE_RECONCILIATION_REQUIRED",
          "创建结果不确定；按发布 ID 对账后再重试", actor, remoteAddress,
          "Kubernetes API 返回不确定结果，保留发布资源供对账");
      throw new ReleaseException(502, "APPLICATION_RELEASE_UNKNOWN",
          "Kubernetes API 返回不确定结果，发布已保留为 UNKNOWN");
    }
    return get(id, actor);
  }

  public ReleaseView pause(String actor, String remoteAddress, UUID id) {
    Plan plan = controllable(load(id, actor));
    patchRollout(plan, Map.of("spec", Map.of("paused", true)), null);
    auditAction(plan, actor, remoteAddress, "PAUSED", "管理员暂停应用发布");
    return get(id, actor);
  }

  public ReleaseView resume(String actor, String remoteAddress, UUID id) {
    Plan plan = controllable(load(id, actor));
    patchRollout(plan, Map.of("spec", Map.of("paused", false)), null);
    auditAction(plan, actor, remoteAddress, "RESUMED", "管理员恢复应用发布");
    return get(id, actor);
  }

  public ReleaseView promote(String actor, String remoteAddress, UUID id) {
    Plan plan = controllable(load(id, actor));
    try (KubernetesClient client = clusters.openClientForOperations(plan.clusterId())) {
      GenericKubernetesResource rollout = getCustom(client, ROLLOUT_API, "Rollout",
          plan.namespace(), plan.rolloutName());
      requireOwned(rollout, plan);
      Map<String, Object> status = map(rollout, "status");
      if (!"Paused".equalsIgnoreCase(string(status.get("phase")))) {
        throw new ReleaseException(409, "APPLICATION_RELEASE_NOT_PAUSED",
            "只有 Argo Rollouts 当前暂停在灰度步骤时才能批准下一阶段");
      }
      Map<String, Object> spec = map(rollout, "spec");
      if (Boolean.TRUE.equals(spec.get("paused"))) {
        throw new ReleaseException(409, "APPLICATION_RELEASE_MANUAL_PAUSE",
            "发布被管理员整体暂停；请先使用恢复操作，再批准灰度步骤");
      }
      GenericKubernetesResource analysisRun = currentAnalysisRun(client, plan, rollout, status);
      if (analysisRun == null || !analysisRunPassed(analysisRun, plan, rollout)) {
        throw new ReleaseException(409, "APPLICATION_RELEASE_ANALYSIS_PENDING",
            "当前金丝雀阶段尚未通过完整的 Prometheus 错误率分析，暂不能批准下一阶段");
      }
      int nextStep = number(status.get("currentStepIndex"), 0) + 1;
      Map<String, Object> statusPatch = new LinkedHashMap<>();
      statusPatch.put("pauseConditions", null);
      statusPatch.put("currentStepIndex", nextStep);
      String patch = json(Map.of("status", statusPatch));
      client.genericKubernetesResources(ROLLOUT_API, "Rollout").inNamespace(plan.namespace())
          .withName(plan.rolloutName()).status().patch(MERGE_PATCH, patch);
      if (Boolean.TRUE.equals(spec.get("paused"))) {
        patchRollout(plan, Map.of("spec", Map.of("paused", false)), null);
      }
    } catch (ReleaseException rejected) {
      throw rejected;
    } catch (RuntimeException failure) {
      throw apiFailure(plan.clusterId(), failure);
    }
    auditAction(plan, actor, remoteAddress, "PROMOTED", "管理员将发布推进到下一灰度阶段");
    return get(id, actor);
  }

  public ReleaseView rollback(String actor, String remoteAddress, UUID id) {
    Plan plan = controllable(load(id, actor));
    patchRollout(plan, Map.of("spec", Map.of("abort", true)), null);
    auditAction(plan, actor, remoteAddress, "ROLLBACK_REQUESTED", "管理员请求 Argo Rollouts 恢复稳定版本");
    return get(id, actor);
  }

  private void refresh(UUID id, String actor) {
    Plan plan = load(id, actor);
    if ("DRAFT".equals(plan.state()) || "FAILED".equals(plan.state())
        || "SUCCEEDED".equals(plan.state()) || "ROLLED_BACK".equals(plan.state())) return;
    try (KubernetesClient client = clusters.openClientForOperations(plan.clusterId())) {
      GenericKubernetesResource rollout = getCustom(client, ROLLOUT_API, "Rollout",
          plan.namespace(), plan.rolloutName());
      GenericKubernetesResource route = getCustom(client, ROUTE_API, "HTTPRoute",
          plan.namespace(), plan.routeName());
      if (rollout == null || route == null) {
        persistState(plan, "UNKNOWN", "APPLICATION_RELEASE_RESOURCE_MISSING",
            "发布所拥有的 Rollout 或 HTTPRoute 不存在");
        return;
      }
      requireOwned(rollout, plan);
      requireOwned(route, plan);
      requireIdentity(rollout, plan.rolloutUid());
      requireIdentity(route, plan.routeUid());
      Map<String, Object> rolloutStatus = map(rollout, "status");
      Map<String, Object> routeSpec = map(route, "spec");
      var weights = routeWeights(routeSpec, plan);
      String phase = string(rolloutStatus.get("phase"));
      String message = string(rolloutStatus.get("message"));
      var run = currentAnalysisRun(client, plan, rollout, rolloutStatus);
      String runName = run == null ? null : run.getMetadata().getName();
      String runPhase = run == null ? "" : string(map(run, "status").get("phase"));
      boolean targetActivated = activateCanaryImage(client, plan, rollout, route, weights, phase,
          actor);
      boolean controllerObservedTarget = controllerObservedGeneration(rollout);
      boolean successfulAnalysis = run != null && analysisRunPassed(run, plan, rollout);
      String nextState = targetActivated ? "RUNNING"
          : observedState(plan, rollout, route, phase, message, weights,
              controllerObservedTarget, successfulAnalysis, runPhase);
      if (run != null) persistMeasurements(plan, run);
      persistObservation(plan, rollout, route, weights, phase, message, nextState, runName,
          runPhase, actor);
    } catch (ReleaseException rejected) {
      throw rejected;
    } catch (RuntimeException unavailable) {
      LOG.debugf(unavailable, "Unable to refresh application release %s", id);
      if ("STARTING".equals(plan.state())) {
        persistState(plan, "UNKNOWN", "APPLICATION_RELEASE_OBSERVATION_UNAVAILABLE",
            "无法读取发布资源的实际状态");
      }
    }
  }

  private void preflight(KubernetesClient client, Plan plan) {
    if (client.apiextensions().v1().customResourceDefinitions()
        .withName("rollouts.argoproj.io").get() == null
        || client.apiextensions().v1().customResourceDefinitions()
            .withName("gateways.gateway.networking.k8s.io").get() == null
        || client.apiextensions().v1().customResourceDefinitions()
            .withName("httproutes.gateway.networking.k8s.io").get() == null) {
      throw new ReleaseException(409, "APPLICATION_RELEASE_CONTROLLERS_MISSING",
          "Gateway API HTTPRoute 与 Argo Rollouts CRD 必须由集群管理员预先安装");
    }
    var controller = client.apps().deployments().inNamespace("argo-rollouts")
        .withName("argo-rollouts").get();
    if (controller == null || controller.getStatus() == null
        || controller.getStatus().getAvailableReplicas() == null
        || controller.getStatus().getAvailableReplicas() < 1) {
      throw new ReleaseException(409, "APPLICATION_RELEASE_CONTROLLER_NOT_READY",
          "argo-rollouts 控制器当前没有可用副本");
    }
    var pluginConfig = client.configMaps().inNamespace("argo-rollouts")
        .withName("argo-rollouts-config").get();
    if (pluginConfig == null || pluginConfig.getData() == null
        || pluginConfig.getData().values().stream().noneMatch(value -> value.contains("gatewayAPI"))) {
      throw new ReleaseException(409, "APPLICATION_RELEASE_GATEWAY_PLUGIN_MISSING",
          "Argo Rollouts 未配置 argoproj-labs/gatewayAPI traffic router 插件");
    }
    GenericKubernetesResource gateway = getCustom(client, ROUTE_API, "Gateway",
        plan.namespace(), plan.gatewayName());
    if (gateway == null || !conditionTrue(map(gateway, "status").get("conditions"), "Accepted")
        || !listenerProgrammed(gateway)) {
      throw new ReleaseException(409, "APPLICATION_RELEASE_GATEWAY_NOT_READY",
          "目标 Gateway 必须已接纳，且至少一个 HTTP listener 已编程");
    }
    Service prometheus = client.services().inNamespace(plan.namespace())
        .withName(plan.prometheusService()).get();
    if (prometheus == null || prometheus.getSpec() == null || prometheus.getSpec().getPorts() == null
        || prometheus.getSpec().getPorts().stream().noneMatch(port -> Integer.valueOf(9090)
            .equals(port.getPort()))) {
      throw new ReleaseException(409, "APPLICATION_RELEASE_PROMETHEUS_NOT_READY",
          "同一 Namespace 必须提供监听 9090 端口的 Prometheus Service");
    }
    var existingRoutes = client.genericKubernetesResources(ROUTE_API, "HTTPRoute").list();
    if (existingRoutes != null && existingRoutes.getItems() != null
        && existingRoutes.getItems().stream().anyMatch(route ->
            !plan.routeName().equals(route.getMetadata().getName())
                && stringList(map(route, "spec").get("hostnames")).contains(plan.hostname())
                && referencesGateway(map(route, "spec").get("parentRefs"),
                    plan.gatewayName(), plan.namespace()))) {
      throw new ReleaseException(409, "APPLICATION_RELEASE_HOSTNAME_CONFLICT",
          "此 Namespace 已有 HTTPRoute 使用该主机名");
    }
  }

  private List<GenericKubernetesResource> releaseResources(Plan plan) {
    var result = new ArrayList<GenericKubernetesResource>();
    result.add(resource("v1", "Service", plan,
        serviceSpec(plan, plan.applicationName()), plan.stableService()));
    result.add(resource("v1", "Service", plan,
        serviceSpec(plan, plan.applicationName()), plan.canaryService()));
    result.add(resource(ROLLOUT_API, "AnalysisTemplate", plan, analysisTemplateSpec(plan),
        plan.analysisName()));
    result.add(resource(ROUTE_API, "HTTPRoute", plan, routeSpec(plan), plan.routeName()));
    result.add(resource(ROLLOUT_API, "Rollout", plan,
        rolloutSpec(plan, plan.stableImage()), plan.rolloutName()));
    return result;
  }

  private GenericKubernetesResource resource(String apiVersion, String kind, Plan plan,
      Map<String, Object> spec) {
    return resource(apiVersion, kind, plan, spec, "");
  }

  private GenericKubernetesResource resource(String apiVersion, String kind, Plan plan,
      Map<String, Object> spec, String name) {
    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("name", name == null || name.isBlank() ? plan.rolloutName() : name);
    metadata.put("namespace", plan.namespace());
    metadata.put("labels", Map.of(MANAGED_LABEL, "nanpanel", RELEASE_LABEL, plan.id().toString()));
    var body = new LinkedHashMap<String, Object>();
    body.put("apiVersion", apiVersion);
    body.put("kind", kind);
    body.put("metadata", metadata);
    body.put("spec", spec);
    return mapper.convertValue(body, GenericKubernetesResource.class);
  }

  private Map<String, Object> serviceSpec(Plan plan, String workload) {
    return Map.of(
        "selector", Map.of("app.kubernetes.io/name", workload),
        "ports", List.of(Map.of("name", "http", "port", plan.containerPort(),
            "targetPort", "http", "protocol", "TCP")));
  }

  private Map<String, Object> routeSpec(Plan plan) {
    return Map.of(
        "parentRefs", List.of(Map.of("name", plan.gatewayName())),
        "hostnames", List.of(plan.hostname()),
        "rules", List.of(Map.of(
            "matches", List.of(Map.of("path", Map.of("type", "PathPrefix", "value", "/"))),
            "backendRefs", List.of(
                Map.of("name", plan.stableService(), "port", plan.containerPort(), "weight", 100),
                Map.of("name", plan.canaryService(), "port", plan.containerPort(), "weight", 0)))));
  }

  private Map<String, Object> analysisTemplateSpec(Plan plan) {
    String selector = "app=\"" + plan.applicationName() + "\",rollout_hash=\"{{ args.canaryHash }}\"";
    String requests = "sum(increase(nanpanel_http_requests_total{" + selector + "}[30s]))";
    String errors = "sum(increase(nanpanel_http_requests_total{" + selector + ",outcome=\"error\"}[30s]))";
    BigDecimal successThreshold = plan.maxErrorRate().divide(new BigDecimal("2"), 8,
        java.math.RoundingMode.HALF_UP);
    BigDecimal inconclusiveValue = plan.maxErrorRate().multiply(new BigDecimal("0.75"))
        .setScale(8, java.math.RoundingMode.HALF_UP);
    String ratioQuery = "(\n  ((" + errors + ") / clamp_min(" + requests + ", 1))\n"
        + "  and " + requests + " >= 20\n)\nor (vector(" + inconclusiveValue.stripTrailingZeros()
        + ") unless " + requests + " >= 20)";
    var metrics = List.of(
        metric(plan, "canary-http-error-rate", 15, 3, successThreshold, plan.maxErrorRate(), ratioQuery),
        metric(plan, "canary-http-request-count", 15, 3, BigDecimal.ZERO, null,
            requests + " or vector(0)"),
        metric(plan, "canary-http-error-count", 15, 3, BigDecimal.ZERO, null,
            errors + " or vector(0)"));
    return Map.of("args", List.of(Map.of("name", "canaryHash")), "metrics", metrics);
  }

  private Map<String, Object> metric(Plan plan, String name, int initialDelay, int count,
      BigDecimal successThreshold, BigDecimal failureThreshold, String query) {
    var metric = new LinkedHashMap<String, Object>();
    metric.put("name", name);
    metric.put("initialDelay", initialDelay + "s");
    metric.put("interval", "10s");
    metric.put("count", count);
    metric.put("failureLimit", failureThreshold == null ? -1 : 0);
    if (failureThreshold == null) metric.put("consecutiveSuccessLimit", count);
    if (failureThreshold != null) metric.put("inconclusiveLimit", 0);
    metric.put("successCondition", failureThreshold == null
        ? "result[0] >= 0"
        : "result[0] <= " + successThreshold.stripTrailingZeros());
    if (failureThreshold != null) {
      metric.put("failureCondition", "result[0] > " + failureThreshold.stripTrailingZeros());
    }
    metric.put("provider", Map.of("prometheus", Map.of(
        "address", "http://" + plan.prometheusService() + "." + plan.namespace()
            + ".svc.cluster.local:9090",
        "query", query)));
    return metric;
  }

  private Map<String, Object> rolloutSpec(Plan plan, String initialImage) {
    Map<String, Object> analysisStep = Map.of("analysis", Map.of(
        "args", List.of(Map.of("name", "canaryHash", "valueFrom",
            Map.of("podTemplateHashValue", "Latest"))),
        "templates", List.of(Map.of("templateName", plan.analysisName()))));
    var steps = List.of(
        Map.of("setWeight", 10), analysisStep, Map.of("pause", Map.of()),
        Map.of("setWeight", 50), analysisStep, Map.of("pause", Map.of()),
        Map.of("setWeight", 100), analysisStep, Map.of("pause", Map.of()));
    var canary = new LinkedHashMap<String, Object>();
    canary.put("stableService", plan.stableService());
    canary.put("canaryService", plan.canaryService());
    canary.put("trafficRouting", Map.of("plugins", Map.of("argoproj-labs/gatewayAPI",
        Map.of("httpRoute", plan.routeName()))));
    canary.put("steps", steps);
    var container = new LinkedHashMap<String, Object>();
    container.put("name", "app");
    container.put("image", initialImage);
    container.put("imagePullPolicy", "IfNotPresent");
    container.put("env", List.of(Map.of("name", "NANPANEL_APPLICATION_NAME",
        "value", plan.applicationName())));
    container.put("ports", List.of(Map.of("name", "http", "containerPort", plan.containerPort())));
    var podTemplate = Map.of("metadata", Map.of("labels", Map.of(
        "app", plan.applicationName(), "app.kubernetes.io/name", plan.applicationName())),
        "spec", Map.of("containers", List.of(container)));
    return Map.of("replicas", plan.replicas(), "revisionHistoryLimit", 3,
        "selector", Map.of("matchLabels", Map.of("app.kubernetes.io/name", plan.applicationName())),
        "strategy", Map.of("canary", canary), "template", podTemplate);
  }

  private void createOwned(KubernetesClient client, GenericKubernetesResource desired, Plan plan) {
    String apiVersion = desired.getApiVersion();
    String kind = desired.getKind();
    String name = desired.getMetadata().getName();
    GenericKubernetesResource existing = getCustom(client, apiVersion, kind, plan.namespace(), name);
    if (existing != null) {
      if (owned(existing, plan)) return;
      adoptCompletedResource(client, existing, desired, plan);
      return;
    }
    try {
      client.resource(desired).create();
    } catch (RuntimeException creationFailure) {
      GenericKubernetesResource after = getCustom(client, apiVersion, kind, plan.namespace(), name);
      if (after == null || !owned(after, plan)) throw creationFailure;
    }
  }

  private void adoptCompletedResource(KubernetesClient client, GenericKubernetesResource existing,
      GenericKubernetesResource desired, Plan plan) {
    if (existing.getMetadata() == null || existing.getMetadata().getLabels() == null) {
      throw ownershipConflict();
    }
    String ownerValue = existing.getMetadata().getLabels().get(RELEASE_LABEL);
    UUID previousId;
    try {
      previousId = UUID.fromString(ownerValue);
    } catch (IllegalArgumentException | NullPointerException invalidOwner) {
      throw ownershipConflict();
    }
    var rows = store.transaction(connection -> query(connection,
        "SELECT * FROM application_release_plan WHERE id=?", previousId));
    if (rows.isEmpty()) throw ownershipConflict();
    Plan previous = plan(rows.getFirst());
    if (!"SUCCEEDED".equals(previous.state()) || !previous.clusterId().equals(plan.clusterId())
        || !previous.namespace().equals(plan.namespace())
        || !previous.applicationName().equals(plan.applicationName())
        || !previous.requestedBy().equals(plan.requestedBy())
        || !previous.image().equals(plan.stableImage())
        || !previous.rolloutName().equals(plan.rolloutName())
        || !previous.routeName().equals(plan.routeName())
        || !previous.stableService().equals(plan.stableService())
        || !previous.canaryService().equals(plan.canaryService())
        || !Integer.valueOf(100).equals(previous.stableWeight())
        || !Integer.valueOf(0).equals(previous.canaryWeight())) {
      throw ownershipConflict();
    }
    String kind = existing.getKind();
    if ("Rollout".equals(kind)) {
      requireIdentity(existing, previous.rolloutUid());
      String currentImage = rolloutImage(existing);
      if (!previous.image().equals(currentImage)
          || !"Healthy".equalsIgnoreCase(previous.rolloutPhase())) throw ownershipConflict();
    } else if ("HTTPRoute".equals(kind)) {
      requireIdentity(existing, previous.routeUid());
      Weights weights = routeWeights(map(existing, "spec"), previous);
      if (!Integer.valueOf(100).equals(weights.stableWeight())
          || !Integer.valueOf(0).equals(weights.canaryWeight())
          || !routeReady(existing, previous)) throw ownershipConflict();
    } else if ("Service".equals(kind)) {
      String name = existing.getMetadata().getName();
      if (!previous.stableService().equals(name) && !previous.canaryService().equals(name)) {
        throw ownershipConflict();
      }
    } else {
      throw ownershipConflict();
    }
    var metadataPatch = new LinkedHashMap<String, Object>();
    metadataPatch.put("labels", desired.getMetadata().getLabels());
    var patch = new LinkedHashMap<String, Object>();
    patch.put("metadata", metadataPatch);
    if ("Rollout".equals(kind)) {
      patch.put("spec", desired.getAdditionalProperties().get("spec"));
    }
    client.genericKubernetesResources(desired.getApiVersion(), kind)
        .inNamespace(plan.namespace()).withName(desired.getMetadata().getName())
        .patch(MERGE_PATCH, json(patch));
  }

  private static ReleaseException ownershipConflict() {
    return new ReleaseException(409, "APPLICATION_RELEASE_OWNERSHIP_CONFLICT",
        "发布资源不属于最近成功且流量已恢复稳定的 NaNPanel 发布，拒绝接管");
  }

  private void patchRollout(Plan plan, Map<String, Object> spec, Map<String, Object> status) {
    try (KubernetesClient client = clusters.openClientForOperations(plan.clusterId())) {
      GenericKubernetesResource current = getCustom(client, ROLLOUT_API, "Rollout",
          plan.namespace(), plan.rolloutName());
      requireOwned(current, plan);
      requireIdentity(current, plan.rolloutUid());
      var patch = new LinkedHashMap<String, Object>();
      patch.put("spec", spec.get("spec"));
      client.genericKubernetesResources(ROLLOUT_API, "Rollout").inNamespace(plan.namespace())
          .withName(plan.rolloutName()).patch(MERGE_PATCH, json(patch));
      if (status != null) {
        client.genericKubernetesResources(ROLLOUT_API, "Rollout").inNamespace(plan.namespace())
            .withName(plan.rolloutName()).status().patch(MERGE_PATCH, json(status));
      }
    } catch (ReleaseException rejected) {
      throw rejected;
    } catch (RuntimeException failure) {
      throw apiFailure(plan.clusterId(), failure);
    }
  }

  private void persistResourceUids(Plan plan, GenericKubernetesResource rollout,
      GenericKubernetesResource route) {
    store.transaction(connection -> {
      update(connection,
          "UPDATE application_release_plan SET rollout_uid=?,route_uid=? WHERE id=?",
          rollout == null || rollout.getMetadata() == null ? null : rollout.getMetadata().getUid(),
          route == null || route.getMetadata() == null ? null : route.getMetadata().getUid(), plan.id());
      return null;
    });
  }

  private void persistObservation(Plan plan, GenericKubernetesResource rollout,
      GenericKubernetesResource route, Weights weights, String phase, String message,
      String nextState, String analysisRun, String analysisPhase, String actor) {
    Instant now = Instant.now();
    boolean weightChanged = !java.util.Objects.equals(plan.stableWeight(), weights.stableWeight())
        || !java.util.Objects.equals(plan.canaryWeight(), weights.canaryWeight());
    boolean changed = weightChanged || !java.util.Objects.equals(plan.state(), nextState)
        || !java.util.Objects.equals(plan.rolloutPhase(), phase)
        || !java.util.Objects.equals(plan.currentStepMessage(), emptyToNull(message));
    store.transaction(connection -> {
      update(connection,
          "UPDATE application_release_plan SET rollout_uid=?,route_uid=?,state=?,"
              + "observed_stable_weight=?,observed_canary_weight=?,observed_at=?,"
              + "rollout_phase=?,current_step_message=?,"
              + "error_code=CASE WHEN ? IN ('SUCCEEDED','ROLLED_BACK') THEN NULL ELSE error_code END,"
              + "error_message=CASE WHEN ? IN ('SUCCEEDED','ROLLED_BACK') THEN NULL ELSE error_message END,"
              + "finished_at=CASE WHEN ? IN ('SUCCEEDED','ROLLED_BACK','FAILED') THEN COALESCE(finished_at,?) ELSE NULL END "
              + "WHERE id=?",
          rollout.getMetadata() == null ? null : rollout.getMetadata().getUid(),
          route.getMetadata() == null ? null : route.getMetadata().getUid(), nextState,
          weights.stableWeight(), weights.canaryWeight(), Timestamp.from(now), phase,
          emptyToNull(message), nextState, nextState, nextState, Timestamp.from(now), plan.id());
      if (changed) {
        int ordinal = query(connection,
            "SELECT COALESCE(MAX(ordinal),0)+1 AS next_ordinal FROM application_release_stage WHERE release_id=?",
            plan.id()).getFirst().get("next_ordinal") instanceof Number value ? value.intValue() : 1;
        String stage = stageName(weights.canaryWeight(), nextState);
        String stageState = stageState(nextState, analysisPhase);
        update(connection,
            "INSERT INTO application_release_stage (release_id,ordinal,stage,state,"
                + "desired_canary_weight,observed_canary_weight,observed_stable_weight,"
                + "analysis_run_name,detail,started_at,finished_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            plan.id(), ordinal, stage, stageState, weights.canaryWeight(), weights.canaryWeight(),
            weights.stableWeight(), analysisRun,
            "Argo phase=" + phase + "; AnalysisRun=" + analysisPhase + "; " + message,
            Timestamp.from(now), Timestamp.from(now));
        if (weightChanged) {
          audit(connection, actor, "KUBERNETES_APPLICATION_RELEASE_WEIGHT_OBSERVED",
              "application_release_plan", plan.id().toString(), "SUCCEEDED", null,
              "观测到 HTTPRoute stable/canary 权重 " + weights.stableWeight() + "/" + weights.canaryWeight());
        }
      }
      if (!plan.state().equals(nextState)) {
        audit(connection, actor, "KUBERNETES_APPLICATION_RELEASE_STATE_CHANGED",
            "application_release_plan", plan.id().toString(), auditOutcome(nextState), null,
            "Argo Rollouts 状态 " + phase + "，发布状态变为 " + nextState);
      }
      return null;
    });
  }

  private void persistMeasurements(Plan plan, GenericKubernetesResource run) {
    Map<String, Object> status = map(run, "status");
    for (Object resultValue : list(status.get("metricResults"))) {
      if (!(resultValue instanceof Map<?, ?> rawResult)) continue;
      @SuppressWarnings("unchecked")
      Map<String, Object> result = (Map<String, Object>)rawResult;
      String metric = string(result.get("name"));
      for (Object measurementValue : list(result.get("measurements"))) {
        if (!(measurementValue instanceof Map<?, ?> rawMeasurement)) continue;
        @SuppressWarnings("unchecked")
        Map<String, Object> measurement = (Map<String, Object>)rawMeasurement;
        Instant sampledAt = instant(measurement.get("finishedAt"));
        if (sampledAt == null) sampledAt = instant(measurement.get("startedAt"));
        if (sampledAt == null) continue;
        int ordinal = number(result.get("count"), 0) - list(result.get("measurements")).size()
            + list(result.get("measurements")).indexOf(measurementValue);
        if (ordinal < 0) ordinal = list(result.get("measurements")).indexOf(measurementValue);
        String phase = string(measurement.get("phase")).toUpperCase(java.util.Locale.ROOT);
        String value = string(measurement.get("value"));
        BigDecimal numeric = numericMeasurement(value);
        String details = string(measurement.get("message"));
        int measurementOrdinal = ordinal;
        Instant sampleTime = sampledAt;
        store.transaction(connection -> {
          var existing = query(connection,
              "SELECT id FROM application_release_measurement WHERE release_id=? "
                  + "AND analysis_run_name=? AND metric_name=? AND measurement_ordinal=?",
              plan.id(), run.getMetadata().getName(), metric, measurementOrdinal);
          if (existing.isEmpty()) {
            update(connection,
                "INSERT INTO application_release_measurement (release_id,analysis_run_name,"
                    + "metric_name,measurement_ordinal,phase,value,detail,sampled_at) "
                    + "VALUES (?,?,?,?,?,?,?,?)",
                plan.id(), run.getMetadata().getName(), metric, measurementOrdinal, phase,
                numeric, details, Timestamp.from(sampleTime));
          }
          return null;
        });
      }
    }
  }

  private void setState(Plan plan, String state, String code, String message, String actor,
      String remoteAddress, String detail) {
    Instant now = Instant.now();
    store.transaction(connection -> {
      update(connection,
          "UPDATE application_release_plan SET state=?,error_code=?,error_message=?,"
              + "started_at=CASE WHEN ? THEN COALESCE(started_at,?) ELSE started_at END,"
              + "finished_at=? "
              + "WHERE id=? AND requested_by=?",
          state, emptyToNull(code), emptyToNull(message),
          List.of("STARTING", "RUNNING").contains(state), Timestamp.from(now),
          List.of("FAILED", "SUCCEEDED", "ROLLED_BACK").contains(state)
              ? Timestamp.from(now) : null,
          plan.id(), actor);
      audit(connection, actor, "KUBERNETES_APPLICATION_RELEASE_" + state,
          "application_release_plan", plan.id().toString(), auditOutcome(state), remoteAddress, detail);
      return null;
    });
  }

  private void persistState(Plan plan, String state, String code, String message) {
    if (plan.state().equals(state)) return;
    store.transaction(connection -> {
      update(connection,
          "UPDATE application_release_plan SET state=?,error_code=?,error_message=?,observed_at=? WHERE id=?",
          state, emptyToNull(code), emptyToNull(message), Timestamp.from(Instant.now()), plan.id());
      audit(connection, plan.requestedBy(), "KUBERNETES_APPLICATION_RELEASE_STATE_CHANGED",
          "application_release_plan", plan.id().toString(), auditOutcome(state), null, message);
      return null;
    });
  }

  private void auditAction(Plan plan, String actor, String remoteAddress, String action,
      String detail) {
    store.transaction(connection -> {
      audit(connection, actor, "KUBERNETES_APPLICATION_RELEASE_" + action,
          "application_release_plan", plan.id().toString(), "ACCEPTED", remoteAddress, detail);
      return null;
    });
  }

  static String auditOutcome(String state) {
    return switch (state) {
      case "SUCCEEDED", "FAILED", "UNKNOWN" -> state;
      case "ROLLED_BACK" -> "SUCCEEDED";
      default -> "ACCEPTED";
    };
  }

  private Plan load(UUID id, String actor) {
    var rows = store.transaction(connection -> query(connection,
        "SELECT * FROM application_release_plan WHERE id=? AND requested_by=?", id, actor));
    if (rows.isEmpty()) throw new ReleaseException(404, "APPLICATION_RELEASE_NOT_FOUND",
        "应用发布计划不存在");
    return plan(rows.getFirst());
  }

  private ReleaseView view(Plan plan) {
    var stages = store.transaction(connection -> query(connection,
        "SELECT ordinal,stage,state,desired_canary_weight,observed_canary_weight,"
            + "observed_stable_weight,analysis_run_name,detail,started_at,finished_at "
            + "FROM application_release_stage WHERE release_id=? ORDER BY ordinal", plan.id()))
        .stream().map(row -> new StageView(number(row.get("ordinal"), 0),
            string(row.get("stage")), string(row.get("state")), nullableInteger(row.get("desired_canary_weight")),
            nullableInteger(row.get("observed_canary_weight")), nullableInteger(row.get("observed_stable_weight")),
            nullableString(row.get("analysis_run_name")), nullableString(row.get("detail")),
            instant(row.get("started_at")), instant(row.get("finished_at")))).toList();
    var measurements = store.transaction(connection -> query(connection,
        "SELECT analysis_run_name,metric_name,measurement_ordinal,phase,value,detail,sampled_at "
            + "FROM application_release_measurement WHERE release_id=? "
            + "ORDER BY sampled_at DESC,metric_name,measurement_ordinal LIMIT 300", plan.id()))
        .stream().map(row -> new MeasurementView(string(row.get("analysis_run_name")),
            string(row.get("metric_name")), number(row.get("measurement_ordinal"), 0),
            string(row.get("phase")), decimal(row.get("value")), nullableString(row.get("detail")),
            instant(row.get("sampled_at")))).toList();
    return new ReleaseView(plan.id().toString(), plan.clusterId().toString(), plan.namespace(),
        plan.applicationName(), plan.rolloutName(), plan.routeName(), plan.hostname(),
        plan.stableImage(), plan.image(),
        plan.containerPort(), plan.replicas(), plan.maxErrorRate(), plan.reason(), plan.state(),
        plan.stableWeight(), plan.canaryWeight(), plan.rolloutPhase(), plan.currentStepMessage(),
        plan.errorCode(), plan.errorMessage(), plan.requestedBy(), plan.createdAt(), plan.startedAt(),
        plan.observedAt(), plan.finishedAt(), stages, measurements);
  }

  private Plan plan(Map<String, Object> row) {
    return new Plan(uuid(row.get("id")), uuid(row.get("cluster_id")), string(row.get("namespace")),
        string(row.get("application_name")), string(row.get("rollout_name")), string(row.get("route_name")),
        string(row.get("analysis_name")), string(row.get("stable_service_name")),
        string(row.get("canary_service_name")), string(row.get("gateway_name")),
        string(row.get("hostname")), string(row.get("prometheus_service_name")),
        string(row.get("stable_image")), string(row.get("image")), number(row.get("container_port"), 8080),
        number(row.get("replicas"), 1), decimal(row.get("max_error_rate")), string(row.get("reason")),
        string(row.get("state")), string(row.get("requested_by")), nullableString(row.get("rollout_uid")),
        nullableString(row.get("route_uid")), nullableInteger(row.get("observed_stable_weight")),
        nullableInteger(row.get("observed_canary_weight")), nullableString(row.get("rollout_phase")),
        nullableString(row.get("current_step_message")), nullableString(row.get("error_code")),
        nullableString(row.get("error_message")), instant(row.get("created_at")),
        instant(row.get("started_at")), instant(row.get("observed_at")), instant(row.get("finished_at")));
  }

  private static Plan controllable(Plan plan) {
    if (List.of("DRAFT", "FAILED", "SUCCEEDED", "ROLLED_BACK").contains(plan.state())) {
      throw new ReleaseException(409, "APPLICATION_RELEASE_STATE_CONFLICT",
          "此发布当前状态不支持该操作");
    }
    return plan;
  }

  private static String observedState(Plan plan, GenericKubernetesResource rollout,
      GenericKubernetesResource route, String phase, String message, Weights weights,
      boolean controllerObservedTarget, boolean successfulAnalysis, String analysisPhase) {
    boolean routeReady = routeReady(route, plan);
    if ("Healthy".equalsIgnoreCase(phase) && routeReady
        && Integer.valueOf(0).equals(weights.canaryWeight())
        && Integer.valueOf(100).equals(weights.stableWeight())
        && conditionTrue(map(rollout, "status").get("conditions"), "Completed")
        && controllerObservedTarget && successfulAnalysis) {
      return "SUCCEEDED";
    }
    if ("Degraded".equalsIgnoreCase(phase) && plan.image().equals(rolloutImage(rollout))
        && routeReady
        && Integer.valueOf(0).equals(weights.canaryWeight())
        && Integer.valueOf(100).equals(weights.stableWeight())
        && ("Failed".equalsIgnoreCase(analysisPhase)
            || "Error".equalsIgnoreCase(analysisPhase)
            || Boolean.TRUE.equals(map(rollout, "spec").get("abort")))) {
      return "ROLLED_BACK";
    }
    if ("Degraded".equalsIgnoreCase(phase)
        && plan.stableImage().equals(rolloutImage(rollout))) return "FAILED";
    if ("Paused".equalsIgnoreCase(phase)) return "PAUSED";
    if ("Progressing".equalsIgnoreCase(phase)) return "RUNNING";
    Object aborted = map(rollout, "spec").get("abort");
    return Boolean.TRUE.equals(aborted) ? "RUNNING" : plan.state();
  }

  private static String stageName(Integer canaryWeight, String state) {
    if ("SUCCEEDED".equals(state)) return "STABLE_FINAL";
    if ("ROLLED_BACK".equals(state)) return "ROLLED_BACK";
    return "CANARY_" + (canaryWeight == null ? "UNKNOWN" : canaryWeight);
  }

  private static String stageState(String state, String analysisPhase) {
    if ("SUCCEEDED".equals(state)) return "SUCCEEDED";
    if ("ROLLED_BACK".equals(state) || "FAILED".equals(state)
        || "FAILED".equalsIgnoreCase(analysisPhase) || "ERROR".equalsIgnoreCase(analysisPhase)) {
      return "FAILED";
    }
    if ("INCONCLUSIVE".equalsIgnoreCase(analysisPhase)) return "INCONCLUSIVE";
    return "OBSERVED";
  }

  private static boolean referencesGateway(Object parentRefs, String gatewayName,
      String gatewayNamespace) {
    return list(parentRefs).stream().map(KubernetesApplicationReleaseService::childMap)
        .anyMatch(parent -> gatewayName.equals(parent.get("name"))
            && (parent.get("namespace") == null
                || gatewayNamespace.equals(parent.get("namespace"))));
  }

  static String backgroundAnalysisName(Map<String, Object> status) {
    Map<String, Object> canary = childMap(status.get("canary"));
    Map<String, Object> step = childMap(canary.get("currentStepAnalysisRunStatus"));
    String stepName = emptyToNull(string(step.get("name")));
    if (stepName != null) return stepName;
    Map<String, Object> current = childMap(canary.get("currentBackgroundAnalysisRunStatus"));
    return emptyToNull(string(current.get("name")));
  }

  private GenericKubernetesResource currentAnalysisRun(KubernetesClient client, Plan plan,
      GenericKubernetesResource rollout, Map<String, Object> rolloutStatus) {
    String runName = backgroundAnalysisName(rolloutStatus);
    if (runName != null) {
      GenericKubernetesResource named = getCustom(client, ROLLOUT_API, "AnalysisRun",
          plan.namespace(), runName);
      if (named != null) return named;
    }
    String currentHash = string(rolloutStatus.get("currentPodHash"));
    String rolloutUid = rollout.getMetadata() == null ? null : rollout.getMetadata().getUid();
    if (currentHash.isBlank() || rolloutUid == null) return null;
    var analysisRuns = client.genericKubernetesResources(ROLLOUT_API, "AnalysisRun")
        .inNamespace(plan.namespace()).list();
    if (analysisRuns == null || analysisRuns.getItems() == null) return null;
    GenericKubernetesResource latest = null;
    String latestCreatedAt = "";
    for (GenericKubernetesResource candidate : analysisRuns.getItems()) {
      if (candidate.getMetadata() == null) continue;
      Map<String, String> labels = candidate.getMetadata().getLabels();
      if (!analysisRunApplicationMatches(plan.applicationName(), labels)
          || !currentHash.equals(labels.get("rollouts-pod-template-hash"))) continue;
      var owners = candidate.getMetadata().getOwnerReferences();
      boolean ownedByRollout = owners != null && owners.stream().anyMatch(owner ->
          "Rollout".equals(owner.getKind()) && plan.rolloutName().equals(owner.getName())
              && rolloutUid.equals(owner.getUid()));
      if (!ownedByRollout) continue;
      String createdAt = candidate.getMetadata().getCreationTimestamp();
      if (latest == null || (createdAt != null && createdAt.compareTo(latestCreatedAt) > 0)) {
        latest = candidate;
        latestCreatedAt = createdAt == null ? "" : createdAt;
      }
    }
    return latest;
  }

  private static Weights routeWeights(Map<String, Object> routeSpec, Plan plan) {
    var rules = list(routeSpec.get("rules"));
    if (rules.isEmpty()) return new Weights(null, null);
    var backends = list(childMap(rules.getFirst()).get("backendRefs"));
    Integer stable = null;
    Integer canary = null;
    for (Object backendValue : backends) {
      Map<String, Object> backend = childMap(backendValue);
      String name = string(backend.get("name"));
      int weight = number(backend.get("weight"), 1);
      if (plan.stableService().equals(name)) stable = weight;
      if (plan.canaryService().equals(name)) canary = weight;
    }
    return new Weights(stable, canary);
  }

  private static boolean conditionTrue(Object values, String type) {
    return list(values).stream().map(KubernetesApplicationReleaseService::childMap)
        .anyMatch(condition -> type.equals(condition.get("type"))
            && "True".equals(condition.get("status")));
  }

  private static boolean listenerProgrammed(GenericKubernetesResource gateway) {
    return list(map(gateway, "status").get("listeners")).stream()
        .map(KubernetesApplicationReleaseService::childMap)
        .anyMatch(listener -> conditionTrue(listener.get("conditions"), "Accepted")
            && conditionTrue(listener.get("conditions"), "Programmed"));
  }

  private static boolean routeReady(GenericKubernetesResource route, Plan plan) {
    return list(map(route, "status").get("parents")).stream()
        .map(KubernetesApplicationReleaseService::childMap)
        .anyMatch(parent -> {
          Map<String, Object> ref = childMap(parent.get("parentRef"));
          boolean sameGateway = plan.gatewayName().equals(ref.get("name"))
              && (ref.get("namespace") == null
                  || plan.namespace().equals(ref.get("namespace")));
          return sameGateway && conditionTrue(parent.get("conditions"), "Accepted")
              && conditionTrue(parent.get("conditions"), "ResolvedRefs");
        });
  }

  private boolean activateCanaryImage(KubernetesClient client, Plan plan,
      GenericKubernetesResource rollout, GenericKubernetesResource route, Weights weights,
      String phase, String actor) {
    String currentImage = rolloutImage(rollout);
    if (plan.image().equals(currentImage)) return false;
    if (!plan.stableImage().equals(currentImage)) {
      throw new ReleaseException(409, "APPLICATION_RELEASE_IMAGE_CONFLICT",
          "Rollout 容器镜像与持久化稳定基线或目标镜像均不匹配，拒绝继续发布");
    }
    if (!"Healthy".equalsIgnoreCase(phase) || !routeReady(route, plan)
        || !Integer.valueOf(100).equals(weights.stableWeight())
        || !Integer.valueOf(0).equals(weights.canaryWeight())) return false;
    Map<String, Object> template = childMap(map(rollout, "spec").get("template"));
    Map<String, Object> podSpec = childMap(template.get("spec"));
    List<?> containers = list(podSpec.get("containers"));
    int appIndex = -1;
    for (int index = 0; index < containers.size(); index++) {
      if ("app".equals(string(childMap(containers.get(index)).get("name")))) {
        appIndex = index;
        break;
      }
    }
    if (appIndex < 0) {
      throw new ReleaseException(409, "APPLICATION_RELEASE_IMAGE_CONFLICT",
          "Rollout 中不存在受管的 app 容器，拒绝替换容器配置");
    }
    String imagePatch = json(List.of(Map.of(
        "op", "replace",
        "path", "/spec/template/spec/containers/" + appIndex + "/image",
        "value", plan.image())));
    client.genericKubernetesResources(ROLLOUT_API, "Rollout").inNamespace(plan.namespace())
        .withName(plan.rolloutName()).patch(PatchContext.of(PatchType.JSON), imagePatch);
    Instant now = Instant.now();
    store.transaction(connection -> {
      int ordinal = query(connection,
          "SELECT COALESCE(MAX(ordinal),0)+1 AS next_ordinal FROM application_release_stage WHERE release_id=?",
          plan.id()).getFirst().get("next_ordinal") instanceof Number value ? value.intValue() : 1;
      update(connection,
          "INSERT INTO application_release_stage (release_id,ordinal,stage,state,"
              + "desired_canary_weight,detail,started_at) VALUES (?,?,?,'ACTIVE',?,?,?)",
          plan.id(), ordinal, "CANARY_IMAGE_APPLIED", 10,
          "稳定基线已通过 Gateway API 观测；已提交目标镜像，等待 Argo 创建 canary ReplicaSet",
          Timestamp.from(now));
      audit(connection, actor, "KUBERNETES_APPLICATION_RELEASE_CANARY_STARTED",
          "application_release_plan", plan.id().toString(), "ACCEPTED", null,
          "稳定镜像已验证，已将 Rollout 镜像切换到目标版本以启动 canary");
      return null;
    });
    return true;
  }

  private static String rolloutImage(GenericKubernetesResource rollout) {
    Map<String, Object> template = childMap(map(rollout, "spec").get("template"));
    Map<String, Object> podSpec = childMap(template.get("spec"));
    return list(podSpec.get("containers")).stream().map(KubernetesApplicationReleaseService::childMap)
        .filter(container -> "app".equals(container.get("name")))
        .map(container -> string(container.get("image"))).findFirst().orElse("");
  }

  private static boolean controllerObservedGeneration(GenericKubernetesResource rollout) {
    Long generation = rollout.getMetadata() == null ? null : rollout.getMetadata().getGeneration();
    if (generation == null) return false;
    return number(map(rollout, "status").get("observedGeneration"), -1) >= generation;
  }

  private static boolean analysisRunPassed(GenericKubernetesResource run, Plan plan,
      GenericKubernetesResource rollout) {
    if (!"Successful".equalsIgnoreCase(string(map(run, "status").get("phase")))) return false;
    String currentHash = string(map(rollout, "status").get("currentPodHash"));
    Map<String, String> labels = run.getMetadata() == null ? Map.of()
        : run.getMetadata().getLabels();
    if (currentHash.isBlank() || !analysisRunApplicationMatches(plan.applicationName(), labels)
        || !currentHash.equals(labels.get("rollouts-pod-template-hash"))) return false;
    String rolloutUid = rollout.getMetadata() == null ? null : rollout.getMetadata().getUid();
    var ownerReferences = run.getMetadata().getOwnerReferences();
    boolean ownedByRollout = ownerReferences != null && ownerReferences.stream()
        .anyMatch(reference -> "Rollout".equals(reference.getKind())
            && plan.rolloutName().equals(reference.getName())
            && rolloutUid != null && rolloutUid.equals(reference.getUid()));
    if (!ownedByRollout) return false;
    BigDecimal successLimit = plan.maxErrorRate().divide(new BigDecimal("2"), 8,
        java.math.RoundingMode.HALF_UP);
    boolean errorRatePassed = false;
    for (Object resultValue : list(map(run, "status").get("metricResults"))) {
      Map<String, Object> result = childMap(resultValue);
      if (!"canary-http-error-rate".equals(result.get("name"))) continue;
      List<?> measurements = list(result.get("measurements"));
      int requiredCount = number(result.get("count"), 3);
      int consecutiveSuccess = number(result.get("consecutiveSuccess"), 0);
      if (measurements.size() < requiredCount || consecutiveSuccess < requiredCount
          || !"Successful".equalsIgnoreCase(string(result.get("phase")))) return false;
      errorRatePassed = measurements.subList(measurements.size() - requiredCount,
          measurements.size()).stream().allMatch(value ->
          "Successful".equalsIgnoreCase(string(childMap(value).get("phase")))
              && numericMeasurement(string(childMap(value).get("value"))) != null
              && numericMeasurement(string(childMap(value).get("value")))
                  .compareTo(successLimit) <= 0);
    }
    return errorRatePassed;
  }

  static boolean analysisRunApplicationMatches(String applicationName,
      Map<String, String> labels) {
    return labels != null && (applicationName.equals(labels.get("app"))
        || applicationName.equals(labels.get("app.kubernetes.io/name")));
  }

  private GenericKubernetesResource getCustom(KubernetesClient client, String apiVersion,
      String kind, String namespace, String name) {
    return client.genericKubernetesResources(apiVersion, kind).inNamespace(namespace)
        .withName(name).get();
  }

  private void requireOwned(GenericKubernetesResource resource, Plan plan) {
    if (resource == null) throw new ReleaseException(404, "APPLICATION_RELEASE_RESOURCE_MISSING",
        "应用发布资源不存在");
    if (!owned(resource, plan)) throw new ReleaseException(409,
        "APPLICATION_RELEASE_OWNERSHIP_CONFLICT", "Kubernetes 资源所有权标记已变化，已停止操作");
  }

  private static void requireIdentity(GenericKubernetesResource resource, String expectedUid) {
    String actualUid = resource.getMetadata() == null ? null : resource.getMetadata().getUid();
    if (expectedUid != null && !expectedUid.equals(actualUid)) {
      throw new ReleaseException(409, "APPLICATION_RELEASE_RESOURCE_REPLACED",
          "发布资源 UID 已变化，拒绝操作被替换的 Kubernetes 资源");
    }
  }

  private static boolean owned(GenericKubernetesResource resource, Plan plan) {
    if (resource.getMetadata() == null || resource.getMetadata().getLabels() == null) return false;
    var labels = resource.getMetadata().getLabels();
    return "nanpanel".equals(labels.get(MANAGED_LABEL))
        && plan.id().toString().equals(labels.get(RELEASE_LABEL));
  }

  private RuntimeException apiFailure(UUID clusterId, RuntimeException failure) {
    if (failure instanceof io.fabric8.kubernetes.client.KubernetesClientException) {
      RuntimeException reported = clusters.reportApiFailure(clusterId, failure);
      if (reported instanceof KubernetesClusterRegistryService.ClusterPermissionDeniedException) {
        return new ReleaseException(403, "KUBERNETES_RBAC_DENIED",
            "集群凭据没有执行应用发布操作的权限");
      }
      if (reported instanceof KubernetesClusterRegistryService.ClusterCredentialRejectedException) {
        return new ReleaseException(502, "KUBERNETES_CREDENTIALS_REJECTED",
            "已保存的集群凭据被 Kubernetes API 拒绝");
      }
    }
    return new ReleaseException(502, "KUBERNETES_CLUSTER_UNAVAILABLE",
        "无法读取或更新 Kubernetes 应用发布资源");
  }

  static void validate(String namespace, CreateRequest input) {
    if (input == null) throw new ReleaseException(422, "VALIDATION_FAILED", "发布请求不能为空");
    dnsLabel(namespace, "namespace");
    dnsLabel(input.name(), "name");
    dnsLabel(input.gatewayName(), "gatewayName");
    dnsLabel(input.prometheusService(), "prometheusService");
    if (input.stableImage() == null || input.stableImage().length() > 512
        || !IMAGE.matcher(input.stableImage()).matches()) {
      throw new ReleaseException(422, "VALIDATION_FAILED", "稳定基线镜像引用无效");
    }
    if (input.image() == null || input.image().length() > 512 || !IMAGE.matcher(input.image()).matches()) {
      throw new ReleaseException(422, "VALIDATION_FAILED", "container image reference 无效");
    }
    if (input.stableImage().equals(input.image())) {
      throw new ReleaseException(422, "VALIDATION_FAILED", "目标镜像必须与稳定基线镜像不同");
    }
    if (input.containerPort() == null || input.containerPort() < 1 || input.containerPort() > 65535) {
      throw new ReleaseException(422, "VALIDATION_FAILED", "containerPort 必须在 1 到 65535 之间");
    }
    if (input.replicas() == null || input.replicas() < 1 || input.replicas() > 20) {
      throw new ReleaseException(422, "VALIDATION_FAILED", "replicas 必须在 1 到 20 之间");
    }
    if (input.hostname() == null || input.hostname().length() > 253
        || !HOSTNAME.matcher(input.hostname()).matches()) {
      throw new ReleaseException(422, "VALIDATION_FAILED", "hostname 必须是小写 DNS 主机名");
    }
    if (input.reason() == null || input.reason().trim().isEmpty() || input.reason().length() > 500
        || input.reason().chars().anyMatch(Character::isISOControl)) {
      throw new ReleaseException(422, "VALIDATION_FAILED", "reason 必填且长度不能超过 500 字符");
    }
    if (input.maxErrorRate() != null && (input.maxErrorRate().compareTo(new BigDecimal("0.01")) < 0
        || input.maxErrorRate().compareTo(new BigDecimal("0.5")) > 0)) {
      throw new ReleaseException(422, "VALIDATION_FAILED", "maxErrorRate 必须在 0.01 到 0.5 之间");
    }
  }

  private static void dnsLabel(String value, String field) {
    if (value == null || value.length() > 63 || !DNS_LABEL.matcher(value).matches()) {
      throw new ReleaseException(422, "VALIDATION_FAILED", field + " 必须是 Kubernetes DNS label");
    }
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException invalid) {
      throw new IllegalStateException("Unable to serialize validated Kubernetes patch", invalid);
    }
  }

  private static Map<String, Object> map(GenericKubernetesResource resource, String key) {
    return childMap(resource.getAdditionalProperties().get(key));
  }

  private static Map<String, Object> childMap(Object value) {
    if (!(value instanceof Map<?, ?> raw)) return Map.of();
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>)raw;
    return result;
  }

  private static List<?> list(Object value) {
    return value instanceof List<?> values ? values : List.of();
  }

  private static List<String> stringList(Object value) {
    return list(value).stream().map(KubernetesApplicationReleaseService::string).toList();
  }

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String nullableString(Object value) {
    return value == null ? null : string(value);
  }

  private static String emptyToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static int number(Object value, int fallback) {
    if (value instanceof Number numeric) return numeric.intValue();
    if (value instanceof String text) {
      try { return Integer.parseInt(text); }
      catch (NumberFormatException invalid) { return fallback; }
    }
    return fallback;
  }

  private static Integer nullableInteger(Object value) {
    return value instanceof Number numeric ? numeric.intValue() : null;
  }

  private static BigDecimal decimal(Object value) {
    if (value instanceof BigDecimal decimal) return decimal;
    if (value instanceof Number number) return new BigDecimal(number.toString());
    return value == null ? null : new BigDecimal(String.valueOf(value));
  }

  private static UUID uuid(Object value) {
    return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
  }

  private static Instant instant(Object value) {
    if (value instanceof Instant instant) return instant;
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof OffsetDateTime dateTime) return dateTime.toInstant();
    if (value instanceof java.util.Date date) return date.toInstant();
    if (value instanceof String text) {
      try { return OffsetDateTime.parse(text).toInstant(); }
      catch (java.time.format.DateTimeParseException ignored) {
        try { return Instant.parse(text); }
        catch (java.time.format.DateTimeParseException invalid) { return null; }
      }
    }
    return null;
  }

  private static BigDecimal numericMeasurement(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      JsonNode node = new ObjectMapper().readTree(value);
      if (node.isArray() && !node.isEmpty()) return new BigDecimal(node.get(0).asText());
      if (node.isNumber()) return node.decimalValue();
    } catch (JsonProcessingException ignored) {
      try { return new BigDecimal(value); }
      catch (NumberFormatException invalid) { return null; }
    }
    return null;
  }

  private static final class Weights {
    private final Integer stableWeight;
    private final Integer canaryWeight;
    private Weights(Integer stableWeight, Integer canaryWeight) {
      this.stableWeight = stableWeight;
      this.canaryWeight = canaryWeight;
    }
    Integer stableWeight() { return stableWeight; }
    Integer canaryWeight() { return canaryWeight; }
  }

  public static final class ReleaseException extends RuntimeException {
    private static final long serialVersionUID = 1L;
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
