package io.nanpanel.plugin.runtime;

import java.nio.file.Path;
import java.util.List;
import org.pf4j.DefaultPluginManager;
import io.nanpanel.plugin.api.NetworkProvider;
/** Trusted plugin lifecycle only; permission enforcement belongs to Core/Gateway. */
public final class ProviderRuntime implements AutoCloseable {
    private final DefaultPluginManager manager;
    public ProviderRuntime(Path directory) { manager = new DefaultPluginManager(directory); }
    public void start() { manager.loadPlugins(); manager.startPlugins(); }
    public List<NetworkProvider> providers() { return List.copyOf(manager.getExtensions(NetworkProvider.class)); }
    @Override public void close() {
        manager.stopPlugins();
        for (var plugin : List.copyOf(manager.getPlugins())) manager.unloadPlugin(plugin.getPluginId());
    }
}
