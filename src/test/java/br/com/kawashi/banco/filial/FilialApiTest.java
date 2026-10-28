package br.com.kawashi.banco.filial;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import br.com.kawashi.banco.cliente.Cliente;
import br.com.kawashi.banco.cliente.ClienteRepository;
import br.com.kawashi.banco.empresa.Empresa;
import br.com.kawashi.banco.empresa.EmpresaRepository;
import br.com.kawashi.banco.testes.GeradorDeDocumentos;
import br.com.kawashi.banco.testes.IntegracaoApi;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@IntegracaoApi
class FilialApiTest {

    private static final long ID_QUE_NAO_EXISTE = 999_999_999L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ClienteRepository clientes;

    @Autowired
    private EmpresaRepository empresas;

    @Autowired
    private FilialRepository filiais;

    @PersistenceContext
    private EntityManager em;

    // ---- apoio -------------------------------------------------------------------

    private static String json(String cnpj, String nome) {
        return """
                {"cnpj": "%s", "nome": "%s"}
                """.formatted(cnpj, nome);
    }

    /** Cria o cliente pela API e a empresa (matriz, ordem 0001, ativa) direto pelo repositorio. */
    private Empresa criarEmpresa() throws Exception {
        String corpoCliente = """
                {"cnpj": "%s", "razaoSocial": "Cliente Pai LTDA", "nomeFantasia": null}
                """.formatted(GeradorDeDocumentos.cnpj());
        String resposta = mvc.perform(post("/api/clientes").contentType(MediaType.APPLICATION_JSON).content(corpoCliente))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long clienteId = ((Number) JsonPath.read(resposta, "$.id")).longValue();
        Cliente cliente = clientes.findById(clienteId).orElseThrow();
        return empresas.save(new Empresa(cliente, GeradorDeDocumentos.cnpj(), "Empresa Mae LTDA", "Mae"));
    }

    private Empresa criarEmpresaInativa() throws Exception {
        Empresa empresa = criarEmpresa();
        empresa.inativar();
        return empresas.save(empresa);
    }

    private static String raiz(Empresa empresa) {
        return empresa.getCnpj().substring(0, 8);
    }

    /** CNPJ valido de filial (so digitos) com a raiz da empresa e a ordem informada. */
    private static String cnpjDeFilial(Empresa empresa, String ordem) {
        return GeradorDeDocumentos.cnpj(raiz(empresa), ordem);
    }

    private static String caminho(Empresa empresa) {
        return "/api/empresas/" + empresa.getId() + "/filiais";
    }

