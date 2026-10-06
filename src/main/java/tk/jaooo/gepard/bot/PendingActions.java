package tk.jaooo.gepard.bot;

import com.google.api.services.calendar.model.Event;
import tk.jaooo.gepard.model.dto.EventExtractionDTO;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Operacoes aguardando confirmacao. Cada uma tem um id proprio, que vai no callbackData dos botoes,
 * para que o botao de uma mensagem antiga nunca confirme a operacao de outra.
 */
class PendingActions {

    static final Duration TTL = Duration.ofHours(1);
    static final Duration UNDO_TTL = Duration.ofMinutes(2);
    static final Duration SHARE_TTL = Duration.ofDays(7);

    /** Pedido do usuario repassado a IA na etapa de alteracao (texto e, opcionalmente, a midia original). */
    record Request(String text, byte[] media, String mimeType) {}

    sealed interface Action permits Create, Edit, Delete, Select, UndoCreate, UndoEdit, UndoDelete, Share {
        long userId();
        long chatId();
    }

    /**
     * Um ou mais eventos a criar. {@code sourceMessageId}: mensagem do usuario que originou o pedido
     * (para edicoes via Telegram).
     */
    record Create(long userId, long chatId, Integer sourceMessageId, List<EventExtractionDTO> dtos) implements Action {
        EventExtractionDTO single() {
            return dtos.getFirst();
        }
    }

    record Edit(long userId, long chatId, String eventId, boolean recurring, EventExtractionDTO patch) implements Action {}

    record Delete(long userId, long chatId, String eventId, String recurringEventId, String summary) implements Action {}

    record Select(long userId, long chatId, boolean delete, Request request, List<Event> candidates) implements Action {}

    /** Desfazer: apagar os eventos recem-criados. */
    record UndoCreate(long userId, long chatId, List<String> eventIds) implements Action {}

    /** Desfazer: regravar o estado anterior do evento. */
    record UndoEdit(long userId, long chatId, Event snapshot) implements Action {}

    /** Desfazer: restaurar o evento apagado. */
    record UndoDelete(long userId, long chatId, String eventId, String summary) implements Action {}

    /** Em grupos: qualquer membro pode copiar os eventos criados para a propria agenda. */
    record Share(long userId, long chatId, List<EventExtractionDTO> dtos, Set<Long> addedBy) implements Action {}

    private static final class Entry {
        final Action action;
        final Instant expiresAt;
        volatile Integer messageId;

        Entry(Action action, Instant expiresAt, Integer messageId) {
            this.action = action;
            this.expiresAt = expiresAt;
            this.messageId = messageId;
        }

        boolean expired() {
            return expiresAt.isBefore(Instant.now());
        }
    }

    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    /** Usuario que tocou em "Ajustar" -> id da criacao a ajustar com a proxima mensagem. */
    private final Map<Long, String> awaitingAdjust = new ConcurrentHashMap<>();

    String put(Action action) {
        return put(action, TTL);
    }

    String put(Action action, Duration ttl) {
        purgeExpired();
        Instant expiresAt = Instant.now().plus(ttl);
        String id;
        do {
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 8; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            id = sb.toString();
        } while (entries.putIfAbsent(id, new Entry(action, expiresAt, null)) != null);
        return id;
    }

    /** Substitui a acao mantendo o id e a mensagem de confirmacao associada. */
    void replace(String id, Action action) {
        Entry old = entries.get(id);
        entries.put(id, new Entry(action,
                old != null ? old.expiresAt : Instant.now().plus(TTL), old != null ? old.messageId : null));
    }

    Optional<Action> get(String id) {
        Entry e = entries.get(id);
        if (e == null) return Optional.empty();
        if (e.expired()) {
            entries.remove(id);
            return Optional.empty();
        }
        return Optional.of(e.action);
    }

    void remove(String id) {
        entries.remove(id);
        awaitingAdjust.values().remove(id);
    }

    void setMessageId(String id, Integer messageId) {
        Entry e = entries.get(id);
        if (e != null) e.messageId = messageId;
    }

    Optional<Integer> getMessageId(String id) {
        Entry e = entries.get(id);
        return e == null ? Optional.empty() : Optional.ofNullable(e.messageId);
    }

    /** Criacao pendente cuja mensagem de confirmacao e {@code messageId}. */
    Optional<String> findCreateByConfirmationMessage(long userId, Integer messageId) {
        return entries.entrySet().stream()
                .filter(en -> !en.getValue().expired()
                        && en.getValue().action instanceof Create c && c.userId() == userId
                        && messageId.equals(en.getValue().messageId))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /** Criacao pendente originada pela mensagem {@code sourceMessageId} do usuario. */
    Optional<String> findCreateBySourceMessage(long userId, long chatId, Integer sourceMessageId) {
        return entries.entrySet().stream()
                .filter(en -> !en.getValue().expired()
                        && en.getValue().action instanceof Create c && c.userId() == userId
                        && c.chatId() == chatId && sourceMessageId.equals(c.sourceMessageId()))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    void awaitAdjust(long userId, String id) {
        awaitingAdjust.put(userId, id);
    }

    Optional<String> takeAwaitingAdjust(long userId) {
        return Optional.ofNullable(awaitingAdjust.remove(userId));
    }

    /** Descarta as confirmacoes pendentes do usuario (desfazer e compartilhamentos continuam valendo). */
    void clearUser(long userId) {
        entries.values().removeIf(e -> e.action.userId() == userId
                && (e.action instanceof Create || e.action instanceof Edit
                    || e.action instanceof Delete || e.action instanceof Select));
        awaitingAdjust.remove(userId);
    }

    private void purgeExpired() {
        entries.values().removeIf(Entry::expired);
        awaitingAdjust.values().removeIf(id -> !entries.containsKey(id));
    }
}
