package tk.jaooo.gepard.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;
import tk.jaooo.gepard.model.AppUser;
import tk.jaooo.gepard.repository.AppUserRepository;
import tk.jaooo.gepard.service.GoogleCalendarService;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final GoogleCalendarService calendarService;
    private final AppUserRepository userRepository;

    @GetMapping("/")
    public String home() {
        return "🤖 Gepard Bot está ONLINE!";
    }

    @GetMapping("/login/oauth2/code/google")
    public RedirectView handleGoogleCallback(
            @RequestParam("code") String code,
            @RequestParam("state") String state) {
        try {
            Long telegramId = Long.parseLong(state);

            // 1. Troca o código pelos tokens do Google
            calendarService.exchangeCodeForTokens(code, telegramId);

            // 2. Recupera o usuário atualizado
            AppUser user = userRepository.findById(telegramId)
                    .orElseThrow(() -> new RuntimeException("Usuário não encontrado pós-auth"));

            // 3. CORREÇÃO: Garante que existe um token de login web
            if (user.getWebLoginToken() == null || user.getWebLoginToken().isBlank() || user.isWebTokenExpired()) {
                String newToken = UUID.randomUUID().toString();
                user.setWebLoginToken(newToken);
                user.setWebLoginTokenExpiresAt(LocalDateTime.now().plusHours(24));
                userRepository.save(user);
            }

            // 4. Redireciona para o painel do usuário
            return new RedirectView("/user/config?token=" + user.getWebLoginToken());

        } catch (Exception e) {
            log.error("Erro no callback OAuth", e);
            // Em caso de erro, redireciona para uma página de erro ou home
            return new RedirectView("/error?msg=" + java.net.URLEncoder.encode(e.getMessage(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
