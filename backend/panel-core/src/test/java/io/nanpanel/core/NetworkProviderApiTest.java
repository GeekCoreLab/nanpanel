package io.nanpanel.core;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.common.QuarkusTestResource;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(NetworkProviderApiTest.ProviderApiProfile.class)
@QuarkusTestResource(value = P3ProviderTestResource.class, restrictToAnnotatedClass = true)
class NetworkProviderApiTest {
  public static class ProviderApiProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("quarkus.scheduler.enabled", "false");
    }
  }

  private static final String PASSWORD = "p3-provider-test-password-2026";
  @Inject AuthStore store;
  @Inject ObjectMapper mapper;
  @Inject ClusterProviderCredentialStore credentials;

  private record Session(String cookie, String csrf, String userId) {}

  @Test
  void coreRoutesPluginProtocolAndBlocksDisableUntilOperationsAreTerminal() throws Exception {
    Session session = administratorSession();

    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/network-providers")
        .then().statusCode(200).body("size()", equalTo(1))
        .body("[0].id", equalTo("p3-fixture-provider"))
        .body("[0].capabilities", hasItem("p3.fixture.tcp"))
        .body("[0].capabilityDetails[0].operations", hasItems("PLAN", "VALIDATE", "APPLY"));
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/plugins")
        .then().statusCode(200).body("[0].id", equalTo("p3-api-fixture"))
        .body("[0].state", equalTo("STARTED"))
        .body("[0].clusterProviderIds", hasItem("p3-fixture-cluster-provider"));
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/cluster-providers")
        .then().statusCode(200).body("[0].id", equalTo("p3-fixture-cluster-provider"))
        .body("[0].apiVersion", equalTo("panel.io/cluster-providers/v2"));

    UUID clusterId = UUID.randomUUID();
    Instant observedAt = Instant.now();
    store.transaction(connection -> {
      AuthStore.update(connection, "INSERT INTO kubernetes_cluster (id,name,kind,endpoint,"
          + "context_name,encrypted_kubeconfig,health_status,cloudcore_status,observed_at,"
          + "imported_by,created_at,updated_at) VALUES (?,?,?,?,?,?,'HEALTHY','DEGRADED',?,?,?,?)",
          clusterId, "provider-snapshot-test", "KUBEEDGE", "https://kubeedge.test:6443",
          "fixture", new byte[] {1}, Timestamp.from(observedAt),
          session.userId(), Timestamp.from(observedAt), Timestamp.from(observedAt));
      AuthStore.update(connection, "INSERT INTO kubernetes_cluster_node (cluster_id,node_uid,"
          + "node_name,roles,node_present,kubeedge_edge,ready,kubelet_version,container_owner,"
          + "observed_at) VALUES (?,?,?,'edge',TRUE,TRUE,TRUE,?,'KUBEEDGE',?)",
          clusterId, "fixture-node-uid", "fixture-edge-node", "v1.31.0-kubeedge.1",
          Timestamp.from(observedAt));
      return null;
    });
    String credentialPath = "/api/v1/cluster-providers/p3-fixture-cluster-provider/clusters/"
        + clusterId + "/credentials";
    String secretMaterial = "edgecore-fixture-token-0123456789";
    var credentialResponse = given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.csrf()).contentType("application/json")
        .body(Map.of("purpose", "EDGECORE_BOOTSTRAP_TOKEN", "material", secretMaterial))
        .post(credentialPath)
        .then().statusCode(201).body("purpose", equalTo("EDGECORE_BOOTSTRAP_TOKEN"))
        .extract();
    String credentialId = credentialResponse.path("id");
    assertFalse(credentialResponse.asString().contains(secretMaterial));
    store.transaction(connection -> {
      byte[] encrypted = (byte[])AuthStore.query(connection,
          "SELECT encrypted_material FROM cluster_provider_credential WHERE id=?",
          UUID.fromString(credentialId)).getFirst().get("encrypted_material");
      assertFalse(new String(encrypted, java.nio.charset.StandardCharsets.ISO_8859_1)
          .contains(secretMaterial));
      return null;
    });
    assertEquals(secretMaterial, credentials.open(UUID.fromString(credentialId), clusterId,
        "p3-fixture-cluster-provider", "EDGECORE_BOOTSTRAP_TOKEN"));
    store.transaction(connection -> {
      AuthStore.update(connection,
          "UPDATE cluster_provider_credential SET expires_at=? WHERE id=?",
          System.currentTimeMillis() - 1, UUID.fromString(credentialId));
      return null;
    });
    assertThrows(IllegalArgumentException.class, () -> credentials.open(UUID.fromString(credentialId),
        clusterId, "p3-fixture-cluster-provider", "EDGECORE_BOOTSTRAP_TOKEN"));
    store.transaction(connection -> {
      AuthStore.update(connection,
          "UPDATE cluster_provider_credential SET expires_at=NULL WHERE id=?",
          UUID.fromString(credentialId));
      return null;
    });
    var metadata = given().cookie("nanpanel_session", session.cookie())
        .get(credentialPath).then().statusCode(200).extract().asString();
    assertFalse(metadata.contains(secretMaterial));
    assertFalse(metadata.contains("encrypted_material"));
    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .delete(credentialPath + "/" + credentialId).then().statusCode(204);
    assertThrows(IllegalArgumentException.class, () -> credentials.open(UUID.fromString(credentialId),
        clusterId, "p3-fixture-cluster-provider", "EDGECORE_BOOTSTRAP_TOKEN"));
    var planCredential = given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.csrf()).contentType("application/json")
        .body(Map.of("purpose", "EDGECORE_BOOTSTRAP_TOKEN", "material", secretMaterial))
        .post(credentialPath).then().statusCode(201).extract();
    String planCredentialId = planCredential.path("id");
    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .header("Idempotency-Key", "cloudcore-plan-invalid")
        .contentType("application/json").body(Map.of("operation", "install_cloudcore",
            "component", "cloudcore", "targetVersion", "v1.21.0",
            "credentialReference", credentialId,
            "reason", "Reject an unavailable credential reference"))
        .post("/api/v1/cluster-providers/p3-fixture-cluster-provider/clusters/" + clusterId + "/plans")
        .then().statusCode(409).body("code", equalTo("CLUSTER_CREDENTIAL_UNAVAILABLE"));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.csrf())
        .post("/api/v1/cluster-providers/p3-fixture-cluster-provider/clusters/" + clusterId
            + "/observations")
        .then().statusCode(200).body("clusterId", equalTo(clusterId.toString()))
        .body("status", equalTo("HEALTHY"))
        .body("fingerprint", equalTo("DEGRADED"))
        .body("conditions", hasItems("nodes=1", "edgeNodes=1"));
    Map<String, String> lifecycleRequest = Map.of("operation", "install_cloudcore",
        "component", "cloudcore", "targetVersion", "v1.21.0",
        "credentialReference", planCredentialId,
        "reason", "Create an isolated provider plan fixture");
    String clusterOperations = "/api/v1/cluster-providers/p3-fixture-cluster-provider/clusters/"
        + clusterId + "/plans";
    var wrongPurposeCredential = given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.csrf()).contentType("application/json")
        .body(Map.of("purpose", "CLOUDCORE_BOOTSTRAP_TOKEN", "material", secretMaterial))
        .post(credentialPath).then().statusCode(201).extract();
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", "cloudcore-plan-wrong-purpose")
        .contentType("application/json").body(Map.of("operation", "install_cloudcore",
            "component", "cloudcore", "targetVersion", "v1.21.0",
            "credentialReference", wrongPurposeCredential.path("id"),
            "reason", "Reject a provider plan with the wrong credential purpose"))
        .post(clusterOperations).then().statusCode(502)
        .body("code", equalTo("CLUSTER_PROVIDER_PLAN_INVALID"));
    var clusterPlan = given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", "cloudcore-plan-0001")
        .contentType("application/json").body(lifecycleRequest)
        .post(clusterOperations)
        .then().statusCode(201).body("providerId", equalTo("p3-fixture-cluster-provider"))
        .body("clusterId", equalTo(clusterId.toString())).body("operation", equalTo("install_cloudcore"))
        .body("state", equalTo("PLANNED")).extract();
    String clusterOperationId = clusterPlan.path("id");
    String persistedPlan = store.transaction(connection -> {
      Object stored = AuthStore.query(connection,
        "SELECT plan_configuration FROM cluster_provider_operation WHERE id=?",
        UUID.fromString(clusterOperationId)).getFirst().get("plan_configuration");
      return stored instanceof java.sql.Clob clob
          ? clob.getSubString(1, Math.toIntExact(clob.length())) : stored.toString();
    });
    var restoredPlan = mapper.readValue(persistedPlan,
        io.nanpanel.plugin.api.ClusterProvider.ClusterPlan.class);
    assertEquals(List.of(UUID.fromString(planCredentialId)),
        restoredPlan.steps().getFirst().credentialReferences());
    assertFalse(persistedPlan.contains(secretMaterial));
    var duplicateClusterPlan = given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", "cloudcore-plan-0001")
        .contentType("application/json").body(lifecycleRequest)
        .post(clusterOperations)
        .then().statusCode(201).extract();
    assertEquals(clusterOperationId, duplicateClusterPlan.path("id"));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.csrf())
        .post("/api/v1/cluster-providers/p3-fixture-cluster-provider/operations/"
            + clusterOperationId + "/validate")
        .then().statusCode(200).body("state", equalTo("VALIDATED"));
    assertTrue(persistedPlan.contains("kubeedge.cloudcore.install"));
    assertTrue(!persistedPlan.contains("token"));
    UUID edgeHostId = UUID.randomUUID();
    var edgeCredential = given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.csrf()).contentType("application/json")
        .body(Map.of("purpose", "EDGECORE_BOOTSTRAP_TOKEN", "material", secretMaterial))
        .post(credentialPath).then().statusCode(201).extract();
    store.transaction(connection -> {
      AuthStore.update(connection,
          "INSERT INTO host (id,hostname,capabilities,generation,observed_generation,status) "
              + "VALUES (?,?, '[]',1,1,'ONLINE')",
          edgeHostId, "provider-edge-fixture-" + edgeHostId.toString().substring(0, 8));
      AuthStore.update(connection, "UPDATE kubernetes_cluster SET cloudcore_status='HEALTHY' "
          + "WHERE id=?", clusterId);
      return null;
    });
    Map<String, String> edgeLifecycleRequest = Map.of("operation", "join_edgecore",
        "component", "edgecore", "hostId", edgeHostId.toString(), "targetVersion", "v1.21.0",
        "targetEndpoint", "cloudcore.example.com:10000",
        "credentialReference", edgeCredential.path("id"),
        "reason", "Persist an isolated EdgeCore join plan fixture");
    var edgePlanResponse = given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", "edgecore-plan-0001")
        .contentType("application/json").body(edgeLifecycleRequest).post(clusterOperations)
        .then().statusCode(201).body("operation", equalTo("join_edgecore"))
        .body("state", equalTo("PLANNED")).extract();
    String edgeOperationId = edgePlanResponse.path("id");
    String persistedEdgePlan = store.transaction(connection -> {
      Object stored = AuthStore.query(connection,
          "SELECT plan_configuration FROM cluster_provider_operation WHERE id=?",
          UUID.fromString(edgeOperationId)).getFirst().get("plan_configuration");
      return stored instanceof java.sql.Clob clob
          ? clob.getSubString(1, Math.toIntExact(clob.length())) : stored.toString();
    });
    assertTrue(persistedEdgePlan.contains(edgeHostId.toString()));
    assertTrue(persistedEdgePlan.contains("cloudcore.example.com:10000"));
    assertTrue(persistedEdgePlan.contains(edgeCredential.path("id")));
    assertFalse(persistedEdgePlan.contains(secretMaterial));
    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .post("/api/v1/cluster-providers/p3-fixture-cluster-provider/operations/"
            + edgeOperationId + "/validate")
        .then().statusCode(200).body("state", equalTo("VALIDATED"));
    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .post("/api/v1/cluster-providers/p3-fixture-cluster-provider/operations/"
            + edgeOperationId + "/execute")
        .then().statusCode(409).body("code", equalTo("CLUSTER_PROVIDER_EXECUTION_UNSUPPORTED"));
    UUID hostOperationId = UUID.randomUUID();
    UUID competingHostId = UUID.randomUUID();
    String edgeHostname = "provider-edge-fixture-" + edgeHostId.toString().substring(0, 8);
    Instant helperFinishedAt = Instant.now();
    store.transaction(connection -> {
      AuthStore.update(connection,
          "INSERT INTO host (id,hostname,capabilities,generation,observed_generation,status) "
              + "VALUES (?,?, '[]',1,1,'ONLINE')",
          competingHostId, "provider-edge-conflict-" + competingHostId.toString().substring(0, 8));
      AuthStore.update(connection,
          "INSERT INTO operation (id,idempotency_key,phase,status,error,created_at,updated_at,"
              + "kind,host_id,requested_by,reason,capability,target,finished_at) "
              + "VALUES (?,?,'COMMIT','RUNNING',NULL,?,?,?,?,?,?,?,?,NULL)",
          hostOperationId, "kubeedge-fixture-host-operation", Timestamp.from(Instant.now()),
          Timestamp.from(Instant.now()), "HOST_HELPER_OPERATION", edgeHostId, session.userId(),
          "test KubeEdge plan reconciliation", "kubeedge.edgecore.join", "");
      AuthStore.update(connection,
          "UPDATE cluster_provider_operation SET provider_id='kubeedge-core',state='EXECUTING',"
              + "host_operation_id=?,detail='EdgeCore dispatch fixture' WHERE id=?",
          hostOperationId, UUID.fromString(edgeOperationId));
      return null;
    });
    String reconciledPlan = "/api/v1/cluster-providers/kubeedge-core/operations/"
        + edgeOperationId;
    given().cookie("nanpanel_session", session.cookie()).get(reconciledPlan)
        .then().statusCode(200).body("state", equalTo("EXECUTING"))
        .body("hostOperationId", equalTo(hostOperationId.toString()));
    store.transaction(connection -> {
      AuthStore.update(connection,
          "UPDATE operation SET status='SUCCEEDED',finished_at=?,updated_at=? WHERE id=?",
          Timestamp.from(helperFinishedAt), Timestamp.from(helperFinishedAt), hostOperationId);
      return null;
    });
    given().cookie("nanpanel_session", session.cookie()).get(reconciledPlan)
        .then().statusCode(200).body("state", equalTo("AWAITING_NODE_REGISTRATION"));
    store.transaction(connection -> {
      AuthStore.update(connection,
          "UPDATE kubernetes_cluster_node SET node_name=?,host_id=?,observed_at=? "
              + "WHERE cluster_id=? AND node_uid=?",
          edgeHostname, competingHostId, Timestamp.from(helperFinishedAt.plusMillis(1)), clusterId,
          "fixture-node-uid");
      return null;
    });
    given().cookie("nanpanel_session", session.cookie()).get(reconciledPlan)
        .then().statusCode(200).body("state", equalTo("AWAITING_NODE_REGISTRATION"));
    assertEquals(competingHostId, store.transaction(connection -> AuthStore.query(connection,
        "SELECT host_id FROM kubernetes_cluster_node WHERE cluster_id=? AND node_uid=?",
        clusterId, "fixture-node-uid").getFirst().get("host_id")));
    store.transaction(connection -> {
      AuthStore.update(connection,
          "UPDATE kubernetes_cluster_node SET host_id=NULL,observed_at=? "
              + "WHERE cluster_id=? AND node_uid=?",
          Timestamp.from(helperFinishedAt.plusMillis(2)), clusterId, "fixture-node-uid");
      return null;
    });
    given().cookie("nanpanel_session", session.cookie()).get(reconciledPlan)
        .then().statusCode(200).body("state", equalTo("SUCCEEDED"));
    assertEquals(edgeHostId, store.transaction(connection -> AuthStore.query(connection,
        "SELECT host_id FROM kubernetes_cluster_node WHERE cluster_id=? AND node_uid=?",
        clusterId, "fixture-node-uid").getFirst().get("host_id")));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", "edgecore-plan-0002")
        .contentType("application/json").body(edgeLifecycleRequest).post(clusterOperations)
        .then().statusCode(409).body("code", equalTo("CLUSTER_PROVIDER_TARGET_ALREADY_REGISTERED"));
    var revocationCredential = given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.csrf()).contentType("application/json")
        .body(Map.of("purpose", "EDGECORE_BOOTSTRAP_TOKEN", "material", secretMaterial))
        .post(credentialPath).then().statusCode(201).extract();
    String revocationCredentialId = revocationCredential.path("id");
    String referencedPlanId = given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", "cloudcore-plan-revoked")
        .contentType("application/json").body(Map.of("operation", "install_cloudcore",
            "component", "cloudcore", "targetVersion", "v1.23.0",
            "credentialReference", revocationCredentialId,
            "reason", "Check revocation before plan validation"))
        .post(clusterOperations).then().statusCode(201).body("state", equalTo("PLANNED"))
        .extract().path("id");
    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .delete(credentialPath + "/" + revocationCredentialId).then().statusCode(204);
    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .post("/api/v1/cluster-providers/p3-fixture-cluster-provider/operations/"
            + referencedPlanId + "/validate")
        .then().statusCode(200).body("state", equalTo("DRIFTED"));
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", "cloudcore-plan-0001")
        .contentType("application/json")
        .body(Map.of("operation", "install_cloudcore", "component", "cloudcore",
            "targetVersion", "v1.22.0", "reason", "Create a conflicting idempotency request"))
        .post(clusterOperations)
        .then().statusCode(409).body("code", equalTo("IDEMPOTENCY_KEY_REUSED"));
    String stalePlanId = given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.csrf()).header("Idempotency-Key", "cloudcore-plan-0002")
        .contentType("application/json")
        .body(Map.of("operation", "install_cloudcore", "component", "cloudcore",
            "targetVersion", "v1.22.0", "reason", "Check inventory drift before validation"))
        .post(clusterOperations)
        .then().statusCode(201).body("state", equalTo("PLANNED")).extract().path("id");
    store.transaction(connection -> {
      AuthStore.update(connection, "UPDATE kubernetes_cluster SET updated_at=? WHERE id=?",
          Timestamp.from(observedAt.plusSeconds(1)), clusterId);
      return null;
    });
    given().cookie("nanpanel_session", session.cookie())
        .header("X-CSRF-Token", session.csrf())
        .post("/api/v1/cluster-providers/p3-fixture-cluster-provider/operations/"
            + stalePlanId + "/validate")
        .then().statusCode(200).body("state", equalTo("DRIFTED"));
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/config-translators")
        .then().statusCode(200).body("[0].id", equalTo("p3-fixture-translator"))
        .body("[0].sourceFormats", hasItem("fixture"));

    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .contentType("application/json").body(Map.of("resourceKey", "fixture-route"))
        .post("/api/v1/network-providers/p3-fixture-provider/observations")
        .then().statusCode(200).body("providerId", equalTo("p3-fixture-provider"))
        .body("snapshot", equalTo("observed:fixture-route"));

    Map<String, String> translation = Map.of(
        "sourceFormat", "fixture", "snapshot", "manual-setting=kept", "targetFormat", "normalized");
    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .contentType("application/json").body(translation)
        .post("/api/v1/config-translators/p3-fixture-translator/normalize")
        .then().statusCode(200).body("snapshot", equalTo("manual-setting=kept"))
        .body("preservedFields", hasItem("manual-setting"));
    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .contentType("application/json").body(translation)
        .post("/api/v1/config-translators/p3-fixture-translator/render")
        .then().statusCode(200).body("snapshot", equalTo("rendered:manual-setting=kept"));

    Map<String, String> request = Map.of("desiredSnapshot", "fixture-route=8080",
        "observedSnapshot", "fixture-route=old");
    var created = given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .contentType("application/json").body(request)
        .post("/api/v1/network-providers/p3-fixture-provider/plans")
        .then().statusCode(201).body("providerId", equalTo("p3-fixture-provider"))
        .body("state", equalTo("PLANNED")).extract();
    String operationId = created.path("id");
    var duplicate = given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .contentType("application/json").body(request)
        .post("/api/v1/network-providers/p3-fixture-provider/plans")
        .then().statusCode(201).extract();
    assertEquals(operationId, duplicate.path("id"));

    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .contentType("application/json").body(Map.of("enabled", false))
        .put("/api/v1/plugins/p3-api-fixture/enabled")
        .then().statusCode(409).body("code", equalTo("PROVIDER_OPERATIONS_OPEN"));
    given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/plugins")
        .then().statusCode(200).body("[0].state", equalTo("STARTED"));

    String operations = "/api/v1/network-providers/p3-fixture-provider/operations/" + operationId;
    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .post(operations + "/validate")
        .then().statusCode(200).body("state", equalTo("VALIDATED"));
    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .post(operations + "/apply")
        .then().statusCode(200).body("state", equalTo("APPLIED"));
    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .post(operations + "/verify")
        .then().statusCode(200).body("state", equalTo("VERIFIED"));
    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .post(operations + "/compensate")
        .then().statusCode(200).body("state", equalTo("COMPENSATED"));
    given().cookie("nanpanel_session", session.cookie())
        .get(operations)
        .then().statusCode(200).body("state", equalTo("COMPENSATED"));

    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .contentType("application/json").body(Map.of("enabled", false))
        .put("/api/v1/plugins/p3-api-fixture/enabled")
        .then().statusCode(200).body("state", equalTo("DISABLED"));
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/network-providers")
        .then().statusCode(200).body("size()", equalTo(0));
    given().cookie("nanpanel_session", session.cookie()).header("X-CSRF-Token", session.csrf())
        .contentType("application/json").body(Map.of("enabled", true))
        .put("/api/v1/plugins/p3-api-fixture/enabled")
        .then().statusCode(200).body("state", equalTo("STARTED"));
    given().cookie("nanpanel_session", session.cookie()).get("/api/v1/network-providers")
        .then().statusCode(200).body("[0].id", equalTo("p3-fixture-provider"));

    var audit = given().cookie("nanpanel_session", session.cookie())
        .get("/api/v1/admin/audit-events?limit=100")
        .then().statusCode(200).extract();
    var actions = audit.jsonPath().getList("items.action", String.class);
    assertTrue(actions.containsAll(java.util.List.of(
        "network.provider.plan", "network.provider.validate", "network.provider.apply",
        "network.provider.verify", "network.provider.compensate", "plugin.disabled",
        "plugin.enabled")));
  }

  private static Session administratorSession() {
    var preLogin = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
    var initial = login(preLogin.cookie("nanpanel_session"), preLogin.path("csrfToken"),
        "test-only-password");
    if (Boolean.TRUE.equals(initial.path("user.mustChangePassword"))) {
      given().cookie("nanpanel_session", initial.cookie("nanpanel_session"))
          .header("X-CSRF-Token", initial.path("csrfToken"))
          .contentType("application/json")
          .body(Map.of("currentPassword", "test-only-password", "newPassword", PASSWORD))
          .put("/api/v1/auth/password").then().statusCode(204);
      var next = given().get("/api/v1/auth/csrf").then().statusCode(200).extract();
      initial = login(next.cookie("nanpanel_session"), next.path("csrfToken"), PASSWORD);
    }
    return new Session(initial.cookie("nanpanel_session"), initial.path("csrfToken"),
        initial.path("user.id"));
  }

  private static ExtractableResponse<Response> login(String cookie, String csrf, String password) {
    return given().cookie("nanpanel_session", cookie).header("X-CSRF-Token", csrf)
        .contentType("application/json")
        .body(Map.of("username", "admin", "password", password))
        .post("/api/v1/auth/login").then().statusCode(200).extract();
  }
}
