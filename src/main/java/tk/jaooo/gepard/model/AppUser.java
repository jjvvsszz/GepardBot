package tk.jaooo.gepard.model;

import jakarta.persistence.*;
import lombok.*;
import tk.jaooo.gepard.util.EventTimes;
import tk.jaooo.gepard.util.StringCryptoConverter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

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

    /** Fuso IANA (ex: America/Manaus). Nulo = padrao do sistema. */
    @Column(length = 64)
    private String timeZone;

    /** Agenda do Google usada pelo bot. Nulo = agenda principal. */
    @Column(length = 255)
    private String calendarId;

    private Boolean dailySummaryEnabled;

    /** Horario local do resumo diario, HH:mm. */
    @Column(length = 5)
    private String dailySummaryTime;

    private LocalDate lastDailySummaryDate;

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

    public ZoneId zone() {
        if (timeZone != null && !timeZone.isBlank()) {
            try {
                return ZoneId.of(timeZone);
            } catch (Exception ignored) { }
        }
        return EventTimes.DEFAULT_ZONE;
    }

    public String calendar() {
        return calendarId != null && !calendarId.isBlank() ? calendarId : "primary";
    }

    public boolean isDailySummaryOn() {
        return Boolean.TRUE.equals(dailySummaryEnabled);
    }

    public LocalTime summaryTime() {
        try {
            return LocalTime.parse(dailySummaryTime);
        } catch (Exception e) {
            return LocalTime.of(7, 0);
        }
    }
}
