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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Path("/api/v1/host-application-releases")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class HostApplicationReleaseResource {
  @Inject HostApplicationReleaseService releases;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  public record TargetRequest(String hostId, String listenAddress, Long expectedGeneration) {}

  public record CreateRequest(String name, String stableImage, String image, Integer containerPort,
      String serverName, Integer listenPort, String healthPath, Integer batchSize,
      List<TargetRequest> targets, String reason) {}

  public record PlanList(List<HostApplicationReleaseService.PlanView> items) {}

  @GET
  public Response list() {
    String actor = actor();
    return noStore(Response.ok(new PlanList(releases.list(actor))).build());
  }

  @POST
  public Response create(CreateRequest input) {
    String actor = actor();
    return execute(() -> {
      List<HostApplicationReleaseService.TargetRequest> targets = input == null || input.targets() == null
          ? null : input.targets().stream().map(target -> target == null ? null
              : new HostApplicationReleaseService.TargetRequest(parseUuid(target.hostId()),
                  target.listenAddress(), target.expectedGeneration())).toList();
      var plan = releases.create(actor, clientIp(), input == null ? null
          : new HostApplicationReleaseService.CreateRequest(input.name(), input.stableImage(),
              input.image(), input.containerPort(), input.serverName(), input.listenPort(),
              input.healthPath(), input.batchSize(), targets, input.reason()));
      return Response.status(Response.Status.CREATED).entity(plan).build();
    });
  }

  @GET
  @Path("{releaseId}")
  public Response get(@PathParam("releaseId") String releaseValue) {
    String actor = actor();
    return execute(() -> Response.ok(releases.get(parseUuid(releaseValue), actor)).build());
  }

  @POST
  @Path("{releaseId}/start")
  public Response start(@PathParam("releaseId") String releaseValue) {
    String actor = actor();
    return action(() -> releases.start(parseUuid(releaseValue), actor, clientIp()));
  }

  @POST
  @Path("{releaseId}/pause")
  public Response pause(@PathParam("releaseId") String releaseValue) {
    String actor = actor();
    return action(() -> releases.pause(parseUuid(releaseValue), actor, clientIp()));
  }

  @POST
  @Path("{releaseId}/resume")
  public Response resume(@PathParam("releaseId") String releaseValue) {
    String actor = actor();
    return action(() -> releases.resume(parseUuid(releaseValue), actor, clientIp()));
  }

  @POST
  @Path("{releaseId}/reconcile")
  public Response reconcile(@PathParam("releaseId") String releaseValue) {
    String actor = actor();
    return action(() -> releases.reconcile(parseUuid(releaseValue), actor, clientIp()));
  }

  @POST
  @Path("{releaseId}/rollback")
  public Response rollback(@PathParam("releaseId") String releaseValue) {
    String actor = actor();
    return action(() -> releases.rollback(parseUuid(releaseValue), actor, clientIp()));
  }

  private Response action(Supplier<HostApplicationReleaseService.PlanView> operation) {
    return execute(() -> Response.ok(operation.get()).build());
  }

  private Response execute(Supplier<Response> operation) {
    try {
      return noStore(operation.get());
    } catch (HostApplicationReleaseService.ReleaseException failure) {
      throw fail(failure.status(), failure.code(), failure.getMessage());
    }
  }

  private Response noStore(Response response) {
    return Response.fromResponse(response).header("Cache-Control", "no-store").build();
  }

  @SuppressWarnings("unchecked")
  private String actor() {
    var session = (Map<String, Object>)request.getProperty("session");
    if (session == null || session.get("user_id") == null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    return String.valueOf(session.get("user_id"));
  }

  private String clientIp() {
    return http == null || http.remoteAddress() == null ? null : http.remoteAddress().host();
  }

  private UUID parseUuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw fail(422, "VALIDATION_FAILED", "主机发布目标 UUID 无效");
    }
  }
}
