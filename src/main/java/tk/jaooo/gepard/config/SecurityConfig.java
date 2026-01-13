package tk.jaooo.gepard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/login/oauth2/code/google", "/error", "/user/config/**").permitAll()
                        .requestMatchers("/admin/**").authenticated() // Admin precisa de senha
                        .anyRequest().permitAll() // Facilita webhook do Telegram se houver
                )
                .formLogin(form -> form.defaultSuccessUrl("/admin", true)) // Login padrão do Spring
                .csrf(csrf -> csrf.ignoringRequestMatchers("/h2-console/**", "/admin/**")) // Ignora CSRF para H2 e Admin simples
                .headers(headers -> headers.frameOptions(f -> f.disable())); // Permite H2 Console

        return http.build();
    }

    // Cria um usuário admin/admin em memória para acessar o painel
    @Bean
    public InMemoryUserDetailsManager userDetailsService() {
        UserDetails user = User.withDefaultPasswordEncoder()
                .username("admin")
                .password("admin")
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(user);
    }
}
