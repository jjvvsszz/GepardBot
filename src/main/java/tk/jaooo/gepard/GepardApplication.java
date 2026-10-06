package tk.jaooo.gepard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GepardApplication {

    public static void main(String[] args) {
        SpringApplication.run(GepardApplication.class, args);
    }

}
