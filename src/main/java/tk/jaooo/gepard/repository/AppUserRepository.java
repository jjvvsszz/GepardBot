package tk.jaooo.gepard.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import tk.jaooo.gepard.model.AppUser;
import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByWebLoginToken(String webLoginToken);

    @Query(value = """
        SELECT * FROM app_users u
        WHERE (u.gemini_api_key IS NOT NULL AND u.gemini_api_key NOT LIKE '{ENC}%')
           OR (u.google_refresh_token IS NOT NULL AND u.google_refresh_token NOT LIKE '{ENC}%')
           OR (u.google_access_token IS NOT NULL AND u.google_access_token NOT LIKE '{ENC}%')
    """, nativeQuery = true)
    List<AppUser> findUsersWithUnencryptedData();
}
