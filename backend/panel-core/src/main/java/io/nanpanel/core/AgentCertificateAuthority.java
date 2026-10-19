package io.nanpanel.core;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class AgentCertificateAuthority {
  @Inject
  @ConfigProperty(name = "nanpanel.agent.ca-cert")
  String caCertificate;

  @Inject
  @ConfigProperty(name = "nanpanel.agent.ca-key")
  String caPrivateKey;

  @Inject
  @ConfigProperty(name = "nanpanel.agent.serving-cert")
  String servingCertificate;

  public String controllerFingerprint() {
    try (var input = Files.newInputStream(Path.of(servingCertificate))) {
      var certificate = (X509Certificate)CertificateFactory.getInstance("X.509")
          .generateCertificate(input);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(certificate.getEncoded()));
    } catch (Exception e) {
      throw new IllegalStateException("Controller TLS certificate is unavailable", e);
    }
  }

  public byte[] controllerCertificatePem() {
    try {
      return Files.readAllBytes(Path.of(servingCertificate));
    } catch (IOException e) {
      throw new IllegalStateException("Controller TLS certificate is unavailable", e);
    }
  }

  public String signClientRequest(UUID agentId, String csrPem) {
    Path work = null;
    try {
      work = Files.createTempDirectory("nanpanel-agent-enrollment-");
      Path csr = work.resolve("agent.csr");
      Path signed = work.resolve("agent.crt");
      Path extensions = work.resolve("extensions.cnf");
      Files.writeString(csr, csrPem, StandardCharsets.US_ASCII);
      Files.writeString(extensions,
          "basicConstraints=critical,CA:FALSE\n" +
          "keyUsage=critical,digitalSignature\n" +
          "extendedKeyUsage=clientAuth\n" +
          "subjectAltName=URI:spiffe://nanpanel/agent/" + agentId + "\n",
          StandardCharsets.US_ASCII);

      String requestInfo = run("req", "-in", csr.toString(), "-noout", "-verify",
          "-subject", "-nameopt", "RFC2253");
      if (!requestInfo.lines().anyMatch(line -> line.equals("subject=CN=" + agentId)))
        throw new IllegalArgumentException("Certificate request identity does not match enrollment");

      run("x509", "-req", "-in", csr.toString(), "-CA", caCertificate,
          "-CAkey", caPrivateKey, "-set_serial", "0x" + agentId.toString().replace("-", ""),
          "-days", "90", "-sha256", "-extfile", extensions.toString(),
          "-out", signed.toString());
      try (var input = Files.newInputStream(signed)) {
        var certificate = (X509Certificate)CertificateFactory.getInstance("X.509")
            .generateCertificate(input);
        certificate.checkValidity();
        return Files.readString(signed, StandardCharsets.US_ASCII);
      }
    } catch (IllegalArgumentException e) {
      throw AuthResource.fail(400, "INVALID_CERTIFICATE_REQUEST", e.getMessage());
    } catch (Exception e) {
      throw new IllegalStateException("Unable to issue Agent certificate", e);
    } finally {
      if (work != null) {
        try (var files = Files.walk(work)) {
          files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
            try { Files.deleteIfExists(path); } catch (IOException ignored) { }
          });
        } catch (IOException ignored) { }
      }
    }
  }

  public static String fingerprint(String certificatePem) {
    try (var input = new java.io.ByteArrayInputStream(
        certificatePem.getBytes(StandardCharsets.US_ASCII))) {
      var certificate = (X509Certificate)CertificateFactory.getInstance("X.509")
          .generateCertificate(input);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(certificate.getEncoded()));
    } catch (Exception e) {
      throw new IllegalArgumentException("Issued certificate is invalid", e);
    }
  }

  private String run(String... args) throws Exception {
    var command = new java.util.ArrayList<String>();
    command.add("openssl");
    command.addAll(java.util.List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("OpenSSL operation timed out");
    }
    byte[] output = process.getInputStream().readAllBytes();
    String result = new String(output, StandardCharsets.UTF_8);
    if (process.exitValue() != 0)
      throw new IllegalArgumentException("OpenSSL rejected the certificate request");
    return result;
  }
}
