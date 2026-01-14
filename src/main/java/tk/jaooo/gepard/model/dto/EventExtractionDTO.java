package tk.jaooo.gepard.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventExtractionDTO(
        String summary,
        String location,
        String description,
        String startDateTime,
        String endDateTime,
        List<Integer> reminders
) {}
