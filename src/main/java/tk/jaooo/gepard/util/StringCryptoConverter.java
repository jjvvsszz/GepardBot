package tk.jaooo.gepard.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Slf4j
@Component
@Converter
public class StringCryptoConverter implements AttributeConverter<String, String> {

    private static final String ALGORITHM = "AES";
    private static final String PREFIX = "{ENC}";

    private static String KEY_STRING;

    @Value("${gepard.security.encryption-key:placeholderTroqueImediatamente!!}")
    public void setKeyString(String keyString) {
        if (keyString.length() < 32) {
            log.warn("⚠️ A chave de criptografia é muito curta! Preenchendo com '#' para atingir 32 bytes.");
            keyString = String.format("%-32s", keyString).replace(' ', '#');
        } else if (keyString.length() > 32) {
            keyString = keyString.substring(0, 32);
        }
        KEY_STRING = keyString;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isBlank()) return attribute;
        if (attribute.startsWith(PREFIX)) return attribute;

        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY_STRING.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            String encrypted = Base64.getEncoder().encodeToString(cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8)));
            return PREFIX + encrypted;
        } catch (Exception e) {
            log.error("Erro ao criptografar dado sensível", e);
            throw new RuntimeException("Falha de segurança na criptografia", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return dbData;

        if (!dbData.startsWith(PREFIX)) {
            return dbData;
        }

        try {
            String dataToDecrypt = dbData.substring(PREFIX.length());
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY_STRING.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return new String(cipher.doFinal(Base64.getDecoder().decode(dataToDecrypt)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Erro ao descriptografar dado. Chave incorreta ou dados corrompidos?", e);
            return null;
        }
    }
}
