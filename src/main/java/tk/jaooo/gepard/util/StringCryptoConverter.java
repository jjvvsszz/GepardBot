package tk.jaooo.gepard.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Component
@Converter
public class StringCryptoConverter implements AttributeConverter<String, String> {

    private static final String ALGORITHM_GCM = "AES/GCM/NoPadding";
    private static final String ALGORITHM_ECB = "AES";
    private static final String KEY_ALGORITHM = "AES";
    private static final String PREFIX = "{ENC}";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private static String KEY_STRING;

    @Value("${gepard.security.encryption-key:placeholderTroqueImediatamente!!}")
    public void setKeyString(String keyString) {
        if (keyString.length() < 32) {
            log.warn("Chave de criptografia curta. Preenchendo com '#' para atingir 32 bytes.");
            keyString = String.format("%-32s", keyString).replace(' ', '#');
        } else if (keyString.length() > 32) {
            keyString = keyString.substring(0, 32);
        }
        KEY_STRING = keyString;
    }

    private static SecretKeySpec getKeySpec() {
        return new SecretKeySpec(KEY_STRING.getBytes(StandardCharsets.UTF_8), KEY_ALGORITHM);
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isBlank()) return attribute;
        if (attribute.startsWith(PREFIX)) return attribute;

        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, getKeySpec(), new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[GCM_IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, GCM_IV_LENGTH, ciphertext.length);

            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Erro ao criptografar dado sensivel", e);
            throw new RuntimeException("Falha de seguranca na criptografia", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return dbData;
        if (!dbData.startsWith(PREFIX)) return dbData;

        String base64Data = dbData.substring(PREFIX.length());

        // Try GCM first (new format)
        String result = tryDecryptGcm(base64Data);
        if (result != null) return result;

        // Fallback to ECB (old format from before migration)
        result = tryDecryptEcb(base64Data);
        if (result != null) {
            log.info("Dado descriptografado com ECB legado. Sera re-criptografado com GCM no proximo save.");
            return result;
        }

        log.error("Falha ao descriptografar dado com GCM e ECB. Chave incorreta ou dados corrompidos.");
        return null;
    }

    private String tryDecryptGcm(String base64Data) {
        try {
            byte[] combined = Base64.getDecoder().decode(base64Data);

            if (combined.length < GCM_IV_LENGTH + 1) return null;

            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM_GCM);
            cipher.init(Cipher.DECRYPT_MODE, getKeySpec(), new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private String tryDecryptEcb(String base64Data) {
        try {
            byte[] ciphertext = Base64.getDecoder().decode(base64Data);

            Cipher cipher = Cipher.getInstance(ALGORITHM_ECB);
            cipher.init(Cipher.DECRYPT_MODE, getKeySpec());

            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
