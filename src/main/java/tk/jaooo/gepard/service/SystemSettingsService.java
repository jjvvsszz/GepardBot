package tk.jaooo.gepard.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tk.jaooo.gepard.model.GlobalConfig;
import tk.jaooo.gepard.repository.GlobalConfigRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemSettingsService {

    private final GlobalConfigRepository repository;

    // Valores iniciais (Fallback do application.yml)
    @Value("${gepard.telegram.bot-token}") private String envBotToken;
    @Value("${gepard.telegram.bot-username}") private String envBotUsername;
    @Value("${spring.security.oauth2.client.registration.google.client-id}") private String envClientId;
    @Value("${spring.security.oauth2.client.registration.google.client-secret}") private String envClientSecret;

    @PostConstruct
    public void init() {
        if (repository.count() == 0) {
            log.info("Inicializando configurações globais com valores de ambiente...");
            GlobalConfig config = GlobalConfig.builder()
                    .id(1L)
                    .telegramBotToken(envBotToken)
                    .telegramBotUsername(envBotUsername)
                    .googleClientId(envClientId)
                    .googleClientSecret(envClientSecret)
                    .geminiModel("models/gemini-3-flash-preview")
                    .build();
            repository.save(config);
        }
    }

    public GlobalConfig getConfig() {
        return repository.findById(1L).orElseThrow(() -> new RuntimeException("Configuração Global não encontrada!"));
    }

    @Transactional
    public void updateConfig(String token, String username, String clientId, String clientSecret, String model) {
        GlobalConfig config = getConfig();
        config.setTelegramBotToken(token);
        config.setTelegramBotUsername(username);
        config.setGoogleClientId(clientId);
        config.setGoogleClientSecret(clientSecret);
        config.setGeminiModel(model);
        repository.save(config);
        // Nota: Para aplicar mudanças de Token do Bot ou Google ID em tempo real,
        // seria necessário reiniciar os Beans. Por simplicidade, pediremos restart manual no painel.
    }
}
