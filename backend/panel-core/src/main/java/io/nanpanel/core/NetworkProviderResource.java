package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthResource.number;
import static io.nanpanel.core.AuthStore.audit;
import static io.nanpanel.core.AuthStore.query;
import static io.nanpanel.core.AuthStore.update;

import io.nanpanel.plugin.api.NetworkProvider;
import io.nanpanel.plugin.api.NetworkProvider.ChangePlan;
import io.nanpanel.plugin.api.NetworkProvider.ValidationResult;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Context;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Path("/api/v1/network-providers")
@Produces(MediaType.APPLICATION_JSON)
public class NetworkProviderResource {
  @Inject AuthStore store;
  @Inject NetworkProviderRegistry registry;
  @Inject io.vertx.core.http.HttpServerRequest http;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;

  public record PlanRequest(String desiredSnapshot, String observedSnapshot) {}
  public record ObserveRequest(String resourceKey) {}

  private Map<String, Object> session() {
    @SuppressWarnings("unchecked")
    Map<String, Object> value = (Map<String, Object>)request.getProperty("session");
    if (value == null || value.get("user_id") == null) {
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    }
    return value;
  }

  private void recent(Map<String, Object> actor) {
    if (System.currentTimeMillis() - number(actor, "authenticated_at") > 300000) {
      store.transaction(c -> {
        audit(c, String.valueOf(actor.get("user_id")),
            "security.reauthentication.required", "network-provider", null, "DENIED",
            remoteAddress(), "网络 Provider 操作需要重新验证身份");
        return null;
      });
      throw fail(403, "REAUTHENTICATION_REQUIRED", "请重新验证密码后再继续网络变更");
    }
  }

  private String remoteAddress() {
    return http.remoteAddress() == null ? null : http.remoteAddress().host();
  }

  private NetworkProvider provider(String providerId) {
    return registry.provider(providerId)
        .orElseThrow(() -> fail(404, "PROVIDER_UNAVAILABLE", "网络 Provider 未启用或未安装"));
  }

  @GET
  public List<Map<String, Object>> providers() {
    session();
    return registry.providers().stream().map(value -> {
      Map<String, Object> result = new LinkedHashMap<>();
      var descriptor = value.descriptor();
      result.put("id", value.id());
      result.put("name", descriptor.name());
      result.put("version", descriptor.version());
      result.put("apiVersion", descriptor.apiVersion());
      result.put("capabilities", value.capabilities());
      result.put("capabilityDetails", value.capabilityDescriptors());
      return result;
    }).toList();
  }

  @POST
  @Path("{providerId}/observations")
  @Consumes(MediaType.APPLICATION_JSON)
  public Map<String, Object> observe(@PathParam("providerId") String providerId,
                                     ObserveRequest input) {
    Map<String, Object> actor = session();
    recent(actor);
    if (input == null || input.resourceKey() == null || input.resourceKey().isBlank()
        || input.resourceKey().length() > 253 || input.resourceKey().chars().anyMatch(Character::isISOControl)) {
      throw fail(400, "VALIDATION_FAILED", "Provider 观察资源标识无效");
    }
    String snapshot;
    try {
      snapshot = await(provider(providerId).observe(input.resourceKey()));
    } catch (RuntimeException failure) {
      throw fail(409, "PROVIDER_OBSERVE_FAILED", "Provider 无法读取当前外部状态");
    }
    if (snapshot == null || snapshot.length() > 65536) {
      throw fail(502, "PROVIDER_OBSERVATION_INVALID", "Provider 返回了无效或过大的观察结果");
    }
    return Map.of("providerId", providerId, "resourceKey", input.resourceKey(), "snapshot", snapshot);
  }

