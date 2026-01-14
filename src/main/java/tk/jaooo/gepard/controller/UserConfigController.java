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

import java.util.List;

@Controller
@RequiredArgsConstructor
public class UserConfigController {

    private final AppUserRepository userRepository;
    private final GoogleCalendarService calendarService;

    // Lista fixa de modelos permitidos
    private static final List<String> AVAILABLE_MODELS = List.of(
            "models/gemini-3-flash-preview",
            "models/gemini-3-pro-preview",
            "models/gemini-flash-latest",
            "models/gemini-flash-lite-latest"
    );

    @GetMapping("/user/config")
    public String showUserConfig(@RequestParam("token") String token, Model model) {
        AppUser user = userRepository.findByWebLoginToken(token)
                .orElseThrow(() -> new RuntimeException("Link inválido ou expirado. Digite /config no Telegram novamente."));

        model.addAttribute("user", user);
        model.addAttribute("availableModels", AVAILABLE_MODELS);

        // Link de autenticação sempre disponível (para conectar ou reconectar)
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
                .orElseThrow(() -> new RuntimeException("Usuário não encontrado."));

        // Atualiza a chave Gemini
        user.setGeminiApiKey(geminiApiKey.trim());

        // Atualiza o modelo preferido
        if (preferredModel != null && AVAILABLE_MODELS.contains(preferredModel)) {
            user.setPreferredModel(preferredModel);
        }

        userRepository.save(user);

        model.addAttribute("message", "✅ Configurações salvas com sucesso!");
        model.addAttribute("user", user);
        model.addAttribute("availableModels", AVAILABLE_MODELS);
        model.addAttribute("googleAuthLink", calendarService.buildAuthorizationUrl(user.getTelegramId()));

        return "user_config";
    }
}
