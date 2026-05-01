package tk.jaooo.gepard.service;

import org.junit.jupiter.api.Test;
import tk.jaooo.gepard.model.AppUser;

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
    void defaultModelShouldBeFlashLite() {
        assertThat(AiService.getDefaultModel()).isEqualTo("models/gemini-3.1-flash-lite-preview");
    }

    @Test
    void shouldDetectDeepSeekModels() {
        AppUser user = new AppUser();
        user.setPreferredModel("deepseek-v4-pro");
        assertThat(getModelDisplayName(user)).isEqualTo("DeepSeek");

        user.setPreferredModel("deepseek-v4-flash");
        assertThat(getModelDisplayName(user)).isEqualTo("DeepSeek");
    }

    @Test
    void shouldDetectGeminiModels() {
        AppUser user = new AppUser();
        user.setPreferredModel("models/gemini-3-flash-preview");
        assertThat(getModelDisplayName(user)).isEqualTo("Gemini Flash");

        user.setPreferredModel("models/gemini-3.1-pro-preview");
        assertThat(getModelDisplayName(user)).isEqualTo("Gemini Pro");

        user.setPreferredModel("models/gemini-3.1-flash-lite-preview");
        assertThat(getModelDisplayName(user)).isEqualTo("Gemini Lite");
    }

    @Test
    void shouldReturnPadraoForNullModel() {
        AppUser user = new AppUser();
        assertThat(getModelDisplayName(user)).isEqualTo("Padrao");
    }

    private static String getModelDisplayName(AppUser user) {
        String model = user.getPreferredModel();
        if (model == null || model.isBlank()) return "Padrao";
        if (model.contains("deepseek")) return "DeepSeek";
        if (model.contains("pro")) return "Gemini Pro";
        if (model.contains("flash-lite")) return "Gemini Lite";
        if (model.contains("flash")) return "Gemini Flash";
        return model;
    }
}
