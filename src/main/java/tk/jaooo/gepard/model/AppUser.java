package tk.jaooo.gepard.model;

import jakarta.persistence.*;
import lombok.*;
import tk.jaooo.gepard.util.StringCryptoConverter;

import java.time.Instant;
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

    @Convert(converter = StringCryptoConverter.class)
    @Column
    private String geminiApiKey;

    @Convert(converter = StringCryptoConverter.class)
    @Column(length = 4096)
    private String deepSeekApiKey;

    @Column(length = 50, name = "preferred_model")
    private String preferredTextModel;

    @Column(length = 50)
    private String preferredFileModel;

    @Convert(converter = StringCryptoConverter.class)
    @Column(length = 4096)
    private String googleRefreshToken;

    @Convert(converter = StringCryptoConverter.class)
    @Column(length = 4096)
    private String googleAccessToken;

    private Instant googleTokenIssuedAt;

    private String webLoginToken;

    private LocalDateTime webLoginTokenExpiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public boolean hasGeminiKey() {
        return geminiApiKey != null && !geminiApiKey.isBlank();
    }

    public boolean hasDeepSeekKey() {
        return deepSeekApiKey != null && !deepSeekApiKey.isBlank();
    }

    public boolean isGoogleConnected() {
        return googleRefreshToken != null;
    }

    public boolean isWebTokenExpired() {
        return webLoginToken == null || webLoginTokenExpiresAt == null
                || !LocalDateTime.now().isBefore(webLoginTokenExpiresAt);
    }
}
