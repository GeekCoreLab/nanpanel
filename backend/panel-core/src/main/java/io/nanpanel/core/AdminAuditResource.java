package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;
import static io.nanpanel.core.AuthResource.stamp;
import static io.nanpanel.core.AuthStore.query;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Path("/api/v1/admin/audit-events")
@Produces(MediaType.APPLICATION_JSON)
public class AdminAuditResource {
  private static final Pattern ACTION =
      Pattern.compile("[A-Za-z0-9._-]{1,100}");
  @Inject AuthStore store;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;

  private void authenticated() {
    var s = (Map<String, Object>)request.getProperty("session");
    if (s == null || s.get("user_id") == null)
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
  }

  private Map<String, Object> view(Map<String, Object> row) {
    var value = new LinkedHashMap<String, Object>();
    value.put("id", row.get("id"));
    value.put("occurredAt", stamp(row.get("occurred_at")));
    value.put("actorId", row.get("actor_id"));
    value.put("action", row.get("action"));
    value.put("resourceType", row.get("resource_type"));
    value.put("resourceId", row.get("resource_id"));
    value.put("outcome", row.get("outcome"));
    value.put("traceId", row.get("trace_id"));
    value.put("clientIp", row.get("client_ip"));
    value.put("summary", row.get("summary"));
    return value;
  }

  private String id(String value, String field) {
    if (value == null)
      return null;
    try {
      return UUID.fromString(value).toString();
    } catch (IllegalArgumentException e) {
      throw fail(400, "VALIDATION_FAILED", field + " 必须为 UUID");
    }
  }

  private Long instant(String value, String field) {
    if (value == null)
      return null;
    try {
      return OffsetDateTime.parse(value).toInstant().toEpochMilli();
    } catch (RuntimeException e) {
      try {
        return Instant.parse(value).toEpochMilli();
      } catch (RuntimeException ignored) {
        throw fail(400, "VALIDATION_FAILED", field + " 必须为 ISO-8601 时间");
      }
    }
  }

  @GET
  public Response list(@QueryParam("limit") Integer requestedLimit,
                       @QueryParam("cursor") String cursor,
                       @QueryParam("actorId") String actorId,
                       @QueryParam("action") String action,
                       @QueryParam("outcome") String outcome,
                       @QueryParam("from") String from,
                       @QueryParam("to") String to) {
    authenticated();
    int limit = requestedLimit == null ? 20 : requestedLimit;
    if (limit < 1 || limit > 100)
      throw fail(400, "VALIDATION_FAILED", "limit 须为 1–100");
    String actor = id(actorId, "actorId");
    if (action != null && !ACTION.matcher(action).matches())
      throw fail(400, "VALIDATION_FAILED", "action 格式无效");
    if (outcome != null &&
        !Set.of("SUCCEEDED", "DENIED", "FAILED", "ACCEPTED", "UNKNOWN").contains(outcome))
      throw fail(400, "VALIDATION_FAILED", "outcome 无效");
    Long startTime = instant(from, "from");
    Long endTime = instant(to, "to");
    if (startTime != null && endTime != null && startTime >= endTime)
      throw fail(400, "VALIDATION_FAILED", "from 必须早于 to");
    String afterId = null;
    if (cursor != null) {
      try {
        String decoded = new String(Base64.getUrlDecoder().decode(cursor),
                                    java.nio.charset.StandardCharsets.UTF_8);
        String[] parts = decoded.split("\\|", -1);
        if (parts.length != 6 ||
            !Objects.equals(parts[1], actor == null ? "" : actor) ||
            !Objects.equals(parts[2], action == null ? "" : action) ||
            !Objects.equals(parts[3], outcome == null ? "" : outcome) ||
            !Objects.equals(parts[4],
                            startTime == null ? "" : startTime.toString()) ||
            !Objects.equals(parts[5],
                            endTime == null ? "" : endTime.toString()))
          throw new IllegalArgumentException();
        afterId = UUID.fromString(parts[0]).toString();
      } catch (IllegalArgumentException e) {
        throw fail(400, "INVALID_CURSOR", "分页游标无效或筛选条件已变化");
      }
    }
    final String after = afterId;
    final int pageSize = limit;
    var clauses = new ArrayList<String>();
    var values = new ArrayList<Object>();
    if (actor != null) {
      clauses.add("actor_id=?");
      values.add(actor);
    }
    if (action != null) {
      clauses.add("action=?");
      values.add(action);
    }
    if (outcome != null) {
      clauses.add("outcome=?");
      values.add(outcome);
    }
    if (startTime != null) {
      clauses.add("occurred_at>=?");
      values.add(startTime);
    }
    if (endTime != null) {
      clauses.add("occurred_at<?");
      values.add(endTime);
    }
    if (after != null) {
      clauses.add("id>?");
      values.add(after);
    }
    String where =
        clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    values.add(pageSize + 1);
    return store.transaction(c -> {
      var rows =
          query(c, "SELECT * FROM audit_event" + where + " ORDER BY id LIMIT ?",
                values.toArray());
      boolean more = rows.size() > pageSize;
      var selected = rows.stream().limit(pageSize).toList();
      var body = new HashMap<String, Object>();
      body.put("items", selected.stream().map(this::view).toList());
      String next = null;
      if (more) {
        String lastId = String.valueOf(selected.getLast().get("id"));
        String payload = String.join(
            "|", lastId, actor == null ? "" : actor,
            action == null ? "" : action, outcome == null ? "" : outcome,
            startTime == null ? "" : startTime.toString(),
            endTime == null ? "" : endTime.toString());
        next = Base64.getUrlEncoder().withoutPadding().encodeToString(
            payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      body.put("nextCursor", next);
      return Response.ok(body).build();
    });
  }

  @GET
  @Path("{eventId}")
  public Object get(@PathParam("eventId") String eventId) {
    authenticated();
    String id = id(eventId, "eventId");
    return store.transaction(c -> {
      var rows = query(c, "SELECT * FROM audit_event WHERE id=?", id);
      if (rows.isEmpty())
        throw fail(404, "NOT_FOUND", "审计事件不存在");
      return view(rows.getFirst());
    });
  }
}
