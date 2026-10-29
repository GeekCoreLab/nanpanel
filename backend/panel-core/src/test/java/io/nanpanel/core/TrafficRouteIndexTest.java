package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class TrafficRouteIndexTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void oneBalancerDrivenByOneServiceIsIndexed() throws Exception {
    var links = MAPPER.readTree("""
        [{"type":"ROUTE_ENTRY","sourceId":"svc-stable","targetId":"lb-1","servicePort":80}]
        """);
    var entries = KubernetesApplicationTrafficRuntimeService.indexRouteEntries(links);
    assertEquals(1, entries.size());
    assertEquals("svc-stable", entries.get("lb-1").serviceId());
    assertEquals(80, entries.get("lb-1").port());
  }

  @Test
  void twoDifferentServicesDrivingOneBalancerAreRejected() throws Exception {
    var links = MAPPER.readTree("""
        [{"type":"ROUTE_ENTRY","sourceId":"svc-a","targetId":"lb-1","servicePort":80},
         {"type":"ROUTE_ENTRY","sourceId":"svc-b","targetId":"lb-1","servicePort":80}]
        """);
    var failure = assertThrows(jakarta.ws.rs.WebApplicationException.class,
        () -> KubernetesApplicationTrafficRuntimeService.indexRouteEntries(links));
    assertEquals(422, failure.getResponse().getStatus());
  }

  @Test
  void theSameServicePortOnTwoDifferentBalancersIsIndexedSeparately() throws Exception {
    var links = MAPPER.readTree("""
        [{"type":"ROUTE_ENTRY","sourceId":"svc-a","targetId":"lb-1","servicePort":80},
         {"type":"ROUTE_ENTRY","sourceId":"svc-a","targetId":"lb-2","servicePort":80}]
        """);
    assertEquals(2,
        KubernetesApplicationTrafficRuntimeService.indexRouteEntries(links).size());
  }

  @Test
  void anIdenticalDuplicateLinkIsDedupedRatherThanRejected() throws Exception {
    var links = MAPPER.readTree("""
        [{"type":"ROUTE_ENTRY","sourceId":"svc-a","targetId":"lb-1","servicePort":80},
         {"type":"ROUTE_ENTRY","sourceId":"svc-a","targetId":"lb-1","servicePort":80}]
        """);
    assertEquals(1,
        KubernetesApplicationTrafficRuntimeService.indexRouteEntries(links).size());
  }

  @Test
  void nonRouteEntryLinksAreIgnored() throws Exception {
    var links = MAPPER.readTree("""
        [{"type":"CALL","sourceId":"dep-a","targetId":"svc-a","servicePort":80},
         {"type":"ROUTE_BACKEND","sourceId":"lb-1","targetId":"svc-v1","servicePort":80}]
        """);
    assertEquals(0,
        KubernetesApplicationTrafficRuntimeService.indexRouteEntries(links).size());
  }
}
