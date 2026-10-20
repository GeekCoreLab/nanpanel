package io.nanpanel.core;

import static io.nanpanel.core.AuthStore.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.loader.KeyPairResourceLoader;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Encrypted SSH credentials and pinned direct SSH connections for a host asset. */
@ApplicationScoped
public class HostSshCredentialService {
  private static final byte ENVELOPE_VERSION = 1;
  private static final int NONCE_BYTES = 12;
  private static final int GCM_TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  @Inject ObjectMapper mapper;

  @Inject
  @ConfigProperty(name = "nanpanel.ssh.credential-key", defaultValue = "")
  String credentialKey;

  public record HostConfig(UUID hostId, String address, int port, String username,
                           String authMethod, byte[] encryptedCredential,
                           String hostKeyFingerprint, String probedFingerprint,
                           java.time.Instant probedAt) {}

  public record Secret(String password, String privateKey, String privateKeyPassphrase) {}

  public record Connection(SshClient client, ClientSession session) implements AutoCloseable {
    @Override
    public void close() {
      try {
        session.close(true);
      } finally {
        client.stop();
      }
    }
  }

  HostConfig find(java.sql.Connection connection, UUID hostId) throws SQLException {
    var rows = query(connection,
        "SELECT host_id,address,port,username,auth_method,encrypted_credential," +
            "host_key_fingerprint,probed_host_key_fingerprint,probed_at " +
            "FROM host_ssh_config WHERE host_id=?",
        hostId);
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    Object probedAt = row.get("probed_at");
    java.time.Instant instant = probedAt instanceof java.sql.Timestamp timestamp
        ? timestamp.toInstant()
        : probedAt instanceof java.time.OffsetDateTime timestamp
            ? timestamp.toInstant() : null;
    return new HostConfig((UUID)row.get("host_id"), String.valueOf(row.get("address")),
        ((Number)row.get("port")).intValue(), String.valueOf(row.get("username")),
        String.valueOf(row.get("auth_method")), (byte[])row.get("encrypted_credential"),
        (String)row.get("host_key_fingerprint"),
        (String)row.get("probed_host_key_fingerprint"), instant);
  }

  byte[] encrypt(UUID hostId, Secret secret) {
    byte[] cleartext = null;
    try {
      cleartext = mapper.writeValueAsBytes(secret);
      return encryptEnvelope(cleartext, aad(hostId));
    } catch (Exception e) {
      throw new IllegalStateException("SSH credential encryption is unavailable", e);
    } finally {
      if (cleartext != null) java.util.Arrays.fill(cleartext, (byte)0);
    }
  }

  Secret decrypt(HostConfig config) {
    byte[] cleartext = null;
    try {
      cleartext = decryptEnvelope(config.encryptedCredential(), aad(config.hostId()));
      return mapper.readValue(cleartext, Secret.class);
    } catch (Exception e) {
      throw new IllegalStateException("SSH credential decryption failed", e);
    } finally {
      if (cleartext != null) java.util.Arrays.fill(cleartext, (byte)0);
    }
  }

  byte[] encryptAudit(UUID sessionId, long sequence, String kind, byte[] payload) {
    if (sequence < 1 || !List.of("INPUT", "OUTPUT", "COMMAND").contains(kind))
      throw new IllegalArgumentException("Invalid bastion recording context");
    try {
      return encryptEnvelope(payload, auditAad(sessionId, sequence, kind));
    } catch (Exception e) {
      throw new IllegalStateException("Bastion audit encryption is unavailable", e);
    }
  }

  byte[] decryptAudit(UUID sessionId, long sequence, String kind, byte[] envelope) {
    if (sequence < 1 || !List.of("INPUT", "OUTPUT", "COMMAND").contains(kind))
      throw new IllegalArgumentException("Invalid bastion recording context");
    try {
      return decryptEnvelope(envelope, auditAad(sessionId, sequence, kind));
    } catch (Exception e) {
      throw new IllegalStateException("Bastion audit decryption failed", e);
    }
  }

