package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import jakarta.ws.rs.WebApplicationException;

class HostSshAccessTest {
  @Test
  void parsesHostBoundRootManagedAliases() {
    UUID firstHost = UUID.fromString("11b10f66-7329-4dbf-82fd-4a76f5022601");
    UUID secondHost = UUID.fromString("db3ca2c2-4201-42a5-8ddb-74bf96c75802");
    assertEquals(Map.of(firstHost, "host-one", secondHost, "homelab_2"),
        HostSshAccessResource.parseAliases("# configured by root\n" + firstHost + " host-one\n" +
            secondHost + " homelab_2\n"));
  }

  @Test
  void rejectsMalformedDuplicateOrUnsafeAliasBindings() {
    String host = "11b10f66-7329-4dbf-82fd-4a76f5022601";
    String otherHost = "db3ca2c2-4201-42a5-8ddb-74bf96c75802";
    assertEquals(Map.of(), HostSshAccessResource.parseAliases(host + " host-one extra\n"));
    assertEquals(Map.of(), HostSshAccessResource.parseAliases(host + " -oProxyCommand=touch\n"));
    assertEquals(Map.of(), HostSshAccessResource.parseAliases(host + " host-one\n" + host + " host-two\n"));
    assertEquals(Map.of(), HostSshAccessResource.parseAliases(host + " host-one\n" + otherHost + " host-one\n"));
    assertEquals(Map.of(), HostSshAccessResource.parseAliases("not-a-host host-one\n"));
  }

  @Test
  void parsesHostBoundSshServiceAllowlistWithNoImplicitDefaults() {
    UUID firstHost = UUID.fromString("11b10f66-7329-4dbf-82fd-4a76f5022601");
    UUID secondHost = UUID.fromString("db3ca2c2-4201-42a5-8ddb-74bf96c75802");
    assertEquals(Map.of(firstHost, Set.of("nanpanel-agent.service", "ssh.service"),
        secondHost, Set.of("nginx.service")),
        HostSshAccessResource.parseSshUnits(
            "# configured by root\n" + firstHost + " nanpanel-agent.service\n" +
                firstHost + " ssh.service\n" + secondHost + " nginx.service\n"));
    assertEquals(Map.of(), HostSshAccessResource.parseSshUnits(""));
  }

  @Test
  void rejectsMalformedDuplicateOrCommandLikeSshServiceAllowlistEntries() {
    String host = "11b10f66-7329-4dbf-82fd-4a76f5022601";
    assertEquals(Map.of(), HostSshAccessResource.parseSshUnits(host + " ssh.service;id\n"));
    assertEquals(Map.of(), HostSshAccessResource.parseSshUnits(host + " ssh.service extra\n"));
    assertEquals(Map.of(), HostSshAccessResource.parseSshUnits(
        host + " ssh.service\n" + host + " ssh.service\n"));
    assertEquals(Map.of(), HostSshAccessResource.parseSshUnits("not-a-host ssh.service\n"));
  }

  @Test
  void acceptsOnlyAnHttpsControllerOriginForRemoteBootstrap() {
    assertEquals("https://panel.example.test:8443",
        HostSshAccessResource.trustedHttpsOrigin("https://panel.example.test:8443/").toString());
    for (String value : new String[] {"disabled", "http://panel.example.test", "https://user@panel.example.test",
        "https://panel.example.test/path", "https://panel.example.test?next=other"}) {
      var error = assertThrows(WebApplicationException.class,
          () -> HostSshAccessResource.trustedHttpsOrigin(value));
      assertEquals(503, error.getResponse().getStatus());
    }
  }
}
