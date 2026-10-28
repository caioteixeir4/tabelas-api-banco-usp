package br.com.kawashi.banco.contato;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
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
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import br.com.kawashi.banco.testes.GeradorDeDocumentos;
import br.com.kawashi.banco.testes.IntegracaoApi;
import jakarta.persistence.EntityManager;

@IntegracaoApi
class ContatoApiTest {

    private static final long ID_INEXISTENTE = 999_999_999L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EntityManager em;

    // ---- apoio ------------------------------------------------------------------

    private static String texto(String valor) {
        return valor == null ? "null" : "\"" + valor + "\"";
    }

    private static String json(String nome, String email, String telefone, Boolean principal) {
        return """
                {"nome": %s, "email": %s, "telefone": %s, "principal": %s}
                """.formatted(texto(nome), texto(email), texto(telefone), principal);
    }

    private long criarCliente() throws Exception {
        String corpo = """
                {"cnpj": "%s", "razaoSocial": "Cliente de Contatos LTDA"}
                """.formatted(GeradorDeDocumentos.cnpj());
        String resposta = mvc.perform(post("/api/clientes").contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resposta, "$.id")).longValue();
    }

    private long criarContato(long clienteId, String nome, String email, String telefone, Boolean principal)
            throws Exception {
        String resposta = mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId)
                        .contentType(MediaType.APPLICATION_JSON).content(json(nome, email, telefone, principal)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(resposta, "$.id")).longValue();
    }

    private void esperaPrincipal(long clienteId, long contatoId, boolean principal) throws Exception {
        mvc.perform(get("/api/clientes/{clienteId}/contatos/{id}", clienteId, contatoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principal").value(principal));
    }

    // ---- criacao: caminho feliz e normalizacao ----------------------------------

    @Test
    void criaContatoNormalizandoTelefoneEEmailEDevolve201ComLocation() throws Exception {
        long clienteId = criarCliente();

        String corpo = mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("  Maria Silva  ", "Maria.Silva@Empresa.COM.br", "(11) 98765-4321", false)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/clientes/" + clienteId + "/contatos/")))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.clienteId").value(clienteId))
                .andExpect(jsonPath("$.nome").value("Maria Silva"))
                .andExpect(jsonPath("$.email").value("maria.silva@empresa.com.br"))
                .andExpect(jsonPath("$.telefone").value("11987654321"))
                .andExpect(jsonPath("$.principal").value(true))
                .andExpect(jsonPath("$.criadoEm").exists())
                .andExpect(jsonPath("$.atualizadoEm").exists())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(corpo, "$.id")).longValue();

        // Forca ida e volta ao banco: o que foi gravado e o que a API devolve.
        em.flush();
        em.clear();
        mvc.perform(get("/api/clientes/{clienteId}/contatos/{id}", clienteId, id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clienteId").value(clienteId))
                .andExpect(jsonPath("$.email").value("maria.silva@empresa.com.br"))
                .andExpect(jsonPath("$.telefone").value("11987654321"))
                .andExpect(jsonPath("$.principal").value(true));
    }

    @Test
    void aceitaTelefoneSoComDigitosFixoOuCelularESoEmailOuSoTelefone() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Fixo", null, "1134567890", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.telefone").value("1134567890"))
                .andExpect(jsonPath("$.email").value(nullValue()));

        mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Celular", null, "11987654321", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.telefone").value("11987654321"));

        mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json("So Email", "so.email@empresa.com", null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("so.email@empresa.com"))
                .andExpect(jsonPath("$.telefone").value(nullValue()));
    }

    @Test
    void emailETelefoneEmBrancoViramNulos() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Branco", "", "   ", null)))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Email Vazio", "", "1134567890", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(nullValue()))
                .andExpect(jsonPath("$.telefone").value("1134567890"));

        mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Telefone Vazio", "vazio@empresa.com", "  ", null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("vazio@empresa.com"))
                .andExpect(jsonPath("$.telefone").value(nullValue()));
    }

    // ---- validacao de campos (400) ----------------------------------------------

    @Test
    void recusaNomeEmBrancoOuAusente() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json("   ", "a@empresa.com", null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Dados inválidos"))
                .andExpect(jsonPath("$.erros[0].campo").value("nome"))
                .andExpect(jsonPath("$.erros[0].mensagem").value("O nome é obrigatório"));

        mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"a@empresa.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("nome"));
    }

    @Test
    void recusaNomeComMaisDe150Caracteres() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json("N".repeat(151), "a@empresa.com", null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("nome"))
                .andExpect(jsonPath("$.erros[0].mensagem").value(containsString("150")));

        mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json("N".repeat(150), "a@empresa.com", null, null)))
                .andExpect(status().isCreated());
    }

    @Test
    void recusaEmailComFormatoInvalido() throws Exception {
        long clienteId = criarCliente();

        for (String invalido : new String[] {"isto-nao-e-email", "sem-dominio@", "@sem-usuario.com"}) {
            mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId).contentType(MediaType.APPLICATION_JSON)
                            .content(json("Fulano", invalido, null, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.erros[0].campo").value("email"))
                    .andExpect(jsonPath("$.erros[0].mensagem").value("O e-mail é inválido"));
        }
    }

    @Test
    void recusaEmailComMaisDe150Caracteres() throws Exception {
        long clienteId = criarCliente();
        // Formato valido (usuario ate 64, rotulos do dominio ate 63), mas com 156 caracteres.
        String longo = "a".repeat(60) + "@" + "b".repeat(60) + "." + "c".repeat(30) + ".com";

        mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Fulano", longo, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("email"))
                .andExpect(jsonPath("$.erros[0].mensagem").value(containsString("150")));
    }

    @Test
    void recusaTelefoneSemDezOuOnzeDigitosOuComLetras() throws Exception {
        long clienteId = criarCliente();

        // 9 digitos, 12 digitos, so pontuacao e letras misturadas.
        for (String invalido : new String[] {"987654321", "(11) 98765-43210", "(  )", "11 9876-ABCD", "1198765432a"}) {
            mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId).contentType(MediaType.APPLICATION_JSON)
                            .content(json("Fulano", "a@empresa.com", invalido, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Dados inválidos"))
                    .andExpect(jsonPath("$.erros[0].campo").value("telefone"))
                    .andExpect(jsonPath("$.erros[0].mensagem").value(containsString("10 ou 11 dígitos")));
        }
    }

    @Test
    void recusaPrincipalComTipoErradoEJsonQuebrado() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\": \"Fulano\", \"email\": \"a@empresa.com\", \"principal\": \"talvez\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Corpo da requisição ilegível"));

        mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content("{isto nao e json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Corpo da requisição ilegível"));
    }

    @Test
    void validaOsMesmosCamposNaAtualizacao() throws Exception {
        long clienteId = criarCliente();
        long id = criarContato(clienteId, "Original", "original@empresa.com", null, null);

        mvc.perform(put("/api/clientes/{clienteId}/contatos/{id}", clienteId, id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("", "original@empresa.com", null, true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("nome"));

        mvc.perform(put("/api/clientes/{clienteId}/contatos/{id}", clienteId, id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Original", "email-invalido", "123", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros.length()").value(2));
    }

    @Test
    void parametroDoCaminhoComTipoErradoViraErro400() throws Exception {
        mvc.perform(get("/api/clientes/{clienteId}/contatos", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(endsWith("tem um valor com tipo errado.")));

        long clienteId = criarCliente();
        mvc.perform(get("/api/clientes/{clienteId}/contatos/{id}", clienteId, "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(endsWith("tem um valor com tipo errado.")));
    }

    // ---- regra 1: cliente do caminho existe e o contato pertence a ele (404) ----

    @Test
    void clienteInexistenteDevolve404EmTodosOsEndpoints() throws Exception {
        mvc.perform(post("/api/clientes/{clienteId}/contatos", ID_INEXISTENTE)
                        .contentType(MediaType.APPLICATION_JSON).content(json("Fulano", "a@empresa.com", null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Não encontrado"))
                .andExpect(jsonPath("$.detail").value(containsString("Cliente")));

        mvc.perform(get("/api/clientes/{clienteId}/contatos", ID_INEXISTENTE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("Cliente")));

        mvc.perform(get("/api/clientes/{clienteId}/contatos/{id}", ID_INEXISTENTE, 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("Cliente")));

        mvc.perform(put("/api/clientes/{clienteId}/contatos/{id}", ID_INEXISTENTE, 1)
                        .contentType(MediaType.APPLICATION_JSON).content(json("Fulano", "a@empresa.com", null, true)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("Cliente")));

        mvc.perform(delete("/api/clientes/{clienteId}/contatos/{id}", ID_INEXISTENTE, 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("Cliente")));
    }

    @Test
    void contatoInexistenteDevolve404() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(get("/api/clientes/{clienteId}/contatos/{id}", clienteId, ID_INEXISTENTE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Não encontrado"))
                .andExpect(jsonPath("$.detail").value(containsString("Contato")));

        mvc.perform(put("/api/clientes/{clienteId}/contatos/{id}", clienteId, ID_INEXISTENTE)
                        .contentType(MediaType.APPLICATION_JSON).content(json("Fulano", "a@empresa.com", null, true)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("Contato")));

        mvc.perform(delete("/api/clientes/{clienteId}/contatos/{id}", clienteId, ID_INEXISTENTE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("Contato")));
    }

    @Test
    void contatoDeOutroClienteETratadoComoInexistente() throws Exception {
        long clienteA = criarCliente();
        long clienteB = criarCliente();
        long contatoDeA = criarContato(clienteA, "Contato de A", "a@empresa.com", null, null);

        mvc.perform(get("/api/clientes/{clienteId}/contatos/{id}", clienteB, contatoDeA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("Contato")));

        mvc.perform(put("/api/clientes/{clienteId}/contatos/{id}", clienteB, contatoDeA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Invadido", "invasor@empresa.com", null, true)))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/clientes/{clienteId}/contatos/{id}", clienteB, contatoDeA))
                .andExpect(status().isNotFound());

        // Nada mudou no contato de A e ele continua no cliente A.
        mvc.perform(get("/api/clientes/{clienteId}/contatos/{id}", clienteA, contatoDeA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Contato de A"))
                .andExpect(jsonPath("$.clienteId").value(clienteA));
    }

    // ---- regra 2: so se cria contato para cliente ativo (422) -------------------

    @Test
    void naoCriaContatoParaClienteInativo() throws Exception {
        long clienteId = criarCliente();
        mvc.perform(post("/api/clientes/{id}/inativar", clienteId)).andExpect(status().isOk());

        mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Fulano", "a@empresa.com", null, null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Regra de negócio violada"))
                .andExpect(jsonPath("$.detail").value(containsString("cliente inativo")));

        mvc.perform(get("/api/clientes/{clienteId}/contatos", clienteId))
                .andExpect(jsonPath("$.total").value(0));

        // Reativado, volta a aceitar contatos.
        mvc.perform(post("/api/clientes/{id}/reativar", clienteId)).andExpect(status().isOk());
        criarContato(clienteId, "Fulano", "a@empresa.com", null, null);
    }

    @Test
    void contatoJaExistenteDeClienteInativoPodeSerAtualizadoEExcluido() throws Exception {
        long clienteId = criarCliente();
        long id = criarContato(clienteId, "Antes", "antes@empresa.com", null, null);
        mvc.perform(post("/api/clientes/{id}/inativar", clienteId)).andExpect(status().isOk());

        mvc.perform(put("/api/clientes/{clienteId}/contatos/{id}", clienteId, id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Depois", "depois@empresa.com", null, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Depois"));

        mvc.perform(delete("/api/clientes/{clienteId}/contatos/{id}", clienteId, id))
                .andExpect(status().isNoContent());
    }

    // ---- regra 3: pelo menos um meio de contato (422) ---------------------------

    @Test
    void naoCriaContatoSemEmailENemTelefone() throws Exception {
        long clienteId = criarCliente();

        for (String corpo : new String[] {
                json("Sem Meio", null, null, null),
                json("Sem Meio", "", "", null),
                json("Sem Meio", "", "   ", true),
                "{\"nome\": \"Sem Meio\"}"}) {
            mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId).contentType(MediaType.APPLICATION_JSON)
                            .content(corpo))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.title").value("Regra de negócio violada"))
                    .andExpect(jsonPath("$.detail").value(containsString("e-mail ou o telefone")));
        }

        mvc.perform(get("/api/clientes/{clienteId}/contatos", clienteId))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void naoAtualizaContatoParaFicarSemEmailENemTelefone() throws Exception {
        long clienteId = criarCliente();
        long id = criarContato(clienteId, "Completo", "completo@empresa.com", "11987654321", null);

        mvc.perform(put("/api/clientes/{clienteId}/contatos/{id}", clienteId, id)
                        .contentType(MediaType.APPLICATION_JSON).content(json("Completo", null, null, true)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("e-mail ou o telefone")));

        mvc.perform(put("/api/clientes/{clienteId}/contatos/{id}", clienteId, id)
                        .contentType(MediaType.APPLICATION_JSON).content(json("Completo", "", "  ", true)))
                .andExpect(status().isUnprocessableEntity());

        // Continua como estava.
        mvc.perform(get("/api/clientes/{clienteId}/contatos/{id}", clienteId, id))
                .andExpect(jsonPath("$.email").value("completo@empresa.com"))
                .andExpect(jsonPath("$.telefone").value("11987654321"));
    }

    // ---- regra 4: o primeiro contato e sempre o principal -----------------------

    @Test
    void primeiroContatoESempreOPrincipalMesmoComPrincipalFalseOuNulo() throws Exception {
        long clienteA = criarCliente();
        long clienteB = criarCliente();

        mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteA).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Primeiro de A", "a1@empresa.com", null, false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.principal").value(true));

        mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteB).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Primeiro de B", "b1@empresa.com", null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.principal").value(true));
    }

    @Test
    void principalNuloOuFalseNosContatosSeguintesViraFalseESemRebaixarOPrincipal() throws Exception {
        long clienteId = criarCliente();
        long primeiro = criarContato(clienteId, "Primeiro", "p@empresa.com", null, null);

        mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Segundo", "s@empresa.com", null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.principal").value(false));
        mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Terceiro", "t@empresa.com", null, false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.principal").value(false));

        esperaPrincipal(clienteId, primeiro, true);
    }

    @Test
    void depoisDeExcluirTodosOsContatosOProximoVoltaASerOPrincipal() throws Exception {
        long clienteId = criarCliente();
        long unico = criarContato(clienteId, "Unico", "u@empresa.com", null, null);
        mvc.perform(delete("/api/clientes/{clienteId}/contatos/{id}", clienteId, unico))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/clientes/{clienteId}/contatos", clienteId).contentType(MediaType.APPLICATION_JSON)
                        .content(json("Novo Primeiro", "novo@empresa.com", null, false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.principal").value(true));
    }

    // ---- regra 5: um unico principal por cliente (rebaixa os outros) ------------

    @Test
    void criarComPrincipalTrueRebaixaOPrincipalAnteriorNaMesmaTransacao() throws Exception {
        long clienteId = criarCliente();
        long antigo = criarContato(clienteId, "Antigo", "antigo@empresa.com", null, null);
        long novo = criarContato(clienteId, "Novo", "novo@empresa.com", null, true);

        esperaPrincipal(clienteId, antigo, false);
        esperaPrincipal(clienteId, novo, true);

        mvc.perform(get("/api/clientes/{clienteId}/contatos", clienteId))
                .andExpect(jsonPath("$.itens[?(@.principal == true)]", hasSize(1)));
    }

    @Test
    void atualizarComPrincipalTrueRebaixaOsDemais() throws Exception {
        long clienteId = criarCliente();
        long primeiro = criarContato(clienteId, "Primeiro", "p@empresa.com", null, null);
        long segundo = criarContato(clienteId, "Segundo", "s@empresa.com", null, null);
        long terceiro = criarContato(clienteId, "Terceiro", "t@empresa.com", null, null);

        mvc.perform(put("/api/clientes/{clienteId}/contatos/{id}", clienteId, terceiro)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Terceiro", "t@empresa.com", null, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principal").value(true));

        esperaPrincipal(clienteId, primeiro, false);
        esperaPrincipal(clienteId, segundo, false);
        esperaPrincipal(clienteId, terceiro, true);

        // Confere no banco de verdade, nao so no cache da sessao.
        em.flush();
        em.clear();
        mvc.perform(get("/api/clientes/{clienteId}/contatos", clienteId))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.itens[?(@.principal == true)]", hasSize(1)))
                .andExpect(jsonPath("$.itens[?(@.id == " + terceiro + ")].principal", contains(true)));
    }

    @Test
    void principalDeUmClienteNaoMexeNosContatosDeOutroCliente() throws Exception {
        long clienteA = criarCliente();
        long clienteB = criarCliente();
        long principalDeA = criarContato(clienteA, "Principal de A", "a@empresa.com", null, null);
        long principalDeB = criarContato(clienteB, "Principal de B", "b@empresa.com", null, null);

        criarContato(clienteA, "Novo principal de A", "a2@empresa.com", null, true);

        esperaPrincipal(clienteA, principalDeA, false);
        esperaPrincipal(clienteB, principalDeB, true);
    }

    // ---- regra 6: nao deixar o cliente sem principal ao atualizar (422) ---------

    @Test
    void naoRebaixaOPrincipalAtualSemHaverOutroPrincipal() throws Exception {
        long clienteId = criarCliente();
        long principal = criarContato(clienteId, "Principal", "p@empresa.com", null, null);
        criarContato(clienteId, "Outro", "o@empresa.com", null, null);

        mvc.perform(put("/api/clientes/{clienteId}/contatos/{id}", clienteId, principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Principal", "p@empresa.com", null, false)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Regra de negócio violada"))
                .andExpect(jsonPath("$.detail").value(containsString("contato principal")));

        esperaPrincipal(clienteId, principal, true);
    }

    @Test
    void omitirPrincipalNoPutDoPrincipalAtualTambemEhRecusado() throws Exception {
        long clienteId = criarCliente();
        long principal = criarContato(clienteId, "Principal", "p@empresa.com", null, null);

        // principal nulo vale false; como e o unico (e o principal), o PUT deixaria o cliente sem principal.
        mvc.perform(put("/api/clientes/{clienteId}/contatos/{id}", clienteId, principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\": \"Principal\", \"email\": \"p@empresa.com\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("contato principal")));

        esperaPrincipal(clienteId, principal, true);
    }

    @Test
    void atualizaDadosDoPrincipalMantendoPrincipalTrue() throws Exception {
        long clienteId = criarCliente();
        long principal = criarContato(clienteId, "Principal", "p@empresa.com", null, null);
        long outro = criarContato(clienteId, "Outro", "o@empresa.com", null, null);

        mvc.perform(put("/api/clientes/{clienteId}/contatos/{id}", clienteId, principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Principal Renomeado", "novo.p@empresa.com", "(21) 3456-7890", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Principal Renomeado"))
                .andExpect(jsonPath("$.principal").value(true));

        esperaPrincipal(clienteId, outro, false);
    }

    @Test
    void trocaDePrincipalFuncionaMarcandoOutroComoPrincipal() throws Exception {
        long clienteId = criarCliente();
        long antigo = criarContato(clienteId, "Antigo", "antigo@empresa.com", null, null);
        long novo = criarContato(clienteId, "Novo", "novo@empresa.com", null, null);

        mvc.perform(put("/api/clientes/{clienteId}/contatos/{id}", clienteId, novo)
                        .contentType(MediaType.APPLICATION_JSON).content(json("Novo", "novo@empresa.com", null, true)))
                .andExpect(status().isOk());

        // Agora o antigo ja nao e o principal e pode ser editado com principal=false.
        mvc.perform(put("/api/clientes/{clienteId}/contatos/{id}", clienteId, antigo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Antigo Editado", "antigo@empresa.com", null, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Antigo Editado"))
                .andExpect(jsonPath("$.principal").value(false));

        esperaPrincipal(clienteId, novo, true);
    }

    // ---- regra 7: excluir o principal enquanto houver outros (422) --------------

    @Test
    void naoExcluiOContatoPrincipalEnquantoExistiremOutros() throws Exception {
        long clienteId = criarCliente();
        long principal = criarContato(clienteId, "Principal", "p@empresa.com", null, null);
        long outro = criarContato(clienteId, "Outro", "o@empresa.com", null, null);

        mvc.perform(delete("/api/clientes/{clienteId}/contatos/{id}", clienteId, principal))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Regra de negócio violada"))
                .andExpect(jsonPath("$.detail").value(containsString("contato principal")));

        mvc.perform(get("/api/clientes/{clienteId}/contatos", clienteId))
                .andExpect(jsonPath("$.total").value(2));

        // Caminho correto: promover o outro e so entao excluir o antigo principal.
        mvc.perform(put("/api/clientes/{clienteId}/contatos/{id}", clienteId, outro)
                        .contentType(MediaType.APPLICATION_JSON).content(json("Outro", "o@empresa.com", null, true)))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/clientes/{clienteId}/contatos/{id}", clienteId, principal))
                .andExpect(status().isNoContent());

        esperaPrincipal(clienteId, outro, true);
    }

    @Test
    void excluiContatoQueNaoEPrincipalEMantemOPrincipal() throws Exception {
        long clienteId = criarCliente();
        long principal = criarContato(clienteId, "Principal", "p@empresa.com", null, null);
        long outro = criarContato(clienteId, "Outro", "o@empresa.com", null, null);

        mvc.perform(delete("/api/clientes/{clienteId}/contatos/{id}", clienteId, outro))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/clientes/{clienteId}/contatos/{id}", clienteId, outro))
                .andExpect(status().isNotFound());
        esperaPrincipal(clienteId, principal, true);
    }

    @Test
    void excluiOUnicoContatoDoClienteMesmoSendoPrincipal() throws Exception {
        long clienteId = criarCliente();
        long unico = criarContato(clienteId, "Unico", "u@empresa.com", null, null);

        mvc.perform(delete("/api/clientes/{clienteId}/contatos/{id}", clienteId, unico))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/clientes/{clienteId}/contatos", clienteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    // ---- leitura, atualizacao e listagem ----------------------------------------

    @Test
    void buscaContatoPorIdDentroDoCliente() throws Exception {
        long clienteId = criarCliente();
        long id = criarContato(clienteId, "Busca", "busca@empresa.com", "(11) 3456-7890", null);

        mvc.perform(get("/api/clientes/{clienteId}/contatos/{id}", clienteId, id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.clienteId").value(clienteId))
                .andExpect(jsonPath("$.nome").value("Busca"))
                .andExpect(jsonPath("$.email").value("busca@empresa.com"))
                .andExpect(jsonPath("$.telefone").value("1134567890"))
                .andExpect(jsonPath("$.principal").value(true))
                .andExpect(jsonPath("$.criadoEm").exists())
                .andExpect(jsonPath("$.atualizadoEm").exists());
    }

    @Test
    void atualizaTodosOsCamposNormalizandoEPodeLimparEmailOuTelefone() throws Exception {
        long clienteId = criarCliente();
        long principal = criarContato(clienteId, "Original", "original@empresa.com", "11987654321", null);

        mvc.perform(put("/api/clientes/{clienteId}/contatos/{id}", clienteId, principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("  Novo Nome  ", "NOVO@Empresa.com", "(21) 98888-7777", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(principal))
                .andExpect(jsonPath("$.clienteId").value(clienteId))
                .andExpect(jsonPath("$.nome").value("Novo Nome"))
                .andExpect(jsonPath("$.email").value("novo@empresa.com"))
                .andExpect(jsonPath("$.telefone").value("21988887777"));

        // Limpa o telefone (fica so o email) e depois limpa o email (fica so o telefone).
        mvc.perform(put("/api/clientes/{clienteId}/contatos/{id}", clienteId, principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Novo Nome", "novo@empresa.com", null, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.telefone").value(nullValue()));
        mvc.perform(put("/api/clientes/{clienteId}/contatos/{id}", clienteId, principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Novo Nome", null, "1134567890", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(nullValue()))
                .andExpect(jsonPath("$.telefone").value("1134567890"));

        em.flush();
        em.clear();
        mvc.perform(get("/api/clientes/{clienteId}/contatos/{id}", clienteId, principal))
                .andExpect(jsonPath("$.nome").value("Novo Nome"))
                .andExpect(jsonPath("$.email").value(nullValue()))
                .andExpect(jsonPath("$.telefone").value("1134567890"));
    }

    @Test
    void listaApenasOsContatosDoClienteComPaginacaoOrdenadaPorIdPorPadrao() throws Exception {
        long clienteId = criarCliente();
        long outroCliente = criarCliente();
        long primeiro = criarContato(clienteId, "Alfa", "alfa@empresa.com", null, null);
        long segundo = criarContato(clienteId, "Beta", "beta@empresa.com", null, null);
        criarContato(clienteId, "Gama", "gama@empresa.com", null, null);
        criarContato(outroCliente, "De Outro Cliente", "outro@empresa.com", null, null);

        mvc.perform(get("/api/clientes/{clienteId}/contatos", clienteId).param("size", "2").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPaginas").value(2))
                .andExpect(jsonPath("$.tamanho").value(2))
                .andExpect(jsonPath("$.pagina").value(0))
                .andExpect(jsonPath("$.itens.length()").value(2))
                .andExpect(jsonPath("$.itens[0].id").value(primeiro))
                .andExpect(jsonPath("$.itens[1].id").value(segundo));

        mvc.perform(get("/api/clientes/{clienteId}/contatos", clienteId).param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagina").value(1))
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andExpect(jsonPath("$.itens[0].nome").value("Gama"))
                .andExpect(jsonPath("$.itens[0].clienteId").value(clienteId));
    }

    @Test
    void listaComOrdenacaoPorNomeDecrescenteERecusaCampoInexistente() throws Exception {
        long clienteId = criarCliente();
        criarContato(clienteId, "Alfa", "alfa@empresa.com", null, null);
        criarContato(clienteId, "Beta", "beta@empresa.com", null, null);
        criarContato(clienteId, "Gama", "gama@empresa.com", null, null);

        mvc.perform(get("/api/clientes/{clienteId}/contatos", clienteId).param("sort", "nome,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[0].nome").value("Gama"))
                .andExpect(jsonPath("$.itens[1].nome").value("Beta"))
                .andExpect(jsonPath("$.itens[2].nome").value("Alfa"));

        mvc.perform(get("/api/clientes/{clienteId}/contatos", clienteId).param("sort", "campoInexistente,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Ordenação inválida"));
    }

    @Test
    void clienteSemContatosTemListagemVazia() throws Exception {
        long clienteId = criarCliente();

        mvc.perform(get("/api/clientes/{clienteId}/contatos", clienteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.totalPaginas").value(0))
                .andExpect(jsonPath("$.itens", hasSize(0)));
    }

    // ---- integracao com a regra de exclusao do cliente --------------------------

    @Test
    void clienteComContatoNaoPodeSerExcluidoMasPodeDepoisDeExcluirOsContatos() throws Exception {
        long clienteId = criarCliente();
        long contato = criarContato(clienteId, "Vinculo", "vinculo@empresa.com", null, null);

        mvc.perform(delete("/api/clientes/{id}", clienteId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("1 contato(s)")));

        mvc.perform(delete("/api/clientes/{clienteId}/contatos/{id}", clienteId, contato))
                .andExpect(status().isNoContent());
        em.flush();

        mvc.perform(delete("/api/clientes/{id}", clienteId))
                .andExpect(status().isNoContent());
    }
}
