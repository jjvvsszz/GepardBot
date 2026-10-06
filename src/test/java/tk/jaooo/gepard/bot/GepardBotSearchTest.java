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
        assertThat(GepardBot.escapeHtml("<a href=\"x\">&")).isEqualTo("&lt;a href=&quot;x&quot;&gt;&amp;");
    }
}
