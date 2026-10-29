package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.nanpanel.core.DatabaseCatalog.Engine;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DatabaseCatalogTest {

  private static String id(String image) {
    Optional<Engine> engine = DatabaseCatalog.detect(image);
    return engine.map(Engine::id).orElse("unknown");
  }

  @Test
  void recognisesEnginesFromTaggedAndUntaggedReferences() {
    assertEquals("mysql", id("mysql:8.0"));
    assertEquals("mysql", id("mysql"));
    assertEquals("mariadb", id("mariadb:11"));
    assertEquals("postgresql", id("postgres:16-alpine"));
    assertEquals("postgresql", id("postgresql:15"));
    assertEquals("redis", id("redis:7-alpine"));
    assertEquals("redis", id("valkey/valkey:8"));
    assertEquals("mongodb", id("mongo:7"));
  }

  @Test
  void ignoresRegistryHostsWithPortsAndDigests() {
    assertEquals("mysql", id("registry.example.test:5000/team/mysql:8.0"));
    assertEquals("redis", id("docker.io/library/redis:7"));
    assertEquals("postgresql", id("ghcr.io/acme/postgres@sha256:" + "a".repeat(64)));
    assertEquals("mariadb", id("bitnami/mariadb:11.2"));
  }

  @Test
  void unknownOrUnrelatedImagesAreNotDatabases() {
    assertEquals("unknown", id("nginx:1.27"));
    assertEquals("unknown", id("registry.test:5000/team/my-mysql-exporter:1"));
    assertEquals("unknown", id(""));
    assertEquals("unknown", id(null));
    assertTrue(DatabaseCatalog.detect(null).isEmpty());
  }

  @Test
  void builtInPortsAndConnectionUrlsMatchTheEngine() {
    Engine mysql = DatabaseCatalog.detect("mysql:8").orElseThrow();
    assertEquals(3306, mysql.defaultPort());
    assertEquals("jdbc:mysql://db-host:13306",
        DatabaseCatalog.connectionUrl(mysql, "db-host", 13306));

    Engine redis = DatabaseCatalog.detect("redis:7").orElseThrow();
    assertEquals("redis://db-host:16379", DatabaseCatalog.connectionUrl(redis, "db-host", 16379));

    Engine mongo = DatabaseCatalog.detect("mongo:7").orElseThrow();
    assertEquals(27017, mongo.defaultPort());
    assertEquals("mongodb://db-host:27017", DatabaseCatalog.connectionUrl(mongo, "db-host", 27017));
  }

  @Test
  void engineDescriptionsExposeStableFields() {
    var description = DatabaseCatalog.describe(DatabaseCatalog.detect("mysql:8").orElseThrow());
    assertEquals("mysql", description.get("id"));
    assertEquals("MySQL", description.get("label"));
    assertEquals(3306, description.get("defaultPort"));
    assertEquals("jdbc:mysql://", description.get("scheme"));
  }
}
