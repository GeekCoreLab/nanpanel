package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import io.nanpanel.plugin.api.ClusterProvider;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Path("/api/v1/cluster-providers/{providerId}/clusters/{clusterId}/credentials")
@Produces(MediaType.APPLICATION_JSON)
public class ClusterProviderCredentialResource {
  private static final int MAX_SECRET_BYTES = 32 * 1024;
  private static final Set<String> KUBEEDGE_PURPOSES = Set.of(
      "CLOUDCORE_BOOTSTRAP_TOKEN", "EDGECORE_BOOTSTRAP_TOKEN",
      "EDGECORE_CERTIFICATE", "EDGECORE_PRIVATE_KEY");

  @Inject AuthStore store;
  @Inject NetworkProviderRegistry providers;
  @Inject KubernetesClusterRegistryService clusters;
  @Inject KubernetesClusterCredentialVault vault;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  public record CreateCredential(String purpose, String material, String expiresAt) {}

  private record Target(String providerId, UUID clusterId, Map<String, Object> actor) {}

  @GET
  public List<Map<String, Object>> list(@PathParam("providerId") String providerId,
      @PathParam("clusterId") String clusterIdValue) {
    Target target = target(providerId, clusterIdValue);
    return store.transaction(connection -> query(connection,
        "SELECT id,purpose,expires_at,revoked_at,created_at FROM cluster_provider_credential "
            + "WHERE provider_id=? AND cluster_id=? ORDER BY created_at DESC,id",
        providerId, target.clusterId()).stream().map(ClusterProviderCredentialResource::view).toList());
  }

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  public Response create(@PathParam("providerId") String providerId,
      @PathParam("clusterId") String clusterIdValue, CreateCredential input) {
    Target target = target(providerId, clusterIdValue);
    recent(target.actor());
    if (input == null || !KUBEEDGE_PURPOSES.contains(input.purpose())
        || input.material() == null || input.material().isBlank()
        || input.material().length() > MAX_SECRET_BYTES
        || input.material().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SECRET_BYTES) {
      throw fail(422, "VALIDATION_FAILED", "KubeEdge 凭据用途或内容无效");
    }
    Instant expiry = parseExpiry(input.expiresAt());
    UUID credentialId = UUID.randomUUID();
    byte[] encrypted;
    try {
      encrypted = vault.encryptProviderCredential(credentialId, target.clusterId(),
          providerId, input.material());
    } catch (IllegalStateException unavailable) {
      throw fail(503, "CREDENTIAL_VAULT_UNAVAILABLE", "Provider 凭据加密不可用");
    }
    long now = System.currentTimeMillis();
    store.transaction(connection -> {
      update(connection, "INSERT INTO cluster_provider_credential "
          + "(id,provider_id,cluster_id,purpose,encrypted_material,expires_at,created_by,created_at) "
          + "VALUES (?,?,?,?,?,?,?,?)", credentialId, providerId, target.clusterId(),
          input.purpose(), encrypted, expiry == null ? null : expiry.toEpochMilli(),
          target.actor().get("user_id"), now);
      audit(connection, String.valueOf(target.actor().get("user_id")),
          "cluster-provider.credential.create", "cluster-provider-credential",
          credentialId.toString(), "SUCCEEDED", remoteAddress(),
          "Stored encrypted " + input.purpose() + " credential for " + providerId);
      return null;
    });
    var created = new java.util.LinkedHashMap<String, Object>();
    created.put("id", credentialId.toString());
    created.put("purpose", input.purpose());
    created.put("expiresAt", expiry == null ? null : expiry.toString());
    created.put("revokedAt", null);
    created.put("createdAt", Instant.ofEpochMilli(now).toString());
    return Response.status(Response.Status.CREATED)
        .entity(created)
        .header("Cache-Control", "no-store").build();
  }

