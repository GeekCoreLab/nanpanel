package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Database instances NaNPanel already runs: a filtered view of the deployed-container
 * list, keeping only images the catalogue recognises as a database engine and adding a
 * connection address. Read-only — create/remove go through the existing app store
 * deploy and {@code container.remove} paths, and nothing here opens a SQL connection.
 */
@Path("/api/v1/databases")
@Produces(MediaType.APPLICATION_JSON)
public class DatabaseResource {

  @Inject AppStoreDeploymentResource deployments;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;

  @SuppressWarnings("unchecked")
  private void session() {
    Map<String, Object> value = (Map<String, Object>) request.getProperty("session");
    if (value == null || value.get("user_id") == null) {
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    }
  }

  @GET
  @SuppressWarnings("unchecked")
  public Map<String, Object> instances() {
    session();
    Map<String, Object> deployed = deployments.list();
    List<Map<String, Object>> containers = deployed.get("items") instanceof List<?> list
        ? (List<Map<String, Object>>) list : List.of();
    var items = new ArrayList<Map<String, Object>>(containers.size());
    for (Map<String, Object> container : containers) {
      String image = container.get("image") == null ? null : String.valueOf(container.get("image"));
      var engine = DatabaseCatalog.detect(image);
      if (engine.isEmpty()) continue;
      Integer hostPort = container.get("hostPort") instanceof Number number
          ? number.intValue() : null;
      String hostname = container.get("hostname") == null ? ""
          : String.valueOf(container.get("hostname"));
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("hostId", container.get("hostId"));
      item.put("hostname", hostname);
      item.put("containerName", container.get("containerName"));
      item.put("image", image);
      item.put("engine", DatabaseCatalog.describe(engine.get()));
      item.put("hostPort", hostPort);
      item.put("containerPort", container.get("containerPort"));
      item.put("connectionUrl", hostPort == null ? null
          : DatabaseCatalog.connectionUrl(engine.get(), hostname, hostPort));
      item.put("note", hostPort == null ? "容器未发布端口，无法从主机外部连接" : "");
      item.put("deployedAt", container.get("deployedAt"));
      items.add(item);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("engines", DatabaseCatalog.engines().stream()
        .map(DatabaseCatalog::describe).toList());
    result.put("items", items);
    return result;
  }
}
