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

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemSettingsService {

    private final GlobalConfigRepository repository;
    private final PasswordEncoder passwordEncoder;

    @Value("${gepard.telegram.bot-token}") private String envBotToken;
    @Value("${gepard.telegram.bot-username}") private String envBotUsername;
    @Value("${spring.security.oauth2.client.registration.google.client-id}") private String envClientId;
    @Value("${spring.security.oauth2.client.registration.google.client-secret}") private String envClientSecret;

    @PostConstruct
    public void init() {
        GlobalConfig config = repository.findById(1L).orElse(null);

        if (config == null) {
            log.info("⚙️ Banco vazio. Inicializando configurações...");
            createInitialConfig();
        } else {
            if (config.getAdminUsername() == null || config.getAdminPasswordHash() == null) {
                log.warn("⚠️ Banco detectado sem credenciais de Admin. Gerando senha temporária...");
                resetAdminCredentials(config);
            }
        }
    }

    private void createInitialConfig() {
        String tempPassword = UUID.randomUUID().toString().substring(0, 8);
        printAdminCredentials("admin", tempPassword);

        GlobalConfig config = GlobalConfig.builder()
                .id(1L)
                .adminUsername("admin")
                .adminPasswordHash(passwordEncoder.encode(tempPassword))
                .adminSetupRequired(true)
                .telegramBotToken(envBotToken)
                .telegramBotUsername(envBotUsername)
                .googleClientId(envClientId)
                .googleClientSecret(envClientSecret)
                .geminiModel("models/gemini-3-flash-preview")
                .build();
        repository.save(config);
    }

    private void resetAdminCredentials(GlobalConfig config) {
        String tempPassword = UUID.randomUUID().toString().substring(0, 8);
        printAdminCredentials("admin", tempPassword);

        config.setAdminUsername("admin");
        config.setAdminPasswordHash(passwordEncoder.encode(tempPassword));
        config.setAdminSetupRequired(true);
        repository.save(config);
    }

    private void printAdminCredentials(String user, String pass) {
        log.warn("==================================================");
        log.warn("🔐 CREDENCIAIS DE ADMIN TEMPORÁRIAS");
        log.warn("👤 Usuário: {}", user);
        log.warn("🔑 Senha:   {}", pass);
        log.warn("==================================================");
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
        config.setAdminSetupRequired(false);
        repository.save(config);
    }
}
