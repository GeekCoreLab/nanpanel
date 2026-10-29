package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReverseProxySitePayloadTest {

  @Test
  void slugNormalizesDomainsToBackendNames() {
    assertEquals("example-com", ReverseProxySiteResource.slug("Example.COM"));
    assertEquals("3com-example-com", ReverseProxySiteResource.slug("3com.example.com"));
    assertEquals("site", ReverseProxySiteResource.slug(""));
    assertEquals("site", ReverseProxySiteResource.slug("---"));
    String longSlug = ReverseProxySiteResource.slug("a".repeat(40) + ".example.com");
    assertTrue(longSlug.length() <= 32, longSlug);
    assertTrue(longSlug.matches("[a-z0-9][a-z0-9-]*"), longSlug);
  }

  @Test
  void payloadCompilesToTheExactNginxRouteShape() {
    JsonNode payload = ReverseProxySiteResource.payload("192.0.2.10", 80, "shop.example.com", "/", 8080);
    assertEquals(5, payload.size());
    assertEquals("192.0.2.10", payload.get("listenAddress").asText());
    assertEquals(80, payload.get("listenPort").asInt());
    assertEquals("shop.example.com", payload.get("serverName").asText());
    assertEquals("/", payload.get("healthPath").asText());
    assertEquals(1, payload.get("backends").size());
    var backend = payload.get("backends").get(0);
    assertEquals(3, backend.size());
    assertEquals("shop-example-com", backend.get("name").asText());
    assertEquals(8080, backend.get("port").asInt());
    assertEquals(100, backend.get("weight").asInt());
  }

  @Test
  void payloadPassesTheDispatchValidator() {
    var validator = new HostHelperOperationResource();
    assertDoesNotThrow(() -> validator.validateNginxRoute(
        ReverseProxySiteResource.payload("192.0.2.10", 80, "shop.example.com", "/health", 8080)));
    assertThrows(RuntimeException.class, () -> validator.validateNginxRoute(
        ReverseProxySiteResource.payload("0.0.0.0", 80, "shop.example.com", "/", 8080)));
    assertThrows(RuntimeException.class, () -> validator.validateNginxRoute(
        ReverseProxySiteResource.payload("192.0.2.10", 80, "bad_domain", "/", 8080)));
  }

  @Test
  void routeTargetMatchesTheManagedRoutePattern() {
    assertEquals("nanpanel-site-s", ReverseProxySiteResource.routeTarget(
        UUID.fromString("00000000-0000-0000-0000-000000000000")).substring(0, 15));
    String target = ReverseProxySiteResource.routeTarget(UUID.randomUUID());
    assertTrue(target.matches("nanpanel-[a-z][a-z0-9-]{0,52}"), target);
  }
}