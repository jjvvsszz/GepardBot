package tk.jaooo.gepard.bot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GepardBotSearchTest {

    @Test
    void shouldExtractMainKeywordFromFullPhrase() {
        List<String> keywords = GepardBot.extractKeywords("almoço de terça já era");
        assertThat(keywords).containsExactly("almoço", "terça");
    }

    @Test
    void shouldRemoveArticlesAndPrepositions() {
        List<String> keywords = GepardBot.extractKeywords("a reunião do time para sexta");
        assertThat(keywords).contains("reunião", "time", "sexta");
        assertThat(keywords).doesNotContain("a", "do", "para");
    }

    @Test
    void shouldHandleSingleKeyword() {
        List<String> keywords = GepardBot.extractKeywords("almoço");
        assertThat(keywords).containsExactly("almoço");
    }

    @Test
    void shouldRemoveAuxiliaryVerbs() {
        List<String> keywords = GepardBot.extractKeywords("almoço foi cancelado já era");
        assertThat(keywords).containsExactly("almoço", "cancelado");
    }

    @Test
    void shouldHandleAllStopwordsOnly() {
        List<String> keywords = GepardBot.extractKeywords("foi já era");
        assertThat(keywords).hasSize(1);
        assertThat(keywords.get(0)).isEqualTo("foi já era");
    }

    @Test
    void shouldHandleEmptyOrWhitespace() {
        List<String> keywords = GepardBot.extractKeywords("  ");
        assertThat(keywords).hasSize(1);
        assertThat(keywords.get(0)).isEmpty();
    }

    @Test
    void shouldExtractFromDeleteScenario() {
        List<String> keywords = GepardBot.extractKeywords("almoço de terça já era, foi desmarcado");
        assertThat(keywords).contains("almoço", "terça", "desmarcado");
    }

    @Test
    void shouldExtractFromEditScenario() {
        List<String> keywords = GepardBot.extractKeywords("adiar almoço de amanhã para quinta");
        assertThat(keywords).contains("adiar", "almoço", "amanhã", "quinta");
    }

    @Test
    void shouldBeCaseInsensitive() {
        List<String> keywords = GepardBot.extractKeywords("ALMOÇO de TERÇA");
        assertThat(keywords).containsExactly("almoço", "terça");
    }

    @Test
    void shouldFilterOutSingleCharWords() {
        List<String> keywords = GepardBot.extractKeywords("a reunião é as 14h");
        assertThat(keywords).containsExactly("reunião", "14h");
    }

    @Test
    void rankingIgnoresAccentsAndDropsNonMatches() {
        com.google.api.services.calendar.model.Event almoco = new com.google.api.services.calendar.model.Event().setSummary("Almoço com time");
        com.google.api.services.calendar.model.Event dentista = new com.google.api.services.calendar.model.Event().setSummary("Dentista");
        com.google.api.services.calendar.model.Event almocoMae = new com.google.api.services.calendar.model.Event().setSummary("Almoco mãe");

        List<com.google.api.services.calendar.model.Event> ranked =
                GepardBot.rankByKeywords(List.of(almoco, dentista, almocoMae), List.of("almoco", "mae"));
        assertThat(ranked).containsExactly(almocoMae, almoco);
    }

    @Test
    void normalizeStripsDiacritics() {
        assertThat(GepardBot.normalize("Reunião ÀS Três")).isEqualTo("reuniao as tres");
        assertThat(GepardBot.normalize(null)).isEmpty();
    }

    @Test
    void errorMessagesAreSpecific() {
        assertThat(GepardBot.errorMessage(new tk.jaooo.gepard.service.AiException(
                tk.jaooo.gepard.service.AiException.Kind.QUOTA, "Gemini", "429", null))).contains("Cota");
        assertThat(GepardBot.errorMessage(new IllegalArgumentException("Data ruim."))).isEqualTo("❌ Data ruim.");
        assertThat(GepardBot.errorMessage(new RuntimeException("x"))).contains("erro inesperado");
    }

    @Test
    void escapeHtmlEscapesQuotes() {
        assertThat(BotTexts.escapeHtml("<a href=\"x\">&")).isEqualTo("&lt;a href=&quot;x&quot;&gt;&amp;");
    }

    @Test
    void parsesSummaryTimes() {
        assertThat(GepardBot.parseSummaryTime("7h")).isEqualTo(java.time.LocalTime.of(7, 0));
        assertThat(GepardBot.parseSummaryTime("06:30")).isEqualTo(java.time.LocalTime.of(6, 30));
        assertThat(GepardBot.parseSummaryTime("19h15")).isEqualTo(java.time.LocalTime.of(19, 15));
        assertThat(GepardBot.parseSummaryTime("25h")).isNull();
        assertThat(GepardBot.parseSummaryTime("cedo")).isNull();
    }

    @Test
    void parsesZonesByIdOrCity() {
        assertThat(GepardBot.parseZone("America/Manaus")).isEqualTo(java.time.ZoneId.of("America/Manaus"));
        assertThat(GepardBot.parseZone("manaus")).isEqualTo(java.time.ZoneId.of("America/Manaus"));
        assertThat(GepardBot.parseZone("Cuiabá")).isEqualTo(java.time.ZoneId.of("America/Cuiaba"));
        assertThat(GepardBot.parseZone("Lugar Nenhum")).isNull();
    }

    private static org.telegram.telegrambots.meta.api.objects.message.Message groupMessage(String text) {
        var m = new org.telegram.telegrambots.meta.api.objects.message.Message();
        m.setText(text);
        return m;
    }

    @Test
    void groupMessagesNeedMentionReplyOrCommand() {
        assertThat(GepardBot.addressedText(groupMessage("bora almoçar amanhã?"), "GepardBot")).isNull();
        assertThat(GepardBot.addressedText(groupMessage("@gepardbot reunião sexta 15h"), "GepardBot"))
                .isEqualTo("reunião sexta 15h");
        assertThat(GepardBot.addressedText(groupMessage("/eventos@GepardBot"), "GepardBot")).isEqualTo("/eventos");

        var bot = new org.telegram.telegrambots.meta.api.objects.User(1L, "Gepard", true);
        bot.setUserName("GepardBot");
        var botMessage = new org.telegram.telegrambots.meta.api.objects.message.Message();
        botMessage.setFrom(bot);
        var reply = groupMessage("às 21h");
        reply.setReplyToMessage(botMessage);
        assertThat(GepardBot.addressedText(reply, "GepardBot")).isEqualTo("às 21h");
    }

    @Test
    void mergeDraftKeepsUnchangedFieldsAndRecurrence() {
        var zone = tk.jaooo.gepard.util.EventTimes.DEFAULT_ZONE;
        var current = new tk.jaooo.gepard.model.dto.EventExtractionDTO("Academia", "Smart Fit", null,
                "2026-05-11T07:00:00-03:00", "2026-05-11T08:00:00-03:00", List.of(30),
                "RRULE:FREQ=WEEKLY;BYDAY=MO", null);
        var patch = new tk.jaooo.gepard.model.dto.EventExtractionDTO(null, null, null,
                "2026-05-11T06:30:00-03:00", null, null, null, null);

        var merged = GepardBot.mergeDraft(current, patch, zone);
        assertThat(merged.summary()).isEqualTo("Academia");
        assertThat(merged.location()).isEqualTo("Smart Fit");
        assertThat(merged.startDateTime()).isEqualTo("2026-05-11T06:30:00-03:00");
        assertThat(merged.endDateTime()).isEqualTo("2026-05-11T07:30:00-03:00");
        assertThat(merged.recurrence()).isEqualTo("RRULE:FREQ=WEEKLY;BYDAY=MO");
        assertThat(merged.reminders()).containsExactly(30);
    }
}
