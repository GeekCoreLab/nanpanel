package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import jakarta.ws.rs.WebApplicationException;

class HostSshAccessTest {
  @Test
  void shellArgumentsAreQuotedAsSingleSafeValues() {
    assertEquals("'systemctl'", HostSshAccessResource.shellQuote("systemctl"));
    assertEquals("'a'\\''b'", HostSshAccessResource.shellQuote("a'b"));
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
