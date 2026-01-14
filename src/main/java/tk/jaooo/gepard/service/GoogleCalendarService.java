package tk.jaooo.gepard.service;

import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.EventReminder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tk.jaooo.gepard.model.AppUser;
import tk.jaooo.gepard.model.dto.EventExtractionDTO;
import tk.jaooo.gepard.repository.AppUserRepository;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GoogleCalendarService {

    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Collections.singletonList("https://www.googleapis.com/auth/calendar");

    private final GoogleAuthorizationCodeFlow flow;
    private final AppUserRepository userRepository;
    private final String redirectUri;
    private final String clientId;
    private final String clientSecret;

    public GoogleCalendarService(
            SystemSettingsService settingsService,
            AppUserRepository userRepository,
            @Value("${gepard.base-url}") String baseUrl) throws GeneralSecurityException, IOException { // Injeção da URL Base

        tk.jaooo.gepard.model.GlobalConfig config = settingsService.getConfig();

        this.userRepository = userRepository;
        this.clientId = config.getGoogleClientId();
        this.clientSecret = config.getGoogleClientSecret();

        String cleanBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.redirectUri = cleanBaseUrl + "/login/oauth2/code/google";

        log.info("Google Redirect URI configurada para: {}", this.redirectUri);

        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();

        GoogleClientSecrets.Details details = new GoogleClientSecrets.Details();
        details.setClientId(clientId);
        details.setClientSecret(clientSecret);

        GoogleClientSecrets secrets = new GoogleClientSecrets();
        secrets.setWeb(details);

        this.flow = new GoogleAuthorizationCodeFlow.Builder(
                HTTP_TRANSPORT, JSON_FACTORY, secrets, SCOPES)
                .setAccessType("offline")
                .build();
    }

    public String buildAuthorizationUrl(Long telegramId) {
        return flow.newAuthorizationUrl()
                .setRedirectUri(redirectUri)
                .setState(String.valueOf(telegramId))
                .setAccessType("offline")
                .set("prompt", "consent")
                .build();
    }

    @Transactional
    public void exchangeCodeForTokens(String code, Long telegramId) throws IOException {
        TokenResponse response = flow.newTokenRequest(code)
                .setRedirectUri(redirectUri)
                .execute();

        AppUser user = userRepository.findById(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        user.setGoogleAccessToken(response.getAccessToken());
        if (response.getRefreshToken() != null) {
            user.setGoogleRefreshToken(response.getRefreshToken());
        }
        userRepository.save(user);
    }

    public String createEvent(AppUser user, EventExtractionDTO eventData) throws IOException, GeneralSecurityException {
        if (user.getGoogleRefreshToken() == null && user.getGoogleAccessToken() == null) {
            throw new IllegalStateException("Usuário não autenticado.");
        }

        if (eventData.summary() == null || eventData.summary().isBlank()) {
            throw new IllegalArgumentException("Não consegui identificar o Título do evento.");
        }
        if (eventData.startDateTime() == null || eventData.startDateTime().isBlank()) {
            throw new IllegalArgumentException("Não consegui identificar a DATA e HORA de início.");
        }

        GoogleCredential credential = new GoogleCredential.Builder()
                .setTransport(GoogleNetHttpTransport.newTrustedTransport())
                .setJsonFactory(JSON_FACTORY)
                .setClientSecrets(this.clientId, this.clientSecret)
                .build();

        credential.setAccessToken(user.getGoogleAccessToken());
        credential.setRefreshToken(user.getGoogleRefreshToken());

        Calendar service = new Calendar.Builder(
                GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, credential)
                .setApplicationName("Gepard Bot")
                .build();

        Event event = new Event()
                .setSummary(eventData.summary())
                .setLocation(eventData.location())
                .setDescription(eventData.description());

        String timeZone = "America/Sao_Paulo";

        DateTime start = parseDate(eventData.startDateTime());
        DateTime end;

        if (eventData.endDateTime() != null && !eventData.endDateTime().isBlank()) {
            end = parseDate(eventData.endDateTime());
        } else {
            end = new DateTime(start.getValue() + 3600000, start.getTimeZoneShift());
        }

        event.setStart(new EventDateTime().setDateTime(start).setTimeZone(timeZone));
        event.setEnd(new EventDateTime().setDateTime(end).setTimeZone(timeZone));

        if (eventData.reminders() != null && !eventData.reminders().isEmpty()) {
            List<EventReminder> reminderList = eventData.reminders().stream()
                    .map(minutes -> new EventReminder().setMethod("popup").setMinutes(minutes))
                    .collect(Collectors.toList());

            Event.Reminders reminders = new Event.Reminders()
                    .setUseDefault(false)
                    .setOverrides(reminderList);

            event.setReminders(reminders);
        } else {
            event.setReminders(new Event.Reminders().setUseDefault(true));
        }

        Event createdEvent = service.events().insert("primary", event).execute();
        return createdEvent.getHtmlLink();
    }

    private DateTime parseDate(String dateStr) {
        if (!dateStr.endsWith("Z") && !dateStr.contains("+") && !String.valueOf(dateStr.charAt(dateStr.length() - 6)).equals("-")) {
            dateStr = dateStr + "-03:00";
        }
        return new DateTime(dateStr);
    }
}
