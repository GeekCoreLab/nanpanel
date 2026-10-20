package io.nanpanel.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.common.channel.PtyChannelConfiguration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import io.smallrye.common.annotation.RunOnVirtualThread;

@WebSocket(path = "/api/v1/bastion-sessions/{sessionId}/terminal")
@ApplicationScoped
public class BastionTerminalWebSocket {
  private static final org.jboss.logging.Logger LOG =
      org.jboss.logging.Logger.getLogger(BastionTerminalWebSocket.class);
  private static final int MAX_MESSAGE_CHARS = 16 * 1024;

  @Inject ObjectMapper mapper;
  @Inject BastionSessionService sessions;
  @Inject HostSshCredentialService credentials;

  @Inject
  @ConfigProperty(name = "nanpanel.auth.origin", defaultValue = "https://localhost")
  String trustedOrigin;

  private final Map<String, Terminal> active = new ConcurrentHashMap<>();

  @OnOpen
  void onOpen(WebSocketConnection connection) {
    String origin = connection.handshakeRequest().header("Origin");
    if (origin == null || !trustedOrigin.equals(origin))
      connection.closeAndAwait(new CloseReason(1008, "untrusted origin"));
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
    String id = connection.pathParam("sessionId");
    if ("auth".equals(type)) {
      authenticate(connection, event, id);
      return;
    }
    Terminal terminal = active.get(connection.id());
    if (terminal == null) {
      connection.closeAndAwait(new CloseReason(1008, "authentication required"));
      return;
    }
    try {
      sessions.touch(terminal.access.sessionId(), terminal.access.webSessionId(),
          terminal.access.actorId());
      switch (type) {
        case "input" -> input(terminal, event.path("data").asText());
        case "resize" -> resize(terminal, event.path("columns").asInt(), event.path("rows").asInt());
        case "keepalive" -> send(connection, Map.of("type", "keepalive"));
        case "close" -> closeTerminal(connection, terminal, "CLOSED", null, null);
        default -> connection.closeAndAwait(new CloseReason(1003, "unknown message type"));
      }
    } catch (Exception failure) {
      LOG.warnf(failure, "Bastion terminal event failed for session %s",
          terminal.access.sessionId());
      closeTerminal(connection, terminal, "FAILED", "TERMINAL_IO_FAILED", null);
    }
  }

  @OnClose
  void onClose(WebSocketConnection connection, CloseReason reason) {
    Terminal terminal = active.remove(connection.id());
    if (terminal != null) {
      terminal.close();
      sessions.finish(terminal.access.sessionId(), "CLOSED", null,
          terminal.channel.getExitStatus());
    } else {
      UUID sessionId;
      try {
        sessionId = UUID.fromString(connection.pathParam("sessionId"));
      } catch (RuntimeException invalidSession) {
        LOG.debug("Closed unauthenticated WebSocket with an invalid bastion session id");
        return;
      }
      sessions.finish(sessionId, "FAILED", "TERMINAL_DISCONNECTED", null);
    }
  }

  void forceTerminate(UUID sessionId) {
    active.forEach((connectionId, terminal) -> {
      if (!terminal.access.sessionId().equals(sessionId) ||
          !active.remove(connectionId, terminal)) return;
      try {
        terminal.close();
      } catch (RuntimeException failure) {
        LOG.warnf(failure, "Could not close target SSH channel for session %s", sessionId);
      }
      try {
        terminal.connection.closeAndAwait(new CloseReason(4001, "session force terminated"));
      } catch (RuntimeException failure) {
        LOG.warnf(failure, "Could not close bastion WebSocket for session %s", sessionId);
      }
    });
  }

  private void authenticate(WebSocketConnection connection, JsonNode event, String idValue) {
    UUID sessionId;
    try {
      sessionId = UUID.fromString(idValue);
    } catch (RuntimeException invalid) {
      connection.closeAndAwait(new CloseReason(1008, "invalid session"));
      return;
    }
    String ticket = event.path("ticket").asText("");
    int columns = event.path("columns").asInt(120);
    int rows = event.path("rows").asInt(36);
    if (!ticket.matches("[A-Za-z0-9_-]{40,128}") || columns < 20 || columns > 500 ||
        rows < 5 || rows > 200 || active.containsKey(connection.id())) {
      connection.closeAndAwait(new CloseReason(1008, "invalid ticket or terminal size"));
      return;
    }
    BastionSessionService.SessionAccess access = null;
    HostSshCredentialService.Connection ssh = null;
    ChannelShell channel = null;
    Terminal terminal = null;
    try {
      access = sessions.consumeTicket(sessionId, ticket, columns, rows);
      ssh = credentials.connect(access.hostConfig());
      var pty = new PtyChannelConfiguration();
      pty.setPtyType("xterm-256color");
      pty.setPtyColumns(columns);
      pty.setPtyLines(rows);
      channel = ssh.session().createShellChannel(pty, Map.of("TERM", "xterm-256color"));
      channel.open().verify(15_000);
      Terminal openedTerminal = new Terminal(access, ssh, channel, connection);
      terminal = openedTerminal;
      active.put(connection.id(), openedTerminal);
      send(connection, Map.of("type", "ready", "sessionId", sessionId.toString()));
      openedTerminal.reader = Thread.ofVirtual().name("nanpanel-bastion-" + sessionId).start(
          () -> readOutput(openedTerminal));
    } catch (Exception failure) {
      LOG.warnf(failure, "Bastion SSH shell setup failed for session %s", sessionId);
      if (access != null) sessions.finish(sessionId, "FAILED", "SSH_CONNECT_FAILED", null);
      if (terminal != null) {
        active.remove(connection.id(), terminal);
        terminal.close();
      } else {
        if (channel != null) channel.close(true);
        if (ssh != null) ssh.close();
      }
      try {
        send(connection, Map.of("type", "error", "message",
            "SSH 连接失败；检查凭据、网络和已确认的 host key"));
      } catch (Exception ignored) {
        // The WebSocket may already be closed.
      }
      connection.closeAndAwait(new CloseReason(1011, "SSH connection failed"));
    }
  }