  @POST
  @Path("{providerId}/plans")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response plan(@PathParam("providerId") String providerId, PlanRequest input) {
    Map<String, Object> actor = session();
    recent(actor);
    if (input == null
        || input.desiredSnapshot() == null || input.desiredSnapshot().length() > 65536
        || (input.observedSnapshot() != null && input.observedSnapshot().length() > 65536)) {
      throw fail(400, "VALIDATION_FAILED", "网络 Provider 计划参数无效");
    }
    NetworkProvider provider = provider(providerId);
    ChangePlan plan;
    try {
      plan = await(provider.plan(input.desiredSnapshot(), input.observedSnapshot()));
    } catch (RuntimeException failure) {
      throw fail(409, "PROVIDER_PLAN_REJECTED", "Provider 无法为当前状态创建计划");
    }
    if (plan == null || plan.id() == null || plan.generation() < 1
        || plan.idempotencyKey() == null
        || !plan.idempotencyKey().matches("[A-Za-z0-9_-]{16,128}")
        || plan.configuration() == null || plan.configuration().length() > 65536) {
      throw fail(502, "PROVIDER_PLAN_INVALID", "Provider 返回了无效或过大的计划");
    }
    long now = System.currentTimeMillis();
    Map<String, Object> saved = store.transaction(c -> {
      var existing = query(c,
          "SELECT * FROM network_provider_operation WHERE provider_id=? AND idempotency_key=?",
          providerId, plan.idempotencyKey());
      if (!existing.isEmpty()) {
        return existing.getFirst();
      }
      update(c,
          "INSERT INTO network_provider_operation "
              + "(id,provider_id,idempotency_key,generation,state,plan_configuration,"
              + "operation_id,detail,requested_by,created_at,updated_at) "
              + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
          plan.id().toString(), providerId, plan.idempotencyKey(), plan.generation(),
          "PLANNED", plan.configuration(), null, "Plan created; no external changes applied",
          actor.get("user_id"), now, now);
      audit(c, String.valueOf(actor.get("user_id")), "network.provider.plan",
          "network-provider-operation", plan.id().toString(), "SUCCEEDED", remoteAddress(),
          "Provider " + providerId + " plan persisted");
      return query(c, "SELECT * FROM network_provider_operation WHERE id=?", plan.id().toString())
          .getFirst();
    });
    return Response.status(Response.Status.CREATED).entity(view(saved)).build();
  }

  @GET
  @Path("{providerId}/operations/{operationId}")
  public Map<String, Object> get(@PathParam("providerId") String providerId,
                                 @PathParam("operationId") String operationId) {
    session();
    return view(find(providerId, operationId));
  }

  @POST
  @Path("{providerId}/operations/{operationId}/validate")
  public Map<String, Object> validate(@PathParam("providerId") String providerId,
                                      @PathParam("operationId") String operationId) {
    Map<String, Object> actor = session();
    recent(actor);
    NetworkProvider provider = provider(providerId);
    Map<String, Object> row = find(providerId, operationId);
    requireState(row, "PLANNED");
    ChangePlan plan = plan(row);
    ValidationResult result = invoke(() -> provider.validate(plan));
    return transition(row, actor, result.successful() ? "VALIDATED" : "DRIFTED",
        result.detail(), null, "network.provider.validate",
        result.successful() ? "SUCCEEDED" : "FAILED");
  }

  @POST
  @Path("{providerId}/operations/{operationId}/apply")
  public Map<String, Object> apply(@PathParam("providerId") String providerId,
                                   @PathParam("operationId") String operationId) {
    Map<String, Object> actor = session();
    recent(actor);
    NetworkProvider provider = provider(providerId);
    Map<String, Object> row = find(providerId, operationId);
    requireState(row, "VALIDATED");
    ChangePlan plan = plan(row);
    row = transition(row, actor, "APPLYING", "Apply started; outcome will be reconciled", plan.id().toString(),
        "network.provider.apply.started", "ACCEPTED");
    try {
      UUID externalId = await(provider.apply(plan));
      if (externalId == null) {
        throw new IllegalStateException("Provider returned no operation ID");
      }
      return transition(row, actor, "APPLIED", "Provider apply returned; verification is required",
          externalId.toString(), "network.provider.apply", "SUCCEEDED");
    } catch (RuntimeException failure) {
      return transition(row, actor, "UNKNOWN",
          "Provider apply outcome is unknown; verify or compensate before retrying",
          plan.id().toString(), "network.provider.apply", "UNKNOWN");
    }
  }

