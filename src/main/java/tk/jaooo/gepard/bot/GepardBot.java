package tk.jaooo.gepard.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventReminder;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ForceReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import tk.jaooo.gepard.bot.PendingActions.Action;
import tk.jaooo.gepard.bot.PendingActions.Create;
import tk.jaooo.gepard.bot.PendingActions.Delete;
import tk.jaooo.gepard.bot.PendingActions.Edit;
import tk.jaooo.gepard.bot.PendingActions.Request;
import tk.jaooo.gepard.bot.PendingActions.Select;
import tk.jaooo.gepard.bot.PendingActions.Share;
import tk.jaooo.gepard.bot.PendingActions.UndoCreate;
import tk.jaooo.gepard.bot.PendingActions.UndoDelete;
import tk.jaooo.gepard.bot.PendingActions.UndoEdit;
import tk.jaooo.gepard.config.BotConfig;
import tk.jaooo.gepard.model.AppUser;
import tk.jaooo.gepard.model.dto.AiResponseDTO;
import tk.jaooo.gepard.model.dto.EventExtractionDTO;
import tk.jaooo.gepard.repository.AppUserRepository;
import tk.jaooo.gepard.service.AiException;
import tk.jaooo.gepard.service.AiService;
import tk.jaooo.gepard.service.GoogleCalendarService;
import tk.jaooo.gepard.service.SystemSettingsService;
import tk.jaooo.gepard.util.EventTimes;
import tk.jaooo.gepard.util.EventTimes.TimeRange;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static tk.jaooo.gepard.bot.BotTexts.escapeHtml;
import static tk.jaooo.gepard.bot.BotTexts.summaryOf;
import static tk.jaooo.gepard.bot.BotTexts.truncate;

@Slf4j
@Component
public class GepardBot implements SpringLongPollingBot, LongPollingUpdateConsumer {

    /** Limite de download de arquivos da Bot API do Telegram. */
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final int MAX_CANDIDATES = 8;
    private static final int MAX_EVENTS_PER_MESSAGE = 10;
    private static final Duration MAX_QUERY_WINDOW = Duration.ofDays(62);
    private static final Duration MIN_FREE_SLOT = Duration.ofMinutes(30);
    private static final LocalTime FREE_DAY_START = LocalTime.of(8, 0);
    private static final LocalTime FREE_DAY_END = LocalTime.of(22, 0);
    private static final Pattern SUMMARY_TIME = Pattern.compile("^(\\d{1,2})(?:[:h](\\d{2}))?h?$");

    private final SystemSettingsService settingsService;
    private final BotConfig botConfig;
    private final AiService aiService;
    private final GoogleCalendarService calendarService;
    private final AppUserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    private final PendingActions pending = new PendingActions();
    private final Map<Long, List<Event>> recentEvents = new ConcurrentHashMap<>();
    /** Desfazer ja usados, para o fim do prazo nao recolocar botoes na mensagem "Desfeito". */
    private final Set<String> usedUndos = ConcurrentHashMap.newKeySet();

