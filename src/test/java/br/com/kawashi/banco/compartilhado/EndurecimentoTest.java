package br.com.kawashi.banco.compartilhado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import br.com.kawashi.banco.testes.GeradorDeDocumentos;
import br.com.kawashi.banco.testes.IntegracaoApi;

/**
 * Endurecimento da API a partir da revisao independente do codigo: paginacao, filtro, tamanho de
 * corpo, entrada suja em documentos, carimbos de tempo, e a decisao "pai inativo so barra a
 * criacao". Cada teste fixa uma correcao ou uma decisao que antes nao tinha teste.
 */
@IntegracaoApi
class EndurecimentoTest {

    @Autowired
    private MockMvc mvc;

    // ---- paginacao -----------------------------------------------------------------------

    @Test
    void paginaGigantesVira400EmVezDe500() throws Exception {
        mvc.perform(get("/api/clientes").param("page", "2147483647"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Parâmetro inválido"))
                .andExpect(jsonPath("$.detail").value(containsString("page")));
    }

    @Test
    void paginaGigantesEhRecusadaTambemNasListagensAninhadas() throws Exception {
        long clienteId = criarCliente("Pai LTDA");
        mvc.perform(get("/api/clientes/{id}/enderecos", clienteId).param("page", "2147483647").param("size", "100"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void paginacaoEhEstavelMesmoComRazaoSocialRepetida() throws Exception {
        String marca = "Empate" + ThreadLocalRandom.current().nextInt(1_000_000);
        Set<Long> esperados = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            esperados.add(criarCliente(marca + " Mesma Razao"));
        }

        Set<Long> vistos = new HashSet<>();
        int linhas = 0;
        for (int pagina = 0; pagina < 3; pagina++) {
            String corpo = mvc.perform(get("/api/clientes").param("razaoSocial", marca).param("size", "10")
                            .param("page", String.valueOf(pagina)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            List<Number> ids = JsonPath.read(corpo, "$.itens[*].id");
            linhas += ids.size();
            ids.forEach(id -> vistos.add(id.longValue()));
        }

        assertThat(linhas).isEqualTo(25);
        assertThat(vistos).isEqualTo(esperados);
    }

    // ---- filtro: % e _ sao texto ---------------------------------------------------------

    @Test
    void porcentoNoFiltroNaoViraCuringa() throws Exception {
        String marca = "Filtro" + ThreadLocalRandom.current().nextInt(1_000_000);
        criarCliente(marca + " 100% Natural");
        criarCliente(marca + " 1000 Natural");

        mvc.perform(get("/api/clientes").param("razaoSocial", marca + " 100%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.itens[0].razaoSocial").value(marca + " 100% Natural"));
    }

    @Test
    void sublinhadoNoFiltroNaoViraCuringa() throws Exception {
        String marca = "Filtro" + ThreadLocalRandom.current().nextInt(1_000_000);
        criarCliente(marca + " A_B");
        criarCliente(marca + " AxB");

        mvc.perform(get("/api/clientes").param("razaoSocial", marca + " A_B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.itens[0].razaoSocial").value(marca + " A_B"));
    }

    // ---- tamanho de corpo ----------------------------------------------------------------

    @Test
    void corpoDeclaradoMuitoGrandeEhRecusadoComo413() throws Exception {
        mvc.perform(post("/api/clientes").contentType(MediaType.APPLICATION_JSON).content(new byte[70_000]))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.title").value("Corpo grande demais"));
    }

    // ---- documentos sujos ----------------------------------------------------------------

    @Test
    void cnpjComLetrasAoRedorDosDigitosEhRecusado() throws Exception {
        String cnpj = "abc" + GeradorDeDocumentos.cnpj() + "xyz";
        enviar("/api/clientes", "{\"cnpj\":\"" + cnpj + "\",\"razaoSocial\":\"X LTDA\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("cnpj"));
    }

    @Test
    void cpfComAlgarismoDeOutroAlfabetoEhRecusado() throws Exception {
        long clienteId = criarCliente("Dono LTDA");
        long empresaId = criarEmpresa(clienteId);
        String cpf = GeradorDeDocumentos.cpf() + "٣"; // algarismo arabe-indico no fim
        enviar("/api/empresas/" + empresaId + "/socios",
                "{\"nome\":\"Fulano\",\"cpf\":\"" + cpf + "\",\"participacao\":10}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("cpf"));
    }

    @Test
    void razaoSocialSoComEspacoUnicodeEhRecusada() throws Exception {
        enviar("/api/clientes", "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj() + "\",\"razaoSocial\":\" \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("razaoSocial"));
    }

    @Test
    void textoComEspacosNasPontasEhAparadoAntesDeValidar() throws Exception {
        // 200 letras + 1 espaco no fim: depois de aparado cabe no limite de 200 caracteres.
        String razao = "a".repeat(200) + " ";
        enviar("/api/clientes", "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj() + "\",\"razaoSocial\":\"" + razao + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.razaoSocial").value("a".repeat(200)));
    }

    // ---- carimbos de tempo ---------------------------------------------------------------

    @Test
    void carimbosDeTempoSaoIguaisNoPostENoGetSeguinte() throws Exception {
        String criado = enviar("/api/clientes",
                "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj() + "\",\"razaoSocial\":\"Carimbo LTDA\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = ((Number) JsonPath.read(criado, "$.id")).longValue();

        String lido = mvc.perform(get("/api/clientes/{id}", id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat((String) JsonPath.read(lido, "$.criadoEm")).isEqualTo(JsonPath.read(criado, "$.criadoEm"));
        assertThat((String) JsonPath.read(lido, "$.atualizadoEm")).isEqualTo(JsonPath.read(criado, "$.atualizadoEm"));
    }

    // ---- decisao: pai inativo so barra a CRIACAO -----------------------------------------

    @Test
    void clienteInativoAindaPermiteAtualizarEExcluirEndereco() throws Exception {
        long clienteId = criarCliente("Cliente Que Fecha LTDA");
        long enderecoId = ((Number) JsonPath.read(enviar("/api/clientes/" + clienteId + "/enderecos",
                "{\"logradouro\":\"Rua A\",\"cidade\":\"Sao Paulo\",\"uf\":\"SP\",\"cep\":\"01001000\",\"principal\":true}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();
        mvc.perform(post("/api/clientes/{id}/inativar", clienteId)).andExpect(status().isOk());

        mvc.perform(put("/api/clientes/{c}/enderecos/{e}", clienteId, enderecoId).contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding(StandardCharsets.UTF_8)
                        .content("{\"logradouro\":\"Rua B\",\"cidade\":\"Sao Paulo\",\"uf\":\"SP\",\"cep\":\"01001000\",\"principal\":true}"
                                .getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logradouro").value("Rua B"));
        mvc.perform(delete("/api/clientes/{c}/enderecos/{e}", clienteId, enderecoId)).andExpect(status().isNoContent());
    }

    @Test
    void clienteInativoAindaPermiteAtualizarInativarEExcluirEmpresa() throws Exception {
        long clienteId = criarCliente("Cliente Que Fecha LTDA");
        String cnpj = GeradorDeDocumentos.cnpj();
        long empresaId = ((Number) JsonPath.read(enviar("/api/clientes/" + clienteId + "/empresas",
                "{\"cnpj\":\"" + cnpj + "\",\"razaoSocial\":\"Empresa LTDA\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();
        mvc.perform(post("/api/clientes/{id}/inativar", clienteId)).andExpect(status().isOk());

        mvc.perform(put("/api/empresas/{id}", empresaId).contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"cnpj\":\"" + cnpj + "\",\"razaoSocial\":\"Empresa Nova LTDA\"}").getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razaoSocial").value("Empresa Nova LTDA"));
        mvc.perform(post("/api/empresas/{id}/inativar", empresaId)).andExpect(status().isOk());
        mvc.perform(post("/api/empresas/{id}/reativar", empresaId)).andExpect(status().isOk());
        mvc.perform(delete("/api/empresas/{id}", empresaId)).andExpect(status().isNoContent());
    }

    // ---- decisao: no socio, CPF duplicado (409) vem antes da soma acima de 100% (422) ------

    @Test
    void socioComCpfDuplicadoERespondeConflitoMesmoQuandoASomaEstoura() throws Exception {
        long empresaId = criarEmpresa(criarCliente("Empresa Dos Socios LTDA"));
        String cpf = GeradorDeDocumentos.cpf();
        enviar("/api/empresas/" + empresaId + "/socios", "{\"nome\":\"Primeiro\",\"cpf\":\"" + cpf + "\",\"participacao\":60}")
                .andExpect(status().isCreated());

        // mesmo CPF e 50%: duplicado (409) E estoura 100% (422). A ordem documentada: 409 primeiro.
        enviar("/api/empresas/" + empresaId + "/socios", "{\"nome\":\"Repetido\",\"cpf\":\"" + cpf + "\",\"participacao\":50}")
                .andExpect(status().isConflict());
    }

    // ---- apoio ---------------------------------------------------------------------------

    private ResultActions enviar(String caminho, String corpoJson) throws Exception {
        return mvc.perform(post(caminho)
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content(corpoJson.getBytes(StandardCharsets.UTF_8)));
    }

    private long criarCliente(String razaoSocial) throws Exception {
        String resposta = enviar("/api/clientes",
                "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj() + "\",\"razaoSocial\":\"" + razaoSocial + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(resposta, "$.id")).longValue();
    }

    private long criarEmpresa(long clienteId) throws Exception {
        String resposta = enviar("/api/clientes/" + clienteId + "/empresas",
                "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj() + "\",\"razaoSocial\":\"Empresa LTDA\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(resposta, "$.id")).longValue();
    }
}