  @POST
  @Path("{providerId}/operations/{operationId}/verify")
  public Map<String, Object> verify(@PathParam("providerId") String providerId,
                                    @PathParam("operationId") String operationId) {
    Map<String, Object> actor = session();
    recent(actor);
    NetworkProvider provider = provider(providerId);
    Map<String, Object> row = find(providerId, operationId);
    requireAnyState(row, "APPLYING", "APPLIED", "UNKNOWN", "VERIFIED", "DRIFTED");
    rejectRecentInFlight(row);
    ChangePlan plan = plan(row);
    UUID externalId = operationId(row, plan);
    ValidationResult restored = invoke(() -> provider.restoreOperation(plan, externalId));
    if (!restored.successful()) {
      return transition(row, actor, "DRIFTED", restored.detail(), externalId.toString(),
          "network.provider.restore", "FAILED");
    }
    ValidationResult result = invoke(() -> provider.verify(externalId));
    return transition(row, actor, result.successful() ? "VERIFIED" : "DRIFTED",
        result.detail(), externalId.toString(), "network.provider.verify",
        result.successful() ? "SUCCEEDED" : "FAILED");
  }

  @POST
  @Path("{providerId}/operations/{operationId}/compensate")
  public Map<String, Object> compensate(@PathParam("providerId") String providerId,
                                        @PathParam("operationId") String operationId) {
    Map<String, Object> actor = session();
    recent(actor);
    NetworkProvider provider = provider(providerId);
    Map<String, Object> row = find(providerId, operationId);
    requireAnyState(row, "APPLYING", "APPLIED", "UNKNOWN", "VERIFIED", "DRIFTED", "COMPENSATING");
    rejectRecentInFlight(row);
    ChangePlan plan = plan(row);
    UUID externalId = operationId(row, plan);
    row = transition(row, actor, "COMPENSATING", "Compensation started", externalId.toString(),
        "network.provider.compensate.started", "ACCEPTED");
    ValidationResult restored = invoke(() -> provider.restoreOperation(plan, externalId));
    if (!restored.successful()) {
      return transition(row, actor, "DRIFTED", restored.detail(), externalId.toString(),
          "network.provider.compensate", "FAILED");
    }
    ValidationResult result = invoke(() -> provider.compensate(externalId));
    return transition(row, actor, result.successful() ? "COMPENSATED" : "DRIFTED",
        result.detail(), externalId.toString(), "network.provider.compensate",
        result.successful() ? "SUCCEEDED" : "FAILED");
  }

  private ValidationResult invoke(java.util.function.Supplier<java.util.concurrent.CompletionStage<ValidationResult>> action) {
    try {
      ValidationResult result = await(action.get());
      if (result == null || result.detail() == null || result.detail().length() > 512) {
        return new ValidationResult(false, "Provider returned an invalid result");
      }
      return result;
    } catch (RuntimeException failure) {
      return new ValidationResult(false, "Provider operation could not be confirmed");
    }
  }

