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

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityMigrationService implements ApplicationRunner {

    private final AppUserRepository userRepository;

    @Override
    @Transactional
    public void run(@NonNull ApplicationArguments args) {
        List<AppUser> vulnerableUsers = userRepository.findUsersWithUnencryptedData();

        if (vulnerableUsers.isEmpty()) {
            log.info("🛡️ Verificação de segurança: Todos os dados já estão criptografados no banco.");
            return;
        }

        log.info("Encontrados {} usuários com dados expostos. Iniciando criptografia...", vulnerableUsers.size());

        for (AppUser user : vulnerableUsers) {
            user.setUpdatedAt(LocalDateTime.now());
            userRepository.saveAndFlush(user);
        }

        log.info("MIGRAÇÃO CRÍTICA CONCLUÍDA: {} usuários protegidos.", vulnerableUsers.size());
    }
}
