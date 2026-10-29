package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only App Store catalog. Deploy is orchestrated separately through the
 * typed Helper container operations; this resource only serves the curated
 * in-repo manifest so the console can render deployability against hosts.
 */
@Path("/api/v1/app-store/catalog")
@Produces(MediaType.APPLICATION_JSON)
public class AppStoreResource {

  @Context jakarta.ws.rs.container.ContainerRequestContext request;

  private Map<String, Object> session() {
    @SuppressWarnings("unchecked")
    Map<String, Object> value = (Map<String, Object>)request.getProperty("session");
    if (value == null || value.get("user_id") == null) {
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    }
    return value;
  }

  @GET
  public Map<String, Object> list() {
    session();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("version", AppStoreCatalog.VERSION);
    body.put("categories", AppStoreCatalog.categories());
    body.put("items", AppStoreCatalog.apps());
    return body;
  }

  @GET
  @Path("{appId}")
  public AppStoreCatalog.AppStoreApp detail(@PathParam("appId") String appId) {
    session();
    return AppStoreCatalog.find(appId)
        .orElseThrow(() -> fail(404, "APP_NOT_FOUND", "应用商店中不存在此应用"));
  }
}