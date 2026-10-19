package io.nanpanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CloudflareDnsClientTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void requestsOnlyDnsOnlyCnamesAndReadsRecords() throws Exception {
    AtomicReference<String> method = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/zones/zone-1/dns_records", exchange -> {
      method.set(exchange.getRequestMethod());
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      String result;
      if ("GET".equals(method.get())) {
        result = "{\"success\":true,\"result\":[{" +
            "\"id\":\"record-1\",\"type\":\"CNAME\",\"name\":\"api.example.test\",\"content\":\"relay-a.example.test\",\"ttl\":60,\"proxied\":false}]}";
      } else {
        result = "{\"success\":true,\"result\":{" +
            "\"id\":\"record-2\",\"type\":\"CNAME\",\"name\":\"api.example.test\",\"content\":\"relay-b.example.test\",\"ttl\":60,\"proxied\":false}}";
      }
      byte[] body = result.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      CloudflareDnsClient client = new CloudflareDnsClient(mapper, "test-token",
          URI.create("http://127.0.0.1:" + server.getAddress().getPort()));

      var listed = client.listRecords("zone-1", "api.example.test");
      assertEquals("GET", method.get());
      assertEquals("Bearer test-token", authorization.get());
      assertEquals("record-1", listed.getFirst().id());

      var created = client.createRecord("zone-1", "api.example.test", "relay-b.example.test");
      assertEquals("POST", method.get());
      assertEquals("Bearer test-token", authorization.get());
      var body = mapper.readTree(requestBody.get());
      assertEquals("CNAME", body.path("type").asText());
      assertEquals("api.example.test", body.path("name").asText());
      assertEquals("relay-b.example.test", body.path("content").asText());
      assertEquals(60, body.path("ttl").asInt());
      assertFalse(body.path("proxied").asBoolean());
      assertEquals("record-2", created.id());
    } finally {
      server.stop(0);
    }
  }
}
