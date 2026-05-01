package tk.jaooo.gepard.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
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
import tk.jaooo.gepard.model.dto.EventExtractionDTO;
import tk.jaooo.gepard.repository.AppUserRepository;
import tk.jaooo.gepard.service.AiService;
import tk.jaooo.gepard.service.GoogleCalendarService;
import tk.jaooo.gepard.service.SystemSettingsService;

import java.io.IOException;
import java.io.InputStream;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class GepardBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private final SystemSettingsService settingsService;
    private final BotConfig botConfig;
    private final AiService aiService;
    private final GoogleCalendarService calendarService;
    private final AppUserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    private final Map<Long, EventExtractionDTO> pendingEvents = new ConcurrentHashMap<>();
    private final Map<Long, List<Event>> recentEvents = new ConcurrentHashMap<>();

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
                    sendHtmlText(chatId, "⚙️ <a href=\"" + link + "\">Abrir Configuracoes</a>");
                    return;
                }
                case "/eventos", "/events" -> {
                    handleListEvents(chatId, user);
                    return;
                }
                case "/cancelar", "/cancel" -> {
                    pendingEvents.remove(telegramId);
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
                sendHtmlText(chatId, "📅 <a href=\"" + authLink + "\">Conectar Google Agenda</a>");
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
        sb.append("Eu crio eventos no Google Agenda a partir de texto, fotos ou audio!\n\n");

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
            sb.append("<b>Dica:</b> Envie texto, foto ou audio descrevendo um evento!\n");
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
                sb.append(i + 1).append(". <b>").append(HtmlUtils.htmlEscape(e.getSummary())).append("</b>\n");
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
            sendHtmlText(message.getChatId(), "✅ Gemini Key salva! <a href=\"" + authLink + "\">Conectar Agenda</a>");
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

            EventExtractionDTO eventDTO = objectMapper.readValue(jsonResponse, EventExtractionDTO.class);

            pendingEvents.put(telegramId, eventDTO);

            String safeSummary = HtmlUtils.htmlEscape(eventDTO.summary());
            String modelUsed = getModelDisplayName(user, mediaBytes != null);

            String confirmMsg = """
                    📌 <b>Confirmar Evento?</b>

                    📝 %s
                    ⏰ Inicio: %s
                    """.formatted(safeSummary, eventDTO.startDateTime())
                    + (eventDTO.endDateTime() != null && !eventDTO.endDateTime().isBlank()
                       ? "⏰ Fim: " + eventDTO.endDateTime() + "\n" : "")
                    + (eventDTO.location() != null && !eventDTO.location().isBlank()
                       ? "📍 " + HtmlUtils.htmlEscape(eventDTO.location()) + "\n" : "")
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

    private void handleCallbackQuery(Update update) {
        var callbackQuery = update.getCallbackQuery();
        String data = callbackQuery.getData();
        Long chatId = callbackQuery.getMessage().getChatId();
        Long telegramId = callbackQuery.getFrom().getId();

        try {
            AppUser user = userRepository.findById(telegramId)
                    .orElseThrow(() -> new RuntimeException("Usuario nao encontrado"));

            if ("confirm_event".equals(data)) {
                EventExtractionDTO eventDTO = pendingEvents.remove(telegramId);
                if (eventDTO == null) {
                    sendRawText(chatId, "⚠️ Evento nao encontrado. Operacao expirada. Tente novamente.");
                    return;
                }
                String eventLink = calendarService.createEvent(user, eventDTO);

                String safeSummary = HtmlUtils.htmlEscape(eventDTO.summary());
                String msg = "✅ <b>Agendado!</b>\n\n"
                        + "📝 " + safeSummary + "\n"
                        + "⏰ " + eventDTO.startDateTime() + "\n"
                        + "\n<a href=\"" + eventLink + "\">Ver no Google Agenda</a>";

                sendHtmlText(chatId, msg);

            } else if ("cancel_event".equals(data)) {
                pendingEvents.remove(telegramId);
                sendRawText(chatId, "❌ Evento cancelado. Nada foi criado.");
            }

        } catch (Exception e) {
            log.error("Erro ao processar callback do usuario {}", telegramId, e);
            pendingEvents.remove(telegramId);
            sendRawText(chatId, "❌ Falha ao criar o evento. Tente novamente.");
        }
    }

    private String getModelDisplayName(AppUser user, boolean hasMedia) {
        String model = hasMedia ? user.getPreferredFileModel() : user.getPreferredTextModel();
        if (model == null || model.isBlank()) {
            model = settingsService.getConfig().getGeminiModel();
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
            return zdt.format(DT_FMT);
        } catch (Exception e) {
            return edt.toString();
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
            sendRawText(chatId, text);
        }
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
