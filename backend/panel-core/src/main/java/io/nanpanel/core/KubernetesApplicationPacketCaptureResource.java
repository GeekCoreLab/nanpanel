package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthResource.number;

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

@Path("/api/v1/kubernetes/applications/{applicationId}/network-topology/captures")
@Produces(MediaType.APPLICATION_JSON)
public class KubernetesApplicationPacketCaptureResource {
  @Inject KubernetesApplicationPacketCaptureService captures;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  @GET
  public Response list(@PathParam("applicationId") String applicationValue,
      @QueryParam("limit") Integer requestedLimit) {
    actor();
    return Response.ok(captures.list(uuid(applicationValue, "applicationId"),
        requestedLimit == null ? 10 : requestedLimit))
        .header("Cache-Control", "no-store").build();
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response start(@PathParam("applicationId") String applicationValue,
      KubernetesApplicationPacketCaptureService.CaptureRequest input) {
    Map<String, Object> session = actor();
    recent(session);
    if (input == null || input.linkId() == null) {
      throw fail(422, "VALIDATION_FAILED", "抓包请求必须指定一条已保存的虚拟网线");
    }
    var result = captures.start(user(session), remoteAddress(),
        uuid(applicationValue, "applicationId"), input.linkId());
    return Response.accepted(result).header("Cache-Control", "no-store").build();
  }

  @GET
  @Path("{captureId}")
  public Response get(@PathParam("applicationId") String applicationValue,
      @PathParam("captureId") String captureValue) {
    actor();
    return Response.ok(captures.get(uuid(applicationValue, "applicationId"),
        uuid(captureValue, "captureId"))).header("Cache-Control", "no-store").build();
  }

  @GET
  @Path("{captureId}/download")
  @Produces(MediaType.APPLICATION_OCTET_STREAM)
  public Response download(@PathParam("applicationId") String applicationValue,
      @PathParam("captureId") String captureValue) {
    actor();
    var artifact = captures.download(uuid(applicationValue, "applicationId"),
        uuid(captureValue, "captureId"));
    return Response.ok(artifact.bytes(), MediaType.APPLICATION_OCTET_STREAM)
        .header("Content-Disposition", "attachment; filename=\"" + artifact.metadata().captureId()
            + ".pcapng\"")
        .header("X-Capture-Format", "PCAPNG")
        .header("Cache-Control", "no-store")
        .build();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> actor() {
    Map<String, Object> value = (Map<String, Object>)request.getProperty("session");
    if (value == null || value.get("user_id") == null) {
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    }
    return value;
  }

  private void recent(Map<String, Object> session) {
    if (System.currentTimeMillis() - number(session, "authenticated_at") <= 300000) return;
    throw fail(403, "REAUTHENTICATION_REQUIRED", "请重新验证密码后再发起抓包");
  }

  private String user(Map<String, Object> session) {
    return String.valueOf(session.get("user_id"));
  }

  private String remoteAddress() {
    return http == null || http.remoteAddress() == null ? null : http.remoteAddress().host();
  }

  private UUID uuid(String value, String field) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw fail(404, "NOT_FOUND", field + " 格式无效");
    }
  }
}
