package tk.jaooo.gepard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Service
public class DeepSeekService {

    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    private static final String PROVIDER = "DeepSeek";
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final ObjectMapper objectMapper;

    public DeepSeekService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String generateContent(String systemPrompt, String promptText, String modelName, String apiKey) {
        log.info("Chamando DeepSeek API com modelo: {}", modelName);

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", modelName);

            ArrayNode messages = objectMapper.createArrayNode();

            ObjectNode systemMsg = objectMapper.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt + "\n" + AiPrompts.JSON_FORMAT);
            messages.add(systemMsg);

            ObjectNode userMsg = objectMapper.createObjectNode();
            userMsg.put("role", "user");
            userMsg.put("content", promptText);
            messages.add(userMsg);

            body.set("messages", messages);
            body.put("stream", false);

            ObjectNode responseFormat = objectMapper.createObjectNode();
            responseFormat.put("type", "json_object");
            body.set("response_format", responseFormat);

            String requestBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            try (HttpClient client = HttpClient.newHttpClient()) {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    log.error("DeepSeek API retornou status {}: {}", response.statusCode(), response.body());
                    throw new AiException(AiException.kindFromHttpStatus(response.statusCode()), PROVIDER,
                            "DeepSeek API error: " + response.statusCode(), null);
                }

                JsonNode json = objectMapper.readTree(response.body());
                String content = json.path("choices").path(0).path("message").path("content").asText();

                if (content.isBlank()) {
                    log.error("DeepSeek retornou resposta vazia");
                    return "{}";
                }

                return content;
            }
        } catch (AiException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
            log.error("DeepSeek indisponivel", e);
            throw new AiException(AiException.Kind.UNAVAILABLE, PROVIDER, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Erro na chamada DeepSeek API", e);
            throw new AiException(AiException.Kind.OTHER, PROVIDER, "Falha ao chamar DeepSeek: " + e.getMessage(), e);
        }
    }
}
