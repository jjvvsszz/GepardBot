package tk.jaooo.gepard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

@Entity
@Table(name = "global_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GlobalConfig {

    @Id
    private Long id;

    // --- CREDENCIAIS DO ADMIN ---
    private String adminUsername;
    private String adminPasswordHash;
    private boolean adminSetupRequired;

    // --- TELEGRAM ---
    private String telegramBotToken;
    private String telegramBotUsername;

    // --- GOOGLE ---
    @Column(length = 1000)
    private String googleClientId;

    @Column(length = 1000)
    private String googleClientSecret;

    // --- GEMINI ---
    private String geminiModel;
}