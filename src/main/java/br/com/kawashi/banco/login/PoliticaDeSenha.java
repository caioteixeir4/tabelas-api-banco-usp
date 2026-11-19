package br.com.kawashi.banco.login;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * O que uma senha nova precisa cumprir. Segue a linha do NIST SP 800-63B: o que protege e o
 * TAMANHO e nao ser uma senha conhecida; regras de composicao ("uma maiuscula e um simbolo")
 * levam a senhas previsiveis como "Senha@2024" e nao entram aqui.
 *
 * <ul>
 *   <li>no minimo {@code SENHA_MINIMO} caracteres (parametro, hoje 12) e no maximo 128;</li>
 *   <li>nao pode ser uma senha muito usada, nem uma variacao trivial dela;</li>
 *   <li>nao pode conter a parte do e-mail antes do arroba;</li>
 *   <li>nao pode ser um caractere repetido ou uma sequencia simples.</li>
 * </ul>
 * Repetir as senhas anteriores e conferido a parte (precisa dos hashes do historico).
 */
public final class PoliticaDeSenha {

    static final int MAXIMO = 128;

    /** Senhas que aparecem no topo de todas as listas de vazamento (e as que o proprio sistema sugeriria). */
    private static final Set<String> CONHECIDAS = Set.of(
            "123456789012", "1234567890123", "12345678901234", "senha1234567", "senhasenha12", "password1234",
            "passwordpassword", "qwertyuiopas", "qwerty123456", "abcdefghijkl", "abc123abc123", "iloveyou1234",
            "admin1234567", "administrador", "welcome12345", "letmein12345", "trustno12345", "111111111111",
            "000000000000", "123123123123", "minhasenha12", "minhasenha123", "senhaforte123", "senhasegura123",
            "kawashi12345", "kawashi123456", "brasil123456", "flamengo1234", "corinthians1", "palmeiras123",
            "mudar1234567", "trocar123456", "temporaria12", "changeme1234", "p@ssw0rd1234", "s3nh4s3gur4",
            "qazwsxedcrfv", "1q2w3e4r5t6y", "zaq12wsxcde3", "asdfghjklzxc");

    private PoliticaDeSenha() {
    }

    /** Devolve os problemas da senha; lista vazia = senha aceita. */
    public static List<String> problemas(String senha, String email, int minimo) {
        List<String> problemas = new ArrayList<>();
        if (senha == null || senha.isEmpty()) {
            problemas.add("Informe a senha.");
            return problemas;
        }
        int tamanho = senha.codePointCount(0, senha.length());
        if (tamanho < minimo) {
            problemas.add("A senha precisa ter pelo menos " + minimo + " caracteres (uma frase curta é mais fácil de lembrar e mais forte).");
        }
        if (tamanho > MAXIMO) {
            problemas.add("A senha aceita no máximo " + MAXIMO + " caracteres.");
        }
        String minuscula = senha.toLowerCase(Locale.ROOT);
        String semVariacao = minuscula.replace('@', 'a').replace('0', 'o').replace('1', 'i').replace('3', 'e')
                .replace('4', 'a').replace('5', 's').replace('$', 's').replaceAll("[^a-z0-9]", "");
        if (CONHECIDAS.contains(minuscula) || CONHECIDAS.contains(semVariacao)) {
            problemas.add("Essa senha é conhecida (aparece em listas de senhas vazadas). Escolha outra.");
        }
        if (email != null && email.contains("@")) {
            String nome = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT);
            if (nome.length() >= 3 && minuscula.contains(nome)) {
                problemas.add("A senha não pode conter o seu e-mail.");
            }
        }
        if (minuscula.chars().distinct().count() <= 2) {
            problemas.add("A senha não pode ser um mesmo caractere repetido.");
        } else if (sequenciaSimples(minuscula)) {
            problemas.add("A senha não pode ser uma sequência simples (como 123456789 ou abcdefgh).");
        }
        return problemas;
    }

    private static boolean sequenciaSimples(String s) {
        boolean sobe = true;
        boolean desce = true;
        for (int i = 1; i < s.length(); i++) {
            int passo = s.charAt(i) - s.charAt(i - 1);
            sobe &= passo == 1;
            desce &= passo == -1;
        }
        return s.length() > 1 && (sobe || desce);
    }
}
