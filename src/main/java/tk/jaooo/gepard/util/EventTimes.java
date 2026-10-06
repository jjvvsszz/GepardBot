package tk.jaooo.gepard.util;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.EventDateTime;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Conversao e formatacao de datas de eventos.
 * Eventos de dia inteiro sao representados com inicio a meia-noite e fim exclusivo (como no Google Agenda).
 */
public final class EventTimes {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Sao_Paulo");
    public static final Locale PT_BR = Locale.of("pt", "BR");

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("dd/MM", PT_BR);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", PT_BR);

    private EventTimes() {}

    public record TimeRange(ZonedDateTime start, ZonedDateTime end, boolean allDay) {
        public Duration duration() {
            return Duration.between(start, end);
        }
    }

    /** Resultado do parse de uma data vinda da IA: data com hora, ou so a data (dia inteiro). */
    private record Parsed(ZonedDateTime dateTime, LocalDate date) {
        boolean isDateOnly() { return date != null; }
    }

    private static Parsed parse(String value, ZoneId zone) {
        String s = value.trim();
        try {
            return new Parsed(OffsetDateTime.parse(s).atZoneSameInstant(zone), null);
        } catch (DateTimeParseException ignored) { }
        try {
            return new Parsed(LocalDateTime.parse(s).atZone(zone), null);
        } catch (DateTimeParseException ignored) { }
        try {
            return new Parsed(null, LocalDate.parse(s));
        } catch (DateTimeParseException ignored) { }
        throw new IllegalArgumentException("Não entendi a data \"" + value + "\".");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Monta o intervalo de um novo evento. Para dia inteiro, {@code end} (se houver) e o ultimo dia, inclusive.
     * Sem fim: 1h para eventos com horario, 1 dia para dia inteiro.
     */
    public static TimeRange fromAi(String start, String end, ZoneId zone) {
        if (isBlank(start)) {
            throw new IllegalArgumentException("Não consegui identificar a data de início.");
        }
        Parsed s = parse(start, zone);
        Parsed e = isBlank(end) ? null : parse(end, zone);

        if (s.isDateOnly()) {
            ZonedDateTime startDay = s.date().atStartOfDay(zone);
            LocalDate lastDay = s.date();
            if (e != null) {
                LocalDate endDate = e.isDateOnly() ? e.date() : e.dateTime().toLocalDate();
                if (!endDate.isBefore(lastDay)) lastDay = endDate;
            }
            return new TimeRange(startDay, lastDay.plusDays(1).atStartOfDay(zone), true);
        }

        ZonedDateTime startDt = s.dateTime();
        ZonedDateTime endDt = e == null ? null
                : e.isDateOnly() ? null : e.dateTime();
        if (endDt == null || !endDt.isAfter(startDt)) {
            endDt = startDt.plusHours(1);
        }
        return new TimeRange(startDt, endDt, false);
    }

    /** Le o intervalo de um evento do Google Agenda. */
    public static TimeRange fromEvent(EventDateTime start, EventDateTime end, ZoneId zone) {
        ZonedDateTime s = toZoned(start, zone);
        ZonedDateTime e = end != null ? toZoned(end, zone) : null;
        boolean allDay = start.getDateTime() == null && start.getDate() != null;
        if (e == null || !e.isAfter(s)) {
            e = allDay ? s.plusDays(1) : s.plusHours(1);
        }
        return new TimeRange(s, e, allDay);
    }

    private static ZonedDateTime toZoned(EventDateTime edt, ZoneId zone) {
        if (edt.getDateTime() != null) {
            return Instant.ofEpochMilli(edt.getDateTime().getValue()).atZone(zone);
        }
        if (edt.getDate() != null) {
            // Datas "dia inteiro" sao armazenadas como meia-noite UTC; usar a string evita voltar um dia.
            return LocalDate.parse(edt.getDate().toStringRfc3339().substring(0, 10)).atStartOfDay(zone);
        }
        throw new IllegalArgumentException("Evento sem data.");
    }

    /**
     * Aplica uma alteracao parcial (campos nulos permanecem) a um intervalo existente.
     * Mudando so o inicio, a duracao original e preservada.
     */
    public static TimeRange applyPatch(TimeRange current, String newStart, String newEnd, ZoneId zone) {
        if (isBlank(newStart) && isBlank(newEnd)) return current;

        if (isBlank(newStart)) {
            Parsed e = parse(newEnd, zone);
            if (current.allDay()) {
                LocalDate last = e.isDateOnly() ? e.date() : e.dateTime().toLocalDate();
                ZonedDateTime end = last.plusDays(1).atStartOfDay(zone);
                return end.isAfter(current.start()) ? new TimeRange(current.start(), end, true) : current;
            }
            ZonedDateTime end;
            if (e.isDateOnly()) {
                end = e.date().atTime(current.end().toLocalTime()).atZone(zone);
            } else {
                end = e.dateTime();
            }
            return end.isAfter(current.start()) ? new TimeRange(current.start(), end, false) : current;
        }

        Parsed s = parse(newStart, zone);
        if (!isBlank(newEnd)) {
            return fromAi(newStart, newEnd, zone);
        }
        if (s.isDateOnly()) {
            if (current.allDay()) {
                ZonedDateTime start = s.date().atStartOfDay(zone);
                return new TimeRange(start, start.plus(current.duration()), true);
            }
            // Mudou so o dia de um evento com horario: mantem o horario e a duracao.
            ZonedDateTime start = s.date().atTime(current.start().toLocalTime()).atZone(zone);
            return new TimeRange(start, start.plus(current.duration()), false);
        }
        Duration duration = current.allDay() ? Duration.ofHours(1) : current.duration();
        return new TimeRange(s.dateTime(), s.dateTime().plus(duration), false);
    }

    /** Inverso de {@link #fromAi}: {inicio, fim} no formato esperado da IA (fim inclusive para dia inteiro). */
    public static String[] toAiStrings(TimeRange r) {
        if (r.allDay()) {
            return new String[]{r.start().toLocalDate().toString(), r.end().minusDays(1).toLocalDate().toString()};
        }
        return new String[]{
                r.start().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                r.end().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)};
    }

