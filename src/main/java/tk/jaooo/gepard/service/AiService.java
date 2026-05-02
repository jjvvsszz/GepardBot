package tk.jaooo.gepard.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tk.jaooo.gepard.model.AppUser;
import tk.jaooo.gepard.model.GlobalConfig;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final GeminiService geminiService;
    private final DeepSeekService deepSeekService;
    private final SystemSettingsService settingsService;

    private static final List<String> DEEPSEEK_MODELS = List.of(
            "deepseek-v4-pro",
            "deepseek-v4-flash"
    );

    private static final List<String> GEMINI_ONLY_MODELS = List.of(
            "models/gemini-3.1-pro-preview",
            "models/gemini-3-flash-preview",
            "models/gemini-3.1-flash-lite-preview"
    );

    public static List<String> getAllModels() {
        return List.of(
                "models/gemini-3.1-pro-preview",
                "models/gemini-3-flash-preview",
                "models/gemini-3.1-flash-lite-preview",
                "deepseek-v4-pro",
                "deepseek-v4-flash"
        );
    }

    public static List<String> getFileModels() {
        return GEMINI_ONLY_MODELS;
    }

    public static String getDefaultModel() {
        return "models/gemini-3.1-flash-lite-preview";
    }

    public String generateContent(String promptText, byte[] mediaBytes, String mediaMimeType, AppUser user) {
        boolean hasMedia = mediaBytes != null && mediaBytes.length > 0;

        if (hasMedia) {
            String fileModel = resolveFileModel(user);
            log.info("Processando arquivo com Gemini (modelo={}).", fileModel);
            return geminiService.generateContent(promptText, mediaBytes, mediaMimeType, user, fileModel, user.getGeminiApiKey());
        }

        String textModel = resolveTextModel(user);

        if (DEEPSEEK_MODELS.contains(textModel)) {
            if (!user.hasDeepSeekKey()) {
                log.info("DeepSeek sem API key (seguranca). Fallback final para Gemini.");
                textModel = resolveFinalFallback();
                return geminiService.generateContent(promptText, null, null, user, textModel, user.getGeminiApiKey());
            }
            log.info("Usando DeepSeek (modelo={}).", textModel);
            return deepSeekService.generateContent(promptText, textModel, user.getDeepSeekApiKey());
        }

        log.info("Usando Gemini (modelo={}).", textModel);
        return geminiService.generateContent(promptText, null, null, user, textModel, user.getGeminiApiKey());
    }

    private String resolveTextModel(AppUser user) {
        GlobalConfig config = settingsService.getConfig();

        String model = user.getPreferredTextModel();
        if (model != null && !model.isBlank()) {
            if (DEEPSEEK_MODELS.contains(model)) {
                if (user.hasDeepSeekKey()) {
                    return model;
                }
                log.info("DeepSeek escolhido pelo usuario sem API key. Avancando para modelo padrao do sistema.");
            } else {
                return model;
            }
        }

        model = config.getDefaultTextModel();
        if (model != null && !model.isBlank()) {
            if (DEEPSEEK_MODELS.contains(model)) {
                if (user.hasDeepSeekKey()) {
                    return model;
                }
                log.info("Modelo padrao DeepSeek sem API key do usuario. Avancando para fallback.");
            } else {
                return model;
            }
        }

        model = config.getFallbackModel();
        if (model != null && !model.isBlank() && !DEEPSEEK_MODELS.contains(model)) {
            return model;
        }

        return getDefaultModel();
    }

    private String resolveFileModel(AppUser user) {
        GlobalConfig config = settingsService.getConfig();

        String model = user.getPreferredFileModel();
        if (model != null && !model.isBlank() && !DEEPSEEK_MODELS.contains(model)) {
            return model;
        }

        model = config.getDefaultFileModel();
        if (model != null && !model.isBlank() && !DEEPSEEK_MODELS.contains(model)) {
            return model;
        }

        model = config.getFallbackModel();
        if (model != null && !model.isBlank() && !DEEPSEEK_MODELS.contains(model)) {
            return model;
        }

        return getDefaultModel();
    }

    private String resolveFinalFallback() {
        GlobalConfig config = settingsService.getConfig();
        String model = config.getFallbackModel();
        if (model != null && !model.isBlank() && !DEEPSEEK_MODELS.contains(model)) {
            return model;
        }
        return getDefaultModel();
    }
}
