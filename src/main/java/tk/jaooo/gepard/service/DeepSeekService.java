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
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final ObjectMapper objectMapper;

    public DeepSeekService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String generateContent(String promptText, String modelName, String apiKey) {
        log.info("Chamando DeepSeek API com modelo: {}", modelName);

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", modelName);

            ArrayNode messages = objectMapper.createArrayNode();

            ObjectNode systemMsg = objectMapper.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", """
                Voce e um assistente de agendamento.
                Fuso: America/Sao_Paulo (-03:00).
                Extraia detalhes do evento do texto do usuario.
                Retorne a resposta em formato JSON.
                
                REGRAS DE LEMBRETES (Reminders):
                1. O campo 'reminders' aceita APENAS numeros inteiros (minutos).
                2. Se o usuario pedir '2 dias antes', CALCULE: 2 * 24 * 60 = 2880. Retorne [2880].
                3. Se pedir '1 semana antes', CALCULE: 7 * 24 * 60 = 10080.
                """);
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
                    throw new RuntimeException("DeepSeek API error: " + response.statusCode());
                }

                JsonNode json = objectMapper.readTree(response.body());
                String content = json.path("choices").path(0).path("message").path("content").asText();

                if (content.isBlank()) {
                    log.error("DeepSeek retornou resposta vazia");
                    return "{}";
                }

                return content;
            }
        } catch (Exception e) {
            log.error("Erro na chamada DeepSeek API", e);
            throw new RuntimeException("Falha ao chamar DeepSeek: " + e.getMessage(), e);
        }
    }
}
