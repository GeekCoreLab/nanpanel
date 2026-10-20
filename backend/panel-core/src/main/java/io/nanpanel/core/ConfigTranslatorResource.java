package io.nanpanel.core;

import static io.nanpanel.core.AuthResource.fail;

import io.nanpanel.plugin.api.ConfigTranslator;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jakarta.ws.rs.container.ContainerRequestContext;

@Path("/api/v1/config-translators")
@Produces(MediaType.APPLICATION_JSON)
public class ConfigTranslatorResource {
  @Inject NetworkProviderRegistry registry;
  @Context ContainerRequestContext request;

  public record TranslationInput(String sourceFormat, String snapshot, String targetFormat) {}

  private void session() {
    @SuppressWarnings("unchecked")
    Map<String, Object> value = (Map<String, Object>)request.getProperty("session");
    if (value == null || value.get("user_id") == null) {
      throw fail(401, "UNAUTHENTICATED", "请登录以继续");
    }
  }

  @GET
  public List<Map<String, Object>> list() {
    session();
    return registry.translators().stream().map(value -> {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("id", value.id());
      result.put("sourceFormats", value.sourceFormats());
      return result;
    }).toList();
  }

  @POST
  @Path("{translatorId}/normalize")
  @Consumes(MediaType.APPLICATION_JSON)
  public ConfigTranslator.TranslationResult normalize(@PathParam("translatorId") String translatorId,
                                                       TranslationInput input) {
    return translate(translatorId, input, true);
  }

  @POST
  @Path("{translatorId}/render")
  @Consumes(MediaType.APPLICATION_JSON)
  public ConfigTranslator.TranslationResult render(@PathParam("translatorId") String translatorId,
                                                    TranslationInput input) {
    return translate(translatorId, input, false);
  }

  private ConfigTranslator.TranslationResult translate(String id, TranslationInput input,
                                                        boolean normalize) {
    session();
    if (input == null || input.sourceFormat() == null || input.sourceFormat().isBlank()
        || input.sourceFormat().length() > 80 || input.targetFormat() == null
        || input.targetFormat().isBlank() || input.targetFormat().length() > 80
        || input.snapshot() == null
        || input.snapshot().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_048_576) {
      throw fail(400, "VALIDATION_FAILED", "配置转换请求无效或超过 1 MiB");
    }
    ConfigTranslator translator = registry.translators().stream()
        .filter(value -> value.id().equals(id)).findFirst()
        .orElseThrow(() -> fail(404, "TRANSLATOR_UNAVAILABLE", "配置 Translator 未启用或未安装"));
    try {
      var action = normalize ? translator.normalize(new ConfigTranslator.TranslationRequest(
          input.sourceFormat(), input.snapshot(), input.targetFormat()))
          : translator.render(new ConfigTranslator.TranslationRequest(
              input.sourceFormat(), input.snapshot(), input.targetFormat()));
      var future = action.toCompletableFuture();
      ConfigTranslator.TranslationResult result;
      try {
        result = future.get(10, TimeUnit.SECONDS);
      } catch (TimeoutException failure) {
        future.cancel(true);
        throw failure;
      }
      if (result == null || result.snapshot() == null
          || result.snapshot().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_048_576
          || result.preservedFields().size() > 256 || result.warnings().size() > 128
          || result.preservedFields().stream().anyMatch(value -> value == null || value.length() > 200)
          || result.warnings().stream().anyMatch(value -> value == null || value.length() > 500)) {
        throw fail(502, "TRANSLATION_INVALID", "Translator 返回了无效或过大的结果");
      }
      return result;
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw fail(503, "TRANSLATION_INTERRUPTED", "配置转换被中断");
    } catch (TimeoutException failure) {
      throw fail(504, "TRANSLATION_TIMEOUT", "配置转换超过 10 秒");
    } catch (ExecutionException | RuntimeException failure) {
      if (failure instanceof jakarta.ws.rs.WebApplicationException webFailure) {
        throw webFailure;
      }
      throw fail(422, "TRANSLATION_REJECTED", "Translator 无法转换当前配置");
    }
  }
}
