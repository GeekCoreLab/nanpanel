package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.nanpanel.plugin.api.ConfigTranslator;
import io.nanpanel.plugin.api.ClusterProvider;
import io.nanpanel.plugin.api.NetworkProvider;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.pf4j.Extension;
import org.pf4j.Plugin;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/** Boots Core with an isolated Provider and Translator JAR for API-level integration tests. */
public final class P3ProviderTestResource implements QuarkusTestResourceLifecycleManager {
  private Path temporary;

  @Override
  public Map<String, String> start() {
    try {
      temporary = Files.createTempDirectory("nanpanel-p3-provider-api-");
      Path plugins = Files.createDirectory(temporary.resolve("providers"));
      Path fixture = Files.createDirectory(temporary.resolve("fixture"));
      createPlugin(plugins.resolve("p3-api-fixture.jar"), fixture);
      return Map.of(
          "nanpanel.providers.enabled", "true",
          "nanpanel.providers.directory", plugins.toString(),
          "quarkus.datasource.jdbc.url",
              "jdbc:h2:mem:p3-provider-api;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
    } catch (Exception failure) {
      throw new IllegalStateException("could not prepare the P3 Provider fixture", failure);
    }
  }

  @Override
  public void stop() {
    if (temporary == null) return;
    try (var paths = Files.walk(temporary)) {
      paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (Exception failure) {
          throw new IllegalStateException("could not remove the P3 Provider fixture", failure);
        }
      });
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("could not remove the P3 Provider fixture", failure);
    }
  }

  private static void createPlugin(Path target, Path fixture) throws Exception {
    Path sourceRoot = Files.createDirectories(fixture.resolve("source"));
    Path classes = Files.createDirectories(fixture.resolve("classes"));
    compileFixture(sourceRoot, classes, "io/nanpanel/core/p3/P3FixturePlugin.java");
    compileFixture(sourceRoot, classes, "io/nanpanel/core/p3/P3FixtureNetworkProvider.java");
    compileFixture(sourceRoot, classes, "io/nanpanel/core/p3/P3FixtureClusterProvider.java");
    compileFixture(sourceRoot, classes, "io/nanpanel/core/p3/P3FixtureConfigTranslator.java");

    Manifest manifest = new Manifest();
    Attributes attributes = manifest.getMainAttributes();
    attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    attributes.putValue("Plugin-Id", "p3-api-fixture");
    attributes.putValue("Plugin-Version", "0.1.0");
    attributes.putValue("Plugin-Class", "io.nanpanel.core.p3.P3FixturePlugin");
    attributes.putValue("Plugin-Provider", "io.nanpanel");
    attributes.putValue("Plugin-Dependencies", "");
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target), manifest);
         var paths = Files.walk(classes)) {
      for (Path path : paths.filter(Files::isRegularFile).toList()) {
        String name = classes.relativize(path).toString().replace(File.separatorChar, '/');
        output.putNextEntry(new JarEntry(name));
        Files.copy(path, output);
        output.closeEntry();
      }
      output.putNextEntry(new JarEntry("META-INF/extensions.idx"));
      output.write(("io.nanpanel.core.p3.P3FixtureNetworkProvider\n"
          + "io.nanpanel.core.p3.P3FixtureClusterProvider\n"
          + "io.nanpanel.core.p3.P3FixtureConfigTranslator\n")
          .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      output.closeEntry();
    }
  }

  private static void compileFixture(Path sourceRoot, Path classes, String relativeName)
      throws Exception {
    Path source = sourceRoot.resolve(relativeName);
    Files.createDirectories(source.getParent());
    try (InputStream input = P3ProviderTestResource.class.getResourceAsStream(
        "/p3-provider-src/" + relativeName)) {
      assertNotNull(input, "missing Provider fixture source " + relativeName);
      Files.copy(input, source);
    }
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "P3 Provider API test requires a JDK");
    String classpath = String.join(File.pathSeparator,
        classLocation(NetworkProvider.class), classLocation(ClusterProvider.class),
        classLocation(ConfigTranslator.class),
        classLocation(Plugin.class), classLocation(Extension.class));
    int result = compiler.run(null, null, null, "--release", "21", "-proc:none", "-classpath",
        classpath, "-d", classes.toString(), source.toString());
    assertEquals(0, result, "failed to compile isolated Provider fixture " + relativeName);
  }

  private static String classLocation(Class<?> type) throws Exception {
    var codeSource = type.getProtectionDomain().getCodeSource();
    if (codeSource != null) return Path.of(codeSource.getLocation().toURI()).toString();
    var resource = type.getResource("/" + type.getName().replace('.', '/') + ".class");
    assertNotNull(resource, "cannot locate class file for " + type.getName());
    if (resource.getProtocol().equals("jar")) {
      return Path.of(((java.net.JarURLConnection)resource.openConnection()).getJarFileURL()
          .toURI()).toString();
    }
    Path classFile = Path.of(resource.toURI());
    Path root = classFile;
    for (int i = 0; i <= type.getName().split("\\.").length; i++) root = root.getParent();
    return root.toString();
  }
}
