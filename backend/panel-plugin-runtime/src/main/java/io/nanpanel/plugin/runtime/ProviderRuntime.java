package io.nanpanel.plugin.runtime;

import java.nio.file.Path;
import java.lang.ref.WeakReference;
import java.util.List;
import org.pf4j.DefaultPluginManager;
import org.pf4j.ClassLoadingStrategy;
import org.pf4j.CompoundPluginLoader;
import org.pf4j.DefaultPluginLoader;
import org.pf4j.PluginClassLoader;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginWrapper;
import org.pf4j.PluginState;
import org.pf4j.DevelopmentPluginLoader;
import org.pf4j.JarPluginLoader;
import io.nanpanel.plugin.api.NetworkProvider;
import io.nanpanel.plugin.api.ClusterProvider;
import io.nanpanel.plugin.api.ConfigTranslator;
/** Trusted plugin lifecycle only; permission enforcement belongs to Core/Gateway. */
public final class ProviderRuntime implements AutoCloseable {
    private final DefaultPluginManager manager;
    public ProviderRuntime(Path directory) {
        manager = new DefaultPluginManager(directory) {
            @Override
            protected org.pf4j.PluginLoader createPluginLoader() {
                return new CompoundPluginLoader()
                        .add(new DevelopmentPluginLoader(this) {
                            @Override
                            protected PluginClassLoader createPluginClassLoader(Path path, PluginDescriptor descriptor) {
                                return sharedApiClassLoader(this.pluginManager, descriptor);
                            }
                        }, this::isDevelopment)
                        .add(new JarPluginLoader(this) {
                            @Override
                            protected PluginClassLoader createPluginClassLoader(Path path, PluginDescriptor descriptor) {
                                return sharedApiClassLoader(this.pluginManager, descriptor);
                            }
                        }, this::isNotDevelopment)
                        .add(new DefaultPluginLoader(this) {
                            @Override
                            protected PluginClassLoader createPluginClassLoader(Path path, PluginDescriptor descriptor) {
                                return sharedApiClassLoader(this.pluginManager, descriptor);
                            }
                        }, this::isNotDevelopment);
            }
        };
    }

    private static PluginClassLoader sharedApiClassLoader(org.pf4j.PluginManager manager, PluginDescriptor descriptor) {
        // Extension APIs and domain classes must retain one identity across the host and plugins.
        return new PluginClassLoader(manager, descriptor, ProviderRuntime.class.getClassLoader(), ClassLoadingStrategy.APD);
    }
    public void start() { manager.loadPlugins(); manager.startPlugins(); }
    public List<NetworkProvider> providers() {
        return manager.getStartedPlugins().stream()
                .flatMap(plugin -> manager.getExtensions(NetworkProvider.class, plugin.getPluginId()).stream())
                .toList();
    }
    public List<ClusterProvider> clusterProviders() {
        return manager.getStartedPlugins().stream()
                .flatMap(plugin -> manager.getExtensions(ClusterProvider.class, plugin.getPluginId()).stream())
                .toList();
    }
    public List<ConfigTranslator> translators() {
        return manager.getStartedPlugins().stream()
                .flatMap(plugin -> manager.getExtensions(ConfigTranslator.class, plugin.getPluginId()).stream())
                .toList();
    }
    public List<PluginStatus> pluginStatuses() {
        return manager.getPlugins().stream().map(plugin -> {
            boolean started = plugin.getPluginState().isStarted();
            List<String> providerIds = started
                    ? manager.getExtensions(NetworkProvider.class, plugin.getPluginId()).stream()
                            .map(NetworkProvider::id).toList() : List.of();
            List<String> translatorIds = started
                    ? manager.getExtensions(ConfigTranslator.class, plugin.getPluginId()).stream()
                            .map(ConfigTranslator::id).toList() : List.of();
            List<String> clusterProviderIds = started
                    ? manager.getExtensions(ClusterProvider.class, plugin.getPluginId()).stream()
                            .map(ClusterProvider::id).toList() : List.of();
            return new PluginStatus(plugin.getPluginId(), plugin.getDescriptor().getVersion(),
                    plugin.getPluginState().toString(), providerIds, translatorIds, clusterProviderIds);
        }).toList();
    }
    public boolean disable(String pluginId) {
        return manager.disablePlugin(pluginId);
    }
    public boolean enable(String pluginId) {
        if (!manager.enablePlugin(pluginId)) return false;
        return manager.startPlugin(pluginId) == PluginState.STARTED;
    }
    public boolean unload(String pluginId) { return manager.unloadPlugin(pluginId); }
    public List<String> pluginIds() { return manager.getPlugins().stream().map(PluginWrapper::getPluginId).toList(); }

    UnloadEvidence unloadAndObserve(String pluginId) {
        PluginWrapper wrapper = manager.getPlugin(pluginId);
        if (wrapper == null) return new UnloadEvidence(false, false, new WeakReference<>(null));
        ClassLoader loader = wrapper.getPluginClassLoader();
        boolean unloaded = unload(pluginId);
        boolean closed = loader instanceof PluginClassLoader pluginLoader && pluginLoader.isClosed();
        return new UnloadEvidence(unloaded, closed, new WeakReference<>(loader));
    }

    record UnloadEvidence(boolean unloaded, boolean classLoaderClosed, WeakReference<ClassLoader> classLoaderReference) {}
    public record PluginStatus(String id, String version, String state,
                               List<String> providerIds, List<String> translatorIds,
                               List<String> clusterProviderIds) {}

    @Override public void close() {
        RuntimeException failure = null;
        for (var plugin : List.copyOf(manager.getStartedPlugins())) {
            try {
                manager.stopPlugin(plugin.getPluginId());
            } catch (RuntimeException stopFailure) {
                if (failure == null) failure = new IllegalStateException("Failed to stop one or more plugins");
                failure.addSuppressed(stopFailure);
            }
        }
        for (var plugin : List.copyOf(manager.getPlugins())) {
            try {
                manager.unloadPlugin(plugin.getPluginId());
            } catch (RuntimeException unloadFailure) {
                if (failure == null) failure = new IllegalStateException("Failed to unload one or more plugins");
                failure.addSuppressed(unloadFailure);
            }
        }
        if (failure != null) throw failure;
    }
}
