package io.nanpanel.core;

import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/** Namespace-scoped applications backed by immutable, YAML-derived versions. */
@ApplicationScoped
public class KubernetesApplicationService {
  private static final Logger LOG = Logger.getLogger(KubernetesApplicationService.class);
  private static final String TEMPLATE_BOOKINFO = "bookinfo";
  private static final String MANAGED_BY = "app.kubernetes.io/managed-by";
  private static final String APPLICATION_ID = "nanpanel.io/application-id";
  private static final int MAX_APPLICATIONS = 200;
  private static final int MAX_REPLICAS = 20;
  private static final int MAX_RESOURCE_YAML_LENGTH = 262_144;
  private static final int MAX_RUNTIME_LOG_LINES = 1000;
  private static final int MAX_RUNTIME_LOG_CHARS = 120_000;
  private static final int MAX_PENDING_EXEC_TICKETS = 1000;
  private static final Duration EXEC_TICKET_TTL = Duration.ofSeconds(30);
  private static final SecureRandom EXEC_RANDOM = new SecureRandom();
  private static final Map<String, Integer> BOOKINFO_RESOURCE_COUNTS = Map.of(
      "ServiceAccount", 4,
      "Service", 4,
      "Deployment", 6);
  private static final Pattern DNS_LABEL =
      Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
  private static final Pattern IMAGE =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/:@+-]{0,511}");
  private static final Pattern SERVICE_PORT_NAME = Pattern.compile("[a-z](?:[a-z0-9-]{0,13}[a-z0-9])?");

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
  public record DraftRequest(Integer baseVersion, List<TopologyEdit> edits,
      List<TopologyAddition> additions, List<TopologyYamlEdit> yamlEdits,
      List<TopologyYamlAddition> yamlAdditions) {}
  public record TopologyEdit(String resourceId, Integer replicas, String image,
      String targetDeployment, Integer servicePort, String targetPort, String serviceProtocol) {}
  public record TopologyAddition(String kind, String name, Integer replicas, String image,
      String targetDeployment, Integer servicePort, String targetPort, String serviceProtocol) {}
  public record TopologyYamlEdit(String resourceId, String sourceYaml) {}
  public record TopologyYamlAddition(String sourceYaml) {}
  public record ApplicationList(List<ApplicationView> items) {}
  public record ApplicationView(UUID id, UUID clusterId, String clusterName, String namespace,
      String name, String displayName, String templateId, int currentVersion, String state,
      Instant createdAt, Instant updatedAt) {}
  public record TopologyNode(String id, String kind, String name, Integer replicas,
      Integer readyReplicas, String image, String targetDeployment, Integer servicePort,
      String targetPort, String serviceProtocol, List<TopologyServicePort> servicePorts,
      String state, String sourceYaml, String serviceType,
      List<String> serviceExternalAddresses) {}
  public record TopologyServicePort(String name, int port, String targetPort, String protocol,
      Integer nodePort) {}
  public record TopologyEdge(String id, String source, String target) {}
  public record Topology(List<TopologyNode> nodes, List<TopologyEdge> edges) {}
  public record ApplicationDetail(ApplicationView application, Topology topology,
      String manifestYaml) {}
  public record VersionView(int version, int baseVersion, String state, String manifestSha256,
      String manifestDiff, String errorMessage, Instant createdAt, Instant deployedAt) {}
  public record VersionList(List<VersionView> items) {}
  public record RuntimeContainer(String name, String image, boolean ready, String state) {}
  public record RuntimePod(String name, String uid, String phase, String nodeName,
      Instant startedAt, List<RuntimeContainer> containers) {}
  public record RuntimeDeployment(String name, String namespace, String state,
      List<RuntimePod> pods) {}
  public record RuntimeEvent(String type, String reason, String message, int count,
      String timestamp, String source) {}
  public record RuntimeEventList(List<RuntimeEvent> items) {}
  public record RuntimeLog(String podName, String container, int tailLines, String content) {}
  public enum ExecMode { ATTACH, NEW_SHELL }
  public record ExecTicket(UUID sessionId, String ticket, Instant expiresAt,
      String websocketPath) {}
  public record ExecTarget(UUID applicationId, String deploymentName, String namespace,
      UUID clusterId, String podName, String podUid, String container) {}
  public record ExecGrant(ExecTarget target, String actorId, String remoteAddress, ExecMode mode) {
    public ExecGrant(ExecTarget target, String actorId, String remoteAddress) {
      this(target, actorId, remoteAddress, ExecMode.NEW_SHELL);
    }
  }

  private record PendingExec(ExecTarget target, String tokenHash, String actorId,
      String remoteAddress, ExecMode mode, Instant expiresAt) {}
  private final ConcurrentMap<UUID, PendingExec> pendingExecs = new ConcurrentHashMap<>();

  private record AppRow(UUID id, UUID clusterId, String clusterName, String namespace,
      String name, String displayName, String templateId, int currentVersion, String state,
      Instant createdAt, Instant updatedAt, String createdBy) {}
  private record StoredVersion(int version, int baseVersion, String manifestYaml,
      String manifestSha256, String manifestDiff, String state, String errorMessage,
      Instant createdAt, Instant deployedAt) {}
  private record DeploymentScope(AppRow application, Deployment deployment, List<Pod> pods) {}

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

  public RuntimeDeployment runtime(UUID applicationId, String deploymentName) {
    AppRow application = app(applicationId);
    try (KubernetesClient client = clusters.openClientForOperations(application.clusterId())) {
      DeploymentScope scope = deploymentScope(client, application, deploymentName);
      if (scope.deployment() == null) {
        return new RuntimeDeployment(deploymentName, application.namespace(), "MISSING", List.of());
      }
      Deployment deployment = scope.deployment();
      Integer desired = deployment.getSpec() == null ? null : deployment.getSpec().getReplicas();
      int ready = deployment.getStatus() == null || deployment.getStatus().getReadyReplicas() == null
          ? 0 : deployment.getStatus().getReadyReplicas();
      int replicas = desired == null ? 1 : desired;
      String state = deploymentFailed(deployment) ? "FAILED"
          : ready >= replicas ? "READY" : "PROGRESSING";
      return new RuntimeDeployment(deploymentName, application.namespace(), state,
          scope.pods().stream().map(KubernetesApplicationService::runtimePod).toList());
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException
        | KubernetesClusterRegistryService.ClusterCredentialRejectedException
        | KubernetesClusterRegistryService.ClusterPermissionDeniedException
        | KubernetesClusterRegistryService.ClusterApiUnavailableException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw apiFailure(failure);
    }
  }

  public RuntimeEventList runtimeEvents(UUID applicationId, String deploymentName, String podUid) {
    AppRow application = app(applicationId);
    try (KubernetesClient client = clusters.openClientForOperations(application.clusterId())) {
      Pod pod = ownedPod(client, application, deploymentName, podUid);
      List<RuntimeEvent> events = client.v1().events().inNamespace(application.namespace())
          .withField("involvedObject.uid", podUid).list().getItems().stream()
          .filter(event -> event.getInvolvedObject() != null
              && podUid.equals(event.getInvolvedObject().getUid()))
          .sorted(Comparator.comparing(KubernetesApplicationService::eventTimestamp,
              Comparator.nullsLast(Comparator.reverseOrder())))
          .limit(200)
          .map(event -> new RuntimeEvent(event.getType(), event.getReason(),
              truncate(event.getMessage(), 4000), event.getSeries() != null
                  && event.getSeries().getCount() != null ? event.getSeries().getCount()
                      : event.getCount() == null ? 1 : event.getCount(),
              eventTimestamp(event), event.getSource() == null ? event.getReportingComponent()
                  : event.getSource().getComponent()))
          .toList();
      return new RuntimeEventList(events);
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException
        | KubernetesClusterRegistryService.ClusterCredentialRejectedException
        | KubernetesClusterRegistryService.ClusterPermissionDeniedException
        | KubernetesClusterRegistryService.ClusterApiUnavailableException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw apiFailure(failure);
    }
  }

  public RuntimeLog runtimeLog(UUID applicationId, String deploymentName, String podUid,
      String containerName, Integer requestedLines) {
    int lines = requestedLines == null ? 300 : requestedLines;
    if (lines < 1 || lines > MAX_RUNTIME_LOG_LINES) {
      throw new ApplicationException(422, "KUBERNETES_APPLICATION_INVALID",
          "日志行数必须在 1–1000 之间");
    }
    if (containerName == null || containerName.isBlank() || containerName.length() > 253) {
      throw new ApplicationException(422, "KUBERNETES_APPLICATION_INVALID", "容器名称无效");
    }
    AppRow application = app(applicationId);
    try (KubernetesClient client = clusters.openClientForOperations(application.clusterId())) {
      Pod pod = ownedPod(client, application, deploymentName, podUid);
      requireContainer(pod, containerName);
      String content = client.pods().inNamespace(application.namespace())
          .withName(pod.getMetadata().getName()).inContainer(containerName)
          .tailingLines(lines).getLog();
      return new RuntimeLog(pod.getMetadata().getName(), containerName, lines,
          tail(content, MAX_RUNTIME_LOG_CHARS));
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException
        | KubernetesClusterRegistryService.ClusterCredentialRejectedException
        | KubernetesClusterRegistryService.ClusterPermissionDeniedException
        | KubernetesClusterRegistryService.ClusterApiUnavailableException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw apiFailure(failure);
    }
  }

  public ExecTarget validateExecTarget(UUID applicationId, String deploymentName,
      String podUid, String containerName) {
    return validateExecTarget(applicationId, deploymentName, podUid, containerName,
        ExecMode.NEW_SHELL);
  }

  public ExecTarget validateExecTarget(UUID applicationId, String deploymentName,
      String podUid, String containerName, ExecMode mode) {
    AppRow application = app(applicationId);
    try (KubernetesClient client = clusters.openClientForOperations(application.clusterId())) {
      Pod pod = ownedPod(client, application, deploymentName, podUid);
      requireContainer(pod, containerName);
      requireRunningContainer(pod, containerName);
      if (mode == ExecMode.ATTACH) requireAttachableTerminal(pod, containerName);
      return new ExecTarget(applicationId, deploymentName, application.namespace(),
          application.clusterId(), pod.getMetadata().getName(), podUid, containerName);
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException
        | KubernetesClusterRegistryService.ClusterCredentialRejectedException
        | KubernetesClusterRegistryService.ClusterPermissionDeniedException
        | KubernetesClusterRegistryService.ClusterApiUnavailableException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw apiFailure(failure);
    }
  }

  public ExecTicket issueExecTicket(ExecTarget target, String actorId, String clientIp) {
    return issueExecTicket(target, ExecMode.NEW_SHELL, actorId, clientIp);
  }

  public ExecTicket issueExecTicket(ExecTarget target, ExecMode mode,
      String actorId, String clientIp) {
    byte[] secret = new byte[32];
    EXEC_RANDOM.nextBytes(secret);
    String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    UUID sessionId = UUID.randomUUID();
    Instant expiresAt = Instant.now().plus(EXEC_TICKET_TTL);
    String tokenHash = sha256(ticket);
    pendingExecs.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(Instant.now()));
    if (pendingExecs.size() >= MAX_PENDING_EXEC_TICKETS) {
      throw new ApplicationException(429, "KUBERNETES_APPLICATION_EXEC_BUSY",
          "Exec 请求过多，请稍后重试");
    }
    pendingExecs.put(sessionId, new PendingExec(target, tokenHash, actorId, clientIp, mode, expiresAt));
    try {
      store.transaction(connection -> {
        audit(connection, actorId, "KUBERNETES_APPLICATION_EXEC_REQUESTED",
            "kubernetes_application", target.applicationId().toString(), "ACCEPTED", clientIp,
            truncate("创建 Deployment Exec " + mode + " 会话 · " + target.deploymentName() + "/" + target.podName()
                + ":" + target.container(), 1000));
        return null;
      });
    } catch (RuntimeException failure) {
      pendingExecs.remove(sessionId);
      throw failure;
    }
    return new ExecTicket(sessionId, ticket, expiresAt,
        "/kubernetes/applications/exec-sessions/" + sessionId + "/terminal");
  }

  public ExecGrant consumeExecTicket(UUID sessionId, String ticket) {
    PendingExec pending = pendingExecs.get(sessionId);
    if (pending == null) return null;
    if (!pending.expiresAt().isAfter(Instant.now())) {
      pendingExecs.remove(sessionId, pending);
      return null;
    }
    if (ticket == null) return null;
    String suppliedHash = sha256(ticket);
    if (!MessageDigest.isEqual(pending.tokenHash().getBytes(StandardCharsets.US_ASCII),
        suppliedHash.getBytes(StandardCharsets.US_ASCII))
        || !pendingExecs.remove(sessionId, pending)) return null;
    return new ExecGrant(pending.target(), pending.actorId(), pending.remoteAddress(), pending.mode());
  }

  public ExecConnection openExec(ExecGrant grant, int columns, int rows) {
    if (columns < 20 || columns > 500 || rows < 5 || rows > 200) {
      throw new ApplicationException(422, "KUBERNETES_APPLICATION_INVALID", "终端尺寸无效");
    }
    ExecTarget target = grant.target();
    KubernetesClient client = clusters.openClientForOperations(target.clusterId());
    ExecWatch watch = null;
    try {
      AppRow application = app(target.applicationId());
      if (!target.namespace().equals(application.namespace())
          || !target.clusterId().equals(application.clusterId())) {
        throw new ApplicationException(409, "KUBERNETES_APPLICATION_OWNERSHIP_CONFLICT",
            "应用运行目标已变更，请重新打开详情");
      }
      Pod pod = ownedPod(client, application, target.deploymentName(), target.podUid());
      if (!target.podName().equals(pod.getMetadata().getName())) {
        throw ownershipConflict("Pod/" + target.podName());
      }
      requireContainer(pod, target.container());
      requireRunningContainer(pod, target.container());
      if (grant.mode() == ExecMode.ATTACH) requireAttachableTerminal(pod, target.container());
      var operation = client.pods().inNamespace(application.namespace())
          .withName(target.podName()).inContainer(target.container()).redirectingInput()
          .redirectingOutput().withTTY();
      watch = grant.mode() == ExecMode.ATTACH
          ? operation.attach() : operation.exec("sh", "-i");
      store.transaction(connection -> {
        audit(connection, grant.actorId(), "KUBERNETES_APPLICATION_EXEC_STARTED",
            "kubernetes_application", target.applicationId().toString(), "SUCCEEDED",
            grant.remoteAddress(), grant.mode() + " · " + target.deploymentName() + "/" + target.podName()
                + ":" + target.container());
        return null;
      });
      return new ExecConnection(client, watch);
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException
        | KubernetesClusterRegistryService.ClusterCredentialRejectedException
        | KubernetesClusterRegistryService.ClusterPermissionDeniedException
        | KubernetesClusterRegistryService.ClusterApiUnavailableException failure) {
      closeExecWatch(watch);
      client.close();
      throw failure;
    } catch (KubernetesClientException failure) {
      closeExecWatch(watch);
      client.close();
      if (failure.getCode() == 400 || failure.getCode() == 404 || failure.getCode() == 409) {
        throw execNotRunning();
      }
      throw apiFailure(failure);
    } catch (RuntimeException failure) {
      closeExecWatch(watch);
      client.close();
      throw apiFailure(failure);
    }
  }

  public void finishExec(ExecGrant grant, String status) {
    store.transaction(connection -> {
      String outcome = switch (status) {
        case "FAILED" -> "FAILED";
        case "REJECTED_CAPACITY" -> "DENIED";
        default -> "SUCCEEDED";
      };
      audit(connection, grant.actorId(), "KUBERNETES_APPLICATION_EXEC_FINISHED",
          "kubernetes_application", grant.target().applicationId().toString(), outcome,
          grant.remoteAddress(), "Exec " + status + " · " + grant.mode() + " · "
              + grant.target().deploymentName() + "/"
              + grant.target().podName() + ":" + grant.target().container());
      return null;
    });
  }

  public static final class ExecConnection implements AutoCloseable {
    private final KubernetesClient client;
    private final ExecWatch watch;

    ExecConnection(KubernetesClient client, ExecWatch watch) {
      this.client = client;
      this.watch = watch;
    }

    public ExecWatch watch() { return watch; }

    @Override
    public void close() {
      try {
        watch.close();
      } finally {
        client.close();
      }
    }
  }

  private DeploymentScope deploymentScope(KubernetesClient client, AppRow application,
      String deploymentName) {
    if (!validResourceName(deploymentName)) {
      throw new ApplicationException(404, "KUBERNETES_APPLICATION_RESOURCE_NOT_FOUND",
          "应用 Deployment 不存在");
    }
    StoredVersion current = application.currentVersion() == 0 ? null
        : version(application.id(), application.currentVersion());
    if (current == null || current.manifestYaml().isBlank()) {
      throw new ApplicationException(409, "KUBERNETES_APPLICATION_NOT_DEPLOYED",
          "应用尚无已部署版本");
    }
    List<HasMetadata> declared = parse(client, current.manifestYaml());
    Deployment expected = declared.stream().filter(Deployment.class::isInstance)
        .map(Deployment.class::cast)
        .filter(item -> deploymentName.equals(item.getMetadata().getName())).findFirst()
        .orElseThrow(() -> new ApplicationException(404,
            "KUBERNETES_APPLICATION_RESOURCE_NOT_FOUND", "Deployment 不属于此应用"));
    String expectedUid = resourceUids(application.id()).get(resourceId(expected));
    if (expectedUid == null) throw ownershipConflict(resourceId(expected));
    Deployment live = client.apps().deployments().inNamespace(application.namespace())
        .withName(deploymentName).get();
    if (live == null) return new DeploymentScope(application, null, List.of());
    requireOwned(live, application.id(), expectedUid);

    Map<String, String> selector = live.getSpec() == null || live.getSpec().getSelector() == null
        ? Map.of() : live.getSpec().getSelector().getMatchLabels();
    if (selector == null || selector.isEmpty()) {
      throw ownershipConflict(resourceId(live));
    }
    List<ReplicaSet> replicaSets = client.apps().replicaSets().inNamespace(application.namespace())
        .withLabels(selector).list().getItems().stream()
        .filter(replicaSet -> hasOwner(replicaSet, "Deployment", live.getMetadata().getUid()))
        .toList();
    Set<String> replicaSetUids = replicaSets.stream().map(item -> item.getMetadata().getUid())
        .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
    List<Pod> pods = replicaSetUids.isEmpty() ? List.of()
        : client.pods().inNamespace(application.namespace()).withLabels(selector).list().getItems()
            .stream().filter(pod -> pod.getMetadata() != null
                && hasOwner(pod, "ReplicaSet", replicaSetUids))
            .sorted(Comparator.comparing(pod -> pod.getMetadata().getName()))
            .toList();
    return new DeploymentScope(application, live, pods);
  }

  private Pod ownedPod(KubernetesClient client, AppRow application, String deploymentName,
      String podUid) {
    if (podUid == null || podUid.isBlank() || podUid.length() > 128) {
      throw new ApplicationException(404, "KUBERNETES_APPLICATION_POD_NOT_FOUND",
          "应用 Pod 不存在");
    }
    return deploymentScope(client, application, deploymentName).pods().stream()
        .filter(pod -> podUid.equals(pod.getMetadata().getUid())).findFirst()
        .orElseThrow(() -> new ApplicationException(404,
            "KUBERNETES_APPLICATION_POD_NOT_FOUND", "Pod 已退出或不属于此 Deployment"));
  }

  private static RuntimePod runtimePod(Pod pod) {
    Map<String, io.fabric8.kubernetes.api.model.ContainerStatus> statuses =
        pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null ? Map.of()
            : pod.getStatus().getContainerStatuses().stream().filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toMap(
                    io.fabric8.kubernetes.api.model.ContainerStatus::getName,
                    status -> status, (first, ignored) -> first));
    List<RuntimeContainer> containers = pod.getSpec() == null || pod.getSpec().getContainers() == null
        ? List.of() : pod.getSpec().getContainers().stream().map(container -> {
          var status = statuses.get(container.getName());
          String state = "WAITING";
          if (status != null && status.getState() != null) {
            if (status.getState().getRunning() != null) state = "RUNNING";
            else if (status.getState().getTerminated() != null) state = "TERMINATED";
            else if (status.getState().getWaiting() != null) {
              state = status.getState().getWaiting().getReason() == null ? "WAITING"
                  : status.getState().getWaiting().getReason();
            }
          }
          return new RuntimeContainer(container.getName(), container.getImage(),
              status != null && Boolean.TRUE.equals(status.getReady()), state);
        }).toList();
    Instant startedAt = null;
    if (pod.getStatus() != null && pod.getStatus().getStartTime() != null) {
      try {
        startedAt = java.time.OffsetDateTime.parse(pod.getStatus().getStartTime()).toInstant();
      } catch (java.time.format.DateTimeParseException ignored) {
        // Older Kubernetes versions can return a timestamp format without an offset.
      }
    }
    return new RuntimePod(pod.getMetadata().getName(), pod.getMetadata().getUid(),
        pod.getStatus() == null ? "UNKNOWN" : pod.getStatus().getPhase(),
        pod.getSpec() == null ? null : pod.getSpec().getNodeName(), startedAt, containers);
  }

  private static boolean hasOwner(HasMetadata resource, String kind, String ownerUid) {
    return ownerUid != null && resource.getMetadata() != null
        && resource.getMetadata().getOwnerReferences() != null
        && resource.getMetadata().getOwnerReferences().stream().filter(java.util.Objects::nonNull)
            .anyMatch(owner -> kind.equals(owner.getKind()) && ownerUid.equals(owner.getUid()));
  }

  private static boolean hasOwner(HasMetadata resource, String kind, Set<String> ownerUids) {
    return resource.getMetadata() != null && resource.getMetadata().getOwnerReferences() != null
        && resource.getMetadata().getOwnerReferences().stream().filter(java.util.Objects::nonNull)
            .anyMatch(owner -> kind.equals(owner.getKind()) && ownerUids.contains(owner.getUid()));
  }

  private static void requireContainer(Pod pod, String containerName) {
    if (containerName == null || pod.getSpec() == null || pod.getSpec().getContainers() == null
        || pod.getSpec().getContainers().stream().noneMatch(container ->
            containerName.equals(container.getName()))) {
      throw new ApplicationException(404, "KUBERNETES_APPLICATION_CONTAINER_NOT_FOUND",
          "容器不属于此 Pod");
    }
  }

  private static void requireAttachableTerminal(Pod pod, String containerName) {
    Container container = pod.getSpec().getContainers().stream()
        .filter(candidate -> containerName.equals(candidate.getName())).findFirst().orElse(null);
    if (container == null || !Boolean.TRUE.equals(container.getStdin())
        || !Boolean.TRUE.equals(container.getTty())) {
      throw new ApplicationException(409, "KUBERNETES_APPLICATION_EXEC_ATTACH_UNSUPPORTED",
          "reviews 容器未以 stdin 和 TTY 启动，无法附加现有终端；请选择新建终端");
    }
  }

  private static void requireRunningContainer(Pod pod, String containerName) {
    boolean podRunning = pod.getStatus() != null && "Running".equals(pod.getStatus().getPhase());
    boolean containerRunning = pod.getStatus() != null
        && pod.getStatus().getContainerStatuses() != null
        && pod.getStatus().getContainerStatuses().stream().filter(java.util.Objects::nonNull)
            .anyMatch(status -> containerName.equals(status.getName())
                && status.getState() != null && status.getState().getRunning() != null);
    if (!podRunning || !containerRunning) throw execNotRunning();
  }

  private static ApplicationException execNotRunning() {
    return new ApplicationException(409, "KUBERNETES_APPLICATION_EXEC_NOT_RUNNING",
        "所选 Pod 或 reviews 容器尚未运行，暂时无法连接 Exec；请等待容器启动后重试");
  }

  private static void closeExecWatch(ExecWatch watch) {
    if (watch == null) return;
    try {
      watch.close();
    } catch (RuntimeException failure) {
      LOG.debug("Could not close failed Kubernetes application exec watch", failure);
    }
  }

  private static String eventTimestamp(io.fabric8.kubernetes.api.model.Event event) {
    if (event.getEventTime() != null) return event.getEventTime().toString();
    if (event.getLastTimestamp() != null) return event.getLastTimestamp();
    return event.getMetadata() == null ? null : event.getMetadata().getCreationTimestamp();
  }

  private static String tail(String value, int maxChars) {
    if (value == null || value.length() <= maxChars) return value;
    return value.substring(value.length() - maxChars);
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
    List<TopologyEdit> edits = input == null || input.edits() == null ? List.of() : input.edits();
    List<TopologyAddition> additions = input == null || input.additions() == null
        ? List.of() : input.additions();
    List<TopologyYamlEdit> yamlEdits = input == null || input.yamlEdits() == null
        ? List.of() : input.yamlEdits();
    List<TopologyYamlAddition> yamlAdditions = input == null || input.yamlAdditions() == null
        ? List.of() : input.yamlAdditions();
    if (input == null || input.baseVersion() == null || input.baseVersion() < 1
        || edits.size() + additions.size() + yamlEdits.size() + yamlAdditions.size() < 1
        || edits.size() + additions.size() + yamlEdits.size() + yamlAdditions.size() > 32
        || additions.size() + yamlAdditions.size() > 32) {
      throw invalid("版本草稿需要基础版本及 1–32 项拓扑修改或新增资源");
    }
    Set<String> editedResourceIds = new HashSet<>();
    for (TopologyEdit edit : edits) {
      if (edit == null || edit.resourceId() == null || !editedResourceIds.add(edit.resourceId())) {
        throw invalid("拓扑修改包含重复或无效资源");
      }
    }
    for (TopologyYamlEdit edit : yamlEdits) {
      if (edit == null || edit.resourceId() == null || !editedResourceIds.add(edit.resourceId())) {
        throw invalid("表单修改与源 YAML 修改不能重复引用同一资源");
      }
    }
    long sourceYamlLength = yamlEdits.stream()
        .mapToLong(edit -> edit.sourceYaml() == null ? 0 : edit.sourceYaml().length()).sum()
        + yamlAdditions.stream()
            .mapToLong(addition -> addition.sourceYaml() == null ? 0 : addition.sourceYaml().length())
            .sum();
    if (sourceYamlLength > MAX_RESOURCE_YAML_LENGTH) {
      throw invalid("本次源 YAML 修改总长度不能超过 256 KiB");
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
      List<HasMetadata> after = applyYamlEdits(client, app.namespace(), applicationId,
          before, yamlEdits);
      after = applyEdits(after, edits, additions);
      after = applyYamlAdditions(client, app.namespace(), applicationId, after, yamlAdditions);
      after = applyAdditions(client, app.namespace(), applicationId, after, additions);
      validateApplicationResources(after);
      ensureReferencedServiceAccountsExist(client, app.namespace(), after);
      String newYaml = serialize(after);
      String diff = resourceDiff(before, after);
      if (diff.isBlank()) throw invalid("拓扑没有产生 YAML 变化，请修改已有资源或添加 Deployment / Service");
      Map<String, String> knownResourceUids = resourceUids(applicationId);
      Set<String> pendingResourceIds = after.stream().map(KubernetesApplicationService::resourceId)
          .filter(id -> !knownResourceUids.containsKey(id))
          .collect(java.util.stream.Collectors.toSet());
      Topology candidateTopology = topology(client, app.id(), app.namespace(), after,
          knownResourceUids, pendingResourceIds);
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
    boolean networkRuntimeActive = store.transaction(connection -> !query(connection,
        "SELECT application_id FROM kubernetes_application_network_runtime WHERE application_id=? "
            + "AND state IN ('APPLYING','APPLIED','VERIFYING','VERIFY_FAILED','COMPENSATING','FAILED','PARTIAL')",
        applicationId).isEmpty() || !query(connection,
            "SELECT application_id FROM kubernetes_application_traffic_runtime WHERE application_id=? "
                + "AND state IN ('APPLYING','APPLIED','VERIFY_FAILED','COMPENSATING','FAILED','PARTIAL')",
            applicationId).isEmpty());
    if (networkRuntimeActive) throw new ApplicationException(409,
        "APPLICATION_NETWORK_RUNTIME_ACTIVE",
        "请先补偿恢复应用网络或 Service 流量路由运行时后再部署应用版本");
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
      query(connection, "SELECT id FROM kubernetes_application WHERE id=? FOR UPDATE", applicationId);
      if (!query(connection,
          "SELECT application_id FROM kubernetes_application_network_runtime WHERE application_id=? "
              + "AND state IN ('APPLYING','APPLIED','VERIFYING','VERIFY_FAILED','COMPENSATING','FAILED','PARTIAL') FOR UPDATE",
          applicationId).isEmpty() || !query(connection,
              "SELECT application_id FROM kubernetes_application_traffic_runtime WHERE application_id=? "
                  + "AND state IN ('APPLYING','APPLIED','VERIFY_FAILED','COMPENSATING','FAILED','PARTIAL') FOR UPDATE",
              applicationId).isEmpty()) {
        throw new ApplicationException(409, "APPLICATION_NETWORK_RUNTIME_ACTIVE",
            "请先补偿恢复应用网络或 Service 流量路由运行时后再部署应用版本");
      }
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
      Map<String, String> previouslyRegistered = Map.copyOf(resourceUids);
      List<HasMetadata> addedResources = desired.stream()
          .filter(resource -> !previouslyRegistered.containsKey(resourceId(resource))).toList();
      List<HasMetadata> changed = new ArrayList<>();
      try {
        for (HasMetadata resource : desired) {
          applyOwned(client, app.namespace(), applicationId, resourceUids, resource);
          changed.add(resource);
        }
        if (!addedResources.isEmpty()) recordResources(applicationId, addedResources);
      } catch (RuntimeException applyFailure) {
        boolean restored = restoreResources(client, app.namespace(), applicationId, resourceUids,
            before, changed);
        if (restored && !addedResources.isEmpty()) {
          try {
            removeResourceRecords(applicationId, addedResources, resourceUids);
          } catch (RuntimeException cleanupFailure) {
            restored = false;
          }
        }
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
      if (deployment.getSpec() == null || deployment.getSpec().getTemplate() == null
          || deployment.getSpec().getSelector() == null
          || deployment.getSpec().getSelector().getMatchLabels() == null) {
        throw invalid("Deployment 必须包含 Pod 模板和 matchLabels selector");
      }
      String selectedApplication = deployment.getSpec().getSelector().getMatchLabels()
          .get(APPLICATION_ID);
      if (selectedApplication != null && !applicationId.toString().equals(selectedApplication)) {
        throw invalid("Deployment selector 不能覆盖保留的应用所有权标签");
      }
      var template = deployment.getSpec().getTemplate();
      Map<String, String> podLabels = template.getMetadata() == null
          || template.getMetadata().getLabels() == null
              ? new HashMap<>() : new HashMap<>(template.getMetadata().getLabels());
      String selectedPodApplication = podLabels.get(APPLICATION_ID);
      if (selectedPodApplication != null
          && !applicationId.toString().equals(selectedPodApplication)) {
        throw invalid("Deployment Pod 模板不能覆盖保留的应用所有权标签");
      }
      podLabels.put(APPLICATION_ID, applicationId.toString());
      if (template.getMetadata() == null) {
        template.setMetadata(new ObjectMetaBuilder().withLabels(podLabels).build());
      } else {
        template.getMetadata().setLabels(podLabels);
      }
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
    String serviceType = spec.getType() == null ? "ClusterIP" : spec.getType();
    if (!Set.of("ClusterIP", "NodePort", "LoadBalancer").contains(serviceType)) {
      throw invalid("Service 只支持 ClusterIP、NodePort 或 LoadBalancer 类型");
    }
    if (spec.getExternalName() != null || spec.getExternalIPs() != null
        && !spec.getExternalIPs().isEmpty()) {
      throw invalid("不支持 ExternalName 或 externalIPs Service");
    }
    spec.setType(serviceType);
    spec.setClusterIP(null);
    spec.setClusterIPs(null);
    spec.setIpFamilies(null);
    spec.setIpFamilyPolicy(null);
    if (!"NodePort".equals(serviceType) && !"LoadBalancer".equals(serviceType)) {
      spec.getPorts().forEach(port -> port.setNodePort(null));
    }
    if (!"LoadBalancer".equals(serviceType)) spec.setHealthCheckNodePort(null);
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

  private void removeResourceRecords(UUID applicationId, List<HasMetadata> resources,
      Map<String, String> resourceUids) {
    store.transaction(connection -> {
      for (HasMetadata resource : resources) {
        String uid = resourceUids.get(resourceId(resource));
        if (uid != null) update(connection,
            "DELETE FROM kubernetes_application_resource WHERE application_id=? "
                + "AND resource_kind=? AND resource_name=? AND resource_uid=?",
            applicationId, resource.getKind(), resource.getMetadata().getName(), uid);
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

  private static List<HasMetadata> applyYamlEdits(KubernetesClient client, String namespace,
      UUID applicationId, List<HasMetadata> source, List<TopologyYamlEdit> edits) {
    if (edits.isEmpty()) return source;
    Map<String, HasMetadata> resources = new LinkedHashMap<>();
    for (HasMetadata item : source) resources.put(resourceId(item), item);
    Set<String> edited = new HashSet<>();
    for (TopologyYamlEdit edit : edits) {
      if (edit == null || edit.resourceId() == null || !edited.add(edit.resourceId())) {
        throw invalid("源 YAML 修改包含重复或无效资源");
      }
      HasMetadata current = resources.get(edit.resourceId());
      if (current == null || !supported(current)) {
        throw invalid("源 YAML 修改引用了应用清单之外或不支持的资源");
      }
      HasMetadata replacement = parseSingleApplicationResource(client, edit.sourceYaml());
      if (!edit.resourceId().equals(resourceId(replacement))) {
        throw invalid("源 YAML 不能修改资源的 kind 或名称");
      }
      normalizeResource(replacement, namespace, applicationId);
      resources.put(edit.resourceId(), replacement);
    }
    return new ArrayList<>(resources.values());
  }

  private static List<HasMetadata> applyYamlAdditions(KubernetesClient client, String namespace,
      UUID applicationId, List<HasMetadata> source, List<TopologyYamlAddition> additions) {
    if (additions.isEmpty()) return source;
    if (source.size() + additions.size() > 64) {
      throw invalid("应用清单资源总数不能超过 64 个");
    }
    List<HasMetadata> resources = new ArrayList<>(source);
    Set<String> ids = resources.stream().map(KubernetesApplicationService::resourceId)
        .collect(java.util.stream.Collectors.toCollection(HashSet::new));
    for (TopologyYamlAddition addition : additions) {
      if (addition == null) throw invalid("新增源 YAML 不能为空");
      HasMetadata resource = parseSingleApplicationResource(client, addition.sourceYaml());
      if (!(resource instanceof Deployment || resource instanceof Service)
          || resource.getMetadata() == null
          || !validResourceName(resource.getMetadata().getName())) {
        throw invalid("源 YAML 新增只支持名称有效的 Deployment 或 Service");
      }
      String id = resourceId(resource);
      if (!ids.add(id)) throw invalid("应用清单包含重复资源: " + id);
      normalizeResource(resource, namespace, applicationId);
      if (fetch(client, resource.getKind(), namespace, resource.getMetadata().getName()) != null) {
        throw new ApplicationException(409, "KUBERNETES_APPLICATION_RESOURCE_CONFLICT",
            id + " 已存在，未接管该资源");
      }
      resources.add(resource);
    }
    return resources;
  }

  private static HasMetadata parseSingleApplicationResource(KubernetesClient client,
      String sourceYaml) {
    if (sourceYaml == null || sourceYaml.isBlank()
        || sourceYaml.length() > MAX_RESOURCE_YAML_LENGTH) {
      throw invalid("每项源 YAML 必须为 1–262144 个字符");
    }
    List<HasMetadata> resources;
    try {
      resources = client.load(new ByteArrayInputStream(
          sourceYaml.getBytes(StandardCharsets.UTF_8))).items();
    } catch (RuntimeException failure) {
      throw invalid("源 YAML 格式无效");
    }
    if (resources.size() != 1 || !supported(resources.getFirst())
        || resources.getFirst().getMetadata() == null
        || !validResourceName(resources.getFirst().getMetadata().getName())) {
      throw invalid("每张卡片的源 YAML 必须只包含一个名称有效的 Deployment、Service 或 ServiceAccount");
    }
    return resources.getFirst();
  }

  private static List<HasMetadata> applyEdits(List<HasMetadata> source,
      List<TopologyEdit> edits, List<TopologyAddition> additions) {
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
    for (TopologyAddition addition : additions) {
      if (addition != null && "Deployment".equals(addition.kind())
          && validResourceName(addition.name())) {
        validTargets.put(addition.name(), addition.name());
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
        if (edit.targetDeployment() != null || edit.servicePort() != null || edit.targetPort() != null
            || edit.serviceProtocol() != null) throw invalid("Deployment 不接受 Service 配置修改");
      } else if (item instanceof Service service) {
        if (edit.replicas() != null || edit.image() != null) {
          throw invalid("Service 不接受副本数或镜像修改");
        }
        if (edit.targetDeployment() != null) {
          String target = edit.targetDeployment().trim();
          if (!validTargets.containsKey(target)) throw invalid("Service 连接目标不属于应用中的 Deployment");
          service.getSpec().setSelector(Map.of("app", target));
        }
        if (edit.servicePort() != null || edit.targetPort() != null || edit.serviceProtocol() != null) {
          List<ServicePort> ports = service.getSpec().getPorts();
          if (ports == null || ports.isEmpty()) throw invalid("Service 没有可编辑的端口");
          ServicePort primary = ports.getFirst();
          int nextPort = edit.servicePort() == null ? primary.getPort() : edit.servicePort();
          if (nextPort < 1 || nextPort > 65535) throw invalid("Service 端口必须在 1–65535 之间");
          String nextProtocol = edit.serviceProtocol() == null || edit.serviceProtocol().isBlank()
              ? (primary.getProtocol() == null ? "TCP" : primary.getProtocol())
              : edit.serviceProtocol().trim().toUpperCase(java.util.Locale.ROOT);
          if (!Set.of("TCP", "UDP", "SCTP").contains(nextProtocol)) {
            throw invalid("Service 协议只支持 TCP、UDP 或 SCTP");
          }
          for (int index = 1; index < ports.size(); index++) {
            ServicePort other = ports.get(index);
            String otherProtocol = other.getProtocol() == null ? "TCP" : other.getProtocol();
            if (other.getPort() != null && other.getPort() == nextPort && otherProtocol.equals(nextProtocol)) {
              throw invalid("同协议下 Service 端口不能重复");
            }
          }
          primary.setPort(nextPort);
          primary.setProtocol(nextProtocol);
          if (edit.targetPort() != null) primary.setTargetPort(parseTargetPort(edit.targetPort()));
        }
      } else if (edit.replicas() != null || edit.image() != null || edit.targetDeployment() != null
          || edit.servicePort() != null || edit.targetPort() != null || edit.serviceProtocol() != null) {
        throw invalid("ServiceAccount 不包含可编辑的拓扑属性");
      }
    }
    return new ArrayList<>(resources.values());
  }

  private static List<HasMetadata> applyAdditions(KubernetesClient client, String namespace,
      UUID applicationId, List<HasMetadata> source, List<TopologyAddition> additions) {
    if (additions.isEmpty()) return source;
    if (source.size() + additions.size() > 64) {
      throw invalid("应用清单资源总数不能超过 64 个");
    }
    List<HasMetadata> resources = new ArrayList<>(source);
    Set<String> ids = new HashSet<>();
    Map<String, String> deploymentLabels = new HashMap<>();
    for (HasMetadata resource : source) {
      ids.add(resourceId(resource));
      if (resource instanceof Deployment deployment
          && deployment.getSpec().getTemplate().getMetadata().getLabels() != null) {
        String appLabel = deployment.getSpec().getTemplate().getMetadata().getLabels().get("app");
        if (appLabel != null) deploymentLabels.put(appLabel, appLabel);
      }
    }
    for (TopologyAddition addition : additions) {
      if (addition != null && "Deployment".equals(addition.kind())
          && validResourceName(addition.name())) {
        deploymentLabels.put(addition.name(), addition.name());
      }
    }
    for (TopologyAddition addition : additions) {
      if (addition == null || !("Deployment".equals(addition.kind()) || "Service".equals(addition.kind()))
          || !validResourceName(addition.name())) {
        throw invalid("新增资源只支持名称有效的 Deployment 或 Service");
      }
      String id = addition.kind() + "/" + addition.name();
      if (!ids.add(id)) throw invalid("应用清单包含重复资源: " + id);
      HasMetadata resource;
      if ("Deployment".equals(addition.kind())) {
        if (addition.replicas() == null || addition.replicas() < 0
            || addition.replicas() > MAX_REPLICAS || addition.image() == null
            || !IMAGE.matcher(addition.image().trim()).matches()
            || addition.targetDeployment() != null || addition.servicePort() != null
            || addition.targetPort() != null || addition.serviceProtocol() != null) {
          throw invalid("新增 Deployment 必须提供有效副本数和镜像，且不接受 Service 配置");
        }
        String image = addition.image().trim();
        String label = addition.name();
        resource = new DeploymentBuilder()
            .withNewMetadata().withName(addition.name()).endMetadata()
            .withNewSpec().withReplicas(addition.replicas())
              .withNewSelector().addToMatchLabels("app", label).endSelector()
              .withNewTemplate()
                .withNewMetadata().addToLabels("app", label).endMetadata()
                .withNewSpec().addNewContainer().withName("app").withImage(image)
                  .withImagePullPolicy("IfNotPresent").endContainer().endSpec()
              .endTemplate()
            .endSpec().build();
        deploymentLabels.put(label, label);
      } else {
        String target = addition.targetDeployment() == null ? ""
            : addition.targetDeployment().trim();
        int port = addition.servicePort() == null ? 0 : addition.servicePort();
        String targetPort = addition.targetPort() == null ? "" : addition.targetPort().trim();
        String protocol = addition.serviceProtocol() == null ? "TCP"
            : addition.serviceProtocol().trim().toUpperCase(java.util.Locale.ROOT);
        if (!deploymentLabels.containsKey(target) || port < 1 || port > 65535
            || !Set.of("TCP", "UDP", "SCTP").contains(protocol)) {
          throw invalid("新增 Service 必须选择应用内 Deployment，并提供有效端口和协议");
        }
        IntOrString parsedTargetPort = parseTargetPort(targetPort);
        resource = new ServiceBuilder()
            .withNewMetadata().withName(addition.name()).endMetadata()
            .withNewSpec().withType("ClusterIP").addToSelector("app", target)
              .addNewPort().withName("http").withPort(port).withProtocol(protocol)
                .withTargetPort(parsedTargetPort).endPort()
            .endSpec().build();
      }
      normalizeResource(resource, namespace, applicationId);
      if (fetch(client, addition.kind(), namespace, addition.name()) != null) {
        throw new ApplicationException(409, "KUBERNETES_APPLICATION_RESOURCE_CONFLICT",
            id + " 已存在，未接管该资源");
      }
      resources.add(resource);
    }
    validateApplicationResources(resources);
    return resources;
  }

  private static Topology topology(KubernetesClient client, UUID applicationId, String namespace,
      List<HasMetadata> resources, Map<String, String> resourceUids) {
    return topology(client, applicationId, namespace, resources, resourceUids, Set.of());
  }

  private static Topology topology(KubernetesClient client, UUID applicationId, String namespace,
      List<HasMetadata> resources, Map<String, String> resourceUids, Set<String> pendingResourceIds) {
    List<TopologyNode> nodes = new ArrayList<>();
    List<TopologyEdge> edges = new ArrayList<>();
    List<Deployment> deployments = resources.stream().filter(Deployment.class::isInstance)
        .map(Deployment.class::cast).toList();
    Map<String, Deployment> observedDeployments = new HashMap<>();
    boolean deploymentListAvailable = false;
    try {
      var response = client.apps().deployments().inNamespace(namespace)
          .withLabel(APPLICATION_ID, applicationId.toString()).list();
      if (response == null || response.getItems() == null) {
        throw new IllegalStateException("Kubernetes Deployment list response is incomplete");
      }
      for (Deployment observed : response.getItems()) {
        if (observed.getMetadata() != null && observed.getMetadata().getName() != null) {
          observedDeployments.put(observed.getMetadata().getName(), observed);
        }
      }
      deploymentListAvailable = true;
    } catch (RuntimeException failure) {
      LOG.debugf("Kubernetes application Deployment list failed for %s (%s); using named reads",
          applicationId, failure.getClass().getSimpleName());
    }
    for (HasMetadata item : resources) {
      if (item instanceof Deployment deployment) {
        String id = resourceId(item);
        String expectedUid = resourceUids.get(id);
        boolean pending = pendingResourceIds.contains(id);
        if (expectedUid == null && !pending) throw ownershipConflict(id);
        Integer replicas = deployment.getSpec().getReplicas();
        Integer ready = pending ? 0 : null;
        String state = pending ? "PENDING" : "UNKNOWN";
        if (!pending) {
          try {
            String name = deployment.getMetadata().getName();
            Deployment live = deploymentListAvailable ? observedDeployments.get(name) : null;
            if (!deploymentListAvailable || live == null) {
              live = (Deployment) fetch(client, "Deployment", namespace, name);
            }
            if (live == null) state = "MISSING";
            else {
              requireOwned(live, applicationId, expectedUid);
              ready = live.getStatus() == null ? null : live.getStatus().getReadyReplicas();
              if (ready == null) ready = 0;
              int desired = live.getSpec() == null || live.getSpec().getReplicas() == null
                  ? 1 : live.getSpec().getReplicas();
              state = deploymentFailed(live) ? "FAILED"
                  : ready >= desired ? "READY" : "PROGRESSING";
            }
          } catch (ApplicationException conflict) {
            throw conflict;
          } catch (RuntimeException failure) {
            state = "UNAVAILABLE";
            LOG.warn("Unable to observe Kubernetes application Deployment " + namespace + "/"
                + deployment.getMetadata().getName(), failure);
          }
        }
        String image = deployment.getSpec().getTemplate().getSpec().getContainers().stream()
            .map(container -> container.getImage()).filter(value -> value != null).findFirst().orElse(null);
        nodes.add(new TopologyNode(id, "Deployment", deployment.getMetadata().getName(),
            replicas, ready, image, deployment.getSpec().getTemplate().getMetadata().getLabels()
                .get("app"), null, null, null, List.of(), state, serialize(List.of(deployment)),
            null, List.of()));
      } else if (item instanceof Service service) {
        String id = resourceId(item);
        String expectedUid = resourceUids.get(id);
        boolean pending = pendingResourceIds.contains(id);
        if (expectedUid == null && !pending) throw ownershipConflict(id);
        String target = service.getSpec().getSelector() == null ? null
            : service.getSpec().getSelector().get("app");
        ServicePort firstPort = service.getSpec().getPorts().getFirst();
        IntOrString serviceTargetPort = firstPort.getTargetPort();
        String targetPort = serviceTargetPort == null ? String.valueOf(firstPort.getPort())
            : String.valueOf(serviceTargetPort.getValue());
        String protocol = firstPort.getProtocol() == null ? "TCP" : firstPort.getProtocol();
        String state = pending ? "PENDING" : "ACTIVE";
        Service observedService = service;
        if (!pending) {
          try {
            HasMetadata live = fetch(client, "Service", namespace, service.getMetadata().getName());
            if (live == null) state = "MISSING";
            else {
              requireOwned(live, applicationId, expectedUid);
              if (live instanceof Service liveService) observedService = liveService;
            }
          } catch (ApplicationException conflict) {
            throw conflict;
          } catch (RuntimeException failure) {
            state = "UNAVAILABLE";
          }
        }
        Service serviceState = observedService;
        List<TopologyServicePort> servicePorts = service.getSpec().getPorts().stream()
            .map(port -> {
              ServicePort livePort = matchingServicePort(serviceState, port);
              ServicePort displayedPort = livePort == null ? port : livePort;
              IntOrString serviceTarget = displayedPort.getTargetPort();
              return new TopologyServicePort(displayedPort.getName(), displayedPort.getPort(),
                  serviceTarget == null ? String.valueOf(displayedPort.getPort())
                      : String.valueOf(serviceTarget.getValue()),
                  displayedPort.getProtocol() == null ? "TCP" : displayedPort.getProtocol(),
                  livePort == null ? null : livePort.getNodePort());
            }).toList();
        String serviceType = serviceState.getSpec() == null || serviceState.getSpec().getType() == null
            ? "ClusterIP" : serviceState.getSpec().getType();
        nodes.add(new TopologyNode(id, "Service", service.getMetadata().getName(),
            null, null, null, target, firstPort.getPort(), targetPort, protocol,
            servicePorts, state, serialize(List.of(service)), serviceType,
            serviceExternalAddresses(serviceState)));
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
            null, null, null, null, null, null, null, List.of(), state,
            serialize(List.of(account)), null, List.of()));
      }
    }
    nodes.sort(Comparator.comparing(TopologyNode::kind).thenComparing(TopologyNode::name));
    edges.sort(Comparator.comparing(TopologyEdge::source).thenComparing(TopologyEdge::target));
    return new Topology(List.copyOf(nodes), List.copyOf(edges));
  }

  private static ServicePort matchingServicePort(Service service, ServicePort sourcePort) {
    if (service.getSpec() == null || service.getSpec().getPorts() == null) return null;
    return service.getSpec().getPorts().stream().filter(java.util.Objects::nonNull)
        .filter(port -> (sourcePort.getName() != null
                && sourcePort.getName().equals(port.getName()))
            || (sourcePort.getPort() == port.getPort()
                && effectiveServiceProtocol(sourcePort).equals(effectiveServiceProtocol(port))))
        .findFirst().orElse(null);
  }

  private static String effectiveServiceProtocol(ServicePort port) {
    return port.getProtocol() == null ? "TCP" : port.getProtocol();
  }

  private static List<String> serviceExternalAddresses(Service service) {
    Set<String> addresses = new LinkedHashSet<>();
    if (service.getStatus() != null && service.getStatus().getLoadBalancer() != null
        && service.getStatus().getLoadBalancer().getIngress() != null) {
      service.getStatus().getLoadBalancer().getIngress().stream()
          .filter(java.util.Objects::nonNull)
          .map(ingress -> ingress.getIp() == null || ingress.getIp().isBlank()
              ? ingress.getHostname() : ingress.getIp())
          .filter(address -> address != null && !address.isBlank())
          .map(String::trim).forEach(addresses::add);
    }
    if (service.getSpec() != null) {
      if (service.getSpec().getExternalIPs() != null) {
        service.getSpec().getExternalIPs().stream().filter(java.util.Objects::nonNull)
            .map(String::trim).filter(address -> !address.isEmpty()).forEach(addresses::add);
      }
      String externalName = service.getSpec().getExternalName();
      if (externalName != null && !externalName.isBlank()) addresses.add(externalName.trim());
    }
    return List.copyOf(addresses);
  }

  private static boolean deploymentFailed(Deployment deployment) {
    if (deployment.getStatus() == null || deployment.getStatus().getConditions() == null) return false;
    return deployment.getStatus().getConditions().stream().filter(java.util.Objects::nonNull)
        .anyMatch(condition ->
            ("Progressing".equals(condition.getType())
                && "False".equalsIgnoreCase(condition.getStatus())
                && "ProgressDeadlineExceeded".equals(condition.getReason()))
            || ("ReplicaFailure".equals(condition.getType())
                && "True".equalsIgnoreCase(condition.getStatus())));
  }

  private static boolean matches(Map<String, String> selector, Map<String, String> labels) {
    if (selector == null || selector.isEmpty()) return false;
    return labels != null && selector.entrySet().stream()
        .allMatch(entry -> entry.getValue().equals(labels.get(entry.getKey())));
  }

  private static IntOrString parseTargetPort(String value) {
    String target = value == null ? "" : value.trim();
    if (target.matches("[0-9]{1,5}")) {
      int port = Integer.parseInt(target);
      if (port >= 1 && port <= 65535) return new IntOrString(port);
      throw invalid("Service 目标端口必须在 1–65535 之间");
    }
    if (!SERVICE_PORT_NAME.matcher(target).matches()) {
      throw invalid("Service 目标端口必须是 1–65535 的数字或有效命名端口");
    }
    return new IntOrString(target);
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
    String id = resourceId(desired);
    String expectedUid = resourceUids.get(id);
    if (expectedUid == null) {
      if (live != null) {
        throw new ApplicationException(409, "KUBERNETES_APPLICATION_RESOURCE_CONFLICT",
            "新增资源名称已被占用，未接管该资源：" + id);
      }
      Map<String, String> labels = desired.getMetadata().getLabels() == null
          ? new HashMap<>() : new HashMap<>(desired.getMetadata().getLabels());
      labels.put(MANAGED_BY, "nanpanel");
      labels.put(APPLICATION_ID, applicationId.toString());
      desired.getMetadata().setNamespace(namespace);
      desired.getMetadata().setLabels(labels);
      HasMetadata created = client.resource(desired).create();
      if (created == null || created.getMetadata() == null || created.getMetadata().getUid() == null
          || created.getMetadata().getUid().isBlank()) {
        throw new ApplicationException(502, "KUBERNETES_RESOURCE_UID_MISSING",
            "Kubernetes 未返回新增资源 UID，部署结果需要核对");
      }
      String createdUid = created.getMetadata().getUid();
      desired.getMetadata().setUid(createdUid);
      resourceUids.put(id, createdUid);
      return;
    }
    requireOwned(live, applicationId, expectedUid);
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
      HasMetadata attemptedResource = attempted.get(i);
      HasMetadata desired = old.get(resourceId(attemptedResource));
      try {
        HasMetadata live = fetch(client, attemptedResource.getKind(), namespace,
            attemptedResource.getMetadata().getName());
        if (desired == null) {
          if (live == null) continue;
          requireOwned(live, applicationId, resourceUids.get(resourceId(attemptedResource)));
          client.resource(live).delete();
          if (fetch(client, attemptedResource.getKind(), namespace,
              attemptedResource.getMetadata().getName()) != null) restored = false;
          continue;
        }
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
