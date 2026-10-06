package tk.jaooo.gepard.service;

import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tk.jaooo.gepard.model.AppUser;
import tk.jaooo.gepard.model.GlobalConfig;
import tk.jaooo.gepard.model.dto.AiResponseDTO;
import tk.jaooo.gepard.util.EventTimes;

import java.time.ZonedDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiService {

    private final GeminiService geminiService;
    private final DeepSeekService deepSeekService;
    private final SystemSettingsService settingsService;
    private final ObjectMapper objectMapper;

    private static final List<String> DEEPSEEK_MODELS = List.of(
            "deepseek-v4-pro",
            "deepseek-flash"
    );

    private static final List<String> GEMINI_ONLY_MODELS = List.of(
            "models/gemini-3.1-pro-preview",
            "models/gemini-3.8-flash",
            "models/gemini-3.5-flash-lite"
    );

    public static List<String> getAllModels() {
        return List.of(
                "models/gemini-3.1-pro-preview",
                "models/gemini-3.8-flash",
                "models/gemini-3.5-flash-lite",
                "deepseek-v4-pro",
                "deepseek-flash"
        );
    }

    public static List<String> getFileModels() {
        return GEMINI_ONLY_MODELS;
    }

    public static String getDefaultModel() {
        return "models/gemini-3.5-flash-lite";
    }

    /** Primeira leitura de uma mensagem do usuario: intencao + dados do evento. */
    public AiResponseDTO interpret(String userText, byte[] mediaBytes, String mediaMimeType, AppUser user) {
        String prompt = EventTimes.promptContext(ZonedDateTime.now(user.zone()))
                + "\nMensagem do usuario: " + userText;
        return parse(generate(AiPrompts.INTERPRET, prompt, mediaBytes, mediaMimeType, user));
    }

    /** Alteracao parcial de um evento existente. {@code currentEventJson} descreve o estado atual. */
    public AiResponseDTO patch(String currentEventJson, String request, byte[] mediaBytes, String mediaMimeType,
                               AppUser user) {
        String prompt = EventTimes.promptContext(ZonedDateTime.now(user.zone()))
                + "\nEvento ATUAL: " + currentEventJson
                + "\nPedido do usuario: " + request;
        return parse(generate(AiPrompts.PATCH, prompt, mediaBytes, mediaMimeType, user));
    }

    AiResponseDTO parse(String json) {
        String clean = json == null ? "" : json.strip();
        if (clean.startsWith("```")) {
            clean = clean.replaceFirst("^```(json)?", "").replaceFirst("```$", "").strip();
        }
        try {
            return objectMapper.readValue(clean.isEmpty() ? "{}" : clean, AiResponseDTO.class);
        } catch (Exception e) {
            log.warn("Resposta da IA nao e JSON valido: {}", json);
            throw new AiException(AiException.Kind.BAD_RESPONSE, "IA", e.getMessage(), e);
        }
    }

    private String generate(String systemPrompt, String promptText, byte[] mediaBytes, String mediaMimeType, AppUser user) {
        boolean hasMedia = mediaBytes != null && mediaBytes.length > 0;

        if (hasMedia) {
            String fileModel = resolveFileModel(user);
            log.info("Processando arquivo com Gemini (modelo={}).", fileModel);
            return geminiService.generateContent(systemPrompt, promptText, mediaBytes, mediaMimeType, fileModel, user.getGeminiApiKey());
        }

        String textModel = resolveTextModel(user);

        if (DEEPSEEK_MODELS.contains(textModel)) {
            log.info("Usando DeepSeek (modelo={}).", textModel);
            return deepSeekService.generateContent(systemPrompt, promptText, textModel, user.getDeepSeekApiKey());
        }

        log.info("Usando Gemini (modelo={}).", textModel);
        return geminiService.generateContent(systemPrompt, promptText, null, null, textModel, user.getGeminiApiKey());
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
}
