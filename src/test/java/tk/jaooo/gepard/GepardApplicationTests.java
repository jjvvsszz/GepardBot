package tk.jaooo.gepard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "telegrambots.enabled=false",
        "gepard.telegram.bot-token=test",
        "gepard.telegram.bot-username=test_bot"
})
class GepardApplicationTests {

    @Test
    void contextLoads() {
    }

}
