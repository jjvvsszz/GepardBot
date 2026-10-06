package tk.jaooo.gepard.bot;

import com.google.api.services.calendar.model.Event;
import tk.jaooo.gepard.util.EventTimes;
import tk.jaooo.gepard.util.EventTimes.TimeRange;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** Textos compartilhados entre o bot e o resumo diario. */
public final class BotTexts {

    private BotTexts() {}

    public static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public static String summaryOf(Event event) {
        return event.getSummary() != null && !event.getSummary().isBlank() ? event.getSummary() : "(sem título)";
    }

    public static TimeRange rangeOf(Event event, ZoneId zone) {
        return EventTimes.fromEvent(event.getStart(), event.getEnd(), zone);
    }

    /**
     * Lista de eventos em HTML, agrupada por dia quando houver mais de um.
     * Ex: "• 14:00–15:00 <b>Dentista</b> · 📍 Centro".
     */
    public static String agenda(List<Event> events, ZoneId zone) {
        StringBuilder sb = new StringBuilder();
        boolean multiDay = events.stream()
                .map(e -> rangeOf(e, zone).start().toLocalDate())
                .distinct().count() > 1;
        LocalDate currentDay = null;
        for (Event e : events) {
            TimeRange r = rangeOf(e, zone);
            LocalDate day = r.start().toLocalDate();
            if (multiDay && !day.equals(currentDay)) {
                if (currentDay != null) sb.append("\n");
                sb.append("<b>").append(EventTimes.formatStart(new TimeRange(r.start(), r.start(), true))
                        .replace(" (dia inteiro)", "")).append("</b>\n");
                currentDay = day;
            }
            sb.append("• ").append(r.allDay() ? "dia inteiro" : EventTimes.formatTimes(r))
                    .append(" <b>").append(escapeHtml(summaryOf(e))).append("</b>");
            if (e.getRecurringEventId() != null) sb.append(" 🔁");
            if (e.getLocation() != null && !e.getLocation().isBlank()) {
                sb.append(" · 📍 ").append(escapeHtml(truncate(e.getLocation(), 40)));
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
