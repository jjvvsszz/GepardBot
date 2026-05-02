package tk.jaooo.gepard.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import tk.jaooo.gepard.config.BotConfig;
import tk.jaooo.gepard.model.AppUser;
import tk.jaooo.gepard.model.dto.AiResponseDTO;
import tk.jaooo.gepard.model.dto.EventExtractionDTO;
import tk.jaooo.gepard.repository.AppUserRepository;
import tk.jaooo.gepard.service.AiService;
import tk.jaooo.gepard.service.GoogleCalendarService;
import tk.jaooo.gepard.service.SystemSettingsService;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
public class GepardBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    private static final DateTimeFormatter DT_FMT_FULL = DateTimeFormatter.ofPattern("EEE dd/MM HH:mm");

    private final SystemSettingsService settingsService;
    private final BotConfig botConfig;
    private final AiService aiService;
    private final GoogleCalendarService calendarService;
    private final AppUserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    private final Map<Long, PendingCreate> pendingEvents = new ConcurrentHashMap<>();
    private final Map<Long, PendingEdit> pendingEdits = new ConcurrentHashMap<>();
    private final Map<Long, PendingDelete> pendingDeletes = new ConcurrentHashMap<>();
    private final Map<Long, MultiSelectState> multiSelectStates = new ConcurrentHashMap<>();
    private final Map<Long, List<Event>> recentEvents = new ConcurrentHashMap<>();

    private record PendingCreate(EventExtractionDTO dto, Long chatId, Integer messageId) {}
    private record PendingEdit(String eventId, Event currentEvent, EventExtractionDTO newDto, Long chatId) {}
    private record PendingDelete(String eventId, Event currentEvent, Long chatId) {}
    private record MultiSelectState(boolean isDelete, EventExtractionDTO newDto,
                                    List<Event> candidates, Long chatId) {}
    private record ScoredEvent(Event event, int score) {}

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

    @Override
    public void consume(Update update) {
        if (update.hasCallbackQuery()) {
            handleCallbackQuery(update);
            return;
        }
        if (update.hasEditedMessage()) {
            handleEditedMessage(update);
            return;
        }
        if (!update.hasMessage()) return;
        Message message = update.getMessage();
        Long telegramId = message.getFrom().getId();
        Long chatId = message.getChatId();
        String text = message.hasText() ? message.getText().trim() : "";

        try {
            AppUser user = userRepository.findById(telegramId).orElseGet(() ->
                    userRepository.save(AppUser.builder()
                            .telegramId(telegramId)
                            .username(message.getFrom().getUserName())
                            .firstName(message.getFrom().getFirstName())
                            .build())
            );

            switch (text) {
                case "/start" -> {
                    handleStart(chatId, user);
                    return;
                }
                case "/config" -> {
                    String link = generateSettingsURL(user);
                    sendHtmlText(chatId, "⚙️ <a href=\"" + escapeHtml(link) + "\">Abrir Configuracoes</a>");
                    return;
                }
                case "/eventos", "/events" -> {
                    handleListEvents(chatId, user);
                    return;
                }
                case "/cancelar", "/cancel" -> {
                    pendingEvents.remove(telegramId);
                    pendingEdits.remove(telegramId);
                    pendingDeletes.remove(telegramId);
                    multiSelectStates.remove(telegramId);
                    recentEvents.remove(telegramId);
                    sendRawText(chatId, "✅ Operacao cancelada.");
                    return;
                }
            }

            if (!user.hasGeminiKey()) {
                handleApiKeyFlow(message, user);
                return;
            }

            if (user.getGoogleRefreshToken() == null) {
                String authLink = calendarService.buildAuthorizationUrl(telegramId);
                sendHtmlText(chatId, "📅 <a href=\"" + escapeHtml(authLink) + "\">Conectar Google Agenda</a>");
                return;
            }

            handleSmartScheduling(message, user);

        } catch (Exception e) {
            log.error("Erro fatal ao processar mensagem do usuario {}", telegramId, e);
            sendRawText(chatId, "❌ Ocorreu um erro interno. Tente novamente mais tarde.");
        }
    }

    private void handleStart(Long chatId, AppUser user) {
        StringBuilder sb = new StringBuilder();
        sb.append("🤖 <b>GepardBot - Seu Assistente de Agenda</b>\n\n");
        sb.append("Eu crio, edito e deleto eventos no Google Agenda a partir de texto, fotos ou audio!\n\n");

        if (!user.hasGeminiKey()) {
            sb.append("🔑 <b>Para comecar:</b> envie sua <b>Gemini API Key</b> (obrigatoria).\n");
            sb.append("Obtenha gratuitamente em: aistudio.google.com/app/apikey\n");
            sb.append("(sua chave comeca com AIza...)\n\n");
        } else if (user.getGoogleRefreshToken() == null) {
            sb.append("📅 Falta conectar sua agenda Google.\n");
            sb.append("Digite qualquer coisa que eu mostro o link.\n\n");
        } else {
            sb.append("✅ Voce ja esta configurado!\n\n");
            sb.append("<b>Comandos:</b>\n");
            sb.append("/eventos - Ver proximos eventos\n");
            sb.append("/config - Painel de configuracoes\n");
            sb.append("/cancelar - Cancelar operacao atual\n\n");
            sb.append("<b>Criar:</b> Envie texto, foto ou audio descrevendo um evento.\n");
            sb.append("<b>Editar:</b> 'adiar almoco para quinta 14h'\n");
            sb.append("<b>Deletar:</b> 'cancelar almoco de amanha' ou 'remover reuniao'\n");
            sb.append("<b>DeepSeek:</b> Envie uma chave sk-... e configure no painel /config\n");
        }
        sendHtmlText(chatId, sb.toString());
    }

    private void handleListEvents(Long chatId, AppUser user) {
        sendTypingAction(chatId);
        try {
            List<Event> events = calendarService.listUpcomingEvents(user, 10);

            if (events.isEmpty()) {
                SendMessage sm = SendMessage.builder()
                        .chatId(chatId)
                        .text("📅 Voce nao tem eventos proximos.")
                        .replyMarkup(mainKeyboard())
                        .build();
                getTelegramClient().execute(sm);
                return;
            }

            recentEvents.put(user.getTelegramId(), events);

            StringBuilder sb = new StringBuilder();
            sb.append("📅 <b>Proximos Eventos:</b>\n\n");

            for (int i = 0; i < events.size(); i++) {
                Event e = events.get(i);
                String start = formatEventDateTime(e.getStart());
                sb.append(i + 1).append(". <b>").append(escapeHtml(e.getSummary())).append("</b>\n");
                sb.append("   ⏰ ").append(start).append("\n\n");
            }

            sb.append("Para <b>deletar</b>, responda: deletar [numero]");

            SendMessage sm = SendMessage.builder()
                    .chatId(chatId)
                    .text(sb.toString())
                    .parseMode("HTML")
                    .build();
            getTelegramClient().execute(sm);

        } catch (Exception e) {
            log.error("Erro ao listar eventos para usuario {}", user.getTelegramId(), e);
            sendRawText(chatId, "❌ Falha ao buscar eventos. Verifique sua conexao Google.");
        }
    }

    private void handleDeleteEvent(Long chatId, AppUser user, String text) {
        try {
            int idx = Integer.parseInt(text.replaceAll("[^0-9]", "")) - 1;
            List<Event> events = recentEvents.get(user.getTelegramId());

            if (events == null || idx < 0 || idx >= events.size()) {
                sendRawText(chatId, "❌ Numero invalido. Use /eventos para ver a lista.");
                return;
            }

            Event event = events.get(idx);
            calendarService.deleteEvent(user, event.getId());
            sendRawText(chatId, "✅ Evento \"" + event.getSummary() + "\" deletado!");

        } catch (NumberFormatException e) {
            sendRawText(chatId, "❌ Formato invalido. Use: deletar [numero]");
        } catch (Exception e) {
            log.error("Erro ao deletar evento", e);
            sendRawText(chatId, "❌ Falha ao deletar evento.");
        }
    }

    private void handleApiKeyFlow(Message message, AppUser user) {
        String text = message.hasText() ? message.getText().trim() : "";

        if (text.startsWith("AIza")) {
            user.setGeminiApiKey(text);
            userRepository.save(user);
            String authLink = calendarService.buildAuthorizationUrl(user.getTelegramId());
            sendHtmlText(message.getChatId(), "✅ Gemini Key salva! <a href=\"" + escapeHtml(authLink) + "\">Conectar Agenda</a>");
        } else if (text.startsWith("sk-")) {
            if (!user.hasGeminiKey()) {
                sendRawText(message.getChatId(), "⚠️ Envie primeiro sua Gemini API Key (comeca com AIza...).\nA Gemini e obrigatoria para processar fotos e audio.");
                return;
            }
            user.setDeepSeekApiKey(text);
            userRepository.save(user);
            sendHtmlText(message.getChatId(), "✅ DeepSeek Key salva! Agora voce pode usar modelos DeepSeek para texto.\nUse /config para escolher o modelo.");
        } else {
            SendMessage sm = SendMessage.builder()
                    .chatId(message.getChatId())
                    .text("""
                            👋 Envie sua <b>API Key</b>:

                            🔑 <b>Gemini</b> (obrigatoria) — comeca com AIza...
                            🔑 <b>DeepSeek</b> (opcional) — comeca com sk-...

                            Configure os modelos no painel /config""")
                    .parseMode("HTML")
                    .build();
            try {
                getTelegramClient().execute(sm);
            } catch (TelegramApiException e) {
                log.warn("Falha ao enviar mensagem API key flow", e);
            }
        }
    }

    private String generateSettingsURL(AppUser user) {
        String token = UUID.randomUUID().toString();
        user.setWebLoginToken(token);
        user.setWebLoginTokenExpiresAt(LocalDateTime.now().plusHours(24));
        userRepository.save(user);
        return baseUrl + "/user/config?token=" + token;
    }

    private void handleSmartScheduling(Message message, AppUser user) {
        Long chatId = message.getChatId();
        Long telegramId = message.getFrom().getId();
        String text = message.hasText() ? message.getText().trim() : "";

        if (text.matches("^(?i)deletar\\s+\\d+$")) {
            handleDeleteEvent(chatId, user, text);
            return;
        }

        sendTypingAction(chatId);

        try {
            byte[] mediaBytes = null;
            String mimeType = null;

            if (message.hasPhoto()) {
                mediaBytes = downloadPhoto(message.getPhoto());
                mimeType = "image/jpeg";
            } else if (message.hasVoice()) {
                mediaBytes = downloadFile(message.getVoice().getFileId());
                mimeType = "audio/ogg";
            }

            String prompt = message.getCaption() != null ? message.getCaption() : message.getText();
            if (prompt == null) prompt = "Extraia os detalhes do evento desta midia.";

            ZonedDateTime nowSP = ZonedDateTime.now(ZoneId.of("America/Sao_Paulo"));
            String fullPrompt = String.format("Hoje e %s (Fuso America/Sao_Paulo). O usuario pede: %s",
                    nowSP.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), prompt);

            String jsonResponse = aiService.generateContent(fullPrompt, mediaBytes, mimeType, user);

            AiResponseDTO response = objectMapper.readValue(jsonResponse, AiResponseDTO.class);

            if (response.isDelete() && response.getSearchQuery() != null && !response.getSearchQuery().isBlank()) {
                handleDeleteIntention(chatId, user, response);
                return;
            }

            if (response.isEdit() && response.getSearchQuery() != null && !response.getSearchQuery().isBlank()) {
                handleEditIntention(chatId, user, response);
                return;
            }

            EventExtractionDTO eventDTO = response.toEventExtractionDTO();

            if (eventDTO.summary() == null || eventDTO.summary().isBlank()
                    || eventDTO.startDateTime() == null || eventDTO.startDateTime().isBlank()) {
                log.warn("IA retornou dados incompletos para usuario {}: summary={}, startDateTime={}",
                        user.getTelegramId(), eventDTO.summary(), eventDTO.startDateTime());
                sendRawText(chatId, "❌ Nao consegui extrair os dados do evento. Tente descrever com mais detalhes.");
                return;
            }

            pendingEvents.put(telegramId, new PendingCreate(eventDTO, chatId, message.getMessageId()));

            String safeSummary = escapeHtml(eventDTO.summary());
            String modelUsed = getModelDisplayName(user, mediaBytes != null);

            String confirmMsg = """
                    📌 <b>Confirmar Evento?</b>

                    📝 %s
                    ⏰ Inicio: %s
                    """.formatted(safeSummary, eventDTO.startDateTime())
                    + (eventDTO.endDateTime() != null && !eventDTO.endDateTime().isBlank()
                       ? "⏰ Fim: " + eventDTO.endDateTime() + "\n" : "")
                    + (eventDTO.location() != null && !eventDTO.location().isBlank()
                       ? "📍 " + escapeHtml(eventDTO.location()) + "\n" : "")
                    + (eventDTO.reminders() != null && !eventDTO.reminders().isEmpty()
                       ? "🔔 Lembretes: " + eventDTO.reminders() + " min\n" : "")
                    + "\n🤖 Modelo: " + modelUsed;

            InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(
                            InlineKeyboardButton.builder()
                                    .text("Sim, criar evento")
                                    .callbackData("confirm_event")
                                    .build(),
                            InlineKeyboardButton.builder()
                                    .text("Nao, cancelar")
                                    .callbackData("cancel_event")
                                    .build()
                    ))
                    .build();

            SendMessage sm = SendMessage.builder()
                    .chatId(chatId)
                    .text(confirmMsg)
                    .parseMode("HTML")
                    .replyMarkup(keyboard)
                    .build();
            getTelegramClient().execute(sm);

        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("Dados invalidos do usuario {}: {}", user.getTelegramId(), e.getMessage());
            sendRawText(chatId, "❌ " + e.getMessage());
        } catch (Exception e) {
            log.error("Erro IA/Agenda para usuario {}", user.getTelegramId(), e);
            sendRawText(chatId, "❌ Falha ao processar. Verifique sua configuracao e tente novamente.");
        }
    }

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

    private List<Event> findEventCandidates(AppUser user, String searchQuery)
            throws IOException, GeneralSecurityException {
        List<String> keywords = extractKeywords(searchQuery);

        String mainKeyword = keywords.get(0);
        List<Event> allCandidates = calendarService.searchEvents(user, mainKeyword, 10);

        if (allCandidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<ScoredEvent> scored = new ArrayList<>();
        for (Event e : allCandidates) {
            if (e.getSummary() == null) continue;
            String summary = e.getSummary().toLowerCase();
            int score = 0;
            for (String kw : keywords) {
                if (summary.contains(kw)) score++;
            }
            scored.add(new ScoredEvent(e, score));
        }
        scored.sort((a, b) -> b.score - a.score);

        List<Event> result = scored.stream()
                .filter(s -> s.score > 0)
                .map(ScoredEvent::event)
                .limit(5)
                .collect(Collectors.toList());

        if (result.isEmpty() && !scored.isEmpty()) {
            result = scored.stream()
                    .map(ScoredEvent::event)
                    .limit(5)
                    .collect(Collectors.toList());
        }
        return result;
    }

    private void handleEditIntention(Long chatId, AppUser user, AiResponseDTO response) {
        Long telegramId = user.getTelegramId();
        String searchQuery = response.getSearchQuery();

        try {
            List<Event> matches = findEventCandidates(user, searchQuery);

            if (matches.isEmpty()) {
                sendRawText(chatId, "❌ Nao encontrei eventos com \"" + searchQuery + "\".\nTente ser mais especifico ou use /eventos para ver sua agenda.");
                return;
            }

            if (matches.size() == 1) {
                Event event = matches.get(0);
                showEditDiffAndConfirm(chatId, telegramId, user, event, response.toEventExtractionDTO());
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("✏️ Encontrei ").append(matches.size()).append(" eventos com \"")
                    .append(escapeHtml(searchQuery)).append("\". Qual editar?\n\n");

            List<InlineKeyboardRow> rows = new ArrayList<>();
            for (int i = 0; i < Math.min(matches.size(), 8); i++) {
                Event e = matches.get(i);
                String start = formatEventDateTime(e.getStart());
                sb.append(i + 1).append(". 📝 ").append(escapeHtml(e.getSummary()))
                        .append(" - ⏰ ").append(start).append("\n");

                InlineKeyboardButton btn = InlineKeyboardButton.builder()
                        .text(String.valueOf(i + 1))
                        .callbackData("edit_select_" + i)
                        .build();
                rows.add(new InlineKeyboardRow(btn));
            }

            rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text("Cancelar")
                    .callbackData("cancel_edit_event")
                    .build()));

            multiSelectStates.put(telegramId, new MultiSelectState(false,
                    response.toEventExtractionDTO(), matches, chatId));

            SendMessage sm = SendMessage.builder()
                    .chatId(chatId)
                    .text(sb.toString())
                    .parseMode("HTML")
                    .replyMarkup(InlineKeyboardMarkup.builder().keyboard(rows).build())
                    .build();
            getTelegramClient().execute(sm);

        } catch (IllegalStateException e) {
            sendRawText(chatId, "❌ " + e.getMessage());
        } catch (Exception e) {
            log.error("Erro ao buscar evento para edicao. Usuario {}", telegramId, e);
            sendRawText(chatId, "❌ Falha ao buscar o evento para edicao.");
        }
    }

    private void handleDeleteIntention(Long chatId, AppUser user, AiResponseDTO response) {
        Long telegramId = user.getTelegramId();
        String searchQuery = response.getSearchQuery();

        try {
            List<Event> matches = findEventCandidates(user, searchQuery);

            if (matches.isEmpty()) {
                sendRawText(chatId, "❌ Nao encontrei eventos com \"" + searchQuery + "\".\nTente ser mais especifico ou use /eventos para ver sua agenda.");
                return;
            }

            if (matches.size() == 1) {
                Event event = matches.get(0);
                showDeleteConfirm(chatId, telegramId, user, event);
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("🗑️ Encontrei ").append(matches.size()).append(" eventos com \"")
                    .append(escapeHtml(searchQuery)).append("\". Qual deletar?\n\n");

            List<InlineKeyboardRow> rows = new ArrayList<>();
            for (int i = 0; i < Math.min(matches.size(), 8); i++) {
                Event e = matches.get(i);
                String start = formatEventDateTime(e.getStart());
                sb.append(i + 1).append(". 📝 ").append(escapeHtml(e.getSummary()))
                        .append(" - ⏰ ").append(start).append("\n");

                InlineKeyboardButton btn = InlineKeyboardButton.builder()
                        .text(String.valueOf(i + 1))
                        .callbackData("delete_select_" + i)
                        .build();
                rows.add(new InlineKeyboardRow(btn));
            }

            rows.add(new InlineKeyboardRow(InlineKeyboardButton.builder()
                    .text("Cancelar")
                    .callbackData("cancel_delete_event")
                    .build()));

            multiSelectStates.put(telegramId, new MultiSelectState(true,
                    null, matches, chatId));

            SendMessage sm = SendMessage.builder()
                    .chatId(chatId)
                    .text(sb.toString())
                    .parseMode("HTML")
                    .replyMarkup(InlineKeyboardMarkup.builder().keyboard(rows).build())
                    .build();
            getTelegramClient().execute(sm);

        } catch (IllegalStateException e) {
            sendRawText(chatId, "❌ " + e.getMessage());
        } catch (Exception e) {
            log.error("Erro ao buscar evento para exclusao. Usuario {}", telegramId, e);
            sendRawText(chatId, "❌ Falha ao buscar o evento para exclusao.");
        }
    }

    private void showEditDiffAndConfirm(Long chatId, Long telegramId, AppUser user,
                                         Event current, EventExtractionDTO newDto) {
        try {
            Event freshEvent = calendarService.getEvent(user, current.getId());

            StringBuilder sb = new StringBuilder("✏️ <b>Alterar evento?</b>\n\n");

            String oldSummary = freshEvent.getSummary() != null ? freshEvent.getSummary() : "";
            String newSummary = newDto.summary() != null ? newDto.summary() : oldSummary;
            if (!newSummary.equalsIgnoreCase(oldSummary)) {
                sb.append("📝 ").append(escapeHtml(oldSummary)).append(" → ")
                        .append(escapeHtml(newSummary)).append("\n");
            } else {
                sb.append("📝 ").append(escapeHtml(oldSummary)).append(" (sem alteração)\n");
            }

            String oldStart = formatEventDateTime(freshEvent.getStart());
            String newStart = newDto.startDateTime() != null ? formatIsoDateTime(newDto.startDateTime()) : oldStart;
            if (!newStart.equals(oldStart)) {
                sb.append("⏰ Início: ").append(oldStart).append(" → ").append(newStart).append("\n");
            } else {
                sb.append("⏰ Início: ").append(oldStart).append(" (sem alteração)\n");
            }

            String oldEnd = formatEventDateTime(freshEvent.getEnd());
            String newEnd;
            if (newDto.endDateTime() != null && !newDto.endDateTime().isBlank()) {
                newEnd = formatIsoDateTime(newDto.endDateTime());
            } else {
                newEnd = oldEnd;
            }
            if (!newEnd.equals(oldEnd)) {
                sb.append("⏰ Fim: ").append(oldEnd).append(" → ").append(newEnd).append("\n");
            }

            String oldLoc = freshEvent.getLocation() != null ? freshEvent.getLocation() : "";
            String newLoc = newDto.location() != null ? newDto.location() : oldLoc;
            if (!newLoc.equalsIgnoreCase(oldLoc)) {
                sb.append("📍 ").append(escapeHtml(oldLoc)).append(" → ")
                        .append(escapeHtml(newLoc)).append("\n");
            } else if (!oldLoc.isBlank()) {
                sb.append("📍 ").append(escapeHtml(oldLoc)).append(" (sem alteração)\n");
            }

            pendingEdits.put(telegramId, new PendingEdit(freshEvent.getId(), freshEvent, newDto, chatId));

            InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(
                            InlineKeyboardButton.builder()
                                    .text("Sim, alterar")
                                    .callbackData("confirm_edit_event")
                                    .build(),
                            InlineKeyboardButton.builder()
                                    .text("Nao, cancelar")
                                    .callbackData("cancel_edit_event")
                                    .build()
                    ))
                    .build();

            sendHtmlWithKeyboard(chatId, sb.toString(), keyboard);

        } catch (Exception e) {
            log.error("Erro ao mostrar diff de edicao para usuario {}", telegramId, e);
            sendRawText(chatId, "❌ Falha ao preparar edicao do evento.");
        }
    }

    private void showDeleteConfirm(Long chatId, Long telegramId, AppUser user, Event event) {
        try {
            Event freshEvent = calendarService.getEvent(user, event.getId());

            StringBuilder sb = new StringBuilder("🗑️ <b>Deletar evento?</b>\n\n");

            sb.append("📝 ").append(escapeHtml(freshEvent.getSummary())).append("\n");
            sb.append("⏰ ").append(formatEventDateTime(freshEvent.getStart()));
            if (freshEvent.getEnd() != null) {
                sb.append(" - ").append(formatEventDateTime(freshEvent.getEnd()));
            }
            sb.append("\n");
            if (freshEvent.getLocation() != null && !freshEvent.getLocation().isBlank()) {
                sb.append("📍 ").append(escapeHtml(freshEvent.getLocation())).append("\n");
            }

            pendingDeletes.put(telegramId, new PendingDelete(freshEvent.getId(), freshEvent, chatId));

            InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(new InlineKeyboardRow(
                            InlineKeyboardButton.builder()
                                    .text("Sim, deletar")
                                    .callbackData("confirm_delete_event")
                                    .build(),
                            InlineKeyboardButton.builder()
                                    .text("Nao, cancelar")
                                    .callbackData("cancel_delete_event")
                                    .build()
                    ))
                    .build();

            sendHtmlWithKeyboard(chatId, sb.toString(), keyboard);

        } catch (Exception e) {
            log.error("Erro ao mostrar confirmacao de exclusao para usuario {}", telegramId, e);
            sendRawText(chatId, "❌ Falha ao preparar exclusao do evento.");
        }
    }

    private void handleEditedMessage(Update update) {
        var edited = update.getEditedMessage();
        Long telegramId = edited.getFrom().getId();
        Long chatId = edited.getChatId();
        Integer messageId = edited.getMessageId();
        String text = edited.hasText() ? edited.getText().trim() : "";

        try {
            AppUser user = userRepository.findById(telegramId).orElse(null);
            if (user == null || !user.hasGeminiKey() || user.getGoogleRefreshToken() == null) {
                return;
            }

            try {
                List<Event> matchedEvents = calendarService.findEventsByExtendedProperties(
                        user,
                        "telegramChatId=" + chatId,
                        "telegramMessageId=" + messageId);

                if (matchedEvents.isEmpty()) {
                    sendTypingAction(chatId);
                    processTextAsSmartScheduling(chatId, telegramId, user, text, edited);
                    return;
                }

                sendTypingAction(chatId);

                ZonedDateTime nowSP = ZonedDateTime.now(ZoneId.of("America/Sao_Paulo"));
                String fullPrompt = String.format("Hoje e %s (Fuso America/Sao_Paulo). O usuario pede: %s",
                        nowSP.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), text);

                String jsonResponse = aiService.generateContent(fullPrompt, null, null, user);
                AiResponseDTO response = objectMapper.readValue(jsonResponse, AiResponseDTO.class);

                if (response.isDelete()) {
                    Event event = matchedEvents.get(0);
                    Event freshEvent = calendarService.getEvent(user, event.getId());
                    pendingDeletes.put(telegramId, new PendingDelete(freshEvent.getId(), freshEvent, chatId));
                    showDeleteConfirm(chatId, telegramId, user, event);
                    return;
                }

                EventExtractionDTO newDto = response.toEventExtractionDTO();

                if (newDto.summary() == null || newDto.summary().isBlank()
                        || newDto.startDateTime() == null || newDto.startDateTime().isBlank()) {
                    sendRawText(chatId, "❌ Nao consegui extrair os dados do evento editado.");
                    return;
                }

                Event matchedEvent = matchedEvents.get(0);
                showEditDiffAndConfirm(chatId, telegramId, user, matchedEvent, newDto);

            } catch (Exception e) {
                log.warn("Falha ao buscar evento por extendedProperty para edicao", e);
                processTextAsSmartScheduling(chatId, telegramId, user, text, edited);
            }

        } catch (Exception e) {
            log.error("Erro ao processar mensagem editada do usuario {}", telegramId, e);
        }
    }

    private void processTextAsSmartScheduling(Long chatId, Long telegramId, AppUser user,
                                               String text, Message message) {
        try {
            ZonedDateTime nowSP = ZonedDateTime.now(ZoneId.of("America/Sao_Paulo"));
            String fullPrompt = String.format("Hoje e %s (Fuso America/Sao_Paulo). O usuario pede: %s",
                    nowSP.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), text);

            String jsonResponse = aiService.generateContent(fullPrompt, null, null, user);
            AiResponseDTO response = objectMapper.readValue(jsonResponse, AiResponseDTO.class);

            if (response.isDelete() && response.getSearchQuery() != null && !response.getSearchQuery().isBlank()) {
                handleDeleteIntention(chatId, user, response);
                return;
            }

            if (response.isEdit() && response.getSearchQuery() != null && !response.getSearchQuery().isBlank()) {
                handleEditIntention(chatId, user, response);
                return;
            }

            EventExtractionDTO eventDTO = response.toEventExtractionDTO();

            if (eventDTO.summary() == null || eventDTO.summary().isBlank()
                    || eventDTO.startDateTime() == null || eventDTO.startDateTime().isBlank()) {
                sendRawText(chatId, "❌ Nao consegui extrair os dados do evento. Tente descrever com mais detalhes.");
                return;
            }

            pendingEvents.put(telegramId, new PendingCreate(eventDTO, chatId, message.getMessageId()));
            sendCreateConfirmation(chatId, user, eventDTO, false);

        } catch (Exception e) {
            log.error("Erro ao processar texto no fallback para usuario {}", telegramId, e);
            sendRawText(chatId, "❌ Falha ao processar. Tente novamente.");
        }
    }

    private void sendCreateConfirmation(Long chatId, AppUser user, EventExtractionDTO eventDTO, boolean hasMedia) {
        String safeSummary = escapeHtml(eventDTO.summary());
        String modelUsed = getModelDisplayName(user, hasMedia);

        String confirmMsg = """
                📌 <b>Confirmar Evento?</b>

                📝 %s
                ⏰ Inicio: %s
                """.formatted(safeSummary, eventDTO.startDateTime())
                + (eventDTO.endDateTime() != null && !eventDTO.endDateTime().isBlank()
                   ? "⏰ Fim: " + eventDTO.endDateTime() + "\n" : "")
                + (eventDTO.location() != null && !eventDTO.location().isBlank()
                   ? "📍 " + escapeHtml(eventDTO.location()) + "\n" : "")
                + (eventDTO.reminders() != null && !eventDTO.reminders().isEmpty()
                   ? "🔔 Lembretes: " + eventDTO.reminders() + " min\n" : "")
                + "\n🤖 Modelo: " + modelUsed;

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("Sim, criar evento")
                                .callbackData("confirm_event")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("Nao, cancelar")
                                .callbackData("cancel_event")
                                .build()
                ))
                .build();

        sendHtmlWithKeyboard(chatId, confirmMsg, keyboard);
    }

    private void handleCallbackQuery(Update update) {
        var callbackQuery = update.getCallbackQuery();
        String data = callbackQuery.getData();
        Long chatId = callbackQuery.getMessage().getChatId();
        Long telegramId = callbackQuery.getFrom().getId();

        try {
            AppUser user = userRepository.findById(telegramId)
                    .orElseThrow(() -> new RuntimeException("Usuario nao encontrado"));

            if ("confirm_event".equals(data)) {
                PendingCreate pending = pendingEvents.remove(telegramId);
                if (pending == null || pending.dto() == null) {
                    sendRawText(chatId, "⚠️ Evento nao encontrado. Operacao expirada. Tente novamente.");
                    return;
                }
                String eventLink = calendarService.createEvent(user, pending.dto(), pending.chatId(), pending.messageId());

                String safeSummary = escapeHtml(pending.dto().summary());
                String msg = "✅ <b>Agendado!</b>\n\n"
                        + "📝 " + safeSummary + "\n"
                        + "⏰ " + pending.dto().startDateTime() + "\n"
                        + "\n<a href=\"" + escapeHtml(eventLink) + "\">Ver no Google Agenda</a>";

                sendHtmlText(chatId, msg);

            } else if ("cancel_event".equals(data)) {
                pendingEvents.remove(telegramId);
                sendRawText(chatId, "❌ Evento cancelado. Nada foi criado.");

            } else if ("confirm_edit_event".equals(data)) {
                PendingEdit pending = pendingEdits.remove(telegramId);
                if (pending == null) {
                    sendRawText(chatId, "⚠️ Edicao expirada. Tente novamente.");
                    return;
                }
                String eventLink = calendarService.updateEvent(user, pending.eventId(), pending.newDto());

                String msg = "✅ <b>Evento alterado!</b>\n\n"
                        + "📝 " + escapeHtml(pending.newDto().summary()) + "\n"
                        + "⏰ " + pending.newDto().startDateTime() + "\n"
                        + "\n<a href=\"" + escapeHtml(eventLink) + "\">Ver no Google Agenda</a>";

                sendHtmlText(chatId, msg);

            } else if ("cancel_edit_event".equals(data)) {
                pendingEdits.remove(telegramId);
                multiSelectStates.remove(telegramId);
                sendRawText(chatId, "❌ Edicao cancelada. Nada foi alterado.");

            } else if ("confirm_delete_event".equals(data)) {
                PendingDelete pending = pendingDeletes.remove(telegramId);
                if (pending == null) {
                    sendRawText(chatId, "⚠️ Exclusao expirada. Tente novamente.");
                    return;
                }
                String summary = pending.currentEvent().getSummary();
                calendarService.deleteEvent(user, pending.eventId());

                sendRawText(chatId, "✅ Evento \"" + summary + "\" deletado!");

            } else if ("cancel_delete_event".equals(data)) {
                pendingDeletes.remove(telegramId);
                multiSelectStates.remove(telegramId);
                sendRawText(chatId, "❌ Exclusao cancelada. Nada foi deletado.");

            } else if (data.startsWith("edit_select_")) {
                int idx = Integer.parseInt(data.substring("edit_select_".length()));
                MultiSelectState state = multiSelectStates.remove(telegramId);
                if (state == null || idx < 0 || idx >= state.candidates().size()) {
                    sendRawText(chatId, "⚠️ Selecao expirada. Tente novamente.");
                    return;
                }
                Event event = state.candidates().get(idx);
                showEditDiffAndConfirm(chatId, telegramId, user, event, state.newDto());

            } else if (data.startsWith("delete_select_")) {
                int idx = Integer.parseInt(data.substring("delete_select_".length()));
                MultiSelectState state = multiSelectStates.remove(telegramId);
                if (state == null || idx < 0 || idx >= state.candidates().size()) {
                    sendRawText(chatId, "⚠️ Selecao expirada. Tente novamente.");
                    return;
                }
                Event event = state.candidates().get(idx);
                showDeleteConfirm(chatId, telegramId, user, event);
            }

        } catch (Exception e) {
            log.error("Erro ao processar callback do usuario {}", telegramId, e);
            pendingEvents.remove(telegramId);
            pendingEdits.remove(telegramId);
            pendingDeletes.remove(telegramId);
            multiSelectStates.remove(telegramId);
            sendRawText(chatId, "❌ Falha na operacao. Tente novamente.");
        }
    }

    private String getModelDisplayName(AppUser user, boolean hasMedia) {
        String model = hasMedia ? user.getPreferredFileModel() : user.getPreferredTextModel();
        if (model == null || model.isBlank()) {
            model = hasMedia
                    ? settingsService.getConfig().getDefaultFileModel()
                    : settingsService.getConfig().getDefaultTextModel();
        }
        if (model == null || model.isBlank()) return "Padrao";
        if (model.contains("deepseek")) return "DeepSeek";
        if (model.contains("pro")) return "Gemini Pro";
        if (model.contains("flash-lite")) return "Gemini Lite";
        if (model.contains("flash")) return "Gemini Flash";
        return model;
    }

    private String formatEventDateTime(EventDateTime edt) {
        try {
            DateTime dt = edt.getDateTime() != null ? edt.getDateTime() : edt.getDate();
            if (dt == null) return "?";
            ZonedDateTime zdt = Instant.ofEpochMilli(dt.getValue())
                    .atZone(ZoneId.of("America/Sao_Paulo"));
            return zdt.format(DT_FMT_FULL);
        } catch (Exception e) {
            return edt.toString();
        }
    }

    private String formatIsoDateTime(String isoStr) {
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(isoStr)
                    .withZoneSameInstant(ZoneId.of("America/Sao_Paulo"));
            return zdt.format(DT_FMT_FULL);
        } catch (Exception e) {
            return isoStr;
        }
    }

    private ReplyKeyboardMarkup mainKeyboard() {
        KeyboardRow row1 = new KeyboardRow();
        row1.add("/start");
        row1.add("/eventos");
        row1.add("/config");

        return ReplyKeyboardMarkup.builder()
                .keyboardRow(row1)
                .resizeKeyboard(true)
                .build();
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

    private byte[] downloadPhoto(List<PhotoSize> photos) throws TelegramApiException, IOException {
        PhotoSize photoSize = photos.stream()
                .max(Comparator.comparing(PhotoSize::getFileSize))
                .orElseThrow(() -> new IllegalStateException("Foto vazia"));
        return downloadFile(photoSize.getFileId());
    }

    private byte[] downloadFile(String fileId) throws TelegramApiException, IOException {
        GetFile getFileMethod = new GetFile(fileId);
        File file = getTelegramClient().execute(getFileMethod);
        try (InputStream is = getTelegramClient().downloadFileAsStream(file)) {
            return is.readAllBytes();
        }
    }

    private void sendHtmlText(Long chatId, String text) {
        SendMessage sm = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("HTML")
                .disableWebPagePreview(true)
                .replyMarkup(mainKeyboard())
                .build();
        try {
            getTelegramClient().execute(sm);
        } catch (TelegramApiException e) {
            log.warn("Falha ao enviar HTML para chat {}. Reenviando como texto puro.", chatId, e);
            sendRawText(chatId, text.replaceAll("<[^>]+>", ""));
        }
    }

    private void sendHtmlWithKeyboard(Long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage sm = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("HTML")
                .replyMarkup(keyboard)
                .build();
        try {
            getTelegramClient().execute(sm);
        } catch (TelegramApiException e) {
            log.warn("Falha ao enviar HTML com keyboard para chat {}", chatId, e);
            sendRawText(chatId, text.replaceAll("<[^>]+>", ""));
        }
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private void sendRawText(Long chatId, String text) {
        SendMessage sm = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(mainKeyboard())
                .build();
        try {
            getTelegramClient().execute(sm);
        } catch (TelegramApiException e) {
            log.error("Falha ao enviar mensagem para chat {}", chatId, e);
        }
    }
}
