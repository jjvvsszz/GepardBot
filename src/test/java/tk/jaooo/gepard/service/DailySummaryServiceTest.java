package tk.jaooo.gepard.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DailySummaryServiceTest {

    private static ZonedDateTime at(String local) {
        return LocalDateTime.parse(local).atZone(ZoneId.of("America/Sao_Paulo"));
    }

    @Test
    void dueOnlyOncePerDayAfterScheduledTime() {
        LocalTime seven = LocalTime.of(7, 0);
        assertThat(DailySummaryService.isDue(seven, null, at("2026-10-06T06:59"))).isFalse();
        assertThat(DailySummaryService.isDue(seven, null, at("2026-10-06T07:00"))).isTrue();
        assertThat(DailySummaryService.isDue(seven, LocalDate.of(2026, 10, 5), at("2026-10-06T07:01"))).isTrue();
        assertThat(DailySummaryService.isDue(seven, LocalDate.of(2026, 10, 6), at("2026-10-06T07:01"))).isFalse();
    }

    @Test
    void skipsWhenTooLate() {
        assertThat(DailySummaryService.isDue(LocalTime.of(7, 0), null, at("2026-10-06T09:30"))).isFalse();
    }
}
