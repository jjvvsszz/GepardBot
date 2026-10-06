package tk.jaooo.gepard.controller;

import com.google.api.services.calendar.model.CalendarListEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tk.jaooo.gepard.model.AppUser;
import tk.jaooo.gepard.repository.AppUserRepository;
import tk.jaooo.gepard.service.AiService;
import tk.jaooo.gepard.service.GoogleCalendarService;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Controller
@RequiredArgsConstructor
public class UserConfigController {

    /** Fusos mais comuns para o seletor; outros podem ser definidos com /fuso no Telegram. */
    static final List<String> COMMON_ZONES = List.of(
            "America/Sao_Paulo", "America/Bahia", "America/Fortaleza", "America/Recife", "America/Belem",
            "America/Maceio", "America/Araguaina", "America/Cuiaba", "America/Campo_Grande", "America/Porto_Velho",
            "America/Boa_Vista", "America/Manaus", "America/Rio_Branco", "America/Noronha",
            "Europe/Lisbon", "Europe/London", "Europe/Madrid", "America/New_York", "America/Chicago",
            "America/Los_Angeles", "America/Argentina/Buenos_Aires", "America/Santiago", "UTC");

    private final AppUserRepository userRepository;
    private final GoogleCalendarService calendarService;

    @GetMapping("/user/config")
    public String showUserConfig(@RequestParam("token") String token, Model model) {
        AppUser user = userRepository.findByWebLoginToken(token)
                .orElseThrow(() -> new RuntimeException("Link invalido ou expirado. Digite /config no Telegram novamente."));

        if (user.isWebTokenExpired()) {
            throw new RuntimeException("Link expirado. Digite /config no Telegram novamente.");
        }

        fillModel(model, user);
        return "user_config";
    }

    @PostMapping("/user/config/save")
    public String saveUserConfig(
            @RequestParam("token") String token,
            @RequestParam("geminiApiKey") String geminiApiKey,
            @RequestParam(value = "deepSeekApiKey", required = false) String deepSeekApiKey,
            @RequestParam(value = "preferredTextModel", required = false) String preferredTextModel,
            @RequestParam(value = "preferredFileModel", required = false) String preferredFileModel,
            @RequestParam(value = "timeZone", required = false) String timeZone,
            @RequestParam(value = "calendarId", required = false) String calendarId,
            @RequestParam(value = "dailySummaryEnabled", required = false) String dailySummaryEnabled,
            @RequestParam(value = "dailySummaryTime", required = false) String dailySummaryTime,
            Model model) {

        AppUser user = userRepository.findByWebLoginToken(token)
                .orElseThrow(() -> new RuntimeException("Usuario nao encontrado."));

        user.setGeminiApiKey(geminiApiKey != null ? geminiApiKey.trim() : null);

        if (deepSeekApiKey != null && !deepSeekApiKey.isBlank()) {
            user.setDeepSeekApiKey(deepSeekApiKey.trim());
        }

        if (preferredTextModel != null && AiService.getAllModels().contains(preferredTextModel)) {
            user.setPreferredTextModel(preferredTextModel);
        }

        if (preferredFileModel != null && AiService.getFileModels().contains(preferredFileModel)) {
            user.setPreferredFileModel(preferredFileModel);
        }

        if (timeZone != null && !timeZone.isBlank()) {
            try {
                user.setTimeZone(ZoneId.of(timeZone.trim()).getId());
            } catch (Exception e) {
                log.warn("Fuso invalido recebido do painel: {}", timeZone);
            }
        }

        if (calendarId != null) {
            if (calendarId.isBlank() || "primary".equals(calendarId)) {
                user.setCalendarId(null);
            } else if (writableCalendars(user).stream().anyMatch(c -> calendarId.equals(c.getId()))) {
                user.setCalendarId(calendarId);
            }
        }

        user.setDailySummaryEnabled(dailySummaryEnabled != null);
        if (dailySummaryTime != null && !dailySummaryTime.isBlank()) {
            try {
                user.setDailySummaryTime(LocalTime.parse(dailySummaryTime.trim()).withSecond(0).toString());
            } catch (Exception e) {
                log.warn("Horario de resumo invalido recebido do painel: {}", dailySummaryTime);
            }
        }

        userRepository.save(user);

        model.addAttribute("message", "✅ Configurações salvas com sucesso!");
        fillModel(model, user);
        return "user_config";
    }

    private void fillModel(Model model, AppUser user) {
        model.addAttribute("user", user);
        model.addAttribute("allModels", AiService.getAllModels());
        model.addAttribute("fileModels", AiService.getFileModels());
        model.addAttribute("googleAuthLink", calendarService.buildAuthorizationUrl(user.getTelegramId()));

        List<String> zones = new ArrayList<>(COMMON_ZONES);
        if (!zones.contains(user.zone().getId())) zones.addFirst(user.zone().getId());
        model.addAttribute("zones", zones);
        model.addAttribute("currentZone", user.zone().getId());
        model.addAttribute("calendars", writableCalendars(user));
        model.addAttribute("summaryTime", user.summaryTime().toString());
    }

    private List<CalendarListEntry> writableCalendars(AppUser user) {
        if (!user.isGoogleConnected()) return List.of();
        try {
            return calendarService.listWritableCalendars(user);
        } catch (Exception e) {
            log.warn("Falha ao listar agendas do usuario {}", user.getTelegramId(), e);
            return List.of();
        }
    }
}
