package io.nanpanel.core;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Encrypts imported cluster kubeconfigs with cluster-bound authenticated data. */
@ApplicationScoped
public class KubernetesClusterCredentialVault {
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final byte ENVELOPE_VERSION = 1;
  private static final SecureRandom RANDOM = new SecureRandom();

  @ConfigProperty(name = "nanpanel.kubernetes.credential-key", defaultValue = "")
  String credentialKey;

  byte[] encrypt(UUID clusterId, String kubeconfig) {
    byte[] cleartext = kubeconfig.getBytes(StandardCharsets.UTF_8);
    byte[] key = keyBytes();
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(clusterId));
      byte[] ciphertext = cipher.doFinal(cleartext);
      byte[] envelope = new byte[1 + NONCE_BYTES + ciphertext.length];
      envelope[0] = ENVELOPE_VERSION;
      System.arraycopy(nonce, 0, envelope, 1, nonce.length);
      System.arraycopy(ciphertext, 0, envelope, 1 + nonce.length, ciphertext.length);
      Arrays.fill(ciphertext, (byte)0);
      return envelope;
    } catch (Exception failure) {
      throw new IllegalStateException("Kubernetes credential encryption is unavailable");
    } finally {
      Arrays.fill(cleartext, (byte)0);
      Arrays.fill(key, (byte)0);
    }
  }

  String decrypt(UUID clusterId, byte[] envelope) {
    if (envelope == null || envelope.length <= 1 + NONCE_BYTES
        || envelope[0] != ENVELOPE_VERSION) {
      throw new IllegalStateException("Encrypted Kubernetes credentials are invalid");
    }
    byte[] key = keyBytes();
    byte[] cleartext = null;
    try {
      byte[] nonce = Arrays.copyOfRange(envelope, 1, 1 + NONCE_BYTES);
      byte[] ciphertext = Arrays.copyOfRange(envelope, 1 + NONCE_BYTES, envelope.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(clusterId));
      cleartext = cipher.doFinal(ciphertext);
      Arrays.fill(ciphertext, (byte)0);
      return new String(cleartext, StandardCharsets.UTF_8);
    } catch (Exception failure) {
      throw new IllegalStateException("Kubernetes credential decryption failed");
    } finally {
      Arrays.fill(key, (byte)0);
      if (cleartext != null) Arrays.fill(cleartext, (byte)0);
    }
  }

  private byte[] keyBytes() {
    try {
      byte[] bytes = Base64.getDecoder().decode(credentialKey);
      if (bytes.length != 32) throw new IllegalArgumentException();
      return bytes;
    } catch (RuntimeException invalid) {
      throw new IllegalStateException(
          "NANPANEL_KUBERNETES_CREDENTIAL_KEY must encode 32 random bytes");
    }
  }

  private static byte[] aad(UUID clusterId) {
    return ("nanpanel-kubernetes-cluster:v1:" + clusterId).getBytes(StandardCharsets.UTF_8);
  }
}
