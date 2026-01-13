package tk.jaooo.gepard.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tk.jaooo.gepard.model.GlobalConfig;

public interface GlobalConfigRepository extends JpaRepository<GlobalConfig, Long> {
}
