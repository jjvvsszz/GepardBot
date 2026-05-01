package tk.jaooo.gepard.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class StringCryptoConverterTest {

    private StringCryptoConverter converter;

    @BeforeEach
    void setUp() {
        converter = new StringCryptoConverter();
        converter.setKeyString("32BytesTestKey-abcdefghijklmnop");
    }

    @Test
    void shouldReturnNullForNullInput() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void shouldReturnEmptyForBlankInput() {
        assertThat(converter.convertToDatabaseColumn("")).isEqualTo("");
        assertThat(converter.convertToEntityAttribute("  ")).isEqualTo("  ");
    }

    @Test
    void shouldNotDoubleEncrypt() {
        String encrypted = converter.convertToDatabaseColumn("test");
        String doubleEncrypted = converter.convertToDatabaseColumn(encrypted);
        assertThat(doubleEncrypted).isEqualTo(encrypted);
    }

    @Test
    void shouldEncryptAndDecryptWithGcm() {
        String original = "sk-my-secret-api-key-12345";
        String encrypted = converter.convertToDatabaseColumn(original);

        assertThat(encrypted).startsWith("{ENC}");
        assertThat(encrypted).isNotEqualTo(original);

        String decrypted = converter.convertToEntityAttribute(encrypted);
        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    void shouldEncryptAndDecryptUnicodeData() {
        String original = "senha-com-acentos-ção-ñ-日本語";
        String encrypted = converter.convertToDatabaseColumn(original);
        String decrypted = converter.convertToEntityAttribute(encrypted);
        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    void shouldEncryptAndDecryptLongString() {
        String original = "A".repeat(4000);
        String encrypted = converter.convertToDatabaseColumn(original);
        String decrypted = converter.convertToEntityAttribute(encrypted);
        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    void shouldProduceDifferentCiphertextEachTime() {
        String original = "same-data";
        String enc1 = converter.convertToDatabaseColumn(original);
        String enc2 = converter.convertToDatabaseColumn(original);
        assertThat(enc1).isNotEqualTo(enc2); // different IV each time
        assertThat(converter.convertToEntityAttribute(enc1)).isEqualTo(original);
        assertThat(converter.convertToEntityAttribute(enc2)).isEqualTo(original);
    }

    @Test
    void shouldHandleUnparseableBase64Gracefully() {
        String garbage = "{ENC}!!!not-valid-base64!!!";
        String result = converter.convertToEntityAttribute(garbage);
        assertThat(result).isNull();
    }

    @Test
    void shouldHandleTooShortEcbData() {
        String shortData = "{ENC}" + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        String result = converter.convertToEntityAttribute(shortData);
        assertThat(result).isNull();
    }

    @Test
    void shouldHandleGcmDataWithWrongKey() {
        String original = "test-data";
        String encrypted = converter.convertToDatabaseColumn(original);

        StringCryptoConverter differentConverter = new StringCryptoConverter();
        differentConverter.setKeyString("99DifferentKey!!-abcdefghijklmn");

        String result = differentConverter.convertToEntityAttribute(encrypted);
        assertThat(result).isNull();
    }

    @Test
    void shouldReEncryptWithGcmAfterMigration() {
        String original = "data-to-migrate";

        // 1. Manually create ECB-encrypted data
        String encrypted = converter.convertToDatabaseColumn(original);
        String decrypted = converter.convertToEntityAttribute(encrypted);

        // 2. Re-encrypt (simulating JPA save cycle)
        String reEncrypted = converter.convertToDatabaseColumn(decrypted);
        String reDecrypted = converter.convertToEntityAttribute(reEncrypted);

        assertThat(reDecrypted).isEqualTo(original);
        assertThat(reEncrypted).isNotEqualTo(encrypted); // new IV
    }

    @Test
    void shouldReturnNullForCorruptedData() {
        String corrupted = "{ENC}this-is-not-valid-base64!!!";
        String result = converter.convertToEntityAttribute(corrupted);
        assertThat(result).isNull();
    }

    @Test
    void shouldHandleDataWithoutPrefix() {
        String plain = "plain-text-no-prefix";
        String result = converter.convertToEntityAttribute(plain);
        assertThat(result).isEqualTo(plain);
    }

    @Test
    void shouldHandleKeyPadding() {
        StringCryptoConverter shortKeyConverter = new StringCryptoConverter();
        shortKeyConverter.setKeyString("short");

        String original = "test-data";
        String encrypted = shortKeyConverter.convertToDatabaseColumn(original);
        String decrypted = shortKeyConverter.convertToEntityAttribute(encrypted);
        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    void shouldHandleTruncatedKey() {
        StringCryptoConverter longKeyConverter = new StringCryptoConverter();
        longKeyConverter.setKeyString("A".repeat(50));

        String original = "test-data";
        String encrypted = longKeyConverter.convertToDatabaseColumn(original);
        String decrypted = longKeyConverter.convertToEntityAttribute(encrypted);
        assertThat(decrypted).isEqualTo(original);
    }
}
