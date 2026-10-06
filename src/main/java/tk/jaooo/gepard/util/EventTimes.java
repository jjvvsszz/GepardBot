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
import java.util.Map;
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

    // ---------------------------------------------------------------- recorrencia

    private static final Map<String, String> WEEKDAYS = Map.of(
            "MO", "seg", "TU", "ter", "WE", "qua", "TH", "qui", "FR", "sex", "SA", "sáb", "SU", "dom");

    /**
     * Normaliza uma regra vinda da IA para "RRULE:...". Retorna null se nao parecer uma RRULE valida.
     */
    public static String normalizeRecurrence(String value) {
        if (value == null || value.isBlank()) return null;
        String s = value.trim().toUpperCase(Locale.ROOT);
        if (!s.startsWith("RRULE:")) s = "RRULE:" + s;
        if (!s.matches("RRULE:([A-Z]+=[A-Z0-9,+\\-]+;?)+") || !s.contains("FREQ=")) return null;
        return s.endsWith(";") ? s.substring(0, s.length() - 1) : s;
    }

    /** Ex: "RRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=10" -> "toda semana (seg, qua), 10 vezes". */
    public static String formatRecurrence(String rrule) {
        String normalized = normalizeRecurrence(rrule);
        if (normalized == null) return "";
        Map<String, String> parts = new java.util.HashMap<>();
        for (String kv : normalized.substring("RRULE:".length()).split(";")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2) parts.put(p[0], p[1]);
        }
        int interval = 1;
        try {
            interval = Integer.parseInt(parts.getOrDefault("INTERVAL", "1"));
        } catch (NumberFormatException ignored) { }

        String base = switch (parts.getOrDefault("FREQ", "")) {
            case "DAILY" -> interval == 1 ? "todo dia" : "a cada " + interval + " dias";
            case "WEEKLY" -> interval == 1 ? "toda semana" : "a cada " + interval + " semanas";
            case "MONTHLY" -> interval == 1 ? "todo mês" : "a cada " + interval + " meses";
            case "YEARLY" -> interval == 1 ? "todo ano" : "a cada " + interval + " anos";
            default -> "repete";
        };
        StringBuilder sb = new StringBuilder(base);
        if (parts.containsKey("BYDAY")) {
            List<String> days = new ArrayList<>();
            for (String d : parts.get("BYDAY").split(",")) {
                String code = d.replaceAll("[^A-Z]", "");
                days.add(WEEKDAYS.getOrDefault(code, d.toLowerCase(Locale.ROOT)));
            }
            sb.append(" (").append(String.join(", ", days)).append(")");
        }
        if (parts.containsKey("COUNT")) {
            sb.append(", ").append(parts.get("COUNT")).append(" vezes");
        } else if (parts.containsKey("UNTIL") && parts.get("UNTIL").length() >= 8) {
            String u = parts.get("UNTIL");
            sb.append(", até ").append(u, 6, 8).append("/").append(u, 4, 6).append("/").append(u, 0, 4);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- horarios livres

    /** Janelas livres de pelo menos {@code minimum} dentro de {@code window}, dados os intervalos ocupados. */
    public static List<TimeRange> freeSlots(TimeRange window, List<TimeRange> busy, Duration minimum) {
        List<TimeRange> sorted = busy.stream()
                .filter(b -> !b.allDay())
                .sorted(java.util.Comparator.comparing(TimeRange::start))
                .toList();
        List<TimeRange> free = new ArrayList<>();
        ZonedDateTime cursor = window.start();
        for (TimeRange b : sorted) {
            if (!b.end().isAfter(cursor)) continue;
            if (!b.start().isBefore(window.end())) break;
            if (Duration.between(cursor, b.start()).compareTo(minimum) >= 0) {
                free.add(new TimeRange(cursor, b.start(), false));
            }
            if (b.end().isAfter(cursor)) cursor = b.end();
        }
        if (Duration.between(cursor, window.end()).compareTo(minimum) >= 0) {
            free.add(new TimeRange(cursor, window.end(), false));
        }
        return free;
    }

    /** Ex: "14:00–15:30" (mesmo dia) ou "sex, 15/05 14:00 – sáb, 16/05 10:00". */
    public static String formatTimes(TimeRange r) {
        if (r.start().toLocalDate().equals(r.end().toLocalDate())
                || r.end().toLocalTime().equals(LocalTime.MIDNIGHT) && r.end().minusDays(1).toLocalDate().equals(r.start().toLocalDate())) {
            String end = r.end().toLocalTime().equals(LocalTime.MIDNIGHT) ? "24:00" : r.end().format(TIME_FMT);
            return r.start().format(TIME_FMT) + "–" + end;
        }
        return formatStart(r) + " – " + formatStart(new TimeRange(r.end(), r.end(), false));
    }

    /** Ex: "Amanhã (qua, 07/10)", "Hoje (ter, 06/10)" ou "sex, 10/10 a dom, 12/10". */
    public static String formatPeriod(TimeRange r, LocalDate today) {
        LocalDate first = r.start().toLocalDate();
        LocalDate last = r.end().toLocalTime().equals(LocalTime.MIDNIGHT) && r.end().isAfter(r.start())
                ? r.end().minusDays(1).toLocalDate() : r.end().toLocalDate();
        if (first.equals(last)) {
            String label = first.equals(today) ? "Hoje" : first.equals(today.plusDays(1)) ? "Amanhã" : null;
            boolean fullDay = r.start().toLocalTime().equals(LocalTime.MIDNIGHT)
                    && r.end().toLocalTime().equals(LocalTime.MIDNIGHT);
            String dayText = day(r.start()) + (fullDay ? "" : ", " + formatTimes(r));
            return label != null ? label + " (" + dayText + ")" : dayText;
        }
        return day(r.start()) + " a " + day(last.atStartOfDay(r.start().getZone()));
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
