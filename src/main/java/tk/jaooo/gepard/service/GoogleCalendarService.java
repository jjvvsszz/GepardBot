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
import com.google.api.services.calendar.model.Events;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tk.jaooo.gepard.model.AppUser;
import tk.jaooo.gepard.model.dto.EventExtractionDTO;
import tk.jaooo.gepard.repository.AppUserRepository;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.*;
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
            @Value("${gepard.base-url}") String baseUrl) {

        tk.jaooo.gepard.model.GlobalConfig config = settingsService.getConfig();

        this.userRepository = userRepository;
        this.clientId = config.getGoogleClientId();
        this.clientSecret = config.getGoogleClientSecret();

        String cleanBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.redirectUri = cleanBaseUrl + "/login/oauth2/code/google";

        log.info("Google Redirect URI configurada para: {}", this.redirectUri);

        if (clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank()) {
            GoogleClientSecrets.Details details = new GoogleClientSecrets.Details();
            details.setClientId(clientId);
            details.setClientSecret(clientSecret);

            GoogleClientSecrets secrets = new GoogleClientSecrets();
            secrets.setWeb(details);

            try {
                final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
                this.flow = new GoogleAuthorizationCodeFlow.Builder(
                        HTTP_TRANSPORT, JSON_FACTORY, secrets, SCOPES)
                        .setAccessType("offline")
                        .build();
            } catch (GeneralSecurityException | IOException e) {
                log.error("Falha critica ao inicializar transporte HTTP seguro para Google Calendar", e);
                throw new RuntimeException("Falha ao inicializar servico do Google Calendar. Verifique a configuracao de rede e JCE.", e);
            }
        } else {
            log.warn("Google Calendar: Client ID ou Client Secret nao configurados. Integracao com Google desabilitada.");
            this.flow = null;
        }
    }

    public String buildAuthorizationUrl(Long telegramId) {
        if (flow == null) throw new IllegalStateException("Google Calendar nao configurado. Configure Client ID e Client Secret no painel admin.");
        return flow.newAuthorizationUrl()
                .setRedirectUri(redirectUri)
                .setState(String.valueOf(telegramId))
                .setAccessType("offline")
                .set("prompt", "consent")
                .build();
    }

    @Transactional
    public void exchangeCodeForTokens(String code, Long telegramId) throws IOException {
        if (flow == null) throw new IllegalStateException("Google Calendar nao configurado.");
        TokenResponse response = flow.newTokenRequest(code)
                .setRedirectUri(redirectUri)
                .execute();

        AppUser user = userRepository.findById(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        user.setGoogleAccessToken(response.getAccessToken());
        if (response.getRefreshToken() != null) {
            user.setGoogleRefreshToken(response.getRefreshToken());
        } else if (user.getGoogleRefreshToken() == null) {
            throw new IllegalStateException("Refresh token nao retornado pelo Google. Reautorize com prompt=consent.");
        }
        user.setGoogleTokenIssuedAt(Instant.now());
        userRepository.save(user);
    }

    private GoogleCredential getValidCredential(AppUser user) throws IOException, GeneralSecurityException {
        GoogleCredential credential = new GoogleCredential.Builder()
                .setTransport(GoogleNetHttpTransport.newTrustedTransport())
                .setJsonFactory(JSON_FACTORY)
                .setClientSecrets(this.clientId, this.clientSecret)
                .build();

        credential.setAccessToken(user.getGoogleAccessToken());
        credential.setRefreshToken(user.getGoogleRefreshToken());

        if (user.getGoogleTokenIssuedAt() != null) {
            long elapsed = Instant.now().getEpochSecond() - user.getGoogleTokenIssuedAt().getEpochSecond();
            if (elapsed >= 3000) {
                try {
                    credential.refreshToken();
                    user.setGoogleAccessToken(credential.getAccessToken());
                    user.setGoogleTokenIssuedAt(Instant.now());
                    userRepository.save(user);
                    log.info("Token Google renovado para usuario {}", user.getTelegramId());
                } catch (IOException e) {
                    log.error("Falha ao renovar token Google para usuario {}", user.getTelegramId(), e);
                    throw new IllegalStateException("Sessao Google expirada. Use /config para reconectar.");
                }
            }
        } else {
            user.setGoogleTokenIssuedAt(Instant.now());
            userRepository.save(user);
        }

        return credential;
    }

    private Calendar buildCalendarService(GoogleCredential credential) throws GeneralSecurityException, IOException {
        return new Calendar.Builder(
                GoogleNetHttpTransport.newTrustedTransport(), JSON_FACTORY, credential)
                .setHttpRequestInitializer(credential)
                .setApplicationName("Gepard Bot")
                .build();
    }

    public String createEvent(AppUser user, EventExtractionDTO eventData) throws IOException, GeneralSecurityException {
        return createEvent(user, eventData, null, null);
    }

    public String createEvent(AppUser user, EventExtractionDTO eventData, Long chatId, Integer messageId) throws IOException, GeneralSecurityException {
        if (user.getGoogleRefreshToken() == null && user.getGoogleAccessToken() == null) {
            throw new IllegalStateException("Usuario nao autenticado.");
        }

        if (eventData.summary() == null || eventData.summary().isBlank()) {
            throw new IllegalArgumentException("Nao consegui identificar o Titulo do evento.");
        }
        if (eventData.startDateTime() == null || eventData.startDateTime().isBlank()) {
            throw new IllegalArgumentException("Nao consegui identificar a DATA e HORA de inicio.");
        }

        GoogleCredential credential = getValidCredential(user);
        Calendar service = buildCalendarService(credential);

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

        if (chatId != null && messageId != null) {
            Map<String, String> extended = new HashMap<>();
            extended.put("telegramChatId", String.valueOf(chatId));
            extended.put("telegramMessageId", String.valueOf(messageId));
            event.setExtendedProperties(new Event.ExtendedProperties().setShared(extended));
        }

        Event createdEvent = service.events().insert("primary", event).execute();
        return createdEvent.getHtmlLink();
    }

    public List<Event> listUpcomingEvents(AppUser user, int maxResults) throws IOException, GeneralSecurityException {
        if (user.getGoogleRefreshToken() == null && user.getGoogleAccessToken() == null) {
            throw new IllegalStateException("Usuario nao autenticado.");
        }

        GoogleCredential credential = getValidCredential(user);
        Calendar service = buildCalendarService(credential);

        Events events = service.events().list("primary")
                .setMaxResults(maxResults)
                .setOrderBy("startTime")
                .setSingleEvents(true)
                .setTimeMin(new DateTime(System.currentTimeMillis()))
                .execute();

        return events.getItems() != null ? events.getItems() : new ArrayList<>();
    }

    public List<Event> searchEvents(AppUser user, String query, int maxResults) throws IOException, GeneralSecurityException {
        if (user.getGoogleRefreshToken() == null && user.getGoogleAccessToken() == null) {
            throw new IllegalStateException("Usuario nao autenticado.");
        }

        GoogleCredential credential = getValidCredential(user);
        Calendar service = buildCalendarService(credential);

        Events events = service.events().list("primary")
                .setQ(query)
                .setMaxResults(maxResults)
                .setOrderBy("startTime")
                .setSingleEvents(true)
                .setTimeMin(new DateTime(System.currentTimeMillis()))
                .execute();

        return events.getItems() != null ? events.getItems() : new ArrayList<>();
    }

    public Event getEvent(AppUser user, String eventId) throws IOException, GeneralSecurityException {
        GoogleCredential credential = getValidCredential(user);
        Calendar service = buildCalendarService(credential);
        return service.events().get("primary", eventId).execute();
    }

    public String updateEvent(AppUser user, String eventId, EventExtractionDTO eventData) throws IOException, GeneralSecurityException {
        GoogleCredential credential = getValidCredential(user);
        Calendar service = buildCalendarService(credential);

        Event existing = service.events().get("primary", eventId).execute();

        if (eventData.summary() != null && !eventData.summary().isBlank()) {
            existing.setSummary(eventData.summary());
        }
        if (eventData.location() != null) {
            existing.setLocation(eventData.location());
        }
        if (eventData.description() != null) {
            existing.setDescription(eventData.description());
        }

        String timeZone = "America/Sao_Paulo";
        if (eventData.startDateTime() != null && !eventData.startDateTime().isBlank()) {
            DateTime start = parseDate(eventData.startDateTime());
            DateTime end;
            if (eventData.endDateTime() != null && !eventData.endDateTime().isBlank()) {
                end = parseDate(eventData.endDateTime());
            } else {
                end = new DateTime(start.getValue() + 3600000, start.getTimeZoneShift());
            }
            existing.setStart(new EventDateTime().setDateTime(start).setTimeZone(timeZone));
            existing.setEnd(new EventDateTime().setDateTime(end).setTimeZone(timeZone));
        }

        if (eventData.reminders() != null && !eventData.reminders().isEmpty()) {
            List<EventReminder> reminderList = eventData.reminders().stream()
                    .map(minutes -> new EventReminder().setMethod("popup").setMinutes(minutes))
                    .collect(Collectors.toList());
            existing.setReminders(new Event.Reminders()
                    .setUseDefault(false)
                    .setOverrides(reminderList));
        }

        Event updated = service.events().update("primary", eventId, existing).execute();
        return updated.getHtmlLink();
    }

    public void deleteEvent(AppUser user, String eventId) throws IOException, GeneralSecurityException {
        GoogleCredential credential = getValidCredential(user);
        Calendar service = buildCalendarService(credential);
        service.events().delete("primary", eventId).execute();
    }

    public List<Event> findEventsByExtendedProperties(AppUser user, String property1, String property2) throws IOException, GeneralSecurityException {
        if (user.getGoogleRefreshToken() == null && user.getGoogleAccessToken() == null) {
            throw new IllegalStateException("Usuario nao autenticado.");
        }

        GoogleCredential credential = getValidCredential(user);
        Calendar service = buildCalendarService(credential);

        Events events = service.events().list("primary")
                .setSharedExtendedProperty(List.of(property1, property2))
                .setMaxResults(10)
                .setSingleEvents(true)
                .execute();

        return events.getItems() != null ? events.getItems() : new ArrayList<>();
    }

    private DateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            throw new IllegalArgumentException("Data invalida: string vazia ou nula");
        }
        if (dateStr.length() >= 6
                && !dateStr.endsWith("Z")
                && !dateStr.contains("+")
                && dateStr.charAt(dateStr.length() - 6) != '-') {
            dateStr = dateStr + "-03:00";
        } else if (dateStr.length() < 6 && !dateStr.endsWith("Z") && !dateStr.contains("+")) {
            dateStr = dateStr + "-03:00";
        }
        return new DateTime(dateStr);
    }
}
