package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KubernetesClusterCredentialVaultTest {
  @Test
  void installationTokenIsEncryptedAndBoundToItsPlanContext() {
    var vault = new KubernetesClusterCredentialVault();
    vault.credentialKey = Base64.getEncoder().encodeToString(new byte[32]);
    UUID planId = UUID.randomUUID();
    String token = "p4-install-token-0123456789";

    byte[] encrypted = vault.encryptK3sInstallToken(planId, token);

    assertNotEquals(token, new String(encrypted, StandardCharsets.UTF_8));
    assertArrayEquals(token.getBytes(StandardCharsets.UTF_8),
        vault.decryptK3sInstallToken(planId, encrypted).getBytes(StandardCharsets.UTF_8));
    assertThrows(IllegalStateException.class, () -> vault.decrypt(planId, encrypted));
    byte[] tampered = Arrays.copyOf(encrypted, encrypted.length);
    tampered[tampered.length - 1] ^= 1;
    assertThrows(IllegalStateException.class, () -> vault.decryptK3sInstallToken(planId, tampered));
  }
}
