package tk.jaooo.gepard.service;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.Client;
import com.google.genai.types.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tk.jaooo.gepard.model.AppUser;

import java.util.*;

@Slf4j
@Service
public class GeminiService {

    private final SystemSettingsService settingsService;

    public GeminiService(SystemSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public String generateContent(String promptText, byte[] mediaBytes, String mediaMimeType, AppUser user) {
        String globalModel = settingsService.getConfig().getGeminiModel();
        String userModel = user.getPreferredModel();
        String modelName = (userModel != null && !userModel.isBlank()) ? userModel : globalModel;

        log.info("Gerando conteúdo para User {} usando modelo: {}", user.getTelegramId(), modelName);

        try (Client client = Client.builder().apiKey(user.getGeminiApiKey()).build()) {

            List<Part> parts = new ArrayList<>();

            if (mediaBytes != null && mediaBytes.length > 0 && mediaMimeType != null) {
                Blob blob = Blob.builder()
                        .mimeType(mediaMimeType)
                        .data(mediaBytes)
                        .build();
                parts.add(Part.builder().inlineData(blob).build());
            }

            parts.add(Part.fromText(promptText));

            Schema eventSchema = Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(ImmutableMap.of(
                            "summary", Schema.builder().type(Type.Known.STRING).description("Título curto").build(),
                            "location", Schema.builder().type(Type.Known.STRING).description("Local").build(),
                            "description", Schema.builder().type(Type.Known.STRING).description("Descrição").build(),
                            "startDateTime", Schema.builder().type(Type.Known.STRING).description("Início ISO8601 (-03:00)").build(),
                            "endDateTime", Schema.builder().type(Type.Known.STRING).description("Fim ISO8601 (-03:00)").build(),
                            "reminders", Schema.builder()
                                    .type(Type.Known.ARRAY)
                                    .items(Schema.builder().type(Type.Known.INTEGER).build())
                                    .description("Se não pedir, avalie de acordo com o evento e defina lembretes da forma que achar necessário (30 é o padrão).").build()
                    ))
                    .required(Arrays.asList("summary", "startDateTime"))
                    .build();

            Content userContent = Content.builder()
                    .role("user")
                    .parts(ImmutableList.copyOf(parts))
                    .build();

            GenerateContentConfig config = GenerateContentConfig.builder()
                    .responseMimeType("application/json")
                    .responseSchema(eventSchema)
                    .systemInstruction(
                            Content.builder()
                                    .parts(ImmutableList.of(
                                            Part.fromText("""
                Você é um assistente de agendamento.
                Fuso: America/Sao_Paulo (-03:00).
                Áudios e Imagens devem ser analisados para extrair detalhes do evento.
                
                REGRAS DE LEMBRETES (Reminders):
                1. O campo 'reminders' aceita APENAS números inteiros (minutos).
                2. Se o usuário pedir '2 dias antes', CALCULE: 2 * 24 * 60 = 2880. Retorne [2880].
                3. Se pedir '1 semana antes', CALCULE: 7 * 24 * 60 = 10080.
                4. Se não pedir, avalie de acordo com o evento e defina lembretes da forma que achar necessário (30 é o padrão).
                """)
                                    ))
                                    .build()
                    )
                    .build();

            GenerateContentResponse response = client.models.generateContent(modelName, userContent, config);
            return extractTextFromResponse(response);

        } catch (Exception e) {
            log.error("Erro na chamada Gemini SDK: ", e);
            throw new RuntimeException(e);
        }
    }

    private String extractTextFromResponse(GenerateContentResponse response) {
        if (response.candidates().isEmpty() || response.candidates().get().isEmpty()) return "{}";
        Candidate candidate = response.candidates().get().get(0);
        if (candidate.content().isEmpty()) return "{}";
        Content content = candidate.content().get();
        if (content.parts().isEmpty() || content.parts().get().isEmpty()) return "{}";

        StringBuilder sb = new StringBuilder();
        for (Part part : content.parts().get()) {
            if (part.text().isPresent()) {
                sb.append(part.text().get());
            }
        }
        return sb.toString();
    }
}
