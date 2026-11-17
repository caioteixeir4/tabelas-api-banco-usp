package br.com.kawashi.banco.compartilhado.cifra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** A cifra sozinha, sem banco nem Spring: o que ela garante e o que ela recusa. */
class CifraDeDadosTest {

    private static final String CHAVE_A = chave(1);
    private static final String CHAVE_B = chave(2);
    private static final String CHAVE_C = chave(3);
    private static final String CPF = "52998224725";

    private final CifraDeDados cifra = new CifraDeDados(CHAVE_A, CHAVE_B);

    private static String chave(int semente) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) semente);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void cifraEDecifraDevolvendoOMesmoTexto() {
        String cifrado = cifra.cifrar("PESSOA.DOCUMENTO", CPF);
        assertThat(cifrado).startsWith("v1:").doesNotContain(CPF);
        assertThat(cifra.decifrar("PESSOA.DOCUMENTO", cifrado)).isEqualTo(CPF);
        assertThat(cifra.decifrar("CONTATO.VALOR", cifra.cifrar("CONTATO.VALOR", "joão@exemplo.com.br")))
                .isEqualTo("joão@exemplo.com.br");
    }

    @Test
    void nuloContinuaNulo() {
        assertThat(cifra.cifrar("X", null)).isNull();
        assertThat(cifra.decifrar("X", null)).isNull();
        assertThat(cifra.indice("X", null)).isNull();
    }

    @Test
    void oMesmoTextoGeraTextosCifradosDiferentesACadaVez() {
        Set<String> vistos = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            vistos.add(cifra.cifrar("PESSOA.DOCUMENTO", CPF));
        }
        // Quem olha a tabela nao descobre que duas linhas tem o mesmo CPF.
        assertThat(vistos).hasSize(200);
    }

    @Test
    void textoCifradoDeUmaColunaNaoDecifraEmOutra() {
        String cifrado = cifra.cifrar("CONTATO.VALOR", CPF);
        assertThatThrownBy(() -> cifra.decifrar("PESSOA.DOCUMENTO", cifrado))
                .isInstanceOf(DadoCifradoInvalidoException.class)
                .hasMessageContaining("PESSOA.DOCUMENTO");
    }

    @Test
    void umCaractereAlteradoEDenunciadoEmVezDeDevolverLixo() {
        String cifrado = cifra.cifrar("PESSOA.DOCUMENTO", CPF);
        for (int posicao = 3; posicao < cifrado.length() - 2; posicao++) {
            char original = cifrado.charAt(posicao);
            char trocado = original == 'A' ? 'B' : 'A';
            String adulterado = cifrado.substring(0, posicao) + trocado + cifrado.substring(posicao + 1);
            assertThatThrownBy(() -> cifra.decifrar("PESSOA.DOCUMENTO", adulterado))
                    .as("posicao %d", posicao)
                    .isInstanceOf(DadoCifradoInvalidoException.class);
        }
    }

    @Test
    void textoEmClaroOuFormatoEstranhoNaoPassaPorCifrado() {
        assertThatThrownBy(() -> cifra.decifrar("X", CPF)).isInstanceOf(DadoCifradoInvalidoException.class);
        assertThatThrownBy(() -> cifra.decifrar("X", "v1:")).isInstanceOf(DadoCifradoInvalidoException.class);
        assertThatThrownBy(() -> cifra.decifrar("X", "v1:nao e base64!")).isInstanceOf(DadoCifradoInvalidoException.class);
        assertThatThrownBy(() -> cifra.decifrar("X", "v2:AAAA")).isInstanceOf(DadoCifradoInvalidoException.class);
    }

    @Test
    void outraChaveNaoDecifra() {
        String cifrado = cifra.cifrar("PESSOA.DOCUMENTO", CPF);
        CifraDeDados outra = new CifraDeDados(CHAVE_C, CHAVE_B);
        assertThatThrownBy(() -> outra.decifrar("PESSOA.DOCUMENTO", cifrado)).isInstanceOf(DadoCifradoInvalidoException.class);
    }

    @Test
    void aMensagemDeErroNaoLevaOConteudo() {
        String cifrado = cifra.cifrar("CONTATO.VALOR", CPF);
        assertThatThrownBy(() -> cifra.decifrar("PESSOA.DOCUMENTO", cifrado))
                .hasMessageNotContaining(CPF)
                .hasMessageNotContaining(cifrado.substring(3));
    }

    @Test
    void indiceEEstavelTem64HexEDependeDoContextoEDaChave() throws Exception {
        String indice = cifra.indice("PESSOA.DOCUMENTO", CPF);
        assertThat(indice).matches("[0-9a-f]{64}");
        assertThat(cifra.indice("PESSOA.DOCUMENTO", CPF)).isEqualTo(indice);
        assertThat(cifra.indice("PESSOA.DOCUMENTO", "11144477735")).isNotEqualTo(indice);
        assertThat(cifra.indice("CONTATO.VALOR", CPF)).isNotEqualTo(indice);
        assertThat(new CifraDeDados(CHAVE_A, CHAVE_C).indice("PESSOA.DOCUMENTO", CPF)).isNotEqualTo(indice);
        // Nao e um hash simples do CPF: sem a chave, ninguem recalcula testando os CPFs possiveis.
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CPF.getBytes(StandardCharsets.UTF_8)));
        assertThat(indice).isNotEqualTo(sha);
    }

    @Test
    void chaveAusenteCurtaOuRepetidaImpedeAAplicacaoDeSubir() {
        assertThatThrownBy(() -> new CifraDeDados("", CHAVE_B))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CIFRA_CHAVE_DADOS").hasMessageContaining("gerar-chaves-de-cifra");
        assertThatThrownBy(() -> new CifraDeDados(CHAVE_A, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CIFRA_CHAVE_INDICE");
        assertThatThrownBy(() -> new CifraDeDados(Base64.getEncoder().encodeToString(new byte[16]), CHAVE_B))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new CifraDeDados("isto nao e base64 ###", CHAVE_B))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("base64");
        assertThatThrownBy(() -> new CifraDeDados(CHAVE_A, CHAVE_A))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mesma chave");
    }

    @Test
    void mascarasEscondemOMeioEDeixamReconhecer() {
        assertThat(Mascaras.documento("52998224725")).isEqualTo("***.982.247-**");
        assertThat(Mascaras.documento("11222333000181")).isEqualTo("**.222.333/0001-**");
        assertThat(Mascaras.documento("123")).isEqualTo("***");
        assertThat(Mascaras.documento(null)).isNull();
        assertThat(Mascaras.email("fulano@exemplo.com.br")).isEqualTo("f***@exemplo.com.br");
        assertThat(Mascaras.email("ab@exemplo.com.br")).isEqualTo("***@exemplo.com.br");
        assertThat(Mascaras.telefone("11987654321")).isEqualTo("(11) *****-4321");
        assertThat(Mascaras.telefone("1133334444")).isEqualTo("(11) ****-4444");
        assertThat(Mascaras.eMascara("***.982.247-**")).isTrue();
        assertThat(Mascaras.eMascara("f***@exemplo.com.br")).isTrue();
        assertThat(Mascaras.eMascara("529.982.247-25")).isFalse();
        assertThat(Mascaras.eMascara(null)).isFalse();
    }
}
