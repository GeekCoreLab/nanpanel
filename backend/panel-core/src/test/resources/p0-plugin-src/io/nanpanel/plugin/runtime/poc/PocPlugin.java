package io.nanpanel.plugin.runtime.poc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

public final class PocPlugin extends Plugin {
  private ScheduledExecutorService executor;

  public PocPlugin(PluginWrapper wrapper) { super(wrapper); }

  @Override
  public void start() {
    Path marker = Path.of(System.getProperty("nanpanel.p0.plugin.marker"));
    executor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread thread = new Thread(r, "nanpanel-p0-plugin-task");
      thread.setDaemon(true);
      return thread;
    });
    executor.scheduleAtFixedRate(() -> {
      try {
        Files.writeString(marker, "tick\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }, 0, 50, TimeUnit.MILLISECONDS);
  }

  @Override
  public void stop() {
    if (executor == null) return;
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("plugin task did not terminate");
      }
      Path marker = Path.of(System.getProperty("nanpanel.p0.plugin.marker"));
      Files.writeString(marker.resolveSibling(marker.getFileName() + ".stopped"), "stopped\n");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    } finally {
      executor = null;
    }
  }
}
