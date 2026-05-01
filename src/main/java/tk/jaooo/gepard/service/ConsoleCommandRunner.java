package tk.jaooo.gepard.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConsoleCommandRunner implements CommandLineRunner {

    private final SystemSettingsService settingsService;

    @Override
    public void run(String... args) {
        Thread consoleThread = new Thread(() -> {
            log.info("🖥️ Console de comandos iniciado. Digite 'help' para ver os comandos disponiveis.");

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isBlank()) continue;

                    String[] parts = line.split("\\s+", 2);
                    String command = parts[0].toLowerCase();

                    switch (command) {
                        case "adminsenha" -> {
                            if (parts.length < 2) {
                                log.warn("❌ Uso: adminsenha <nova_senha>");
                            } else {
                                String newPassword = parts[1];
                                settingsService.updatePassword(newPassword);
                                log.info("✅ Senha do admin alterada com sucesso.");
                            }
                        }
                        case "help" -> {
                            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                            log.info("📋 Comandos disponiveis:");
                            log.info("  adminsenha <senha>  — Altera a senha do painel admin");
                            log.info("  help               — Mostra esta lista");
                            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                        }
                        default -> log.warn("❌ Comando desconhecido: '{}'. Digite 'help' para ajuda.", command);
                    }
                }
            } catch (Exception e) {
                log.error("Erro no console de comandos", e);
            }
        }, "console-command-runner");

        consoleThread.setDaemon(true);
        consoleThread.start();
    }
}