  @DELETE
  @Path("{credentialId}")
  public Response revoke(@PathParam("providerId") String providerId,
      @PathParam("clusterId") String clusterIdValue,
      @PathParam("credentialId") String credentialIdValue) {
    Target target = target(providerId, clusterIdValue);
    recent(target.actor());
    UUID credentialId = uuid(credentialIdValue, "凭据 ID");
    long now = System.currentTimeMillis();
    store.transaction(connection -> {
      int changed = update(connection, "UPDATE cluster_provider_credential "
          + "SET revoked_at=? WHERE id=? AND provider_id=? AND cluster_id=? AND revoked_at IS NULL",
          now, credentialId, providerId, target.clusterId());
      if (changed != 1) throw fail(404, "NOT_FOUND", "Provider 凭据不存在或已撤销");
      audit(connection, String.valueOf(target.actor().get("user_id")),
          "cluster-provider.credential.revoke", "cluster-provider-credential",
          credentialId.toString(), "SUCCEEDED", remoteAddress(), "Revoked Provider credential");
      return null;
    });
    return Response.noContent().build();
  }

  private Target target(String providerId, String clusterIdValue) {
    Map<String, Object> actor = session();
    UUID clusterId = uuid(clusterIdValue, "集群 ID");
    ClusterProvider provider = providers.clusterProviders().stream()
        .filter(value -> value.id().equals(providerId)).findFirst()
        .orElseThrow(() -> fail(404, "CLUSTER_PROVIDER_UNAVAILABLE", "集群 Provider 未启用或未安装"));
    ClusterProvider.ClusterSnapshot snapshot;
    try {
      snapshot = clusters.providerSnapshot(clusterId);
    } catch (KubernetesClusterRegistryService.ClusterNotFoundException missing) {
      throw fail(404, "KUBERNETES_CLUSTER_NOT_FOUND", "集群未登记");
    }
    if (!"KUBEEDGE".equals(snapshot.kind())
        || !provider.clusterKinds().contains(snapshot.kind())) {
      throw fail(422, "CLUSTER_PROVIDER_KIND_UNSUPPORTED", "凭据用途与目标集群 Provider 不匹配");
    }
    return new Target(providerId, clusterId, actor);
  }

  private Map<String, Object> session() {
    @SuppressWarnings("unchecked")
    Map<String, Object> actor = (Map<String, Object>)request.getProperty("session");
    if (actor == null || actor.get("user_id") == null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    return actor;
  }

  private void recent(Map<String, Object> actor) {
    Object authenticated = actor.get("authenticated_at");
    long authenticatedAt = authenticated instanceof Number number
        ? number.longValue() : Long.MIN_VALUE;
    if (System.currentTimeMillis() - authenticatedAt > 300_000) {
      store.transaction(connection -> {
        audit(connection, String.valueOf(actor.get("user_id")),
            "security.reauthentication.required", "cluster-provider-credential", null,
            "DENIED", remoteAddress(), "Provider 凭据管理需要重新验证身份");
        return null;
      });
      throw fail(403, "REAUTHENTICATION_REQUIRED", "请重新验证密码后再管理 Provider 凭据");
    }
  }

  private Instant parseExpiry(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      Instant expiry = Instant.parse(value);
      if (!expiry.isAfter(Instant.now()) || expiry.isAfter(Instant.now().plusSeconds(366L * 86400)))
        throw new IllegalArgumentException();
      return expiry;
    } catch (RuntimeException invalid) {
      throw fail(422, "VALIDATION_FAILED", "凭据到期时间必须在未来 366 天内");
    }
  }

  private static Map<String, Object> view(Map<String, Object> row) {
    var result = new java.util.LinkedHashMap<String, Object>();
    result.put("id", String.valueOf(row.get("id")));
    result.put("purpose", row.get("purpose"));
    result.put("expiresAt", row.get("expires_at") == null
        ? null : Instant.ofEpochMilli(((Number)row.get("expires_at")).longValue()).toString());
    result.put("revokedAt", row.get("revoked_at") == null
        ? null : Instant.ofEpochMilli(((Number)row.get("revoked_at")).longValue()).toString());
    result.put("createdAt", Instant.ofEpochMilli(((Number)row.get("created_at")).longValue()).toString());
    return result;
  }

  private static UUID uuid(String value, String field) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException invalid) {
      throw fail(400, "VALIDATION_FAILED", field + " 必须为 UUID");
    }
  }

  private String remoteAddress() {
    return http.remoteAddress() == null ? null : http.remoteAddress().host();
  }

}
