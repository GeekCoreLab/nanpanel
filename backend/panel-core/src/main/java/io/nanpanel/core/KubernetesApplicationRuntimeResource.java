package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Path("/api/v1/kubernetes/applications")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class KubernetesApplicationRuntimeResource {
  @Inject KubernetesApplicationService applications;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  // Retain the optional reason field for older clients; audits use server-generated context.
  public record ExecRequest(String container, String mode, Integer columns, Integer rows, String reason) {}

  @GET
  @Path("{applicationId}/deployments/{deploymentName}/runtime")
  public Response runtime(@PathParam("applicationId") String appValue,
      @PathParam("deploymentName") String deploymentName) {
    actor();
    return execute(() -> Response.ok(applications.runtime(uuid(appValue), deploymentName)).build());
  }

  @GET
  @Path("{applicationId}/deployments/{deploymentName}/pods/{podUid}/events")
  public Response events(@PathParam("applicationId") String appValue,
      @PathParam("deploymentName") String deploymentName, @PathParam("podUid") String podUid) {
    actor();
    return execute(() -> Response.ok(applications.runtimeEvents(uuid(appValue), deploymentName,
        podUid)).build());
  }

  @GET
  @Path("{applicationId}/deployments/{deploymentName}/pods/{podUid}/logs")
  public Response logs(@PathParam("applicationId") String appValue,
      @PathParam("deploymentName") String deploymentName, @PathParam("podUid") String podUid,
      @QueryParam("container") String container, @QueryParam("tailLines") Integer tailLines) {
    actor();
    return execute(() -> Response.ok(applications.runtimeLog(uuid(appValue), deploymentName,
        podUid, container, tailLines)).build());
  }

  @POST
  @Path("{applicationId}/deployments/{deploymentName}/pods/{podUid}/exec-tickets")
  public Response execTicket(@PathParam("applicationId") String appValue,
      @PathParam("deploymentName") String deploymentName, @PathParam("podUid") String podUid,
      ExecRequest input) {
    Map<String, Object> session = authenticated();
    String container = input == null ? null : input.container();
    String requestedMode = input == null ? null : input.mode();
    KubernetesApplicationService.ExecMode mode;
    try {
      mode = requestedMode == null || requestedMode.isBlank()
          ? KubernetesApplicationService.ExecMode.NEW_SHELL
          : KubernetesApplicationService.ExecMode.valueOf(requestedMode);
    } catch (IllegalArgumentException invalidMode) {
      throw fail(422, "VALIDATION_FAILED", "Exec 模式必须为 ATTACH 或 NEW_SHELL");
    }
    if (!"reviews".equals(container)) {
      throw fail(422, "VALIDATION_FAILED", "Exec 目标固定为 Pod 的 reviews 容器");
    }
    int columns = input == null || input.columns() == null ? 120 : input.columns();
    int rows = input == null || input.rows() == null ? 36 : input.rows();
    if (columns < 20 || columns > 500 || rows < 5 || rows > 200) {
      throw fail(422, "VALIDATION_FAILED", "Exec 终端尺寸无效");
    }
    return execute(() -> {
      var target = applications.validateExecTarget(uuid(appValue), deploymentName, podUid,
          container, mode);
      var ticket = applications.issueExecTicket(target, mode,
          String.valueOf(session.get("user_id")), clientIp());
      return Response.status(Response.Status.CREATED).entity(Map.of(
          "sessionId", ticket.sessionId().toString(), "ticket", ticket.ticket(),
          "expiresAt", ticket.expiresAt().toString(), "websocketPath", ticket.websocketPath(),
          "columns", columns, "rows", rows)).header("Cache-Control", "no-store").build();
    });
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
      throw fail(403, "KUBERNETES_RBAC_DENIED", "集群凭据没有访问应用运行时所需的 Namespace 权限");
    } catch (KubernetesClusterRegistryService.ClusterApiUnavailableException unavailable) {
      throw fail(502, "KUBERNETES_CLUSTER_UNAVAILABLE", "无法连接 Kubernetes API");
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> authenticated() {
    var session = (Map<String, Object>) request.getProperty("session");
    if (session == null || session.get("user_id") == null) {
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    }
    return session;
  }

  private String actor() {
    return String.valueOf(authenticated().get("user_id"));
  }

  private String clientIp() {
    return http == null || http.remoteAddress() == null ? null : http.remoteAddress().host();
  }

  private UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (RuntimeException invalid) {
      throw fail(404, "KUBERNETES_APPLICATION_NOT_FOUND", "Kubernetes 应用不存在");
    }
  }
}
