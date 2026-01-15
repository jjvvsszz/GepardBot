package tk.jaooo.gepard.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tk.jaooo.gepard.model.AppUser;
import tk.jaooo.gepard.repository.AppUserRepository;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityMigrationService implements ApplicationRunner {

    private final AppUserRepository userRepository;

    @Override
    @Transactional
    public void run(@NonNull ApplicationArguments args) {
        log.info("🔐 Verificando necessidade de migração de criptografia...");

        List<AppUser> users = userRepository.findAll();
        int migratedCount = 0;

        for (AppUser user : users) {
            boolean changed = false;

            if (isUnencrypted(user.getGeminiApiKey())) {
                user.setGeminiApiKey(user.getGeminiApiKey());
                changed = true;
            }

            if (isUnencrypted(user.getGoogleRefreshToken())) {
                user.setGoogleRefreshToken(user.getGoogleRefreshToken());
                changed = true;
            }
            if (isUnencrypted(user.getGoogleAccessToken())) {
                user.setGoogleAccessToken(user.getGoogleAccessToken());
                changed = true;
            }

            if (changed) {
                userRepository.save(user);
                migratedCount++;
            }
        }

        if (migratedCount > 0) {
            log.info("✅ MIGRAÇÃO CONCLUÍDA: {} usuários tiveram suas credenciais criptografadas.", migratedCount);
        } else {
            log.info("🛡️ Todos os dados já estão seguros.");
        }
    }

    private boolean isUnencrypted(String value) {
        return value != null && !value.isBlank() && !value.startsWith("{ENC}");
    }
}
