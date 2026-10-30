package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The canvas compares what it draws with what the cluster was told, so the applied
 * weights must be readable from the stored snapshot without a live cluster.
 */
class AppliedRouteSnapshotTest {
  private static final String SNAPSHOT = """
      {"namespace":"demo","revisionId":"r1","generation":3,
       "routes":[
         {"name":"np-route-1-shop","resource":{"apiVersion":"networking.istio.io/v1beta1",
           "kind":"VirtualService","spec":{"hosts":["shop.demo.svc.cluster.local"],
           "gateways":["mesh"],"http":[{"name":"flow-1",
             "match":[{"port":8080,"sourceNamespace":"demo"}],
             "route":[
               {"destination":{"host":"api.demo.svc.cluster.local","port":{"number":8080}},"weight":90},
               {"destination":{"host":"web.demo.svc.cluster.local","port":{"number":8081}},"weight":10}]}]}}},
         {"name":"np-route-2-cart","resource":{"kind":"VirtualService",
           "spec":{"hosts":["cart.demo.svc.cluster.local"],"http":[]}}}
       ]}""";

  @Test
  void appliedWeightsAreReadBackFromTheStoredSnapshot() {
    List<KubernetesApplicationTrafficRuntimeService.AppliedRoute> routes =
        KubernetesApplicationTrafficRuntimeService.appliedRoutes(SNAPSHOT);
    assertEquals(2, routes.size());

    var shop = routes.get(0);
    assertEquals("np-route-1-shop", shop.name());
    assertEquals("shop", shop.service());
    assertEquals(8080, shop.port());
    assertEquals(2, shop.backends().size());
    assertEquals("api", shop.backends().get(0).service());
    assertEquals(8080, shop.backends().get(0).port());
    assertEquals(90, shop.backends().get(0).weight());
    assertEquals("web", shop.backends().get(1).service());
    assertEquals(10, shop.backends().get(1).weight());

    // A route without http rules still reports its host, with no backends.
    assertEquals("cart", routes.get(1).service());
    assertEquals(0, routes.get(1).port());
    assertTrue(routes.get(1).backends().isEmpty());
  }

  @Test
  void anEmptyOrMissingSnapshotYieldsNoRoutes() {
    assertTrue(KubernetesApplicationTrafficRuntimeService.appliedRoutes(null).isEmpty());
    assertTrue(KubernetesApplicationTrafficRuntimeService.appliedRoutes("  ").isEmpty());
    assertTrue(KubernetesApplicationTrafficRuntimeService
        .appliedRoutes("{\"routes\":[]}").isEmpty());
  }
}
