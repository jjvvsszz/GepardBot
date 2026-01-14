package tk.jaooo.gepard.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import tk.jaooo.gepard.model.GlobalConfig;
import tk.jaooo.gepard.repository.GlobalConfigRepository;

@Service
@RequiredArgsConstructor
public class CustomAdminDetailsService implements UserDetailsService {

    private final GlobalConfigRepository repository;

    @NotNull
    @Override
    public UserDetails loadUserByUsername(@NotNull String username) throws UsernameNotFoundException {
        GlobalConfig config = repository.findById(1L)
                .orElseThrow(() -> new UsernameNotFoundException("Admin não configurado"));

        if (!config.getAdminUsername().equals(username)) {
            throw new UsernameNotFoundException("Usuário incorreto");
        }

        return User.builder()
                .username(config.getAdminUsername())
                .password(config.getAdminPasswordHash())
                .roles("ADMIN")
                .build();
    }
}