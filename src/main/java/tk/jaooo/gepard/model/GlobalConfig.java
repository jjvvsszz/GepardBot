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

    private String telegramBotToken;
    private String telegramBotUsername;

    @Column(length = 1000)
    private String googleClientId;

    @Column(length = 1000)
    private String googleClientSecret;

    private String geminiModel;
}
