package tk.jaooo.gepard.service;

import lombok.Getter;

/** Falha ao chamar um provedor de IA, classificada para gerar uma mensagem util ao usuario. */
@Getter
public class AiException extends RuntimeException {

    public enum Kind { INVALID_KEY, QUOTA, UNAVAILABLE, BAD_RESPONSE, OTHER }

    private final Kind kind;
    private final String provider;

    public AiException(Kind kind, String provider, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.provider = provider;
    }

    public static Kind kindFromHttpStatus(int status) {
        return switch (status) {
            case 401, 403 -> Kind.INVALID_KEY;
            case 402, 429 -> Kind.QUOTA;
            default -> status >= 500 ? Kind.UNAVAILABLE : Kind.OTHER;
        };
    }

    public String userMessage() {
        return switch (kind) {
            case INVALID_KEY -> "🔑 Sua API Key do " + provider + " foi recusada. Confira a chave em /config.";
            case QUOTA -> "⏳ Cota ou saldo da sua API Key do " + provider + " esgotado. Tente mais tarde ou troque o modelo em /config.";
            case UNAVAILABLE -> "🌐 O " + provider + " está instável no momento. Tente de novo em instantes.";
            case BAD_RESPONSE -> "🤔 Não entendi a resposta da IA. Tente reformular a mensagem.";
            case OTHER -> "❌ Falha ao consultar o " + provider + ". Tente novamente.";
        };
    }
}
