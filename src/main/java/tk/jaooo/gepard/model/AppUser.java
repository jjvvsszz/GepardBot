package tk.jaooo.gepard.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "app_users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser {

    @Id
    private Long telegramId;

    private String username;
    private String firstName;

    @Column(length = 100)
    private String geminiApiKey;

    @Column(length = 2048)
    private String googleRefreshToken;

    @Column(length = 2048)
    private String googleAccessToken;

    private String webLoginToken;

    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public boolean hasApiKey() {
        return geminiApiKey != null && !geminiApiKey.isBlank();
    }

    public boolean isGoogleConnected() {
        return googleRefreshToken != null;
    }
}