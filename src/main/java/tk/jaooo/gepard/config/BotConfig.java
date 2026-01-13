package tk.jaooo.gepard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import tk.jaooo.gepard.service.SystemSettingsService;

@Configuration
public class BotConfig {

    @Bean
    public TelegramClient telegramClient(SystemSettingsService settingsService) {
        String token = settingsService.getConfig().getTelegramBotToken();
        return new OkHttpTelegramClient(token);
    }
}
