package io.nanpanel.core.p3;

import io.nanpanel.plugin.api.ConfigTranslator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.pf4j.Extension;

@Extension
public final class P3FixtureConfigTranslator implements ConfigTranslator {
  @Override
  public String id() {
    return "p3-fixture-translator";
  }

  @Override
  public Set<String> sourceFormats() {
    return Set.of("fixture");
  }

  @Override
  public CompletionStage<TranslationResult> normalize(TranslationRequest request) {
    return CompletableFuture.completedFuture(new TranslationResult(
        request.snapshot(), List.of("manual-setting"), List.of()));
  }

  @Override
  public CompletionStage<TranslationResult> render(TranslationRequest request) {
    return CompletableFuture.completedFuture(new TranslationResult(
        "rendered:" + request.snapshot(), List.of(), List.of()));
  }
}
