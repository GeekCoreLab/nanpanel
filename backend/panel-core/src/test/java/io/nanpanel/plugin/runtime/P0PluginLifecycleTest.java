package io.nanpanel.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.nanpanel.plugin.api.NetworkProvider;
import io.quarkus.test.junit.QuarkusTest;
import java.io.File;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.pf4j.Extension;
import org.pf4j.Plugin;
import org.junit.jupiter.api.Test;

@QuarkusTest
class P0PluginLifecycleTest {
  @Test
  void quarkusHostLoadsProviderAndUnloadStopsTasksAndReleasesClassLoader() throws Exception {
    Path temporary = Files.createTempDirectory("nanpanel-p0-plugin-test-");
    Path directory = Files.createDirectory(temporary.resolve("plugins"));
    Path fixture = Files.createDirectory(temporary.resolve("fixture"));
    Path marker = Files.createTempFile("nanpanel-p0-plugin-", ".log");
    Files.deleteIfExists(marker);
    System.setProperty("nanpanel.p0.plugin.marker", marker.toString());
    WeakReference<ClassLoader> reference;
    try {
      createPlugin(directory, fixture);
      reference = loadAndUnload(directory, marker);
      awaitCollected(reference, Duration.ofSeconds(5));
    } finally {
      System.clearProperty("nanpanel.p0.plugin.marker");
      try (var paths = Files.walk(temporary)) {
        paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
          try { Files.deleteIfExists(path); } catch (Exception e) { throw new RuntimeException(e); }
        });
      }
      Files.deleteIfExists(marker);
      Files.deleteIfExists(marker.resolveSibling(marker.getFileName() + ".stopped"));
    }
  }

  private WeakReference<ClassLoader> loadAndUnload(Path directory, Path marker) throws Exception {
    ProviderRuntime runtime = new ProviderRuntime(directory);
    runtime.start();
    assertEquals(java.util.List.of("p0-lifecycle"), runtime.pluginIds());
    var providers = runtime.providers();
    assertEquals(1, providers.size());
    assertEquals("p0-lifecycle-provider", providers.getFirst().id());
    assertTrue(Files.exists(marker));

    var evidence = runtime.unloadAndObserve("p0-lifecycle");
    providers = java.util.List.of();
    assertTrue(evidence.unloaded());
    assertTrue(evidence.classLoaderClosed(), "PF4J should close the plugin URLClassLoader");
    assertTrue(runtime.pluginIds().isEmpty());
    assertTrue(runtime.providers().isEmpty());
    assertTrue(Files.exists(marker.resolveSibling(marker.getFileName() + ".stopped")));
    long stoppedSize = Files.size(marker);
    Thread.sleep(250);
    assertEquals(stoppedSize, Files.size(marker), "plugin scheduled task continued after unload");
    runtime.close();
    return evidence.classLoaderReference();
  }

  private static void awaitCollected(WeakReference<ClassLoader> reference, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (reference.get() != null && System.nanoTime() < deadline) {
      System.gc();
      Thread.sleep(50);
    }
    assertNull(reference.get(), "plugin ClassLoader remains strongly referenced after unload");
  }

  private static void createPlugin(Path directory, Path fixture) throws Exception {
    Path sourceRoot = Files.createDirectories(fixture.resolve("source"));
    Path classes = Files.createDirectories(fixture.resolve("classes"));
    compileFixture(sourceRoot, classes, "io/nanpanel/plugin/runtime/poc/PocPlugin.java");
    compileFixture(sourceRoot, classes, "io/nanpanel/plugin/runtime/poc/PocNetworkProvider.java");
    Manifest manifest = new Manifest();
    Attributes attributes = manifest.getMainAttributes();
    attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    attributes.putValue("Plugin-Id", "p0-lifecycle");
    attributes.putValue("Plugin-Version", "0.1.0");
    attributes.putValue("Plugin-Class", "io.nanpanel.plugin.runtime.poc.PocPlugin");
    Path jar = directory.resolve("p0-lifecycle.jar");
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
      try (var paths = Files.walk(classes)) {
        for (Path path : paths.filter(Files::isRegularFile).toList()) {
          String name = classes.relativize(path).toString().replace(File.separatorChar, '/');
          output.putNextEntry(new JarEntry(name));
          Files.copy(path, output);
          output.closeEntry();
        }
      }
      output.putNextEntry(new JarEntry("META-INF/extensions.idx"));
      output.write("io.nanpanel.plugin.runtime.poc.PocNetworkProvider\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      output.closeEntry();
    }
  }

  private static void compileFixture(Path sourceRoot, Path classes, String relativeName) throws Exception {
    Path source = sourceRoot.resolve(relativeName);
    Files.createDirectories(source.getParent());
    try (InputStream input = P0PluginLifecycleTest.class.getResourceAsStream("/p0-plugin-src/" + relativeName)) {
      assertNotNull(input, "missing plugin fixture source " + relativeName);
      Files.copy(input, source);
    }
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "P0 lifecycle PoC requires a JDK");
    String classpath = String.join(File.pathSeparator,
        classLocation(NetworkProvider.class), classLocation(Plugin.class), classLocation(Extension.class));
    int result = compiler.run(null, null, null, "--release", "21", "-proc:none", "-classpath", classpath,
        "-d", classes.toString(), source.toString());
    assertEquals(0, result, "failed to compile isolated PF4J fixture");
  }

  private static String classLocation(Class<?> type) throws Exception {
    var codeSource = type.getProtectionDomain().getCodeSource();
    if (codeSource != null) return Path.of(codeSource.getLocation().toURI()).toString();
    var resource = type.getResource("/" + type.getName().replace('.', '/') + ".class");
    assertNotNull(resource, "cannot locate class file for " + type.getName());
    if (resource.getProtocol().equals("jar")) {
      return Path.of(((java.net.JarURLConnection) resource.openConnection()).getJarFileURL().toURI()).toString();
    }
    Path classFile = Path.of(resource.toURI());
    Path root = classFile;
    for (int i = 0; i <= type.getName().split("\\.").length; i++) root = root.getParent();
    return root.toString();
  }
}
