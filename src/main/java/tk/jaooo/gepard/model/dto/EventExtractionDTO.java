package tk.jaooo.gepard.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Dados de um evento extraidos pela IA.
 * {@code recurrence}: regra RRULE (ex: "RRULE:FREQ=WEEKLY;BYDAY=MO"); {@code attendees}: e-mails de convidados.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventExtractionDTO(
        String summary,
        String location,
        String description,
        String startDateTime,
        String endDateTime,
        List<Integer> reminders,
        String recurrence,
        List<String> attendees
) {
    public EventExtractionDTO(String summary, String location, String description,
                              String startDateTime, String endDateTime, List<Integer> reminders) {
        this(summary, location, description, startDateTime, endDateTime, reminders, null, null);
    }

    public EventExtractionDTO withTimes(String start, String end) {
        return new EventExtractionDTO(summary, location, description, start, end, reminders, recurrence, attendees);
    }
}
