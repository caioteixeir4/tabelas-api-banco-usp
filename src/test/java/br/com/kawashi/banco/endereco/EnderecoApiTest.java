package br.com.kawashi.banco.endereco;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import br.com.kawashi.banco.testes.GeradorDeDocumentos;
import br.com.kawashi.banco.testes.IntegracaoApi;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@IntegracaoApi
class EnderecoApiTest {

    private static final String RAIZ = "/api/clientes/{clienteId}/enderecos";
    private static final String ITEM = RAIZ + "/{id}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EnderecoRepository enderecos;

    @PersistenceContext
    private EntityManager em;

    // ---- auxiliares ---------------------------------------------------------------

    private static String texto(String valor) {
        return valor == null ? "null" : "\"" + valor + "\"";
    }

    private static String corpo(String logradouro, String numero, String complemento, String bairro, String cidade,
            String uf, String cep, Boolean principal) {
        return """
                {"logradouro": %s, "numero": %s, "complemento": %s, "bairro": %s, "cidade": %s, "uf": %s, "cep": %s, "principal": %s}
                """.formatted(texto(logradouro), texto(numero), texto(complemento), texto(bairro), texto(cidade),
                texto(uf), texto(cep), principal == null ? "null" : principal.toString());
    }

    /** Endereco valido com o logradouro informado; o resto e fixo. */
    private static String corpo(String logradouro, Boolean principal) {
        return corpo(logradouro, "100", null, "Centro", "São Paulo", "SP", "01310-100", principal);
    }

