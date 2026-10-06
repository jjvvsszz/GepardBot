package tk.jaooo.gepard.model.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiResponseDTOTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldDetectCreateAsDefault() throws Exception {
        String json = """
                {"summary":"Almoco","startDateTime":"2026-05-10T12:00:00-03:00"}""";
        AiResponseDTO dto = mapper.readValue(json, AiResponseDTO.class);
        assertThat(dto.isEdit()).isFalse();
        assertThat(dto.isDelete()).isFalse();
        assertThat(dto.getSummary()).isEqualTo("Almoco");
    }

    @Test
    void shouldDetectEditOperation() throws Exception {
        String json = """
                {"operation":"edit","searchQuery":"almoco","summary":"Almoco","startDateTime":"2026-05-10T14:00:00-03:00"}""";
        AiResponseDTO dto = mapper.readValue(json, AiResponseDTO.class);
        assertThat(dto.isEdit()).isTrue();
        assertThat(dto.isDelete()).isFalse();
        assertThat(dto.getSearchQuery()).isEqualTo("almoco");
    }

    @Test
    void shouldDetectDeleteOperation() throws Exception {
        String json = """
                {"operation":"delete","searchQuery":"almoco","summary":"Almoco","startDateTime":"2026-05-10T12:00:00-03:00"}""";
        AiResponseDTO dto = mapper.readValue(json, AiResponseDTO.class);
        assertThat(dto.isDelete()).isTrue();
        assertThat(dto.isEdit()).isFalse();
    }

    @Test
    void shouldBeCaseInsensitiveForOperation() throws Exception {
        String json = """
                {"operation":"EDIT","searchQuery":"reuniao","summary":"Reuniao","startDateTime":"2026-05-10T12:00:00-03:00"}""";
        AiResponseDTO dto = mapper.readValue(json, AiResponseDTO.class);
        assertThat(dto.isEdit()).isTrue();
        assertThat(dto.isDelete()).isFalse();
    }

    @Test
    void shouldConvertToEventExtractionDTO() throws Exception {
        String json = """
                {"operation":"edit","searchQuery":"almoco","summary":"Almoco Editado","location":"Sala B","description":"Com time","startDateTime":"2026-05-10T15:00:00-03:00","endDateTime":"2026-05-10T16:00:00-03:00","reminders":[15]}""";
        AiResponseDTO dto = mapper.readValue(json, AiResponseDTO.class);
        EventExtractionDTO eventDTO = dto.toEventExtractionDTO();

        assertThat(eventDTO.summary()).isEqualTo("Almoco Editado");
        assertThat(eventDTO.location()).isEqualTo("Sala B");
        assertThat(eventDTO.description()).isEqualTo("Com time");
        assertThat(eventDTO.startDateTime()).isEqualTo("2026-05-10T15:00:00-03:00");
        assertThat(eventDTO.endDateTime()).isEqualTo("2026-05-10T16:00:00-03:00");
        assertThat(eventDTO.reminders()).containsExactly(15);
    }

    @Test
    void shouldHandleUnknownProperties() throws Exception {
        String json = """
                {"operation":"create","summary":"Teste","startDateTime":"2026-05-10T12:00:00-03:00","extraField":"shouldBeIgnored"}""";
        AiResponseDTO dto = mapper.readValue(json, AiResponseDTO.class);
        assertThat(dto.getSummary()).isEqualTo("Teste");
        assertThat(dto.isEdit()).isFalse();
        assertThat(dto.isDelete()).isFalse();
    }

    @Test
    void shouldHandleAllFieldsPresent() throws Exception {
        String json = """
                {"operation":"create","searchQuery":null,"summary":"Evento Completo","location":"Sala A","description":"Descricao do evento","startDateTime":"2026-05-10T10:00:00-03:00","endDateTime":"2026-05-10T11:00:00-03:00","reminders":[30,60]}""";
        AiResponseDTO dto = mapper.readValue(json, AiResponseDTO.class);
        assertThat(dto.getSummary()).isEqualTo("Evento Completo");
        assertThat(dto.getLocation()).isEqualTo("Sala A");
        assertThat(dto.getReminders()).containsExactly(30, 60);
    }

    @Test
    void shouldConvertDeleteToEventExtractionDTO() throws Exception {
        String json = """
                {"operation":"delete","searchQuery":"cafe"}""";
        AiResponseDTO dto = mapper.readValue(json, AiResponseDTO.class);
        assertThat(dto.isDelete()).isTrue();
        assertThat(dto.getSearchQuery()).isEqualTo("cafe");

        EventExtractionDTO eventDTO = dto.toEventExtractionDTO();
        assertThat(eventDTO.summary()).isNull();
        assertThat(eventDTO.startDateTime()).isNull();
    }

    @Test
    void shouldReadMultipleEventsAndQueryFields() throws Exception {
        String json = """
                {"operation":"create","events":[
                  {"summary":"Dentista","startDateTime":"2026-05-11T10:00:00-03:00"},
                  {"summary":"Reuniao","startDateTime":"2026-05-13T15:00:00-03:00","attendees":["ana@x.com"],
                   "recurrence":"RRULE:FREQ=WEEKLY"}]}""";
        AiResponseDTO dto = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, AiResponseDTO.class);
        assertThat(dto.toEventExtractionDTOs()).hasSize(2);
        assertThat(dto.toEventExtractionDTOs().get(1).attendees()).containsExactly("ana@x.com");
        assertThat(dto.toEventExtractionDTOs().get(1).recurrence()).isEqualTo("RRULE:FREQ=WEEKLY");

        AiResponseDTO single = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue("{\"summary\":\"X\",\"startDateTime\":\"2026-05-11\"}", AiResponseDTO.class);
        assertThat(single.toEventExtractionDTOs()).extracting(EventExtractionDTO::summary).containsExactly("X");

        AiResponseDTO query = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue("{\"operation\":\"query\",\"queryStart\":\"2026-05-11T12:00:00-03:00\",\"checkFree\":true}", AiResponseDTO.class);
        assertThat(query.isQuery()).isTrue();
        assertThat(query.getCheckFree()).isTrue();
    }
}
