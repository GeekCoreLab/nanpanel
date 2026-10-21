package io.nanpanel.core;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.nanpanel.cluster.KubernetesClusterKubeConfig;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class KubernetesClusterClientFactory {
  public KubernetesClient open(KubernetesClusterKubeConfig kubeconfig) {
    return kubeconfig.openClient();
  }
}
