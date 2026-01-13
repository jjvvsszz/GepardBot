package tk.jaooo.gepard.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tk.jaooo.gepard.model.AppUser;
import tk.jaooo.gepard.repository.AppUserRepository;
import tk.jaooo.gepard.service.GoogleCalendarService;

@Controller
@RequiredArgsConstructor
public class UserConfigController {

    private final AppUserRepository userRepository;
    private final GoogleCalendarService calendarService;

    @GetMapping("/user/config")
    public String showUserConfig(@RequestParam("token") String token, Model model) {
        AppUser user = userRepository.findByWebLoginToken(token)
                .orElseThrow(() -> new RuntimeException("Link inválido ou expirado. Digite /config no Telegram novamente."));

        model.addAttribute("user", user);

        // Gera link de auth do Google caso ele queira conectar
        String googleAuthLink = calendarService.buildAuthorizationUrl(user.getTelegramId());
        model.addAttribute("googleAuthLink", googleAuthLink);

        return "user_config"; // Template HTML
    }

    @PostMapping("/user/config/save")
    public String saveUserConfig(
            @RequestParam("token") String token,
            @RequestParam("geminiApiKey") String geminiApiKey,
            Model model) {

        AppUser user = userRepository.findByWebLoginToken(token)
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado."));

        // Atualiza apenas a chave Gemini (Google é via OAuth separado)
        user.setGeminiApiKey(geminiApiKey.trim());
        userRepository.save(user);

        model.addAttribute("message", "✅ Configurações salvas com sucesso! Pode voltar ao Telegram.");
        model.addAttribute("user", user);
        model.addAttribute("googleAuthLink", calendarService.buildAuthorizationUrl(user.getTelegramId()));

        return "user_config";
    }
}
