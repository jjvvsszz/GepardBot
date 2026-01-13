package tk.jaooo.gepard.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tk.jaooo.gepard.model.GlobalConfig;
import tk.jaooo.gepard.service.SystemSettingsService;

@Controller
@RequiredArgsConstructor
public class AdminController {

    private final SystemSettingsService settingsService;

    @GetMapping("/admin")
    public String adminPanel(Model model) {
        GlobalConfig config = settingsService.getConfig();
        model.addAttribute("config", config);
        return "admin";
    }

    @PostMapping("/admin/save")
    public String saveConfig(
            @RequestParam String telegramBotToken,
            @RequestParam String telegramBotUsername,
            @RequestParam String googleClientId,
            @RequestParam String googleClientSecret,
            @RequestParam String geminiModel,
            Model model) {

        settingsService.updateConfig(telegramBotToken, telegramBotUsername, googleClientId, googleClientSecret, geminiModel);

        model.addAttribute("message", "Configurações salvas! Reinicie a aplicação para que as alterações de Token surtam efeito.");
        model.addAttribute("config", settingsService.getConfig());
        return "admin";
    }
}
