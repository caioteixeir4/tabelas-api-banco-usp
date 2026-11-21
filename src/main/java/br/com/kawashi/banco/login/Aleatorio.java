package br.com.kawashi.banco.login;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Segredos aleatorios (token, convite, desafio, codigo) e o hash com que eles vao para o banco. */
public final class Aleatorio {

    private static final SecureRandom SORTEIO = new SecureRandom();

    private Aleatorio() {
    }

    /** 32 bytes do gerador criptografico, em base64url sem preenchimento (43 caracteres). */
    public static String base64Url32() {
        byte[] bytes = new byte[32];
        SORTEIO.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Codigo de recuperacao: 16 caracteres de um alfabeto sem letras e numeros parecidos (sem 0/O,
     * 1/I/L), em 4 grupos: "K7M3-QX9R-...". Cerca de 80 bits: nao da para adivinhar por tentativa.
     */
    public static String codigoDeRecuperacao() {
        String alfabeto = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) {
                s.append('-');
            }
            s.append(alfabeto.charAt(SORTEIO.nextInt(alfabeto.length())));
        }
        return s.toString();
    }

    /**
     * SHA-256 em hexadecimal. Basta um hash sem chave: os segredos guardados assim tem pelo menos 80
     * bits aleatorios, entao nao ha como testar todas as possibilidades a partir do hash.
     */
    public static String sha256(String texto) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
