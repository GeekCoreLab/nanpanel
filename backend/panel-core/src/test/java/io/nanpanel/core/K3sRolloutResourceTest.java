package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class K3sRolloutResourceTest {
  @Test
  void acceptsOnlyNonDowngradeTargetsWithinOneKubernetesMinor() {
    assertTrue(K3sRolloutResource.compatibleUpgrade("v1.33.5+k3s1", "v1.33.5+k3s2"));
    assertTrue(K3sRolloutResource.compatibleUpgrade("v1.33.5+k3s1", "v1.33.6+k3s1"));
    assertTrue(K3sRolloutResource.compatibleUpgrade("v1.33.5+k3s1", "v1.34.0+k3s1"));

    assertFalse(K3sRolloutResource.compatibleUpgrade("v1.33.5+k3s2", "v1.33.5+k3s1"));
    assertFalse(K3sRolloutResource.compatibleUpgrade("v1.33.5+k3s1", "v1.33.4+k3s1"));
    assertFalse(K3sRolloutResource.compatibleUpgrade("v1.33.5+k3s1", "v1.35.0+k3s1"));
    assertFalse(K3sRolloutResource.compatibleUpgrade("unknown", "v1.34.0+k3s1"));
  }
}
