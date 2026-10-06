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
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventReminder;
import com.google.api.services.calendar.model.Events;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tk.jaooo.gepard.model.AppUser;
import tk.jaooo.gepard.model.dto.EventExtractionDTO;
import tk.jaooo.gepard.repository.AppUserRepository;
import tk.jaooo.gepard.util.EventTimes;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

    public Event createEvent(AppUser user, EventExtractionDTO eventData, Long chatId, Integer messageId) throws IOException, GeneralSecurityException {
        requireAuth(user);

        if (eventData.summary() == null || eventData.summary().isBlank()) {
            throw new IllegalArgumentException("Não consegui identificar o título do evento.");
        }
        ZoneId zone = user.zone();
        EventTimes.TimeRange range = EventTimes.fromAi(eventData.startDateTime(), eventData.endDateTime(), zone);

        Calendar service = buildCalendarService(getValidCredential(user));

        Event event = new Event()
                .setSummary(eventData.summary())
                .setLocation(eventData.location())
                .setDescription(eventData.description());
        setRange(event, range, zone);

        if (eventData.reminders() != null && !eventData.reminders().isEmpty()) {
            event.setReminders(toReminders(eventData.reminders()));
        } else {
            event.setReminders(new Event.Reminders().setUseDefault(true));
        }

        String rrule = EventTimes.normalizeRecurrence(eventData.recurrence());
        if (rrule != null) event.setRecurrence(List.of(rrule));

        List<EventAttendee> attendees = toAttendees(eventData.attendees());
        if (!attendees.isEmpty()) event.setAttendees(attendees);

        if (chatId != null && messageId != null) {
            Map<String, String> extended = new HashMap<>();
            extended.put("telegramChatId", String.valueOf(chatId));
            extended.put("telegramMessageId", String.valueOf(messageId));
            event.setExtendedProperties(new Event.ExtendedProperties().setShared(extended));
        }

        Calendar.Events.Insert insert = service.events().insert(user.calendar(), event);
        if (!attendees.isEmpty()) insert.setSendUpdates("all");
        return insert.execute();
    }

    public List<Event> listUpcomingEvents(AppUser user, int maxResults) throws IOException, GeneralSecurityException {
        return listEvents(user, null, Instant.now(), null, maxResults);
    }

    /**
     * Lista eventos (ocorrencias individuais) entre {@code from} e {@code to} (opcional),
     * filtrando pelo texto {@code query} (opcional) com a busca do Google.
     */
    public List<Event> listEvents(AppUser user, String query, Instant from, Instant to, int maxResults)
            throws IOException, GeneralSecurityException {
        requireAuth(user);
        Calendar service = buildCalendarService(getValidCredential(user));

        Calendar.Events.List request = service.events().list(user.calendar())
                .setMaxResults(maxResults)
                .setOrderBy("startTime")
                .setSingleEvents(true)
                .setTimeMin(new DateTime(from.toEpochMilli()));
        if (to != null) request.setTimeMax(new DateTime(to.toEpochMilli()));
        if (query != null && !query.isBlank()) request.setQ(query);

        Events events = request.execute();
        return events.getItems() != null ? events.getItems() : new ArrayList<>();
    }

    /**
     * Eventos com horario que ocupam parte de {@code range} (ignora dia inteiro, "disponivel" e recusados),
     * exceto {@code ignoreEventId}.
     */
    public List<Event> findConflicts(AppUser user, EventTimes.TimeRange range, String ignoreEventId)
            throws IOException, GeneralSecurityException {
        if (range.allDay()) return List.of();
        return listEvents(user, null, range.start().toInstant(), range.end().toInstant(), 20).stream()
                .filter(e -> e.getStart() != null && e.getStart().getDateTime() != null)
                .filter(e -> !"transparent".equals(e.getTransparency()))
                .filter(e -> ignoreEventId == null || !ignoreEventId.equals(e.getId()))
                .filter(e -> e.getAttendees() == null || e.getAttendees().stream()
                        .noneMatch(a -> Boolean.TRUE.equals(a.getSelf()) && "declined".equals(a.getResponseStatus())))
                .toList();
    }

    public Event getEvent(AppUser user, String eventId) throws IOException, GeneralSecurityException {
        Calendar service = buildCalendarService(getValidCredential(user));
        return service.events().get(user.calendar(), eventId).execute();
    }

    /**
     * Aplica uma alteracao parcial. Campos nulos permanecem; mudando so o inicio, a duracao e mantida.
     * Com {@code wholeSeries}, altera o evento recorrente inteiro deslocando-o pela mesma diferenca de horario.
     */
    public Event updateEvent(AppUser user, String eventId, EventExtractionDTO patch, boolean wholeSeries)
            throws IOException, GeneralSecurityException {
        Calendar service = buildCalendarService(getValidCredential(user));
        ZoneId zone = user.zone();
        String calendarId = user.calendar();

        Event instance = service.events().get(calendarId, eventId).execute();
        EventTimes.TimeRange current = EventTimes.fromEvent(instance.getStart(), instance.getEnd(), zone);
        EventTimes.TimeRange updated = EventTimes.applyPatch(current, patch.startDateTime(), patch.endDateTime(), zone);
        boolean notify = patch.attendees() != null && !patch.attendees().isEmpty();

        if (!wholeSeries || instance.getRecurringEventId() == null) {
            applyFields(instance, patch);
            if (!updated.equals(current)) setRange(instance, updated, zone);
            Calendar.Events.Update update = service.events().update(calendarId, eventId, instance);
            if (notify) update.setSendUpdates("all");
            return update.execute();
        }

        Event master = service.events().get(calendarId, instance.getRecurringEventId()).execute();
        applyFields(master, patch);
        if (!updated.equals(current)) {
            if (updated.allDay() != current.allDay()
                    || !updated.start().toLocalDate().equals(current.start().toLocalDate())) {
                throw new IllegalArgumentException(
                        "Para mudar o dia de uma série inteira, altere só esta ocorrência ou edite no Google Agenda.");
            }
            EventTimes.TimeRange masterRange = EventTimes.fromEvent(master.getStart(), master.getEnd(), zone);
            ZonedDateTime newStart = masterRange.start().plus(Duration.between(current.start(), updated.start()));
            setRange(master, new EventTimes.TimeRange(newStart, newStart.plus(updated.duration()), masterRange.allDay()), zone);
        }
        Calendar.Events.Update update = service.events().update(calendarId, master.getId(), master);
        if (notify) update.setSendUpdates("all");
        return update.execute();
    }

    public void deleteEvent(AppUser user, String eventId) throws IOException, GeneralSecurityException {
        Calendar service = buildCalendarService(getValidCredential(user));
        service.events().delete(user.calendar(), eventId).execute();
    }

    /** Desfaz uma exclusao: eventos apagados ficam com status "cancelled" e podem voltar a "confirmed". */
    public Event restoreDeleted(AppUser user, String eventId) throws IOException, GeneralSecurityException {
        Calendar service = buildCalendarService(getValidCredential(user));
        return service.events().patch(user.calendar(), eventId, new Event().setStatus("confirmed")).execute();
    }

    /** Desfaz uma alteracao, regravando os campos editaveis de um snapshot anterior. */
    public Event restoreSnapshot(AppUser user, Event snapshot) throws IOException, GeneralSecurityException {
        Calendar service = buildCalendarService(getValidCredential(user));
        Event fields = new Event()
                .setSummary(snapshot.getSummary())
                .setLocation(snapshot.getLocation() != null ? snapshot.getLocation() : "")
                .setDescription(snapshot.getDescription() != null ? snapshot.getDescription() : "")
                .setStart(snapshot.getStart())
                .setEnd(snapshot.getEnd())
                .setReminders(snapshot.getReminders());
        if (snapshot.getAttendees() != null) fields.setAttendees(snapshot.getAttendees());
        return service.events().patch(user.calendar(), snapshot.getId(), fields).execute();
    }

    /** Agendas em que o usuario pode criar eventos (para escolha no painel). */
    public List<CalendarListEntry> listWritableCalendars(AppUser user) throws IOException, GeneralSecurityException {
        requireAuth(user);
        Calendar service = buildCalendarService(getValidCredential(user));
        CalendarList list = service.calendarList().list().setMinAccessRole("writer").execute();
        return list.getItems() != null ? list.getItems() : List.of();
    }

    public List<Event> findEventsByExtendedProperties(AppUser user, String property1, String property2) throws IOException, GeneralSecurityException {
        requireAuth(user);
        Calendar service = buildCalendarService(getValidCredential(user));

        Events events = service.events().list(user.calendar())
                .setSharedExtendedProperty(List.of(property1, property2))
                .setMaxResults(10)
                .setSingleEvents(true)
                .execute();

        return events.getItems() != null ? events.getItems() : new ArrayList<>();
    }

    private static void requireAuth(AppUser user) {
        if (user.getGoogleRefreshToken() == null && user.getGoogleAccessToken() == null) {
            throw new IllegalStateException("Usuário não autenticado no Google. Use /config para conectar.");
        }
    }

    private static void applyFields(Event event, EventExtractionDTO patch) {
        if (patch.summary() != null && !patch.summary().isBlank()) event.setSummary(patch.summary());
        if (patch.location() != null) event.setLocation(patch.location());
        if (patch.description() != null) event.setDescription(patch.description());
        if (patch.reminders() != null && !patch.reminders().isEmpty()) event.setReminders(toReminders(patch.reminders()));
        List<EventAttendee> attendees = toAttendees(patch.attendees());
        if (!attendees.isEmpty()) event.setAttendees(attendees);
    }

    private static void setRange(Event event, EventTimes.TimeRange range, ZoneId zone) {
        event.setStart(EventTimes.toEventDateTime(range.start(), range.allDay(), zone));
        event.setEnd(EventTimes.toEventDateTime(range.end(), range.allDay(), zone));
    }

    /** Apenas e-mails com formato valido, sem repeticao. */
    public static List<EventAttendee> toAttendees(List<String> emails) {
        if (emails == null) return List.of();
        return emails.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(e -> e.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))
                .map(e -> e.toLowerCase(Locale.ROOT))
                .distinct()
                .map(e -> new EventAttendee().setEmail(e))
                .toList();
    }

    private static Event.Reminders toReminders(List<Integer> minutes) {
        List<EventReminder> overrides = minutes.stream()
                .distinct()
                .limit(5) // limite da API do Google
                .map(m -> new EventReminder().setMethod("popup").setMinutes(m))
                .collect(Collectors.toList());
        return new Event.Reminders().setUseDefault(false).setOverrides(overrides);
    }
}
