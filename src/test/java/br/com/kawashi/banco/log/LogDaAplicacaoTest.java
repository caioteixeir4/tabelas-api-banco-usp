package br.com.kawashi.banco.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import br.com.kawashi.banco.testes.Api;
import br.com.kawashi.banco.testes.UsuarioDeTeste;
import br.com.kawashi.banco.testes.GeradorDeDocumentos;
import br.com.kawashi.banco.testes.IntegracaoApi;

/**
 * Os logs ficam em outro schema (KAWASHI_ENV_<AMBIENTE>_LOG), em que a API so insere e le. Aqui se
 * confere, pela conexao da propria API, que cada mudanca deixa rastro, que o rastro e encadeado pelo
 * banco e que a API nao consegue alterar nem apagar o que registrou.
 */
@IntegracaoApi
class LogDaAplicacaoTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioDeTeste usuarios;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SchemaDeLog schema;
    private Api api;

    @BeforeEach
    void preparar() {
        api = usuarios.api(mvc);
    }

    private List<Map<String, Object>> alteracoes(String tabela, long id) {
        return jdbc.queryForList("SELECT ORDEM, ACAO, USUARIO, DETALHE, HASH, HASH_ANTERIOR FROM " + schema.tabela("LOG_ALTERACAO")
                + " WHERE TABELA = ? AND REGISTRO_ID = ? ORDER BY ORDEM", tabela, id);
    }

    @Test
    void schemaDeLogEOSchemaDoAmbienteComSufixo() {
        String corrente = jdbc.queryForObject("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL", String.class);
        assertThat(schema.nome()).isEqualTo(corrente + "_LOG");
    }

    @Test
    void criarAlterarEExcluirDeixamRastroEncadeado() throws Exception {
        // Numa filial: criar, alterar, inativar e excluir sao todos do administrador da empresa.
        String raiz = GeradorDeDocumentos.raizDeCnpj();
        long empresa = api.empresa(GeradorDeDocumentos.cnpj(raiz, "0001"));
        long id = api.filial(empresa, raiz);
        String cnpj = GeradorDeDocumentos.cnpj(raiz, "0002");
        api.put("/api/filiais/" + id, "{\"cnpj\":\"" + cnpj + "\",\"nome\":\"Outro Nome\"}").andExpect(status().isOk());
        api.post("/api/filiais/" + id + "/inativar").andExpect(status().isOk());
        api.post("/api/filiais/" + id + "/inativar").andExpect(status().isOk()); // repetir nao muda nada: sem rastro novo
        api.delete("/api/filiais/" + id).andExpect(status().isNoContent());

        List<Map<String, Object>> linhas = alteracoes("FILIAL", id);
        assertThat(linhas).extracting(l -> l.get("ACAO")).containsExactly("INSERT", "UPDATE", "UPDATE", "DELETE");
        assertThat(linhas).extracting(l -> l.get("DETALHE")).containsExactly(null, "dados cadastrais", "inativada", null);
        assertThat(linhas).allSatisfy(l -> assertThat((String) l.get("HASH")).matches("[0-9a-f]{64}"));
        // Nesta transacao so este teste escreve, entao as quatro linhas sao vizinhas na corrente:
        // numeracao seguida, e cada uma aponta para o hash da anterior.
        for (int i = 1; i < linhas.size(); i++) {
            assertThat(((Number) linhas.get(i).get("ORDEM")).longValue())
                    .isEqualTo(((Number) linhas.get(i - 1).get("ORDEM")).longValue() + 1);
            assertThat(linhas.get(i).get("HASH_ANTERIOR")).isEqualTo(linhas.get(i - 1).get("HASH"));
        }
    }

    @Test
    void todasAsTabelasDeCadastroRegistramACriacao() throws Exception {
        long filial = api.filial();
        long pessoa = api.pessoa();
        long contato = api.criar("/api/pessoas/" + pessoa + "/contatos", "{\"tipo\":\"EMAIL\",\"valor\":\"a@exemplo.com.br\"}");
        long endereco = api.criar("/api/filiais/" + filial + "/enderecos",
                "{\"logradouro\":\"Rua A\",\"cidade\":\"Sao Paulo\",\"uf\":\"SP\",\"cep\":\"01001000\"}");

        assertThat(alteracoes("FILIAL", filial)).hasSize(1);
        assertThat(alteracoes("PESSOA", pessoa)).hasSize(1);
        assertThat(alteracoes("CONTATO", contato)).hasSize(1);
        assertThat(alteracoes("ENDERECO", endereco)).hasSize(1);
    }

    @Test
    void pedidoRecusadoNaoDeixaRastroDeAlteracao() throws Exception {
        long antes = jdbc.queryForObject("SELECT COUNT(*) FROM " + schema.tabela("LOG_ALTERACAO"), Long.class);
        api.post("/api/empresas/" + api.empresaAtual() + "/pessoas", Api.pessoaJson("CLIENTE", "123", null, null))
                .andExpect(status().isBadRequest());
        api.delete("/api/pessoas/999999999").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schema.tabela("LOG_ALTERACAO"), Long.class)).isEqualTo(antes);
    }

    @Test
    void aCorrenteEstaInteira() throws Exception {
        api.pessoa();
        Long quebras = jdbc.queryForObject("SELECT COUNT(*) FROM " + schema.tabela("V_LOG_ALTERACAO_QUEBRAS"), Long.class);
        assertThat(quebras).isZero();
    }

    @Test
    void aApiNaoConsegueAlterarNemApagarOLog() {
        // ORA-41900 (ou ORA-01031, conforme o cliente): falta o privilegio. A API so tem INSERT e SELECT
        // neste schema.
        assertThatThrownBy(() -> jdbc.update("UPDATE " + schema.tabela("LOG_ALTERACAO") + " SET USUARIO = 'outro'"))
                .isInstanceOf(DataAccessException.class).hasMessageMatching("(?s).*ORA-(01031|41900).*");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + schema.tabela("LOG_ALTERACAO")))
                .isInstanceOf(DataAccessException.class).hasMessageMatching("(?s).*ORA-(01031|41900).*");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + schema.tabela("LOG_ACESSO")))
                .isInstanceOf(DataAccessException.class).hasMessageMatching("(?s).*ORA-(01031|41900).*");
        // A tabela de controle da corrente nem e visivel.
        assertThatThrownBy(() -> jdbc.queryForList("SELECT * FROM " + schema.tabela("LOG_CADEIA")))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("ORA-00942");
    }

    @Test
    void cadaChamadaVaiParaOLogDeAcessoSemAQueryString() throws Exception {
        long id = api.empresa();
        api.get("/api/empresas/" + id + "?segredo=nao-deve-aparecer").andExpect(status().isOk());
        api.get("/api/empresas/999999999").andExpect(status().isNotFound());

        List<Map<String, Object>> acessos = jdbc.queryForList("SELECT METODO, CAMINHO, STATUS, IP_ORIGEM FROM "
                + schema.tabela("LOG_ACESSO") + " WHERE CAMINHO IN (?, ?) ORDER BY ID", "/api/empresas/" + id, "/api/empresas/999999999");
        assertThat(acessos).hasSize(2);
        assertThat(acessos.get(0)).containsEntry("METODO", "GET").containsEntry("CAMINHO", "/api/empresas/" + id);
        assertThat(((Number) acessos.get(0).get("STATUS")).intValue()).isEqualTo(200);
        assertThat(((Number) acessos.get(1).get("STATUS")).intValue()).isEqualTo(404);
        // O IP gravado e o do navegador, repassado pelo BFF, e nao o do BFF (o da conexao).
        assertThat(acessos.get(0).get("IP_ORIGEM")).isEqualTo(Api.IP_DE_TESTE);
    }
}
