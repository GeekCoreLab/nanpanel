package io.nanpanel.core;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Signs the root-configured Relay fallback list so Agents can verify it locally. */
@ApplicationScoped
public class RelayFallbackSigner {
  private static final byte[] ED25519_SPKI_PREFIX =
      HexFormat.of().parseHex("302a300506032b6570032100");

  @Inject
  @ConfigProperty(name = "nanpanel.agent.relay-fallback-endpoints", defaultValue = "disabled")
  String endpointConfiguration;

  @Inject
  @ConfigProperty(name = "nanpanel.agent.relay-signing-private-key", defaultValue = "disabled")
  String privateKeyPath;

  @Inject
  @ConfigProperty(name = "nanpanel.agent.relay-signing-public-key", defaultValue = "disabled")
  String publicKeyHex;

  public record Manifest(List<String> endpoints, String signature, String publicKey) {}

  public Manifest manifest() {
    final List<String> endpoints;
    try {
      endpoints = AgentEnrollmentResource.parseRelayEndpoints(endpointConfiguration);
    } catch (IllegalArgumentException invalid) {
      throw AuthResource.fail(500, "RELAY_FALLBACK_CONFIGURATION_INVALID",
          "Relay 备用地址必须是 2–8 个唯一 HTTPS 源站");
    }
    if (endpoints.isEmpty()) return new Manifest(List.of(), "", "");
    if (privateKeyPath == null || privateKeyPath.isBlank() ||
        privateKeyPath.equalsIgnoreCase("disabled") || publicKeyHex == null ||
        !publicKeyHex.matches("[a-fA-F0-9]{64}"))
      throw AuthResource.fail(503, "RELAY_FALLBACK_SIGNING_UNAVAILABLE",
          "Relay 备用名单签名密钥未配置");

    try {
      byte[] publicKey = HexFormat.of().parseHex(publicKeyHex);
      PrivateKey privateKey = readPrivateKey(Path.of(privateKeyPath));
      return signManifest(endpoints, privateKey, publicKey);
    } catch (Exception failure) {
      throw AuthResource.fail(503, "RELAY_FALLBACK_SIGNING_UNAVAILABLE",
          "Relay 备用名单无法签名或公钥与私钥不匹配");
    }
  }

  static byte[] canonicalPayload(List<String> endpoints) {
    return ("nanpanel-relay-fallback-v1\n" + String.join("\n", endpoints))
        .getBytes(StandardCharsets.UTF_8);
  }

  static Manifest signManifest(List<String> endpoints, PrivateKey privateKey, byte[] publicKey)
      throws Exception {
    if (publicKey.length != 32) throw new IllegalArgumentException("invalid Ed25519 public key length");
    Signature signer = Signature.getInstance("Ed25519");
    signer.initSign(privateKey);
    signer.update(canonicalPayload(endpoints));
    byte[] signature = signer.sign();

    byte[] encodedPublicKey = new byte[ED25519_SPKI_PREFIX.length + publicKey.length];
    System.arraycopy(ED25519_SPKI_PREFIX, 0, encodedPublicKey, 0, ED25519_SPKI_PREFIX.length);
    System.arraycopy(publicKey, 0, encodedPublicKey, ED25519_SPKI_PREFIX.length, publicKey.length);
    var key = KeyFactory.getInstance("Ed25519")
        .generatePublic(new X509EncodedKeySpec(encodedPublicKey));
    Signature verifier = Signature.getInstance("Ed25519");
    verifier.initVerify(key);
    verifier.update(canonicalPayload(endpoints));
    if (!verifier.verify(signature))
      throw new IllegalArgumentException("configured public key does not match the signing key");
    return new Manifest(endpoints, HexFormat.of().formatHex(signature),
        HexFormat.of().formatHex(publicKey));
  }

  private PrivateKey readPrivateKey(Path path) throws Exception {
    if (!path.isAbsolute() || path.getParent() == null)
      throw new IllegalArgumentException("Relay signing key file is unavailable");
    Path trustedParent = path.getParent().toRealPath();
    Path realPath = path.toRealPath();
    if (!realPath.startsWith(trustedParent) ||
        !Files.isRegularFile(realPath, LinkOption.NOFOLLOW_LINKS))
      throw new IllegalArgumentException("Relay signing key file escapes its configured directory");
    var attributes = Files.readAttributes(realPath,
        java.nio.file.attribute.PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    Set<PosixFilePermission> permissions = attributes.permissions();
    if (!"root".equals(attributes.owner().getName()) ||
        permissions.contains(PosixFilePermission.OTHERS_READ) ||
        permissions.contains(PosixFilePermission.GROUP_WRITE) ||
        permissions.contains(PosixFilePermission.OTHERS_WRITE) || attributes.size() > 4096)
      throw new IllegalArgumentException("Relay signing key file is not root-only");
    String pem = Files.readString(realPath, StandardCharsets.US_ASCII);
    String encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
    byte[] der = Base64.getDecoder().decode(encoded);
    return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
  }
}
