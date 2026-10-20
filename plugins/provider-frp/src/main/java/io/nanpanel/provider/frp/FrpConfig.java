package io.nanpanel.provider.frp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FrpConfig {
  private static final Pattern TABLE = Pattern.compile("^\\s*\\[\\[proxies]]\\s*(?:#.*)?$");
  private static final Pattern ASSIGNMENT = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9]*)\\s*=\\s*(.*?)\\s*(?:#.*)?$");
  private static final List<String> ROUTE_KEYS = List.of("name", "type", "localIP", "localPort", "remotePort");

  private FrpConfig() {}

  record Route(String name, String type, String localIp, int localPort, int remotePort) {}
  record Block(int start, int end, Map<String, String> values, List<String> lines) {}

  static List<Block> blocks(String text) {
    String[] lines = text.split("(?<=\\n)", -1);
    List<Integer> starts = new ArrayList<>();
    for (int i = 0; i < lines.length; i++) {
      if (TABLE.matcher(stripNewline(lines[i])).matches()) starts.add(i);
    }
    List<Block> blocks = new ArrayList<>();
    for (int n = 0; n < starts.size(); n++) {
      int start = starts.get(n);
      int end = n + 1 < starts.size() ? starts.get(n + 1) : lines.length;
      Map<String, String> values = new LinkedHashMap<>();
      List<String> content = new ArrayList<>();
      for (int i = start + 1; i < end; i++) {
        String line = stripNewline(lines[i]);
        content.add(lines[i]);
        Matcher matcher = ASSIGNMENT.matcher(line);
        if (!matcher.matches()) continue;
        String key = matcher.group(1);
        String value = matcher.group(2).trim();
        if (values.putIfAbsent(key, value) != null) {
          throw new IllegalArgumentException("duplicate key in a proxy table");
        }
      }
      blocks.add(new Block(start, end, Map.copyOf(values), List.copyOf(content)));
    }
    return blocks;
  }

  static Map<String, Route> routes(String text) {
    Map<String, Route> routes = new LinkedHashMap<>();
    for (Block block : blocks(text)) {
      String name = stringValue(block.values(), "name", false);
      if (name == null) continue;
      Route route = new Route(name, stringValue(block.values(), "type", false),
          stringValue(block.values(), "localIP", false),
          intValue(block.values(), "localPort", false),
          intValue(block.values(), "remotePort", false));
      if (routes.putIfAbsent(name, route) != null) {
        throw new IllegalArgumentException("duplicate proxy name");
      }
    }
    return Map.copyOf(routes);
  }

  static Route desired(String name, String localIp, int localPort, int remotePort) {
    if (name == null || !name.matches("nanpanel-managed-[a-z0-9][a-z0-9-]{0,50}")) {
      throw new IllegalArgumentException("managed FRP proxy name is invalid");
    }
    if (localIp == null || localIp.length() > 253
        || !localIp.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")) {
      throw new IllegalArgumentException("local IP or host is invalid");
    }
    requirePort(localPort);
    requirePort(remotePort);
    return new Route(name, "tcp", localIp, localPort, remotePort);
  }

  static String patch(String source, String name, Route desired) {
    List<Block> blocks = blocks(source);
    Block target = null;
    Map<String, Route> currentRoutes = routes(source);
    for (Block block : blocks) {
      if (name.equals(stringValue(block.values(), "name", false))) {
        target = block;
        break;
      }
    }
    if (desired != null) {
      for (Route route : currentRoutes.values()) {
        if (!route.name().equals(name) && route.remotePort() > 0
            && route.remotePort() == desired.remotePort()) {
          throw new IllegalArgumentException("remote port is already used by another proxy");
        }
      }
    }
    String[] lines = source.split("(?<=\\n)", -1);
    if (target == null) {
      if (desired == null) return source;
      String separator = source.isEmpty() || source.endsWith("\n") ? "" : "\n";
      return source + separator + format(desired);
    }
    if (desired == null) {
      StringBuilder result = new StringBuilder();
      for (int i = 0; i < lines.length; i++) {
        if (i < target.start() || i >= target.end()) result.append(lines[i]);
      }
      return result.toString();
    }
    Map<String, String> replacements = new LinkedHashMap<>();
    replacements.put("type", quote(desired.type()));
    replacements.put("localIP", quote(desired.localIp()));
    replacements.put("localPort", Integer.toString(desired.localPort()));
    replacements.put("remotePort", Integer.toString(desired.remotePort()));
    StringBuilder body = new StringBuilder();
    Map<String, Boolean> seen = new LinkedHashMap<>();
    for (int i = target.start(); i < target.end(); i++) {
      String line = lines[i];
      Matcher matcher = ASSIGNMENT.matcher(stripNewline(line));
      if (matcher.matches() && replacements.containsKey(matcher.group(1))) {
        String key = matcher.group(1);
        String ending = line.endsWith("\r\n") ? "\r\n" : line.endsWith("\n") ? "\n" : "";
        int equals = line.indexOf('=');
        String tail = line.substring(equals + 1, line.length() - ending.length());
        String withoutComment = stripComment(tail);
        int leading = 0;
        while (leading < withoutComment.length() && Character.isWhitespace(withoutComment.charAt(leading))) leading++;
        int trailing = withoutComment.length();
        while (trailing > leading && Character.isWhitespace(withoutComment.charAt(trailing - 1))) trailing--;
        int commentIndex = commentIndex(tail);
        String comment = commentIndex < 0 ? "" : tail.substring(commentIndex);
        body.append(line, 0, equals + 1).append(withoutComment, 0, leading)
            .append(replacements.get(key)).append(withoutComment, trailing, withoutComment.length())
            .append(comment).append(ending);
        seen.put(key, true);
      } else {
        body.append(line);
      }
    }
    StringBuilder missing = new StringBuilder();
    for (String key : ROUTE_KEYS) {
      if (key.equals("name") || seen.containsKey(key)) continue;
      String value = switch (key) {
        case "type" -> quote(desired.type());
        case "localIP" -> quote(desired.localIp());
        case "localPort" -> Integer.toString(desired.localPort());
        case "remotePort" -> Integer.toString(desired.remotePort());
        default -> throw new IllegalStateException("unexpected route field");
      };
      missing.append(key).append(" = ").append(value).append('\n');
    }
    if (!missing.isEmpty()) {
      int last = body.length();
      if (last > 0 && body.charAt(last - 1) != '\n') body.append('\n');
      body.append(missing);
    }
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < target.start(); i++) result.append(lines[i]);
    result.append(body);
    for (int i = target.end(); i < lines.length; i++) result.append(lines[i]);
    return result.toString();
  }

  static String format(Route route) {
    return "[[proxies]]\n"
        + "name = " + quote(route.name()) + "\n"
        + "type = " + quote(route.type()) + "\n"
        + "localIP = " + quote(route.localIp()) + "\n"
        + "localPort = " + route.localPort() + "\n"
        + "remotePort = " + route.remotePort() + "\n";
  }

  static String stringValue(Map<String, String> values, String key, boolean required) {
    String value = values.get(key);
    if (value == null) {
      if (required) throw new IllegalArgumentException("missing proxy field " + key);
      return null;
    }
    if (value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"'
        || value.substring(1, value.length() - 1).contains("\\")) {
      throw new IllegalArgumentException("unsupported value for proxy field " + key);
    }
    return value.substring(1, value.length() - 1);
  }

  static int intValue(Map<String, String> values, String key, boolean required) {
    String value = values.get(key);
    if (value == null) {
      if (required) throw new IllegalArgumentException("missing proxy field " + key);
      return 0;
    }
    try {
      int parsed = Integer.parseInt(value);
      requirePort(parsed);
      return parsed;
    } catch (NumberFormatException failure) {
      throw new IllegalArgumentException("invalid proxy port " + key, failure);
    }
  }

  static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  static String hash(String value) {
    try {
      byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(bytes);
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException("SHA-256 is unavailable", failure);
    }
  }

  static void requirePort(int port) {
    if (port < 1 || port > 65535) throw new IllegalArgumentException("proxy port is out of range");
  }

  private static String stripComment(String value) {
    int index = commentIndex(value);
    return index < 0 ? value : value.substring(0, index);
  }

  private static int commentIndex(String value) {
    boolean quoted = false;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' && (i == 0 || value.charAt(i - 1) != '\\')) quoted = !quoted;
      if (c == '#' && !quoted) return i;
    }
    return -1;
  }

  private static String stripNewline(String line) {
    if (line.endsWith("\r\n")) return line.substring(0, line.length() - 2);
    return line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
  }
}
