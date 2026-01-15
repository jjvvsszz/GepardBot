package tk.jaooo.gepard.model;

import jakarta.persistence.*;
import lombok.*;
import tk.jaooo.gepard.util.StringCryptoConverter;

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

    @Column(length = 50)
    private String preferredModel;

    @Convert(converter = StringCryptoConverter.class)
    @Column(length = 4096)
    private String googleRefreshToken;

    @Convert(converter = StringCryptoConverter.class)
    @Column(length = 4096)
    private String googleAccessToken;

    private String webLoginToken;

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

    public boolean hasApiKey() {
        return geminiApiKey != null && !geminiApiKey.isBlank();
    }

    public boolean isGoogleConnected() {
        return googleRefreshToken != null;
    }
}
