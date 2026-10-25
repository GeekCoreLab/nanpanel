package io.nanpanel.core;

import io.nanpanel.plugin.api.NetworkProvider;
import io.nanpanel.plugin.api.ClusterProvider;
import io.nanpanel.plugin.api.ConfigTranslator;
import io.nanpanel.plugin.runtime.ProviderRuntime;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class NetworkProviderRegistry {
  @ConfigProperty(name = "nanpanel.providers.enabled", defaultValue = "false")
  boolean enabled;

  @ConfigProperty(name = "nanpanel.providers.directory", defaultValue = "/work/providers")
  String directory;

  private ProviderRuntime runtime;
  @jakarta.inject.Inject LinuxNetnsProvider linuxNetnsProvider;

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
    var values = new ArrayList<NetworkProvider>();
    values.add(linuxNetnsProvider);
    if (runtime != null) {
      Set<String> ids = new HashSet<>();
      values.forEach(provider -> ids.add(provider.id()));
      runtime.providers().stream().filter(provider -> ids.add(provider.id())).forEach(values::add);
    }
    return List.copyOf(values);
  }

  public List<ClusterProvider> clusterProviders() {
    return runtime == null ? List.of() : runtime.clusterProviders();
  }

  public List<ConfigTranslator> translators() {
    return runtime == null ? List.of() : runtime.translators();
  }

  public List<ProviderRuntime.PluginStatus> pluginStatuses() {
    return runtime == null ? List.of() : runtime.pluginStatuses();
  }

  public boolean disablePlugin(String pluginId) {
    return runtime != null && runtime.disable(pluginId);
  }

  public boolean enablePlugin(String pluginId) {
    return runtime != null && runtime.enable(pluginId);
  }

  @PreDestroy
  void stop() {
    if (runtime != null) {
      runtime.close();
      runtime = null;
    }
  }
}
