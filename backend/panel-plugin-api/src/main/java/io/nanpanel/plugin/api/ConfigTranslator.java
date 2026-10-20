package io.nanpanel.plugin.api;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import org.pf4j.ExtensionPoint;

/** Converts a provider-native configuration to and from a normalized network model. */
public interface ConfigTranslator extends ExtensionPoint {
    String id();
    Set<String> sourceFormats();
    CompletionStage<TranslationResult> normalize(TranslationRequest request);
    CompletionStage<TranslationResult> render(TranslationRequest request);

    record TranslationRequest(String sourceFormat, String snapshot, String targetFormat) {}
    record TranslationResult(String snapshot, List<String> preservedFields, List<String> warnings) {
        public TranslationResult {
            preservedFields = List.copyOf(preservedFields);
            warnings = List.copyOf(warnings);
        }
    }
}
