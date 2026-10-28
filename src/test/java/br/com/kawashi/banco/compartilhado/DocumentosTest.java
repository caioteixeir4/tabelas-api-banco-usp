package br.com.kawashi.banco.compartilhado;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import br.com.kawashi.banco.testes.GeradorDeDocumentos;

class DocumentosTest {

    @Test
    void cnpjConhecidoEValido() {
        assertThat(Documentos.cnpjValido("11222333000181")).isTrue();
        assertThat(Documentos.cnpjValido("11.222.333/0001-81")).isTrue();
    }

    @Test
    void cnpjsGeradosSaoValidos() {
        for (int i = 0; i < 200; i++) {
            assertThat(Documentos.cnpjValido(GeradorDeDocumentos.cnpj())).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"11222333000182", "00000000000000", "11111111111111", "1122233300018", "112223330001811", "abc"})
    @NullAndEmptySource
    void cnpjInvalidoERecusado(String valor) {
        assertThat(Documentos.cnpjValido(valor)).isFalse();
    }

    @Test
    void cpfConhecidoEValido() {
        assertThat(Documentos.cpfValido("52998224725")).isTrue();
        assertThat(Documentos.cpfValido("529.982.247-25")).isTrue();
    }

    @Test
    void cpfsGeradosSaoValidos() {
        for (int i = 0; i < 200; i++) {
            assertThat(Documentos.cpfValido(GeradorDeDocumentos.cpf())).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"52998224726", "00000000000", "11111111111", "5299822472", "529982247255", "abc"})
    @NullAndEmptySource
    void cpfInvalidoERecusado(String valor) {
        assertThat(Documentos.cpfValido(valor)).isFalse();
    }

    @Test
    void somenteDigitosRemovePontuacao() {
        assertThat(Documentos.somenteDigitos("12.345.678/0001-90")).isEqualTo("12345678000190");
        assertThat(Documentos.somenteDigitos(null)).isNull();
    }

    @Test
    void raizEOrdemDoCnpj() {
        assertThat(Documentos.raizCnpj("11.222.333/0001-81")).isEqualTo("11222333");
        assertThat(Documentos.ordemCnpj("11.222.333/0001-81")).isEqualTo("0001");
    }

    @Test
    void ufsExistentesSaoAceitasEInventadasNao() {
        assertThat(Uf.existe("SP")).isTrue();
        assertThat(Uf.existe("TO")).isTrue();
        assertThat(Uf.existe("XX")).isFalse();
        assertThat(Uf.existe("sp")).isFalse();
        assertThat(Uf.existe(null)).isFalse();
        assertThat(Uf.values()).hasSize(27);
    }
}
