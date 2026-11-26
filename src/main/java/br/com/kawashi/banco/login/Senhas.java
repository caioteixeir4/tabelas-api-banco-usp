package br.com.kawashi.banco.login;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.springframework.stereotype.Component;

import br.com.kawashi.banco.compartilhado.erros.RegraDeNegocioException;

/**
 * Senha: o hash e Argon2id (o que a OWASP recomenda; parametros minimos dela: 19 MiB, 2 passadas,
 * 1 fio), no formato PHC ({@code $argon2id$v=19$m=19456,t=2,p=1$sal$hash}), com sal aleatorio de 16
 * bytes. Do hash nao se volta a senha, e conferir leva o mesmo tempo acertando ou errando.
 *
 * <p>Regras da senha: pelo menos SENHA_MIN_CARACTERES, nada das senhas mais comuns, e nada que
 * contenha o e-mail (a parte antes do @) ou o nome da pessoa.
 */
@Component
public class Senhas {

    private static final int MEMORIA_KIB = 19_456;
    private static final int PASSADAS = 2;
    private static final int FIOS = 1;
    private static final int TAMANHO_HASH = 32;
    private static final Pattern PHC = Pattern.compile(
            "\\$argon2id\\$v=19\\$m=(\\d+),t=(\\d+),p=(\\d+)\\$([A-Za-z0-9+/]+)\\$([A-Za-z0-9+/]+)");
    private static final SecureRandom ALEATORIO = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder D64 = Base64.getDecoder();

    /** Algumas das senhas mais usadas (listas publicas de vazamentos); sem diferenciar maiusculas. */
    private static final Set<String> COMUNS = Set.of("123456", "1234567", "12345678", "123456789", "1234567890", "12345678910",
            "0123456789", "senha", "senha123", "senha1234", "senha12345", "password", "password1", "password123", "qwerty",
            "qwerty123", "qwertyuiop", "abc123", "abcd1234", "111111", "000000", "123123", "654321", "admin", "admin123",
            "administrador", "iloveyou", "brasil", "brasil123", "mudar123", "mudar@123", "trocar123", "kawashi", "kawashi123",
            "1q2w3e4r", "1q2w3e4r5t", "q1w2e3r4", "asdfghjkl", "zxcvbnm", "11111111", "00000000", "abcdefgh", "abcdefghij");

    /** O hash Argon2id da senha, no formato PHC. */
    public String hash(String senha) {
        byte[] sal = new byte[16];
        ALEATORIO.nextBytes(sal);
        return "$argon2id$v=19$m=" + MEMORIA_KIB + ",t=" + PASSADAS + ",p=" + FIOS + "$" + B64.encodeToString(sal) + "$"
                + B64.encodeToString(calcular(senha, sal, MEMORIA_KIB, PASSADAS, FIOS));
    }

    /** Confere a senha com o hash guardado. Hash nulo ou fora do formato: falso (sem excecao). */
    public boolean confere(String senha, String guardado) {
        if (senha == null || guardado == null) {
            return false;
        }
        Matcher m = PHC.matcher(guardado);
        if (!m.matches()) {
            return false;
        }
        byte[] esperado = D64.decode(m.group(5));
        byte[] calculado = calcular(senha, D64.decode(m.group(4)), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)));
        return MessageDigest.isEqual(esperado, calculado);
    }

    /**
     * Gasta o mesmo tempo de uma conferencia de verdade. Usado quando o identificador nao existe:
     * assim o tempo da resposta nao revela se a conta existe.
     */
    public void gastarOMesmoTempo(String senha) {
        calcular(senha == null ? "" : senha, new byte[16], MEMORIA_KIB, PASSADAS, FIOS);
    }

    /** Recusa (422) a senha que nao segue as regras. */
    public void exigirBoa(String senha, int minimo, String email, String nome) {
        if (senha == null || senha.codePointCount(0, senha.length()) < minimo) {
            throw new RegraDeNegocioException("A senha precisa ter pelo menos " + minimo + " caracteres.");
        }
        if (senha.length() > 200) {
            throw new RegraDeNegocioException("A senha pode ter no máximo 200 caracteres.");
        }
        String simples = semAcento(senha);
        if (COMUNS.contains(simples) || simples.chars().distinct().count() <= 2) {
            throw new RegraDeNegocioException("Esta senha é fácil demais de adivinhar: escolha outra.");
        }
        if (email != null) {
            String antesDoArroba = semAcento(email.split("@")[0]);
            if (antesDoArroba.length() >= 3 && simples.contains(antesDoArroba)) {
                throw new RegraDeNegocioException("A senha não pode conter o seu e-mail.");
            }
        }
        for (String parte : partesDoNome(nome)) {
            if (simples.contains(parte)) {
                throw new RegraDeNegocioException("A senha não pode conter o seu nome.");
            }
        }
    }

    /** As partes do nome com 3 letras ou mais, sem acento e em minusculas ("Caio Teixeira" -> caio, teixeira). */
    static Set<String> partesDoNome(String nome) {
        if (nome == null) {
            return Set.of();
        }
        java.util.Set<String> partes = new java.util.LinkedHashSet<>();
        for (String p : semAcento(nome).split("[^a-z0-9]+")) {
            if (p.length() >= 3 && !Set.of("das", "dos", "del", "von", "van").contains(p)) {
                partes.add(p);
            }
        }
        return partes;
    }

    static String semAcento(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private static byte[] calcular(String senha, byte[] sal, int memoria, int passadas, int fios) {
        Argon2BytesGenerator gerador = new Argon2BytesGenerator();
        gerador.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memoria).withIterations(passadas).withParallelism(fios).withSalt(sal).build());
        byte[] saida = new byte[TAMANHO_HASH];
        gerador.generateBytes(senha.getBytes(StandardCharsets.UTF_8), saida);
        return saida;
    }
}