    /**
     * Cada update roda numa virtual thread; updates do mesmo usuario sao encadeados para manter a ordem,
     * mas um usuario esperando a IA nao bloqueia os demais.
     */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("gepard-scheduler").factory());
    private final Map<Long, CompletableFuture<Void>> userQueues = new ConcurrentHashMap<>();

    private record ScoredEvent(Event event, int score) {}

    private record Media(byte[] bytes, String mimeType) {}

    public GepardBot(
            SystemSettingsService settingsService,
            BotConfig botConfig,
            AiService aiService,
            GoogleCalendarService calendarService,
            AppUserRepository userRepository,
            ObjectMapper objectMapper,
            @Value("${gepard.base-url}") String baseUrl) {
        this.settingsService = settingsService;
        this.botConfig = botConfig;
        this.aiService = aiService;
        this.calendarService = calendarService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private TelegramClient getTelegramClient() {
        return botConfig.getTelegramClient();
    }

    @Override
    public String getBotToken() { return settingsService.getConfig().getTelegramBotToken(); }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() { return this; }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
        executor.shutdown();
    }

    private String botUsername() {
        String name = settingsService.getConfig().getTelegramBotUsername();
        if (name == null) return "";
        return name.startsWith("@") ? name.substring(1) : name;
    }

    // ================================================================ despacho

    @Override
    public void consume(List<Update> updates) {
        updates.forEach(this::enqueue);
    }

    private void enqueue(Update update) {
        Long userId = userIdOf(update);
        if (userId == null) {
            executor.submit(() -> handleSafely(update));
            return;
        }
        CompletableFuture<Void> next = userQueues.compute(userId, (id, previous) ->
                (previous != null ? previous : CompletableFuture.<Void>completedFuture(null))
                        .thenRunAsync(() -> handleSafely(update), executor));
        // fora do compute: se a tarefa ja terminou, o callback roda aqui e nao pode alterar o mapa recursivamente
        next.whenComplete((r, e) -> userQueues.remove(userId, next));
    }

    private static Long userIdOf(Update update) {
        if (update.hasCallbackQuery()) return update.getCallbackQuery().getFrom().getId();
        if (update.hasEditedMessage() && update.getEditedMessage().getFrom() != null) {
            return update.getEditedMessage().getFrom().getId();
        }
        if (update.hasMessage() && update.getMessage().getFrom() != null) return update.getMessage().getFrom().getId();
        return null;
    }

    private void handleSafely(Update update) {
        try {
            if (update.hasCallbackQuery()) {
                handleCallbackQuery(update.getCallbackQuery());
            } else if (update.hasEditedMessage()) {
                handleEditedMessage(update.getEditedMessage());
            } else if (update.hasMessage() && update.getMessage().getFrom() != null) {
                handleMessage(update.getMessage());
            }
        } catch (Exception e) {
            log.error("Erro nao tratado ao processar update {}", update.getUpdateId(), e);
        }
    }

    // ================================================================ grupos

    /**
     * Em grupos o bot so responde quando e chamado: mencao (@bot), resposta a uma mensagem dele ou comando.
     * Retorna o texto sem a mencao, ou null se a mensagem nao e para o bot.
     */
    static String addressedText(Message message, String botUsername) {
        String raw = message.hasText() ? message.getText() : Objects.requireNonNullElse(message.getCaption(), "");
        raw = raw.trim();
        String mention = "@" + botUsername;
        boolean mentioned = !botUsername.isBlank() && raw.toLowerCase(Locale.ROOT).contains(mention.toLowerCase(Locale.ROOT));
        boolean replyToBot = message.getReplyToMessage() != null
                && message.getReplyToMessage().getFrom() != null
                && Boolean.TRUE.equals(message.getReplyToMessage().getFrom().getIsBot())
                && botUsername.equalsIgnoreCase(Objects.requireNonNullElse(message.getReplyToMessage().getFrom().getUserName(), ""));
        boolean command = raw.startsWith("/");
        if (!mentioned && !replyToBot && !command) return null;
        if (botUsername.isBlank()) return raw;
        return raw.replaceAll("(?i)" + Pattern.quote(mention), "").trim();
    }

    // ================================================================ mensagens

    private void handleMessage(Message message) {
        Long telegramId = message.getFrom().getId();
        Long chatId = message.getChatId();
        boolean group = !message.isUserMessage();

        String text;
        if (group) {
            text = addressedText(message, botUsername());
            if (text == null) return;
        } else {
            text = message.hasText() ? message.getText().trim()
                    : message.getCaption() != null ? message.getCaption().trim() : "";
        }

        try {
            AppUser user = userRepository.findById(telegramId).orElseGet(() ->
                    userRepository.save(AppUser.builder()
                            .telegramId(telegramId)
                            .username(message.getFrom().getUserName())
                            .firstName(message.getFrom().getFirstName())
                            .build())
            );

            String command = text.startsWith("/") ? text.split("[\\s@]", 2)[0].toLowerCase(Locale.ROOT) : "";
            String argument = text.contains(" ") ? text.substring(text.indexOf(' ') + 1).trim() : "";
            switch (command) {
                case "/start", "/ajuda", "/help" -> {
                    handleStart(chatId, user, group);
                    return;
                }
                case "/config" -> {
                    if (group) {
                        sendText(chatId, "🔒 Por segurança, use /config na conversa privada comigo.");
                        return;
                    }
                    String link = generateSettingsURL(user);
                    sendHtml(chatId, "⚙️ <a href=\"" + escapeHtml(link) + "\">Abrir configurações</a>\n"
                            + "<i>O link vale por 24 horas.</i>");
                    return;
                }
                case "/eventos", "/events" -> {
                    if (requireReady(chatId, user, group)) handleListEvents(chatId, user);
                    return;
                }
                case "/cancelar", "/cancel" -> {
                    pending.clearUser(telegramId);
                    recentEvents.remove(telegramId);
                    sendText(chatId, "✅ Tudo cancelado. Nenhuma alteração pendente.");
                    return;
                }
                case "/resumo" -> {
                    handleSummaryCommand(chatId, user, argument);
                    return;
                }
                case "/fuso" -> {
                    handleTimeZoneCommand(chatId, user, argument);
                    return;
                }
                default -> { }
            }

            if (!user.hasGeminiKey() && !group) {
                handleApiKeyFlow(message, user);
                return;
            }
            if (!requireReady(chatId, user, group)) return;

            if (text.matches("(?i)^/?deletar\\s+\\d+$")) {
                handleDeleteByIndex(chatId, user, text);
                return;
            }

            Optional<String> adjustId = findAdjustTarget(message);
            if (adjustId.isPresent() && !text.isBlank()) {
                handleAdjust(chatId, user, adjustId.get(), text);
                return;
            }

            handleSmartScheduling(message, user, text);

        } catch (Exception e) {
            log.error("Erro ao processar mensagem do usuario {}", telegramId, e);
            sendText(chatId, errorMessage(e));
        }
    }

    /** Verifica se o usuario tem chave de IA e agenda conectada; senao explica o que falta. */
    private boolean requireReady(Long chatId, AppUser user, boolean group) {
        if (group && (!user.hasGeminiKey() || user.getGoogleRefreshToken() == null)) {
            String link = botUsername().isBlank() ? "" : " https://t.me/" + botUsername() + "?start=1";
            sendText(chatId, "👋 Para usar o Gepard, configure-o primeiro na conversa privada comigo:" + link);
            return false;
        }
        if (!user.hasGeminiKey()) {
            sendText(chatId, "🔑 Envie sua Gemini API Key para começar (veja /start).");
            return false;
        }
        if (user.getGoogleRefreshToken() == null) {
            sendHtml(chatId, "📅 Falta só conectar sua agenda: " + googleAuthAnchor(user.getTelegramId()));
            return false;
        }
        return true;
    }

    /** Resposta a uma confirmacao de criacao (ou a mensagem apos tocar em "Ajustar") ajusta o rascunho. */
    private Optional<String> findAdjustTarget(Message message) {
        long userId = message.getFrom().getId();
        if (message.getReplyToMessage() != null) {
            Optional<String> byReply = pending.findCreateByConfirmationMessage(
                    userId, message.getReplyToMessage().getMessageId());
            if (byReply.isPresent()) {
                pending.takeAwaitingAdjust(userId);
                return byReply;
            }
        }
        return pending.takeAwaitingAdjust(userId).filter(id -> pending.get(id).isPresent());
    }

    private void handleStart(Long chatId, AppUser user, boolean group) {
        StringBuilder sb = new StringBuilder();
        sb.append("🐆 <b>Gepard — seu assistente de agenda</b>\n\n");
        sb.append("Eu crio, altero, cancelo e consulto eventos no Google Agenda a partir de texto, áudio, foto ou PDF.\n\n");

        if (group) {
            sb.append("👥 <b>Em grupos</b>, me mencione (@").append(escapeHtml(botUsername()))
                    .append(") ou responda a uma mensagem minha. Cada pessoa usa a própria agenda, ")
                    .append("e os outros membros podem tocar em ➕ para copiar o evento.\n");
            sb.append("Convidados por e-mail: <i>@").append(escapeHtml(botUsername()))
                    .append(" reunião sexta 15h com ana@exemplo.com</i>");
        } else if (!user.hasGeminiKey()) {
            sb.append("🔑 <b>Para começar</b>, envie sua <b>Gemini API Key</b> (começa com <code>AIza</code>).\n");
            sb.append("É gratuita: <a href=\"https://aistudio.google.com/app/apikey\">aistudio.google.com/app/apikey</a>");
        } else if (user.getGoogleRefreshToken() == null) {
            sb.append("📅 Falta só conectar sua agenda: ").append(googleAuthAnchor(user.getTelegramId()));
        } else {
            sb.append("✅ Tudo pronto! Experimente:\n");
            sb.append("• <i>Jantar com a Maria sexta 20h no Outback</i>\n");
            sb.append("• <i>Dentista seg 10h e reunião qua 15h</i>\n");
            sb.append("• <i>Academia toda seg e qua às 7h</i>\n");
            sb.append("• <i>Adiar o almoço de amanhã para quinta</i>\n");
            sb.append("• <i>O que tenho amanhã?</i> / <i>Estou livre sexta à tarde?</i>\n");
            sb.append("• Foto de um convite, print de conversa ou um áudio\n\n");
            sb.append("<b>Comandos</b>\n");
            sb.append("/eventos — próximos eventos\n");
            sb.append("/resumo 7h — resumo diário da agenda\n");
            sb.append("/fuso — ver ou mudar seu fuso horário\n");
            sb.append("/config — chaves de IA, modelos, agenda e conta Google\n");
            sb.append("/cancelar — descarta confirmações pendentes");
        }
        sendHtml(chatId, sb.toString());
    }

    private String googleAuthAnchor(Long telegramId) {
        String authLink = calendarService.buildAuthorizationUrl(telegramId);
        return "<a href=\"" + escapeHtml(authLink) + "\">Conectar Google Agenda</a>";
    }

    private void handleListEvents(Long chatId, AppUser user) {
        sendTypingAction(chatId);
        try {
            List<Event> events = calendarService.listUpcomingEvents(user, 10);

            if (events.isEmpty()) {
                sendText(chatId, "📅 Você não tem eventos próximos.");
                return;
            }

            recentEvents.put(user.getTelegramId(), events);

            StringBuilder sb = new StringBuilder("📅 <b>Próximos eventos</b>\n\n");
            for (int i = 0; i < events.size(); i++) {
                Event e = events.get(i);
                sb.append(i + 1).append(". <b>").append(escapeHtml(summaryOf(e))).append("</b>")
                        .append(e.getRecurringEventId() != null ? " 🔁" : "").append("\n");
                sb.append("    ⏰ ").append(EventTimes.formatStart(BotTexts.rangeOf(e, user.zone()))).append("\n");
            }
            sb.append("\nPara apagar, envie <code>deletar 2</code> ou peça naturalmente: <i>cancelar o dentista</i>.");
            sendHtml(chatId, sb.toString());

        } catch (Exception e) {
            log.error("Erro ao listar eventos para usuario {}", user.getTelegramId(), e);
            sendText(chatId, errorMessage(e));
        }
    }

    private void handleDeleteByIndex(Long chatId, AppUser user, String text) throws Exception {
        int idx = Integer.parseInt(text.replaceAll("[^0-9]", "")) - 1;
        List<Event> events = recentEvents.get(user.getTelegramId());

        if (events == null || idx < 0 || idx >= events.size()) {
            sendText(chatId, "❌ Número inválido. Use /eventos para ver a lista atualizada.");
            return;
        }
        showDeleteConfirm(chatId, user, events.get(idx), null);
    }

    private void handleApiKeyFlow(Message message, AppUser user) {
        String text = message.hasText() ? message.getText().trim() : "";
        Long chatId = message.getChatId();

        if (text.startsWith("AIza")) {
            user.setGeminiApiKey(text);
            userRepository.save(user);
            sendHtml(chatId, "✅ Gemini Key salva!\n\n📅 Agora conecte sua agenda: " + googleAuthAnchor(user.getTelegramId()));
        } else if (text.startsWith("sk-")) {
            sendText(chatId, "⚠️ Envie primeiro sua Gemini API Key (começa com AIza).\n"
                    + "Ela é obrigatória para ler fotos, áudios e PDFs. A DeepSeek Key é opcional e pode ser adicionada depois em /config.");
        } else {
            sendHtml(chatId, """
                    👋 Para começar, envie sua <b>Gemini API Key</b> (começa com <code>AIza</code>).

                    É gratuita: <a href="https://aistudio.google.com/app/apikey">aistudio.google.com/app/apikey</a>
                    Depois você pode adicionar uma chave DeepSeek e escolher modelos em /config.""");
        }
    }

    private String generateSettingsURL(AppUser user) {
        String token = UUID.randomUUID().toString();
        user.setWebLoginToken(token);
        user.setWebLoginTokenExpiresAt(LocalDateTime.now().plusHours(24));
        userRepository.save(user);
        return baseUrl + "/user/config?token=" + token;
    }

    // ================================================================ resumo diario e fuso

    /** Interpreta "7h", "07:30", "7", "19h15". Retorna null se invalido. */
    static LocalTime parseSummaryTime(String value) {
        Matcher m = SUMMARY_TIME.matcher(value.trim().toLowerCase(Locale.ROOT).replace(" ", ""));
        if (!m.matches()) return null;
        int h = Integer.parseInt(m.group(1));
        int min = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
        if (h > 23 || min > 59) return null;
        return LocalTime.of(h, min);
    }

    private void handleSummaryCommand(Long chatId, AppUser user, String argument) {
        String arg = argument.toLowerCase(Locale.ROOT);
        if (arg.isBlank()) {
            String status = user.isDailySummaryOn()
                    ? "✅ Ativado às " + user.summaryTime() + "."
                    : "Desativado.";
            sendHtml(chatId, "☀️ <b>Resumo diário</b>: " + status + "\n\n"
                    + "Toda manhã eu mando a sua agenda do dia.\n"
                    + "• <code>/resumo 7h</code> — ativar (ou mudar o horário)\n"
                    + "• <code>/resumo off</code> — desativar");
            return;
        }
        if (Set.of("off", "desligar", "desativar", "parar", "nao", "não").contains(arg)) {
            user.setDailySummaryEnabled(false);
            userRepository.save(user);
            sendText(chatId, "🔕 Resumo diário desativado.");
            return;
        }
        LocalTime time = parseSummaryTime(arg);
        if (time == null) {
            sendText(chatId, "❌ Horário inválido. Exemplos: /resumo 7h, /resumo 06:30");
            return;
        }
        user.setDailySummaryEnabled(true);
        user.setDailySummaryTime(time.toString());
        // Se o horario de hoje ja passou, o primeiro resumo sai amanha.
        if (!ZonedDateTime.now(user.zone()).toLocalTime().isBefore(time)) {
            user.setLastDailySummaryDate(LocalDate.now(user.zone()));
        }
        userRepository.save(user);
        sendText(chatId, "☀️ Combinado! Todo dia às " + time + " (" + user.zone().getId() + ") eu mando sua agenda.");
    }

    /** Aceita um id IANA ("America/Manaus") ou so a cidade ("Manaus", "Lisbon"). */
    static ZoneId parseZone(String value) {
        String v = value.trim().replace(' ', '_');
        if (v.isEmpty()) return null;
        try {
            return ZoneId.of(v);
        } catch (Exception ignored) { }
        String city = normalize(v);
        return ZoneId.getAvailableZoneIds().stream()
                .filter(id -> id.contains("/") && normalize(id.substring(id.lastIndexOf('/') + 1)).equals(city))
                .sorted()
                .findFirst()
                .map(ZoneId::of)
                .orElse(null);
    }

    private void handleTimeZoneCommand(Long chatId, AppUser user, String argument) {
        if (argument.isBlank()) {
            ZonedDateTime now = ZonedDateTime.now(user.zone());
            sendHtml(chatId, "🌎 Seu fuso: <b>" + user.zone().getId() + "</b> (agora "
                    + now.toLocalTime().withSecond(0).withNano(0) + ")\n\n"
                    + "Para mudar: <code>/fuso Manaus</code>, <code>/fuso America/Recife</code>, <code>/fuso Lisbon</code>");
            return;
        }
        ZoneId zone = parseZone(argument);
        if (zone == null) {
            sendText(chatId, "❌ Não reconheci esse fuso. Exemplos: /fuso Manaus, /fuso America/Cuiaba, /fuso Lisbon");
            return;
        }
        user.setTimeZone(zone.getId());
        userRepository.save(user);
        sendText(chatId, "✅ Fuso alterado para " + zone.getId() + " (agora "
                + ZonedDateTime.now(zone).toLocalTime().withSecond(0).withNano(0) + ").");
    }

    // ================================================================ interpretacao

    private void handleSmartScheduling(Message message, AppUser user, String text) throws Exception {
        Long chatId = message.getChatId();

        Media media;
        try {
            media = extractMedia(message);
        } catch (IllegalArgumentException e) {
            sendText(chatId, "📎 " + e.getMessage());
            return;
        }

        if (text.isBlank() && media == null) {
            sendText(chatId, "Envie um texto, áudio, foto ou PDF descrevendo o compromisso. 🙂");
            return;
        }
        if (text.isBlank()) text = "Extraia o compromisso desta mídia.";

        sendTypingAction(chatId);
        AiResponseDTO response = aiService.interpret(text, media != null ? media.bytes() : null,
                media != null ? media.mimeType() : null, user);

        Request request = new Request(text, media != null ? media.bytes() : null, media != null ? media.mimeType() : null);
        route(chatId, user, response, request, message.getMessageId());
    }

    private void route(Long chatId, AppUser user, AiResponseDTO response, Request request, Integer sourceMessageId)
            throws Exception {
        boolean hasQuery = response.getSearchQuery() != null && !response.getSearchQuery().isBlank();

        if (response.isQuery()) {
            handleQuery(chatId, user, response);
            return;
        }
        if ((response.isDelete() || response.isEdit()) && hasQuery) {
            handleFindAndAct(chatId, user, response, request);
            return;
        }
        if (response.isNone() || response.isDelete() || response.isEdit()) {
            sendHtml(chatId, """
                    🤔 Não identifiquei um compromisso nessa mensagem.

                    Experimente algo como:
                    • <i>Dentista dia 15 às 14h</i>
                    • <i>Adiar a reunião de amanhã para as 16h</i>
                    • <i>O que tenho na sexta?</i>""");
            return;
        }

        List<EventExtractionDTO> valid = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (EventExtractionDTO dto : response.toEventExtractionDTOs()) {
            String problem = validateForCreate(dto, user.zone());
            if (problem == null) valid.add(dto);
            else problems.add(problem);
        }
        if (valid.isEmpty()) {
            sendText(chatId, "❌ " + problems.getFirst()
                    + " Tente descrever com mais detalhes (ex: \"Reunião amanhã às 15h\").");
            return;
        }
        if (valid.size() > MAX_EVENTS_PER_MESSAGE) valid = valid.subList(0, MAX_EVENTS_PER_MESSAGE);

        String id = pending.put(new Create(user.getTelegramId(), chatId, sourceMessageId, List.copyOf(valid)));
        String text = createConfirmationText(user, valid);
        if (!problems.isEmpty()) {
            text += "\n\n⚠️ <i>" + problems.size() + " item(ns) ignorado(s): " + escapeHtml(problems.getFirst()) + "</i>";
        }
        Integer msgId = sendOrEdit(chatId, null, text, createKeyboard(id, valid.size()));
        pending.setMessageId(id, msgId);
    }

    /** Retorna o problema encontrado ou null se o rascunho pode ser criado. */
    private static String validateForCreate(EventExtractionDTO dto, ZoneId zone) {
        if (dto.summary() == null || dto.summary().isBlank()) return "Não consegui identificar o título do evento.";
        if (dto.startDateTime() == null || dto.startDateTime().isBlank()) return "Não consegui identificar a data e hora.";
        try {
            EventTimes.fromAi(dto.startDateTime(), dto.endDateTime(), zone);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        return null;
    }

    // ================================================================ consultas

    private void handleQuery(Long chatId, AppUser user, AiResponseDTO response) throws Exception {
        ZoneId zone = user.zone();
        LocalDate today = LocalDate.now(zone);
        TimeRange window;
        try {
            window = EventTimes.fromAi(response.getQueryStart(), response.getQueryEnd(), zone);
        } catch (IllegalArgumentException e) {
            ZonedDateTime start = today.atStartOfDay(zone);
            window = new TimeRange(start, start.plusDays(1), true);
        }
        if (window.duration().compareTo(MAX_QUERY_WINDOW) > 0) {
            window = new TimeRange(window.start(), window.start().plus(MAX_QUERY_WINDOW), window.allDay());
        }
        // Datas sem hora viram dias inteiros 00:00 -> 00:00; tratamos como janela comum daqui em diante.
        TimeRange period = new TimeRange(window.start(), window.end(), false);

        String searchQuery = response.getSearchQuery();
        boolean bySearch = searchQuery != null && !searchQuery.isBlank() && !Boolean.TRUE.equals(response.getCheckFree());
        List<Event> events = calendarService.listEvents(user, bySearch ? extractKeywords(searchQuery).getFirst() : null,
                period.start().toInstant(), period.end().toInstant(), 50);

        if (bySearch) {
            List<Event> ranked = rankByKeywords(events, extractKeywords(searchQuery));
            if (ranked.isEmpty()) {
                // A busca do Google diferencia acentos: compara localmente todos os eventos do periodo.
                ranked = rankByKeywords(calendarService.listEvents(user, null,
                        period.start().toInstant(), period.end().toInstant(), 250), extractKeywords(searchQuery));
            }
            if (!ranked.isEmpty()) events = ranked;
            if (events.isEmpty()) {
                sendText(chatId, "🔍 Não encontrei \"" + searchQuery + "\" entre "
                        + EventTimes.formatPeriod(period, today) + ".");
                return;
            }
            sendHtml(chatId, "🔍 <b>" + escapeHtml(searchQuery) + "</b>\n\n" + BotTexts.agenda(events, zone));
            return;
        }

        StringBuilder sb = new StringBuilder("📅 <b>").append(EventTimes.formatPeriod(period, today)).append("</b>\n\n");
        if (events.isEmpty()) {
            sb.append(Boolean.TRUE.equals(response.getCheckFree())
                    ? "✅ Você está livre — nada marcado nesse período."
                    : "Nada marcado. 🎉");
        } else {
            sb.append(BotTexts.agenda(events, zone));
            if (Boolean.TRUE.equals(response.getCheckFree())) {
                List<TimeRange> free = freeSlotsFor(period, events, zone);
                sb.append("\n");
                if (free.isEmpty()) {
                    sb.append("🔴 Sem horários livres de pelo menos 30 min.");
                } else {
                    boolean multiDay = !free.getFirst().start().toLocalDate().equals(free.getLast().start().toLocalDate());
                    sb.append("🟢 <b>Livre:</b> ").append(free.stream()
                            .map(f -> multiDay ? EventTimes.formatStart(f).replaceAll(" \\d{2}:\\d{2}$", "") + " "
                                    + EventTimes.formatTimes(f) : EventTimes.formatTimes(f))
                            .collect(Collectors.joining(", ")));
                }
            }
        }
        if (events.size() >= 50) sb.append("\n<i>Mostrando os 50 primeiros.</i>");
        sendHtml(chatId, sb.toString());
    }

    /** Horarios livres; em perguntas sobre dias inteiros considera so o periodo acordado (08h-22h). */
    private static List<TimeRange> freeSlotsFor(TimeRange period, List<Event> events, ZoneId zone) {
        List<TimeRange> busy = events.stream().map(e -> BotTexts.rangeOf(e, zone)).toList();
        boolean wholeDays = period.start().toLocalTime().equals(LocalTime.MIDNIGHT)
                && period.end().toLocalTime().equals(LocalTime.MIDNIGHT);
        if (!wholeDays) return EventTimes.freeSlots(period, busy, MIN_FREE_SLOT);

        List<TimeRange> free = new ArrayList<>();
        for (LocalDate d = period.start().toLocalDate(); d.isBefore(period.end().toLocalDate()) && free.size() < 20; d = d.plusDays(1)) {
            TimeRange day = new TimeRange(d.atTime(FREE_DAY_START).atZone(zone), d.atTime(FREE_DAY_END).atZone(zone), false);
            free.addAll(EventTimes.freeSlots(day, busy, MIN_FREE_SLOT));
        }
        return free;
    }

    // ================================================================ ajuste do rascunho

    private void handleAdjust(Long chatId, AppUser user, String id, String text) throws Exception {
        if (!(pending.get(id).orElse(null) instanceof Create create)) {
            sendText(chatId, "⚠️ Essa confirmação expirou. Envie o evento novamente.");
            return;
        }
        if (create.dtos().size() > 1) {
            sendText(chatId, "Para ajustar vários eventos, cancele e envie o pedido de novo com as correções.");
            return;
        }
        sendTypingAction(chatId);

        AiResponseDTO patch = aiService.patch(describeDraft(create.single()), text, null, null, user);
        if (patch.isDelete()) {
            removeKeyboard(chatId, pending.getMessageId(id).orElse(null));
            pending.remove(id);
            sendText(chatId, "✖️ Ok, não vou criar esse evento.");
            return;
        }

        EventExtractionDTO dto = mergeDraft(create.single(), patch.toEventExtractionDTO(), user.zone());
        String problem = validateForCreate(dto, user.zone());
        if (problem != null) {
            sendText(chatId, "❌ " + problem);
            return;
        }

        // A confirmacao antiga perde os botoes; a nova (com o mesmo id) fica no fim da conversa.
        removeKeyboard(chatId, pending.getMessageId(id).orElse(null));
        pending.replace(id, new Create(create.userId(), create.chatId(), create.sourceMessageId(), List.of(dto)));
        Integer msgId = sendOrEdit(chatId, null, createConfirmationText(user, List.of(dto)), createKeyboard(id, 1));
        pending.setMessageId(id, msgId);
    }

    private String describeDraft(EventExtractionDTO dto) throws Exception {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("summary", dto.summary());
        node.put("startDateTime", dto.startDateTime());
        node.put("endDateTime", dto.endDateTime());
        node.put("location", dto.location());
        node.put("description", dto.description());
        node.putPOJO("reminders", dto.reminders());
        node.put("recurrence", dto.recurrence());
        node.putPOJO("attendees", dto.attendees());
        return objectMapper.writeValueAsString(node);
    }

    static EventExtractionDTO mergeDraft(EventExtractionDTO current, EventExtractionDTO patch, ZoneId zone) {
        TimeRange range = EventTimes.applyPatch(
                EventTimes.fromAi(current.startDateTime(), current.endDateTime(), zone),
                patch.startDateTime(), patch.endDateTime(), zone);
        String[] times = EventTimes.toAiStrings(range);
        return new EventExtractionDTO(
                notBlankOr(patch.summary(), current.summary()),
                patch.location() != null ? patch.location() : current.location(),
                patch.description() != null ? patch.description() : current.description(),
                times[0],
                times[1],
                patch.reminders() != null && !patch.reminders().isEmpty() ? patch.reminders() : current.reminders(),
                patch.recurrence() != null ? patch.recurrence() : current.recurrence(),
                patch.attendees() != null && !patch.attendees().isEmpty() ? patch.attendees() : current.attendees());
    }

    private static String notBlankOr(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    // ================================================================ busca de eventos

    static List<String> extractKeywords(String searchQuery) {
        Set<String> stopwords = Set.of("de", "da", "do", "das", "dos",
                "em", "no", "na", "nos", "nas", "para", "pro", "pra",
                "com", "que", "um", "uma", "uns", "umas",
                "ja", "já", "era", "foi", "está", "esta",
                "seu", "sua", "ele", "ela", "meu", "minha", "esse", "essa",
                "a", "as", "o", "os", "e", "é");

        String[] words = searchQuery.toLowerCase().split("\\s+");
        List<String> keywords = new ArrayList<>();
        for (String w : words) {
            if (!stopwords.contains(w) && w.length() > 1) {
                keywords.add(w);
            }
        }
        if (keywords.isEmpty()) {
            keywords.add(searchQuery.toLowerCase().trim());
        }
        return keywords;
    }

    /** Minusculas e sem acentos, para "almoco" casar com "Almoço". */
    static String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    static List<Event> rankByKeywords(List<Event> events, List<String> keywords) {
        List<String> normalized = keywords.stream().map(GepardBot::normalize).toList();
        List<ScoredEvent> scored = new ArrayList<>();
        for (Event e : events) {
            String summary = normalize(e.getSummary());
            int score = 0;
            for (String kw : normalized) {
                if (!kw.isBlank() && summary.contains(kw)) score++;
            }
            if (score > 0) scored.add(new ScoredEvent(e, score));
        }
        // sort estavel: empates mantem a ordem cronologica
        scored.sort(Comparator.comparingInt(ScoredEvent::score).reversed());
        return scored.stream().map(ScoredEvent::event).collect(Collectors.toList());
    }

    private List<Event> findEventCandidates(AppUser user, String searchQuery, String searchDate)
            throws IOException, GeneralSecurityException {
        ZoneId zone = user.zone();
        List<String> keywords = extractKeywords(searchQuery);
        ZonedDateTime startOfToday = LocalDate.now(zone).atStartOfDay(zone);

        // 1. O usuario disse quando o evento acontece: olha so aquele dia.
        LocalDate date = parseLocalDate(searchDate);
        if (date != null) {
            ZonedDateTime dayStart = date.atStartOfDay(zone);
            List<Event> day = calendarService.listEvents(user, null, dayStart.toInstant(),
                    dayStart.plusDays(1).toInstant(), 50);
            List<Event> ranked = rankByKeywords(day, keywords);
            if (!ranked.isEmpty()) return limit(ranked);
            if (!day.isEmpty() && day.size() <= MAX_CANDIDATES) return day;
        }

        // 2. Busca textual do Google a partir de hoje (inclui eventos que ja comecaram).
        List<Event> byQuery = calendarService.listEvents(user, keywords.getFirst(), startOfToday.toInstant(), null, 20);
        List<Event> ranked = rankByKeywords(byQuery, keywords);
        if (!ranked.isEmpty()) return limit(ranked);

        // 3. Sem acento/variacao a busca do Google pode falhar: compara localmente nos proximos 90 dias.
        List<Event> upcoming = calendarService.listEvents(user, null, startOfToday.toInstant(),
                startOfToday.plusDays(90).toInstant(), 250);
        ranked = rankByKeywords(upcoming, keywords);
        if (!ranked.isEmpty()) return limit(ranked);

        // 4. O Google achou pela descricao/local, mesmo sem a palavra no titulo.
        return limit(byQuery);
    }

    private static List<Event> limit(List<Event> events) {
        return events.size() > MAX_CANDIDATES ? new ArrayList<>(events.subList(0, MAX_CANDIDATES)) : events;
    }

    private static LocalDate parseLocalDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim().substring(0, Math.min(10, value.trim().length())));
        } catch (Exception e) {
            return null;
        }
    }

    private void handleFindAndAct(Long chatId, AppUser user, AiResponseDTO response, Request request) throws Exception {
        boolean isDelete = response.isDelete();
        String searchQuery = response.getSearchQuery();
        List<Event> matches = findEventCandidates(user, searchQuery, response.getSearchDate());

        if (matches.isEmpty()) {
            sendText(chatId, "🔍 Não encontrei eventos com \"" + searchQuery + "\".\n"
                    + "Tente outra palavra do título ou veja sua agenda com /eventos.");
            return;
        }

        if (matches.size() == 1) {
            if (isDelete) {
                showDeleteConfirm(chatId, user, matches.getFirst(), null);
            } else {
                prepareEdit(chatId, user, matches.getFirst(), request, null);
            }
            return;
        }

        String id = pending.put(new Select(user.getTelegramId(), chatId, isDelete, request, matches));
        StringBuilder sb = new StringBuilder(isDelete ? "🗑️ " : "✏️ ");
        sb.append("Encontrei ").append(matches.size()).append(" eventos. Qual deles você quer ")
                .append(isDelete ? "<b>apagar</b>" : "<b>alterar</b>").append("?");

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            Event e = matches.get(i);
            String label = truncate(summaryOf(e), 28) + " · " + EventTimes.formatStart(BotTexts.rangeOf(e, user.zone()));
            rows.add(new InlineKeyboardRow(button(label, "sel:" + id + ":" + i)));
        }
        rows.add(new InlineKeyboardRow(button("✖️ Cancelar", "no:" + id)));

        Integer msgId = sendOrEdit(chatId, null, sb.toString(), InlineKeyboardMarkup.builder().keyboard(rows).build());
        pending.setMessageId(id, msgId);
    }

    // ================================================================ edicao e exclusao

    /**
     * Segunda etapa da edicao: com o evento em maos, pede a IA apenas o que muda.
     * {@code editMessageId}: mensagem a reaproveitar (ex: a lista de selecao), ou null para enviar uma nova.
     */
    private void prepareEdit(Long chatId, AppUser user, Event candidate, Request request, Integer editMessageId)
            throws Exception {
        sendTypingAction(chatId);
        ZoneId zone = user.zone();
        Event event = calendarService.getEvent(user, candidate.getId());
        TimeRange current = BotTexts.rangeOf(event, zone);

        AiResponseDTO response = aiService.patch(describeEvent(event, current), request.text(),
                request.media(), request.mimeType(), user);
        if (response.isDelete()) {
            showDeleteConfirm(chatId, user, event, editMessageId);
            return;
        }
        EventExtractionDTO patch = response.toEventExtractionDTO();
        TimeRange updated = EventTimes.applyPatch(current, patch.startDateTime(), patch.endDateTime(), zone);

        List<String> changes = new ArrayList<>();
        String oldSummary = summaryOf(event);
        if (patch.summary() != null && !patch.summary().isBlank() && !patch.summary().equals(oldSummary)) {
            changes.add("📝 " + escapeHtml(oldSummary) + " → <b>" + escapeHtml(patch.summary()) + "</b>");
        }
        if (!updated.equals(current)) {
            changes.add("⏰ <s>" + EventTimes.format(current) + "</s>\n     → <b>" + EventTimes.format(updated) + "</b>");
        }
        String oldLocation = Objects.requireNonNullElse(event.getLocation(), "");
        if (patch.location() != null && !patch.location().equals(oldLocation)) {
            changes.add("📍 " + (oldLocation.isBlank() ? "(sem local)" : escapeHtml(oldLocation))
                    + " → <b>" + (patch.location().isBlank() ? "(sem local)" : escapeHtml(patch.location())) + "</b>");
        }
        if (patch.description() != null && !patch.description().equals(Objects.requireNonNullElse(event.getDescription(), ""))) {
            changes.add("🗒️ Descrição → <i>" + escapeHtml(truncate(patch.description(), 200)) + "</i>");
        }
        if (patch.reminders() != null && !patch.reminders().isEmpty()
                && !patch.reminders().equals(reminderMinutes(event))) {
            changes.add("🔔 " + EventTimes.formatReminders(patch.reminders()));
        }
        List<EventAttendee> newAttendees = GoogleCalendarService.toAttendees(patch.attendees());
        if (!newAttendees.isEmpty()) {
            changes.add("👥 " + escapeHtml(newAttendees.stream().map(EventAttendee::getEmail)
                    .collect(Collectors.joining(", "))) + " <i>(receberão convite)</i>");
        }

        if (changes.isEmpty()) {
            sendOrEdit(chatId, editMessageId, "🤔 Não identifiquei o que mudar em <b>" + escapeHtml(oldSummary)
                    + "</b>.\nDiga, por exemplo: <i>mudar " + escapeHtml(oldSummary.toLowerCase()) + " para sexta às 15h</i>.", null);
            return;
        }

        boolean recurring = event.getRecurringEventId() != null;
        String id = pending.put(new Edit(user.getTelegramId(), chatId, event.getId(), recurring, patch));

        StringBuilder sb = new StringBuilder("✏️ <b>Alterar evento?</b>\n\n");
        if (patch.summary() == null || patch.summary().isBlank() || patch.summary().equals(oldSummary)) {
            sb.append("📝 <b>").append(escapeHtml(oldSummary)).append("</b>\n");
        }
        changes.forEach(c -> sb.append(c).append("\n"));
        if (!updated.equals(current)) {
            sb.append(conflictText(calendarService.findConflicts(user, updated, event.getId()), zone));
        }
        if (recurring) sb.append("\n🔁 Este evento se repete.");

        InlineKeyboardMarkup keyboard = recurring
                ? keyboard(List.of(button("Só esta", "ok:" + id), button("Todas", "all:" + id)),
                           List.of(button("✖️ Cancelar", "no:" + id)))
                : keyboard(List.of(button("✅ Alterar", "ok:" + id), button("✖️ Cancelar", "no:" + id)));

        Integer msgId = sendOrEdit(chatId, editMessageId, sb.toString(), keyboard);
        pending.setMessageId(id, msgId);
    }

    private String describeEvent(Event event, TimeRange range) throws Exception {
        String[] times = EventTimes.toAiStrings(range);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("summary", event.getSummary());
        node.put("startDateTime", times[0]);
        node.put("endDateTime", times[1]);
        node.put("allDay", range.allDay());
        node.put("location", event.getLocation());
        node.put("description", event.getDescription());
        node.putPOJO("reminders", reminderMinutes(event));
        if (event.getAttendees() != null) {
            node.putPOJO("attendees", event.getAttendees().stream().map(EventAttendee::getEmail).toList());
        }
        return objectMapper.writeValueAsString(node);
    }

    private static List<Integer> reminderMinutes(Event event) {
        if (event.getReminders() == null || event.getReminders().getOverrides() == null) return List.of();
        return event.getReminders().getOverrides().stream().map(EventReminder::getMinutes).toList();
    }

    private void showDeleteConfirm(Long chatId, AppUser user, Event candidate, Integer editMessageId) throws Exception {
        Event event = calendarService.getEvent(user, candidate.getId());
        boolean recurring = event.getRecurringEventId() != null;

        String id = pending.put(new Delete(user.getTelegramId(), chatId, event.getId(),
                event.getRecurringEventId(), summaryOf(event)));

        StringBuilder sb = new StringBuilder("🗑️ <b>Apagar evento?</b>\n\n");
        sb.append("📝 <b>").append(escapeHtml(summaryOf(event))).append("</b>\n");
        sb.append("⏰ ").append(EventTimes.format(BotTexts.rangeOf(event, user.zone()))).append("\n");
        if (event.getLocation() != null && !event.getLocation().isBlank()) {
            sb.append("📍 ").append(escapeHtml(event.getLocation())).append("\n");
        }
        if (recurring) sb.append("\n🔁 Este evento se repete.");

        InlineKeyboardMarkup keyboard = recurring
                ? keyboard(List.of(button("Só esta", "ok:" + id), button("Toda a série", "all:" + id)),
                           List.of(button("✖️ Cancelar", "no:" + id)))
                : keyboard(List.of(button("🗑️ Apagar", "ok:" + id), button("✖️ Cancelar", "no:" + id)));

        Integer msgId = sendOrEdit(chatId, editMessageId, sb.toString(), keyboard);
        pending.setMessageId(id, msgId);
    }

    // ================================================================ mensagem editada no Telegram

    private void handleEditedMessage(Message edited) {
        if (edited.getFrom() == null) return;
        Long telegramId = edited.getFrom().getId();
        Long chatId = edited.getChatId();
        Integer messageId = edited.getMessageId();
        String text = edited.isUserMessage()
                ? (edited.hasText() ? edited.getText().trim()
                    : edited.getCaption() != null ? edited.getCaption().trim() : "")
                : addressedText(edited, botUsername());
        if (text == null || text.isBlank() || text.startsWith("/")) return;

        try {
            AppUser user = userRepository.findById(telegramId).orElse(null);
            if (user == null || !user.hasGeminiKey() || user.getGoogleRefreshToken() == null) return;

            // 1. A mensagem gerou um rascunho ainda nao confirmado: atualiza o rascunho.
            Optional<String> draftId = pending.findCreateBySourceMessage(telegramId, chatId, messageId);
            if (draftId.isPresent()) {
                sendTypingAction(chatId);
                AiResponseDTO response = aiService.interpret(text, null, null, user);
                List<EventExtractionDTO> dtos = response.toEventExtractionDTOs().stream()
                        .filter(d -> validateForCreate(d, user.zone()) == null)
                        .limit(MAX_EVENTS_PER_MESSAGE)
                        .toList();
                if (response.isNone() || response.isEdit() || response.isDelete() || response.isQuery() || dtos.isEmpty()) {
                    sendText(chatId, "🤔 Não consegui entender a mensagem editada. O rascunho anterior continua valendo.");
                    return;
                }
                pending.replace(draftId.get(), new Create(telegramId, chatId, messageId, dtos));
                Integer msgId = sendOrEdit(chatId, pending.getMessageId(draftId.get()).orElse(null),
                        createConfirmationText(user, dtos), createKeyboard(draftId.get(), dtos.size()));
                pending.setMessageId(draftId.get(), msgId);
                return;
            }

            // 2. A mensagem ja criou um evento: propoe a alteracao correspondente.
            List<Event> matched = calendarService.findEventsByExtendedProperties(user,
                    "telegramChatId=" + chatId, "telegramMessageId=" + messageId);
            if (matched.size() > 1) {
                sendText(chatId, "Essa mensagem criou vários eventos. Peça a alteração diretamente, "
                        + "ex: \"mudar o dentista para 11h\".");
            } else if (matched.size() == 1) {
                Request request = new Request("O usuario reescreveu a mensagem que criou este evento. "
                        + "Ajuste o evento para refletir o novo texto completo: \"" + text + "\"", null, null);
                prepareEdit(chatId, user, matched.getFirst(), request, null);
            }
            // 3. Qualquer outra edicao (ex: corrigir um typo numa conversa antiga) e ignorada.

        } catch (Exception e) {
            log.error("Erro ao processar mensagem editada do usuario {}", telegramId, e);
            sendText(chatId, errorMessage(e));
        }
    }

    // ================================================================ botoes

    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        String data = Objects.requireNonNullElse(callbackQuery.getData(), "");
        Long chatId = callbackQuery.getMessage().getChatId();
        Integer messageId = callbackQuery.getMessage().getMessageId();
        Long telegramId = callbackQuery.getFrom().getId();

        String[] parts = data.split(":");
        String verb = parts[0];
        String id = parts.length > 1 ? parts[1] : "";
        Action action = pending.get(id).orElse(null);

        if (action == null) {
            answerCallback(callbackQuery, "Essa opção expirou.", false);
            if (!"add".equals(verb)) removeKeyboard(chatId, messageId);
            return;
        }
        if ("add".equals(verb) && action instanceof Share share) {
            handleShareCallback(callbackQuery, share);
            return;
        }
        if (action.userId() != telegramId) {
            answerCallback(callbackQuery, "Só quem fez o pedido pode usar este botão.", false);
            return;
        }

        try {
            AppUser user = userRepository.findById(telegramId)
                    .orElseThrow(() -> new IllegalStateException("Usuário não encontrado. Envie /start."));

            switch (verb) {
                case "ok", "all" -> {
                    answerCallback(callbackQuery, null, false);
                    confirm(chatId, messageId, user, id, action, "all".equals(verb), !callbackQuery.getMessage().isUserMessage());
                }
                case "no" -> {
                    pending.remove(id);
                    answerCallback(callbackQuery, "Cancelado", false);
                    sendOrEdit(chatId, messageId, "✖️ Cancelado. Nada foi alterado.", null);
                }
                case "adj" -> {
                    answerCallback(callbackQuery, null, false);
                    pending.awaitAdjust(telegramId, id);
                    SendMessage sm = SendMessage.builder()
                            .chatId(chatId)
                            .text("✏️ O que devo mudar? Ex: \"às 21h\", \"no sábado\", \"local: Outback\", \"lembrete 1 dia antes\".")
                            .replyMarkup(ForceReplyKeyboard.builder().forceReply(true)
                                    .inputFieldPlaceholder("ex: às 21h").build())
                            .build();
                    getTelegramClient().execute(sm);
                }
                case "undo" -> {
                    answerCallback(callbackQuery, null, false);
                    undo(chatId, messageId, user, id, action);
                }
                case "sel" -> {
                    if (!(action instanceof Select select) || parts.length < 3) {
                        answerCallback(callbackQuery, null, false);
                        return;
                    }
                    int idx = Integer.parseInt(parts[2]);
                    if (idx < 0 || idx >= select.candidates().size()) {
                        answerCallback(callbackQuery, "Opção inválida.", false);
                        return;
                    }
                    answerCallback(callbackQuery, null, false);
                    pending.remove(id);
                    Event event = select.candidates().get(idx);
                    if (select.delete()) {
                        showDeleteConfirm(chatId, user, event, messageId);
                    } else {
                        prepareEdit(chatId, user, event, select.request(), messageId);
                    }
                }
                default -> answerCallback(callbackQuery, null, false);
            }

        } catch (Exception e) {
            log.error("Erro ao processar callback do usuario {}", telegramId, e);
            answerCallback(callbackQuery, null, false);
            sendText(chatId, errorMessage(e));
        }
    }

    private void confirm(Long chatId, Integer messageId, AppUser user, String id, Action action,
                         boolean wholeSeries, boolean group) throws Exception {
        long userId = user.getTelegramId();
        switch (action) {
            case Create c -> {
                List<Event> created = new ArrayList<>();
                String failure = null;
                for (EventExtractionDTO dto : c.dtos()) {
                    try {
                        created.add(calendarService.createEvent(user, dto, c.chatId(), c.sourceMessageId()));
                    } catch (Exception e) {
                        log.error("Falha ao criar evento para usuario {}", userId, e);
                        failure = errorMessage(e);
                        break;
                    }
                }
                pending.remove(id);
                if (created.isEmpty()) {
                    sendOrEdit(chatId, messageId, escapeHtml(Objects.requireNonNullElse(failure, "❌ Falha ao criar.")), null);
                    return;
                }
                StringBuilder sb = new StringBuilder(created.size() == 1
                        ? "✅ <b>Agendado!</b>\n\n" : "✅ <b>" + created.size() + " eventos agendados!</b>\n\n");
                for (Event e : created) {
                    sb.append(eventSummaryText(e, user.zone()))
                            .append("<a href=\"").append(escapeHtml(e.getHtmlLink())).append("\">Ver no Google Agenda</a>\n\n");
                }
                if (failure != null) sb.append("⚠️ Os demais não foram criados: ").append(escapeHtml(failure));

                String undoId = pending.put(new UndoCreate(userId, chatId,
                        created.stream().map(Event::getId).toList()), PendingActions.UNDO_TTL);
                String shareId = group
                        ? pending.put(new Share(userId, chatId, c.dtos().subList(0, created.size()),
                                ConcurrentHashMap.newKeySet()), PendingActions.SHARE_TTL)
                        : null;
                showResultWithUndo(chatId, messageId, sb.toString().strip(), undoId, shareId);
            }
            case Edit e -> {
                boolean series = wholeSeries && e.recurring();
                Event instance = calendarService.getEvent(user, e.eventId());
                Event snapshot = series ? calendarService.getEvent(user, instance.getRecurringEventId()) : instance;
                Event updated = calendarService.updateEvent(user, e.eventId(), e.patch(), series);
                pending.remove(id);
                String undoId = pending.put(new UndoEdit(userId, chatId, snapshot), PendingActions.UNDO_TTL);
                showResultWithUndo(chatId, messageId, "✅ <b>" + (series ? "Série alterada!" : "Evento alterado!")
                        + "</b>\n\n" + eventSummaryText(updated, user.zone())
                        + "<a href=\"" + escapeHtml(updated.getHtmlLink()) + "\">Ver no Google Agenda</a>", undoId, null);
            }
            case Delete d -> {
                boolean series = wholeSeries && d.recurringEventId() != null;
                String deletedId = series ? d.recurringEventId() : d.eventId();
                calendarService.deleteEvent(user, deletedId);
                pending.remove(id);
                String undoId = pending.put(new UndoDelete(userId, chatId, deletedId, d.summary()), PendingActions.UNDO_TTL);
                showResultWithUndo(chatId, messageId, "🗑️ " + (series ? "Série" : "Evento") + " <b>"
                        + escapeHtml(d.summary()) + "</b> apagad" + (series ? "a" : "o") + ".", undoId, null);
            }
            default -> { /* demais acoes usam outros verbos */ }
        }
    }

    /** Mostra o resultado com o botao "Desfazer", que some quando o prazo acaba. */
    private void showResultWithUndo(Long chatId, Integer messageId, String html, String undoId, String shareId) {
        Integer resultId = sendOrEdit(chatId, messageId, html, resultKeyboard(undoId, shareId));
        pending.setMessageId(undoId, resultId);
        scheduler.schedule(() -> executor.submit(() -> {
            // So mexe na mensagem se o desfazer nao foi usado (senao ela ja mostra "Desfeito").
            if (usedUndos.remove(undoId)) return;
            pending.remove(undoId);
            InlineKeyboardMarkup remaining = shareId != null && pending.get(shareId).isPresent()
                    ? resultKeyboard(null, shareId) : null;
            setKeyboard(chatId, resultId, remaining);
        }), PendingActions.UNDO_TTL.toMillis(), TimeUnit.MILLISECONDS);
    }

    private InlineKeyboardMarkup resultKeyboard(String undoId, String shareId) {
        List<InlineKeyboardButton> row = new ArrayList<>();
        if (undoId != null) row.add(button("↩️ Desfazer", "undo:" + undoId));
        if (shareId != null) row.add(button("➕ Adicionar à minha agenda", "add:" + shareId));
        return row.isEmpty() ? null : keyboard(row);
    }

    private void undo(Long chatId, Integer messageId, AppUser user, String id, Action action) throws Exception {
        String text = switch (action) {
            case UndoCreate u -> {
                for (String eventId : u.eventIds()) {
                    try {
                        calendarService.deleteEvent(user, eventId);
                    } catch (GoogleJsonResponseException e) {
                        if (e.getStatusCode() != 404 && e.getStatusCode() != 410) throw e;
                    }
                }
                yield u.eventIds().size() == 1 ? "↩️ Desfeito: o evento foi removido." : "↩️ Desfeito: os eventos foram removidos.";
            }
            case UndoEdit u -> {
                calendarService.restoreSnapshot(user, u.snapshot());
                yield "↩️ Desfeito: <b>" + escapeHtml(summaryOf(u.snapshot())) + "</b> voltou ao que era.";
            }
            case UndoDelete u -> {
                calendarService.restoreDeleted(user, u.eventId());
                yield "↩️ Desfeito: <b>" + escapeHtml(u.summary()) + "</b> foi restaurado.";
            }
            default -> null;
        };
        if (text == null) return;
        usedUndos.add(id);
        pending.remove(id);
        // Compartilhamento de eventos que nao existem mais tambem perde o sentido.
        sendOrEdit(chatId, messageId, text, null);
    }

    private void handleShareCallback(CallbackQuery query, Share share) {
        long clickerId = query.getFrom().getId();
        if (clickerId == share.userId()) {
            answerCallback(query, "Esse evento já está na sua agenda.", false);
            return;
        }
        if (share.addedBy().contains(clickerId)) {
            answerCallback(query, "Você já adicionou este evento.", false);
            return;
        }
        AppUser clicker = userRepository.findById(clickerId).orElse(null);
        if (clicker == null || !clicker.hasGeminiKey() || clicker.getGoogleRefreshToken() == null) {
            String link = botUsername().isBlank() ? "" : " t.me/" + botUsername();
            answerCallback(query, "Para adicionar, configure o Gepard na conversa privada comigo primeiro." + link, true);
            return;
        }
        try {
            for (EventExtractionDTO dto : share.dtos()) {
                // Sem convidados: quem copia nao deve reenviar convites.
                EventExtractionDTO copy = new EventExtractionDTO(dto.summary(), dto.location(), dto.description(),
                        dto.startDateTime(), dto.endDateTime(), dto.reminders(), dto.recurrence(), null);
                calendarService.createEvent(clicker, copy, null, null);
            }
            share.addedBy().add(clickerId);
            answerCallback(query, "✅ Adicionado à sua agenda!", false);
        } catch (Exception e) {
            log.error("Falha ao copiar evento para usuario {}", clickerId, e);
            answerCallback(query, errorMessage(e), true);
        }
    }

    // ================================================================ textos

    private String createConfirmationText(AppUser user, List<EventExtractionDTO> dtos)
            throws IOException, GeneralSecurityException {
        ZoneId zone = user.zone();
        StringBuilder sb = new StringBuilder(dtos.size() == 1
                ? "📌 <b>Criar evento?</b>\n\n" : "📌 <b>Criar " + dtos.size() + " eventos?</b>\n\n");
        for (int i = 0; i < dtos.size(); i++) {
            EventExtractionDTO dto = dtos.get(i);
            TimeRange range = EventTimes.fromAi(dto.startDateTime(), dto.endDateTime(), zone);
            if (dtos.size() > 1) sb.append(i + 1).append(". ");
            sb.append("📝 <b>").append(escapeHtml(dto.summary())).append("</b>\n");
            sb.append("⏰ ").append(EventTimes.format(range)).append("\n");
            String recurrence = EventTimes.formatRecurrence(dto.recurrence());
            if (!recurrence.isEmpty()) sb.append("🔁 ").append(escapeHtml(recurrence)).append("\n");
            if (dto.location() != null && !dto.location().isBlank()) {
                sb.append("📍 ").append(escapeHtml(dto.location())).append("\n");
            }
            if (dto.description() != null && !dto.description().isBlank() && dtos.size() == 1) {
                sb.append("🗒️ <i>").append(escapeHtml(truncate(dto.description(), 200))).append("</i>\n");
            }
            if (dto.reminders() != null && !dto.reminders().isEmpty()) {
                sb.append("🔔 ").append(EventTimes.formatReminders(dto.reminders())).append("\n");
            }
            List<EventAttendee> attendees = GoogleCalendarService.toAttendees(dto.attendees());
            if (!attendees.isEmpty()) {
                sb.append("👥 ").append(escapeHtml(attendees.stream().map(EventAttendee::getEmail)
                        .collect(Collectors.joining(", ")))).append(" <i>(receberão convite)</i>\n");
            }
            sb.append(conflictText(calendarService.findConflicts(user, range, null), zone));
            if (dtos.size() > 1) sb.append("\n");
        }
        if (dtos.size() == 1) sb.append("\n<i>Algo errado? Toque em Ajustar ou responda a esta mensagem.</i>");
        return sb.toString().stripTrailing();
    }

    private static String conflictText(List<Event> conflicts, ZoneId zone) {
        if (conflicts.isEmpty()) return "";
        String names = conflicts.stream().limit(3)
                .map(e -> "<b>" + escapeHtml(summaryOf(e)) + "</b> (" + EventTimes.formatTimes(BotTexts.rangeOf(e, zone)) + ")")
                .collect(Collectors.joining(", "));
        return "⚠️ Conflita com " + names + (conflicts.size() > 3 ? " e mais " + (conflicts.size() - 3) : "") + "\n";
    }

    private InlineKeyboardMarkup createKeyboard(String id, int count) {
        if (count > 1) {
            return keyboard(List.of(button("✅ Criar todos", "ok:" + id), button("✖️ Cancelar", "no:" + id)));
        }
        return keyboard(List.of(
                button("✅ Criar", "ok:" + id),
                button("✏️ Ajustar", "adj:" + id),
                button("✖️ Cancelar", "no:" + id)));
    }

    private static String eventSummaryText(Event event, ZoneId zone) {
        StringBuilder sb = new StringBuilder();
        sb.append("📝 <b>").append(escapeHtml(summaryOf(event))).append("</b>\n");
        sb.append("⏰ ").append(EventTimes.format(BotTexts.rangeOf(event, zone))).append("\n");
        if (event.getRecurrence() != null && !event.getRecurrence().isEmpty()) {
            String rec = EventTimes.formatRecurrence(event.getRecurrence().getFirst());
            if (!rec.isEmpty()) sb.append("🔁 ").append(escapeHtml(rec)).append("\n");
        }
        if (event.getLocation() != null && !event.getLocation().isBlank()) {
            sb.append("📍 ").append(escapeHtml(event.getLocation())).append("\n");
        }
        List<Integer> reminders = reminderMinutes(event);
        if (!reminders.isEmpty()) sb.append("🔔 ").append(EventTimes.formatReminders(reminders)).append("\n");
        if (event.getAttendees() != null && !event.getAttendees().isEmpty()) {
            sb.append("👥 ").append(event.getAttendees().size()).append(" convidado(s)\n");
        }
        return sb.toString();
    }

    /** Mensagem amigavel para cada tipo de falha. */
    static String errorMessage(Exception e) {
        if (e instanceof AiException ai) return ai.userMessage();
        if (e instanceof GoogleJsonResponseException g) {
            return switch (g.getStatusCode()) {
                case 401 -> "🔑 Sua conexão com o Google expirou. Use /config para reconectar a agenda.";
                case 403 -> "🔒 O Google recusou o acesso à agenda. Use /config para reconectar e conceder permissão.";
                case 404, 410 -> "🔍 Esse evento não existe mais (talvez já tenha sido apagado). Veja /eventos.";
                default -> "📅 O Google Agenda retornou um erro. Tente novamente em instantes.";
            };
        }
        if (e instanceof TokenResponseException) {
            return "🔑 Sua conexão com o Google expirou. Use /config para reconectar a agenda.";
        }
        if (e instanceof IllegalArgumentException) return "❌ " + e.getMessage();
        if (e instanceof IllegalStateException) return "⚠️ " + e.getMessage();
        return "❌ Ocorreu um erro inesperado. Tente novamente.";
    }

    // ================================================================ midia

    /** Retorna null se a mensagem nao tem midia; lanca IllegalArgumentException se o tipo nao e suportado. */
    private Media extractMedia(Message message) throws TelegramApiException, IOException {
        if (message.hasPhoto()) {
            PhotoSize photo = message.getPhoto().stream()
                    .max(Comparator.comparingInt(p -> p.getWidth() * p.getHeight()))
                    .orElseThrow(() -> new IllegalArgumentException("Foto vazia."));
            return new Media(downloadFile(photo.getFileId()), "image/jpeg");
        }
        if (message.hasVoice()) {
            checkSize(message.getVoice().getFileSize());
            return new Media(downloadFile(message.getVoice().getFileId()),
                    Objects.requireNonNullElse(message.getVoice().getMimeType(), "audio/ogg"));
        }
        if (message.hasAudio()) {
            checkSize(message.getAudio().getFileSize());
            return new Media(downloadFile(message.getAudio().getFileId()),
                    Objects.requireNonNullElse(message.getAudio().getMimeType(), "audio/mpeg"));
        }
        if (message.hasVideoNote()) {
            checkSize(message.getVideoNote().getFileSize());
            return new Media(downloadFile(message.getVideoNote().getFileId()), "video/mp4");
        }
        if (message.hasDocument()) {
            Document doc = message.getDocument();
            String mime = Objects.requireNonNullElse(doc.getMimeType(), "");
            if (!(mime.startsWith("image/") || mime.startsWith("audio/") || mime.equals("application/pdf"))) {
                throw new IllegalArgumentException("Esse tipo de arquivo não é suportado. Envie PDF, imagem ou áudio.");
            }
            checkSize(doc.getFileSize());
            return new Media(downloadFile(doc.getFileId()), mime);
        }
        if (message.hasSticker() || message.hasVideo() || message.hasAnimation()) {
            throw new IllegalArgumentException("Não consigo ler esse tipo de mídia. Envie texto, áudio, foto ou PDF.");
        }
        return null;
    }

    private static void checkSize(Number size) {
        if (size != null && size.longValue() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Arquivo grande demais (limite de 20 MB).");
        }
    }

    private byte[] downloadFile(String fileId) throws TelegramApiException, IOException {
        File file = getTelegramClient().execute(new GetFile(fileId));
        try (InputStream is = getTelegramClient().downloadFileAsStream(file)) {
            return is.readAllBytes();
        }
    }

    // ================================================================ envio

    private static InlineKeyboardButton button(String text, String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }

    @SafeVarargs
    private static InlineKeyboardMarkup keyboard(List<InlineKeyboardButton>... rows) {
        List<InlineKeyboardRow> keyboardRows = new ArrayList<>();
        for (List<InlineKeyboardButton> row : rows) keyboardRows.add(new InlineKeyboardRow(row));
        return InlineKeyboardMarkup.builder().keyboard(keyboardRows).build();
    }

    private ReplyKeyboardMarkup mainKeyboard() {
        KeyboardRow row = new KeyboardRow();
        row.add("/eventos");
        row.add("/config");
        row.add("/start");
        return ReplyKeyboardMarkup.builder()
                .keyboardRow(row)
                .resizeKeyboard(true)
                .build();
    }

    private void answerCallback(CallbackQuery query, String text, boolean alert) {
        try {
            getTelegramClient().execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(query.getId())
                    .text(text)
                    .showAlert(alert)
                    .build());
        } catch (TelegramApiException e) {
            log.debug("Falha ao responder callback", e);
        }
    }

    private void removeKeyboard(Long chatId, Integer messageId) {
        setKeyboard(chatId, messageId, null);
    }

    private void setKeyboard(Long chatId, Integer messageId, InlineKeyboardMarkup keyboard) {
        if (messageId == null) return;
        try {
            getTelegramClient().execute(EditMessageReplyMarkup.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .replyMarkup(keyboard)
                    .build());
        } catch (TelegramApiException e) {
            log.debug("Falha ao alterar teclado da mensagem {}", messageId, e);
        }
    }

    /**
     * Edita a mensagem {@code messageId} (se houver) ou envia uma nova. Retorna o id da mensagem resultante.
     * {@code keyboard} nulo remove os botoes.
     */
    private Integer sendOrEdit(Long chatId, Integer messageId, String html, InlineKeyboardMarkup keyboard) {
        if (messageId != null) {
            try {
                getTelegramClient().execute(EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .text(html)
                        .parseMode("HTML")
                        .disableWebPagePreview(true)
                        .replyMarkup(keyboard)
                        .build());
                return messageId;
            } catch (TelegramApiException e) {
                log.debug("Falha ao editar mensagem {}; enviando nova.", messageId, e);
            }
        }
        return send(chatId, html, keyboard != null ? keyboard : chatId > 0 ? mainKeyboard() : null);
    }

    private Integer send(Long chatId, String html, ReplyKeyboard markup) {
        SendMessage sm = SendMessage.builder()
                .chatId(chatId)
                .text(html)
                .parseMode("HTML")
                .disableWebPagePreview(true)
                .replyMarkup(markup)
                .build();
        try {
            return getTelegramClient().execute(sm).getMessageId();
        } catch (TelegramApiException e) {
            log.warn("Falha ao enviar HTML para chat {}. Reenviando como texto puro.", chatId, e);
            return sendPlain(chatId, html.replaceAll("<[^>]+>", ""), markup);
        }
    }

    private void sendHtml(Long chatId, String html) {
        send(chatId, html, chatId > 0 ? mainKeyboard() : null);
    }

    private void sendText(Long chatId, String text) {
        sendPlain(chatId, text, chatId > 0 ? mainKeyboard() : null);
    }

    private Integer sendPlain(Long chatId, String text, ReplyKeyboard markup) {
        try {
            return getTelegramClient().execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .replyMarkup(markup)
                    .build()).getMessageId();
        } catch (TelegramApiException e) {
            log.error("Falha ao enviar mensagem para chat {}", chatId, e);
            return null;
        }
    }

    private void sendTypingAction(Long chatId) {
        try {
            getTelegramClient().execute(SendChatAction.builder()
                    .chatId(chatId)
                    .action(ActionType.TYPING.toString()).build());
        } catch (TelegramApiException e) {
            log.debug("Falha ao enviar acao de digitacao para chat {}", chatId, e);
        }
    }
}
