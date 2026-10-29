package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guards the endpoint → permission table: any resource that exposes an HTTP endpoint
 * without a matching rule fails here, so the table cannot silently fall behind. Works by
 * reflecting over the compiled resource classes instead of parsing source text.
 */
class EndpointPermissionsTest {

  @Test
  void everyResourceEndpointMapsToAPermission() throws Exception {
    Set<String> endpoints = new LinkedHashSet<>();
    Set<String> missing = new LinkedHashSet<>();
    Path classes = Paths.get("target/classes/io/nanpanel/core");
    assertTrue(Files.isDirectory(classes), "compiled classes not found at " + classes.toAbsolutePath());
    try (var files = Files.list(classes)) {
      for (Path file : files.toList()) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".class") || name.contains("$")) continue;
        Class<?> type = Class.forName("io.nanpanel.core." + name.substring(0, name.length() - 6));
        var classPath = type.getAnnotation(jakarta.ws.rs.Path.class);
        if (classPath == null) continue;
        for (Method method : type.getDeclaredMethods()) {
          String verb = verbOf(method);
          if (verb == null) continue;
          var subPath = method.getAnnotation(jakarta.ws.rs.Path.class);
          String path = classPath.value()
              + (subPath == null || subPath.value().isEmpty() ? "" : "/" + subPath.value());
          path = path.replaceAll("//+", "/");
          String endpoint = verb + " " + path;
          endpoints.add(endpoint);
          if (EndpointPermissions.permissionFor(verb, path) == null) missing.add(endpoint);
        }
      }
    }
    assertTrue(endpoints.size() > 40,
        "resource discovery found too few endpoints (" + endpoints.size() + "): " + endpoints);
    assertTrue(missing.isEmpty(), "endpoints without a permission rule: " + missing);
  }

  @Test
  void methodSpecificRulesWinOverFamilyRules() {
    assertTrue("site.read".equals(EndpointPermissions.permissionFor("GET", "/api/v1/reverse-proxy-sites")));
    assertTrue("site.delete".equals(
        EndpointPermissions.permissionFor("DELETE", "/api/v1/reverse-proxy-sites/s-1")));
    assertTrue("site.apply".equals(
        EndpointPermissions.permissionFor("POST", "/api/v1/reverse-proxy-sites/s-1/tls")));
    assertTrue("operation.read".equals(EndpointPermissions.permissionFor("GET", "/api/v1/operations")));
    assertTrue("PUBLIC".equals(EndpointPermissions.permissionFor("POST", "/api/v1/auth/login")));
    assertTrue("auth.self".equals(EndpointPermissions.permissionFor("GET", "/api/v1/auth/me")));
    assertTrue(EndpointPermissions.permissionFor("GET", "/nowhere") == null);
  }

  @Test
  void everyMappedPermissionIsDeclaredForAdmin() {
    List<String> admin = RoleCatalog.role("ADMIN").orElseThrow().permissions();
    var undeclared = new LinkedHashSet<String>();
    for (EndpointPermissions.Rule rule : EndpointPermissions.rules()) {
      if (EndpointPermissions.PUBLIC.equals(rule.permission())) continue;
      if (!admin.contains(rule.permission())) undeclared.add(rule.permission());
    }
    assertTrue(undeclared.isEmpty(), "mapped permissions missing from ADMIN: " + undeclared);
  }

  private static String verbOf(Method method) {
    if (method.isAnnotationPresent(jakarta.ws.rs.GET.class)) return "GET";
    if (method.isAnnotationPresent(jakarta.ws.rs.POST.class)) return "POST";
    if (method.isAnnotationPresent(jakarta.ws.rs.PUT.class)) return "PUT";
    if (method.isAnnotationPresent(jakarta.ws.rs.DELETE.class)) return "DELETE";
    if (method.isAnnotationPresent(jakarta.ws.rs.PATCH.class)) return "PATCH";
    return null;
  }
}
