package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class HostHelperOperationResourceTest {
  private final HostHelperOperationResource resource = new HostHelperOperationResource();

  @Test
  void acceptsOnlyHttpsK3sApiOriginsWithoutCredentialBearingComponents() {
    assertTrue(resource.validK3sServerUrl("https://k3s.example.net"));
    assertTrue(resource.validK3sServerUrl("https://k3s.example.net:6443/"));

    assertFalse(resource.validK3sServerUrl("http://k3s.example.net:6443"));
    assertFalse(resource.validK3sServerUrl("https://user@k3s.example.net:6443"));
    assertFalse(resource.validK3sServerUrl("https://k3s.example.net:6443?token=secret"));
    assertFalse(resource.validK3sServerUrl("https://k3s.example.net:6443/api"));
    assertFalse(resource.validK3sServerUrl("https://localhost:6443"));
  }

  @Test
  void acceptsOnlyScopedManagedNginxRoutesAndTypedBackendWeights() throws Exception {
    var mapper = new ObjectMapper();
    String target = "nanpanel-app-0123456789abcdef01234567";
    var valid = mapper.readTree("""
        {"listenAddress":"127.0.0.1","listenPort":18080,"serverName":"app.example.test",
         "healthPath":"/health","backends":[{"name":"stable","port":20080,"weight":100}]}
        """);
    assertDoesNotThrow(() -> resource.validateTargetAndPayload("nginx.route.set", target, valid));

    var wildcard = mapper.readTree("""
        {"listenAddress":"0.0.0.0","listenPort":18080,"serverName":"app.example.test",
         "healthPath":"/health","backends":[{"name":"stable","port":20080,"weight":100}]}
        """);
    assertThrows(jakarta.ws.rs.WebApplicationException.class,
        () -> resource.validateTargetAndPayload("nginx.route.set", target, wildcard));
    var scopedIpv6 = mapper.readTree("""
        {"listenAddress":"fe80::1%eth0","listenPort":18080,"serverName":"app.example.test",
         "healthPath":"/health","backends":[{"name":"stable","port":20080,"weight":100}]}
        """);
    assertThrows(jakarta.ws.rs.WebApplicationException.class,
        () -> resource.validateTargetAndPayload("nginx.route.set", target, scopedIpv6));
    assertThrows(jakarta.ws.rs.WebApplicationException.class,
        () -> resource.validateTargetAndPayload("nginx.route.set", "customer-site", valid));
    var unbalanced = mapper.readTree("""
        {"listenAddress":"127.0.0.1","listenPort":18080,"serverName":"app.example.test",
         "healthPath":"/health","backends":[{"name":"stable","port":20080,"weight":90}]}
        """);
    assertThrows(jakarta.ws.rs.WebApplicationException.class,
        () -> resource.validateTargetAndPayload("nginx.route.set", target, unbalanced));
  }
}
