package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.UUID;
import io.nanpanel.cluster.KubernetesTopologyDiscovery;

@Path("/api/v1/kubernetes/clusters")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class KubernetesClusterResource {
  public record NodeHostMappingRequest(String hostId) {}

  @Inject KubernetesClusterRegistryService clusters;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  @GET
  public Response list() {
    actor();
    return noStore(Response.ok(clusters.list())).build();
  }

  @POST
  public Response importCluster(KubernetesClusterRegistryService.ImportRequest input) {
    String actor = actor();
    KubernetesClusterRegistryService.ClusterSummary cluster;
    try {
      cluster = clusters.importCluster(actor, clientIp(), input);
    } catch (IllegalArgumentException invalid) {
      throw fail(422, "KUBECONFIG_INVALID", invalid.getMessage());
    } catch (KubernetesClusterRegistryService.CredentialKeyUnavailableException unavailable) {
      throw fail(503, "KUBERNETES_CREDENTIAL_KEY_UNAVAILABLE",
          "集群凭据加密密钥未正确配置，未保存集群凭据");
    } catch (KubernetesClusterRegistryService.ClusterAlreadyExistsException exists) {
      throw fail(409, "KUBERNETES_CLUSTER_EXISTS", "集群名称已登记");
    } catch (KubernetesClusterRegistryService.ClusterCredentialRejectedException rejected) {
      throw fail(422, "KUBECONFIG_CREDENTIALS_REJECTED", "Kubernetes API 拒绝了所选 context 的凭据");
    } catch (KubernetesClusterRegistryService.ClusterPermissionDeniedException denied) {
      throw fail(403, "KUBERNETES_RBAC_DENIED", "所选 context 没有读取 Namespace 与 Node 清单的权限");
    } catch (KubernetesClusterRegistryService.ClusterApiUnavailableException unavailable) {
      throw fail(502, "KUBERNETES_CLUSTER_UNAVAILABLE",
          "无法验证 Kubernetes API 或读取必需的节点和 Namespace 清单");
    }
    return noStore(Response.status(Response.Status.CREATED).entity(cluster)
        .location(java.net.URI.create(http.absoluteURI())
            .resolve("/api/v1/kubernetes/clusters/" + cluster.id()))).build();
  }

  @GET
  @Path("{clusterId}")
  public Response get(@PathParam("clusterId") String clusterValue) {
    actor();
    try {
      return noStore(Response.ok(clusters.get(uuid(clusterValue)))).build();
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw fail(404, "KUBERNETES_CLUSTER_NOT_FOUND", "集群未登记");
    }
  }

  @GET
  @Path("{clusterId}/topology/namespaces")
  public Response topologyNamespaces(@PathParam("clusterId") String clusterValue) {
    actor();
    try {
      return noStore(Response.ok(clusters.topologyNamespaces(uuid(clusterValue)))).build();
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw fail(404, "KUBERNETES_CLUSTER_NOT_FOUND", "集群未登记");
    } catch (KubernetesClusterRegistryService.ClusterCredentialRejectedException rejected) {
      throw fail(502, "KUBERNETES_CREDENTIALS_REJECTED", "已保存的集群凭据被 Kubernetes API 拒绝");
    } catch (KubernetesClusterRegistryService.ClusterPermissionDeniedException denied) {
      throw fail(403, "KUBERNETES_RBAC_DENIED", "集群凭据没有读取 Namespace 清单的权限");
    } catch (KubernetesClusterRegistryService.ClusterApiUnavailableException unavailable) {
      throw fail(502, "KUBERNETES_CLUSTER_UNAVAILABLE", "无法读取该集群的 Namespace 清单");
    }
  }

  @GET
  @Path("{clusterId}/topology/namespaces/{namespace}")
  public Response topologyNamespace(@PathParam("clusterId") String clusterValue,
                                    @PathParam("namespace") String namespace) {
    actor();
    if (namespace == null || !namespace.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
      throw fail(404, "KUBERNETES_NAMESPACE_NOT_FOUND", "Namespace 不存在或名称无效");
    }
    try {
      return noStore(Response.ok(clusters.topologyNamespace(uuid(clusterValue), namespace))).build();
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw fail(404, "KUBERNETES_CLUSTER_NOT_FOUND", "集群未登记");
    } catch (KubernetesTopologyDiscovery.NamespaceNotFoundException missing) {
      throw fail(404, "KUBERNETES_NAMESPACE_NOT_FOUND", "Namespace 不存在或名称无效");
    } catch (KubernetesClusterRegistryService.ClusterCredentialRejectedException rejected) {
      throw fail(502, "KUBERNETES_CREDENTIALS_REJECTED", "已保存的集群凭据被 Kubernetes API 拒绝");
    } catch (KubernetesClusterRegistryService.ClusterPermissionDeniedException denied) {
      throw fail(403, "KUBERNETES_RBAC_DENIED", "集群凭据没有读取该 Namespace 资源的权限");
    } catch (KubernetesClusterRegistryService.ClusterApiUnavailableException unavailable) {
      throw fail(502, "KUBERNETES_CLUSTER_UNAVAILABLE", "无法读取该集群 Namespace 的资源清单");
    }
  }

  @GET
  @Path("{clusterId}/nodes")
  public Response nodes(@PathParam("clusterId") String clusterValue) {
    actor();
    try {
      return noStore(Response.ok(clusters.refreshNodes(uuid(clusterValue)))).build();
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw fail(404, "KUBERNETES_CLUSTER_NOT_FOUND", "集群未登记");
    } catch (KubernetesClusterRegistryService.ClusterCredentialRejectedException rejected) {
      throw fail(502, "KUBERNETES_CREDENTIALS_REJECTED", "已保存的集群凭据被 Kubernetes API 拒绝");
    } catch (KubernetesClusterRegistryService.ClusterPermissionDeniedException denied) {
      throw fail(403, "KUBERNETES_RBAC_DENIED", "集群凭据没有读取节点清单的权限");
    } catch (KubernetesClusterRegistryService.ClusterApiUnavailableException unavailable) {
      throw fail(502, "KUBERNETES_CLUSTER_UNAVAILABLE", "Kubernetes API 暂时不可用；最近一次节点清单已标记为过期");
    }
  }

  @PUT
  @Path("{clusterId}/nodes/{nodeUid}/host")
  public Response mapHost(@PathParam("clusterId") String clusterValue,
                          @PathParam("nodeUid") String nodeUid,
                          NodeHostMappingRequest input) {
    String actor = actor();
    UUID hostId = input == null ? null : uuid(input.hostId());
    if (hostId == null) throw fail(422, "VALIDATION_FAILED", "hostId 必须为 UUID");
    try {
      return noStore(Response.ok(clusters.mapNode(actor, clientIp(), uuid(clusterValue),
          nodeUid, hostId))).build();
    } catch (IllegalArgumentException invalid) {
      throw fail(422, "VALIDATION_FAILED", invalid.getMessage());
    } catch (KubernetesClusterRegistryService.ClusterNodeNotFoundException missing) {
      throw fail(404, "KUBERNETES_NODE_NOT_FOUND", "集群节点未登记或已被移除");
    } catch (KubernetesClusterRegistryService.HostNotFoundException missing) {
      throw fail(404, "NOT_FOUND", "主机资产不存在");
    } catch (KubernetesClusterRegistryService.HostAlreadyMappedException conflict) {
      throw fail(409, "KUBERNETES_NODE_MAPPING_CONFLICT", "此主机已映射到其他 Kubernetes 节点");
    }
  }

  @DELETE
  @Path("{clusterId}/nodes/{nodeUid}/host")
  public Response unmapHost(@PathParam("clusterId") String clusterValue,
                            @PathParam("nodeUid") String nodeUid) {
    String actor = actor();
    try {
      clusters.unmapNode(actor, clientIp(), uuid(clusterValue), nodeUid);
      return Response.noContent().build();
    } catch (IllegalArgumentException invalid) {
      throw fail(422, "VALIDATION_FAILED", invalid.getMessage());
    } catch (KubernetesClusterRegistryService.ClusterNodeNotFoundException missing) {
      throw fail(404, "KUBERNETES_NODE_NOT_FOUND", "集群节点未登记或已被移除");
    } catch (KubernetesClusterRegistryService.HostOwnershipManagedByKubernetesException owned) {
      throw fail(409, "KUBERNETES_CONTAINER_OWNED",
          "该主机仍映射到 Kubernetes Node，需先从集群移除节点或完成所有权转移");
    }
  }

  private String actor() {
    @SuppressWarnings("unchecked")
    var session = (Map<String, Object>)request.getProperty("session");
    if (session == null || session.get("user_id") == null) {
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    }
    return String.valueOf(session.get("user_id"));
  }

  private UUID uuid(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException invalid) {
      throw fail(400, "VALIDATION_FAILED", "集群与主机 ID 必须为 UUID");
    }
  }

  private String clientIp() {
    return http.remoteAddress() == null ? null : http.remoteAddress().host();
  }

  private static Response.ResponseBuilder noStore(Response.ResponseBuilder response) {
    return response.header("Cache-Control", "no-store");
  }
}
