package br.com.kawashi.banco.empresa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import br.com.kawashi.banco.testes.GeradorDeDocumentos;
import br.com.kawashi.banco.testes.IntegracaoApi;
import jakarta.persistence.EntityManager;

@IntegracaoApi
class EmpresaApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    @Autowired
    private EmpresaRepository empresas;

    // ---- apoio ------------------------------------------------------------------

    private static String json(String cnpj, String razaoSocial, String nomeFantasia) {
        String fantasia = nomeFantasia == null ? "null" : "\"" + nomeFantasia + "\"";
        return """
                {"cnpj": "%s", "razaoSocial": "%s", "nomeFantasia": %s}
                """.formatted(cnpj, razaoSocial, fantasia);
    }

    private static String jsonCliente(String cnpj, String razaoSocial) {
        return """
                {"cnpj": "%s", "razaoSocial": "%s", "nomeFantasia": null}
                """.formatted(cnpj, razaoSocial);
    }

    private long criarCliente() throws Exception {
        String corpo = mvc.perform(post("/api/clientes").contentType(MediaType.APPLICATION_JSON)
                        .content(jsonCliente(GeradorDeDocumentos.cnpj(), "Cliente Pai LTDA")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(corpo, "$.id")).longValue();
    }

    private long criarEmpresa(long clienteId, String cnpj, String razaoSocial) throws Exception {
        String corpo = mvc.perform(post("/api/clientes/{clienteId}/empresas", clienteId)
                        .contentType(MediaType.APPLICATION_JSON).content(json(cnpj, razaoSocial, null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(corpo, "$.id")).longValue();
    }

    private long criarEmpresa(long clienteId) throws Exception {
        return criarEmpresa(clienteId, GeradorDeDocumentos.cnpj(), "Empresa Filha LTDA");
    }

    private void inserirFilial(long empresaId, String cnpjDaMatriz) {
        String cnpjFilial = GeradorDeDocumentos.cnpj(cnpjDaMatriz.substring(0, 8), "0002");
        jdbc.update("INSERT INTO FILIAL (EMPRESA_ID, CNPJ, NOME) VALUES (?, ?, ?)", empresaId, cnpjFilial, "Filial de teste");
    }

    private void inserirSocio(long empresaId) {
        jdbc.update("INSERT INTO SOCIO (EMPRESA_ID, NOME, CPF, PARTICIPACAO) VALUES (?, ?, ?, ?)",
                empresaId, "Sócio de teste", GeradorDeDocumentos.cpf(), 50);
    }

    // ---- criar ------------------------------------------------------------------

    @Test
    void criaEmpresaComCnpjFormatadoEGuardaSoDigitos() throws Exception {
        long clienteId = criarCliente();
        String cnpj = GeradorDeDocumentos.cnpj();

        mvc.perform(post("/api/clientes/{clienteId}/empresas", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.formatarCnpj(cnpj), "Padaria Pão Quente LTDA", "Pão Quente")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/empresas/")))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.clienteId").value(clienteId))
                .andExpect(jsonPath("$.cnpj").value(cnpj))
                .andExpect(jsonPath("$.razaoSocial").value("Padaria Pão Quente LTDA"))
                .andExpect(jsonPath("$.nomeFantasia").value("Pão Quente"))
                .andExpect(jsonPath("$.ativa").value(true))
                .andExpect(jsonPath("$.criadoEm").exists())
                .andExpect(jsonPath("$.atualizadoEm").exists());
    }

    @Test
    void headerLocationApontaParaOCaminhoPlanoDaEmpresaCriada() throws Exception {
        long clienteId = criarCliente();
        MvcResult resultado = mvc.perform(post("/api/clientes/{clienteId}/empresas", clienteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.cnpj(), "Localizada LTDA", null)))
                .andExpect(status().isCreated())
                .andReturn();
        long id = ((Number) JsonPath.read(resultado.getResponse().getContentAsString(), "$.id")).longValue();

        // o Location nao e aninhado no cliente: aponta para o caminho plano da empresa
        assertThat(resultado.getResponse().getHeader("Location")).endsWith("/api/empresas/" + id);
        mvc.perform(get(resultado.getResponse().getHeader("Location")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void normalizaEntradaAparandoRazaoSocialEGuardandoFantasiaEmBrancoComoNulo() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(post("/api/clientes/{clienteId}/empresas", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.cnpj(), "  Com Espaços LTDA  ", "   ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.razaoSocial").value("Com Espaços LTDA"))
                .andExpect(jsonPath("$.nomeFantasia").value(nullValue()));
    }

    @Test
    void naoCriaEmpresaParaClienteQueNaoExiste() throws Exception {
        mvc.perform(post("/api/clientes/{clienteId}/empresas", 999_999_999L).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.cnpj(), "Sem Pai LTDA", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Não encontrado"))
                .andExpect(jsonPath("$.detail").value(containsString("Cliente")));
    }

    @Test
    void naoCriaEmpresaParaClienteInativo() throws Exception {
        long clienteId = criarCliente();
        mvc.perform(post("/api/clientes/{id}/inativar", clienteId)).andExpect(status().isOk());

        mvc.perform(post("/api/clientes/{clienteId}/empresas", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.cnpj(), "Pai Inativo LTDA", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Regra de negócio violada"))
                .andExpect(jsonPath("$.detail").value(containsString("inativo")));

        // reativado o cliente, o cadastro volta a ser permitido
        mvc.perform(post("/api/clientes/{id}/reativar", clienteId)).andExpect(status().isOk());
        criarEmpresa(clienteId);
    }

    @Test
    void recusaCnpjQueNaoEDeMatrizEIndicaOCaminhoDasFiliais() throws Exception {
        long clienteId = criarCliente();
        String filial = GeradorDeDocumentos.cnpj(GeradorDeDocumentos.raizDeCnpj(), "0002");

        mvc.perform(post("/api/clientes/{clienteId}/empresas", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.formatarCnpj(filial), "Isto E Filial LTDA", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Regra de negócio violada"))
                .andExpect(jsonPath("$.detail").value(containsString("matriz")))
                .andExpect(jsonPath("$.detail").value(containsString("0001")))
                .andExpect(jsonPath("$.detail").value(containsString("/api/empresas/{id}/filiais")));
    }

    @Test
    void recusaCnpjDuplicadoComConflitoMesmoParaOutroCliente() throws Exception {
        long clienteA = criarCliente();
        long clienteB = criarCliente();
        String cnpj = GeradorDeDocumentos.cnpj();
        criarEmpresa(clienteA, cnpj, "Primeira LTDA");

        mvc.perform(post("/api/clientes/{clienteId}/empresas", clienteA).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.formatarCnpj(cnpj), "Segunda LTDA", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflito"))
                .andExpect(jsonPath("$.detail").value(containsString(cnpj)));

        mvc.perform(post("/api/clientes/{clienteId}/empresas", clienteB).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpj, "Terceira LTDA", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString(cnpj)));
    }

    @Test
    void verificaAsRegrasNaOrdemClienteDepoisMatrizDepoisDuplicidade() throws Exception {
        long clienteId = criarCliente();
        String cnpjDeFilialJaCadastradoComoEmpresa = GeradorDeDocumentos.cnpj(GeradorDeDocumentos.raizDeCnpj(), "0002");
        // mesmo que o CNPJ ja existisse, a regra da matriz vem antes da checagem de duplicidade
        jdbc.update("INSERT INTO EMPRESA (CLIENTE_ID, CNPJ, RAZAO_SOCIAL) VALUES (?, ?, ?)", clienteId,
                cnpjDeFilialJaCadastradoComoEmpresa, "Dado legado");

        mvc.perform(post("/api/clientes/{clienteId}/empresas", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpjDeFilialJaCadastradoComoEmpresa, "Legado LTDA", null)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void recusaCnpjComDigitoVerificadorErrado() throws Exception {
        long clienteId = criarCliente();
        String invalido = GeradorDeDocumentos.comDigitoVerificadorErrado(GeradorDeDocumentos.cnpj());

        mvc.perform(post("/api/clientes/{clienteId}/empresas", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalido, "Empresa X", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Dados inválidos"))
                .andExpect(jsonPath("$.erros[0].campo").value("cnpj"))
                .andExpect(jsonPath("$.erros[0].mensagem").value("CNPJ inválido"));
    }

    @Test
    void recusaCnpjAusenteOuEmBranco() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(post("/api/clientes/{clienteId}/empresas", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"razaoSocial": "Sem CNPJ LTDA"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("cnpj"))
                .andExpect(jsonPath("$.erros[0].mensagem").value("O CNPJ é obrigatório"));

        mvc.perform(post("/api/clientes/{clienteId}/empresas", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json("  ", "CNPJ Branco LTDA", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("cnpj"));
    }

    @Test
    void recusaRazaoSocialEmBrancoOuLongaDemais() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(post("/api/clientes/{clienteId}/empresas", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.cnpj(), "  ", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("razaoSocial"))
                .andExpect(jsonPath("$.erros[0].mensagem").value("A razão social é obrigatória"));

        mvc.perform(post("/api/clientes/{clienteId}/empresas", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.cnpj(), "x".repeat(201), null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("razaoSocial"));
    }

    @Test
    void recusaNomeFantasiaLongoDemais() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(post("/api/clientes/{clienteId}/empresas", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.cnpj(), "Empresa Y", "y".repeat(201))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("nomeFantasia"));
    }

    @Test
    void aceitaRazaoSocialENomeFantasiaNoLimiteDe200Caracteres() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(post("/api/clientes/{clienteId}/empresas", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.cnpj(), "r".repeat(200), "f".repeat(200))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.razaoSocial").value("r".repeat(200)))
                .andExpect(jsonPath("$.nomeFantasia").value("f".repeat(200)));
    }

    @Test
    void recusaCorpoQueNaoEJson() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(post("/api/clientes/{clienteId}/empresas", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content("{isto nao e json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Corpo da requisição ilegível"));
    }

    @Test
    void clienteIdComTipoErradoViraErro400() throws Exception {
        mvc.perform(get("/api/clientes/{clienteId}/empresas", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(endsWith("tem um valor com tipo errado.")));
    }

    // ---- buscar -----------------------------------------------------------------

    @Test
    void buscaPorIdEDevolve404QuandoNaoExiste() throws Exception {
        long clienteId = criarCliente();
        long id = criarEmpresa(clienteId, GeradorDeDocumentos.cnpj(), "Busca LTDA");

        mvc.perform(get("/api/empresas/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.clienteId").value(clienteId))
                .andExpect(jsonPath("$.razaoSocial").value("Busca LTDA"))
                .andExpect(jsonPath("$.ativa").value(true));

        mvc.perform(get("/api/empresas/{id}", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Não encontrado"))
                .andExpect(jsonPath("$.detail").value(containsString("Empresa")));
    }

    // ---- listar -----------------------------------------------------------------

    @Test
    void listaComPaginacaoOrdenadaPorIdPorPadrao() throws Exception {
        long clienteId = criarCliente();
        criarEmpresa(clienteId, GeradorDeDocumentos.cnpj(), "Alfa LTDA");
        criarEmpresa(clienteId, GeradorDeDocumentos.cnpj(), "Beta LTDA");
        criarEmpresa(clienteId, GeradorDeDocumentos.cnpj(), "Gama LTDA");

        mvc.perform(get("/api/clientes/{clienteId}/empresas", clienteId).param("size", "2").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPaginas").value(2))
                .andExpect(jsonPath("$.pagina").value(0))
                .andExpect(jsonPath("$.tamanho").value(2))
                .andExpect(jsonPath("$.itens.length()").value(2))
                .andExpect(jsonPath("$.itens[0].razaoSocial").value("Alfa LTDA"))
                .andExpect(jsonPath("$.itens[1].razaoSocial").value("Beta LTDA"))
                .andExpect(jsonPath("$.itens[0].clienteId").value(clienteId));

        mvc.perform(get("/api/clientes/{clienteId}/empresas", clienteId).param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andExpect(jsonPath("$.itens[0].razaoSocial").value("Gama LTDA"));
    }

    @Test
    void listaUsaTamanhoDePaginaPadraoDe20() throws Exception {
        long clienteId = criarCliente();
        criarEmpresa(clienteId);

        mvc.perform(get("/api/clientes/{clienteId}/empresas", clienteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tamanho").value(20))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void listaAceitaOrdenacaoEscolhidaPeloCliente() throws Exception {
        long clienteId = criarCliente();
        criarEmpresa(clienteId, GeradorDeDocumentos.cnpj(), "Alfa LTDA");
        criarEmpresa(clienteId, GeradorDeDocumentos.cnpj(), "Gama LTDA");

        mvc.perform(get("/api/clientes/{clienteId}/empresas", clienteId).param("sort", "razaoSocial,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].razaoSocial").value("Gama LTDA"))
                .andExpect(jsonPath("$.itens[1].razaoSocial").value("Alfa LTDA"));
    }

    @Test
    void recusaOrdenarPorCampoQueNaoExiste() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(get("/api/clientes/{clienteId}/empresas", clienteId).param("sort", "campoInexistente,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Ordenação inválida"));
    }

    @Test
    void listaSoMostraAsEmpresasDoClienteDoCaminho() throws Exception {
        long clienteA = criarCliente();
        long clienteB = criarCliente();
        long empresaDeA = criarEmpresa(clienteA, GeradorDeDocumentos.cnpj(), "Empresa de A LTDA");
        criarEmpresa(clienteB, GeradorDeDocumentos.cnpj(), "Empresa de B LTDA");

        mvc.perform(get("/api/clientes/{clienteId}/empresas", clienteA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.itens[0].id").value(empresaDeA))
                .andExpect(jsonPath("$.itens[0].razaoSocial").value("Empresa de A LTDA"));

        mvc.perform(get("/api/clientes/{clienteId}/empresas", clienteB))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.itens[0].razaoSocial").value("Empresa de B LTDA"));
    }

    @Test
    void clienteSemEmpresasDevolveListaVazia() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(get("/api/clientes/{clienteId}/empresas", clienteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.totalPaginas").value(0))
                .andExpect(jsonPath("$.itens.length()").value(0));
    }

    @Test
    void listaDeClienteQueNaoExisteDevolve404EmVezDeListaVazia() throws Exception {
        mvc.perform(get("/api/clientes/{clienteId}/empresas", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Não encontrado"))
                .andExpect(jsonPath("$.detail").value(containsString("Cliente")));
    }

    @Test
    void clienteInativoAindaPodeTerSuasEmpresasListadas() throws Exception {
        long clienteId = criarCliente();
        criarEmpresa(clienteId);
        mvc.perform(post("/api/clientes/{id}/inativar", clienteId)).andExpect(status().isOk());

        mvc.perform(get("/api/clientes/{clienteId}/empresas", clienteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    // ---- atualizar --------------------------------------------------------------

    @Test
    void atualizaRazaoSocialENomeFantasiaMasNaoAceitaTrocarOCnpj() throws Exception {
        long clienteId = criarCliente();
        String cnpj = GeradorDeDocumentos.cnpj();
        long id = criarEmpresa(clienteId, cnpj, "Nome Antigo LTDA");

        mvc.perform(put("/api/empresas/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpj, "Nome Novo LTDA", "Fantasia Nova")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.clienteId").value(clienteId))
                .andExpect(jsonPath("$.cnpj").value(cnpj))
                .andExpect(jsonPath("$.razaoSocial").value("Nome Novo LTDA"))
                .andExpect(jsonPath("$.nomeFantasia").value("Fantasia Nova"));

        mvc.perform(get("/api/empresas/{id}", id))
                .andExpect(jsonPath("$.razaoSocial").value("Nome Novo LTDA"));

        mvc.perform(put("/api/empresas/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.cnpj(), "Nome Novo LTDA", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Regra de negócio violada"))
                .andExpect(jsonPath("$.detail").value(containsString("CNPJ")));

        mvc.perform(get("/api/empresas/{id}", id))
                .andExpect(jsonPath("$.cnpj").value(cnpj));
    }

    @Test
    void atualizacaoAceitaOMesmoCnpjComPontuacaoEPermiteLimparONomeFantasia() throws Exception {
        long clienteId = criarCliente();
        String cnpj = GeradorDeDocumentos.cnpj();
        long id = criarEmpresa(clienteId, cnpj, "Fantasia Some LTDA");
        mvc.perform(put("/api/empresas/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpj, "Fantasia Some LTDA", "Tem Fantasia")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nomeFantasia").value("Tem Fantasia"));

        mvc.perform(put("/api/empresas/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.formatarCnpj(cnpj), "  Fantasia Some LTDA  ", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cnpj").value(cnpj))
                .andExpect(jsonPath("$.razaoSocial").value("Fantasia Some LTDA"))
                .andExpect(jsonPath("$.nomeFantasia").value(nullValue()));
    }

    @Test
    void atualizacaoDeEmpresaQueNaoExisteDevolve404() throws Exception {
        mvc.perform(put("/api/empresas/{id}", 999_999_999L).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.cnpj(), "Fantasma LTDA", null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void atualizacaoValidaOsCamposDoCorpo() throws Exception {
        long clienteId = criarCliente();
        String cnpj = GeradorDeDocumentos.cnpj();
        long id = criarEmpresa(clienteId, cnpj, "Valida Update LTDA");

        mvc.perform(put("/api/empresas/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpj, "", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("razaoSocial"));

        mvc.perform(put("/api/empresas/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.comDigitoVerificadorErrado(cnpj), "Valida Update LTDA", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("cnpj"));
    }

    // ---- inativar e reativar ----------------------------------------------------

    @Test
    void inativaEReativaEmpresa() throws Exception {
        long clienteId = criarCliente();
        long id = criarEmpresa(clienteId);

        mvc.perform(post("/api/empresas/{id}/inativar", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativa").value(false));
        mvc.perform(get("/api/empresas/{id}", id)).andExpect(jsonPath("$.ativa").value(false));

        mvc.perform(post("/api/empresas/{id}/reativar", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativa").value(true));
        mvc.perform(get("/api/empresas/{id}", id)).andExpect(jsonPath("$.ativa").value(true));
    }

    @Test
    void inativarApenasTrocaASituacaoSemCascataParaOCliente() throws Exception {
        long clienteId = criarCliente();
        long id = criarEmpresa(clienteId);
        inserirFilial(id, empresaCnpj(id));
        inserirSocio(id);

        mvc.perform(post("/api/empresas/{id}/inativar", id)).andExpect(status().isOk());

        mvc.perform(get("/api/clientes/{id}", clienteId)).andExpect(jsonPath("$.ativo").value(true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM FILIAL WHERE EMPRESA_ID = ? AND ATIVA = TRUE", Long.class, id))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SOCIO WHERE EMPRESA_ID = ?", Long.class, id)).isEqualTo(1L);
    }

    @Test
    void inativarEReativarSaoIdempotentes() throws Exception {
        long clienteId = criarCliente();
        long id = criarEmpresa(clienteId);

        mvc.perform(post("/api/empresas/{id}/inativar", id)).andExpect(status().isOk());
        mvc.perform(post("/api/empresas/{id}/inativar", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativa").value(false));
        mvc.perform(post("/api/empresas/{id}/reativar", id)).andExpect(status().isOk());
        mvc.perform(post("/api/empresas/{id}/reativar", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativa").value(true));
    }

    @Test
    void inativarEReativarEmpresaQueNaoExisteDevolve404() throws Exception {
        mvc.perform(post("/api/empresas/{id}/inativar", 999_999_999L)).andExpect(status().isNotFound());
        mvc.perform(post("/api/empresas/{id}/reativar", 999_999_999L)).andExpect(status().isNotFound());
    }

    // ---- excluir ----------------------------------------------------------------

    @Test
    void excluiEmpresaSemVinculos() throws Exception {
        long clienteId = criarCliente();
        long id = criarEmpresa(clienteId);

        mvc.perform(delete("/api/empresas/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/empresas/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/clientes/{clienteId}/empresas", clienteId))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void excluirEmpresaQueNaoExisteDevolve404() throws Exception {
        mvc.perform(delete("/api/empresas/{id}", 999_999_999L)).andExpect(status().isNotFound());
    }

    @Test
    void naoExcluiEmpresaComFiliaisEInformaQuantas() throws Exception {
        long clienteId = criarCliente();
        long id = criarEmpresa(clienteId);
        String cnpj = empresaCnpj(id);
        inserirFilial(id, cnpj);
        jdbc.update("INSERT INTO FILIAL (EMPRESA_ID, CNPJ, NOME) VALUES (?, ?, ?)", id,
                GeradorDeDocumentos.cnpj(cnpj.substring(0, 8), "0003"), "Outra filial");

        mvc.perform(delete("/api/empresas/{id}", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflito"))
                .andExpect(jsonPath("$.detail").value(containsString("2 filial(is)")))
                .andExpect(jsonPath("$.detail").value(containsString("0 sócio(s)")));

        mvc.perform(get("/api/empresas/{id}", id)).andExpect(status().isOk());
    }

    @Test
    void naoExcluiEmpresaComSociosEInformaQuantos() throws Exception {
        long clienteId = criarCliente();
        long id = criarEmpresa(clienteId);
        inserirSocio(id);
        inserirSocio(id);
        inserirSocio(id);

        mvc.perform(delete("/api/empresas/{id}", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("0 filial(is)")))
                .andExpect(jsonPath("$.detail").value(containsString("3 sócio(s)")));

        mvc.perform(get("/api/empresas/{id}", id)).andExpect(status().isOk());
    }

    @Test
    void conflitoDeExclusaoInformaFiliaisESociosJuntos() throws Exception {
        long clienteId = criarCliente();
        long id = criarEmpresa(clienteId);
        inserirFilial(id, empresaCnpj(id));
        inserirSocio(id);
        inserirSocio(id);

        mvc.perform(delete("/api/empresas/{id}", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("1 filial(is), 2 sócio(s)")));
    }

    @Test
    void empresaPassaAPoderSerExcluidaQuandoOsVinculosSaem() throws Exception {
        long clienteId = criarCliente();
        long id = criarEmpresa(clienteId);
        inserirFilial(id, empresaCnpj(id));
        inserirSocio(id);
        mvc.perform(delete("/api/empresas/{id}", id)).andExpect(status().isConflict());

        jdbc.update("DELETE FROM FILIAL WHERE EMPRESA_ID = ?", id);
        jdbc.update("DELETE FROM SOCIO WHERE EMPRESA_ID = ?", id);

        mvc.perform(delete("/api/empresas/{id}", id)).andExpect(status().isNoContent());
    }

    @Test
    void empresaInativaComVinculosTambemNaoPodeSerExcluida() throws Exception {
        long clienteId = criarCliente();
        long id = criarEmpresa(clienteId);
        inserirSocio(id);
        mvc.perform(post("/api/empresas/{id}/inativar", id)).andExpect(status().isOk());

        mvc.perform(delete("/api/empresas/{id}", id)).andExpect(status().isConflict());
    }

    @Test
    void cnpjDeEmpresaExcluidaPodeSerCadastradoDeNovo() throws Exception {
        long clienteId = criarCliente();
        String cnpj = GeradorDeDocumentos.cnpj();
        long id = criarEmpresa(clienteId, cnpj, "Vai Embora LTDA");
        mvc.perform(delete("/api/empresas/{id}", id)).andExpect(status().isNoContent());

        criarEmpresa(clienteId, cnpj, "Voltou LTDA");
    }

    // ---- integracao com a exclusao de cliente -----------------------------------

    @Test
    void clienteComEmpresaNaoPodeSerExcluidoAteQueAEmpresaSeja() throws Exception {
        long clienteId = criarCliente();
        long empresaId = criarEmpresa(clienteId);

        mvc.perform(delete("/api/clientes/{id}", clienteId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflito"))
                .andExpect(jsonPath("$.detail").value(containsString("1 empresa(s)")));
        mvc.perform(get("/api/clientes/{id}", clienteId)).andExpect(status().isOk());

        mvc.perform(delete("/api/empresas/{id}", empresaId)).andExpect(status().isNoContent());

        mvc.perform(delete("/api/clientes/{id}", clienteId)).andExpect(status().isNoContent());
        mvc.perform(get("/api/clientes/{id}", clienteId)).andExpect(status().isNotFound());
    }

    // ---- entidade ---------------------------------------------------------------

    @Test
    void respostaMontaOClienteIdSemPrecisarCarregarOClienteForaDaTransacao() throws Exception {
        long clienteId = criarCliente();
        long id = criarEmpresa(clienteId);
        em.flush();
        em.clear();

        Empresa empresa = empresas.findById(id).orElseThrow();
        // solta a empresa e o proxy preguicoso do cliente da sessao, como acontece no controlador
        // (open-in-view esta desligado): o clienteId precisa sair sem consultar o banco de novo
        em.clear();

        assertThat(EmpresaResponse.de(empresa).clienteId()).isEqualTo(clienteId);
    }

    private String empresaCnpj(long id) throws Exception {
        String corpo = mvc.perform(get("/api/empresas/{id}", id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(corpo, "$.cnpj");
    }
}
