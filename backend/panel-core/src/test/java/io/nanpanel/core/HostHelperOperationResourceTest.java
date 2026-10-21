package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
