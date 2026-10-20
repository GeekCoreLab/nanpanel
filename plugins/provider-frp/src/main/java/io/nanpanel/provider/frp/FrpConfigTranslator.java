package io.nanpanel.provider.frp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.nanpanel.plugin.api.ConfigTranslator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.pf4j.Extension;

/** Normalizes supported TCP route fields while carrying the original TOML byte-for-byte. */
@Extension
public final class FrpConfigTranslator implements ConfigTranslator {
  static final String NORMALIZED_FORMAT = "nanpanel.network-config/v1";
  private static final int MAX_INPUT = 1_048_576;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Set<String> ROUTE_FIELDS = Set.of("name", "type", "localIP", "localPort", "remotePort");

  @Override
  public String id() {
    return "frp-toml";
  }

  @Override
  public Set<String> sourceFormats() {
    return Set.of("frpc.toml", NORMALIZED_FORMAT);
  }

  @Override
  public CompletionStage<TranslationResult> normalize(TranslationRequest request) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        if (!"frpc.toml".equals(request.sourceFormat())
            || !NORMALIZED_FORMAT.equals(request.targetFormat())
            || request.snapshot() == null || request.snapshot().length() > MAX_INPUT) {
          throw new IllegalArgumentException("unsupported FRP translation request");
        }
        String source = request.snapshot();
        ObjectNode output = MAPPER.createObjectNode();
        output.put("format", NORMALIZED_FORMAT);
        output.put("sourceFormat", "frpc.toml");
        output.put("sourceSnapshot", source);
        ArrayNode routes = output.putArray("routes");
        LinkedHashSet<String> preserved = preservedKeys(source);
        for (FrpConfig.Route route : FrpConfig.routes(source).values()) {
          ObjectNode value = routes.addObject();
          value.put("name", route.name());
          if (route.type() != null) value.put("type", route.type());
          if (route.localIp() != null) value.put("localIP", route.localIp());
          if (route.localPort() > 0) value.put("localPort", route.localPort());
          if (route.remotePort() > 0) value.put("remotePort", route.remotePort());
        }
        return new TranslationResult(MAPPER.writeValueAsString(output), List.copyOf(preserved),
            List.of("The original FRP configuration is retained as sourceSnapshot; only listed TCP route fields are normalized."));
      } catch (Exception failure) {
        throw new IllegalArgumentException("FRP TOML could not be normalized safely", failure);
      }
    });
  }

  @Override
  public CompletionStage<TranslationResult> render(TranslationRequest request) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        if (!NORMALIZED_FORMAT.equals(request.sourceFormat()) || !"frpc.toml".equals(request.targetFormat())
            || request.snapshot() == null || request.snapshot().length() > MAX_INPUT) {
          throw new IllegalArgumentException("unsupported FRP translation request");
        }
        JsonNode document = MAPPER.readTree(request.snapshot());
        if (!NORMALIZED_FORMAT.equals(document.path("format").asText())
            || !document.path("sourceSnapshot").isTextual() || !document.path("routes").isArray()) {
          throw new IllegalArgumentException("normalized FRP model is invalid");
        }
        String rendered = document.path("sourceSnapshot").asText();
        if (rendered.length() > MAX_INPUT) throw new IllegalArgumentException("source config exceeds 1 MiB");
        Set<String> originalNames = FrpConfig.routes(rendered).keySet();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : document.path("routes")) {
          String name = requiredText(item, "name");
          if (!seen.add(name)) throw new IllegalArgumentException("duplicate normalized proxy name");
          FrpConfig.Route desired = route(item, name);
          FrpConfig.Route original = FrpConfig.routes(rendered).get(name);
          if (original == null && !name.startsWith("nanpanel-managed-")) {
            throw new IllegalArgumentException("new proxy names must use the managed prefix");
          }
          if (original != null && !name.startsWith("nanpanel-managed-") && !original.equals(desired)) {
            throw new IllegalArgumentException("unmanaged proxy routes cannot be changed");
          }
          if (name.startsWith("nanpanel-managed-")) {
            rendered = FrpConfig.patch(rendered, name, desired);
          }
        }
        List<String> warnings = new ArrayList<>();
        if (originalNames.stream().anyMatch(name -> !seen.contains(name))) {
          warnings.add("Routes omitted from the normalized model remain unchanged in sourceSnapshot.");
        }
        return new TranslationResult(rendered, List.copyOf(preservedKeys(rendered)), warnings);
      } catch (Exception failure) {
        throw new IllegalArgumentException("normalized FRP model could not be rendered safely", failure);
      }
    });
  }

  private static FrpConfig.Route route(JsonNode item, String name) {
    String type = requiredText(item, "type");
    String host = requiredText(item, "localIP");
    int localPort = requiredPort(item, "localPort");
    int remotePort = requiredPort(item, "remotePort");
    if (!"tcp".equals(type)) throw new IllegalArgumentException("only TCP proxies are supported");
    if (!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")
        || !host.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")) {
      throw new IllegalArgumentException("proxy name or local host is invalid");
    }
    return new FrpConfig.Route(name, type, host, localPort, remotePort);
  }

  private static int requiredPort(JsonNode item, String field) {
    if (!item.path(field).canConvertToInt()) throw new IllegalArgumentException("invalid " + field);
    int value = item.path(field).intValue();
    FrpConfig.requirePort(value);
    return value;
  }

  private static String requiredText(JsonNode item, String field) {
    JsonNode value = item.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("invalid " + field);
    }
    return value.asText();
  }

  private static LinkedHashSet<String> preservedKeys(String source) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String line : source.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("[")) continue;
      int separator = trimmed.indexOf('=');
      if (separator < 1) continue;
      String key = trimmed.substring(0, separator).trim();
      if (!ROUTE_FIELDS.contains(key)) result.add(key);
    }
    for (FrpConfig.Block block : FrpConfig.blocks(source)) {
      for (String key : block.values().keySet()) {
        if (!ROUTE_FIELDS.contains(key)) result.add(key);
      }
    }
    return result;
  }
}
