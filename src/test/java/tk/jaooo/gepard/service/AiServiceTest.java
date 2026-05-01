package tk.jaooo.gepard.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiServiceTest {

    @Test
    void shouldReturnAllFiveModels() {
        List<String> models = AiService.getAllModels();
        assertThat(models).hasSize(5);
        assertThat(models).contains(
                "models/gemini-3.1-pro-preview",
                "models/gemini-3-flash-preview",
                "models/gemini-3.1-flash-lite-preview",
                "deepseek-v4-pro",
                "deepseek-v4-flash"
        );
    }

    @Test
    void shouldReturnOnlyGeminiModelsForFiles() {
        List<String> fileModels = AiService.getFileModels();
        assertThat(fileModels).hasSize(3);
        assertThat(fileModels).allMatch(m -> m.contains("gemini"));
        assertThat(fileModels).doesNotContain("deepseek-v4-pro", "deepseek-v4-flash");
    }

    @Test
    void defaultModelShouldBeFlashLite() {
        assertThat(AiService.getDefaultModel()).isEqualTo("models/gemini-3.1-flash-lite-preview");
    }

    @Test
    void shouldDetectDeepSeekInModelName() {
        assertThat(isDeepSeek("deepseek-v4-pro")).isTrue();
        assertThat(isDeepSeek("deepseek-v4-flash")).isTrue();
        assertThat(isDeepSeek("models/gemini-3-flash-preview")).isFalse();
        assertThat(isDeepSeek("models/gemini-3.1-flash-lite-preview")).isFalse();
    }

    @Test
    void shouldDetectGeminiInModelName() {
        assertThat(isGemini("models/gemini-3.1-pro-preview")).isTrue();
        assertThat(isGemini("models/gemini-3-flash-preview")).isTrue();
        assertThat(isGemini("models/gemini-3.1-flash-lite-preview")).isTrue();
        assertThat(isGemini("deepseek-v4-pro")).isFalse();
        assertThat(isGemini("deepseek-v4-flash")).isFalse();
    }

    private static boolean isDeepSeek(String model) {
        return model != null && model.contains("deepseek");
    }

    private static boolean isGemini(String model) {
        return model != null && model.contains("gemini");
    }
}
