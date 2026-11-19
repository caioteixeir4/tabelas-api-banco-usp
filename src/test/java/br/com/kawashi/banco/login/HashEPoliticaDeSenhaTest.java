package br.com.kawashi.banco.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** O hash de senha e a politica de senha, sem banco nem Spring. */
class HashEPoliticaDeSenhaTest {

    private final HashDeSenha hash = new HashDeSenha(1024, 1, 1, true);

    @Test
    void hashSaiNoFormatoPhcDoArgon2idENaoContemASenha() {
        String gerado = hash.gerar("minha frase secreta".toCharArray());
        assertThat(gerado).matches("\\$argon2id\\$v=19\\$m=1024,t=1,p=1\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}");
        assertThat(gerado).doesNotContain("frase");
    }

    @Test
    void confereASenhaCertaERecusaAErrada() {
        String gerado = hash.gerar("minha frase secreta".toCharArray());
        assertThat(hash.confere("minha frase secreta".toCharArray(), gerado)).isTrue();
        assertThat(hash.confere("minha frase secretA".toCharArray(), gerado)).isFalse();
        assertThat(hash.confere("".toCharArray(), gerado)).isFalse();
    }

    @Test
    void aMesmaSenhaGeraHashesDiferentes() {
        // Sal aleatorio: duas pessoas com a mesma senha nao tem o mesmo hash.
        assertThat(hash.gerar("igual igual igual".toCharArray())).isNotEqualTo(hash.gerar("igual igual igual".toCharArray()));
    }

    @Test
    void hashAlteradoOuEstranhoNaoConfere() {
        String gerado = hash.gerar("minha frase secreta".toCharArray());
        String trocado = gerado.substring(0, gerado.length() - 2) + (gerado.endsWith("A") ? "BB" : "AA");
        assertThat(hash.confere("minha frase secreta".toCharArray(), trocado)).isFalse();
        assertThat(hash.confere("minha frase secreta".toCharArray(), null)).isFalse();
        assertThat(hash.confere("minha frase secreta".toCharArray(), "$2a$10$bcryptqualquer")).isFalse();
        assertThat(hash.confereDisfarce("qualquer".toCharArray())).isFalse();
    }

    @Test
    void custoAbaixoDoMinimoDaOwaspSoComAutorizacaoDeTeste() {
        assertThatThrownBy(() -> new HashDeSenha(1024, 1, 1, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OWASP");
        assertThatThrownBy(() -> new HashDeSenha(65536, 1, 4, false)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hashComCustoMenorQueOAtualPrecisaSerRefeito() {
        String fraco = hash.gerar("minha frase secreta".toCharArray());
        HashDeSenha maisForte = new HashDeSenha(2048, 2, 1, true);
        assertThat(maisForte.precisaRefazer(fraco)).isTrue();
        // ... e continua conferindo com os parametros gravados nele.
        assertThat(maisForte.confere("minha frase secreta".toCharArray(), fraco)).isTrue();
        assertThat(maisForte.precisaRefazer(maisForte.gerar("x".toCharArray()))).isFalse();
    }

    @Test
    void politicaExigeTamanhoERecusaSenhasConhecidasESimples() {
        String email = "fulano.silva@exemplo.com.br";
        assertThat(PoliticaDeSenha.problemas("uma frase longa e minha", email, 12)).isEmpty();
        assertThat(PoliticaDeSenha.problemas("curta", email, 12)).anyMatch(p -> p.contains("pelo menos 12"));
        assertThat(PoliticaDeSenha.problemas("x".repeat(129), email, 12)).anyMatch(p -> p.contains("no máximo"));
        assertThat(PoliticaDeSenha.problemas("password1234", email, 12)).anyMatch(p -> p.contains("conhecida"));
        assertThat(PoliticaDeSenha.problemas("P@ssw0rd1234", email, 12)).anyMatch(p -> p.contains("conhecida"));
        assertThat(PoliticaDeSenha.problemas("fulano.silva da empresa", email, 12)).anyMatch(p -> p.contains("e-mail"));
        assertThat(PoliticaDeSenha.problemas("aaaaaaaaaaaaaa", email, 12)).anyMatch(p -> p.contains("repetido"));
        assertThat(PoliticaDeSenha.problemas("abcdefghijklmn", email, 12)).anyMatch(p -> p.contains("sequência"));
        assertThat(PoliticaDeSenha.problemas("", email, 12)).isNotEmpty();
        // Sem regra de composicao: uma frase so com minusculas e espacos e aceita (NIST 800-63B).
        assertThat(PoliticaDeSenha.problemas("cavalo bateria grampo correto", email, 12)).isEmpty();
    }
}
