package io.nanpanel.core;

import static io.nanpanel.core.AuthStore.query;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;

/** Opens provider credentials only after checking their complete persisted scope and status. */
@ApplicationScoped
public class ClusterProviderCredentialStore {
  @Inject AuthStore store;
  @Inject KubernetesClusterCredentialVault vault;

  String open(UUID credentialId, UUID clusterId, String providerId, String purpose) {
    return store.transaction(connection -> {
      var rows = query(connection, "SELECT purpose,encrypted_material,expires_at,revoked_at "
          + "FROM cluster_provider_credential WHERE id=? AND cluster_id=? AND provider_id=?",
          credentialId, clusterId, providerId);
      if (rows.isEmpty()) throw unavailable();
      Map<String, Object> row = rows.getFirst();
      if (!purpose.equals(row.get("purpose")) || row.get("revoked_at") != null
          || (row.get("expires_at") != null
              && ((Number)row.get("expires_at")).longValue() <= System.currentTimeMillis()))
        throw unavailable();
      return vault.decryptProviderCredential(credentialId, clusterId, providerId,
          (byte[])row.get("encrypted_material"));
    });
  }

  private static IllegalArgumentException unavailable() {
    return new IllegalArgumentException("Provider credential is unavailable");
  }
}
