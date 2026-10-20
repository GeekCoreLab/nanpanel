package io.nanpanel.core;

import io.nanpanel.plugin.api.NetworkProvider;
import io.nanpanel.plugin.runtime.ProviderRuntime;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class NetworkProviderRegistry {
  @ConfigProperty(name = "nanpanel.providers.enabled", defaultValue = "false")
  boolean enabled;

  @ConfigProperty(name = "nanpanel.providers.directory", defaultValue = "/work/providers")
  String directory;

  private ProviderRuntime runtime;

  @PostConstruct
  void start() {
    if (!enabled || !Files.isDirectory(Path.of(directory))) {
      return;
    }
    runtime = new ProviderRuntime(Path.of(directory));
    runtime.start();
  }

  public Optional<NetworkProvider> provider(String id) {
    return providers().stream().filter(provider -> provider.id().equals(id)).findFirst();
  }

  public List<NetworkProvider> providers() {
    return runtime == null ? List.of() : runtime.providers();
  }

  @PreDestroy
  void stop() {
    if (runtime != null) {
      runtime.close();
      runtime = null;
    }
  }
}
