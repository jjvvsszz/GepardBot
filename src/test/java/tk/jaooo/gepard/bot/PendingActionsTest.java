package tk.jaooo.gepard.bot;

import org.junit.jupiter.api.Test;
import tk.jaooo.gepard.model.dto.EventExtractionDTO;

import static org.assertj.core.api.Assertions.assertThat;

class PendingActionsTest {

    private static PendingActions.Create create(long user, int sourceMsg, String summary) {
        return new PendingActions.Create(user, 10L, sourceMsg,
                new EventExtractionDTO(summary, null, null, "2026-05-10T10:00:00-03:00", null, null));
    }

    @Test
    void eachActionHasItsOwnId() {
        PendingActions pending = new PendingActions();
        String first = pending.put(create(1, 100, "Dentista"));
        String second = pending.put(create(1, 101, "Reuniao"));

        assertThat(first).isNotEqualTo(second);
        // confirmar a primeira nao pode usar os dados da segunda
        assertThat(((PendingActions.Create) pending.get(first).orElseThrow()).dto().summary()).isEqualTo("Dentista");
        assertThat(((PendingActions.Create) pending.get(second).orElseThrow()).dto().summary()).isEqualTo("Reuniao");
    }

    @Test
    void findsDraftByConfirmationAndSourceMessage() {
        PendingActions pending = new PendingActions();
        String id = pending.put(create(1, 100, "Dentista"));
        pending.setMessageId(id, 555);

        assertThat(pending.findCreateByConfirmationMessage(1, 555)).contains(id);
        assertThat(pending.findCreateByConfirmationMessage(2, 555)).isEmpty();
        assertThat(pending.findCreateBySourceMessage(1, 10L, 100)).contains(id);
        assertThat(pending.findCreateBySourceMessage(1, 11L, 100)).isEmpty();
    }

    @Test
    void replaceKeepsConfirmationMessage() {
        PendingActions pending = new PendingActions();
        String id = pending.put(create(1, 100, "Dentista"));
        pending.setMessageId(id, 555);
        pending.replace(id, create(1, 100, "Dentista 15h"));

        assertThat(pending.getMessageId(id)).contains(555);
        assertThat(((PendingActions.Create) pending.get(id).orElseThrow()).dto().summary()).isEqualTo("Dentista 15h");
    }

    @Test
    void adjustTargetIsConsumedAndClearedWithUser() {
        PendingActions pending = new PendingActions();
        String id = pending.put(create(1, 100, "Dentista"));
        pending.awaitAdjust(1, id);

        assertThat(pending.takeAwaitingAdjust(1)).contains(id);
        assertThat(pending.takeAwaitingAdjust(1)).isEmpty();

        pending.awaitAdjust(1, id);
        pending.clearUser(1);
        assertThat(pending.get(id)).isEmpty();
        assertThat(pending.takeAwaitingAdjust(1)).isEmpty();
    }
}
