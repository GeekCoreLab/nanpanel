package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthResource.number;
import static io.nanpanel.core.AuthStore.audit;

import jakarta.inject.Inject;
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

@Path("/api/v1/kubernetes/applications/{applicationId}/network-topology/traffic-runtime")
@Produces(MediaType.APPLICATION_JSON)
public class KubernetesApplicationTrafficRuntimeResource {
  @Inject KubernetesApplicationTrafficRuntimeService runtime;
  @Inject AuthStore store;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  @GET
  public Response get(@PathParam("applicationId") String applicationValue) {
    actor();
    return Response.ok(runtime.get(uuid(applicationValue)))
        .header("Cache-Control", "no-store").build();
  }

  @POST
  @Path("apply")
  public Response apply(@PathParam("applicationId") String applicationValue) {
    Map<String, Object> actor = actor();
    recent(actor, "APPLY");
    return Response.ok(runtime.apply(user(actor), remoteAddress(), uuid(applicationValue)))
        .header("Cache-Control", "no-store").build();
  }

  @POST
  @Path("verify")
  public Response verify(@PathParam("applicationId") String applicationValue) {
    Map<String, Object> actor = actor();
    recent(actor, "VERIFY");
    return Response.ok(runtime.verify(user(actor), remoteAddress(), uuid(applicationValue)))
        .header("Cache-Control", "no-store").build();
  }

  @POST
  @Path("compensate")
  public Response compensate(@PathParam("applicationId") String applicationValue) {
    Map<String, Object> actor = actor();
    recent(actor, "COMPENSATE");
    return Response.ok(runtime.compensate(user(actor), remoteAddress(), uuid(applicationValue)))
        .header("Cache-Control", "no-store").build();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> actor() {
    Map<String, Object> value = (Map<String, Object>)request.getProperty("session");
    if (value == null || value.get("user_id") == null) {
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    }
    return value;
  }

  private void recent(Map<String, Object> value, String operation) {
    if (System.currentTimeMillis() - number(value, "authenticated_at") <= 300000) return;
    String actor = user(value);
    store.transaction(connection -> {
      audit(connection, actor, "security.reauthentication.required", "application-traffic-runtime",
          null, "DENIED", remoteAddress(), "Service 流量路由 " + operation + " 需要重新验证身份");
      return null;
    });
    throw fail(403, "REAUTHENTICATION_REQUIRED", "请重新验证密码后再操作 Service 流量路由");
  }

  private String user(Map<String, Object> value) {
    return String.valueOf(value.get("user_id"));
  }

  private String remoteAddress() {
    return http == null || http.remoteAddress() == null ? null : http.remoteAddress().host();
  }

  private UUID uuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw fail(404, "KUBERNETES_APPLICATION_NOT_FOUND", "Kubernetes 应用不存在");
    }
  }
}
