package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;

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
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Path("/api/v1/kubernetes/applications")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class KubernetesApplicationResource {
  @Inject KubernetesApplicationService applications;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  @GET
  public Response list() {
    actor();
    return execute(() -> Response.ok(applications.list()).build());
  }

  @POST
  @Path("bookinfo")
  public Response createBookinfo(KubernetesApplicationService.CreateBookinfoRequest input) {
    String actor = actor();
    return execute(() -> Response.status(Response.Status.CREATED).entity(
        applications.createBookinfo(actor, clientIp(), input)).build());
  }

  @GET
  @Path("{applicationId}")
  public Response get(@PathParam("applicationId") String applicationValue) {
    actor();
    return execute(() -> Response.ok(applications.get(uuid(applicationValue))).build());
  }

  @GET
  @Path("{applicationId}/versions")
  public Response versions(@PathParam("applicationId") String applicationValue) {
    actor();
    return execute(() -> Response.ok(applications.versions(uuid(applicationValue))).build());
  }

  @POST
  @Path("{applicationId}/versions")
  public Response createDraft(@PathParam("applicationId") String applicationValue,
      KubernetesApplicationService.DraftRequest input) {
    String actor = actor();
    return execute(() -> Response.status(Response.Status.CREATED).entity(
        applications.createDraft(actor, clientIp(), uuid(applicationValue), input)).build());
  }

  @POST
  @Path("{applicationId}/versions/{version}/deploy")
  public Response deployVersion(@PathParam("applicationId") String applicationValue,
      @PathParam("version") String versionValue) {
    String actor = actor();
    int version;
    try {
      version = Integer.parseInt(versionValue);
    } catch (NumberFormatException invalid) {
      throw fail(404, "KUBERNETES_APPLICATION_VERSION_NOT_FOUND", "应用版本不存在");
    }
    return execute(() -> Response.ok(applications.deployVersion(actor, clientIp(),
        uuid(applicationValue), version)).build());
  }

  private Response execute(Supplier<Response> operation) {
    try {
      return Response.fromResponse(operation.get()).header("Cache-Control", "no-store").build();
    } catch (KubernetesApplicationService.ApplicationException failure) {
      throw fail(failure.status(), failure.code(), failure.getMessage());
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw fail(404, "KUBERNETES_CLUSTER_NOT_FOUND", "集群未登记");
    } catch (KubernetesClusterRegistryService.ClusterCredentialRejectedException rejected) {
      throw fail(502, "KUBERNETES_CREDENTIALS_REJECTED", "已保存的集群凭据被 Kubernetes API 拒绝");
    } catch (KubernetesClusterRegistryService.ClusterPermissionDeniedException denied) {
      throw fail(403, "KUBERNETES_RBAC_DENIED", "集群凭据没有应用操作所需的 Namespace 权限");
    } catch (KubernetesClusterRegistryService.ClusterApiUnavailableException unavailable) {
      throw fail(502, "KUBERNETES_CLUSTER_UNAVAILABLE", "无法连接 Kubernetes API");
    }
  }

  @SuppressWarnings("unchecked")
  private String actor() {
    var session = (Map<String, Object>) request.getProperty("session");
    if (session == null || session.get("user_id") == null) {
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    }
    return String.valueOf(session.get("user_id"));
  }

  private String clientIp() {
    return http == null || http.remoteAddress() == null ? null : http.remoteAddress().host();
  }

  private UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw fail(404, "KUBERNETES_APPLICATION_NOT_FOUND", "Kubernetes 应用不存在");
    }
  }
}
