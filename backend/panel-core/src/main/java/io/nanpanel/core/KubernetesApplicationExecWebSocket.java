package io.nanpanel.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@WebSocket(path = "/api/v1/kubernetes/applications/exec-sessions/{sessionId}/terminal")
@ApplicationScoped
public class KubernetesApplicationExecWebSocket {
  private static final Logger LOG = Logger.getLogger(KubernetesApplicationExecWebSocket.class);
  private static final int MAX_MESSAGE_CHARS = 16 * 1024;
  private static final int MAX_INPUT_BYTES = 8 * 1024;
  private static final int MAX_ACTIVE_SESSIONS = 20;
  private static final Duration MAX_SESSION_AGE = Duration.ofHours(2);
  private static final Duration MAX_IDLE = Duration.ofMinutes(15);

  @Inject ObjectMapper mapper;
  @Inject KubernetesApplicationService applications;

  @Inject
  @ConfigProperty(name = "nanpanel.auth.origin", defaultValue = "https://localhost")
  String trustedOrigin;

  private final Map<String, Terminal> active = new ConcurrentHashMap<>();

  @OnOpen
  void onOpen(WebSocketConnection connection) {
    String origin = connection.handshakeRequest().header("Origin");
    if (origin == null || !trustedOrigin.equals(origin)) {
      connection.closeAndAwait(new CloseReason(1008, "untrusted origin"));
    }
  }

  @OnTextMessage
  @RunOnVirtualThread
  void onMessage(String message, WebSocketConnection connection) {
    if (message == null || message.length() > MAX_MESSAGE_CHARS) {
      connection.closeAndAwait(new CloseReason(1009, "message too large"));
      return;
    }
    JsonNode event;
    try {
      event = mapper.readTree(message);
    } catch (Exception malformed) {
      connection.closeAndAwait(new CloseReason(1003, "invalid message"));
      return;
    }
    if (event == null || !event.isObject() || !event.path("type").isTextual()) {
      connection.closeAndAwait(new CloseReason(1003, "invalid message"));
      return;
    }
    String type = event.path("type").asText();
    if ("auth".equals(type)) {
      authenticate(connection, event);
      return;
    }
    Terminal terminal = active.get(connection.id());
    if (terminal == null) {
      connection.closeAndAwait(new CloseReason(1008, "authentication required"));
      return;
    }
    terminal.touch();
    try {
      switch (type) {
        case "input" -> input(terminal, event.path("data").asText());
        case "resize" -> resize(terminal.watch, event.path("columns").asInt(),
            event.path("rows").asInt());
        case "keepalive" -> send(connection, Map.of("type", "keepalive"));
        case "close" -> closeTerminal(connection, terminal, "client closed");
        default -> connection.closeAndAwait(new CloseReason(1003, "unknown message type"));
      }
    } catch (Exception failure) {
      LOG.warnf(failure, "Kubernetes application exec event failed for application %s",
          terminal.grant.target().applicationId());
      closeTerminal(connection, terminal, "terminal IO failed");
    }
  }

  @OnClose
  void onClose(WebSocketConnection connection, CloseReason reason) {
    Terminal terminal = active.remove(connection.id());
    if (terminal != null) terminal.close("CLOSED");
  }

  private void authenticate(WebSocketConnection connection, JsonNode event) {
    UUID sessionId;
    try {
      sessionId = UUID.fromString(connection.pathParam("sessionId"));
    } catch (RuntimeException invalid) {
      connection.closeAndAwait(new CloseReason(1008, "invalid session"));
      return;
    }
    String ticket = event.path("ticket").asText("");
    int columns = event.path("columns").asInt(120);
    int rows = event.path("rows").asInt(36);
    if (!ticket.matches("[A-Za-z0-9_-]{40,64}") || columns < 20 || columns > 500
        || rows < 5 || rows > 200 || active.containsKey(connection.id())) {
      connection.closeAndAwait(new CloseReason(1008, "invalid ticket or terminal size"));
      return;
    }
    KubernetesApplicationService.ExecGrant grant =
        applications.consumeExecTicket(sessionId, ticket);
    if (grant == null) {
      connection.closeAndAwait(new CloseReason(1008, "ticket expired or already used"));
      return;
    }
    if (active.size() >= MAX_ACTIVE_SESSIONS) {
      applications.finishExec(grant, "REJECTED_CAPACITY");
      connection.closeAndAwait(new CloseReason(1013, "too many active exec sessions"));
      return;
    }
    KubernetesApplicationService.ExecConnection exec = null;
    Terminal terminal = null;
    try {
      exec = applications.openExec(grant, columns, rows);
      terminal = new Terminal(grant, exec, connection);
      active.put(connection.id(), terminal);
      send(connection, Map.of("type", "ready", "sessionId", sessionId.toString()));
      Terminal opened = terminal;
      opened.reader = Thread.ofVirtual().name("nanpanel-app-exec-" + sessionId)
          .start(() -> readOutput(opened));
      opened.monitor = Thread.ofVirtual().name("nanpanel-app-exec-timeout-" + sessionId)
          .start(() -> monitor(opened));
    } catch (Exception failure) {
      LOG.warnf(failure, "Kubernetes application exec setup failed for application %s",
          grant.target().applicationId());
      if (terminal != null) {
        active.remove(connection.id(), terminal);
        terminal.close("FAILED");
      } else if (exec != null) {
        exec.close();
        applications.finishExec(grant, "FAILED");
      } else {
        applications.finishExec(grant, "FAILED");
      }
      try {
        send(connection, Map.of("type", "error",
            "message", failure instanceof KubernetesApplicationService.ApplicationException
                ? failure.getMessage() : "无法打开目标容器的交互式 Shell"));
      } catch (Exception ignored) {
        // The socket can already have closed during setup failure.
      }
      connection.closeAndAwait(new CloseReason(1011, "exec setup failed"));
    }
  }

