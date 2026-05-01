package tk.jaooo.gepard.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tk.jaooo.gepard.config.BotConfig;
import tk.jaooo.gepard.model.GlobalConfig;
import tk.jaooo.gepard.repository.AppUserRepository;
import tk.jaooo.gepard.service.AiService;
import tk.jaooo.gepard.service.SystemSettingsService;

@Controller
@RequiredArgsConstructor
public class AdminController {

    private final SystemSettingsService settingsService;
    private final BotConfig botConfig;
    private final AppUserRepository userRepository;

    @GetMapping("/admin")
    public String adminPanel(Model model) {
        GlobalConfig config = settingsService.getConfig();

        if (Boolean.TRUE.equals(config.getAdminSetupRequired())) {
            return "redirect:/admin/setup";
        }

        model.addAttribute("config", config);
        model.addAttribute("availableModels", AiService.getAllModels());
        model.addAttribute("userCount", userRepository.count());
        return "admin";
    }

    @GetMapping("/admin/users")
    public String userList(Model model) {
        model.addAttribute("users", userRepository.findAll());
        model.addAttribute("count", userRepository.count());
        return "admin_users";
    }

    @GetMapping("/admin/setup")
    public String setupPage() {
        GlobalConfig config = settingsService.getConfig();
        if (!Boolean.TRUE.equals(config.getAdminSetupRequired())) {
            return "redirect:/admin";
        }
        return "admin_setup";
    }

    @PostMapping("/admin/setup/save")
    public String saveCredentials(
            @RequestParam String username,
            @RequestParam String password) {

        settingsService.updateAdminCredentials(username, password);
        return "redirect:/admin";
    }

    @PostMapping("/admin/save")
    public String saveConfig(
            @RequestParam String telegramBotToken,
            @RequestParam String telegramBotUsername,
            @RequestParam String googleClientId,
            @RequestParam String googleClientSecret,
            @RequestParam String geminiModel,
            Model model) {

        String oldToken = settingsService.getConfig().getTelegramBotToken();
        settingsService.updateConfig(telegramBotToken, telegramBotUsername, googleClientId, googleClientSecret, geminiModel);

        if (!telegramBotToken.equals(oldToken)) {
            botConfig.refreshToken(telegramBotToken);
            model.addAttribute("message", "✅ Configuracoes salvas! Token do bot atualizado em tempo real.");
        } else {
            model.addAttribute("message", "✅ Configuracoes salvas! Reinicie a aplicacao para aplicar novos IDs/OAuth.");
        }

        model.addAttribute("config", settingsService.getConfig());
        model.addAttribute("availableModels", AiService.getAllModels());
        model.addAttribute("userCount", userRepository.count());
        return "admin";
    }
}