  private byte[] encryptEnvelope(byte[] cleartext, byte[] associatedData) throws Exception {
    byte[] key = keyBytes();
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(GCM_TAG_BITS, nonce));
      cipher.updateAAD(associatedData);
      byte[] ciphertext = cipher.doFinal(cleartext);
      byte[] envelope = new byte[1 + NONCE_BYTES + ciphertext.length];
      envelope[0] = ENVELOPE_VERSION;
      System.arraycopy(nonce, 0, envelope, 1, nonce.length);
      System.arraycopy(ciphertext, 0, envelope, 1 + nonce.length, ciphertext.length);
      return envelope;
    } finally {
      java.util.Arrays.fill(key, (byte)0);
    }
  }

  private byte[] decryptEnvelope(byte[] envelope, byte[] associatedData) throws Exception {
    if (envelope == null || envelope.length <= 1 + NONCE_BYTES ||
        envelope[0] != ENVELOPE_VERSION)
      throw new IllegalStateException("Encrypted envelope is invalid");
    byte[] key = keyBytes();
    try {
      byte[] nonce = java.util.Arrays.copyOfRange(envelope, 1, 1 + NONCE_BYTES);
      byte[] ciphertext = java.util.Arrays.copyOfRange(envelope, 1 + NONCE_BYTES, envelope.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(GCM_TAG_BITS, nonce));
      cipher.updateAAD(associatedData);
      return cipher.doFinal(ciphertext);
    } finally {
      java.util.Arrays.fill(key, (byte)0);
    }
  }

  String probeFingerprint(HostConfig config) throws Exception {
    SshClient client = SshClient.setUpDefaultClient();
    client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
    client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
    client.setServerKeyVerifier((session, address, key) -> true);
    client.start();
    try {
      try (ClientSession session = client.connect(config.username(), config.address(), config.port())
          .verify(8_000).getSession()) {
        var state = session.waitFor(java.util.Set.of(
            ClientSession.ClientSessionEvent.WAIT_AUTH,
            ClientSession.ClientSessionEvent.CLOSED,
            ClientSession.ClientSessionEvent.TIMEOUT), 8_000);
        if (!state.contains(ClientSession.ClientSessionEvent.WAIT_AUTH))
          throw new IllegalStateException("SSH key exchange did not complete");
        // Do not authenticate during fingerprint discovery.
        String fingerprint = KeyUtils.getFingerPrint(session.getServerKey());
        if (fingerprint == null || !fingerprint.startsWith("SHA256:"))
          throw new IllegalStateException("SSH server did not provide a usable host key");
        return fingerprint;
      }
    } finally {
      client.stop();
    }
  }

  Connection connect(HostConfig config) throws Exception {
    if (config.hostKeyFingerprint() == null)
      throw new IllegalStateException("SSH host key is not trusted");
    Secret secret = decrypt(config);
    SshClient client = SshClient.setUpDefaultClient();
    client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
    client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
    client.setServerKeyVerifier((session, address, key) -> MessageDigest.isEqual(
        KeyUtils.getFingerPrint(key).getBytes(StandardCharsets.US_ASCII),
        config.hostKeyFingerprint().getBytes(StandardCharsets.US_ASCII)));
    client.start();
    ClientSession session = null;
    try {
      session = client.connect(config.username(), config.address(), config.port())
          .verify(10_000).getSession();
      if ("PASSWORD".equals(config.authMethod())) {
        session.addPasswordIdentity(secret.password());
      } else if ("PRIVATE_KEY".equals(config.authMethod())) {
        var provider = secret.privateKeyPassphrase() == null ||
            secret.privateKeyPassphrase().isEmpty()
            ? FilePasswordProvider.EMPTY
            : FilePasswordProvider.of(secret.privateKeyPassphrase());
        KeyPairResourceLoader loader = SecurityUtils.getKeyPairResourceParser();
        Collection<KeyPair> keyPairs = loader.loadKeyPairs(null,
            NamedResource.ofName("nanpanel-host-key"), provider,
            new ByteArrayInputStream(secret.privateKey().getBytes(StandardCharsets.UTF_8)));
        if (keyPairs.isEmpty()) throw new IllegalArgumentException("SSH private key is invalid");
        for (KeyPair keyPair : keyPairs) session.addPublicKeyIdentity(keyPair);
      } else {
        throw new IllegalArgumentException("Unsupported SSH authentication method");
      }
      session.auth().verify(10_000);
      return new Connection(client, session);
    } catch (Exception e) {
      if (session != null) session.close(true);
      client.stop();
      throw e;
    }
  }

  private byte[] keyBytes() {
    try {
      byte[] bytes = Base64.getDecoder().decode(credentialKey);
      if (bytes.length != 32) throw new IllegalArgumentException("AES key must be 32 bytes");
      return bytes;
    } catch (RuntimeException invalid) {
      throw new IllegalStateException("NANPANEL_SSH_CREDENTIAL_KEY must be base64 for 32 random bytes");
    }
  }

  private byte[] aad(UUID hostId) {
    return ("nanpanel-host-ssh:v1:" + hostId).getBytes(StandardCharsets.UTF_8);
  }

  private byte[] auditAad(UUID sessionId, long sequence, String kind) {
    return ("nanpanel-bastion:v1:" + sessionId + ":" + sequence + ":" + kind)
        .getBytes(StandardCharsets.UTF_8);
  }
}
