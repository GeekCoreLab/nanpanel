package io.nanpanel.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class CloudflareDnsClient {
  private static final URI API_ORIGIN = URI.create("https://api.cloudflare.com/client/v4");

  public record Record(String id, String type, String name, String content,
                       int ttl, boolean proxied) {}

  private final ObjectMapper mapper;
  private final HttpClient http;
  private final String token;
  private final URI apiOrigin;

  @Inject
  public CloudflareDnsClient(ObjectMapper mapper,
      @ConfigProperty(name = "nanpanel.dns.cloudflare-token", defaultValue = "disabled")
      String token) {
    this(mapper, token, API_ORIGIN);
  }

  CloudflareDnsClient(ObjectMapper mapper, String token, URI apiOrigin) {
    this.mapper = mapper;
    this.token = token;
    this.apiOrigin = apiOrigin;
    this.http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  public List<Record> listRecords(String zoneId, String name)
      throws IOException, InterruptedException {
    String url = zonePath(zoneId) + "/dns_records?name=" + encode(name) + "&per_page=100";
    JsonNode result = request("GET", url, null, true);
    if (!result.isArray()) throw new IOException("Cloudflare returned an invalid DNS record list");
    List<Record> records = new ArrayList<>();
    for (JsonNode item : result) {
      records.add(new Record(text(item, "id"), text(item, "type"), text(item, "name"),
          text(item, "content"), item.path("ttl").asInt(-1), item.path("proxied").asBoolean()));
    }
    return List.copyOf(records);
  }

  public Record createRecord(String zoneId, String name, String content)
      throws IOException, InterruptedException {
    return parseRecord(request("POST", zonePath(zoneId) + "/dns_records",
        recordBody(name, content), false));
  }

  public Record updateRecord(String zoneId, String recordId, String name, String content)
      throws IOException, InterruptedException {
    return parseRecord(request("PATCH", zonePath(zoneId) + "/dns_records/" + encode(recordId),
        recordBody(name, content), false));
  }

  private JsonNode request(String method, String path, JsonNode body, boolean retryable)
      throws IOException, InterruptedException {
    URI uri = apiOrigin.resolve(path);
    for (int attempt = 0; ; attempt++) {
      HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(8))
          .header("Authorization", "Bearer " + token)
          .header("Accept", "application/json");
      if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
      else builder.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
      HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      int code = response.statusCode();
      if (retryable && isRetryable(code) && attempt < 2) {
        Thread.sleep(150L << attempt);
        continue;
      }
      if (code < 200 || code >= 300)
        throw new IOException("Cloudflare DNS API returned HTTP " + code);
      JsonNode root = mapper.readTree(response.body());
      if (!root.path("success").asBoolean(false))
        throw new IOException("Cloudflare DNS API rejected the request");
      return root.path("result");
    }
  }

  private ObjectNode recordBody(String name, String content) {
    ObjectNode body = mapper.createObjectNode();
    body.put("type", "CNAME");
    body.put("name", name);
    body.put("content", content);
    body.put("ttl", 60);
    body.put("proxied", false);
    return body;
  }

  private Record parseRecord(JsonNode value) throws IOException {
    Record record = new Record(text(value, "id"), text(value, "type"), text(value, "name"),
        text(value, "content"), value.path("ttl").asInt(-1), value.path("proxied").asBoolean());
    if (record.id().isBlank() || record.type().isBlank() || record.name().isBlank() ||
        record.content().isBlank() || record.ttl() < 1)
      throw new IOException("Cloudflare returned an invalid DNS record");
    return record;
  }

  private String zonePath(String zoneId) {
    return "/zones/" + encode(zoneId);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isTextual() ? value.textValue() : "";
  }

  private static boolean isRetryable(int status) {
    return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
  }
}
