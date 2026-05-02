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
}
