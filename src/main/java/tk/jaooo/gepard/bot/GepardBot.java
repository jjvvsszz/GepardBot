package tk.jaooo.gepard.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import tk.jaooo.gepard.model.AppUser;
import tk.jaooo.gepard.model.dto.EventExtractionDTO;
import tk.jaooo.gepard.repository.AppUserRepository;
import tk.jaooo.gepard.service.GeminiService;
import tk.jaooo.gepard.service.GoogleCalendarService;
import tk.jaooo.gepard.service.SystemSettingsService;

import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
public class GepardBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private final SystemSettingsService settingsService;
    private final TelegramClient telegramClient;
    private final GeminiService geminiService;
    private final GoogleCalendarService calendarService;
    private final AppUserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public GepardBot(
            SystemSettingsService settingsService,
            TelegramClient telegramClient,
            GeminiService geminiService,
            GoogleCalendarService calendarService,
            AppUserRepository userRepository,
            ObjectMapper objectMapper,
            @Value("${gepard.base-url}") String baseUrl) { // Injeção
        this.settingsService = settingsService;
        this.telegramClient = telegramClient;
        this.geminiService = geminiService;
        this.calendarService = calendarService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public String getBotToken() { return settingsService.getConfig().getTelegramBotToken(); }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() { return this; }

    @Override
    public void consume(Update update) {
        if (!update.hasMessage()) return;
        Message message = update.getMessage();
        Long telegramId = message.getFrom().getId();
        Long chatId = message.getChatId();
        String text = message.hasText() ? message.getText() : "";

        try {
            AppUser user = userRepository.findById(telegramId).orElseGet(() ->
                    userRepository.save(AppUser.builder()
                            .telegramId(telegramId)
                            .username(message.getFrom().getUserName())
                            .firstName(message.getFrom().getFirstName())
                            .build())
            );

            if (text.equals("/config") || text.equals("/start")) {
                String token = java.util.UUID.randomUUID().toString();
                user.setWebLoginToken(token);
                userRepository.save(user);

                String link = baseUrl + "/user/config?token=" + token;

                String msg = """
                ⚙️ <b>Painel de Configuração</b>
                
                Clique no link abaixo para gerenciar sua Chave Gemini e conectar sua Agenda:
                
                👉 <a href="%s">Abrir Minhas Configurações</a>
                
                <i>(O link é seguro e exclusivo para você)</i>
                
                Caso não abra, copie:
                <code>%s</code>
                """.formatted(link, link);

                sendHtmlText(chatId, msg);
                return;
            }

            if (!user.hasApiKey()) {
                handleApiKeyFlow(message, user);
                return;
            }

            if (user.getGoogleRefreshToken() == null) {
                String authLink = calendarService.buildAuthorizationUrl(telegramId);
                String msg = """
                        📅 <b>Permissão necessária</b>
                        
                        <a href="%s">Clique aqui para conectar sua Agenda</a>
                        """.formatted(authLink);
                sendHtmlText(chatId, msg);
                return;
            }

            handleSmartScheduling(message, user);

        } catch (Exception e) {
            log.error("Erro fatal", e);
            sendRawText(chatId, "❌ Erro: " + e.getMessage());
        }
    }

    private void handleApiKeyFlow(Message message, AppUser user) {
        String text = message.hasText() ? message.getText().trim() : "";
        if (text.startsWith("AIza")) {
            user.setGeminiApiKey(text);
            userRepository.save(user);
            sendHtmlText(message.getChatId(), "✅ <b>API Key Salva!</b>\nAgora mande o evento (texto ou foto).");
        } else {
            sendHtmlText(message.getChatId(), "👋 Envie sua <b>Gemini API Key</b> para começar.");
        }
    }

    private void handleSmartScheduling(Message message, AppUser user) {
        Long chatId = message.getChatId();
        sendTypingAction(chatId);

        try {
            byte[] imageBytes = null;
            if (message.hasPhoto()) imageBytes = downloadPhoto(message.getPhoto());

            String prompt = message.getCaption() != null ? message.getCaption() : message.getText();
            if (prompt == null) prompt = "Detalhes na imagem";

            ZonedDateTime nowSP = ZonedDateTime.now(ZoneId.of("America/Sao_Paulo"));
            String fullPrompt = String.format("Hoje é %s (Fuso America/Sao_Paulo). O usuário pede: %s",
                    nowSP.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), prompt);

            String jsonResponse = geminiService.generateContent(fullPrompt, imageBytes, user);
            EventExtractionDTO eventDTO = objectMapper.readValue(jsonResponse, EventExtractionDTO.class);

            String eventLink = calendarService.createEvent(user, eventDTO);

            String safeSummary = HtmlUtils.htmlEscape(eventDTO.summary());
            String msg = """
                    ✅ <b>Agendado!</b>
                    
                    📝 %s
                    ⏰ %s
                    
                    <a href="%s">Ver no Google Agenda</a>
                    """.formatted(safeSummary, eventDTO.startDateTime(), eventLink);

            sendHtmlText(chatId, msg);

        } catch (RuntimeException e) {
            String errorMsg = e.getMessage();
            log.error("Erro IA/Agenda: {}", errorMsg);

            if (errorMsg.contains("429") || errorMsg.contains("quota")) {
                sendRawText(chatId, "⏳ Cota excedida do Gemini. Tente novamente em breve.");
            } else if (errorMsg.contains("404") || errorMsg.contains("Not Found")) {
                sendRawText(chatId, "❌ Modelo não encontrado. Verifique a configuração.");
            } else if (errorMsg.contains("Unable to process input image")) {
                sendRawText(chatId, "❌ A IA não conseguiu ler a imagem. Tente outra foto.");
            } else {
                sendRawText(chatId, "❌ Erro: " + errorMsg);
            }
        } catch (Exception e) {
            log.error("Erro genérico", e);
            sendRawText(chatId, "❌ Erro inesperado: " + e.getMessage());
        }
    }

    private void sendTypingAction(Long chatId) {
        try {
            telegramClient.execute(SendChatAction.builder()
                    .chatId(chatId)
                    .action(ActionType.TYPING.toString()).build());
        } catch (TelegramApiException _) {}
    }

    private byte[] downloadPhoto(List<PhotoSize> photos) throws TelegramApiException, IOException {
        PhotoSize photoSize = photos.stream()
                .max(Comparator.comparing(PhotoSize::getFileSize))
                .orElseThrow(() -> new IllegalStateException("Foto vazia"));
        GetFile getFileMethod = new GetFile(photoSize.getFileId());
        File file = telegramClient.execute(getFileMethod);
        try (InputStream is = telegramClient.downloadFileAsStream(file)) {
            return is.readAllBytes();
        }
    }

    private void sendHtmlText(Long chatId, String text) {
        SendMessage sm = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .parseMode("HTML")
                .disableWebPagePreview(true)
                .build();
        try {
            telegramClient.execute(sm);
        } catch (TelegramApiException e) {
            log.error("Erro ao enviar HTML Telegram", e);
            sendRawText(chatId, text);
        }
    }

    private void sendRawText(Long chatId, String text) {
        SendMessage sm = SendMessage.builder().chatId(chatId).text(text).build();
        try { telegramClient.execute(sm); } catch (TelegramApiException e) { log.error("F", e); }
    }
}
