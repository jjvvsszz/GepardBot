package tk.jaooo.gepard.config;

import lombok.Getter;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import tk.jaooo.gepard.service.SystemSettingsService;

@Configuration
public class BotConfig {

    @Getter
    private volatile TelegramClient telegramClient;

    public BotConfig(SystemSettingsService settingsService) {
        this.telegramClient = new OkHttpTelegramClient(settingsService.getConfig().getTelegramBotToken());
    }

    public void refreshToken(String newToken) {
        this.telegramClient = new OkHttpTelegramClient(newToken);
    }
}
