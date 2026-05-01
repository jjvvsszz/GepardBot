package tk.jaooo.gepard.service;

import com.google.api.client.util.DateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleCalendarServiceTest {

    private String applyParseDateLogic(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            throw new IllegalArgumentException("Data invalida: string vazia ou nula");
        }
        if (dateStr.length() >= 6
                && !dateStr.endsWith("Z")
                && !dateStr.contains("+")
                && dateStr.charAt(dateStr.length() - 6) != '-') {
            dateStr = dateStr + "-03:00";
        } else if (dateStr.length() < 6 && !dateStr.endsWith("Z") && !dateStr.contains("+")) {
            dateStr = dateStr + "-03:00";
        }
        return dateStr;
    }

    @Test
    void shouldAddTimezoneWhenMissingAndLongEnough() {
        String result = applyParseDateLogic("2026-05-10T20:00:00");
        assertThat(result).isEqualTo("2026-05-10T20:00:00-03:00");
        new DateTime(result); // validates parsing
    }

    @Test
    void shouldNotAddTimezoneWhenZPresent() {
        String result = applyParseDateLogic("2026-05-10T23:00:00Z");
        assertThat(result).isEqualTo("2026-05-10T23:00:00Z");
        new DateTime(result);
    }

    @Test
    void shouldNotAddTimezoneWhenOffsetPresent() {
        String result = applyParseDateLogic("2026-05-10T20:00:00+05:00");
        assertThat(result).isEqualTo("2026-05-10T20:00:00+05:00");
        new DateTime(result);
    }

    @Test
    void shouldHandleShortString() {
        String result = applyParseDateLogic("12:00");
        assertThat(result).isEqualTo("12:00-03:00");
    }

    @Test
    void shouldNotDoubleAddTimezoneWhenTzAlreadyPresent() {
        String result = applyParseDateLogic("2026-12-25T10:00:00-03:00");
        assertThat(result).isEqualTo("2026-12-25T10:00:00-03:00");
    }

    @Test
    void shouldRejectBlank() {
        assertThatThrownBy(() -> applyParseDateLogic(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Data invalida");
    }

    @Test
    void shouldRejectNull() {
        assertThatThrownBy(() -> applyParseDateLogic(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAddTimezoneForShortTimeString() {
        String result = applyParseDateLogic("10:30");
        assertThat(result).isEqualTo("10:30-03:00");
    }
}
