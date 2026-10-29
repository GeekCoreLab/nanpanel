package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AppStoreCatalogTest {
  private static final java.util.regex.Pattern APP_ID =
      java.util.regex.Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,62})");
  private static final java.util.regex.Pattern IMAGE =
      java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@-]{0,254}");
  private static final java.util.regex.Pattern ENV_NAME =
      java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

  @Test
  void catalogIsNonemptyCuratedAndUniquelyKeyed() {
    var apps = AppStoreCatalog.apps();
    assertFalse(apps.isEmpty(), "catalog must contain applications");
    Set<String> ids = new HashSet<>();
    for (var app : apps) {
      assertTrue(APP_ID.matcher(app.id()).matches(), "invalid app id: " + app.id());
      assertTrue(ids.add(app.id()), "duplicate app id: " + app.id());
      assertNotNull(app.name());
      assertFalse(app.name().isBlank());
      assertNotNull(app.summary());
      assertFalse(app.summary().isBlank());
      assertTrue(AppStoreCatalog.categories().stream()
              .anyMatch(c -> app.category().equals(c.get("id"))),
          "unknown category: " + app.category());
    }
  }

  @Test
  void everyAppDeclaresAValidImageAndPortOrVolume() {
    for (var app : AppStoreCatalog.apps()) {
      assertTrue(IMAGE.matcher(app.image()).matches(), "invalid image: " + app.id());
      if (app.defaultPort() != null) {
        assertTrue(app.defaultPort() >= 1 && app.defaultPort() <= 65535,
            "invalid default port for " + app.id());
      }
      for (var volume : app.volumes()) {
        assertNotNull(volume.name());
        assertFalse(volume.name().isBlank());
        assertTrue(volume.mountPath().startsWith("/"),
            "volume mount path must be absolute: " + app.id());
      }
      Set<String> envNames = new HashSet<>();
      for (var env : app.environment()) {
        assertTrue(ENV_NAME.matcher(env.name()).matches(), "invalid env name: " + app.id());
        assertTrue(envNames.add(env.name()), "duplicate env name: " + app.id());
      }
    }
  }

  @Test
  void deployableNowIsNeverClaimedForAppsNeedingVolumesOrSecrets() {
    for (var app : AppStoreCatalog.apps()) {
      boolean needsExtension = !app.volumes().isEmpty()
          || app.environment().stream().anyMatch(env -> env.required());
      assertFalse(app.deployableNow() && needsExtension,
          app.id() + " requires volumes/secrets but is marked one-click deployable");
    }
    assertTrue(AppStoreCatalog.apps().stream().anyMatch(AppStoreCatalog.AppStoreApp::deployableNow),
        "catalog must contain at least one immediately deployable app");
  }

  @Test
  void findReturnsMatchingAppOrEmpty() {
    assertTrue(AppStoreCatalog.find("nginx").isPresent());
    assertTrue(AppStoreCatalog.find("not-a-real-app").isEmpty());
    assertTrue(AppStoreCatalog.find(null).isEmpty());
  }
}