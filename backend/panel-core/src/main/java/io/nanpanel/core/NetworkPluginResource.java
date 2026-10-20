package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthResource.number;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;

import io.nanpanel.plugin.runtime.ProviderRuntime.PluginStatus;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.container.ContainerRequestContext;
import java.util.List;
import java.util.Map;

@Path("/api/v1/plugins")
@Produces(MediaType.APPLICATION_JSON)
public class NetworkPluginResource {
  @Inject NetworkProviderRegistry registry;
  @Inject AuthStore store;
  @Inject io.vertx.core.http.HttpServerRequest http;
  @Context ContainerRequestContext request;

  public record EnabledRequest(boolean enabled) {}

  private Map<String, Object> session() {
    @SuppressWarnings("unchecked")
    Map<String, Object> value = (Map<String, Object>)request.getProperty("session");
    if (value == null || value.get("user_id") == null) {
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    }
    return value;
  }

  @GET
  public List<PluginStatus> list() {
    session();
    return registry.pluginStatuses();
  }

  @PUT
  @Path("{pluginId}/enabled")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response setEnabled(@PathParam("pluginId") String pluginId, EnabledRequest input) {
    Map<String, Object> actor = session();
    if (input == null || pluginId == null || !pluginId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) {
      throw fail(400, "VALIDATION_FAILED", "Plugin ID 或状态无效");
    }
    if (System.currentTimeMillis() - number(actor, "authenticated_at") > 300000) {
      throw fail(403, "REAUTHENTICATION_REQUIRED", "停用或启用 Plugin 需要重新验证身份");
    }
    PluginStatus plugin = registry.pluginStatuses().stream()
        .filter(value -> value.id().equals(pluginId)).findFirst()
        .orElseThrow(() -> fail(404, "PLUGIN_UNAVAILABLE", "Plugin 未安装或未加载"));
    if (!input.enabled() && "STARTED".equals(plugin.state()) && hasOpenProviderOperation(plugin.providerIds())) {
      throw fail(409, "PROVIDER_OPERATIONS_OPEN", "请先完成该 Plugin 的 Provider 操作，再停用 Plugin");
    }
    boolean changed = input.enabled() ? registry.enablePlugin(pluginId) : registry.disablePlugin(pluginId);
    if (!changed) {
      throw fail(409, "PLUGIN_LIFECYCLE_FAILED", "Plugin 生命周期转换失败");
    }
    PluginStatus updated = registry.pluginStatuses().stream()
        .filter(value -> value.id().equals(pluginId)).findFirst()
        .orElseThrow(() -> fail(409, "PLUGIN_LIFECYCLE_FAILED", "Plugin 状态转换后无法读取"));
    String clientIp = http.remoteAddress() == null ? null : http.remoteAddress().host();
    store.transaction(c -> {
      audit(c, String.valueOf(actor.get("user_id")),
          input.enabled() ? "plugin.enabled" : "plugin.disabled", "plugin", pluginId,
          "SUCCEEDED", clientIp, "Plugin 生命周期状态变更为 " + updated.state());
      return null;
    });
    return Response.ok(updated).build();
  }

  private boolean hasOpenProviderOperation(List<String> providerIds) {
    if (providerIds.isEmpty()) {
      return false;
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(providerIds.size(), "?"));
    List<Object> args = providerIds.stream().map(value -> (Object)value).toList();
    return store.transaction(c -> {
      Map<String, Object> row = query(c,
          "SELECT COUNT(*) AS open_count FROM network_provider_operation WHERE provider_id IN ("
              + placeholders + ") AND state NOT IN ('VERIFIED','COMPENSATED','FAILED')",
          args.toArray()).getFirst();
      return ((Number)row.get("open_count")).longValue() > 0;
    });
  }
}
