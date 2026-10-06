package tk.jaooo.gepard.service;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class GeminiService {

    private static final String PROVIDER = "Gemini";

    private static final Map<String, Schema> EVENT_FIELDS = ImmutableMap.<String, Schema>builder()
            .put("summary", stringField("Titulo curto"))
            .put("location", stringField("Local"))
            .put("description", stringField("Descricao"))
            .put("startDateTime", stringField("Inicio em ISO 8601 com offset, ou YYYY-MM-DD para dia inteiro"))
            .put("endDateTime", stringField("Fim em ISO 8601 com offset, ou YYYY-MM-DD (ultimo dia) para dia inteiro"))
            .put("reminders", Schema.builder()
                    .type(Type.Known.ARRAY)
                    .items(Schema.builder().type(Type.Known.INTEGER).build())
                    .description("Minutos antes do evento")
                    .build())
            .put("recurrence", stringField("Regra RRULE se o evento se repete, ex: RRULE:FREQ=WEEKLY;BYDAY=MO"))
            .put("attendees", Schema.builder()
                    .type(Type.Known.ARRAY)
                    .items(Schema.builder().type(Type.Known.STRING).build())
                    .description("E-mails de convidados citados explicitamente")
                    .build())
            .build();

    private static final Schema RESPONSE_SCHEMA = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(ImmutableMap.<String, Schema>builder()
                    .put("operation", Schema.builder()
                            .type(Type.Known.STRING)
                            .enum_(List.of("create", "edit", "delete", "query", "none"))
                            .description("Intencao do usuario")
                            .build())
                    .put("searchQuery", stringField("Para edit/delete/query: apenas a palavra principal do titulo do evento"))
                    .put("searchDate", stringField("Para edit/delete: data (YYYY-MM-DD) em que o evento atual acontece, se informada"))
                    .putAll(EVENT_FIELDS)
                    .put("events", Schema.builder()
                            .type(Type.Known.ARRAY)
                            .items(Schema.builder().type(Type.Known.OBJECT).properties(EVENT_FIELDS).build())
                            .description("Para create: um item por compromisso")
                            .build())
                    .put("queryStart", stringField("Para query: inicio do periodo perguntado, ISO 8601"))
                    .put("queryEnd", stringField("Para query: fim do periodo perguntado, ISO 8601"))
                    .put("checkFree", Schema.builder().type(Type.Known.BOOLEAN)
                            .description("Para query: o usuario quer saber se esta livre").build())
                    .build())
            .required(List.of("operation"))
            .build();

    private static Schema stringField(String description) {
        return Schema.builder().type(Type.Known.STRING).description(description).build();
    }

    public String generateContent(String systemPrompt, String promptText, byte[] mediaBytes, String mediaMimeType,
                                  String modelName, String apiKey) {
        log.info("Gemini gerando com modelo: {}", modelName);

        try (Client client = Client.builder().apiKey(apiKey).build()) {

            List<Part> parts = new ArrayList<>();

            if (mediaBytes != null && mediaBytes.length > 0 && mediaMimeType != null) {
                Blob blob = Blob.builder()
                        .mimeType(mediaMimeType)
                        .data(mediaBytes)
                        .build();
                parts.add(Part.builder().inlineData(blob).build());
            }

            parts.add(Part.fromText(promptText));

            Content userContent = Content.builder()
                    .role("user")
                    .parts(ImmutableList.copyOf(parts))
                    .build();

            GenerateContentConfig config = GenerateContentConfig.builder()
                    .responseMimeType("application/json")
                    .responseSchema(RESPONSE_SCHEMA)
                    .systemInstruction(Content.builder()
                            .parts(ImmutableList.of(Part.fromText(systemPrompt)))
                            .build())
                    .build();

            GenerateContentResponse response = client.models.generateContent(modelName, userContent, config);
            return extractTextFromResponse(response);

        } catch (ApiException e) {
            log.error("Gemini retornou erro {} ({}): {}", e.code(), e.status(), e.message());
            throw new AiException(classify(e), PROVIDER, e.message(), e);
        } catch (Exception e) {
            log.error("Erro na chamada Gemini SDK: ", e);
            throw new AiException(AiException.Kind.OTHER, PROVIDER, e.getMessage(), e);
        }
    }

    private static AiException.Kind classify(ApiException e) {
        String detail = (e.status() + " " + e.message()).toUpperCase(Locale.ROOT);
        if (detail.contains("API_KEY_INVALID") || detail.contains("API KEY NOT VALID")) {
            return AiException.Kind.INVALID_KEY;
        }
        return AiException.kindFromHttpStatus(e.code());
    }

    private String extractTextFromResponse(GenerateContentResponse response) {
        if (response.candidates().isEmpty() || response.candidates().get().isEmpty()) return "{}";
        Candidate candidate = response.candidates().get().getFirst();
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