  private <T> T await(CompletionStage<T> action) {
    var future = action.toCompletableFuture();
    try {
      return future.get(30, TimeUnit.SECONDS);
    } catch (InterruptedException failure) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Provider operation was interrupted", failure);
    } catch (TimeoutException failure) {
      future.cancel(true);
      throw new IllegalStateException("Provider operation timed out", failure);
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause();
      if (cause instanceof RuntimeException runtimeFailure) {
        throw runtimeFailure;
      }
      throw new IllegalStateException("Provider operation failed", cause);
    }
  }

  private UUID operationId(Map<String, Object> row, ChangePlan plan) {
    Object stored = row.get("operation_id");
    try {
      return stored == null ? plan.id() : UUID.fromString(String.valueOf(stored));
    } catch (IllegalArgumentException failure) {
      throw fail(500, "OPERATION_CORRUPT", "网络 Provider 操作记录无效");
    }
  }

  private ChangePlan plan(Map<String, Object> row) {
    try {
      return new ChangePlan(UUID.fromString(String.valueOf(row.get("id"))),
          ((Number)row.get("generation")).longValue(),
          String.valueOf(row.get("idempotency_key")),
          String.valueOf(row.get("plan_configuration")));
    } catch (RuntimeException failure) {
      throw fail(500, "OPERATION_CORRUPT", "网络 Provider 计划记录无效");
    }
  }

  private Map<String, Object> find(String providerId, String operationId) {
    UUID id;
    try {
      id = UUID.fromString(operationId);
    } catch (RuntimeException failure) {
      throw fail(400, "VALIDATION_FAILED", "operationId 必须为 UUID");
    }
    return store.transaction(c -> {
      var rows = query(c,
          "SELECT * FROM network_provider_operation WHERE provider_id=? AND id=?",
          providerId, id.toString());
      if (rows.isEmpty()) {
        throw fail(404, "NOT_FOUND", "网络 Provider 操作不存在");
      }
      return rows.getFirst();
    });
  }

  private void requireState(Map<String, Object> row, String state) {
    requireAnyState(row, state);
  }

  private void requireAnyState(Map<String, Object> row, String... allowed) {
    String state = String.valueOf(row.get("state"));
    for (String value : allowed) {
      if (value.equals(state)) {
        return;
      }
    }
    throw fail(409, "INVALID_OPERATION_STATE", "当前 Provider 操作状态不能执行此步骤");
  }

  private void rejectRecentInFlight(Map<String, Object> row) {
    String state = String.valueOf(row.get("state"));
    if (("APPLYING".equals(state) || "COMPENSATING".equals(state))
        && System.currentTimeMillis() - ((Number)row.get("updated_at")).longValue() < 30000) {
      throw fail(409, "OPERATION_IN_PROGRESS", "Provider 操作仍在进行，请稍后读取状态");
    }
  }

  private Map<String, Object> transition(Map<String, Object> row, Map<String, Object> actor,
      String state, String detail, String externalOperationId, String action, String outcome) {
    String id = String.valueOf(row.get("id"));
    long now = System.currentTimeMillis();
    return store.transaction(c -> {
      int changed = update(c,
          "UPDATE network_provider_operation SET state=?,detail=?,operation_id=?,updated_at=? WHERE id=? AND state=?",
          state, boundedDetail(detail), externalOperationId, now, id, row.get("state"));
      if (changed != 1) {
        throw fail(409, "OPERATION_CHANGED", "Provider 操作已被其他请求更新");
      }
      audit(c, String.valueOf(actor.get("user_id")), action,
          "network-provider-operation", id, outcome, remoteAddress(), detail);
      return view(query(c, "SELECT * FROM network_provider_operation WHERE id=?", id).getFirst());
    });
  }

  private Map<String, Object> view(Map<String, Object> row) {
    var result = new LinkedHashMap<String, Object>();
    result.put("id", row.get("id"));
    result.put("providerId", row.get("provider_id"));
    result.put("generation", ((Number)row.get("generation")).longValue());
    result.put("state", row.get("state"));
    result.put("detail", row.get("detail"));
    result.put("createdAt", Instant.ofEpochMilli(((Number)row.get("created_at")).longValue()).toString());
    result.put("updatedAt", Instant.ofEpochMilli(((Number)row.get("updated_at")).longValue()).toString());
    return result;
  }

  private String boundedDetail(String detail) {
    if (detail == null || detail.isBlank()) {
      return "Provider returned no detail";
    }
    return detail.length() <= 512 ? detail : detail.substring(0, 512);
  }
}