    private long criarFilial(Empresa empresa, String ordem, String nome) throws Exception {
        String corpo = mvc.perform(post(caminho(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpjDeFilial(empresa, ordem), nome)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(corpo, "$.id")).longValue();
    }

    // ---- criar: caminho feliz e normalizacao -------------------------------------

    @Test
    void criaFilialComCnpjFormatadoEGuardaSoDigitos() throws Exception {
        Empresa empresa = criarEmpresa();
        String cnpj = cnpjDeFilial(empresa, "0002");

        mvc.perform(post(caminho(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.formatarCnpj(cnpj), "Filial Centro")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/api/filiais/\\d+")))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.empresaId").value(empresa.getId()))
                .andExpect(jsonPath("$.cnpj").value(cnpj))
                .andExpect(jsonPath("$.nome").value("Filial Centro"))
                .andExpect(jsonPath("$.ativa").value(true))
                .andExpect(jsonPath("$.criadoEm").exists())
                .andExpect(jsonPath("$.atualizadoEm").exists());
    }

    @Test
    void locationApontaParaAFilialCriada() throws Exception {
        Empresa empresa = criarEmpresa();

        String localizacao = mvc.perform(post(caminho(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpjDeFilial(empresa, "0002"), "Filial Location")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");

        mvc.perform(get(localizacao))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Filial Location"));
    }

    @Test
    void removeEspacosDasBordasDoNome() throws Exception {
        Empresa empresa = criarEmpresa();

        mvc.perform(post(caminho(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpjDeFilial(empresa, "0002"), "   Filial Espacada   ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nome").value("Filial Espacada"));
    }

    @Test
    void aceitaVariasFiliaisDaMesmaEmpresaComOrdensDiferentes() throws Exception {
        Empresa empresa = criarEmpresa();

        criarFilial(empresa, "0002", "Filial 2");
        criarFilial(empresa, "0003", "Filial 3");
        criarFilial(empresa, "0150", "Filial 150");

        mvc.perform(get(caminho(empresa)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3));
    }

    // ---- criar: regras de negocio ------------------------------------------------

    @Test
    void naoCriaFilialParaEmpresaQueNaoExiste() throws Exception {
        mvc.perform(post("/api/empresas/{empresaId}/filiais", ID_QUE_NAO_EXISTE).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.cnpj(GeradorDeDocumentos.raizDeCnpj(), "0002"), "Orfa")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Não encontrado"))
                .andExpect(jsonPath("$.detail").value(containsString("Empresa")));
    }

    @Test
    void naoCriaFilialParaEmpresaInativa() throws Exception {
        Empresa empresa = criarEmpresaInativa();

        mvc.perform(post(caminho(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpjDeFilial(empresa, "0002"), "Filial Barrada")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Regra de negócio violada"))
                .andExpect(jsonPath("$.detail").value(containsString("inativa")));
    }

    @Test
    void criaFilialDepoisQueAEmpresaVoltaAFicarAtiva() throws Exception {
        Empresa empresa = criarEmpresaInativa();
        empresa.reativar();
        empresas.save(empresa);

        mvc.perform(post(caminho(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpjDeFilial(empresa, "0002"), "Filial Liberada")))
                .andExpect(status().isCreated());
    }

    @Test
    void recusaCnpjComRaizDiferenteDaEmpresa() throws Exception {
        Empresa empresa = criarEmpresa();
        String outraRaiz = GeradorDeDocumentos.raizDeCnpj();
        while (outraRaiz.equals(raiz(empresa))) {
            outraRaiz = GeradorDeDocumentos.raizDeCnpj();
        }

        mvc.perform(post(caminho(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.cnpj(outraRaiz, "0002"), "Filial Alheia")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Regra de negócio violada"))
                .andExpect(jsonPath("$.detail").value(containsString("raiz")))
                .andExpect(jsonPath("$.detail").value(containsString(raiz(empresa))))
                .andExpect(jsonPath("$.detail").value(containsString(outraRaiz)));
    }

    @Test
    void recusaOrdemDaMatrizNaFilial() throws Exception {
        Empresa empresa = criarEmpresa();

        // Mesma raiz da empresa e ordem 0001: e o proprio CNPJ da matriz.
        mvc.perform(post(caminho(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpjDeFilial(empresa, "0001"), "Filial Falsa Matriz")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Regra de negócio violada"))
                .andExpect(jsonPath("$.detail").value(containsString("0001")))
                .andExpect(jsonPath("$.detail").value(containsString("matriz")));
    }

    @Test
    void recusaOrdemDaMatrizMesmoComPontuacao() throws Exception {
        Empresa empresa = criarEmpresa();

        mvc.perform(post(caminho(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.formatarCnpj(cnpjDeFilial(empresa, "0001")), "Filial Falsa Matriz")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("matriz")));
    }

    @Test
    void recusaCnpjDuplicadoComConflito() throws Exception {
        Empresa empresa = criarEmpresa();
        String cnpj = cnpjDeFilial(empresa, "0002");
        criarFilial(empresa, "0002", "Primeira");

        mvc.perform(post(caminho(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.formatarCnpj(cnpj), "Segunda")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflito"))
                .andExpect(jsonPath("$.detail").value(containsString(cnpj)));
    }

    // ---- criar: validacao de campos ----------------------------------------------

    @Test
    void recusaCnpjComDigitoVerificadorErrado() throws Exception {
        Empresa empresa = criarEmpresa();
        String invalido = GeradorDeDocumentos.comDigitoVerificadorErrado(cnpjDeFilial(empresa, "0002"));

        mvc.perform(post(caminho(empresa)).contentType(MediaType.APPLICATION_JSON).content(json(invalido, "Filial X")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Dados inválidos"))
                .andExpect(jsonPath("$.erros[0].campo").value("cnpj"))
                .andExpect(jsonPath("$.erros[0].mensagem").value("CNPJ inválido"));
    }

    @Test
    void recusaCnpjAusente() throws Exception {
        Empresa empresa = criarEmpresa();

        mvc.perform(post(caminho(empresa)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"nome": "Sem CNPJ"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("cnpj"))
                .andExpect(jsonPath("$.erros[0].mensagem").value("O CNPJ é obrigatório"));
    }

    @Test
    void recusaNomeEmBranco() throws Exception {
        Empresa empresa = criarEmpresa();

        mvc.perform(post(caminho(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpjDeFilial(empresa, "0002"), "   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("nome"))
                .andExpect(jsonPath("$.erros[0].mensagem").value("O nome é obrigatório"));
    }

    @Test
    void recusaNomeComMaisDe150Caracteres() throws Exception {
        Empresa empresa = criarEmpresa();

        mvc.perform(post(caminho(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpjDeFilial(empresa, "0002"), "x".repeat(151))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("nome"));
    }

    @Test
    void aceitaNomeComExatamente150Caracteres() throws Exception {
        Empresa empresa = criarEmpresa();

        mvc.perform(post(caminho(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpjDeFilial(empresa, "0002"), "x".repeat(150))))
                .andExpect(status().isCreated());
    }

    @Test
    void recusaCorpoQueNaoEJson() throws Exception {
        Empresa empresa = criarEmpresa();

        mvc.perform(post(caminho(empresa)).contentType(MediaType.APPLICATION_JSON).content("{isto nao e json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Corpo da requisição ilegível"));
    }

    @Test
    void idDeEmpresaComTipoErradoViraErro400() throws Exception {
        mvc.perform(get("/api/empresas/{empresaId}/filiais", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(endsWith("tem um valor com tipo errado.")));
    }

    // ---- buscar ------------------------------------------------------------------

    @Test
    void buscaPorIdEDevolve404QuandoNaoExiste() throws Exception {
        Empresa empresa = criarEmpresa();
        long id = criarFilial(empresa, "0002", "Filial Busca");

        mvc.perform(get("/api/filiais/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.empresaId").value(empresa.getId()))
                .andExpect(jsonPath("$.cnpj").value(cnpjDeFilial(empresa, "0002")))
                .andExpect(jsonPath("$.nome").value("Filial Busca"))
                .andExpect(jsonPath("$.ativa").value(true));

        mvc.perform(get("/api/filiais/{id}", ID_QUE_NAO_EXISTE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Não encontrado"))
                .andExpect(jsonPath("$.detail").value(containsString("Filial")));
    }

    @Test
    void idDeFilialComTipoErradoViraErro400() throws Exception {
        mvc.perform(get("/api/filiais/{id}", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(endsWith("tem um valor com tipo errado.")));
    }

    @Test
    void devolveOIdDaEmpresaSemCarregarAEmpresaInteira() throws Exception {
        // open-in-view esta desligado: depois que a transacao do servico acaba, o controlador monta a
        // resposta com a empresa ainda como proxy. Isto garante que pegar o id nao precisa de sessao.
        Empresa empresa = criarEmpresa();
        long id = criarFilial(empresa, "0002", "Filial Proxy");
        em.flush();
        em.clear();

        Filial filial = filiais.findById(id).orElseThrow();

        assertFalse(Hibernate.isInitialized(filial.getEmpresa()));
        assertEquals(empresa.getId(), filial.getEmpresa().getId());
        assertFalse(Hibernate.isInitialized(filial.getEmpresa()));

        mvc.perform(get("/api/filiais/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.empresaId").value(empresa.getId()));
    }

    // ---- atualizar ---------------------------------------------------------------

    @Test
    void atualizaONomeEMantemOCnpj() throws Exception {
        Empresa empresa = criarEmpresa();
        String cnpj = cnpjDeFilial(empresa, "0002");
        long id = criarFilial(empresa, "0002", "Nome Antigo");

        mvc.perform(put("/api/filiais/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpj, "  Nome Novo  ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.cnpj").value(cnpj))
                .andExpect(jsonPath("$.nome").value("Nome Novo"));

        mvc.perform(get("/api/filiais/{id}", id))
                .andExpect(jsonPath("$.nome").value("Nome Novo"));
    }

    @Test
    void aceitaOMesmoCnpjComPontuacaoNaAtualizacao() throws Exception {
        Empresa empresa = criarEmpresa();
        String cnpj = cnpjDeFilial(empresa, "0002");
        long id = criarFilial(empresa, "0002", "Nome Antigo");

        mvc.perform(put("/api/filiais/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.formatarCnpj(cnpj), "Nome Novo")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cnpj").value(cnpj))
                .andExpect(jsonPath("$.nome").value("Nome Novo"));
    }

    @Test
    void recusaTrocarOCnpjDaFilial() throws Exception {
        Empresa empresa = criarEmpresa();
        long id = criarFilial(empresa, "0002", "Filial Fixa");

        // Outro CNPJ, ate mesmo valido e com a raiz certa: CNPJ de filial nao muda.
        mvc.perform(put("/api/filiais/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpjDeFilial(empresa, "0003"), "Filial Fixa")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Regra de negócio violada"))
                .andExpect(jsonPath("$.detail").value(containsString("CNPJ")));

        mvc.perform(get("/api/filiais/{id}", id))
                .andExpect(jsonPath("$.cnpj").value(cnpjDeFilial(empresa, "0002")));
    }

    @Test
    void recusaTrocarOCnpjParaOutraRaiz() throws Exception {
        Empresa empresa = criarEmpresa();
        long id = criarFilial(empresa, "0002", "Filial Fixa");

        mvc.perform(put("/api/filiais/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.cnpj(GeradorDeDocumentos.raizDeCnpj(), "0002"), "Filial Fixa")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("CNPJ")));
    }

    @Test
    void atualizacaoValidaOsCamposEDevolve404ParaFilialInexistente() throws Exception {
        Empresa empresa = criarEmpresa();
        String cnpj = cnpjDeFilial(empresa, "0002");
        long id = criarFilial(empresa, "0002", "Filial Valida");

        mvc.perform(put("/api/filiais/{id}", id).contentType(MediaType.APPLICATION_JSON).content(json(cnpj, "  ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("nome"));

        mvc.perform(put("/api/filiais/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.comDigitoVerificadorErrado(cnpj), "Filial Valida")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("cnpj"));

        mvc.perform(put("/api/filiais/{id}", ID_QUE_NAO_EXISTE).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpj, "Fantasma")))
                .andExpect(status().isNotFound());
    }

    // ---- inativar e reativar -----------------------------------------------------

    @Test
    void inativaEReativaFilial() throws Exception {
        Empresa empresa = criarEmpresa();
        long id = criarFilial(empresa, "0002", "Liga Desliga");

        mvc.perform(post("/api/filiais/{id}/inativar", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.ativa").value(false))
                .andExpect(jsonPath("$.nome").value("Liga Desliga"));
        mvc.perform(get("/api/filiais/{id}", id)).andExpect(jsonPath("$.ativa").value(false));

        mvc.perform(post("/api/filiais/{id}/reativar", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativa").value(true));
        mvc.perform(get("/api/filiais/{id}", id)).andExpect(jsonPath("$.ativa").value(true));
    }

    @Test
    void inativarEReativarRepetidosNaoDaErro() throws Exception {
        Empresa empresa = criarEmpresa();
        long id = criarFilial(empresa, "0002", "Idempotente");

        mvc.perform(post("/api/filiais/{id}/reativar", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativa").value(true));

        mvc.perform(post("/api/filiais/{id}/inativar", id)).andExpect(status().isOk());
        mvc.perform(post("/api/filiais/{id}/inativar", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativa").value(false));
    }

    @Test
    void inativarEReativarDevolvem404ParaFilialInexistente() throws Exception {
        mvc.perform(post("/api/filiais/{id}/inativar", ID_QUE_NAO_EXISTE)).andExpect(status().isNotFound());
        mvc.perform(post("/api/filiais/{id}/reativar", ID_QUE_NAO_EXISTE)).andExpect(status().isNotFound());
    }

    @Test
    void inativarEReativarSoTrocamASituacaoMesmoComEmpresaInativa() throws Exception {
        Empresa empresa = criarEmpresa();
        long id = criarFilial(empresa, "0002", "Filial Resistente");
        empresa.inativar();
        empresas.save(empresa);

        mvc.perform(post("/api/filiais/{id}/inativar", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativa").value(false));
        mvc.perform(post("/api/filiais/{id}/reativar", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativa").value(true));

        // Atualizar o nome tambem continua permitido: so a CRIACAO exige empresa ativa.
        mvc.perform(put("/api/filiais/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpjDeFilial(empresa, "0002"), "Filial Renomeada")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Filial Renomeada"));
    }

    // ---- excluir -----------------------------------------------------------------

    @Test
    void excluiFilial() throws Exception {
        Empresa empresa = criarEmpresa();
        long id = criarFilial(empresa, "0002", "Descartavel");

        mvc.perform(delete("/api/filiais/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/filiais/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(get(caminho(empresa)))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void excluirFilialInexistenteDevolve404() throws Exception {
        mvc.perform(delete("/api/filiais/{id}", ID_QUE_NAO_EXISTE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Não encontrado"));
    }

    @Test
    void depoisDeExcluirOCnpjPodeSerUsadoDeNovo() throws Exception {
        Empresa empresa = criarEmpresa();
        long id = criarFilial(empresa, "0002", "Primeira Vida");
        mvc.perform(delete("/api/filiais/{id}", id)).andExpect(status().isNoContent());
        em.flush();

        mvc.perform(post(caminho(empresa)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpjDeFilial(empresa, "0002"), "Segunda Vida")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nome").value("Segunda Vida"));
    }

    @Test
    void excluiFilialMesmoComEmpresaInativa() throws Exception {
        Empresa empresa = criarEmpresa();
        long id = criarFilial(empresa, "0002", "Ultima");
        empresa.inativar();
        empresas.save(empresa);

        mvc.perform(delete("/api/filiais/{id}", id)).andExpect(status().isNoContent());
    }

    // ---- listar ------------------------------------------------------------------

    @Test
    void listaComPaginacaoOrdenadaPorIdPorPadrao() throws Exception {
        Empresa empresa = criarEmpresa();
        criarFilial(empresa, "0002", "Filial A");
        criarFilial(empresa, "0003", "Filial B");
        criarFilial(empresa, "0004", "Filial C");

        mvc.perform(get(caminho(empresa)).param("size", "2").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPaginas").value(2))
                .andExpect(jsonPath("$.pagina").value(0))
                .andExpect(jsonPath("$.tamanho").value(2))
                .andExpect(jsonPath("$.itens.length()").value(2))
                .andExpect(jsonPath("$.itens[0].nome").value("Filial A"))
                .andExpect(jsonPath("$.itens[1].nome").value("Filial B"))
                .andExpect(jsonPath("$.itens[0].empresaId").value(empresa.getId()));

        mvc.perform(get(caminho(empresa)).param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagina").value(1))
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andExpect(jsonPath("$.itens[0].nome").value("Filial C"));
    }

    @Test
    void listaComTamanhoPadraoDe20() throws Exception {
        Empresa empresa = criarEmpresa();
        criarFilial(empresa, "0002", "Unica");

        mvc.perform(get(caminho(empresa)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tamanho").value(20))
                .andExpect(jsonPath("$.totalPaginas").value(1))
                .andExpect(jsonPath("$.itens.length()").value(1));
    }

    @Test
    void listaVaziaQuandoAEmpresaNaoTemFiliais() throws Exception {
        Empresa empresa = criarEmpresa();

        mvc.perform(get(caminho(empresa)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.totalPaginas").value(0))
                .andExpect(jsonPath("$.itens.length()").value(0));
    }

    @Test
    void listaSoAsFiliaisDaEmpresaDoCaminho() throws Exception {
        Empresa primeira = criarEmpresa();
        Empresa segunda = criarEmpresa();
        criarFilial(primeira, "0002", "Da Primeira 1");
        criarFilial(primeira, "0003", "Da Primeira 2");
        criarFilial(segunda, "0002", "Da Segunda");

        mvc.perform(get(caminho(primeira)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.itens[0].nome").value("Da Primeira 1"))
                .andExpect(jsonPath("$.itens[1].nome").value("Da Primeira 2"));

        mvc.perform(get(caminho(segunda)))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.itens[0].nome").value("Da Segunda"))
                .andExpect(jsonPath("$.itens[0].empresaId").value(segunda.getId()));
    }

    @Test
    void listaOrdenadaPorOutroCampoEmOrdemDecrescente() throws Exception {
        Empresa empresa = criarEmpresa();
        criarFilial(empresa, "0002", "Alfa");
        criarFilial(empresa, "0003", "Beta");

        mvc.perform(get(caminho(empresa)).param("sort", "nome,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].nome").value("Beta"))
                .andExpect(jsonPath("$.itens[1].nome").value("Alfa"));
    }

    @Test
    void recusaOrdenarPorCampoQueNaoExiste() throws Exception {
        Empresa empresa = criarEmpresa();

        mvc.perform(get(caminho(empresa)).param("sort", "campoInexistente,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Ordenação inválida"));
    }

    @Test
    void listarDeEmpresaInexistenteDevolve404() throws Exception {
        mvc.perform(get("/api/empresas/{empresaId}/filiais", ID_QUE_NAO_EXISTE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Não encontrado"))
                .andExpect(jsonPath("$.detail").value(containsString("Empresa")));
    }

    @Test
    void listarEmpresaInativaContinuaPermitido() throws Exception {
        Empresa empresa = criarEmpresa();
        criarFilial(empresa, "0002", "Historica");
        empresa.inativar();
        empresas.save(empresa);

        mvc.perform(get(caminho(empresa)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void filialDeUmaEmpresaNaoApareceNaListaDeOutraEContinuaSendoBuscavelPeloId() throws Exception {
        // O caminho plano /api/filiais/{id} nao tem pai: o isolamento por empresa vale na listagem,
        // onde uma filial de outro pai e tratada como se nao existisse.
        Empresa dona = criarEmpresa();
        Empresa outra = criarEmpresa();
        long id = criarFilial(dona, "0002", "Filial da Dona");

        mvc.perform(get(caminho(outra)))
                .andExpect(jsonPath("$.total").value(0));

        mvc.perform(get("/api/filiais/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.empresaId").value(dona.getId()));
    }

    @Test
    void naoAceitaCriarFilialDeUmaEmpresaNoCaminhoDeOutra() throws Exception {
        Empresa dona = criarEmpresa();
        Empresa outra = criarEmpresa();

        // CNPJ com a raiz da "dona" enviado para o caminho da "outra": raiz diferente => 422.
        mvc.perform(post(caminho(outra)).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpjDeFilial(dona, "0002"), "Filial Trocada")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("raiz")));
    }
}
