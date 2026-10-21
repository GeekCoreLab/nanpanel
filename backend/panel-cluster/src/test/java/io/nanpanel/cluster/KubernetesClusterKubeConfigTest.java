package io.nanpanel.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KubernetesClusterKubeConfigTest {
  private static final String CONFIG = """
      apiVersion: v1
      kind: Config
      current-context: selected
      clusters:
      - name: cluster-a
        cluster:
          server: https://kube-a.example.test:6443
          certificate-authority-data: Y2EtY2VydA==
      - name: cluster-b
        cluster:
          server: https://kube-b.example.test:6443
          certificate-authority-data: Y2EtY2VydA==
      contexts:
      - name: selected
        context:
          cluster: cluster-a
          user: user-a
      - name: other
        context:
          cluster: cluster-b
          user: user-b
      users:
      - name: user-a
        user:
          token: token-a-secret
      - name: user-b
        user:
          token: token-b-secret
      preferences: {}
      """;

  @Test
  void narrowsTheImportedConfigToOneContextAndRedactsItsStringForm() {
    var selected = KubernetesClusterKubeConfig.select(CONFIG, "selected");

    assertEquals("selected", selected.contextName());
    assertEquals("https://kube-a.example.test:6443", selected.endpoint().toString());
    assertTrue(selected.sanitizedYaml().contains("token-a-secret"));
    assertFalse(selected.sanitizedYaml().contains("token-b-secret"));
    assertFalse(selected.sanitizedYaml().contains("kube-b.example.test"));
    assertFalse(selected.toString().contains("token-a-secret"));
    try (var client = selected.openClient()) {
      assertEquals("https://kube-a.example.test:6443/", client.getMasterUrl().toExternalForm());
    }
  }

  @Test
  void rejectsExecCredentialsAndInsecureOrLocalEndpoints() {
    assertThrows(IllegalArgumentException.class, () -> KubernetesClusterKubeConfig.select(
        CONFIG.replace("token: token-a-secret", "exec:\n      command: /usr/bin/anything"),
        "selected"));
    assertThrows(IllegalArgumentException.class, () -> KubernetesClusterKubeConfig.select(
        CONFIG.replace("certificate-authority-data: Y2EtY2VydA==",
            "insecure-skip-tls-verify: true"), "selected"));
    assertThrows(IllegalArgumentException.class, () -> KubernetesClusterKubeConfig.select(
        CONFIG.replace("https://kube-a.example.test:6443", "http://kube-a.example.test:6443"),
        "selected"));
    assertThrows(IllegalArgumentException.class, () -> KubernetesClusterKubeConfig.select(
        CONFIG.replace("https://kube-a.example.test:6443", "https://127.0.0.1:6443"),
        "selected"));
  }

  @Test
  void rejectsMissingContextsExternalCredentialFilesAndOversizedImports() {
    assertThrows(IllegalArgumentException.class,
        () -> KubernetesClusterKubeConfig.select(CONFIG, "missing"));
    assertThrows(IllegalArgumentException.class, () -> KubernetesClusterKubeConfig.select(
        CONFIG.replace("token: token-a-secret", "tokenFile: /var/run/token"), "selected"));
    assertThrows(IllegalArgumentException.class, () -> KubernetesClusterKubeConfig.select(
        "x".repeat(256 * 1024 + 1), "selected"));
  }
}