  private void input(Terminal terminal, String data) throws Exception {
    byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
    synchronized (terminal) {
      if (!sessions.recordChunk(terminal.access.sessionId(), "INPUT", bytes)) {
        closeTerminal(terminal.connection, terminal, "FAILED", "RECORDING_LIMIT_EXCEEDED", null);
        return;
      }
      String command = terminal.commandInput(data);
      if (command != null) sessions.recordCommand(terminal.access.sessionId(),
          ++terminal.commandSequence, command);
      terminal.input.write(bytes);
      terminal.input.flush();
    }
  }

  private void resize(Terminal terminal, int columns, int rows) throws Exception {
    if (columns < 20 || columns > 500 || rows < 5 || rows > 200)
      throw new IllegalArgumentException("Invalid terminal size");
    if (terminal.channel instanceof org.apache.sshd.client.channel.PtyCapableChannelSession pty) {
      pty.sendWindowChange(columns, rows);
    }
  }

  private void readOutput(Terminal terminal) {
    try (InputStream output = terminal.channel.getInvertedOut()) {
      byte[] buffer = new byte[4096];
      int read;
      while ((read = output.read(buffer)) >= 0) {
        if (read == 0) continue;
        byte[] payload = java.util.Arrays.copyOf(buffer, read);
        if (!sessions.recordChunk(terminal.access.sessionId(), "OUTPUT", payload)) {
          closeTerminal(terminal.connection, terminal, "FAILED", "RECORDING_LIMIT_EXCEEDED", null);
          return;
        }
        send(terminal.connection, Map.of("type", "output",
            "data", Base64.getEncoder().encodeToString(payload)));
      }
      sessions.finish(terminal.access.sessionId(), "CLOSED", null,
          terminal.channel.getExitStatus());
    } catch (Exception failure) {
      LOG.warnf(failure, "Bastion SSH output failed for session %s",
          terminal.access.sessionId());
      sessions.finish(terminal.access.sessionId(), "FAILED", "TERMINAL_OUTPUT_FAILED",
          terminal.channel.getExitStatus());
    } finally {
      Terminal removed = active.remove(terminal.connection.id());
      if (removed != null) removed.close();
    }
  }

  private void closeTerminal(WebSocketConnection connection, Terminal terminal,
      String status, String code, Integer exitCode) {
    active.remove(connection.id(), terminal);
    terminal.close();
    sessions.finish(terminal.access.sessionId(), status, code, exitCode);
    connection.closeAndAwait(new CloseReason(1000, "terminal closed"));
  }

  private void send(WebSocketConnection connection, Object value) throws Exception {
    connection.sendTextAndAwait(mapper.writeValueAsString(value));
  }

  private final class Terminal {
    final BastionSessionService.SessionAccess access;
    final HostSshCredentialService.Connection ssh;
    final ChannelShell channel;
    final WebSocketConnection connection;
    final OutputStream input;
    final StringBuilder command = new StringBuilder();
    final AtomicBoolean closed = new AtomicBoolean();
    Thread reader;
    int commandSequence;
    boolean escape;
    boolean csi;

    Terminal(BastionSessionService.SessionAccess access,
             HostSshCredentialService.Connection ssh, ChannelShell channel,
             WebSocketConnection connection) {
      this.access = access;
      this.ssh = ssh;
      this.channel = channel;
      this.connection = connection;
      this.input = channel.getInvertedIn();
    }

    String commandInput(String data) {
      String completed = null;
      for (int index = 0; index < data.length(); index++) {
        char value = data.charAt(index);
        if (csi) {
          if (value >= '@' && value <= '~') csi = false;
          continue;
        }
        if (escape) {
          escape = false;
          if (value == '[') csi = true;
          continue;
        }
        if (value == '\u001b') { escape = true; continue; }
        if (value == '\r' || value == '\n') {
          String line = command.toString().strip();
          if (!line.isEmpty()) completed = line;
          command.setLength(0);
        } else if (value == '\u007f' || value == '\b') {
          if (!command.isEmpty()) command.deleteCharAt(command.offsetByCodePoints(command.length(), -1));
        } else if (value == '\u0003') {
          command.setLength(0);
        } else if (value == '\t' || !Character.isISOControl(value)) {
          command.append(value);
          if (command.length() > 4096) throw new IllegalArgumentException("Command exceeds audit limit");
        }
      }
      return completed;
    }

    void close() {
      if (!closed.compareAndSet(false, true)) return;
      channel.close(true);
      ssh.close();
      Thread currentReader = reader;
      if (currentReader != null && currentReader != Thread.currentThread()) currentReader.interrupt();
    }
  }
}
