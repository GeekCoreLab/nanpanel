package io.nanpanel.provider.frp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nanpanel.plugin.runtime.ProviderRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrpPluginLifecycleTest {
  @TempDir Path temporary;

  @Test
  void pf4jDiscoversProviderAndTranslatorAndDisablePreservesExternalConfig() throws Exception {
    Path plugins = Files.createDirectory(temporary.resolve("plugins"));
    Path config = temporary.resolve("frpc.toml");
    String unmanaged = "serverAddr = \"example.test\"\nauth.token = \"unmanaged-secret\"\n";
    Files.writeString(config, unmanaged);
    buildPluginJar(plugins.resolve("provider-frp.jar"));

    ProviderRuntime runtime = new ProviderRuntime(plugins);
    try {
      runtime.start();
      assertEquals(java.util.List.of("provider-frp"), runtime.pluginIds());
      assertEquals("frp-routes", runtime.providers().getFirst().id());
      assertEquals("frp-toml", runtime.translators().getFirst().id());
      assertTrue(runtime.disable("provider-frp"));
      assertEquals("DISABLED", runtime.pluginStatuses().getFirst().state());
      assertTrue(runtime.providers().isEmpty());
      assertTrue(runtime.translators().isEmpty());
      assertEquals(unmanaged, Files.readString(config));
      assertTrue(runtime.enable("provider-frp"));
      assertEquals("frp-routes", runtime.providers().getFirst().id());
      assertEquals(unmanaged, Files.readString(config));
    } finally {
      runtime.close();
    }
  }

  private static void buildPluginJar(Path target) throws Exception {
    Manifest manifest = new Manifest();
    Attributes attributes = manifest.getMainAttributes();
    attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    attributes.putValue("Plugin-Id", "provider-frp");
    attributes.putValue("Plugin-Version", "0.1.0");
    attributes.putValue("Plugin-Class", "io.nanpanel.provider.frp.FrpPlugin");
    attributes.putValue("Plugin-Provider", "io.nanpanel");
    attributes.putValue("Plugin-Dependencies", "");
    Path classes = Path.of("target/classes");
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target), manifest);
         var paths = Files.walk(classes)) {
      for (Path path : paths.filter(Files::isRegularFile).toList()) {
        String name = classes.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
        output.putNextEntry(new JarEntry(name));
        Files.copy(path, output);
        output.closeEntry();
      }
    }
  }
}
