package br.com.kawashi.banco.socio;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import br.com.kawashi.banco.cliente.Cliente;
import br.com.kawashi.banco.cliente.ClienteRepository;
import br.com.kawashi.banco.empresa.Empresa;
import br.com.kawashi.banco.empresa.EmpresaRepository;
import br.com.kawashi.banco.testes.GeradorDeDocumentos;
import br.com.kawashi.banco.testes.IntegracaoApi;

@IntegracaoApi
class SocioApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ClienteRepository clienteRepository;

    @Autowired
    private EmpresaRepository empresaRepository;

    // ---- apoio ------------------------------------------------------------------

    /** A participacao entra como texto cru de JSON (ex.: "40", "12.5", "null", "\"abc\""). */
    private static String json(String nome, String cpf, String participacao) {
        return """
                {"nome": "%s", "cpf": "%s", "participacao": %s}
                """.formatted(nome, cpf, participacao);
    }

    private long criarCliente() throws Exception {
        String corpo = mvc.perform(post("/api/clientes").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cnpj": "%s", "razaoSocial": "Cliente dos Socios LTDA", "nomeFantasia": null}
                                """.formatted(GeradorDeDocumentos.cnpj())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(corpo, "$.id")).longValue();
    }

    private Empresa criarEmpresa() throws Exception {
        Cliente cliente = clienteRepository.findById(criarCliente()).orElseThrow();
        return empresaRepository.saveAndFlush(
                new Empresa(cliente, GeradorDeDocumentos.cnpj(), "Empresa dos Socios LTDA", null));
    }

    private long criarEmpresaId() throws Exception {
        return criarEmpresa().getId();
    }

    private ResultActions tentarCriar(long empresaId, String nome, String cpf, String participacao) throws Exception {
        return mvc.perform(post("/api/empresas/{empresaId}/socios", empresaId)
                .contentType(MediaType.APPLICATION_JSON).content(json(nome, cpf, participacao)));
    }

    private long criarSocio(long empresaId, String nome, String cpf, String participacao) throws Exception {
        String corpo = tentarCriar(empresaId, nome, cpf, participacao)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(corpo, "$.id")).longValue();
    }

    private long criarSocio(long empresaId, String participacao) throws Exception {
        return criarSocio(empresaId, "Sócio " + participacao, GeradorDeDocumentos.cpf(), participacao);
    }

    private ResultActions tentarAtualizar(long id, String nome, String cpf, String participacao) throws Exception {
        return mvc.perform(put("/api/socios/{id}", id)
                .contentType(MediaType.APPLICATION_JSON).content(json(nome, cpf, participacao)));
    }

    // ---- criar ------------------------------------------------------------------

    @Test
    void criaSocioComCpfFormatadoEGuardaSoDigitos() throws Exception {
        long empresaId = criarEmpresaId();
        String cpf = GeradorDeDocumentos.cpf();

        String corpo = tentarCriar(empresaId, "Maria da Silva", GeradorDeDocumentos.formatarCpf(cpf), "40")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/socios/")))
                .andExpect(jsonPath("$.empresaId").value(empresaId))
                .andExpect(jsonPath("$.nome").value("Maria da Silva"))
                .andExpect(jsonPath("$.cpf").value(cpf))
                .andExpect(jsonPath("$.participacao").value(40.0))
                .andExpect(jsonPath("$.criadoEm").exists())
                .andExpect(jsonPath("$.atualizadoEm").exists())
                .andReturn().getResponse().getContentAsString();

        long id = ((Number) JsonPath.read(corpo, "$.id")).longValue();
        mvc.perform(get("/api/socios/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cpf").value(cpf));
    }

    @Test
    void locationApontaParaOCaminhoPlanoDoSocioCriado() throws Exception {
        long empresaId = criarEmpresaId();

        var resposta = tentarCriar(empresaId, "João Souza", GeradorDeDocumentos.cpf(), "25.5")
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        long id = ((Number) JsonPath.read(resposta.getContentAsString(), "$.id")).longValue();

        assertTrue(resposta.getHeader("Location").endsWith("/api/socios/" + id),
                "o Location deveria terminar em /api/socios/" + id + " mas foi " + resposta.getHeader("Location"));
    }

    @Test
    void removeEspacosSobrandoNoNome() throws Exception {
        long empresaId = criarEmpresaId();

        tentarCriar(empresaId, "   Ana Paula   ", GeradorDeDocumentos.cpf(), "10")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nome").value("Ana Paula"));
    }

    @Test
    void aceitaParticipacaoNosExtremosDe001A100() throws Exception {
        long empresaMinima = criarEmpresaId();
        tentarCriar(empresaMinima, "Minoritário", GeradorDeDocumentos.cpf(), "0.01")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participacao").value(0.01));

        long empresaMaxima = criarEmpresaId();
        tentarCriar(empresaMaxima, "Sócio Único", GeradorDeDocumentos.cpf(), "100")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participacao").value(100.0));
    }

    @Test
    void aceitaNomeComExatamente150Caracteres() throws Exception {
        long empresaId = criarEmpresaId();

        tentarCriar(empresaId, "n".repeat(150), GeradorDeDocumentos.cpf(), "10")
                .andExpect(status().isCreated());
    }

    // ---- validacao de campos (400) ----------------------------------------------

    @Test
    void recusaNomeEmBranco() throws Exception {
        long empresaId = criarEmpresaId();

        tentarCriar(empresaId, "  ", GeradorDeDocumentos.cpf(), "10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Dados inválidos"))
                .andExpect(jsonPath("$.erros[0].campo").value("nome"))
                .andExpect(jsonPath("$.erros[0].mensagem").value("O nome é obrigatório"));
    }

    @Test
    void recusaNomeComMaisDe150Caracteres() throws Exception {
        long empresaId = criarEmpresaId();

        tentarCriar(empresaId, "n".repeat(151), GeradorDeDocumentos.cpf(), "10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("nome"))
                .andExpect(jsonPath("$.erros[0].mensagem").value(containsString("150")));
    }

    @Test
    void recusaCpfEmBranco() throws Exception {
        long empresaId = criarEmpresaId();

        tentarCriar(empresaId, "Sem Cpf", "", "10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("cpf"))
                .andExpect(jsonPath("$.erros[0].mensagem").value("O CPF é obrigatório"));
    }

    @Test
    void recusaCpfComDigitoVerificadorErrado() throws Exception {
        long empresaId = criarEmpresaId();
        String invalido = GeradorDeDocumentos.comDigitoVerificadorErrado(GeradorDeDocumentos.cpf());

        tentarCriar(empresaId, "Cpf Ruim", invalido, "10")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("cpf"))
                .andExpect(jsonPath("$.erros[0].mensagem").value("CPF inválido"));
    }

    @Test
    void recusaParticipacaoAusente() throws Exception {
        long empresaId = criarEmpresaId();

        tentarCriar(empresaId, "Sem Participação", GeradorDeDocumentos.cpf(), "null")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("participacao"))
                .andExpect(jsonPath("$.erros[0].mensagem").value("A participação é obrigatória"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-5", "0.001", "100.01", "150", "1000", "10.123"})
    void recusaParticipacaoForaDaFaixaOuComCasasDemais(String participacao) throws Exception {
        long empresaId = criarEmpresaId();

        tentarCriar(empresaId, "Participação Ruim", GeradorDeDocumentos.cpf(), participacao)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Dados inválidos"))
                .andExpect(jsonPath("$.erros[0].campo").value("participacao"));
    }

    @Test
    void mensagensDeParticipacaoSaoClarasEmPortugues() throws Exception {
        long empresaId = criarEmpresaId();

        tentarCriar(empresaId, "Zero", GeradorDeDocumentos.cpf(), "0")
                .andExpect(jsonPath("$.erros[0].mensagem").value(containsString("maior que zero")));
        tentarCriar(empresaId, "Acima", GeradorDeDocumentos.cpf(), "100.01")
                .andExpect(jsonPath("$.erros[0].mensagem").value(containsString("no máximo 100")));
        tentarCriar(empresaId, "Casas", GeradorDeDocumentos.cpf(), "10.123")
                .andExpect(jsonPath("$.erros[0].mensagem").value(containsString("2 casas decimais")));
    }

    @Test
    void recusaCorpoIlegivelEParticipacaoComTipoErrado() throws Exception {
        long empresaId = criarEmpresaId();

        mvc.perform(post("/api/empresas/{empresaId}/socios", empresaId)
                        .contentType(MediaType.APPLICATION_JSON).content("{isto nao e json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Corpo da requisição ilegível"));

        tentarCriar(empresaId, "Tipo Errado", GeradorDeDocumentos.cpf(), "\"abc\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Corpo da requisição ilegível"));
    }

    @Test
    void idDaEmpresaComTipoErradoViraErro400() throws Exception {
        mvc.perform(get("/api/empresas/{empresaId}/socios", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(endsWith("tem um valor com tipo errado.")));
    }

    // ---- regra 1: empresa existente e ativa -------------------------------------

    @Test
    void recusaCriarSocioEmEmpresaInexistente() throws Exception {
        tentarCriar(999_999_999L, "Sócio Fantasma", GeradorDeDocumentos.cpf(), "10")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Não encontrado"))
                .andExpect(jsonPath("$.detail").value(containsString("Empresa")));
    }

    @Test
    void recusaListarSociosDeEmpresaInexistente() throws Exception {
        mvc.perform(get("/api/empresas/{empresaId}/socios", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Não encontrado"));
    }

    @Test
    void recusaCadastrarSocioEmEmpresaInativa() throws Exception {
        Empresa empresa = criarEmpresa();
        empresa.inativar();
        empresaRepository.saveAndFlush(empresa);

        tentarCriar(empresa.getId(), "Sócio Barrado", GeradorDeDocumentos.cpf(), "10")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Regra de negócio violada"))
                .andExpect(jsonPath("$.detail").value(containsString("inativa")));

        mvc.perform(get("/api/empresas/{empresaId}/socios", empresa.getId()))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void empresaReativadaVoltaAAceitarSocios() throws Exception {
        Empresa empresa = criarEmpresa();
        empresa.inativar();
        empresaRepository.saveAndFlush(empresa);
        empresa.reativar();
        empresaRepository.saveAndFlush(empresa);

        tentarCriar(empresa.getId(), "Sócio Aceito", GeradorDeDocumentos.cpf(), "10")
                .andExpect(status().isCreated());
    }

    @Test
    void empresaInativaAindaPodeSerConsultadaEOsSociosExistentesPodemSerMantidos() throws Exception {
        Empresa empresa = criarEmpresa();
        String cpf = GeradorDeDocumentos.cpf();
        long id = criarSocio(empresa.getId(), "Sócio Antigo", cpf, "50");
        empresa.inativar();
        empresaRepository.saveAndFlush(empresa);

        mvc.perform(get("/api/empresas/{empresaId}/socios", empresa.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/socios/{id}", id)).andExpect(status().isOk());
        tentarAtualizar(id, "Sócio Antigo Renomeado", cpf, "60")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Sócio Antigo Renomeado"));
        mvc.perform(delete("/api/socios/{id}", id)).andExpect(status().isNoContent());
    }

    // ---- regra 2: CPF unico dentro da mesma empresa -----------------------------

    @Test
    void recusaCpfDuplicadoNaMesmaEmpresaComConflito() throws Exception {
        long empresaId = criarEmpresaId();
        String cpf = GeradorDeDocumentos.cpf();
        criarSocio(empresaId, "Primeiro", cpf, "10");

        tentarCriar(empresaId, "Segundo", GeradorDeDocumentos.formatarCpf(cpf), "10")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflito"))
                .andExpect(jsonPath("$.detail").value(containsString(cpf)));
    }

    @Test
    void mesmoCpfPodeSerSocioDeEmpresasDiferentes() throws Exception {
        String cpf = GeradorDeDocumentos.cpf();
        long primeira = criarEmpresaId();
        long segunda = criarEmpresaId();

        criarSocio(primeira, "Sócio Em Duas", cpf, "60");
        tentarCriar(segunda, "Sócio Em Duas", cpf, "60")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.empresaId").value(segunda));
    }

    // ---- regra 3: soma das participacoes ate 100% -------------------------------

    @Test
    void recusaCriarQuandoASomaPassaDe100EInformaQuantoEstaDisponivel() throws Exception {
        long empresaId = criarEmpresaId();
        criarSocio(empresaId, "60.5");

        tentarCriar(empresaId, "Estourou", GeradorDeDocumentos.cpf(), "40")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Regra de negócio violada"))
                .andExpect(jsonPath("$.detail").value(containsString("100%")))
                .andExpect(jsonPath("$.detail").value(containsString("39.50%")));

        mvc.perform(get("/api/empresas/{empresaId}/socios", empresaId))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void aceitaCompletarExatamente100PorCento() throws Exception {
        long empresaId = criarEmpresaId();
        criarSocio(empresaId, "60.5");

        // 39.5 (escala 1) completa 100 contra 60.50 (escala 2): so compareTo acerta isso, equals nao.
        tentarCriar(empresaId, "Fecha a Conta", GeradorDeDocumentos.cpf(), "39.5")
                .andExpect(status().isCreated());

        tentarCriar(empresaId, "Sobrou Nada", GeradorDeDocumentos.cpf(), "0.01")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("0.00%")));
    }

    @Test
    void somaComCasasDecimaisFechaEmCentavosDePercentual() throws Exception {
        long empresaId = criarEmpresaId();
        criarSocio(empresaId, "33.33");
        criarSocio(empresaId, "33.33");
        criarSocio(empresaId, "33.33");

        tentarCriar(empresaId, "Dois Centésimos", GeradorDeDocumentos.cpf(), "0.02")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("0.01%")));

        tentarCriar(empresaId, "Um Centésimo", GeradorDeDocumentos.cpf(), "0.01")
                .andExpect(status().isCreated());
    }

    @Test
    void asSomasDeEmpresasDiferentesSaoIndependentes() throws Exception {
        long primeira = criarEmpresaId();
        long segunda = criarEmpresaId();

        criarSocio(primeira, "100");
        criarSocio(segunda, "100");
    }

    @Test
    void atualizacaoRecusaEstourarOLimiteEInformaOQueSobra() throws Exception {
        long empresaId = criarEmpresaId();
        criarSocio(empresaId, "60");
        String cpfB = GeradorDeDocumentos.cpf();
        long idB = criarSocio(empresaId, "Sócio B", cpfB, "40");

        tentarAtualizar(idB, "Sócio B", cpfB, "41")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Regra de negócio violada"))
                .andExpect(jsonPath("$.detail").value(containsString("40.00%")));

        mvc.perform(get("/api/socios/{id}", idB))
                .andExpect(jsonPath("$.participacao").value(40.0));
    }

    @Test
    void atualizacaoNaoContaOProprioSocioNaSoma() throws Exception {
        long empresaId = criarEmpresaId();
        String cpf = GeradorDeDocumentos.cpf();
        long id = criarSocio(empresaId, "Dono de Tudo", cpf, "100");

        // Se o proprio socio fosse somado, 100 + 100 estouraria. Reenviar o mesmo valor tem de passar.
        tentarAtualizar(id, "Dono de Tudo", cpf, "100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participacao").value(100.0));
        tentarAtualizar(id, "Dono de Tudo", cpf, "90")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participacao").value(90.0));
    }

    @Test
    void atualizacaoPermiteRedistribuirAParticipacaoEntreSocios() throws Exception {
        long empresaId = criarEmpresaId();
        String cpfA = GeradorDeDocumentos.cpf();
        String cpfB = GeradorDeDocumentos.cpf();
        long idA = criarSocio(empresaId, "Sócio A", cpfA, "60");
        long idB = criarSocio(empresaId, "Sócio B", cpfB, "40");

        tentarAtualizar(idA, "Sócio A", cpfA, "50").andExpect(status().isOk());
        tentarAtualizar(idB, "Sócio B", cpfB, "50").andExpect(status().isOk());
        tentarAtualizar(idB, "Sócio B", cpfB, "50.01")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("50.00%")));
    }

    // ---- regra 4: CPF nao muda no PUT -------------------------------------------

    @Test
    void atualizaNomeEParticipacaoMasNaoAceitaTrocarOCpf() throws Exception {
        long empresaId = criarEmpresaId();
        String cpf = GeradorDeDocumentos.cpf();
        long id = criarSocio(empresaId, "Nome Antigo", cpf, "30");

        tentarAtualizar(id, "  Nome Novo  ", cpf, "55.5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.empresaId").value(empresaId))
                .andExpect(jsonPath("$.nome").value("Nome Novo"))
                .andExpect(jsonPath("$.cpf").value(cpf))
                .andExpect(jsonPath("$.participacao").value(55.5));

        tentarAtualizar(id, "Nome Novo", GeradorDeDocumentos.cpf(), "55.5")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Regra de negócio violada"))
                .andExpect(jsonPath("$.detail").value(containsString("CPF")));

        mvc.perform(get("/api/socios/{id}", id))
                .andExpect(jsonPath("$.cpf").value(cpf))
                .andExpect(jsonPath("$.nome").value("Nome Novo"));
    }

    @Test
    void atualizacaoAceitaOMesmoCpfComPontuacao() throws Exception {
        long empresaId = criarEmpresaId();
        String cpf = GeradorDeDocumentos.cpf();
        long id = criarSocio(empresaId, "Com Pontos", cpf, "30");

        tentarAtualizar(id, "Com Pontos", GeradorDeDocumentos.formatarCpf(cpf), "35")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cpf").value(cpf))
                .andExpect(jsonPath("$.participacao").value(35.0));
    }

    @Test
    void atualizacaoValidaOsCamposComo400() throws Exception {
        long empresaId = criarEmpresaId();
        String cpf = GeradorDeDocumentos.cpf();
        long id = criarSocio(empresaId, "Valida PUT", cpf, "30");

        tentarAtualizar(id, "", cpf, "30")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("nome"));
        tentarAtualizar(id, "Valida PUT", GeradorDeDocumentos.comDigitoVerificadorErrado(cpf), "30")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("cpf"));
        tentarAtualizar(id, "Valida PUT", cpf, "100.5")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("participacao"));
    }

    @Test
    void atualizacaoDeSocioInexistenteDevolve404() throws Exception {
        tentarAtualizar(999_999_999L, "Ninguém", GeradorDeDocumentos.cpf(), "10")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Não encontrado"));
    }

    // ---- regra 5: excluir libera a participacao ---------------------------------

    @Test
    void excluiSocioELiberaAParticipacao() throws Exception {
        long empresaId = criarEmpresaId();
        String cpf = GeradorDeDocumentos.cpf();
        long id = criarSocio(empresaId, "Vai Sair", cpf, "100");

        tentarCriar(empresaId, "Sem Espaço", GeradorDeDocumentos.cpf(), "10")
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(delete("/api/socios/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/socios/{id}", id)).andExpect(status().isNotFound());

        // A participacao voltou a ficar livre, e o CPF tambem pode ser cadastrado de novo.
        tentarCriar(empresaId, "Voltou", cpf, "100").andExpect(status().isCreated());
    }

    @Test
    void exclusaoDeSocioInexistenteDevolve404() throws Exception {
        long empresaId = criarEmpresaId();
        long id = criarSocio(empresaId, "Uma Vez Só", GeradorDeDocumentos.cpf(), "10");

        mvc.perform(delete("/api/socios/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/socios/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Não encontrado"));
    }

    // ---- buscar -----------------------------------------------------------------

    @Test
    void buscaPorIdEDevolve404QuandoNaoExiste() throws Exception {
        long empresaId = criarEmpresaId();
        String cpf = GeradorDeDocumentos.cpf();
        long id = criarSocio(empresaId, "Busca Sócio", cpf, "12.5");

        mvc.perform(get("/api/socios/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.empresaId").value(empresaId))
                .andExpect(jsonPath("$.nome").value("Busca Sócio"))
                .andExpect(jsonPath("$.cpf").value(cpf))
                .andExpect(jsonPath("$.participacao").value(12.5));

        mvc.perform(get("/api/socios/{id}", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Não encontrado"))
                .andExpect(jsonPath("$.detail").value(containsString("Sócio")));
    }

    @Test
    void idDoSocioComTipoErradoViraErro400() throws Exception {
        mvc.perform(get("/api/socios/{id}", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(endsWith("tem um valor com tipo errado.")));
    }

    // ---- listar -----------------------------------------------------------------

    @Test
    void listaOsSociosDaEmpresaComPaginacaoOrdenadosPorId() throws Exception {
        long empresaId = criarEmpresaId();
        criarSocio(empresaId, "Alfa", GeradorDeDocumentos.cpf(), "10");
        criarSocio(empresaId, "Beta", GeradorDeDocumentos.cpf(), "10");
        criarSocio(empresaId, "Gama", GeradorDeDocumentos.cpf(), "10");

        mvc.perform(get("/api/empresas/{empresaId}/socios", empresaId).param("size", "2").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPaginas").value(2))
                .andExpect(jsonPath("$.pagina").value(0))
                .andExpect(jsonPath("$.tamanho").value(2))
                .andExpect(jsonPath("$.itens.length()").value(2))
                .andExpect(jsonPath("$.itens[0].nome").value("Alfa"))
                .andExpect(jsonPath("$.itens[1].nome").value("Beta"));

        mvc.perform(get("/api/empresas/{empresaId}/socios", empresaId).param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andExpect(jsonPath("$.itens[0].nome").value("Gama"));
    }

    @Test
    void listaUsaPaginaDe20ItensPorPadraoEAceitaOrdenacaoPorOutroCampo() throws Exception {
        long empresaId = criarEmpresaId();
        criarSocio(empresaId, "Carlos", GeradorDeDocumentos.cpf(), "10");
        criarSocio(empresaId, "Ana", GeradorDeDocumentos.cpf(), "10");

        mvc.perform(get("/api/empresas/{empresaId}/socios", empresaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tamanho").value(20))
                .andExpect(jsonPath("$.itens[0].nome").value("Carlos"));

        mvc.perform(get("/api/empresas/{empresaId}/socios", empresaId).param("sort", "nome,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].nome").value("Ana"))
                .andExpect(jsonPath("$.itens[1].nome").value("Carlos"));
    }

    @Test
    void listaVaziaQuandoAEmpresaNaoTemSocios() throws Exception {
        long empresaId = criarEmpresaId();

        mvc.perform(get("/api/empresas/{empresaId}/socios", empresaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.itens.length()").value(0));
    }

    @Test
    void listaNaoMostraSociosDeOutraEmpresa() throws Exception {
        long primeira = criarEmpresaId();
        long segunda = criarEmpresaId();
        criarSocio(primeira, "Da Primeira", GeradorDeDocumentos.cpf(), "10");
        criarSocio(segunda, "Da Segunda", GeradorDeDocumentos.cpf(), "10");
        criarSocio(segunda, "Também da Segunda", GeradorDeDocumentos.cpf(), "10");

        mvc.perform(get("/api/empresas/{empresaId}/socios", primeira))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.itens[0].nome").value("Da Primeira"))
                .andExpect(jsonPath("$.itens[0].empresaId").value(primeira));
        mvc.perform(get("/api/empresas/{empresaId}/socios", segunda))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void recusaOrdenarPorCampoQueNaoExiste() throws Exception {
        long empresaId = criarEmpresaId();

        mvc.perform(get("/api/empresas/{empresaId}/socios", empresaId).param("sort", "campoInexistente,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Ordenação inválida"));
    }
}
