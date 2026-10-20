package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/api/v1/kubernetes")
@Produces(MediaType.APPLICATION_JSON)
public class KubernetesServiceInventoryResource {
  @Inject KubernetesServiceInventoryService inventory;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;

  @GET
  @Path("service-inventory")
  public Response serviceInventory() {
    authenticated();
    requireAvailable();
    try {
      return Response.ok(inventory.latest())
          .header("Cache-Control", "no-store")
          .build();
    } catch (RuntimeException failure) {
      throw fail(500, "KUBERNETES_INVENTORY_UNAVAILABLE",
          "暂时无法读取已保存的 Kubernetes 服务清单");
    }
  }

  @POST
  @Path("service-inventory/refresh")
  public Response refreshServiceInventory() {
    authenticated();
    requireAvailable();
    try {
      return Response.ok(inventory.refresh())
          .header("Cache-Control", "no-store")
          .build();
    } catch (RuntimeException failure) {
      throw fail(502, "KUBERNETES_DISCOVERY_FAILED",
          "暂时无法刷新 Kubernetes 服务清单");
    }
  }

  @GET
  @Path("service-probes")
  public Response serviceProbes() {
    authenticated();
    requireAvailable();
    try {
      return Response.ok(inventory.latestProbes())
          .header("Cache-Control", "no-store")
          .build();
    } catch (RuntimeException failure) {
      throw fail(500, "KUBERNETES_PROBES_UNAVAILABLE",
          "暂时无法读取 Kubernetes 服务探测结果");
    }
  }

  private void requireAvailable() {
    if (!inventory.isEnabled()) {
      throw fail(503, "KUBERNETES_DISCOVERY_DISABLED",
          "Kubernetes 服务发现尚未启用");
    }
    if (!inventory.isConfigured()) {
      throw fail(503, "KUBERNETES_DISCOVERY_MISCONFIGURED",
          "Kubernetes 服务发现缺少集群或 namespace 配置");
    }
  }

  @SuppressWarnings("unchecked")
  private void authenticated() {
    var session = (Map<String, Object>)request.getProperty("session");
    if (session == null || session.get("user_id") == null) {
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    }
  }
}
