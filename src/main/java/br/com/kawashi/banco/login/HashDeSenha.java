package br.com.kawashi.banco.login;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Hash das senhas do login com <b>argon2id</b>, o algoritmo recomendado em primeiro lugar pela OWASP
 * e pela RFC 9106. Ele e "caro em memoria": cada tentativa de adivinhar a senha exige dezenas de
 * megabytes, o que tira a vantagem de quem ataca com placa de video ou hardware dedicado.
 *
 * <p>O resultado sai no formato padrao PHC, com tudo o que e preciso para conferir depois:
 * {@code $argon2id$v=19$m=65536,t=3,p=4$<sal>$<hash>}. Como os parametros ficam gravados junto,
 * da para endurecer o custo no futuro sem perder as senhas antigas: elas continuam conferindo, e
 * {@link #precisaRefazer} avisa que devem ser regravadas no proximo login certo.
 *
 * <p>Padrao: m = 64 MiB, t = 3, p = 4 (segunda recomendacao da RFC 9106). Custo menor que o minimo
 * da OWASP (19 MiB) so e aceito com {@code app.senha.custo-de-teste=true}, que existe so nos
 * testes: assim um erro de configuracao em producao nao enfraquece as senhas em silencio.
 */
@Component
public class HashDeSenha {

    private static final int BYTES_DO_SAL = 16;
    private static final int BYTES_DO_HASH = 32;
    private static final int MEMORIA_MINIMA_KIB = 19_456;
    private static final Pattern PHC = Pattern.compile(
            "\\$argon2id\\$v=19\\$m=(\\d{1,7}),t=(\\d{1,2}),p=(\\d{1,2})\\$([A-Za-z0-9+/]+)\\$([A-Za-z0-9+/]+)");

    private final int memoriaKib;
    private final int iteracoes;
    private final int paralelismo;
    private final SecureRandom sorteio = new SecureRandom();
    /** Um hash qualquer, com o custo atual, para gastar o mesmo tempo quando o usuario nao existe. */
    private final String hashDeDisfarce;

    public HashDeSenha(@Value("${app.senha.argon2-memoria-kib:65536}") int memoriaKib,
            @Value("${app.senha.argon2-iteracoes:3}") int iteracoes,
            @Value("${app.senha.argon2-paralelismo:4}") int paralelismo,
            @Value("${app.senha.custo-de-teste:false}") boolean custoDeTeste) {
        if (!custoDeTeste && (memoriaKib < MEMORIA_MINIMA_KIB || iteracoes < 2 || paralelismo < 1)) {
            throw new IllegalStateException("Custo do argon2id abaixo do minimo da OWASP (m >= 19456 KiB, t >= 2). "
                    + "Custo menor so com app.senha.custo-de-teste=true, que e para os testes.");
        }
        this.memoriaKib = memoriaKib;
        this.iteracoes = iteracoes;
        this.paralelismo = paralelismo;
        byte[] qualquer = new byte[24];
        sorteio.nextBytes(qualquer);
        this.hashDeDisfarce = gerar(Base64.getEncoder().encodeToString(qualquer).toCharArray());
    }

    /** Gera o hash de uma senha, com sal novo. */
    public String gerar(char[] senha) {
        byte[] sal = new byte[BYTES_DO_SAL];
        sorteio.nextBytes(sal);
        byte[] hash = calcular(senha, sal, memoriaKib, iteracoes, paralelismo);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return "$argon2id$v=19$m=" + memoriaKib + ",t=" + iteracoes + ",p=" + paralelismo
                + "$" + b64.encodeToString(sal) + "$" + b64.encodeToString(hash);
    }

    /** Confere uma senha contra um hash gravado. Comparacao em tempo constante. */
    public boolean confere(char[] senha, String gravado) {
        if (gravado == null) {
            return false;
        }
        Matcher m = PHC.matcher(gravado);
        if (!m.matches()) {
            return false;
        }
        byte[] sal = Base64.getDecoder().decode(m.group(4));
        byte[] esperado = Base64.getDecoder().decode(m.group(5));
        byte[] calculado = calcular(senha, sal, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), esperado.length);
        return MessageDigest.isEqual(calculado, esperado);
    }

    /**
     * Gasta o mesmo tempo de uma conferencia de verdade e devolve falso. Usado quando o usuario nao
     * existe, esta inativo ou bloqueado: assim o tempo de resposta nao revela qual foi o caso.
     */
    public boolean confereDisfarce(char[] senha) {
        confere(senha, hashDeDisfarce);
        return false;
    }

    /** O hash gravado tem custo menor que o atual: deve ser regravado no proximo login certo. */
    public boolean precisaRefazer(String gravado) {
        Matcher m = PHC.matcher(gravado == null ? "" : gravado);
        if (!m.matches()) {
            return true;
        }
        return Integer.parseInt(m.group(1)) < memoriaKib || Integer.parseInt(m.group(2)) < iteracoes
                || Integer.parseInt(m.group(3)) < paralelismo;
    }

    private static byte[] calcular(char[] senha, byte[] sal, int memoria, int iteracoes, int paralelismo) {
        return calcular(senha, sal, memoria, iteracoes, paralelismo, BYTES_DO_HASH);
    }

    private static byte[] calcular(char[] senha, byte[] sal, int memoria, int iteracoes, int paralelismo, int tamanho) {
        Argon2Parameters parametros = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memoria)
                .withIterations(iteracoes)
                .withParallelism(paralelismo)
                .withSalt(sal)
                .build();
        Argon2BytesGenerator gerador = new Argon2BytesGenerator();
        gerador.init(parametros);
        byte[] bytesDaSenha = paraUtf8(senha);
        byte[] saida = new byte[tamanho];
        try {
            gerador.generateBytes(bytesDaSenha, saida);
        } finally {
            Arrays.fill(bytesDaSenha, (byte) 0);
        }
        return saida;
    }

    private static byte[] paraUtf8(char[] senha) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(senha));
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), (byte) 0);
        }
        return bytes;
    }
}
