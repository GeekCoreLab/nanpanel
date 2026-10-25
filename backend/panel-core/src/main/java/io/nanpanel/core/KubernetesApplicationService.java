package io.nanpanel.core;

import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Namespace-scoped applications backed by immutable, YAML-derived versions. */
@ApplicationScoped
public class KubernetesApplicationService {
  private static final String TEMPLATE_BOOKINFO = "bookinfo";
  private static final String MANAGED_BY = "app.kubernetes.io/managed-by";
  private static final String APPLICATION_ID = "nanpanel.io/application-id";
  private static final int MAX_APPLICATIONS = 200;
  private static final int MAX_REPLICAS = 20;
  private static final Map<String, Integer> BOOKINFO_RESOURCE_COUNTS = Map.of(
      "ServiceAccount", 4,
      "Service", 4,
      "Deployment", 6);
  private static final Pattern DNS_LABEL =
      Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern IMAGE =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/:@+-]{0,511}");

  private static final String BOOKINFO_MANIFEST = """
      apiVersion: v1
      kind: ServiceAccount
      metadata:
        name: details
      ---
      apiVersion: v1
      kind: ServiceAccount
      metadata:
        name: ratings
      ---
      apiVersion: v1
      kind: ServiceAccount
      metadata:
        name: reviews
      ---
      apiVersion: v1
      kind: ServiceAccount
      metadata:
        name: productpage
      ---
      apiVersion: v1
      kind: Service
      metadata:
        name: details
      spec:
        ports:
        - name: http
          port: 9080
          targetPort: 9080
        selector:
          app: details
      ---
      apiVersion: v1
      kind: Service
      metadata:
        name: ratings
      spec:
        ports:
        - name: http
          port: 9080
          targetPort: 9080
        selector:
          app: ratings
      ---
      apiVersion: v1
      kind: Service
      metadata:
        name: reviews
      spec:
        ports:
        - name: http
          port: 9080
          targetPort: 9080
        selector:
          app: reviews
      ---
      apiVersion: v1
      kind: Service
      metadata:
        name: productpage
      spec:
        ports:
        - name: http
          port: 9080
          targetPort: 9080
        selector:
          app: productpage
      ---
      apiVersion: apps/v1
      kind: Deployment
      metadata:
        name: details-v1
        labels:
          app: details
          version: v1
      spec:
        replicas: 1
        selector:
          matchLabels:
            app: details
            version: v1
        template:
          metadata:
            labels:
              app: details
              version: v1
          spec:
            serviceAccountName: details
            containers:
            - name: details
              image: registry.istio.io/release/examples-bookinfo-details-v1:1.20.3
              imagePullPolicy: IfNotPresent
              ports:
              - containerPort: 9080
      ---
      apiVersion: apps/v1
      kind: Deployment
      metadata:
        name: ratings-v1
        labels:
          app: ratings
          version: v1
      spec:
        replicas: 1
        selector:
          matchLabels:
            app: ratings
            version: v1
        template:
          metadata:
            labels:
              app: ratings
              version: v1
          spec:
            serviceAccountName: ratings
            containers:
            - name: ratings
              image: registry.istio.io/release/examples-bookinfo-ratings-v1:1.20.3
              imagePullPolicy: IfNotPresent
              ports:
              - containerPort: 9080
      ---
      apiVersion: apps/v1
      kind: Deployment
      metadata:
        name: reviews-v1
        labels:
          app: reviews
          version: v1
      spec:
        replicas: 1
        selector:
          matchLabels:
            app: reviews
            version: v1
        template:
          metadata:
            labels:
              app: reviews
              version: v1
          spec:
            serviceAccountName: reviews
            containers:
            - name: reviews
              image: registry.istio.io/release/examples-bookinfo-reviews-v1:1.20.3
              imagePullPolicy: IfNotPresent
              ports:
              - containerPort: 9080
      ---
      apiVersion: apps/v1
      kind: Deployment
      metadata:
        name: reviews-v2
        labels:
          app: reviews
          version: v2
      spec:
        replicas: 1
        selector:
          matchLabels:
            app: reviews
            version: v2
        template:
          metadata:
            labels:
              app: reviews
              version: v2
          spec:
            serviceAccountName: reviews
            containers:
            - name: reviews
              image: registry.istio.io/release/examples-bookinfo-reviews-v2:1.20.3
              imagePullPolicy: IfNotPresent
              ports:
              - containerPort: 9080
      ---
      apiVersion: apps/v1
      kind: Deployment
      metadata:
        name: reviews-v3
        labels:
          app: reviews
          version: v3
      spec:
        replicas: 1
        selector:
          matchLabels:
            app: reviews
            version: v3
        template:
          metadata:
            labels:
              app: reviews
              version: v3
          spec:
            serviceAccountName: reviews
            containers:
            - name: reviews
              image: registry.istio.io/release/examples-bookinfo-reviews-v3:1.20.3
              imagePullPolicy: IfNotPresent
              ports:
              - containerPort: 9080
      ---
      apiVersion: apps/v1
      kind: Deployment
      metadata:
        name: productpage-v1
        labels:
          app: productpage
          version: v1
      spec:
        replicas: 1
        selector:
          matchLabels:
            app: productpage
            version: v1
        template:
          metadata:
            labels:
              app: productpage
              version: v1
          spec:
            serviceAccountName: productpage
            containers:
            - name: productpage
              image: registry.istio.io/release/examples-bookinfo-productpage-v1:1.20.3
              imagePullPolicy: IfNotPresent
              ports:
              - containerPort: 9080
      """;

  @Inject AuthStore store;
  @Inject KubernetesClusterRegistryService clusters;
  @Inject KubernetesApplicationManifestFactory manifestFactory;

  public record CreateBookinfoRequest(UUID clusterId, String namespace, String displayName) {}
  public record CreateApplicationRequest(UUID clusterId, String namespace, String applicationName,
      String displayName, String source, String image, Integer replicas, Integer containerPort,
      Integer servicePort, Map<String, String> environment, String definition) {}
  public record ApplicationPreview(Map<String, Integer> resourceCounts, int totalResources,
      String manifestYaml, List<String> notes) {}
  public record DraftRequest(Integer baseVersion, List<TopologyEdit> edits) {}
  public record TopologyEdit(String resourceId, Integer replicas, String image,
      String targetDeployment) {}
  public record ApplicationList(List<ApplicationView> items) {}
  public record ApplicationView(UUID id, UUID clusterId, String clusterName, String namespace,
      String name, String displayName, String templateId, int currentVersion, String state,
      Instant createdAt, Instant updatedAt) {}
  public record TopologyNode(String id, String kind, String name, Integer replicas,
      Integer readyReplicas, String image, String targetDeployment, String state) {}
  public record TopologyEdge(String id, String source, String target) {}
  public record Topology(List<TopologyNode> nodes, List<TopologyEdge> edges) {}
  public record ApplicationDetail(ApplicationView application, Topology topology,
      String manifestYaml) {}
  public record VersionView(int version, int baseVersion, String state, String manifestSha256,
      String manifestDiff, String errorMessage, Instant createdAt, Instant deployedAt) {}
  public record VersionList(List<VersionView> items) {}

  private record AppRow(UUID id, UUID clusterId, String clusterName, String namespace,
      String name, String displayName, String templateId, int currentVersion, String state,
      Instant createdAt, Instant updatedAt, String createdBy) {}
  private record StoredVersion(int version, int baseVersion, String manifestYaml,
      String manifestSha256, String manifestDiff, String state, String errorMessage,
      Instant createdAt, Instant deployedAt) {}

  public ApplicationList list() {
    List<Map<String, Object>> rows = store.transaction(connection -> query(connection,
        "SELECT a.id,a.cluster_id,c.name AS cluster_name,a.namespace,a.application_name,"
            + "a.display_name,a.template_id,a.current_version,a.state,a.created_at,a.updated_at,"
            + "a.created_by FROM kubernetes_application a JOIN kubernetes_cluster c "
            + "ON c.id=a.cluster_id ORDER BY a.updated_at DESC LIMIT ?", MAX_APPLICATIONS + 1));
    if (rows.size() > MAX_APPLICATIONS) throw new ApplicationException(413,
        "KUBERNETES_APPLICATION_LIMIT", "应用数量超过单次返回上限，请联系管理员");
    return new ApplicationList(rows.stream().map(KubernetesApplicationService::application).toList());
  }

  public ApplicationDetail get(UUID applicationId) {
    AppRow app = app(applicationId);
    StoredVersion current = app.currentVersion() == 0 ? latestVersion(applicationId)
        : version(applicationId, app.currentVersion());
    if (current == null || app.currentVersion() == 0) {
      return new ApplicationDetail(view(app), new Topology(List.of(), List.of()),
          current == null ? "" : current.manifestYaml());
    }
    try (KubernetesClient client = clusters.openClientForOperations(app.clusterId())) {
      List<HasMetadata> resources = parse(client, current.manifestYaml());
      return new ApplicationDetail(view(app), topology(client, app.id(), app.namespace(),
          resources, resourceUids(applicationId)), current.manifestYaml());
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw missing;
    } catch (KubernetesClusterRegistryService.ClusterCredentialRejectedException
        | KubernetesClusterRegistryService.ClusterPermissionDeniedException
        | KubernetesClusterRegistryService.ClusterApiUnavailableException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw apiFailure(failure);
    }
  }

  public VersionList versions(UUID applicationId) {
    app(applicationId);
    List<Map<String, Object>> rows = store.transaction(connection -> query(connection,
        "SELECT version,base_version,manifest_sha256,manifest_diff,state,error_message,"
            + "created_at,deployed_at FROM kubernetes_application_version "
            + "WHERE application_id=? ORDER BY version DESC", applicationId));
    return new VersionList(rows.stream().map(KubernetesApplicationService::versionView).toList());
  }

  public ApplicationDetail createBookinfo(String actor, String remoteAddress,
      CreateBookinfoRequest input) {
    if (input == null) throw invalid("创建请求不能为空");
    return createApplication(actor, remoteAddress, new CreateApplicationRequest(input.clusterId(),
        input.namespace(), "bookinfo", input.displayName(), "BOOKINFO", null, null, null, null,
        Map.of(), null));
  }

  public ApplicationPreview preview(CreateApplicationRequest input) {
    ValidatedCreate create = validateCreate(input);
    List<HasMetadata> resources = prepareInitialResources(create.definition().resources(),
        input.namespace(), UUID.randomUUID(), create.definition().templateId());
    Map<String, Integer> counts = new java.util.TreeMap<>();
    resources.forEach(resource -> counts.merge(resource.getKind(), 1, Integer::sum));
    List<String> notes = new ArrayList<>(create.definition().notes());
    resources.forEach(resource -> resource.getMetadata().getLabels().remove(APPLICATION_ID));
    notes.add("创建时会为每个资源写入此应用专属的所有权 UUID 标签。");
    if (resources.stream().anyMatch(ServiceAccount.class::isInstance)) {
      notes.add("应用内 ServiceAccount 不会自动挂载 Kubernetes API Token；镜像拉取凭据引用会保留。");
    }
    List<String> externalAccounts = externalServiceAccounts(resources);
    if (!externalAccounts.isEmpty()) {
      notes.add("Deployment 会引用但不会修改这些外部 ServiceAccount；创建前会检查其存在："
          + String.join(", ", externalAccounts));
    }
    return new ApplicationPreview(Map.copyOf(counts), resources.size(), serialize(resources),
        List.copyOf(notes));
  }

  public ApplicationDetail createApplication(String actor, String remoteAddress,
      CreateApplicationRequest input) {
    ValidatedCreate create = validateCreate(input);
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    String displayName = input.displayName().trim();
    String applicationName = create.applicationName();
    String templateId = create.definition().templateId();
    String yaml;
    List<HasMetadata> resources;
    try (KubernetesClient client = clusters.openClientForOperations(input.clusterId())) {
      if (client.namespaces().withName(input.namespace()).get() == null) {
        throw new ApplicationException(404, "KUBERNETES_NAMESPACE_NOT_FOUND",
            "目标 Namespace 不存在，请选择集群中已存在的 Namespace");
      }
      resources = prepareInitialResources(create.definition().resources(), input.namespace(), id,
          templateId);
      yaml = serialize(resources);
      ensureReferencedServiceAccountsExist(client, input.namespace(), resources);
      ensureResourcesAvailable(client, input.namespace(), resources);
      AppRow app = new AppRow(id, input.clusterId(), clusterName(input.clusterId()), input.namespace(),
          applicationName, displayName, templateId, 0, "APPLYING", now, now, actor);
      try {
        store.transaction(connection -> {
          update(connection, "INSERT INTO kubernetes_application (id,cluster_id,namespace,"
                  + "application_name,display_name,template_id,current_version,state,created_by,"
                  + "created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
              id, input.clusterId(), input.namespace(), applicationName, displayName,
              templateId, 0, "APPLYING", actor, Timestamp.from(now), Timestamp.from(now));
          update(connection, "INSERT INTO kubernetes_application_version (application_id,version,"
                  + "base_version,manifest_yaml,manifest_sha256,manifest_diff,state,requested_by,created_at) "
                  + "VALUES (?,?,?,?,?,?,?,?,?)",
              id, 1, 0, yaml, sha256(yaml),
              "初始版本：通过 " + templateId + " 创建 " + resources.size()
                  + " 个 Namespace 资源。",
              "APPLYING", actor, Timestamp.from(now));
          String action = TEMPLATE_BOOKINFO.equals(templateId)
              ? "KUBERNETES_APPLICATION_BOOKINFO_CREATED" : "KUBERNETES_APPLICATION_CREATED";
          audit(connection, actor, action,
              "kubernetes_application", id.toString(), "ACCEPTED", remoteAddress,
              "已接受应用创建；来源 " + templateId + "，集群 " + input.clusterId()
                  + "，Namespace " + input.namespace());
          return null;
        });
      } catch (RuntimeException failure) {
        throw new ApplicationException(409, "KUBERNETES_APPLICATION_EXISTS",
            "该集群 Namespace 中已有同名应用或记录");
      }
      boolean rolledBack = false;
      try {
        List<HasMetadata> created = createResources(client, input.namespace(), resources);
        recordResources(id, created);
      } catch (RuntimeException deployFailure) {
        rolledBack = rollbackCreated(client, input.namespace(), id, resources);
        String state = rolledBack ? "FAILED" : "PARTIAL";
        String message = safeMessage(deployFailure);
        markVersionFailure(actor, remoteAddress, id, state, message,
            "应用初始资源创建" + (rolledBack ? "失败并已清理已创建资源" : "部分失败，清理未能全部完成"));
        throw new ApplicationException(rolledBack ? 502 : 503,
            rolledBack ? "KUBERNETES_APPLICATION_CREATE_FAILED" : "KUBERNETES_APPLICATION_PARTIAL",
            rolledBack ? "应用创建失败，已清理本次创建的资源" : "应用创建部分失败，请查看应用状态并手动核对资源");
      }
      Topology initialTopology;
      try {
        initialTopology = topology(client, id, input.namespace(), resources, resourceUids(id));
      } catch (RuntimeException topologyFailure) {
        markVersionFailure(actor, remoteAddress, id, "PARTIAL", safeMessage(topologyFailure),
            "应用已创建，但拓扑观测发现资源所有权不匹配");
        throw new ApplicationException(503, "KUBERNETES_APPLICATION_PARTIAL",
            "应用资源已创建，但所有权校验未通过；应用状态为 PARTIAL");
      }
      finishDeployment(actor, remoteAddress, id, 1, "应用初始版本已部署");
      AppRow deployed = new AppRow(app.id(), app.clusterId(), app.clusterName(), app.namespace(),
          app.name(), app.displayName(), app.templateId(), 1, "DEPLOYED", app.createdAt(), now, actor);
      return new ApplicationDetail(view(deployed), initialTopology, yaml);
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw missing;
    } catch (ApplicationException failure) {
      throw failure;
    } catch (KubernetesClusterRegistryService.ClusterCredentialRejectedException
        | KubernetesClusterRegistryService.ClusterPermissionDeniedException
        | KubernetesClusterRegistryService.ClusterApiUnavailableException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw apiFailure(failure);
    }
  }

  private record ValidatedCreate(String applicationName,
      KubernetesApplicationManifestFactory.Definition definition) {}

  public ApplicationDetail createDraft(String actor, String remoteAddress, UUID applicationId,
      DraftRequest input) {
    if (input == null || input.baseVersion() == null || input.baseVersion() < 1
        || input.edits() == null || input.edits().isEmpty() || input.edits().size() > 32) {
      throw invalid("A version draft requires a base version and 1–32 topology edits");
    }
    AppRow app = app(applicationId);
    if (!"DEPLOYED".equals(app.state())) {
      throw new ApplicationException(409, "KUBERNETES_APPLICATION_NOT_READY",
          "应用当前状态不允许创建版本草稿");
    }
    if (input.baseVersion() != app.currentVersion()) {
      throw new ApplicationException(409, "KUBERNETES_APPLICATION_VERSION_STALE",
          "应用版本已变化，请刷新拓扑后重新创建草稿");
    }
    try (KubernetesClient client = clusters.openClientForOperations(app.clusterId())) {
      StoredVersion current = version(applicationId, app.currentVersion());
      if (current == null) throw new ApplicationException(409,
          "KUBERNETES_APPLICATION_VERSION_MISSING", "找不到当前应用版本清单");
      List<HasMetadata> before = parse(client, current.manifestYaml());
      List<HasMetadata> after = applyEdits(before, input.edits());
      String newYaml = serialize(after);
      String diff = resourceDiff(before, after);
      if (diff.isBlank()) throw invalid("拓扑没有产生 YAML 变化，请调整副本数、镜像或 Service 连接");
      Topology candidateTopology = topology(client, app.id(), app.namespace(), after,
          resourceUids(applicationId));
      Instant now = Instant.now();
      int nextVersion = store.transaction(connection -> {
        List<Map<String, Object>> locked = query(connection,
            "SELECT current_version,state FROM kubernetes_application WHERE id=? FOR UPDATE",
            applicationId);
        if (locked.isEmpty() || number(locked.getFirst().get("current_version")) != input.baseVersion()
            || !"DEPLOYED".equals(String.valueOf(locked.getFirst().get("state")))) {
          throw new ApplicationException(409, "KUBERNETES_APPLICATION_VERSION_STALE",
              "应用版本已变化或当前状态不允许创建草稿");
        }
        List<Map<String, Object>> pending = query(connection,
            "SELECT version FROM kubernetes_application_version "
                + "WHERE application_id=? AND state='DRAFT'", applicationId);
        if (!pending.isEmpty()) throw new ApplicationException(409,
            "KUBERNETES_APPLICATION_DRAFT_EXISTS", "请先检查并部署当前待确认草稿");
        List<Map<String, Object>> next = query(connection,
            "SELECT COALESCE(MAX(version),0)+1 AS next_version FROM kubernetes_application_version "
                + "WHERE application_id=?", applicationId);
        int versionNumber = number(next.getFirst().get("next_version"));
        update(connection, "INSERT INTO kubernetes_application_version (application_id,version,"
                + "base_version,manifest_yaml,manifest_sha256,manifest_diff,state,requested_by,created_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?)",
            applicationId, versionNumber, input.baseVersion(), newYaml, sha256(newYaml), diff,
            "DRAFT", actor, Timestamp.from(now));
        audit(connection, actor, "KUBERNETES_APPLICATION_VERSION_DRAFTED",
            "kubernetes_application", applicationId.toString(), "SUCCEEDED", remoteAddress,
            "已生成应用版本 v" + versionNumber + " 的拓扑 YAML 差异，尚未写入集群");
        return versionNumber;
      });
      return new ApplicationDetail(view(app), candidateTopology, newYaml);
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw missing;
    } catch (ApplicationException failure) {
      throw failure;
    } catch (KubernetesClusterRegistryService.ClusterCredentialRejectedException
        | KubernetesClusterRegistryService.ClusterPermissionDeniedException
        | KubernetesClusterRegistryService.ClusterApiUnavailableException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw apiFailure(failure);
    }
  }

  public ApplicationDetail deployVersion(String actor, String remoteAddress, UUID applicationId,
      int versionNumber) {
    AppRow app = app(applicationId);
    StoredVersion desiredVersion = version(applicationId, versionNumber);
    if (desiredVersion == null) throw new ApplicationException(404,
        "KUBERNETES_APPLICATION_VERSION_NOT_FOUND", "应用版本不存在");
    if (!"DRAFT".equals(desiredVersion.state())) throw new ApplicationException(409,
        "KUBERNETES_APPLICATION_VERSION_NOT_DRAFT", "只有待确认的版本草稿可以部署");
    if (!"DEPLOYED".equals(app.state()) || desiredVersion.baseVersion() != app.currentVersion()) {
      throw new ApplicationException(409, "KUBERNETES_APPLICATION_VERSION_STALE",
          "草稿基于旧版本或应用未就绪，请刷新后重新生成差异");
    }
    StoredVersion previous = version(applicationId, app.currentVersion());
    if (previous == null) throw new ApplicationException(409,
        "KUBERNETES_APPLICATION_VERSION_MISSING", "找不到当前应用版本清单");
    Instant now = Instant.now();
    store.transaction(connection -> {
      int claimed = update(connection,
          "UPDATE kubernetes_application_version SET state='APPLYING',error_message=NULL "
              + "WHERE application_id=? AND version=? AND base_version=? AND state='DRAFT'",
          applicationId, versionNumber, app.currentVersion());
      if (claimed != 1) throw new ApplicationException(409,
          "KUBERNETES_APPLICATION_VERSION_NOT_DRAFT", "该版本已被其他请求提交或状态已变化");
      int appUpdated = update(connection,
          "UPDATE kubernetes_application SET state='APPLYING',updated_at=? "
              + "WHERE id=? AND state='DEPLOYED' AND current_version=?",
          Timestamp.from(now), applicationId, app.currentVersion());
      if (appUpdated != 1) throw new ApplicationException(409,
          "KUBERNETES_APPLICATION_VERSION_STALE", "应用当前版本已变化，请刷新后重新确认");
      audit(connection, actor, "KUBERNETES_APPLICATION_VERSION_DEPLOY_ACCEPTED",
          "kubernetes_application", applicationId.toString(), "ACCEPTED", remoteAddress,
          "管理员确认部署应用版本 v" + versionNumber);
      return null;
    });
    try (KubernetesClient client = clusters.openClientForOperations(app.clusterId())) {
      List<HasMetadata> before = parse(client, previous.manifestYaml());
      List<HasMetadata> desired = parse(client, desiredVersion.manifestYaml());
      Map<String, String> resourceUids = resourceUids(applicationId);
      List<HasMetadata> changed = new ArrayList<>();
      try {
        for (HasMetadata resource : desired) {
          applyOwned(client, app.namespace(), applicationId, resourceUids, resource);
          changed.add(resource);
        }
      } catch (RuntimeException applyFailure) {
        boolean restored = restoreResources(client, app.namespace(), applicationId, resourceUids,
            before, changed);
        boolean knownRejected = applyFailure instanceof ApplicationException
            || applyFailure instanceof KubernetesClientException clientFailure
                && clientFailure.getCode() >= 400 && clientFailure.getCode() < 500;
        String state = restored && knownRejected ? "FAILED" : "PARTIAL";
        finishFailure(actor, remoteAddress, applicationId, versionNumber, state,
            safeMessage(applyFailure), restored
                ? state.equals("FAILED") ? "版本部署被 Kubernetes 拒绝，已恢复前一版本资源"
                    : "更新结果未知；已恢复已确认更新的资源，但仍需核对集群状态"
                : "版本部署或恢复部分失败；当前集群可能包含部分新版本资源");
        throw new ApplicationException("FAILED".equals(state) ? 502 : 503,
            "FAILED".equals(state) ? "KUBERNETES_APPLICATION_DEPLOY_FAILED" : "KUBERNETES_APPLICATION_PARTIAL",
            "FAILED".equals(state) ? "版本部署失败，已恢复前一版本" : "版本部署结果不确定或部分失败，请检查集群资源后处理");
      }
      Topology deployedTopology;
      try {
        deployedTopology = topology(client, app.id(), app.namespace(), desired, resourceUids);
      } catch (RuntimeException topologyFailure) {
        finishFailure(actor, remoteAddress, applicationId, versionNumber, "PARTIAL",
            safeMessage(topologyFailure), "资源已更新，但部署后拓扑所有权核对未通过");
        throw new ApplicationException(503, "KUBERNETES_APPLICATION_PARTIAL",
            "资源已更新，但部署后拓扑所有权核对未通过；应用状态为 PARTIAL");
      }
      finishDeployment(actor, remoteAddress, applicationId, versionNumber,
          "应用版本 v" + versionNumber + " 已确认并部署");
      AppRow deployed = new AppRow(app.id(), app.clusterId(), app.clusterName(), app.namespace(),
          app.name(), app.displayName(), app.templateId(), versionNumber, "DEPLOYED",
          app.createdAt(), Instant.now(), app.createdBy());
      return new ApplicationDetail(view(deployed), deployedTopology, desiredVersion.manifestYaml());
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      finishFailure(actor, remoteAddress, applicationId, versionNumber, "FAILED",
          "已登记集群不存在", "部署版本时未找到已登记集群");
      throw missing;
    } catch (ApplicationException failure) {
      throw failure;
    } catch (KubernetesClusterRegistryService.ClusterCredentialRejectedException
        | KubernetesClusterRegistryService.ClusterPermissionDeniedException
        | KubernetesClusterRegistryService.ClusterApiUnavailableException failure) {
      finishFailure(actor, remoteAddress, applicationId, versionNumber, "FAILED",
          safeMessage(failure), "部署应用版本时集群凭据、权限或 API 不可用");
      throw failure;
    } catch (RuntimeException failure) {
      finishFailure(actor, remoteAddress, applicationId, versionNumber, "FAILED",
          safeMessage(failure), "部署应用版本时 Kubernetes API 请求失败");
      throw apiFailure(failure);
    }
  }

  private ValidatedCreate validateCreate(CreateApplicationRequest input) {
    if (input == null || input.clusterId() == null || !validNamespace(input.namespace())
        || input.displayName() == null || input.displayName().isBlank()
        || input.displayName().trim().length() > 80 || input.source() == null) {
      throw invalid("clusterId、有效 Namespace、创建方式和 1–80 字符的应用显示名称均为必填项");
    }
    String applicationName = "BOOKINFO".equals(input.source()) ? "bookinfo"
        : input.applicationName() == null ? "" : input.applicationName().trim();
    if (!DNS_LABEL.matcher(applicationName).matches()) {
      throw invalid("应用标识必须是 1–63 位小写字母、数字或连字符，且首尾为字母或数字");
    }
    try {
      KubernetesApplicationManifestFactory.CreateRequest request =
          new KubernetesApplicationManifestFactory.CreateRequest(applicationName,
              input.displayName().trim(), input.source(), input.image(), input.replicas(),
              input.containerPort(), input.servicePort(), input.environment(), input.definition());
      return new ValidatedCreate(applicationName, manifestFactory.create(request, BOOKINFO_MANIFEST));
    } catch (KubernetesApplicationManifestFactory.ApplicationDefinitionException failure) {
      throw invalid(failure.getMessage());
    }
  }

  private static boolean validNamespace(String namespace) {
    return namespace != null && DNS_LABEL.matcher(namespace).matches();
  }

  private static List<HasMetadata> prepareInitialResources(List<HasMetadata> source,
      String namespace, UUID applicationId, String templateId) {
    if (source == null || source.isEmpty() || source.size() > 64) {
      throw invalid("应用清单资源数量必须在 1–64 个之间");
    }
    List<HasMetadata> resources = new ArrayList<>(source);
    Map<String, Integer> actualCounts = new HashMap<>();
    resources.forEach(resource -> actualCounts.merge(resource.getKind(), 1, Integer::sum));
    if (actualCounts.getOrDefault("Deployment", 0) == 0) {
      throw invalid("应用至少需要一个 Deployment 工作负载");
    }
    if (TEMPLATE_BOOKINFO.equals(templateId) && !BOOKINFO_RESOURCE_COUNTS.equals(actualCounts)) {
      throw new ApplicationException(500, "BOOKINFO_TEMPLATE_INVALID", "Bookinfo 模板资源数量不符合预期");
    }
    Set<String> ids = new HashSet<>();
    for (HasMetadata resource : resources) {
      if (!supported(resource) || resource.getMetadata() == null
          || !validResourceName(resource.getMetadata().getName())) {
        throw invalid("只支持名称有效的 Deployment、Service 和 ServiceAccount 资源");
      }
      String id = resourceId(resource);
      if (!ids.add(id)) throw invalid("应用清单包含重复资源: " + id);
      normalizeResource(resource, namespace, applicationId);
    }
    validateApplicationResources(resources);
    return resources;
  }

  private static void normalizeResource(HasMetadata resource, String namespace, UUID applicationId) {
    Map<String, String> labels = resource.getMetadata().getLabels() == null
        ? new HashMap<>() : new HashMap<>(resource.getMetadata().getLabels());
    labels.put(MANAGED_BY, "nanpanel");
    labels.put(APPLICATION_ID, applicationId.toString());
    ObjectMetaBuilder metadata = new ObjectMetaBuilder().withName(resource.getMetadata().getName())
        .withNamespace(namespace).withLabels(labels);
    if (resource.getMetadata().getAnnotations() != null) {
      metadata.withAnnotations(new HashMap<>(resource.getMetadata().getAnnotations()));
    }
    resource.setMetadata(metadata.build());
    if (resource instanceof Deployment deployment) {
      deployment.setStatus(null);
      validateDeployment(deployment);
    } else if (resource instanceof Service service) {
      service.setStatus(null);
      validateService(service);
    } else if (resource instanceof ServiceAccount account) {
      account.setSecrets(null);
      account.setAutomountServiceAccountToken(false);
    }
  }

  private static void validateDeployment(Deployment deployment) {
    var spec = deployment.getSpec();
    if (spec == null || spec.getSelector() == null || spec.getTemplate() == null
        || spec.getTemplate().getSpec() == null || spec.getTemplate().getMetadata() == null
        || spec.getTemplate().getSpec().getContainers() == null
        || spec.getTemplate().getSpec().getContainers().isEmpty()) {
      throw invalid("Deployment 必须包含 selector、Pod 模板和至少一个容器");
    }
    int replicas = spec.getReplicas() == null ? 1 : spec.getReplicas();
    if (replicas < 0 || replicas > MAX_REPLICAS) throw invalid("Deployment 副本数必须在 0–20 之间");
    spec.setReplicas(replicas);
    if (spec.getSelector().getMatchExpressions() != null
        && !spec.getSelector().getMatchExpressions().isEmpty()) {
      throw invalid("暂不支持 Deployment selector.matchExpressions");
    }
    Map<String, String> selector = spec.getSelector().getMatchLabels() == null
        ? new HashMap<>() : new HashMap<>(spec.getSelector().getMatchLabels());
    Map<String, String> podLabels = spec.getTemplate().getMetadata().getLabels() == null
        ? new HashMap<>() : new HashMap<>(spec.getTemplate().getMetadata().getLabels());
    if (selector.isEmpty()
        || !selector.entrySet().stream().allMatch(entry -> entry.getValue().equals(podLabels.get(entry.getKey())))) {
      throw invalid("Deployment selector 必须与 Pod 模板标签匹配");
    }
    String appLabel = podLabels.get("app");
    if (appLabel == null || appLabel.isBlank()) {
      String fallback = deployment.getMetadata().getName();
      podLabels.put("app", fallback);
      selector.put("app", fallback);
    }
    spec.getSelector().setMatchLabels(selector);
    spec.getTemplate().getMetadata().setLabels(podLabels);
    var pod = spec.getTemplate().getSpec();
    if (Boolean.TRUE.equals(pod.getHostNetwork()) || Boolean.TRUE.equals(pod.getHostPID())
        || Boolean.TRUE.equals(pod.getHostIPC())) {
      throw invalid("不允许导入 hostNetwork、hostPID 或 hostIPC Deployment");
    }
    if (pod.getVolumes() != null && pod.getVolumes().stream().anyMatch(volume ->
        volume.getHostPath() != null || volume.getSecret() != null || volume.getConfigMap() != null
            || volume.getProjected() != null)) {
      throw invalid("不允许导入 HostPath、Secret、ConfigMap 或 Projected Volume");
    }
    if (pod.getSecurityContext() != null && pod.getSecurityContext().getSysctls() != null
        && !pod.getSecurityContext().getSysctls().isEmpty()) {
      throw invalid("暂不支持 Pod sysctls");
    }
    List<io.fabric8.kubernetes.api.model.Container> containers = new ArrayList<>(pod.getContainers());
    if (pod.getInitContainers() != null) containers.addAll(pod.getInitContainers());
    for (var container : containers) {
      if (container.getImage() == null || !IMAGE.matcher(container.getImage().trim()).matches()) {
        throw invalid("Deployment 中的容器镜像格式无效");
      }
      container.setImage(container.getImage().trim());
      if (container.getSecurityContext() != null
          && Boolean.TRUE.equals(container.getSecurityContext().getPrivileged())) {
        throw invalid("不允许导入 privileged 容器");
      }
      if (container.getSecurityContext() != null
          && Boolean.TRUE.equals(container.getSecurityContext().getAllowPrivilegeEscalation())) {
        throw invalid("不允许启用 allowPrivilegeEscalation");
      }
      if (container.getSecurityContext() != null
          && container.getSecurityContext().getCapabilities() != null
          && container.getSecurityContext().getCapabilities().getAdd() != null
          && !container.getSecurityContext().getCapabilities().getAdd().isEmpty()) {
        throw invalid("不允许为容器增加 Linux capabilities");
      }
      if (container.getPorts() != null && container.getPorts().stream().anyMatch(port -> port.getHostPort() != null)) {
        throw invalid("不允许导入 hostPort；请使用 Kubernetes Service 暴露端口");
      }
      if (container.getEnv() != null && container.getEnv().stream().anyMatch(env ->
          env.getValueFrom() != null && (env.getValueFrom().getSecretKeyRef() != null
              || env.getValueFrom().getConfigMapKeyRef() != null))) {
        throw invalid("暂不支持引用 Secret 或 ConfigMap 的环境变量；请先将资源纳入应用清单");
      }
      if (container.getEnvFrom() != null && container.getEnvFrom().stream().anyMatch(source ->
          source.getSecretRef() != null || source.getConfigMapRef() != null)) {
        throw invalid("暂不支持 envFrom 引用 Secret 或 ConfigMap");
      }
      if (container.getVolumeMounts() != null && container.getVolumeMounts().stream()
          .anyMatch(mount -> "Bidirectional".equals(mount.getMountPropagation()))) {
        throw invalid("不允许 Bidirectional mountPropagation");
      }
    }
  }

  private static void validateService(Service service) {
    var spec = service.getSpec();
    if (spec == null || spec.getSelector() == null || spec.getSelector().isEmpty()
        || spec.getPorts() == null || spec.getPorts().isEmpty()) {
      throw invalid("Service 必须包含 selector 和至少一个端口");
    }
    if (spec.getType() != null && !"ClusterIP".equals(spec.getType())) {
      throw invalid("导入的 Service 只支持 ClusterIP 类型");
    }
    if (spec.getExternalName() != null || spec.getExternalIPs() != null
        && !spec.getExternalIPs().isEmpty()) {
      throw invalid("不支持 ExternalName 或 externalIPs Service");
    }
    spec.setType("ClusterIP");
    spec.setClusterIP(null);
    spec.setClusterIPs(null);
    spec.setIpFamilies(null);
    spec.setIpFamilyPolicy(null);
    spec.setHealthCheckNodePort(null);
    spec.getPorts().forEach(port -> port.setNodePort(null));
  }

  private static void validateApplicationResources(List<HasMetadata> resources) {
    List<Deployment> deployments = resources.stream().filter(Deployment.class::isInstance)
        .map(Deployment.class::cast).toList();
    for (Service service : resources.stream().filter(Service.class::isInstance)
        .map(Service.class::cast).toList()) {
      boolean matches = deployments.stream().anyMatch(deployment ->
          matches(service.getSpec().getSelector(), deployment.getSpec().getTemplate()
              .getMetadata().getLabels()));
      if (!matches) throw invalid("Service selector 没有匹配应用内 Deployment: "
          + service.getMetadata().getName());
    }
  }

  private static List<String> externalServiceAccounts(List<HasMetadata> resources) {
    Set<String> included = resources.stream().filter(ServiceAccount.class::isInstance)
        .map(ServiceAccount.class::cast).map(account -> account.getMetadata().getName())
        .collect(java.util.stream.Collectors.toSet());
    return resources.stream().filter(Deployment.class::isInstance).map(Deployment.class::cast)
        .map(deployment -> deployment.getSpec().getTemplate().getSpec().getServiceAccountName())
        .filter(name -> name != null && !name.isBlank() && !"default".equals(name)
            && !included.contains(name))
        .distinct().sorted().toList();
  }

  private static void ensureReferencedServiceAccountsExist(KubernetesClient client,
      String namespace, List<HasMetadata> resources) {
    for (String account : externalServiceAccounts(resources)) {
      if (client.serviceAccounts().inNamespace(namespace).withName(account).get() == null) {
        throw invalid("Deployment 引用了不存在的 ServiceAccount: " + account);
      }
    }
  }

  private static boolean validResourceName(String name) {
    return name != null && name.length() <= 63 && DNS_LABEL.matcher(name).matches();
  }

  private static List<HasMetadata> parse(KubernetesClient client, String yaml) {
    try {
      return client.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))).items();
    } catch (RuntimeException failure) {
      throw new ApplicationException(500, "KUBERNETES_APPLICATION_MANIFEST_INVALID",
          "无法解析应用版本清单");
    }
  }

  private static String serialize(List<HasMetadata> resources) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (HasMetadata resource : resources) {
      String yaml = io.fabric8.kubernetes.client.utils.Serialization.asYaml(resource).strip();
      if (yaml.isEmpty()) continue;
      if (out.size() > 0) out.writeBytes("\n---\n".getBytes(StandardCharsets.UTF_8));
      out.writeBytes(yaml.getBytes(StandardCharsets.UTF_8));
    }
    return out.toString(StandardCharsets.UTF_8) + "\n";
  }

  private static boolean supported(HasMetadata resource) {
    return resource instanceof Deployment || resource instanceof Service
        || resource instanceof ServiceAccount;
  }

  private static void ensureResourcesAvailable(KubernetesClient client, String namespace,
      List<HasMetadata> resources) {
    for (HasMetadata resource : resources) {
      if (fetch(client, resource.getKind(), namespace, resource.getMetadata().getName()) != null) {
        throw new ApplicationException(409, "KUBERNETES_APPLICATION_RESOURCE_CONFLICT",
            resource.getKind() + "/" + resource.getMetadata().getName() + " 已存在，未接管任何资源");
      }
    }
  }

  private static List<HasMetadata> createResources(KubernetesClient client, String namespace,
      List<HasMetadata> resources) {
    List<HasMetadata> created = new ArrayList<>();
    for (HasMetadata resource : resources) {
      HasMetadata persisted = client.resource(resource).create();
      if (persisted == null || persisted.getMetadata() == null
          || persisted.getMetadata().getUid() == null || persisted.getMetadata().getUid().isBlank()) {
        throw new ApplicationException(502, "KUBERNETES_RESOURCE_UID_MISSING",
            "Kubernetes 未返回资源 UID，已拒绝登记应用所有权");
      }
      created.add(persisted);
    }
    return created;
  }

  private void recordResources(UUID applicationId, List<HasMetadata> resources) {
    Instant now = Instant.now();
    store.transaction(connection -> {
      for (HasMetadata resource : resources) {
        update(connection, "INSERT INTO kubernetes_application_resource "
                + "(application_id,resource_kind,resource_name,resource_uid,created_at) "
                + "VALUES (?,?,?,?,?)",
            applicationId, resource.getKind(), resource.getMetadata().getName(),
            resource.getMetadata().getUid(), Timestamp.from(now));
      }
      return null;
    });
  }

  private Map<String, String> resourceUids(UUID applicationId) {
    List<Map<String, Object>> rows = store.transaction(connection -> query(connection,
        "SELECT resource_kind,resource_name,resource_uid FROM kubernetes_application_resource "
            + "WHERE application_id=?", applicationId));
    Map<String, String> values = new HashMap<>();
    rows.forEach(row -> values.put(String.valueOf(row.get("resource_kind")) + "/"
        + String.valueOf(row.get("resource_name")), String.valueOf(row.get("resource_uid"))));
    return values;
  }

  private static boolean rollbackCreated(KubernetesClient client, String namespace,
      UUID applicationId, List<HasMetadata> resources) {
    boolean clean = true;
    for (int i = resources.size() - 1; i >= 0; i--) {
      HasMetadata desired = resources.get(i);
      try {
        HasMetadata current = fetch(client, desired.getKind(), namespace,
            desired.getMetadata().getName());
        if (current == null) continue;
        if (!owned(current, applicationId)) {
          clean = false;
          continue;
        }
        client.resource(current).delete();
      } catch (RuntimeException failure) {
        clean = false;
      }
    }
    return clean;
  }

  private static List<HasMetadata> applyEdits(List<HasMetadata> source,
      List<TopologyEdit> edits) {
    Map<String, HasMetadata> resources = new LinkedHashMap<>();
    Map<String, String> validTargets = new HashMap<>();
    for (HasMetadata item : source) {
      if (!supported(item)) throw invalid("版本清单含有不支持的资源类型");
      resources.put(resourceId(item), item);
      if (item instanceof Deployment deployment) {
        String app = deployment.getSpec().getTemplate().getMetadata().getLabels().get("app");
        if (app != null) validTargets.put(app, app);
      }
    }
    Set<String> edited = new HashSet<>();
    for (TopologyEdit edit : edits) {
      if (edit == null || edit.resourceId() == null || !edited.add(edit.resourceId())) {
        throw invalid("拓扑修改包含重复或无效资源");
      }
      HasMetadata item = resources.get(edit.resourceId());
      if (item == null) throw invalid("拓扑修改引用了应用清单之外的资源");
      if (item instanceof Deployment deployment) {
        if (edit.replicas() != null) {
          if (edit.replicas() < 0 || edit.replicas() > MAX_REPLICAS) {
            throw invalid("Deployment 副本数必须在 0–20 之间");
          }
          deployment.getSpec().setReplicas(edit.replicas());
        }
        if (edit.image() != null) {
          String image = edit.image().trim();
          if (!IMAGE.matcher(image).matches()) throw invalid("容器镜像格式无效");
          var containers = deployment.getSpec().getTemplate().getSpec().getContainers();
          if (containers == null || containers.isEmpty()) throw invalid("Deployment 没有可编辑的容器");
          containers.forEach(container -> container.setImage(image));
        }
        if (edit.targetDeployment() != null) throw invalid("Deployment 不接受 Service 连接修改");
      } else if (item instanceof Service service) {
        if (edit.replicas() != null || edit.image() != null) {
          throw invalid("Service 不接受副本数或镜像修改");
        }
        if (edit.targetDeployment() != null) {
          String target = edit.targetDeployment().trim();
          if (!validTargets.containsKey(target)) throw invalid("Service 连接目标不属于应用中的 Deployment");
          service.getSpec().setSelector(Map.of("app", target));
        }
      } else if (edit.replicas() != null || edit.image() != null || edit.targetDeployment() != null) {
        throw invalid("ServiceAccount 不包含可编辑的拓扑属性");
      }
    }
    return new ArrayList<>(resources.values());
  }

  private static Topology topology(KubernetesClient client, UUID applicationId, String namespace,
      List<HasMetadata> resources, Map<String, String> resourceUids) {
    List<TopologyNode> nodes = new ArrayList<>();
    List<TopologyEdge> edges = new ArrayList<>();
    List<Deployment> deployments = resources.stream().filter(Deployment.class::isInstance)
        .map(Deployment.class::cast).toList();
    for (HasMetadata item : resources) {
      if (item instanceof Deployment deployment) {
        String id = resourceId(item);
        String expectedUid = resourceUids.get(id);
        if (expectedUid == null) throw ownershipConflict(id);
        Integer replicas = deployment.getSpec().getReplicas();
        Integer ready = null;
        String state = "UNKNOWN";
        try {
          Deployment live = (Deployment) fetch(client, "Deployment", namespace,
              deployment.getMetadata().getName());
          if (live == null) state = "MISSING";
          else {
            requireOwned(live, applicationId, expectedUid);
            ready = live.getStatus() == null ? 0 : live.getStatus().getReadyReplicas();
            if (ready == null) ready = 0;
            int desired = live.getSpec() == null || live.getSpec().getReplicas() == null
                ? 1 : live.getSpec().getReplicas();
            state = ready >= desired ? "READY" : "PROGRESSING";
          }
        } catch (ApplicationException conflict) {
          throw conflict;
        } catch (RuntimeException failure) {
          state = "UNAVAILABLE";
        }
        String image = deployment.getSpec().getTemplate().getSpec().getContainers().stream()
            .map(container -> container.getImage()).filter(value -> value != null).findFirst().orElse(null);
        nodes.add(new TopologyNode(id, "Deployment", deployment.getMetadata().getName(),
            replicas, ready, image, deployment.getSpec().getTemplate().getMetadata().getLabels()
                .get("app"), state));
      } else if (item instanceof Service service) {
        String id = resourceId(item);
        String expectedUid = resourceUids.get(id);
        if (expectedUid == null) throw ownershipConflict(id);
        String target = service.getSpec().getSelector() == null ? null
            : service.getSpec().getSelector().get("app");
        String state = "ACTIVE";
        try {
          HasMetadata live = fetch(client, "Service", namespace, service.getMetadata().getName());
          if (live == null) state = "MISSING";
          else requireOwned(live, applicationId, expectedUid);
        } catch (ApplicationException conflict) {
          throw conflict;
        } catch (RuntimeException failure) {
          state = "UNAVAILABLE";
        }
        nodes.add(new TopologyNode(id, "Service", service.getMetadata().getName(),
            null, null, null, target, state));
        for (Deployment deployment : deployments) {
          Map<String, String> labels = deployment.getSpec().getTemplate().getMetadata().getLabels();
          if (matches(service.getSpec().getSelector(), labels)) {
            edges.add(new TopologyEdge(id + "->" + resourceId(deployment), id, resourceId(deployment)));
          }
        }
      } else if (item instanceof ServiceAccount account) {
        String id = resourceId(item);
        String expectedUid = resourceUids.get(id);
        if (expectedUid == null) throw ownershipConflict(id);
        String state = "CONFIGURED";
        try {
          HasMetadata live = fetch(client, "ServiceAccount", namespace, account.getMetadata().getName());
          if (live == null) state = "MISSING";
          else requireOwned(live, applicationId, expectedUid);
        } catch (ApplicationException conflict) {
          throw conflict;
        } catch (RuntimeException failure) {
          state = "UNAVAILABLE";
        }
        nodes.add(new TopologyNode(id, "ServiceAccount", account.getMetadata().getName(),
            null, null, null, null, state));
      }
    }
    nodes.sort(Comparator.comparing(TopologyNode::kind).thenComparing(TopologyNode::name));
    edges.sort(Comparator.comparing(TopologyEdge::source).thenComparing(TopologyEdge::target));
    return new Topology(List.copyOf(nodes), List.copyOf(edges));
  }

  private static boolean matches(Map<String, String> selector, Map<String, String> labels) {
    if (selector == null || selector.isEmpty()) return false;
    return labels != null && selector.entrySet().stream()
        .allMatch(entry -> entry.getValue().equals(labels.get(entry.getKey())));
  }

  private static String resourceId(HasMetadata item) {
    return item.getKind() + "/" + item.getMetadata().getName();
  }

  private static String resourceDiff(List<HasMetadata> before, List<HasMetadata> after) {
    Map<String, String> oldYaml = resourceYaml(before);
    Map<String, String> newYaml = resourceYaml(after);
    StringBuilder diff = new StringBuilder();
    Set<String> keys = new java.util.TreeSet<>();
    keys.addAll(oldYaml.keySet());
    keys.addAll(newYaml.keySet());
    for (String key : keys) {
      String oldValue = oldYaml.get(key);
      String newValue = newYaml.get(key);
      if (java.util.Objects.equals(oldValue, newValue)) continue;
      diff.append("--- vCURRENT/").append(key).append('\n')
          .append("+++ vDRAFT/").append(key).append('\n');
      if (oldValue != null) oldValue.lines().forEach(line -> diff.append('-').append(line).append('\n'));
      if (newValue != null) newValue.lines().forEach(line -> diff.append('+').append(line).append('\n'));
    }
    return diff.toString();
  }

  private static Map<String, String> resourceYaml(List<HasMetadata> resources) {
    Map<String, String> values = new LinkedHashMap<>();
    resources.stream().sorted(Comparator.comparing(KubernetesApplicationService::resourceId))
        .forEach(resource -> values.put(resourceId(resource),
            io.fabric8.kubernetes.client.utils.Serialization.asYaml(resource).strip()));
    return values;
  }

  private static HasMetadata fetch(KubernetesClient client, String kind, String namespace,
      String name) {
    return switch (kind) {
      case "Deployment" -> client.apps().deployments().inNamespace(namespace).withName(name).get();
      case "Service" -> client.services().inNamespace(namespace).withName(name).get();
      case "ServiceAccount" -> client.serviceAccounts().inNamespace(namespace).withName(name).get();
      default -> throw invalid("应用包含不支持的 Kubernetes 资源类型: " + kind);
    };
  }

  private static boolean owned(HasMetadata resource, UUID applicationId) {
    return resource.getMetadata() != null && resource.getMetadata().getLabels() != null
        && applicationId.toString().equals(resource.getMetadata().getLabels().get(APPLICATION_ID));
  }

  private static void applyOwned(KubernetesClient client, String namespace, UUID applicationId,
      Map<String, String> resourceUids, HasMetadata desired) {
    HasMetadata live = fetch(client, desired.getKind(), namespace, desired.getMetadata().getName());
    requireOwned(live, applicationId, resourceUids.get(resourceId(desired)));
    String resourceVersion = live.getMetadata().getResourceVersion();
    if (resourceVersion == null || resourceVersion.isBlank()) {
      throw new ApplicationException(502, "KUBERNETES_RESOURCE_VERSION_MISSING",
          "Kubernetes 未返回资源版本，已拒绝覆盖资源");
    }
    desired.getMetadata().setUid(live.getMetadata().getUid());
    desired.getMetadata().setResourceVersion(resourceVersion);
    client.resource(desired).lockResourceVersion(resourceVersion).replace();
  }

  private static boolean restoreResources(KubernetesClient client, String namespace,
      UUID applicationId, Map<String, String> resourceUids, List<HasMetadata> before,
      List<HasMetadata> attempted) {
    Map<String, HasMetadata> old = new HashMap<>();
    before.forEach(item -> old.put(resourceId(item), item));
    boolean restored = true;
    for (int i = attempted.size() - 1; i >= 0; i--) {
      HasMetadata desired = old.get(resourceId(attempted.get(i)));
      if (desired == null) continue;
      try {
        HasMetadata live = fetch(client, desired.getKind(), namespace, desired.getMetadata().getName());
        requireOwned(live, applicationId, resourceUids.get(resourceId(desired)));
        String resourceVersion = live.getMetadata().getResourceVersion();
        if (resourceVersion == null) {
          restored = false;
          continue;
        }
        desired.getMetadata().setUid(live.getMetadata().getUid());
        desired.getMetadata().setResourceVersion(resourceVersion);
        client.resource(desired).lockResourceVersion(resourceVersion).replace();
      } catch (RuntimeException failure) {
        restored = false;
      }
    }
    return restored;
  }

  private void finishDeployment(String actor, String remoteAddress, UUID applicationId,
      int versionNumber, String summary) {
    Instant now = Instant.now();
    store.transaction(connection -> {
      update(connection, "UPDATE kubernetes_application_version SET state='FAILED',"
              + "error_message='此草稿基于旧版本，已由新版本部署取代' "
              + "WHERE application_id=? AND state='DRAFT' AND version<>?",
          applicationId, versionNumber);
      update(connection, "UPDATE kubernetes_application_version SET state='DEPLOYED',"
              + "error_message=NULL,deployed_at=? WHERE application_id=? AND version=?",
          Timestamp.from(now), applicationId, versionNumber);
      update(connection, "UPDATE kubernetes_application SET current_version=?,state='DEPLOYED',updated_at=? "
              + "WHERE id=?", versionNumber, Timestamp.from(now), applicationId);
      audit(connection, actor, "KUBERNETES_APPLICATION_VERSION_DEPLOYED",
          "kubernetes_application", applicationId.toString(), "SUCCEEDED", remoteAddress, summary);
      return null;
    });
  }

  private void finishFailure(String actor, String remoteAddress, UUID applicationId,
      int versionNumber, String state, String message, String summary) {
    Instant now = Instant.now();
    store.transaction(connection -> {
      update(connection, "UPDATE kubernetes_application_version SET state=?,error_message=? "
              + "WHERE application_id=? AND version=?", state, truncate(message, 1000),
          applicationId, versionNumber);
      update(connection, "UPDATE kubernetes_application SET state=CASE "
              + "WHEN ?='FAILED' AND current_version>0 THEN 'DEPLOYED' ELSE ? END,updated_at=? WHERE id=?",
          state, state, Timestamp.from(now), applicationId);
      audit(connection, actor, "KUBERNETES_APPLICATION_VERSION_DEPLOY_FAILED",
          "kubernetes_application", applicationId.toString(), "FAILED", remoteAddress, summary);
      return null;
    });
  }

  private void markVersionFailure(String actor, String remoteAddress, UUID applicationId,
      String state, String message, String summary) {
    finishFailure(actor, remoteAddress, applicationId, 1, state, message, summary);
  }

  private AppRow app(UUID applicationId) {
    List<Map<String, Object>> rows = store.transaction(connection -> query(connection,
        "SELECT a.id,a.cluster_id,c.name AS cluster_name,a.namespace,a.application_name,"
            + "a.display_name,a.template_id,a.current_version,a.state,a.created_at,a.updated_at,"
            + "a.created_by FROM kubernetes_application a JOIN kubernetes_cluster c "
            + "ON c.id=a.cluster_id WHERE a.id=?", applicationId));
    if (rows.isEmpty()) throw new ApplicationException(404,
        "KUBERNETES_APPLICATION_NOT_FOUND", "Kubernetes 应用不存在");
    return row(rows.getFirst());
  }

  private String clusterName(UUID clusterId) {
    List<Map<String, Object>> rows = store.transaction(connection -> query(connection,
        "SELECT name FROM kubernetes_cluster WHERE id=?", clusterId));
    if (rows.isEmpty()) throw new KubernetesClusterRegistryService.ClusterNotFoundException();
    return String.valueOf(rows.getFirst().get("name"));
  }

  private StoredVersion version(UUID applicationId, int versionNumber) {
    List<Map<String, Object>> rows = store.transaction(connection -> query(connection,
        "SELECT version,base_version,manifest_yaml,manifest_sha256,manifest_diff,state,"
            + "error_message,created_at,deployed_at FROM kubernetes_application_version "
            + "WHERE application_id=? AND version=?", applicationId, versionNumber));
    return rows.isEmpty() ? null : storedVersion(rows.getFirst());
  }

  private StoredVersion latestVersion(UUID applicationId) {
    List<Map<String, Object>> rows = store.transaction(connection -> query(connection,
        "SELECT version,base_version,manifest_yaml,manifest_sha256,manifest_diff,state,"
            + "error_message,created_at,deployed_at FROM kubernetes_application_version "
            + "WHERE application_id=? ORDER BY version DESC LIMIT 1", applicationId));
    return rows.isEmpty() ? null : storedVersion(rows.getFirst());
  }

  private static void requireOwned(HasMetadata live, UUID applicationId, String expectedUid) {
    if (live == null || expectedUid == null || !owned(live, applicationId)
        || live.getMetadata().getUid() == null || !expectedUid.equals(live.getMetadata().getUid())) {
      throw ownershipConflict(live == null || live.getMetadata() == null
          ? "unknown resource" : resourceId(live));
    }
  }

  private static ApplicationException ownershipConflict(String resourceId) {
    return new ApplicationException(409, "KUBERNETES_APPLICATION_OWNERSHIP_CONFLICT",
        "目标资源已缺失或 UID/所有权不再匹配：" + resourceId);
  }

  private static AppRow row(Map<String, Object> row) {
    return new AppRow(uuid(row.get("id")), uuid(row.get("cluster_id")),
        String.valueOf(row.get("cluster_name")), String.valueOf(row.get("namespace")),
        String.valueOf(row.get("application_name")), String.valueOf(row.get("display_name")),
        String.valueOf(row.get("template_id")), number(row.get("current_version")),
        String.valueOf(row.get("state")), instant(row.get("created_at")),
        instant(row.get("updated_at")), String.valueOf(row.get("created_by")));
  }

  private static ApplicationView application(Map<String, Object> row) {
    return view(row(row));
  }

  private static ApplicationView view(AppRow app) {
    return new ApplicationView(app.id(), app.clusterId(), app.clusterName(), app.namespace(),
        app.name(), app.displayName(), app.templateId(), app.currentVersion(), app.state(),
        app.createdAt(), app.updatedAt());
  }

  private static StoredVersion storedVersion(Map<String, Object> row) {
    return new StoredVersion(number(row.get("version")), number(row.get("base_version")),
        String.valueOf(row.get("manifest_yaml")), String.valueOf(row.get("manifest_sha256")),
        String.valueOf(row.get("manifest_diff")), String.valueOf(row.get("state")),
        row.get("error_message") == null ? null : String.valueOf(row.get("error_message")),
        instant(row.get("created_at")), row.get("deployed_at") == null
            ? null : instant(row.get("deployed_at")));
  }

  private static VersionView versionView(Map<String, Object> row) {
    return new VersionView(number(row.get("version")), number(row.get("base_version")),
        String.valueOf(row.get("state")), String.valueOf(row.get("manifest_sha256")),
        String.valueOf(row.get("manifest_diff")), row.get("error_message") == null
            ? null : String.valueOf(row.get("error_message")), instant(row.get("created_at")),
        row.get("deployed_at") == null ? null : instant(row.get("deployed_at")));
  }

  private static int number(Object value) {
    return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
  }

  private static UUID uuid(Object value) {
    return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
  }

  private static Instant instant(Object value) {
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
    if (value instanceof Instant instant) return instant;
    return Instant.parse(String.valueOf(value));
  }

  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static String safeMessage(RuntimeException failure) {
    if (failure instanceof KubernetesClientException clientFailure) {
      return truncate("Kubernetes API returned " + clientFailure.getCode() + ": "
          + clientFailure.getStatus().getMessage(), 1000);
    }
    return truncate(failure.getMessage() == null ? failure.getClass().getSimpleName()
        : failure.getMessage(), 1000);
  }

  private static String truncate(String value, int limit) {
    if (value == null || value.length() <= limit) return value;
    return value.substring(0, limit);
  }

  private static ApplicationException apiFailure(RuntimeException failure) {
    if (failure instanceof ApplicationException applicationFailure) return applicationFailure;
    if (failure instanceof KubernetesClientException clientFailure) {
      if (clientFailure.getCode() == 403) return new ApplicationException(403,
          "KUBERNETES_RBAC_DENIED", "集群凭据没有执行此应用操作所需的 Namespace 权限");
      if (clientFailure.getCode() == 409) return new ApplicationException(409,
          "KUBERNETES_APPLICATION_RESOURCE_CONFLICT", "Kubernetes 资源版本冲突，请刷新应用后重试");
    }
    return new ApplicationException(502, "KUBERNETES_CLUSTER_UNAVAILABLE",
        "Kubernetes API 不可用或应用操作失败");
  }

  private static ApplicationException invalid(String message) {
    return new ApplicationException(422, "KUBERNETES_APPLICATION_INVALID", message);
  }

  public static final class ApplicationException extends RuntimeException {
    private final int status;
    private final String code;

    public ApplicationException(int status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
  }
}
