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

    private static final List<String> DEEPSEEK_MODELS = List.of(
            "deepseek-v4-pro",
            "deepseek-v4-flash"
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

    public static String getDefaultModel() {
        return "models/gemini-3.1-flash-lite-preview";
    }

    public String generateContent(String promptText, byte[] mediaBytes, String mediaMimeType, AppUser user) {
        String userModel = user.getPreferredModel();
        if (userModel == null || userModel.isBlank()) {
            userModel = getDefaultModel();
        }

        boolean hasMedia = mediaBytes != null && mediaBytes.length > 0;

        if (hasMedia && DEEPSEEK_MODELS.contains(userModel)) {
            log.info("Midia detectada com modelo DeepSeek ({}). Redirecionando para Gemini.", userModel);
            return geminiService.generateContent(promptText, mediaBytes, mediaMimeType, user);
        }

        if (DEEPSEEK_MODELS.contains(userModel)) {
            return deepSeekService.generateContent(promptText, userModel, user.getGeminiApiKey());
        }

        return geminiService.generateContent(promptText, mediaBytes, mediaMimeType, user);
    }
}
