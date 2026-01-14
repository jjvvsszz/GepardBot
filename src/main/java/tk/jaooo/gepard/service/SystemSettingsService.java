package tk.jaooo.gepard.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tk.jaooo.gepard.model.GlobalConfig;
import tk.jaooo.gepard.repository.GlobalConfigRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemSettingsService {

    private final GlobalConfigRepository repository;
    private final PasswordEncoder passwordEncoder;

    // Fallbacks
    @Value("${gepard.telegram.bot-token:}") private String envBotToken;
    @Value("${gepard.telegram.bot-username:}") private String envBotUsername;
    @Value("${spring.security.oauth2.client.registration.google.client-id:}") private String envClientId;
    @Value("${spring.security.oauth2.client.registration.google.client-secret:}") private String envClientSecret;

    @PostConstruct
    public void init() {
        if (repository.count() == 0) {
            log.info("⚙️ Inicializando configurações globais...");

            String tempPassword = java.util.UUID.randomUUID().toString().substring(0, 8);

            log.warn("⚠️ ATENÇÃO: PRIMEIRO ACESSO ADMIN ⚠️");
            log.warn("Usuário: admin");
            log.warn("Senha Temporária: {}", tempPassword);
            log.warn("Você será obrigado a mudar esta senha ao logar.");

            GlobalConfig config = GlobalConfig.builder()
                    .id(1L)
                    .adminUsername("admin")
                    .adminPasswordHash(passwordEncoder.encode(tempPassword))
                    .adminSetupRequired(true)
                    .telegramBotToken(envBotToken)
                    .telegramBotUsername(envBotUsername)
                    .googleClientId(envClientId)
                    .googleClientSecret(envClientSecret)
                    .geminiModel("gemini-1.5-flash")
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
    }

    @Transactional
    public void updateAdminCredentials(String newUsername, String newPassword) {
        GlobalConfig config = getConfig();
        config.setAdminUsername(newUsername);
        config.setAdminPasswordHash(passwordEncoder.encode(newPassword));
        config.setAdminSetupRequired(false); // Libera o acesso
        repository.save(config);
    }
}
