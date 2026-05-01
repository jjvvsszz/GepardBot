package tk.jaooo.gepard.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tk.jaooo.gepard.model.AppUser;
import tk.jaooo.gepard.repository.AppUserRepository;
import tk.jaooo.gepard.service.AiService;
import tk.jaooo.gepard.service.GoogleCalendarService;

@Controller
@RequiredArgsConstructor
public class UserConfigController {

    private final AppUserRepository userRepository;
    private final GoogleCalendarService calendarService;

    @GetMapping("/user/config")
    public String showUserConfig(@RequestParam("token") String token, Model model) {
        AppUser user = userRepository.findByWebLoginToken(token)
                .orElseThrow(() -> new RuntimeException("Link invalido ou expirado. Digite /config no Telegram novamente."));

        if (user.isWebTokenExpired()) {
            throw new RuntimeException("Link expirado. Digite /config no Telegram novamente.");
        }

        model.addAttribute("user", user);
        model.addAttribute("availableModels", AiService.getAllModels());

        String googleAuthLink = calendarService.buildAuthorizationUrl(user.getTelegramId());
        model.addAttribute("googleAuthLink", googleAuthLink);

        return "user_config";
    }

    @PostMapping("/user/config/save")
    public String saveUserConfig(
            @RequestParam("token") String token,
            @RequestParam("geminiApiKey") String geminiApiKey,
            @RequestParam(value = "preferredModel", required = false) String preferredModel,
            Model model) {

        AppUser user = userRepository.findByWebLoginToken(token)
                .orElseThrow(() -> new RuntimeException("Usuario nao encontrado."));

        user.setGeminiApiKey(geminiApiKey.trim());

        if (preferredModel != null && AiService.getAllModels().contains(preferredModel)) {
            user.setPreferredModel(preferredModel);
        }

        userRepository.save(user);

        model.addAttribute("message", "✅ Configuracoes salvas com sucesso!");
        model.addAttribute("user", user);
        model.addAttribute("availableModels", AiService.getAllModels());
        model.addAttribute("googleAuthLink", calendarService.buildAuthorizationUrl(user.getTelegramId()));

        return "user_config";
    }
}
