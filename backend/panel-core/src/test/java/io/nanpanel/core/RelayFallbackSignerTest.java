package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class RelayFallbackSignerTest {
  @Test
  void signsNormalizedFallbackListAndRejectsAlteredPayload() throws Exception {
    var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    byte[] encodedPublicKey = keyPair.getPublic().getEncoded();
    byte[] rawPublicKey = java.util.Arrays.copyOfRange(
        encodedPublicKey, encodedPublicKey.length - 32, encodedPublicKey.length);
    List<String> endpoints = List.of("https://relay-c.example", "https://relay-d.example:9443");

    var manifest = RelayFallbackSigner.signManifest(endpoints, keyPair.getPrivate(), rawPublicKey);
    Signature verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(keyPair.getPublic());
    verifier.update(RelayFallbackSigner.canonicalPayload(manifest.endpoints()));
    assertTrue(verifier.verify(HexFormat.of().parseHex(manifest.signature())));

    verifier.initVerify(keyPair.getPublic());
    verifier.update(RelayFallbackSigner.canonicalPayload(
        List.of("https://attacker.example", "https://relay-d.example:9443")));
    assertFalse(verifier.verify(HexFormat.of().parseHex(manifest.signature())));
  }
}
