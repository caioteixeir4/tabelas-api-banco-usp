package br.com.kawashi.banco.pessoa;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import br.com.kawashi.banco.testes.Api;
import br.com.kawashi.banco.testes.UsuarioDeTeste;
import br.com.kawashi.banco.testes.GeradorDeDocumentos;
import br.com.kawashi.banco.testes.IntegracaoApi;

@IntegracaoApi
class PessoaApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioDeTeste usuarios;
    private Api api;
    private long empresaId;
    private String pessoas;

    @BeforeEach
    void preparar() throws Exception {
        api = usuarios.api(mvc);
        empresaId = api.empresa();
        pessoas = "/api/empresas/" + empresaId + "/pessoas";
    }

    // ---- o tipo e a separacao ------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"CLIENTE", "COLABORADOR", "FORNECEDOR"})
    void criaPessoaDeCadaTipoSemParticipacao(String tipo) throws Exception {
        String cpf = GeradorDeDocumentos.cpf();
        api.post(pessoas, Api.pessoaJson(tipo.toLowerCase(), GeradorDeDocumentos.formatarCpf(cpf), null, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value(tipo))
                .andExpect(jsonPath("$.natureza").value("F"))
                .andExpect(jsonPath("$.documento").value(br.com.kawashi.banco.compartilhado.cifra.Mascaras.documento(cpf)))
                .andExpect(jsonPath("$.empresaId").value(empresaId))
                .andExpect(jsonPath("$.participacao").doesNotExist())
                .andExpect(jsonPath("$.ativa").value(true));
    }

    @Test
    void tipoForaDaListaEhRecusado() throws Exception {
        api.post(pessoas, Api.pessoaJson("ESTAGIARIO", GeradorDeDocumentos.cpf(), null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("tipo"));
    }

    @Test
    void clienteEFornecedorPodemSerPessoaJuridica() throws Exception {
        String cnpj = GeradorDeDocumentos.cnpj();
        api.post(pessoas, "{\"tipo\":\"FORNECEDOR\",\"natureza\":\"J\",\"documento\":\"" + cnpj + "\",\"nome\":\"Fornecedor LTDA\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.natureza").value("J"))
                .andExpect(jsonPath("$.documento").value(br.com.kawashi.banco.compartilhado.cifra.Mascaras.documento(cnpj)));
    }

    @Test
    void documentoPrecisaCombinarComANatureza() throws Exception {
        // CNPJ valido, mas a natureza diz pessoa fisica.
        api.post(pessoas, "{\"tipo\":\"CLIENTE\",\"natureza\":\"F\",\"documento\":\"" + GeradorDeDocumentos.cnpj() + "\",\"nome\":\"X\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("documento"))
                .andExpect(jsonPath("$.erros[0].mensagem").value("CPF inválido"));
        api.post(pessoas, "{\"tipo\":\"CLIENTE\",\"natureza\":\"J\",\"documento\":\"" + GeradorDeDocumentos.cpf() + "\",\"nome\":\"X\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].mensagem").value("CNPJ inválido"));
        String cpfErrado = GeradorDeDocumentos.comDigitoVerificadorErrado(GeradorDeDocumentos.cpf());
        api.post(pessoas, Api.pessoaJson("CLIENTE", cpfErrado, null, null)).andExpect(status().isBadRequest());
        api.post(pessoas, "{\"tipo\":\"CLIENTE\",\"natureza\":\"X\",\"documento\":\"" + GeradorDeDocumentos.cpf() + "\",\"nome\":\"X\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("natureza"));
    }

    @Test
    void mesmoDocumentoRepeteEmOutroTipoEEmOutraEmpresaMasNaoNoMesmoTipo() throws Exception {
        String cpf = GeradorDeDocumentos.cpf();
        api.criar(pessoas, Api.pessoaJson("CLIENTE", cpf, null, null));
        api.post(pessoas, Api.pessoaJson("CLIENTE", cpf, null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("um cliente")));
        // a mesma pessoa tambem e colaboradora desta empresa: outra linha
        api.criar(pessoas, Api.pessoaJson("COLABORADOR", cpf, null, "Analista"));
        // e cliente de outra empresa: outra linha
        api.criar("/api/empresas/" + api.empresa() + "/pessoas", Api.pessoaJson("CLIENTE", cpf, null, null));
        api.entrarNa(empresaId);
        api.get(pessoas).andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void soEmpresaExistenteEAtivaRecebePessoa() throws Exception {
        api.post("/api/empresas/999999999/pessoas", Api.pessoaJson("CLIENTE", GeradorDeDocumentos.cpf(), null, null))
                .andExpect(status().isNotFound());
        api.get("/api/empresas/999999999/pessoas").andExpect(status().isNotFound());
        // Empresa inativada: a sessao cai antes de chegar a regra (o servico ainda recusaria com 422).
        usuarios.apiDaPlataforma(mvc).post("/api/plataforma/empresas/" + empresaId + "/inativar").andExpect(status().isOk());
        api.post(pessoas, Api.pessoaJson("CLIENTE", GeradorDeDocumentos.cpf(), null, null))
                .andExpect(status().isUnauthorized());
    }

    // ---- campos que dependem do tipo -----------------------------------------------------

    @Test
    void participacaoSoExisteParaSocioECargoSoParaColaborador() throws Exception {
        api.post(pessoas, Api.pessoaJson("SOCIO", GeradorDeDocumentos.cpf(), null, null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("participação")));
        api.post(pessoas, Api.pessoaJson("CLIENTE", GeradorDeDocumentos.cpf(), "10", null))
                .andExpect(status().isUnprocessableEntity());
        api.post(pessoas, Api.pessoaJson("SOCIO", GeradorDeDocumentos.cpf(), "10", "Diretor"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("cargo")));
        api.post(pessoas, Api.pessoaJson("COLABORADOR", GeradorDeDocumentos.cpf(), null, "  Analista  "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cargo").value("Analista"));
    }

    @Test
    void participacaoAceitaZerosADireitaERecusaTerceiraCasaDeVerdade() throws Exception {
        api.post(pessoas, Api.pessoaJson("SOCIO", GeradorDeDocumentos.cpf(), "33.330", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participacao").value(33.33));
        api.post(pessoas, Api.pessoaJson("SOCIO", GeradorDeDocumentos.cpf(), "0.001", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("participacao"));
        api.post(pessoas, Api.pessoaJson("SOCIO", GeradorDeDocumentos.cpf(), "100.01", null))
                .andExpect(status().isBadRequest());
    }

    @Test
    void somaDasParticipacoesDosSociosNaoPassaDe100() throws Exception {
        api.socio(empresaId, "60");
        api.post(pessoas, Api.pessoaJson("SOCIO", GeradorDeDocumentos.cpf(), "40.01", null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("40.00%")));
        long segundo = api.socio(empresaId, "40");
        // quem nao e socio nao entra na soma
        api.pessoa(empresaId, "CLIENTE");
        // na atualizacao a soma desconta o proprio socio: 40 -> 35 cabe, 40 -> 41 nao
        String cpf = com.jayway.jsonpath.JsonPath.read(Api.corpo(api.get("/api/pessoas/" + segundo)), "$.documento");
        api.put("/api/pessoas/" + segundo, Api.pessoaJson("SOCIO", cpf, "35", null))
                .andExpect(status().isOk()).andExpect(jsonPath("$.participacao").value(35.0));
        api.put("/api/pessoas/" + segundo, Api.pessoaJson("SOCIO", cpf, "41", null))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void socioRepetidoRespondeConflitoMesmoQuandoASomaTambemEstoura() throws Exception {
        String cpf = GeradorDeDocumentos.cpf();
        api.criar(pessoas, Api.pessoaJson("SOCIO", cpf, "60", null));
        api.post(pessoas, Api.pessoaJson("SOCIO", cpf, "50", null)).andExpect(status().isConflict());
    }

    // ---- atualizar, listar, excluir ------------------------------------------------------

    @Test
    void tipoNaturezaEDocumentoNaoMudam() throws Exception {
        String cpf = GeradorDeDocumentos.cpf();
        long id = api.criar(pessoas, Api.pessoaJson("CLIENTE", cpf, null, null));
        api.put("/api/pessoas/" + id, "{\"tipo\":\"CLIENTE\",\"natureza\":\"F\",\"documento\":\"" + cpf + "\",\"nome\":\"Nome Novo\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.nome").value("Nome Novo"));
        api.put("/api/pessoas/" + id, Api.pessoaJson("FORNECEDOR", cpf, null, null)).andExpect(status().isUnprocessableEntity());
        api.put("/api/pessoas/" + id, Api.pessoaJson("CLIENTE", GeradorDeDocumentos.cpf(), null, null))
                .andExpect(status().isUnprocessableEntity());
        api.get("/api/pessoas/999999999").andExpect(status().isNotFound());
    }

    @Test
    void listaPorTipoEPorSituacao() throws Exception {
        api.pessoa(empresaId, "CLIENTE");
        long colaborador = api.pessoa(empresaId, "COLABORADOR");
        api.socio(empresaId, "10");
        api.post("/api/pessoas/" + colaborador + "/inativar").andExpect(jsonPath("$.ativa").value(false));

        api.get(pessoas).andExpect(jsonPath("$.total").value(3));
        api.get(pessoas + "?tipo=socio").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.itens[0].tipo").value("SOCIO"));
        api.get(pessoas + "?ativa=false").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.itens[0].id").value(colaborador));
        api.get(pessoas + "?tipo=INVENTADO").andExpect(status().isBadRequest());
        api.post("/api/pessoas/" + colaborador + "/reativar").andExpect(jsonPath("$.ativa").value(true));
    }

    @Test
    void pessoaComContatoOuEnderecoNaoEhExcluida() throws Exception {
        long id = api.pessoa(empresaId, "CLIENTE");
        long contato = api.criar("/api/pessoas/" + id + "/contatos", "{\"tipo\":\"TELEFONE\",\"valor\":\"(11) 98765-4321\"}");
        api.delete("/api/pessoas/" + id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("1 contato")));
        api.delete("/api/contatos/" + contato).andExpect(status().isNoContent());
        api.delete("/api/pessoas/" + id).andExpect(status().isNoContent());
        api.get("/api/pessoas/" + id).andExpect(status().isNotFound());
    }
}