    public static EventDateTime toEventDateTime(ZonedDateTime value, boolean allDay, ZoneId zone) {
        if (allDay) {
            return new EventDateTime().setDate(new DateTime(value.toLocalDate().toString()));
        }
        return new EventDateTime()
                .setDateTime(new DateTime(Date.from(value.toInstant()), TimeZone.getTimeZone(zone)))
                .setTimeZone(zone.getId());
    }

    // ---------------------------------------------------------------- formatacao

    private static String weekday(ZonedDateTime dt) {
        return dt.getDayOfWeek().getDisplayName(TextStyle.SHORT, PT_BR).replace(".", "");
    }

    private static String day(ZonedDateTime dt) {
        return weekday(dt) + ", " + dt.format(DAY_FMT);
    }

    /** Ex: "sex, 10/05 às 20:00–21:00 (1h)", "sáb, 11/05 (dia inteiro)". */
    public static String format(TimeRange r) {
        if (r.allDay()) {
            ZonedDateTime lastDay = r.end().minusDays(1);
            if (!lastDay.toLocalDate().isAfter(r.start().toLocalDate())) {
                return day(r.start()) + " (dia inteiro)";
            }
            return day(r.start()) + " a " + day(lastDay) + " (dia inteiro)";
        }
        String duration = " (" + formatDuration(r.duration()) + ")";
        if (r.start().toLocalDate().equals(r.end().toLocalDate())) {
            return day(r.start()) + " às " + r.start().format(TIME_FMT) + "–" + r.end().format(TIME_FMT) + duration;
        }
        return day(r.start()) + " " + r.start().format(TIME_FMT)
                + " a " + day(r.end()) + " " + r.end().format(TIME_FMT) + duration;
    }

    /** Apenas o inicio, para listas. Ex: "sex, 10/05 20:00" ou "sex, 10/05 (dia inteiro)". */
    public static String formatStart(TimeRange r) {
        return r.allDay() ? day(r.start()) + " (dia inteiro)" : day(r.start()) + " " + r.start().format(TIME_FMT);
    }

    public static String formatDuration(Duration d) {
        long minutes = d.toMinutes();
        long h = minutes / 60;
        long m = minutes % 60;
        if (h == 0) return m + "min";
        if (m == 0) return h + "h";
        return h + "h" + String.format("%02d", m);
    }

    /** Ex: [30, 1440] -> "30 min e 1 dia antes". */
    public static String formatReminders(List<Integer> reminders) {
        if (reminders == null || reminders.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        reminders.stream().distinct().sorted().forEach(min -> parts.add(formatReminder(min)));
        String joined = parts.size() == 1 ? parts.getFirst()
                : String.join(", ", parts.subList(0, parts.size() - 1)) + " e " + parts.getLast();
        return joined + " antes";
    }

    private static String formatReminder(int minutes) {
        if (minutes == 0) return "na hora";
        if (minutes % 10080 == 0) return plural(minutes / 10080, "semana", "semanas");
        if (minutes % 1440 == 0) return plural(minutes / 1440, "dia", "dias");
        if (minutes % 60 == 0) return (minutes / 60) + "h";
        return minutes + " min";
    }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /** Contexto temporal enviado a IA, com o dia da semana por extenso (LLMs erram ao calcula-lo). */
    public static String promptContext(ZonedDateTime now) {
        String weekday = now.getDayOfWeek().getDisplayName(TextStyle.FULL, PT_BR);
        return "Agora: %s, %s (fuso %s, UTC%s). ISO: %s.".formatted(
                weekday,
                now.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                now.getZone().getId(),
                now.getOffset().getId().equals("Z") ? "+00:00" : now.getOffset().getId(),
                now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }
}
