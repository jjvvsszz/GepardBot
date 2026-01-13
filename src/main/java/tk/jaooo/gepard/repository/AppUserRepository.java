package tk.jaooo.gepard.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tk.jaooo.gepard.model.AppUser;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByWebLoginToken(String webLoginToken);
}
