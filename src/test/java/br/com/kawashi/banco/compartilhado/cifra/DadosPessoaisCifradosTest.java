package br.com.kawashi.banco.compartilhado.cifra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import br.com.kawashi.banco.log.SchemaDeLog;
import br.com.kawashi.banco.testes.Api;
import br.com.kawashi.banco.testes.UsuarioDeTeste;
import br.com.kawashi.banco.testes.GeradorDeDocumentos;
import br.com.kawashi.banco.testes.IntegracaoApi;

import jakarta.persistence.EntityManager;

/**
 * As tres camadas vistas de ponta a ponta contra o Oracle real: o que fica gravado na tabela (so
 * cifra), o que a API responde (mascara), como se ve o valor inteiro (revelar, com rastro) e o que
 * acontece quando alguem mexe no dado cifrado por fora.
 */
@IntegracaoApi
class DadosPessoaisCifradosTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioDeTeste usuarios;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager em;
    @Autowired
    private SchemaDeLog schemaDeLog;
    private Api api;

    @BeforeEach
    void preparar() {
        api = new Api(mvc, usuarios.token());
    }

    private Map<String, Object> linhaDaPessoa(long id) {
        em.flush();
        return jdbc.queryForMap("SELECT DOCUMENTO_CIFRADO, DOCUMENTO_INDICE FROM PESSOA WHERE ID = ?", id);
    }

    private Map<String, Object> linhaDoContato(long id) {
        em.flush();
        return jdbc.queryForMap("SELECT VALOR_CIFRADO, VALOR_INDICE FROM CONTATO WHERE ID = ?", id);
    }

    private long pessoaCom(long empresaId, String tipo, String cpf) throws Exception {
        return api.criar("/api/empresas/" + empresaId + "/pessoas", Api.pessoaJson(tipo, cpf, null, null));
    }

    // ---- camada 2: o que o banco guarda --------------------------------------------------

    @Test
    void oBancoGuardaODocumentoCifradoENuncaEmClaro() throws Exception {
        String cpf = GeradorDeDocumentos.cpf();
        long id = pessoaCom(api.empresa(), "CLIENTE", cpf);

        Map<String, Object> linha = linhaDaPessoa(id);
        String cifrado = (String) linha.get("DOCUMENTO_CIFRADO");
        assertThat(cifrado).startsWith("v1:").doesNotContain(cpf);
        assertThat((String) linha.get("DOCUMENTO_INDICE")).matches("[0-9a-f]{64}").doesNotContain(cpf);
        // Nenhuma coluna da linha, lida direto do banco, contem o CPF.
        Map<String, Object> tudo = jdbc.queryForMap("SELECT * FROM PESSOA WHERE ID = ?", id);
        assertThat(tudo.values()).noneMatch(v -> v != null && v.toString().contains(cpf));
        // A coluna antiga nao existe mais.
        assertThat(tudo).doesNotContainKey("DOCUMENTO");
    }

    @Test
    void oMesmoCpfEmDuasEmpresasTemCifrasDiferentesEAMesmaImpressaoDigital() throws Exception {
        String cpf = GeradorDeDocumentos.cpf();
        Map<String, Object> a = linhaDaPessoa(pessoaCom(api.empresa(), "CLIENTE", cpf));
        Map<String, Object> b = linhaDaPessoa(pessoaCom(api.empresa(), "CLIENTE", cpf));
        // Quem le a tabela nao ve que e o mesmo CPF pela cifra...
        assertThat(a.get("DOCUMENTO_CIFRADO")).isNotEqualTo(b.get("DOCUMENTO_CIFRADO"));
        // ...e a impressao digital, igual, e o que deixa o banco barrar a repeticao na mesma empresa.
        assertThat(a.get("DOCUMENTO_INDICE")).isEqualTo(b.get("DOCUMENTO_INDICE"));
    }

    @Test
    void aUnicidadeContinuaValendoComODocumentoCifradoEOErroNaoRepeteODocumento() throws Exception {
        String cpf = GeradorDeDocumentos.cpf();
        long empresa = api.empresa();
        pessoaCom(empresa, "CLIENTE", cpf);
        api.post("/api/empresas/" + empresa + "/pessoas", Api.pessoaJson("CLIENTE", GeradorDeDocumentos.formatarCpf(cpf), null, null))
                .andExpect(status().isConflict())
                .andExpect(content().string(not(containsString(cpf))))
                .andExpect(jsonPath("$.detail").value(containsString(Mascaras.documento(cpf))));
    }

    @Test
    void oBancoBarraARepeticaoMesmoSemAApi() throws Exception {
        long empresa = api.empresa();
        long id = pessoaCom(empresa, "CLIENTE", GeradorDeDocumentos.cpf());
        Map<String, Object> linha = linhaDaPessoa(id);
        // Um INSERT direto com a mesma impressao digital, na mesma empresa e tipo: UK_PESSOA recusa.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO PESSOA (EMPRESA_ID, TIPO, NATUREZA, DOCUMENTO_CIFRADO, DOCUMENTO_INDICE, NOME, ATIVA) VALUES (?, 'CLIENTE', 'F', ?, ?, 'Copia', 1)",
                empresa, linha.get("DOCUMENTO_CIFRADO"), linha.get("DOCUMENTO_INDICE")))
                .isInstanceOf(DataAccessException.class).hasMessageMatching("(?s).*ORA-00001.*");
    }

    @Test
    void oBancoRecusaTextoEmClaroNasColunasCifradas() throws Exception {
        long empresa = api.empresa();
        String cpf = GeradorDeDocumentos.cpf();
        // Documento em claro na coluna de cifra (um INSERT manual, um erro de codigo): CHECK recusa.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO PESSOA (EMPRESA_ID, TIPO, NATUREZA, DOCUMENTO_CIFRADO, DOCUMENTO_INDICE, NOME, ATIVA) VALUES (?, 'CLIENTE', 'F', ?, ?, 'Em claro', 1)",
                empresa, cpf, "a".repeat(64)))
                .isInstanceOf(DataAccessException.class).hasMessageMatching("(?s).*ORA-02290.*");
        long pessoa = pessoaCom(empresa, "CLIENTE", cpf);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO CONTATO (PESSOA_ID, TIPO, VALOR_CIFRADO, VALOR_INDICE, PRINCIPAL) VALUES (?, 'EMAIL', 'fulano@exemplo.com.br', ?, 1)",
                pessoa, "a".repeat(64)))
                .isInstanceOf(DataAccessException.class).hasMessageMatching("(?s).*ORA-02290.*");
    }

    @Test
    void contatoTambemVaiCifradoENormalizadoAntesDeCifrar() throws Exception {
        long pessoa = api.pessoa();
        long email = api.criar("/api/pessoas/" + pessoa + "/contatos", "{\"tipo\":\"EMAIL\",\"valor\":\"  FULANO@Exemplo.com.br \"}");
        long fone = api.criar("/api/pessoas/" + pessoa + "/contatos", "{\"tipo\":\"TELEFONE\",\"valor\":\"(11) 98765-4321\"}");

        assertThat((String) linhaDoContato(email).get("VALOR_CIFRADO")).startsWith("v1:").doesNotContainIgnoringCase("fulano");
        assertThat((String) linhaDoContato(fone).get("VALOR_CIFRADO")).startsWith("v1:").doesNotContain("98765");
        // O valor revelado e o normalizado: e-mail em minuscula, telefone so com digitos.
        api.get("/api/contatos/" + email + "/valor").andExpect(jsonPath("$.valor").value("fulano@exemplo.com.br"));
        api.get("/api/contatos/" + fone + "/valor").andExpect(jsonPath("$.valor").value("11987654321"));
        // Repetido no mesmo dono continua sendo conflito, mesmo escrito de outro jeito.
        api.post("/api/pessoas/" + pessoa + "/contatos", "{\"tipo\":\"EMAIL\",\"valor\":\"fulano@EXEMPLO.com.br\"}").andExpect(status().isConflict());
        api.post("/api/pessoas/" + pessoa + "/contatos", "{\"tipo\":\"TELEFONE\",\"valor\":\"11 98765 4321\"}").andExpect(status().isConflict());
    }

    // ---- camada 3: o que a API mostra ----------------------------------------------------

    @Test
    void respostasSaemMascaradasERevelarDevolveOValorInteiro() throws Exception {
        String cpf = GeradorDeDocumentos.cpf();
        long empresa = api.empresa();
        long id = pessoaCom(empresa, "CLIENTE", cpf);
        String mascara = Mascaras.documento(cpf);

        api.get("/api/pessoas/" + id)
                .andExpect(jsonPath("$.documento").value(mascara))
                .andExpect(content().string(not(containsString(cpf))));
        api.get("/api/empresas/" + empresa + "/pessoas")
                .andExpect(jsonPath("$.itens[0].documento").value(mascara))
                .andExpect(content().string(not(containsString(cpf))));
        api.get("/api/pessoas/" + id + "/documento")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.natureza").value("F"))
                .andExpect(jsonPath("$.documento").value(cpf));
        api.get("/api/pessoas/999999999/documento").andExpect(status().isNotFound());
        api.get("/api/contatos/999999999/valor").andExpect(status().isNotFound());
    }

    @Test
    void revelarDeixaRastroNoLogDeAcesso() throws Exception {
        long id = api.pessoa();
        long contato = api.criar("/api/pessoas/" + id + "/contatos", "{\"tipo\":\"EMAIL\",\"valor\":\"a@exemplo.com.br\"}");
        api.get("/api/pessoas/" + id + "/documento").andExpect(status().isOk());
        api.get("/api/contatos/" + contato + "/valor").andExpect(status().isOk());

        List<Map<String, Object>> acessos = jdbc.queryForList("SELECT METODO, CAMINHO, STATUS FROM " + schemaDeLog.tabela("LOG_ACESSO")
                + " WHERE CAMINHO IN (?, ?) ORDER BY ID", "/api/pessoas/" + id + "/documento", "/api/contatos/" + contato + "/valor");
        // Da para responder "quem viu o documento de quem, e quando": cada revelacao e uma linha.
        assertThat(acessos).hasSize(2);
        assertThat(acessos).allSatisfy(a -> assertThat(((Number) a.get("STATUS")).intValue()).isEqualTo(200));
    }

    @Test
    void atualizarAceitaAMascaraComoMesmoDocumentoMasNaoOutraMascara() throws Exception {
        String cpf = GeradorDeDocumentos.cpf();
        long id = pessoaCom(api.empresa(), "CLIENTE", cpf);
        String cifradoAntes = (String) linhaDaPessoa(id).get("DOCUMENTO_CIFRADO");

        // Quem so tem o que a API mostrou devolve a mascara: vale por "o mesmo documento".
        api.put("/api/pessoas/" + id, "{\"tipo\":\"CLIENTE\",\"natureza\":\"F\",\"documento\":\"" + Mascaras.documento(cpf) + "\",\"nome\":\"Nome Novo\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.nome").value("Nome Novo"));
        // O documento completo continua aceito.
        api.put("/api/pessoas/" + id, "{\"tipo\":\"CLIENTE\",\"natureza\":\"F\",\"documento\":\"" + cpf + "\",\"nome\":\"Outro Nome\"}")
                .andExpect(status().isOk());
        // A mascara de OUTRO documento nao e "o mesmo".
        api.put("/api/pessoas/" + id, "{\"tipo\":\"CLIENTE\",\"natureza\":\"F\",\"documento\":\"***.000.000-**\",\"nome\":\"X\"}")
                .andExpect(status().isUnprocessableEntity());
        // Atualizar o nome nao regrava (nem recifra) o documento.
        assertThat(linhaDaPessoa(id).get("DOCUMENTO_CIFRADO")).isEqualTo(cifradoAntes);
        api.get("/api/pessoas/" + id + "/documento").andExpect(jsonPath("$.documento").value(cpf));
    }

    @Test
    void cadastroNaoAceitaValorMascarado() throws Exception {
        long empresa = api.empresa();
        api.post("/api/empresas/" + empresa + "/pessoas", "{\"tipo\":\"CLIENTE\",\"natureza\":\"F\",\"documento\":\"***.982.247-**\",\"nome\":\"X\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("mascarado")));
        long pessoa = api.pessoa(empresa, "CLIENTE");
        api.post("/api/pessoas/" + pessoa + "/contatos", "{\"tipo\":\"EMAIL\",\"valor\":\"f***@exemplo.com.br\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("mascarado")));
        api.post("/api/pessoas/" + pessoa + "/contatos", "{\"tipo\":\"TELEFONE\",\"valor\":\"(11) *****-4321\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void contatoAtualizadoComAMascaraMantemOValorEComValorNovoTroca() throws Exception {
        long pessoa = api.pessoa();
        String contatos = "/api/pessoas/" + pessoa + "/contatos";
        long primeiro = api.criar(contatos, "{\"tipo\":\"EMAIL\",\"valor\":\"primeiro@exemplo.com.br\"}");
        long segundo = api.criar(contatos, "{\"tipo\":\"EMAIL\",\"valor\":\"segundo@exemplo.com.br\"}");
        Map<String, Object> antes = linhaDoContato(segundo);

        // Promover a principal sem conhecer o e-mail inteiro: devolve a mascara como veio.
        api.put("/api/contatos/" + segundo, "{\"tipo\":\"EMAIL\",\"valor\":\"s***@exemplo.com.br\",\"principal\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.principal").value(true))
                .andExpect(jsonPath("$.valor").value("s***@exemplo.com.br"));
        Map<String, Object> depois = linhaDoContato(segundo);
        assertThat(depois.get("VALOR_CIFRADO")).isEqualTo(antes.get("VALOR_CIFRADO"));
        api.get("/api/contatos/" + segundo + "/valor").andExpect(jsonPath("$.valor").value("segundo@exemplo.com.br"));
        api.get("/api/contatos/" + primeiro).andExpect(jsonPath("$.principal").value(false));

        // Mascara que nao e a deste contato: recusada, nada muda.
        api.put("/api/contatos/" + segundo, "{\"tipo\":\"EMAIL\",\"valor\":\"x***@outro.com.br\",\"principal\":true}")
                .andExpect(status().isBadRequest());

        // Valor novo, completo: troca a cifra e a impressao digital.
        api.put("/api/contatos/" + segundo, "{\"tipo\":\"EMAIL\",\"valor\":\"novo@exemplo.com.br\",\"principal\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.valor").value("n***@exemplo.com.br"));
        Map<String, Object> trocado = linhaDoContato(segundo);
        assertThat(trocado.get("VALOR_CIFRADO")).isNotEqualTo(antes.get("VALOR_CIFRADO"));
        assertThat(trocado.get("VALOR_INDICE")).isNotEqualTo(antes.get("VALOR_INDICE"));
        api.get("/api/contatos/" + segundo + "/valor").andExpect(jsonPath("$.valor").value("novo@exemplo.com.br"));
    }

    // ---- adulteracao por fora ------------------------------------------------------------

    @Test
    void cifraAlteradaPorForaViraErroInternoSemVazarNada() throws Exception {
        String cpf = GeradorDeDocumentos.cpf();
        long id = pessoaCom(api.empresa(), "CLIENTE", cpf);
        String cifrado = (String) linhaDaPessoa(id).get("DOCUMENTO_CIFRADO");
        char ultimo = cifrado.charAt(cifrado.length() - 5);
        String adulterado = cifrado.substring(0, cifrado.length() - 5) + (ultimo == 'A' ? 'B' : 'A') + cifrado.substring(cifrado.length() - 4);

        jdbc.update("UPDATE PESSOA SET DOCUMENTO_CIFRADO = ? WHERE ID = ?", adulterado, id);
        em.clear();
        api.get("/api/pessoas/" + id)
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString(cpf))))
                .andExpect(content().string(not(containsString(adulterado))));
    }

    @Test
    void cifraDeOutraColunaNaoPassaPorDocumento() throws Exception {
        long id = api.pessoa();
        long contato = api.criar("/api/pessoas/" + id + "/contatos", "{\"tipo\":\"EMAIL\",\"valor\":\"a@exemplo.com.br\"}");
        String cifraDoContato = (String) linhaDoContato(contato).get("VALOR_CIFRADO");

        // Um DBA copia a cifra do e-mail para a coluna do documento: e cifra valida, da chave certa,
        // mas de outra coluna. A API nao a aceita como documento.
        jdbc.update("UPDATE PESSOA SET DOCUMENTO_CIFRADO = ? WHERE ID = ?", cifraDoContato, id);
        em.clear();
        api.get("/api/pessoas/" + id + "/documento").andExpect(status().isInternalServerError());
    }
}
