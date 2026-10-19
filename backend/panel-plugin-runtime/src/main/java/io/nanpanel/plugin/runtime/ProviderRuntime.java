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
import org.pf4j.DevelopmentPluginLoader;
import org.pf4j.JarPluginLoader;
import io.nanpanel.plugin.api.NetworkProvider;
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
    public List<NetworkProvider> providers() { return List.copyOf(manager.getExtensions(NetworkProvider.class)); }
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

    @Override public void close() {
        manager.stopPlugins();
        for (var plugin : List.copyOf(manager.getPlugins())) manager.unloadPlugin(plugin.getPluginId());
    }
}
