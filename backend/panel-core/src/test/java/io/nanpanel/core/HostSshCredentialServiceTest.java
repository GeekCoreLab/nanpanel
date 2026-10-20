package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.UUID;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.junit.jupiter.api.Test;

@QuarkusTest
class HostSshCredentialServiceTest {
  @Inject HostSshCredentialService service;

  @Test
  void encryptsCredentialsAndBindsTheEnvelopeToHostIdentity() {
    UUID hostId = UUID.randomUUID();
    var secret = new HostSshCredentialService.Secret("ssh-test-password", null, null);
    byte[] encrypted = service.encrypt(hostId, secret);
    assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains("ssh-test-password"));
    var config = new HostSshCredentialService.HostConfig(hostId, "127.0.0.1", 22,
        "root", "PASSWORD", encrypted, null, null, null);
    assertEquals(secret, service.decrypt(config));
    var otherHost = new HostSshCredentialService.HostConfig(UUID.randomUUID(), "127.0.0.1",
        22, "root", "PASSWORD", encrypted, null, null, null);
    assertThrows(IllegalStateException.class, () -> service.decrypt(otherHost));
  }

  @Test
  void encryptsTerminalRecordingsAndBindsThemToSessionSequenceAndDirection() {
    UUID sessionId = UUID.randomUUID();
    byte[] command = "cat /etc/shadow".getBytes(StandardCharsets.UTF_8);
    byte[] encrypted = service.encryptAudit(sessionId, 1, "COMMAND", command);
    assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains("/etc/shadow"));
    assertArrayEquals(command, service.decryptAudit(sessionId, 1, "COMMAND", encrypted));
    assertThrows(IllegalStateException.class,
        () -> service.decryptAudit(sessionId, 2, "COMMAND", encrypted));
    assertThrows(IllegalStateException.class,
        () -> service.decryptAudit(UUID.randomUUID(), 1, "COMMAND", encrypted));
  }

  @Test
  void connectsOnlyWithTheSavedPasswordOrPrivateKeyAndPinnedHostKey() throws Exception {
    KeyPair serverKey = keyPair();
    KeyPair userKey = keyPair();
    SshServer server = SshServer.setUpDefaultServer();
    server.setHost("127.0.0.1");
    server.setPort(0);
    server.setKeyPairProvider(KeyPairProvider.wrap(serverKey));
    server.setPasswordAuthenticator((username, password, session) ->
        username.equals("panel-test") && password.equals("password-1"));
    server.setPublickeyAuthenticator((username, key, session) ->
        username.equals("panel-test") && KeyUtils.compareKeys(key, userKey.getPublic()));
    server.start();
    try {
      UUID hostId = UUID.randomUUID();
      String fingerprint = KeyUtils.getFingerPrint(serverKey.getPublic());
      byte[] passwordCiphertext = service.encrypt(hostId,
          new HostSshCredentialService.Secret("password-1", null, null));
      var passwordConfig = new HostSshCredentialService.HostConfig(hostId, "127.0.0.1",
          server.getPort(), "panel-test", "PASSWORD", passwordCiphertext,
          fingerprint, null, null);
      try (var connection = service.connect(passwordConfig)) {
        assertEquals(serverKey.getPublic(), connection.session().getServerKey());
      }

      UUID keyHostId = UUID.randomUUID();
      byte[] privateKeyCiphertext = service.encrypt(keyHostId,
          new HostSshCredentialService.Secret(null, privateKeyPem(userKey), null));
      var keyConfig = new HostSshCredentialService.HostConfig(keyHostId, "127.0.0.1",
          server.getPort(), "panel-test", "PRIVATE_KEY", privateKeyCiphertext,
          fingerprint, null, null);
      try (var connection = service.connect(keyConfig)) {
        assertEquals(serverKey.getPublic(), connection.session().getServerKey());
      }

      var mismatchedPin = new HostSshCredentialService.HostConfig(hostId, "127.0.0.1",
          server.getPort(), "panel-test", "PASSWORD", passwordCiphertext,
          "SHA256:" + "A".repeat(43), null, null);
      assertThrows(Exception.class, () -> service.connect(mismatchedPin));
    } finally {
      server.stop(true);
    }
  }

  private KeyPair keyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  private String privateKeyPem(KeyPair keyPair) {
    String body = Base64.getMimeEncoder(64, new byte[] {'\n'})
        .encodeToString(keyPair.getPrivate().getEncoded());
    return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
  }
}
