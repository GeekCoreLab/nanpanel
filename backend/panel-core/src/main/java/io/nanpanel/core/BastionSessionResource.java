package io.nanpanel.core;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class BastionSessionResource {
  @Inject BastionSessionService sessions;
  @Context jakarta.ws.rs.container.ContainerRequestContext request;
  @Context io.vertx.core.http.HttpServerRequest http;

  private UUID uuid(String value, String field) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
      return id;
    } catch (RuntimeException e) {
      throw AuthResource.fail(400, "VALIDATION_FAILED", field + " 必须为 UUID");
    }
  }

  private Map<String, Object> authenticated() {
    @SuppressWarnings("unchecked")
    var session = (Map<String, Object>)request.getProperty("session");
    if (session == null || session.get("user_id") == null)
      throw AuthResource.fail(401, "UNAUTHENTICATED", "请登录以继续");
    return session;
  }

  @POST
  @Path("hosts/{hostId}/ssh/sessions")
  public Response create(@PathParam("hostId") String hostValue,
                         BastionSessionService.StartRequest input) {
    UUID hostId = uuid(hostValue, "hostId");
    Map<String, Object> webSession = authenticated();
    String reason = input == null || input.reason() == null ? "" : input.reason().trim();
    int columns = input == null || input.columns() == null ? 120 : input.columns();
    int rows = input == null || input.rows() == null ? 36 : input.rows();
    if (reason.isEmpty() || reason.length() > 500 ||
        reason.chars().anyMatch(Character::isISOControl) ||
        columns < 20 || columns > 500 || rows < 5 || rows > 200)
      throw AuthResource.fail(422, "VALIDATION_FAILED", "操作原因或终端尺寸无效");
    UUID webSessionId;
    try {
      webSessionId = UUID.fromString(String.valueOf(webSession.get("id")));
    } catch (RuntimeException e) {
      throw AuthResource.fail(401, "UNAUTHENTICATED", "登录会话无效");
    }
    String clientIp = http.remoteAddress() == null ? null : http.remoteAddress().host();
    var ticket = sessions.create(hostId,
        UUID.fromString(String.valueOf(webSession.get("user_id"))), webSessionId,
        reason, columns, rows, clientIp);
    return Response.status(Response.Status.CREATED)
        .entity(Map.of("sessionId", ticket.sessionId().toString(),
            "ticket", ticket.ticket(), "expiresAt", ticket.expiresAt().toString(),
            "websocketPath", ticket.websocketPath()))
        .header("Cache-Control", "no-store").build();
  }

  @GET
  @Path("bastion-sessions")
  public Object list(@QueryParam("hostId") String hostValue,
                     @QueryParam("limit") Integer requestedLimit) {
    authenticated();
    UUID hostId = hostValue == null ? null : uuid(hostValue, "hostId");
    int limit = requestedLimit == null ? 100 : requestedLimit;
    if (limit < 1 || limit > 100)
      throw AuthResource.fail(400, "VALIDATION_FAILED", "limit 须为 1–100");
    return Map.of("items", sessions.list(hostId, limit));
  }

  @GET
  @Path("bastion-sessions/{sessionId}/commands")
  public Object commands(@PathParam("sessionId") String sessionValue) {
    Map<String, Object> session = authenticated();
    String clientIp = http.remoteAddress() == null ? null : http.remoteAddress().host();
    return Map.of("items", sessions.commands(uuid(sessionValue, "sessionId"),
        UUID.fromString(String.valueOf(session.get("user_id"))), clientIp));
  }

  @GET
  @Path("bastion-sessions/{sessionId}/recording")
  public Object recording(@PathParam("sessionId") String sessionValue) {
    Map<String, Object> session = authenticated();
    var body = new LinkedHashMap<String, Object>();
    String clientIp = http.remoteAddress() == null ? null : http.remoteAddress().host();
    body.put("items", sessions.recording(uuid(sessionValue, "sessionId"),
        UUID.fromString(String.valueOf(session.get("user_id"))), clientIp));
    body.put("encoding", "base64");
    body.put("format", "nanpanel-terminal-v1");
    return Response.ok(body).header("Cache-Control", "no-store").build();
  }
}
