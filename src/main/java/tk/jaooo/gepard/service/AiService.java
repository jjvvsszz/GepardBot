package tk.jaooo.gepard.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tk.jaooo.gepard.model.AppUser;

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
            String fileModel = user.getPreferredFileModel();
            if (fileModel == null || fileModel.isBlank()) {
                fileModel = settingsService.getConfig().getGeminiModel();
            }
            if (DEEPSEEK_MODELS.contains(fileModel)) {
                log.info("Modelo de arquivo DeepSeek ({}). Forcando system default Gemini.", fileModel);
                fileModel = settingsService.getConfig().getGeminiModel();
            }
            if (DEEPSEEK_MODELS.contains(fileModel)) {
                fileModel = getDefaultModel();
            }
            log.info("Processando arquivo com Gemini (modelo={}).", fileModel);
            return geminiService.generateContent(promptText, mediaBytes, mediaMimeType, user, fileModel, user.getGeminiApiKey());
        }

        String textModel = user.getPreferredTextModel();
        if (textModel == null || textModel.isBlank()) {
            textModel = settingsService.getConfig().getGeminiModel();
        }

        if (DEEPSEEK_MODELS.contains(textModel)) {
            if (!user.hasDeepSeekKey()) {
                log.info("DeepSeek selecionado sem API key. Fallback para Gemini.");
                return geminiService.generateContent(promptText, null, null, user,
                        getDefaultModel(), user.getGeminiApiKey());
            }
            log.info("Usando DeepSeek (modelo={}).", textModel);
            return deepSeekService.generateContent(promptText, textModel, user.getDeepSeekApiKey());
        }

        log.info("Usando Gemini (modelo={}).", textModel);
        return geminiService.generateContent(promptText, null, null, user, textModel, user.getGeminiApiKey());
    }
}