  private void input(Terminal terminal, String data) throws Exception {
    byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_INPUT_BYTES) {
      terminal.connection.closeAndAwait(new CloseReason(1009, "input too large"));
      return;
    }
    synchronized (terminal) {
      terminal.watch.getInput().write(bytes);
      terminal.watch.getInput().flush();
    }
  }

  private void resize(ExecWatch watch, int columns, int rows) {
    if (columns < 20 || columns > 500 || rows < 5 || rows > 200) {
      throw new IllegalArgumentException("Invalid terminal size");
    }
    watch.resize(columns, rows);
  }

  private void readOutput(Terminal terminal) {
    try (InputStream output = terminal.watch.getOutput()) {
      byte[] buffer = new byte[4096];
      int read;
      while ((read = output.read(buffer)) >= 0) {
        if (read == 0) continue;
        terminal.touch();
        byte[] payload = java.util.Arrays.copyOf(buffer, read);
        send(terminal.connection, Map.of("type", "output",
            "data", Base64.getEncoder().encodeToString(payload)));
      }
      terminal.close("CLOSED");
      terminal.connection.closeAndAwait(new CloseReason(1000, "exec ended"));
    } catch (Exception failure) {
      if (!terminal.closed.get()) {
        LOG.debugf("Kubernetes application exec output ended for application %s (%s)",
            terminal.grant.target().applicationId(), failure.getClass().getSimpleName());
        terminal.close("FAILED");
        terminal.connection.closeAndAwait(new CloseReason(1011, "exec output failed"));
      }
    }
  }

  private void monitor(Terminal terminal) {
    while (!terminal.closed.get()) {
      try {
        Thread.sleep(30_000);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
      Instant now = Instant.now();
      if (Duration.between(terminal.openedAt, now).compareTo(MAX_SESSION_AGE) >= 0
          || Duration.between(terminal.lastActivity, now).compareTo(MAX_IDLE) >= 0) {
        closeTerminal(terminal.connection, terminal, "session timeout");
      }
    }
  }

  private void closeTerminal(WebSocketConnection connection, Terminal terminal, String reason) {
    active.remove(connection.id(), terminal);
    terminal.close("CLOSED");
    connection.closeAndAwait(new CloseReason(1000, reason));
  }

  private void send(WebSocketConnection connection, Object value) throws Exception {
    connection.sendTextAndAwait(mapper.writeValueAsString(value));
  }

  private final class Terminal {
    final KubernetesApplicationService.ExecGrant grant;
    final KubernetesApplicationService.ExecConnection exec;
    final ExecWatch watch;
    final WebSocketConnection connection;
    final AtomicBoolean closed = new AtomicBoolean();
    final Instant openedAt = Instant.now();
    volatile Instant lastActivity = openedAt;
    Thread reader;
    Thread monitor;

    Terminal(KubernetesApplicationService.ExecGrant grant,
        KubernetesApplicationService.ExecConnection exec, WebSocketConnection connection) {
      this.grant = grant;
      this.exec = exec;
      this.watch = exec.watch();
      this.connection = connection;
    }

    void touch() { lastActivity = Instant.now(); }

    void close(String status) {
      if (!closed.compareAndSet(false, true)) return;
      if (reader != null && reader != Thread.currentThread()) reader.interrupt();
      if (monitor != null && monitor != Thread.currentThread()) monitor.interrupt();
      try {
        exec.close();
      } catch (RuntimeException failure) {
        LOG.warnf(failure, "Could not close exec stream for application %s",
            grant.target().applicationId());
      }
      try {
        applications.finishExec(grant, status);
      } catch (RuntimeException failure) {
        LOG.warnf(failure, "Could not persist exec completion audit for application %s",
            grant.target().applicationId());
      }
    }
  }
}
