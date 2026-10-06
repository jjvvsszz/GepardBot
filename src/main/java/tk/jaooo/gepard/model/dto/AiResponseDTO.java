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
    private String searchDate;
    private String summary;
    private String location;
    private String description;
    private String startDateTime;
    private String endDateTime;
    private List<Integer> reminders;
    private String recurrence;
    private List<String> attendees;

    /** Varios eventos numa mesma mensagem (create). Quando presente, tem prioridade sobre os campos acima. */
    private List<EventExtractionDTO> events;

    /** Consulta (operation=query): periodo perguntado e se o usuario quer saber horarios livres. */
    private String queryStart;
    private String queryEnd;
    private Boolean checkFree;

    public boolean isEdit() {
        return "edit".equalsIgnoreCase(operation);
    }

    public boolean isDelete() {
        return "delete".equalsIgnoreCase(operation);
    }

    public boolean isNone() {
        return "none".equalsIgnoreCase(operation);
    }

    public boolean isQuery() {
        return "query".equalsIgnoreCase(operation);
    }

    public EventExtractionDTO toEventExtractionDTO() {
        return new EventExtractionDTO(summary, location, description, startDateTime, endDateTime,
                reminders, recurrence, attendees);
    }

    /** Todos os eventos a criar: a lista {@code events}, ou o evento descrito nos campos de topo. */
    public List<EventExtractionDTO> toEventExtractionDTOs() {
        if (events != null && !events.isEmpty()) return events;
        return List.of(toEventExtractionDTO());
    }
}
