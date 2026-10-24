package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HostApplicationReleaseServiceTest {
  private static HostApplicationReleaseService.CreateRequest request(
      String name, String stableImage, String image, String address, int batchSize, String reason) {
    return new HostApplicationReleaseService.CreateRequest(name, stableImage, image, 8080,
        "app.example.test", 18080, "/health", batchSize,
        List.of(new HostApplicationReleaseService.TargetRequest(UUID.randomUUID(), address, 2L)),
        reason);
  }

  @Test
  void acceptsAConstrainedOrderedHostRelease() {
    assertDoesNotThrow(() -> HostApplicationReleaseService.validate(request(
        "api", "registry.example/api:1.0", "registry.example/api:1.1", "192.0.2.10", 1,
        "Roll out api 1.1")));
    assertTrue(HostApplicationReleaseService.validHostAddress("2001:db8::10"));
  }

  @Test
  void rejectsUnsafeNamesImagesPortsRoutesAndBatches() {
    for (var invalid : new HostApplicationReleaseService.CreateRequest[] {
        request("API", "registry.example/api:1.0", "registry.example/api:1.1", "192.0.2.10", 1, "release"),
        request("api", "registry.example/api:1.0", "registry.example/api:1.0", "192.0.2.10", 1, "release"),
        new HostApplicationReleaseService.CreateRequest("api", "registry.example/api:1.0",
            "registry.example/api:1.1", 0, "app.example.test", 18080, "/health", 1,
            List.of(new HostApplicationReleaseService.TargetRequest(UUID.randomUUID(), "192.0.2.10", 1L)), "release"),
        new HostApplicationReleaseService.CreateRequest("api", "registry.example/api:1.0",
            "registry.example/api:1.1", 8080, "app.example.test", 80, "/health", 1,
            List.of(new HostApplicationReleaseService.TargetRequest(UUID.randomUUID(), "192.0.2.10", 1L)), "release"),
        new HostApplicationReleaseService.CreateRequest("api", "registry.example/api:1.0",
            "registry.example/api:1.1", 8080, "app.example.test", 18080, "/../etc/passwd", 1,
            List.of(new HostApplicationReleaseService.TargetRequest(UUID.randomUUID(), "192.0.2.10", 1L)), "release"),
        request("api", "registry.example/api:1.0", "registry.example/api:1.1", "192.0.2.10", 2, "release")
    }) {
      assertThrows(jakarta.ws.rs.WebApplicationException.class,
          () -> HostApplicationReleaseService.validate(invalid));
    }
  }

  @Test
  void rejectsWildcardAndNonAddressListeners() {
    assertTrue(!HostApplicationReleaseService.validHostAddress("0.0.0.0"));
    assertTrue(!HostApplicationReleaseService.validHostAddress("example.test"));
    assertTrue(!HostApplicationReleaseService.validHostAddress("::"));
    assertTrue(!HostApplicationReleaseService.validHostAddress("fe80::1%eth0"));
  }

  @Test
  void acceptsOnlyMatchingRouteWeightAndHttpHealthEvidence() throws Exception {
    var mapper = new ObjectMapper();
    var valid = mapper.readTree("""
        {"route":"nanpanel-app-a123","configSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
         "listenAddress":"192.0.2.10","listenPort":18080,"backendWeights":[["stable",0],["canary",100]],
         "routedHttpStatus":204}
        """);
    assertTrue(HostApplicationReleaseService.validRouteOperationResult(valid,
        "nanpanel-app-a123", "192.0.2.10", 18080, 0, 100));
    assertTrue(!HostApplicationReleaseService.validRouteOperationResult(valid,
        "nanpanel-app-a123", "192.0.2.10", 18080, 100, 0));
    var failedHttp = mapper.readTree("""
        {"route":"nanpanel-app-a123","configSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
         "listenAddress":"192.0.2.10","listenPort":18080,"backendWeights":[["stable",0],["canary",100]],
         "routedHttpStatus":503}
        """);
    assertTrue(!HostApplicationReleaseService.validRouteOperationResult(failedHttp,
        "nanpanel-app-a123", "192.0.2.10", 18080, 0, 100));
  }

  @Test
  void unwrapsTheAgentOperationEnvelopeBeforeCheckingRouteProof() throws Exception {
    var mapper = new ObjectMapper();
    var operationResult = mapper.readTree("""
        {"capability":"nginx.route.set","target":"nanpanel-app-a123",
         "output":"{\\"route\\":\\"nanpanel-app-a123\\",\\"configSha256\\":\\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\\",\\"listenAddress\\":\\"192.0.2.10\\",\\"listenPort\\":18080,\\"backendWeights\\":[[\\"stable\\",0],[\\"canary\\",100]],\\"routedHttpStatus\\":204}"}
        """);
    var routeResult = HostApplicationReleaseService.unwrapRouteOperationResult(operationResult, mapper);
    assertTrue(HostApplicationReleaseService.validRouteOperationResult(routeResult,
        "nanpanel-app-a123", "192.0.2.10", 18080, 0, 100));
    assertTrue(HostApplicationReleaseService.unwrapRouteOperationResult(
        mapper.readTree("{\"capability\":\"container.start\",\"target\":\"app\",\"output\":\"ok\"}"),
        mapper) == null);
  }
}
