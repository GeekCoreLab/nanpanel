package io.nanpanel.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Recognises database engines from container image references so the console can turn
 * an already-deployed container into a database instance with a ready-to-use connection
 * address. Pure and dependency-free so the matching rules are unit-tested.
 *
 * Only image references are inspected — nothing is pulled, run or connected to.
 */
public final class DatabaseCatalog {

  private DatabaseCatalog() {}

  public record Engine(String id, String label, int defaultPort, String scheme,
                       List<String> aliases) {}

  private static final List<Engine> ENGINES = List.of(
      new Engine("mysql", "MySQL", 3306, "jdbc:mysql://", List.of("mysql", "percona")),
      new Engine("mariadb", "MariaDB", 3306, "jdbc:mariadb://", List.of("mariadb")),
      new Engine("postgresql", "PostgreSQL", 5432, "jdbc:postgresql://", List.of("postgres", "postgresql")),
      new Engine("redis", "Redis", 6379, "redis://", List.of("redis", "valkey")),
      new Engine("mongodb", "MongoDB", 27017, "mongodb://", List.of("mongo", "mongodb")));

  public static List<Engine> engines() {
    return ENGINES;
  }

  /** Repository name without registry, tag or digest: `registry:5000/acme/mysql:8` → `mysql`. */
  static String repository(String image) {
    if (image == null) return "";
    String reference = image.split("@", 2)[0].trim();
    int slash = reference.lastIndexOf('/');
    int colon = reference.lastIndexOf(':');
    String name = colon > slash ? reference.substring(0, colon) : reference;
    int lastSlash = name.lastIndexOf('/');
    return (lastSlash >= 0 ? name.substring(lastSlash + 1) : name).toLowerCase(Locale.ROOT);
  }

  public static Optional<Engine> detect(String image) {
    String repository = repository(image);
    if (repository.isEmpty()) return Optional.empty();
    for (Engine engine : ENGINES) {
      for (String alias : engine.aliases()) {
        if (repository.equals(alias) || repository.startsWith(alias + "-")
            || repository.startsWith(alias + "_")) {
          return Optional.of(engine);
        }
      }
    }
    return Optional.empty();
  }

  /** Connection address/URL for an instance reachable on the host's loopback port. */
  public static String connectionUrl(Engine engine, String hostname, int port) {
    return engine.scheme() + hostname + ":" + port;
  }

  /** Serialisable view of an engine for API responses. */
  public static Map<String, Object> describe(Engine engine) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", engine.id());
    map.put("label", engine.label());
    map.put("defaultPort", engine.defaultPort());
    map.put("scheme", engine.scheme());
    return map;
  }
}
