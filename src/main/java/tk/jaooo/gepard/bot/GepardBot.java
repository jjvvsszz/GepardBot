package tk.jaooo.gepard.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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

    public GepardBot(
            SystemSettingsService settingsService,
            TelegramClient telegramClient,
            GeminiService geminiService,
            GoogleCalendarService calendarService,
            AppUserRepository userRepository,
            ObjectMapper objectMapper) {
        this.settingsService = settingsService;
        this.telegramClient = telegramClient;
        this.geminiService = geminiService;
        this.calendarService = calendarService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
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

            if (!user.hasApiKey()) {
                handleApiKeyFlow(message, user);
                return;
            }

            if (user.getGoogleRefreshToken() == null) {
                String authLink = calendarService.buildAuthorizationUrl(telegramId);
                sendMarkdownText(chatId, "📅 **Permissão necessária**\n\n[Clique aqui para conectar sua Agenda](" + authLink + ")");
                return;
            }

            if (text.equals("/config") || text.equals("/start")) {
                String token = java.util.UUID.randomUUID().toString();
                user.setWebLoginToken(token);
                userRepository.save(user);

                String link = "http://localhost:8080/user/config?token=" + token;

                String msg = """
                ⚙️ **Painel de Configuração**
                
                Clique no link abaixo para gerenciar sua Chave Gemini e conectar sua Agenda:
                
                👉 [Abrir Minhas Configurações](%s)
                
                *(O link é seguro e exclusivo para você)*
                """.formatted(link);

                sendMarkdownText(chatId, msg);
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
            sendMarkdownText(message.getChatId(), "✅ **API Key Salva!**\nAgora mande o evento.");
        } else {
            sendMarkdownText(message.getChatId(), "👋 Envie sua **Gemini API Key** para começar.");
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

            sendMarkdownText(chatId, "✅ **Agendado!**\n\n" +
                    "📝 " + eventDTO.summary() + "\n" +
                    "⏰ " + eventDTO.startDateTime() + "\n" +
                    "[Ver no Google Agenda](" + eventLink + ")");

        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            String errorMsg = e.getMessage();

            log.error("Erro no processo de IA/Agenda: {}", errorMsg);

            if (errorMsg.contains("429") || errorMsg.contains("quota")) {
                sendRawText(chatId, "⏳ Cota excedida. Tente novamente em breve.");
            } else if (errorMsg.contains("404") || errorMsg.contains("Not Found")) {
                sendRawText(chatId, "❌ Modelo não encontrado ou erro de API.");
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
        } catch (TelegramApiException e) {}
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

    private void sendMarkdownText(Long chatId, String text) {
        SendMessage sm = SendMessage.builder().chatId(chatId).text(text).parseMode("Markdown").build();
        try { telegramClient.execute(sm); } catch (TelegramApiException e) { sendRawText(chatId, text); }
    }

    private void sendRawText(Long chatId, String text) {
        SendMessage sm = SendMessage.builder().chatId(chatId).text(text).build();
        try { telegramClient.execute(sm); } catch (TelegramApiException e) { log.error("F", e); }
    }
}
