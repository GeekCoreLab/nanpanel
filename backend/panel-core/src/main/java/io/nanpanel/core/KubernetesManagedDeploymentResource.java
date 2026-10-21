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

@Path("/api/v1/kubernetes/clusters/{clusterId}/namespaces/{namespace}/deployments")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class KubernetesManagedDeploymentResource {
  @Inject KubernetesManagedDeploymentService deployments;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  @GET
  public Response list(@PathParam("clusterId") String clusterValue,
                       @PathParam("namespace") String namespace) {
    actor();
    try {
      return noStore(Response.ok(deployments.list(uuid(clusterValue), namespace))).build();
    } catch (IllegalArgumentException invalid) {
      throw fail(422, "KUBERNETES_RESOURCE_INVALID", invalid.getMessage());
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw fail(404, "KUBERNETES_CLUSTER_NOT_FOUND", "集群未登记");
    } catch (KubernetesManagedDeploymentService.ResourceLimitException limit) {
      throw fail(422, "KUBERNETES_RESOURCE_LIMIT", "此 Namespace 的托管 Deployment 数量超过上限");
    } catch (KubernetesManagedDeploymentService.PermissionDeniedException denied) {
      throw fail(403, "KUBERNETES_RBAC_DENIED", "集群凭据没有读取 Deployment 的权限");
    } catch (KubernetesManagedDeploymentService.CredentialRejectedException rejected) {
      throw fail(502, "KUBERNETES_CREDENTIALS_REJECTED", "已保存的集群凭据被 Kubernetes API 拒绝");
    } catch (KubernetesManagedDeploymentService.ClusterApiUnavailableException unavailable) {
      throw fail(502, "KUBERNETES_CLUSTER_UNAVAILABLE", "无法读取该集群的 Deployment 清单");
    }
  }

  @POST
  public Response create(@PathParam("clusterId") String clusterValue,
                         @PathParam("namespace") String namespace,
                         KubernetesManagedDeploymentService.CreateRequest input) {
    String actor = actor();
    try {
      var created = deployments.create(actor, clientIp(), uuid(clusterValue), namespace, input);
      return noStore(Response.status(Response.Status.CREATED).entity(created)).build();
    } catch (IllegalArgumentException invalid) {
      throw fail(422, "KUBERNETES_RESOURCE_INVALID", invalid.getMessage());
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw fail(404, "KUBERNETES_CLUSTER_NOT_FOUND", "集群未登记");
    } catch (KubernetesManagedDeploymentService.PermissionDeniedException denied) {
      throw fail(403, "KUBERNETES_RBAC_DENIED", "集群凭据没有在此 Namespace 创建 Deployment 的权限");
    } catch (KubernetesManagedDeploymentService.CredentialRejectedException rejected) {
      throw fail(502, "KUBERNETES_CREDENTIALS_REJECTED", "已保存的集群凭据被 Kubernetes API 拒绝");
    } catch (KubernetesManagedDeploymentService.ResourceAlreadyManagedException
             | KubernetesManagedDeploymentService.ResourceConflictException conflict) {
      throw fail(409, "KUBERNETES_RESOURCE_CONFLICT", "Deployment 名称已被托管记录或集群资源占用");
    } catch (KubernetesManagedDeploymentService.RegistryUnavailableException unavailable) {
      throw fail(503, "KUBERNETES_REGISTRY_UNAVAILABLE", "集群已响应，但控制面无法保存所有权记录");
    } catch (KubernetesManagedDeploymentService.ClusterApiUnavailableException unavailable) {
      throw fail(502, "KUBERNETES_CLUSTER_UNAVAILABLE", "无法在该 Namespace 创建 Deployment");
    }
  }

  @PUT
  @Path("{name}/scale")
  public Response scale(@PathParam("clusterId") String clusterValue,
                        @PathParam("namespace") String namespace,
                        @PathParam("name") String name,
                        KubernetesManagedDeploymentService.ScaleRequest input) {
    String actor = actor();
    try {
      return noStore(Response.ok(deployments.scale(actor, clientIp(), uuid(clusterValue),
          namespace, name, input))).build();
    } catch (IllegalArgumentException invalid) {
      throw fail(422, "KUBERNETES_RESOURCE_INVALID", invalid.getMessage());
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw fail(404, "KUBERNETES_CLUSTER_NOT_FOUND", "集群未登记");
    } catch (KubernetesManagedDeploymentService.ResourceNotManagedException missing) {
      throw fail(404, "KUBERNETES_RESOURCE_NOT_MANAGED", "Deployment 不属于 NaNPanel 管理");
    } catch (KubernetesManagedDeploymentService.OwnershipConflictException conflict) {
      throw fail(409, "KUBERNETES_RESOURCE_OWNERSHIP_CONFLICT", "Deployment UID 或所有权标记已变化");
    } catch (KubernetesManagedDeploymentService.ResourceConflictException conflict) {
      throw fail(409, "KUBERNETES_RESOURCE_CONFLICT", "Deployment 已被并发修改，请重新读取后重试");
    } catch (KubernetesManagedDeploymentService.PermissionDeniedException denied) {
      throw fail(403, "KUBERNETES_RBAC_DENIED", "集群凭据没有更新 Deployment scale 子资源的权限");
    } catch (KubernetesManagedDeploymentService.CredentialRejectedException rejected) {
      throw fail(502, "KUBERNETES_CREDENTIALS_REJECTED", "已保存的集群凭据被 Kubernetes API 拒绝");
    } catch (KubernetesManagedDeploymentService.ClusterApiUnavailableException unavailable) {
      throw fail(502, "KUBERNETES_CLUSTER_UNAVAILABLE", "无法更新该 Deployment 的副本数");
    }
  }

  @DELETE
  @Path("{name}")
  public Response delete(@PathParam("clusterId") String clusterValue,
                         @PathParam("namespace") String namespace,
                         @PathParam("name") String name) {
    String actor = actor();
    try {
      deployments.delete(actor, clientIp(), uuid(clusterValue), namespace, name);
      return Response.noContent().header("Cache-Control", "no-store").build();
    } catch (IllegalArgumentException invalid) {
      throw fail(422, "KUBERNETES_RESOURCE_INVALID", invalid.getMessage());
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw fail(404, "KUBERNETES_CLUSTER_NOT_FOUND", "集群未登记");
    } catch (KubernetesManagedDeploymentService.ResourceNotManagedException missing) {
      throw fail(404, "KUBERNETES_RESOURCE_NOT_MANAGED", "Deployment 不属于 NaNPanel 管理");
    } catch (KubernetesManagedDeploymentService.OwnershipConflictException conflict) {
      throw fail(409, "KUBERNETES_RESOURCE_OWNERSHIP_CONFLICT", "Deployment UID 或所有权标记已变化，拒绝删除");
    } catch (KubernetesManagedDeploymentService.ResourceConflictException conflict) {
      throw fail(409, "KUBERNETES_RESOURCE_CONFLICT", "Deployment 已被并发修改，请重新读取后重试");
    } catch (KubernetesManagedDeploymentService.PermissionDeniedException denied) {
      throw fail(403, "KUBERNETES_RBAC_DENIED", "集群凭据没有删除 Deployment 的权限");
    } catch (KubernetesManagedDeploymentService.CredentialRejectedException rejected) {
      throw fail(502, "KUBERNETES_CREDENTIALS_REJECTED", "已保存的集群凭据被 Kubernetes API 拒绝");
    } catch (KubernetesManagedDeploymentService.ClusterApiUnavailableException unavailable) {
      throw fail(502, "KUBERNETES_CLUSTER_UNAVAILABLE", "无法删除该 Deployment");
    }
  }

  @SuppressWarnings("unchecked")
  private String actor() {
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
      throw fail(400, "VALIDATION_FAILED", "clusterId 必须为 UUID");
    }
  }

  private String clientIp() {
    return http.remoteAddress() == null ? null : http.remoteAddress().host();
  }

  private static Response.ResponseBuilder noStore(Response.ResponseBuilder response) {
    return response.header("Cache-Control", "no-store");
  }
}
