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

@Path("/api/v1/kubernetes/clusters/{clusterId}/namespaces/{namespace}/application-releases")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class KubernetesApplicationReleaseResource {
  @Inject KubernetesApplicationReleaseService releases;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  public record CreateRequest(String name, String stableImage, String image, Integer replicas, Integer containerPort,
      String gatewayName, String hostname, String prometheusService, java.math.BigDecimal maxErrorRate,
      String reason) {}

  public record ReleaseList(java.util.List<KubernetesApplicationReleaseService.ReleaseView> items) {}

  @GET
  public Response list(@PathParam("clusterId") String clusterValue,
                       @PathParam("namespace") String namespace) {
    String actor = actor();
    return execute(() -> Response.ok(new ReleaseList(
        releases.list(actor, uuid(clusterValue), namespace))).build());
  }

  @POST
  public Response create(@PathParam("clusterId") String clusterValue,
                         @PathParam("namespace") String namespace, CreateRequest input) {
    String actor = actor();
    return execute(() -> Response.status(Response.Status.CREATED).entity(releases.create(actor,
        clientIp(), uuid(clusterValue), namespace, input == null ? null
            : new KubernetesApplicationReleaseService.CreateRequest(input.name(), input.stableImage(), input.image(),
                input.replicas(), input.containerPort(), input.gatewayName(), input.hostname(),
                input.prometheusService(), input.maxErrorRate(), input.reason()))).build());
  }

  @GET
  @Path("{releaseId}")
  public Response get(@PathParam("releaseId") String releaseValue) {
    String actor = actor();
    return execute(() -> Response.ok(releases.get(uuid(releaseValue), actor)).build());
  }

  @POST
  @Path("{releaseId}/start")
  public Response start(@PathParam("releaseId") String releaseValue) {
    String actor = actor();
    return action(() -> releases.start(actor, clientIp(), uuid(releaseValue)));
  }

  @POST
  @Path("{releaseId}/pause")
  public Response pause(@PathParam("releaseId") String releaseValue) {
    String actor = actor();
    return action(() -> releases.pause(actor, clientIp(), uuid(releaseValue)));
  }

  @POST
  @Path("{releaseId}/resume")
  public Response resume(@PathParam("releaseId") String releaseValue) {
    String actor = actor();
    return action(() -> releases.resume(actor, clientIp(), uuid(releaseValue)));
  }

  @POST
  @Path("{releaseId}/promote")
  public Response promote(@PathParam("releaseId") String releaseValue) {
    String actor = actor();
    return action(() -> releases.promote(actor, clientIp(), uuid(releaseValue)));
  }

  @POST
  @Path("{releaseId}/rollback")
  public Response rollback(@PathParam("releaseId") String releaseValue) {
    String actor = actor();
    return action(() -> releases.rollback(actor, clientIp(), uuid(releaseValue)));
  }

  private Response action(Supplier<KubernetesApplicationReleaseService.ReleaseView> operation) {
    return execute(() -> Response.ok(operation.get()).build());
  }

  private Response execute(Supplier<Response> operation) {
    try {
      Response response = operation.get();
      return Response.fromResponse(response).header("Cache-Control", "no-store").build();
    } catch (KubernetesApplicationReleaseService.ReleaseException failure) {
      throw fail(failure.status(), failure.code(), failure.getMessage());
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw fail(404, "KUBERNETES_CLUSTER_NOT_FOUND", "集群未登记");
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

  private String clientIp() {
    return http == null || http.remoteAddress() == null ? null : http.remoteAddress().host();
  }

  private UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw fail(404, "APPLICATION_RELEASE_NOT_FOUND", "应用发布计划不存在");
    }
  }
}
