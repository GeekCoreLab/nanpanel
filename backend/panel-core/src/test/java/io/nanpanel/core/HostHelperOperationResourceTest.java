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

  @Test
  void acceptsOnlyTypedIsolatedBridgeTopologyPayloads() throws Exception {
    resource.mapper = new ObjectMapper();
    String topologyId = "11111111-1111-4111-8111-111111111111";
    var valid = resource.mapper.readTree("""
        {"action":"APPLY","topologyId":"11111111-1111-4111-8111-111111111111",
         "revisionId":"22222222-2222-4222-8222-222222222222","generation":3,
         "nodes":[{"id":"33333333-3333-4333-8333-333333333333","kind":"SWITCH"},
                  {"id":"44444444-4444-4444-8444-444444444444","kind":"SWITCH"}],
         "links":[{"id":"55555555-5555-4555-8555-555555555555",
                   "sourceNodeId":"33333333-3333-4333-8333-333333333333",
                   "sourcePort":"out-1","targetNodeId":"44444444-4444-4444-8444-444444444444",
                   "targetPort":"in-1","layer":"L2","protocol":"ETHERNET"}]}
        """);
    assertDoesNotThrow(
        () -> resource.validateTargetAndPayload("network.topology.reconcile", topologyId, valid));

    var unknown = valid.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode)unknown.path("links").path(0))
        .put("command", "ip route add");
    assertThrows(jakarta.ws.rs.WebApplicationException.class,
        () -> resource.validateTargetAndPayload("network.topology.reconcile", topologyId, unknown));

    var unsupported = valid.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode)unsupported.path("nodes").path(0))
        .put("kind", "ROUTER");
    assertThrows(jakarta.ws.rs.WebApplicationException.class,
        () -> resource.validateTargetAndPayload("network.topology.reconcile", topologyId, unsupported));

    var wrongPort = valid.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode)wrongPort.path("links").path(0))
        .put("sourcePort", "out-17");
    assertThrows(jakarta.ws.rs.WebApplicationException.class,
        () -> resource.validateTargetAndPayload("network.topology.reconcile", topologyId, wrongPort));
  }

  @Test
  void acceptsExtendedContainerDeployPayloadsWithBoundedEnvironmentVolumesAndMemory() throws Exception {
    var mapper = new ObjectMapper();
    String target = "nanpanel-mysql";
    var valid = mapper.readTree("""
        {"image":"mysql:8.4","hostPort":13306,"containerPort":3306,
         "environment":["MYSQL_ROOT_PASSWORD=hunter2","MYSQL_DATABASE=app"],
         "volumes":[{"name":"data","mountPath":"/var/lib/mysql"}],
         "memoryMb":1024,"stateful":true}
        """);
    assertDoesNotThrow(() -> resource.validateTargetAndPayload("container.deploy", target, valid));

    var legacy = mapper.readTree("""
        {"image":"nginx:1.27","hostPort":18080,"containerPort":80}
        """);
    assertDoesNotThrow(() -> resource.validateTargetAndPayload("container.deploy", target, legacy));

    var badEnv = mapper.readTree("""
        {"image":"mysql:8.4","environment":["-bad=value"]}
        """);
    assertThrows(jakarta.ws.rs.WebApplicationException.class,
        () -> resource.validateTargetAndPayload("container.deploy", target, badEnv));
    var badMount = mapper.readTree("""
        {"image":"mysql:8.4","volumes":[{"name":"data","mountPath":"../etc"}]}
        """);
    assertThrows(jakarta.ws.rs.WebApplicationException.class,
        () -> resource.validateTargetAndPayload("container.deploy", target, badMount));
    var lowMemory = mapper.readTree("""
        {"image":"mysql:8.4","memoryMb":32}
        """);
    assertThrows(jakarta.ws.rs.WebApplicationException.class,
        () -> resource.validateTargetAndPayload("container.deploy", target, lowMemory));
    var unknownField = mapper.readTree("""
        {"image":"mysql:8.4","command":"id"}
        """);
    assertThrows(jakarta.ws.rs.WebApplicationException.class,
        () -> resource.validateTargetAndPayload("container.deploy", target, unknownField));
  }

  @Test
  void containerRemoveTargetsAContainerAndRejectsPayloads() throws Exception {
    assertDoesNotThrow(
        () -> resource.validateTargetAndPayload("container.remove", "nanpanel-web", null));
    assertThrows(jakarta.ws.rs.WebApplicationException.class,
        () -> resource.validateTargetAndPayload("container.remove", "bad;name", null));
    assertThrows(jakarta.ws.rs.WebApplicationException.class,
        () -> resource.validateTargetAndPayload("container.remove", "nanpanel-web",
            new ObjectMapper().readTree("""
                {"removeVolumes":true}
                """)));
  }

  @Test
  void acceptsOptionalTlsOnlyWithBoundedNamesAndBoundedPemMaterial() throws Exception {
    var mapper = new ObjectMapper();
    String target = "nanpanel-app-0123456789abcdef01234567";
    var withTls = mapper.readTree("""
        {"listenAddress":"127.0.0.1","listenPort":443,"serverName":"app.example.test",
         "healthPath":"/","backends":[{"name":"stable","port":20080,"weight":100}],
         "tls":{"certificate":"shop-example","privateKey":"shop-example"}}
        """);
    assertDoesNotThrow(() -> resource.validateTargetAndPayload("nginx.route.set", target, withTls));

    var pathTraversal = mapper.readTree("""
        {"listenAddress":"127.0.0.1","listenPort":443,"serverName":"app.example.test",
         "healthPath":"/","backends":[{"name":"stable","port":20080,"weight":100}],
         "tls":{"certificate":"../etc","privateKey":"shop-example"}}
        """);
    assertThrows(jakarta.ws.rs.WebApplicationException.class,
        () -> resource.validateTargetAndPayload("nginx.route.set", target, pathTraversal));

    var unknownField = mapper.readTree("""
        {"listenAddress":"127.0.0.1","listenPort":443,"serverName":"app.example.test",
         "healthPath":"/","backends":[{"name":"stable","port":20080,"weight":100}],
         "tls":{"certificate":"shop-example","privateKey":"shop-example"},"extra":"x"}
        """);
    assertThrows(jakarta.ws.rs.WebApplicationException.class,
        () -> resource.validateTargetAndPayload("nginx.route.set", target, unknownField));

    var tlsMaterial = mapper.createObjectNode()
        .put("certificatePem", "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----")
        .put("privateKeyPem", "-----BEGIN PRIVATE KEY-----\nMIIB\n-----END PRIVATE KEY-----");
    assertDoesNotThrow(
        () -> resource.validateTargetAndPayload("nginx.tls.install", "shop-example", tlsMaterial));
    assertThrows(jakarta.ws.rs.WebApplicationException.class,
        () -> resource.validateTargetAndPayload("nginx.tls.install", "Shop", tlsMaterial));
    var badPem = mapper.createObjectNode()
        .put("certificatePem", "not a pem")
        .put("privateKeyPem", "-----BEGIN PRIVATE KEY-----\nMIIB\n-----END PRIVATE KEY-----");
    assertThrows(jakarta.ws.rs.WebApplicationException.class,
        () -> resource.validateTargetAndPayload("nginx.tls.install", "shop-example", badPem));
  }
}