    private long criarCliente() throws Exception {
        String json = """
                {"cnpj": "%s", "razaoSocial": "Cliente de Endereço LTDA", "nomeFantasia": null}
                """.formatted(GeradorDeDocumentos.cnpj());
        String resposta = mvc.perform(post("/api/clientes").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resposta, "$.id")).longValue();
    }

    private long criar(long clienteId, String corpo) throws Exception {
        String resposta = mvc.perform(post(RAIZ, clienteId).contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resposta, "$.id")).longValue();
    }

    private void esperarPrincipal(long clienteId, long id, boolean principal) throws Exception {
        mvc.perform(get(ITEM, clienteId, id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principal").value(principal));
    }

    // ---- criacao ------------------------------------------------------------------

    @Test
    void criaEnderecoComCepFormatadoEUfMinusculaENormalizaOsDados() throws Exception {
        long clienteId = criarCliente();

        String resposta = mvc.perform(post(RAIZ, clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("  Avenida Paulista  ", " 1578 ", "  ", " Bela Vista ", " São Paulo ", "sp", "01310-200", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clienteId").value(clienteId))
                .andExpect(jsonPath("$.logradouro").value("Avenida Paulista"))
                .andExpect(jsonPath("$.numero").value("1578"))
                .andExpect(jsonPath("$.complemento").doesNotExist())
                .andExpect(jsonPath("$.bairro").value("Bela Vista"))
                .andExpect(jsonPath("$.cidade").value("São Paulo"))
                .andExpect(jsonPath("$.uf").value("SP"))
                .andExpect(jsonPath("$.cep").value("01310200"))
                .andExpect(jsonPath("$.principal").value(true))
                .andExpect(jsonPath("$.criadoEm").exists())
                .andExpect(jsonPath("$.atualizadoEm").exists())
                .andReturn().getResponse().getContentAsString();

        long id = ((Number) JsonPath.read(resposta, "$.id")).longValue();
        mvc.perform(get(ITEM, clienteId, id))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.cep").value("01310200"))
                .andExpect(jsonPath("$.uf").value("SP"));
    }

    @Test
    void devolveLocationApontandoParaOEnderecoCriado() throws Exception {
        long clienteId = criarCliente();

        MvcResult resultado = mvc.perform(post(RAIZ, clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("Rua Um", null)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/clientes/" + clienteId + "/enderecos/")))
                .andReturn();

        long id = ((Number) JsonPath.read(resultado.getResponse().getContentAsString(), "$.id")).longValue();
        String location = resultado.getResponse().getHeader("Location");
        assertTrue(location.endsWith("/api/clientes/" + clienteId + "/enderecos/" + id), location);

        mvc.perform(get(URI.create(location)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.logradouro").value("Rua Um"));
    }

    @Test
    void aceitaCepSemPontuacaoEGuardaOitoDigitos() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(post(RAIZ, clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("Rua do CEP", "10", "Fundos", "Centro", "Campinas", "SP", "13010111", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cep").value("13010111"))
                .andExpect(jsonPath("$.complemento").value("Fundos"));
    }

    @Test
    void aceitaApenasOsCamposObrigatorios() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(post(RAIZ, clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"logradouro": "Estrada Velha", "cidade": "Cuiabá", "uf": "MT", "cep": "78000-000"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numero").doesNotExist())
                .andExpect(jsonPath("$.complemento").doesNotExist())
                .andExpect(jsonPath("$.bairro").doesNotExist())
                .andExpect(jsonPath("$.principal").value(true));
    }

    // ---- regras de principal na criacao -------------------------------------------

    @Test
    void primeiroEnderecoESempreOPrincipalMesmoComPrincipalFalseOuNulo() throws Exception {
        long comFalse = criarCliente();
        mvc.perform(post(RAIZ, comFalse).contentType(MediaType.APPLICATION_JSON).content(corpo("Rua Primeira", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.principal").value(true));

        long comNulo = criarCliente();
        mvc.perform(post(RAIZ, comNulo).contentType(MediaType.APPLICATION_JSON).content(corpo("Rua Primeira", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.principal").value(true));
    }

    @Test
    void segundoEnderecoNaoNasceComoPrincipalQuandoPrincipalVemNuloOuFalso() throws Exception {
        long clienteId = criarCliente();
        long primeiro = criar(clienteId, corpo("Rua Primeira", null));

        long comNulo = criar(clienteId, corpo("Rua Segunda", null));
        long comFalse = criar(clienteId, corpo("Rua Terceira", false));

        esperarPrincipal(clienteId, primeiro, true);
        esperarPrincipal(clienteId, comNulo, false);
        esperarPrincipal(clienteId, comFalse, false);
    }

    @Test
    void gravarNovoPrincipalRebaixaOAnteriorNaMesmaTransacao() throws Exception {
        long clienteId = criarCliente();
        long primeiro = criar(clienteId, corpo("Rua Primeira", null));
        long segundo = criar(clienteId, corpo("Rua Segunda", true));

        esperarPrincipal(clienteId, primeiro, false);
        esperarPrincipal(clienteId, segundo, true);

        long terceiro = criar(clienteId, corpo("Rua Terceira", true));

        esperarPrincipal(clienteId, primeiro, false);
        esperarPrincipal(clienteId, segundo, false);
        esperarPrincipal(clienteId, terceiro, true);

        mvc.perform(get(RAIZ, clienteId))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.itens[?(@.principal == true)]", hasSize(1)));
    }

    @Test
    void principalDeUmClienteNaoAfetaOsEnderecosDeOutroCliente() throws Exception {
        long clienteA = criarCliente();
        long clienteB = criarCliente();
        long enderecoA = criar(clienteA, corpo("Rua do A", null));
        long enderecoB = criar(clienteB, corpo("Rua do B", null));

        criar(clienteB, corpo("Outra Rua do B", true));

        esperarPrincipal(clienteA, enderecoA, true);
        esperarPrincipal(clienteB, enderecoB, false);
    }

    // ---- cliente do caminho ---------------------------------------------------------

    @Test
    void naoCriaEnderecoParaClienteInexistente() throws Exception {
        mvc.perform(post(RAIZ, 999_999_999L).contentType(MediaType.APPLICATION_JSON).content(corpo("Rua Nenhuma", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Não encontrado"))
                .andExpect(jsonPath("$.detail").value(containsString("Cliente")));
    }

    @Test
    void clienteInativoNaoRecebeEnderecoMasVoltaARecebeerDepoisDeReativado() throws Exception {
        long clienteId = criarCliente();
        mvc.perform(post("/api/clientes/{id}/inativar", clienteId)).andExpect(status().isOk());

        mvc.perform(post(RAIZ, clienteId).contentType(MediaType.APPLICATION_JSON).content(corpo("Rua Fechada", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Regra de negócio violada"))
                .andExpect(jsonPath("$.detail").value(containsString("inativo")));

        mvc.perform(get(RAIZ, clienteId))
                .andExpect(jsonPath("$.total").value(0));

        mvc.perform(post("/api/clientes/{id}/reativar", clienteId)).andExpect(status().isOk());
        mvc.perform(post(RAIZ, clienteId).contentType(MediaType.APPLICATION_JSON).content(corpo("Rua Aberta", null)))
                .andExpect(status().isCreated());
    }

    @Test
    void listarEBuscarComClienteInexistenteDevolvem404() throws Exception {
        mvc.perform(get(RAIZ, 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("Cliente")));
        mvc.perform(get(ITEM, 999_999_999L, 1L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("Cliente")));
        mvc.perform(put(ITEM, 999_999_999L, 1L).contentType(MediaType.APPLICATION_JSON).content(corpo("Rua", null)))
                .andExpect(status().isNotFound());
        mvc.perform(delete(ITEM, 999_999_999L, 1L))
                .andExpect(status().isNotFound());
    }

    // ---- validacao de campos --------------------------------------------------------

    @Test
    void recusaLogradouroEmBrancoOuAusente() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(post(RAIZ, clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("   ", "1", null, null, "São Paulo", "SP", "01310-100", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Dados inválidos"))
                .andExpect(jsonPath("$.erros[0].campo").value("logradouro"))
                .andExpect(jsonPath("$.erros[0].mensagem").value("O logradouro é obrigatório"));

        mvc.perform(post(RAIZ, clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cidade": "São Paulo", "uf": "SP", "cep": "01310-100"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("logradouro"));
    }

    @Test
    void recusaCidadeEmBranco() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(post(RAIZ, clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("Rua Sem Cidade", "1", null, null, "", "SP", "01310-100", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("cidade"))
                .andExpect(jsonPath("$.erros[0].mensagem").value("A cidade é obrigatória"));
    }

    @Test
    void recusaUfEmBrancoOuInexistente() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(post(RAIZ, clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("Rua Sem UF", "1", null, null, "São Paulo", "", "01310-100", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("uf"))
                .andExpect(jsonPath("$.erros[0].mensagem").value("A UF é obrigatória"));

        mvc.perform(post(RAIZ, clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("Rua UF Falsa", "1", null, null, "São Paulo", "XX", "01310-100", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("uf"))
                .andExpect(jsonPath("$.erros[0].mensagem").value(containsString("UF inválida")));

        mvc.perform(post(RAIZ, clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("Rua UF Longa", "1", null, null, "São Paulo", "SPP", "01310-100", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("uf"));
    }

    @Test
    void recusaCepAusenteOuMalFormado() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(post(RAIZ, clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("Rua Sem CEP", "1", null, null, "São Paulo", "SP", null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[?(@.campo == 'cep')].mensagem", hasItem("O CEP é obrigatório")));

        for (String invalido : new String[] {"1234567", "123456789", "abcde-fgh", "01310-10", "0131-0100", "01.310-100", "01310 100"}) {
            mvc.perform(post(RAIZ, clienteId).contentType(MediaType.APPLICATION_JSON)
                            .content(corpo("Rua CEP Ruim", "1", null, null, "São Paulo", "SP", invalido, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.erros[0].campo").value("cep"))
                    .andExpect(jsonPath("$.erros[0].mensagem").value(containsString("CEP inválido")));
        }
    }

    @Test
    void recusaCamposAcimaDoTamanhoMaximo() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(post(RAIZ, clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("L".repeat(201), "N".repeat(21), "C".repeat(101), "B".repeat(101),
                                "D".repeat(101), "SP", "01310-100", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros.length()").value(5))
                .andExpect(jsonPath("$.erros[?(@.campo == 'logradouro')].mensagem", hasItem("O logradouro aceita no máximo 200 caracteres")))
                .andExpect(jsonPath("$.erros[?(@.campo == 'numero')].mensagem", hasItem("O número aceita no máximo 20 caracteres")))
                .andExpect(jsonPath("$.erros[?(@.campo == 'complemento')].mensagem", hasItem("O complemento aceita no máximo 100 caracteres")))
                .andExpect(jsonPath("$.erros[?(@.campo == 'bairro')].mensagem", hasItem("O bairro aceita no máximo 100 caracteres")))
                .andExpect(jsonPath("$.erros[?(@.campo == 'cidade')].mensagem", hasItem("A cidade aceita no máximo 100 caracteres")));

        // No limite exato, passa.
        mvc.perform(post(RAIZ, clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("L".repeat(200), "N".repeat(20), "C".repeat(100), "B".repeat(100),
                                "D".repeat(100), "SP", "01310-100", null)))
                .andExpect(status().isCreated());
    }

    @Test
    void recusaCorpoQueNaoEJsonOuComTipoErrado() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(post(RAIZ, clienteId).contentType(MediaType.APPLICATION_JSON).content("{isto nao e json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Corpo da requisição ilegível"));

        mvc.perform(post(RAIZ, clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"logradouro": "Rua X", "cidade": "São Paulo", "uf": "SP", "cep": "01310-100", "principal": "talvez"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Corpo da requisição ilegível"));
    }

    @Test
    void clienteIdComTipoErradoViraErro400() throws Exception {
        mvc.perform(post(RAIZ, "abc").contentType(MediaType.APPLICATION_JSON).content(corpo("Rua X", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(endsWith("tem um valor com tipo errado.")));
    }

    // ---- busca ----------------------------------------------------------------------

    @Test
    void buscaPorIdEDevolve404QuandoNaoExiste() throws Exception {
        long clienteId = criarCliente();
        long id = criar(clienteId, corpo("Rua da Busca", "55", "Sala 2", "Jardins", "Santos", "SP", "11010-000", null));

        mvc.perform(get(ITEM, clienteId, id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.clienteId").value(clienteId))
                .andExpect(jsonPath("$.logradouro").value("Rua da Busca"))
                .andExpect(jsonPath("$.numero").value("55"))
                .andExpect(jsonPath("$.complemento").value("Sala 2"))
                .andExpect(jsonPath("$.bairro").value("Jardins"))
                .andExpect(jsonPath("$.cidade").value("Santos"))
                .andExpect(jsonPath("$.uf").value("SP"))
                .andExpect(jsonPath("$.cep").value("11010000"))
                .andExpect(jsonPath("$.principal").value(true));

        mvc.perform(get(ITEM, clienteId, 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Não encontrado"));
    }

    @Test
    void enderecoDeOutroClienteETratadoComoInexistente() throws Exception {
        long donoId = criarCliente();
        long outroId = criarCliente();
        long id = criar(donoId, corpo("Rua do Dono", null));
        criar(outroId, corpo("Rua do Outro", null));

        mvc.perform(get(ITEM, outroId, id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Não encontrado"));

        mvc.perform(put(ITEM, outroId, id).contentType(MediaType.APPLICATION_JSON).content(corpo("Rua Invadida", true)))
                .andExpect(status().isNotFound());

        mvc.perform(delete(ITEM, outroId, id))
                .andExpect(status().isNotFound());

        // O endereco do dono continua intacto.
        mvc.perform(get(ITEM, donoId, id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logradouro").value("Rua do Dono"))
                .andExpect(jsonPath("$.principal").value(true));
    }

    // ---- atualizacao ----------------------------------------------------------------

    @Test
    void atualizaOsDadosENormalizaCepEUf() throws Exception {
        long clienteId = criarCliente();
        long id = criar(clienteId, corpo("Rua Antiga", "1", "Casa", "Bairro Velho", "Antiga", "SP", "01000-000", null));

        mvc.perform(put(ITEM, clienteId, id).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(" Rua Nova ", "2", null, "Bairro Novo", "Niterói", "rj", "24020-005", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.clienteId").value(clienteId))
                .andExpect(jsonPath("$.logradouro").value("Rua Nova"))
                .andExpect(jsonPath("$.numero").value("2"))
                .andExpect(jsonPath("$.complemento").doesNotExist())
                .andExpect(jsonPath("$.bairro").value("Bairro Novo"))
                .andExpect(jsonPath("$.cidade").value("Niterói"))
                .andExpect(jsonPath("$.uf").value("RJ"))
                .andExpect(jsonPath("$.cep").value("24020005"))
                .andExpect(jsonPath("$.principal").value(true));

        mvc.perform(get(ITEM, clienteId, id))
                .andExpect(jsonPath("$.logradouro").value("Rua Nova"))
                .andExpect(jsonPath("$.uf").value("RJ"))
                .andExpect(jsonPath("$.cep").value("24020005"));
    }

    @Test
    void atualizacaoRecusaDadosInvalidosCom400() throws Exception {
        long clienteId = criarCliente();
        long id = criar(clienteId, corpo("Rua Boa", null));

        mvc.perform(put(ITEM, clienteId, id).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo("Rua Boa", "1", null, null, "São Paulo", "ZZ", "123", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Dados inválidos"))
                .andExpect(jsonPath("$.erros[?(@.campo == 'uf')]", hasSize(1)))
                .andExpect(jsonPath("$.erros[?(@.campo == 'cep')]", hasSize(1)));

        mvc.perform(put(ITEM, clienteId, id).contentType(MediaType.APPLICATION_JSON)
                        .content(corpo(" ", "1", null, null, "São Paulo", "SP", "01310-100", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("logradouro"));
    }

    @Test
    void promoverUmEnderecoViaAtualizacaoRebaixaOPrincipalAtual() throws Exception {
        long clienteId = criarCliente();
        long primeiro = criar(clienteId, corpo("Rua Primeira", null));
        long segundo = criar(clienteId, corpo("Rua Segunda", null));
        long terceiro = criar(clienteId, corpo("Rua Terceira", null));

        mvc.perform(put(ITEM, clienteId, segundo).contentType(MediaType.APPLICATION_JSON).content(corpo("Rua Segunda", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principal").value(true));

        esperarPrincipal(clienteId, primeiro, false);
        esperarPrincipal(clienteId, segundo, true);
        esperarPrincipal(clienteId, terceiro, false);
        mvc.perform(get(RAIZ, clienteId))
                .andExpect(jsonPath("$.itens[?(@.principal == true)]", hasSize(1)));
    }

    @Test
    void atualizarEnderecoNaoPrincipalSemPedirPrincipalNaoMudaOPrincipal() throws Exception {
        long clienteId = criarCliente();
        long primeiro = criar(clienteId, corpo("Rua Primeira", null));
        long segundo = criar(clienteId, corpo("Rua Segunda", null));

        mvc.perform(put(ITEM, clienteId, segundo).contentType(MediaType.APPLICATION_JSON).content(corpo("Rua Segunda Editada", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principal").value(false));
        mvc.perform(put(ITEM, clienteId, segundo).contentType(MediaType.APPLICATION_JSON).content(corpo("Rua Segunda Editada de Novo", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principal").value(false));

        esperarPrincipal(clienteId, primeiro, true);
    }

    @Test
    void atualizarOPrincipalMantendoPrincipalTrueFunciona() throws Exception {
        long clienteId = criarCliente();
        long id = criar(clienteId, corpo("Rua Unica", null));

        mvc.perform(put(ITEM, clienteId, id).contentType(MediaType.APPLICATION_JSON).content(corpo("Rua Unica Editada", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logradouro").value("Rua Unica Editada"))
                .andExpect(jsonPath("$.principal").value(true));
    }

    @Test
    void naoDeixaOPrincipalAtualVirarNaoPrincipalSemOutroPrincipal() throws Exception {
        long clienteId = criarCliente();
        long principal = criar(clienteId, corpo("Rua Principal", null));
        long outro = criar(clienteId, corpo("Rua Outra", null));

        mvc.perform(put(ITEM, clienteId, principal).contentType(MediaType.APPLICATION_JSON).content(corpo("Rua Principal Editada", false)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Regra de negócio violada"))
                .andExpect(jsonPath("$.detail").value(containsString("promova outro endereço a principal antes")));

        // Principal nulo vale falso, entao a mesma regra vale quando o campo e omitido.
        mvc.perform(put(ITEM, clienteId, principal).contentType(MediaType.APPLICATION_JSON).content(corpo("Rua Principal Editada", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("promova outro endereço a principal antes")));

        // Nada mudou.
        mvc.perform(get(ITEM, clienteId, principal))
                .andExpect(jsonPath("$.logradouro").value("Rua Principal"))
                .andExpect(jsonPath("$.principal").value(true));
        esperarPrincipal(clienteId, outro, false);
    }

    @Test
    void naoDeixaOUnicoEnderecoVirarNaoPrincipal() throws Exception {
        long clienteId = criarCliente();
        long id = criar(clienteId, corpo("Rua Unica", null));

        mvc.perform(put(ITEM, clienteId, id).contentType(MediaType.APPLICATION_JSON).content(corpo("Rua Unica", false)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("promova outro endereço a principal antes")));

        esperarPrincipal(clienteId, id, true);
    }

    @Test
    void trocaDePrincipalEmDoisPassosComRegrasRespeitadas() throws Exception {
        long clienteId = criarCliente();
        long antigo = criar(clienteId, corpo("Rua Antiga", null));
        long novo = criar(clienteId, corpo("Rua Nova", null));

        // Promover o novo ja rebaixa o antigo; depois disso o antigo pode ser editado como nao principal.
        mvc.perform(put(ITEM, clienteId, novo).contentType(MediaType.APPLICATION_JSON).content(corpo("Rua Nova", true)))
                .andExpect(status().isOk());
        mvc.perform(put(ITEM, clienteId, antigo).contentType(MediaType.APPLICATION_JSON).content(corpo("Rua Antiga Editada", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principal").value(false));

        esperarPrincipal(clienteId, novo, true);
    }

    // ---- exclusao -------------------------------------------------------------------

    @Test
    void excluiEnderecoNaoPrincipal() throws Exception {
        long clienteId = criarCliente();
        long principal = criar(clienteId, corpo("Rua Principal", null));
        long secundario = criar(clienteId, corpo("Rua Secundaria", null));

        mvc.perform(delete(ITEM, clienteId, secundario)).andExpect(status().isNoContent());

        mvc.perform(get(ITEM, clienteId, secundario)).andExpect(status().isNotFound());
        esperarPrincipal(clienteId, principal, true);
        mvc.perform(delete(ITEM, clienteId, secundario)).andExpect(status().isNotFound());
    }

    @Test
    void naoExcluiOPrincipalEnquantoExistiremOutrosEnderecos() throws Exception {
        long clienteId = criarCliente();
        long principal = criar(clienteId, corpo("Rua Principal", null));
        long outro = criar(clienteId, corpo("Rua Outra", null));

        mvc.perform(delete(ITEM, clienteId, principal))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Regra de negócio violada"))
                .andExpect(jsonPath("$.detail").value(containsString("promova outro endereço a principal antes")));

        esperarPrincipal(clienteId, principal, true);
        esperarPrincipal(clienteId, outro, false);

        // Depois de promover o outro, o antigo principal pode sair e o outro segue como principal.
        mvc.perform(put(ITEM, clienteId, outro).contentType(MediaType.APPLICATION_JSON).content(corpo("Rua Outra", true)))
                .andExpect(status().isOk());
        mvc.perform(delete(ITEM, clienteId, principal)).andExpect(status().isNoContent());

        esperarPrincipal(clienteId, outro, true);
        mvc.perform(get(RAIZ, clienteId))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.itens[?(@.principal == true)]", hasSize(1)));
    }

    @Test
    void excluiOUnicoEnderecoMesmoSendoOPrincipalEDepoisOClientePodeSerExcluido() throws Exception {
        long clienteId = criarCliente();
        long id = criar(clienteId, corpo("Rua Unica", null));

        // Com endereco, o cliente tem vinculos e nao pode ser excluido (regra do cliente).
        mvc.perform(delete("/api/clientes/{id}", clienteId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("1 endereço(s)")));

        mvc.perform(delete(ITEM, clienteId, id)).andExpect(status().isNoContent());
        mvc.perform(get(ITEM, clienteId, id)).andExpect(status().isNotFound());
        mvc.perform(get(RAIZ, clienteId)).andExpect(jsonPath("$.total").value(0));

        mvc.perform(delete("/api/clientes/{id}", clienteId)).andExpect(status().isNoContent());
    }

    @Test
    void depoisDeExcluirTodosOsEnderecosOProximoVoltaASerOPrincipal() throws Exception {
        long clienteId = criarCliente();
        long primeiro = criar(clienteId, corpo("Rua Primeira", null));
        mvc.perform(delete(ITEM, clienteId, primeiro)).andExpect(status().isNoContent());

        mvc.perform(post(RAIZ, clienteId).contentType(MediaType.APPLICATION_JSON).content(corpo("Rua Nova", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.principal").value(true));
    }

    // ---- listagem -------------------------------------------------------------------

    @Test
    void listaVaziaQuandoOClienteNaoTemEnderecos() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(get(RAIZ, clienteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.totalPaginas").value(0))
                .andExpect(jsonPath("$.itens.length()").value(0));
    }

    @Test
    void listaPaginadaSoComEnderecosDoClienteNaOrdemDeCadastro() throws Exception {
        long clienteId = criarCliente();
        long outroCliente = criarCliente();
        criar(clienteId, corpo("Rua 1", null));
        criar(clienteId, corpo("Rua 2", null));
        criar(clienteId, corpo("Rua 3", null));
        criar(clienteId, corpo("Rua 4", null));
        criar(clienteId, corpo("Rua 5", null));
        criar(outroCliente, corpo("Rua de Outro Cliente", null));

        mvc.perform(get(RAIZ, clienteId).param("size", "2").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.totalPaginas").value(3))
                .andExpect(jsonPath("$.pagina").value(0))
                .andExpect(jsonPath("$.tamanho").value(2))
                .andExpect(jsonPath("$.itens.length()").value(2))
                .andExpect(jsonPath("$.itens[0].logradouro").value("Rua 1"))
                .andExpect(jsonPath("$.itens[0].principal").value(true))
                .andExpect(jsonPath("$.itens[0].clienteId").value(clienteId))
                .andExpect(jsonPath("$.itens[1].logradouro").value("Rua 2"));

        mvc.perform(get(RAIZ, clienteId).param("size", "2").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andExpect(jsonPath("$.itens[0].logradouro").value("Rua 5"));

        mvc.perform(get(RAIZ, clienteId))
                .andExpect(jsonPath("$.tamanho").value(20))
                .andExpect(jsonPath("$.itens.length()").value(5))
                .andExpect(jsonPath("$.itens[?(@.clienteId != " + clienteId + ")]", hasSize(0)));
    }

    @Test
    void listaAceitaOrdenacaoEOutrosCamposERecusaCampoInexistente() throws Exception {
        long clienteId = criarCliente();
        criar(clienteId, corpo("Rua B", null));
        criar(clienteId, corpo("Rua A", null));

        mvc.perform(get(RAIZ, clienteId).param("sort", "logradouro,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].logradouro").value("Rua A"))
                .andExpect(jsonPath("$.itens[1].logradouro").value("Rua B"));

        mvc.perform(get(RAIZ, clienteId).param("sort", "campoInexistente,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Ordenação inválida"));
    }

    // ---- infraestrutura -------------------------------------------------------------

    /**
     * O Open Session in View esta desligado, entao a resposta e montada depois que a sessao do
     * Hibernate fechou. Este teste garante que ler o id do cliente a partir do proxy preguicoso nao
     * exige carregar o cliente (senao a listagem daria LazyInitializationException em producao, o
     * que os demais testes nao pegam porque rodam dentro de uma unica transacao).
     */
    @Test
    void idDoClienteNaRespostaNaoExigeCarregarOCliente() throws Exception {
        long clienteId = criarCliente();
        long id = criar(clienteId, corpo("Rua Preguicosa", null));
        em.flush();
        em.clear();

        Endereco endereco = enderecos.findById(id).orElseThrow();

        assertFalse(Hibernate.isInitialized(endereco.getCliente()));
        assertEquals(clienteId, EnderecoResponse.de(endereco).clienteId());
        assertFalse(Hibernate.isInitialized(endereco.getCliente()));
    }
}
