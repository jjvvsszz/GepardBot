package tk.jaooo.gepard.service;

import com.google.api.services.calendar.model.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import tk.jaooo.gepard.bot.BotTexts;
import tk.jaooo.gepard.config.BotConfig;
import tk.jaooo.gepard.model.AppUser;
import tk.jaooo.gepard.repository.AppUserRepository;
import tk.jaooo.gepard.util.EventTimes;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/** Envia a agenda do dia no horario escolhido por cada usuario (/resumo). */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailySummaryService {

    /** Depois desse atraso (ex: servidor fora do ar) o resumo do dia e pulado em vez de chegar tarde. */
    private static final long MAX_DELAY_MINUTES = 120;

    private final AppUserRepository userRepository;
    private final GoogleCalendarService calendarService;
    private final BotConfig botConfig;

    @Scheduled(cron = "0 * * * * *")
    public void tick() {
        for (AppUser user : userRepository.findByDailySummaryEnabledTrue()) {
            try {
                processUser(user);
            } catch (Exception e) {
                log.error("Falha no resumo diario do usuario {}", user.getTelegramId(), e);
            }
        }
    }

    /** True se o resumo deve sair agora: horario atingido, ainda nao enviado hoje e sem atraso excessivo. */
    static boolean isDue(LocalTime scheduled, LocalDate lastSent, ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        if (today.equals(lastSent)) return false;
        LocalTime t = now.toLocalTime();
        if (t.isBefore(scheduled)) return false;
        return java.time.Duration.between(scheduled, t).toMinutes() <= MAX_DELAY_MINUTES;
    }

    private void processUser(AppUser user) throws Exception {
        if (user.getGoogleRefreshToken() == null) return;
        ZoneId zone = user.zone();
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate today = now.toLocalDate();
        if (today.equals(user.getLastDailySummaryDate())) return;

        if (!isDue(user.summaryTime(), user.getLastDailySummaryDate(), now)) {
            // Passou da janela (ex: horario mudado para mais cedo): marca para nao enviar atrasado.
            if (!now.toLocalTime().isBefore(user.summaryTime())) {
                markSent(user, today);
            }
            return;
        }
        // Marca antes de enviar: uma falha no Google nao deve gerar uma tentativa por minuto.
        markSent(user, today);

        ZonedDateTime start = today.atStartOfDay(zone);
        List<Event> events = calendarService.listEvents(user, null, start.toInstant(), start.plusDays(1).toInstant(), 30);

        StringBuilder sb = new StringBuilder("☀️ <b>Bom dia");
        if (user.getFirstName() != null && !user.getFirstName().isBlank()) {
            sb.append(", ").append(BotTexts.escapeHtml(user.getFirstName()));
        }
        sb.append("!</b>\n\n📅 <b>")
                .append(EventTimes.formatPeriod(new EventTimes.TimeRange(start, start.plusDays(1), false), today))
                .append("</b>\n\n");
        if (events.isEmpty()) {
            sb.append("Nada marcado para hoje. Aproveite! 🎉");
        } else {
            sb.append(BotTexts.agenda(events, zone));
        }
        sb.append("\n\n<i>/resumo off para desativar</i>");

        botConfig.getTelegramClient().execute(SendMessage.builder()
                .chatId(user.getTelegramId())
                .text(sb.toString())
                .parseMode("HTML")
                .disableWebPagePreview(true)
                .build());
    }

    private void markSent(AppUser user, LocalDate today) {
        user.setLastDailySummaryDate(today);
        userRepository.save(user);
    }
}
