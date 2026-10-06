package tk.jaooo.gepard.util;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.EventDateTime;
import org.junit.jupiter.api.Test;
import tk.jaooo.gepard.util.EventTimes.TimeRange;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventTimesTest {

    private static final ZoneId SP = EventTimes.DEFAULT_ZONE;

    private static ZonedDateTime sp(String local) {
        return java.time.LocalDateTime.parse(local).atZone(SP);
    }

    @Test
    void fromAiDefaultsToOneHour() {
        TimeRange r = EventTimes.fromAi("2026-05-10T20:00:00-03:00", null, SP);
        assertThat(r.allDay()).isFalse();
        assertThat(r.start()).isEqualTo(sp("2026-05-10T20:00"));
        assertThat(r.duration()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void fromAiAcceptsMissingOffsetAndUtc() {
        assertThat(EventTimes.fromAi("2026-05-10T20:00:00", null, SP).start()).isEqualTo(sp("2026-05-10T20:00"));
        assertThat(EventTimes.fromAi("2026-05-10T23:00:00Z", null, SP).start()).isEqualTo(sp("2026-05-10T20:00"));
        assertThat(EventTimes.fromAi("2026-05-10T20:00", null, SP).start()).isEqualTo(sp("2026-05-10T20:00"));
    }

    @Test
    void fromAiIgnoresEndBeforeStart() {
        TimeRange r = EventTimes.fromAi("2026-05-10T20:00:00-03:00", "2026-05-10T19:00:00-03:00", SP);
        assertThat(r.end()).isEqualTo(sp("2026-05-10T21:00"));
    }

    @Test
    void fromAiDateOnlyIsAllDayWithExclusiveEnd() {
        TimeRange single = EventTimes.fromAi("2026-05-10", null, SP);
        assertThat(single.allDay()).isTrue();
        assertThat(single.end().toLocalDate()).isEqualTo(LocalDate.of(2026, 5, 11));

        TimeRange trip = EventTimes.fromAi("2026-05-10", "2026-05-12", SP);
        assertThat(trip.end().toLocalDate()).isEqualTo(LocalDate.of(2026, 5, 13));
        assertThat(EventTimes.toAiStrings(trip)).containsExactly("2026-05-10", "2026-05-12");
    }

    @Test
    void fromAiRejectsGarbage() {
        assertThatThrownBy(() -> EventTimes.fromAi("amanha", null, SP)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventTimes.fromAi(" ", null, SP)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromEventAllDayDoesNotShiftToPreviousDay() {
        EventDateTime start = new EventDateTime().setDate(new DateTime("2026-05-10"));
        EventDateTime end = new EventDateTime().setDate(new DateTime("2026-05-11"));
        TimeRange r = EventTimes.fromEvent(start, end, SP);
        assertThat(r.allDay()).isTrue();
        assertThat(r.start().toLocalDate()).isEqualTo(LocalDate.of(2026, 5, 10));
        assertThat(EventTimes.format(r)).isEqualTo("dom, 10/05 (dia inteiro)");
    }

    @Test
    void patchOnlyDayKeepsTimeAndDuration() {
        TimeRange lunch = new TimeRange(sp("2026-05-12T12:00"), sp("2026-05-12T13:30"), false);
        TimeRange moved = EventTimes.applyPatch(lunch, "2026-05-14", null, SP);
        assertThat(moved.start()).isEqualTo(sp("2026-05-14T12:00"));
        assertThat(moved.duration()).isEqualTo(Duration.ofMinutes(90));
    }

    @Test
    void patchStartKeepsDuration() {
        TimeRange meeting = new TimeRange(sp("2026-05-12T10:00"), sp("2026-05-12T12:00"), false);
        TimeRange moved = EventTimes.applyPatch(meeting, "2026-05-12T15:00:00-03:00", null, SP);
        assertThat(moved.start()).isEqualTo(sp("2026-05-12T15:00"));
        assertThat(moved.end()).isEqualTo(sp("2026-05-12T17:00"));
    }

    @Test
    void patchOnlyEndChangesDuration() {
        TimeRange meeting = new TimeRange(sp("2026-05-12T10:00"), sp("2026-05-12T11:00"), false);
        TimeRange longer = EventTimes.applyPatch(meeting, null, "2026-05-12T12:30:00-03:00", SP);
        assertThat(longer.start()).isEqualTo(meeting.start());
        assertThat(longer.end()).isEqualTo(sp("2026-05-12T12:30"));
    }

    @Test
    void patchWithNothingReturnsSameRange() {
        TimeRange meeting = new TimeRange(sp("2026-05-12T10:00"), sp("2026-05-12T11:00"), false);
        assertThat(EventTimes.applyPatch(meeting, null, " ", SP)).isEqualTo(meeting);
    }

    @Test
    void patchAllDayToTimedUsesOneHour() {
        TimeRange birthday = EventTimes.fromAi("2026-05-10", null, SP);
        TimeRange timed = EventTimes.applyPatch(birthday, "2026-05-10T19:00:00-03:00", null, SP);
        assertThat(timed.allDay()).isFalse();
        assertThat(timed.duration()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void toEventDateTimeRoundTrips() {
        TimeRange r = new TimeRange(sp("2026-05-12T10:00"), sp("2026-05-12T11:00"), false);
        EventDateTime s = EventTimes.toEventDateTime(r.start(), false, SP);
        EventDateTime e = EventTimes.toEventDateTime(r.end(), false, SP);
        assertThat(s.getTimeZone()).isEqualTo("America/Sao_Paulo");
        assertThat(EventTimes.fromEvent(s, e, SP)).isEqualTo(r);

        TimeRange allDay = EventTimes.fromAi("2026-05-10", null, SP);
        EventDateTime as = EventTimes.toEventDateTime(allDay.start(), true, SP);
        assertThat(as.getDate().toStringRfc3339()).isEqualTo("2026-05-10");
        assertThat(as.getDateTime()).isNull();
    }

    @Test
    void formatsInPortuguese() {
        TimeRange r = new TimeRange(sp("2026-05-15T20:00"), sp("2026-05-15T21:30"), false);
        assertThat(EventTimes.format(r)).isEqualTo("sex, 15/05 às 20:00–21:30 (1h30)");
        assertThat(EventTimes.formatStart(r)).isEqualTo("sex, 15/05 20:00");

        TimeRange trip = EventTimes.fromAi("2026-05-15", "2026-05-17", SP);
        assertThat(EventTimes.format(trip)).isEqualTo("sex, 15/05 a dom, 17/05 (dia inteiro)");
    }

    @Test
    void formatsReminders() {
        assertThat(EventTimes.formatReminders(List.of(30))).isEqualTo("30 min antes");
        assertThat(EventTimes.formatReminders(List.of(1440, 30))).isEqualTo("30 min e 1 dia antes");
        assertThat(EventTimes.formatReminders(List.of(60, 2880, 10080))).isEqualTo("1h, 2 dias e 1 semana antes");
        assertThat(EventTimes.formatReminders(List.of())).isEmpty();
    }

    @Test
    void promptContextIncludesWeekday() {
        String ctx = EventTimes.promptContext(sp("2026-10-06T19:30"));
        assertThat(ctx).contains("terça-feira").contains("06/10/2026 19:30").contains("UTC-03:00");
    }
}
