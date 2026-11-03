package br.com.kawashi.banco.compartilhado;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import br.com.kawashi.banco.testes.GeradorDeDocumentos;
import br.com.kawashi.banco.testes.IntegracaoApi;

/**
 * Entradas que passavam pela validacao e so falhavam la no banco (ou, pior, viravam 500): espaco
 * Unicode no lugar de documento ou UF, numero JSON com zeros a direita e e-mail que cresce ao virar
 * minuscula. Achados pela revisao independente da API; cada teste fixa uma correcao.
 */
@IntegracaoApi
class EntradasDeBordaTest {

    /** Espaco "em quadratim" (U+2003): e espaco para o Unicode, mas o trim() do Java nao o remove. */
    private static final String ESPACO_EXOTICO = " ";

    @Autowired
    private MockMvc mvc;

    private long clienteId;
    private long empresaId;

    @BeforeEach
    void criarPais() throws Exception {
        clienteId = criar("/api/clientes", "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj() + "\",\"razaoSocial\":\"Pai LTDA\"}");
        empresaId = criar("/api/clientes/" + clienteId + "/empresas",
                "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj() + "\",\"razaoSocial\":\"Empresa Pai LTDA\"}");
    }

    // ---- espaco Unicode no lugar de campo obrigatorio ------------------------------------

    @Test
    void espacoUnicodeNoCnpjDeClienteEErro400() throws Exception {
        enviar("/api/clientes", "{\"cnpj\":\"" + ESPACO_EXOTICO + "\",\"razaoSocial\":\"X LTDA\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("cnpj"));
    }

    @Test
    void espacoUnicodeNoCpfDeSocioEErro400() throws Exception {
        enviar("/api/empresas/" + empresaId + "/socios",
                "{\"nome\":\"Fulano\",\"cpf\":\"" + ESPACO_EXOTICO + "\",\"participacao\":10}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("cpf"));
    }

    @Test
    void espacoUnicodeNaUfDeEnderecoEErro400() throws Exception {
        enviar("/api/clientes/" + clienteId + "/enderecos",
                "{\"logradouro\":\"Rua A\",\"cidade\":\"Sao Paulo\",\"uf\":\"" + ESPACO_EXOTICO + "\",\"cep\":\"01001000\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("uf"));
    }

    // ---- participacao com zeros a direita ------------------------------------------------

    @Test
    void participacaoComZerosADireitaEAceitaENormalizada() throws Exception {
        enviar("/api/empresas/" + empresaId + "/socios",
                "{\"nome\":\"Maioria\",\"cpf\":\"" + GeradorDeDocumentos.cpf() + "\",\"participacao\":33.330}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participacao").value(33.33));

        enviar("/api/empresas/" + empresaId + "/socios",
                "{\"nome\":\"Resto\",\"cpf\":\"" + GeradorDeDocumentos.cpf() + "\",\"participacao\":66.670}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participacao").value(66.67));
    }

    @Test
    void participacaoDe100ComTresCasasEAceita() throws Exception {
        enviar("/api/empresas/" + empresaId + "/socios",
                "{\"nome\":\"Dono\",\"cpf\":\"" + GeradorDeDocumentos.cpf() + "\",\"participacao\":100.000}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participacao").value(100.0));
    }

    @Test
    void participacaoComCasasDemaisDeVerdadeContinuaRecusada() throws Exception {
        enviar("/api/empresas/" + empresaId + "/socios",
                "{\"nome\":\"Fracao\",\"cpf\":\"" + GeradorDeDocumentos.cpf() + "\",\"participacao\":0.001}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("participacao"));

        enviar("/api/empresas/" + empresaId + "/socios",
                "{\"nome\":\"Fracao\",\"cpf\":\"" + GeradorDeDocumentos.cpf() + "\",\"participacao\":33.333}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("participacao"));
    }

    // ---- e-mail que cresce ao virar minuscula ---------------------------------------------

    @Test
    void emailQueCrescendoAoMinusculizarPassaDe150CaracteresEErro400() throws Exception {
        // "İ" (U+0130) vira DOIS caracteres em minuscula: 64 + "@" + 85 = 150 antes, 214 depois.
        String email = "İ".repeat(64) + "@" + "a".repeat(40) + "." + "b".repeat(44);

        enviar("/api/clientes/" + clienteId + "/contatos", "{\"nome\":\"Fulano\",\"email\":\"" + email + "\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void emailMaiusculoContinuaSendoGravadoEmMinuscula() throws Exception {
        enviar("/api/clientes/" + clienteId + "/contatos", "{\"nome\":\"Fulano\",\"email\":\"Fulano@Exemplo.COM\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("fulano@exemplo.com"));
    }

    // ---- apoio ----------------------------------------------------------------------------

    private ResultActions enviar(String caminho, String corpoJson) throws Exception {
        return mvc.perform(post(caminho)
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content(corpoJson.getBytes(StandardCharsets.UTF_8)));
    }

    private long criar(String caminho, String corpoJson) throws Exception {
        String resposta = enviar(caminho, corpoJson).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(resposta, "$.id")).longValue();
    }
}
