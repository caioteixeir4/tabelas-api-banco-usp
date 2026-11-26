package br.com.kawashi.banco.login;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

/**
 * O segundo fator: o codigo de 6 digitos de um app autenticador (Google Authenticator, Microsoft
 * Authenticator...), pelo padrao TOTP (RFC 6238): HMAC-SHA1, passo de 30 segundos. Aceita o passo
 * atual e um de cada lado (relogio do celular adiantado ou atrasado). O passo usado e devolvido para
 * quem chama guardar: o mesmo codigo nao vale duas vezes.
 *
 * <p>O QR code (que o app le para guardar o segredo) e gerado aqui, sem servico externo, e vai ao
 * site como uma matriz de 0 e 1: a pagina desenha os quadrados, sem imagem de outro lugar.
 */
@Component
public class Totp {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom ALEATORIO = new SecureRandom();
    static final int PASSO_SEGUNDOS = 30;

    /** Segredo novo: 20 bytes aleatorios, em base32 (32 letras), como os apps esperam. */
    public String novoSegredo() {
        byte[] b = new byte[20];
        ALEATORIO.nextBytes(b);
        return base32(b);
    }

    /** O endereco que o app autenticador entende (vai dentro do QR code). */
    public String uri(String email, String segredo) {
        String rotulo = URLEncoder.encode("Kawashi:" + email, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + rotulo + "?secret=" + segredo + "&issuer=Kawashi&algorithm=SHA1&digits=6&period=" + PASSO_SEGUNDOS;
    }

    /** O QR code da uri, como linhas de 0 e 1 (1 = quadrado escuro), com a margem ja incluida. */
    public List<String> qr(String conteudo) {
        try {
            BitMatrix m = new QRCodeWriter().encode(conteudo, BarcodeFormat.QR_CODE, 0, 0,
                    java.util.Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
            List<String> linhas = new ArrayList<>();
            for (int y = 0; y < m.getHeight(); y++) {
                StringBuilder s = new StringBuilder(m.getWidth());
                for (int x = 0; x < m.getWidth(); x++) {
                    s.append(m.get(x, y) ? '1' : '0');
                }
                linhas.add(s.toString());
            }
            return linhas;
        } catch (com.google.zxing.WriterException e) {
            throw new IllegalStateException("Nao foi possivel gerar o QR code.", e);
        }
    }

    /**
     * Confere o codigo. Devolve o passo que bateu, ou vazio. {@code ultimoPassoUsado}: passos ate ele
     * (inclusive) nao valem mais, entao um codigo visto por alguem nao serve de novo.
     */
    public OptionalLong confere(String segredo, String codigo, Long ultimoPassoUsado, long agoraEmSegundos) {
        if (segredo == null || codigo == null || !codigo.strip().matches("\\d{6}")) {
            return OptionalLong.empty();
        }
        String digitado = codigo.strip();
        long passo = agoraEmSegundos / PASSO_SEGUNDOS;
        for (long p = passo - 1; p <= passo + 1; p++) {
            if (ultimoPassoUsado != null && p <= ultimoPassoUsado) {
                continue;
            }
            if (java.security.MessageDigest.isEqual(codigoDoPasso(segredo, p).getBytes(StandardCharsets.US_ASCII),
                    digitado.getBytes(StandardCharsets.US_ASCII))) {
                return OptionalLong.of(p);
            }
        }
        return OptionalLong.empty();
    }

    /** O codigo de 6 digitos de um passo (RFC 6238 / RFC 4226). */
    public String codigoDoPasso(String segredo, long passo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(deBase32(segredo), "HmacSHA1"));
            byte[] h = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(passo).array());
            int o = h[h.length - 1] & 0x0f;
            int binario = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", binario % 1_000_000);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String base32(byte[] dados) {
        StringBuilder s = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : dados) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                s.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            s.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return s.toString();
    }

    static byte[] deBase32(String texto) {
        String t = texto.replace(" ", "").replace("=", "").toUpperCase(java.util.Locale.ROOT);
        java.io.ByteArrayOutputStream saida = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : t.toCharArray()) {
            int v = BASE32.indexOf(c);
            if (v < 0) {
                throw new IllegalArgumentException("Segredo fora do base32.");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                saida.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return saida.toByteArray();
    }
}
