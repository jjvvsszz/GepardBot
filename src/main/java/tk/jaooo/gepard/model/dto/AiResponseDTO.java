package tk.jaooo.gepard.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiResponseDTO {

    private String operation;
    private String searchQuery;
    private String summary;
    private String location;
    private String description;
    private String startDateTime;
    private String endDateTime;
    private List<Integer> reminders;

    public boolean isEdit() {
        return "edit".equalsIgnoreCase(operation);
    }

    public boolean isDelete() {
        return "delete".equalsIgnoreCase(operation);
    }

    public EventExtractionDTO toEventExtractionDTO() {
        return new EventExtractionDTO(
                summary,
                location,
                description,
                startDateTime,
                endDateTime,
                reminders
        );
    }
}
