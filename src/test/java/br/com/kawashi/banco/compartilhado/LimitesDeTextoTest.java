package br.com.kawashi.banco.compartilhado;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;

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
 * No Oracle, VARCHAR2(200) sem a palavra CHAR mede o limite em BYTES, e o banco e AL32UTF8: uma
 * letra acentuada ocupa 2 bytes. Bean Validation (@Size) conta CARACTERES. Sem a migracao V4, um
 * texto de 200 letras "a" acentuadas passa na API e estoura a coluna (ORA-12899), virando um 409
 * generico no lugar de um cadastro valido.
 *
 * <p>Este teste manda CADA campo de texto livre de CADA fatia no tamanho maximo, todo em "ã" (2
 * bytes em UTF-8), e exige 201; e manda um caractere a mais e exige 400 da validacao da API. Ele
 * guarda tanto a V4 quanto a coerencia entre o @Size de cada Request e o tamanho da coluna.
 */
@IntegracaoApi
class LimitesDeTextoTest {

    private static String acentuado(int caracteres) {
        return "ã".repeat(caracteres);
    }

    @Autowired
    private MockMvc mvc;

    private long clienteId;
    private long empresaId;
    private String raizDaEmpresa;

    @BeforeEach
    void criarPais() throws Exception {
        clienteId = criar("/api/clientes", Map.of("cnpj", GeradorDeDocumentos.cnpj(), "razaoSocial", "Pai dos Limites LTDA"));
        raizDaEmpresa = GeradorDeDocumentos.raizDeCnpj();
        empresaId = criar("/api/clientes/" + clienteId + "/empresas",
                Map.of("cnpj", GeradorDeDocumentos.cnpj(raizDaEmpresa, "0001"), "razaoSocial", "Empresa Pai LTDA"));
    }

    // ---- cada fatia: o maximo (em letras de 2 bytes) e aceito; um a mais e recusado ------------

    @Test
    void clienteAceitaOMaximoDeCaracteresAcentuados() throws Exception {
        enviar("/api/clientes", cliente(200, 200)).andExpect(status().isCreated());
    }

    @Test
    void clienteRecusaUmCaractereAMais() throws Exception {
        enviar("/api/clientes", cliente(201, 10)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("razaoSocial"));
        enviar("/api/clientes", cliente(10, 201)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("nomeFantasia"));
    }

    @Test
    void enderecoAceitaOMaximoDeCaracteresAcentuados() throws Exception {
        enviar(enderecos(), endereco(200, 20, 100, 100, 100)).andExpect(status().isCreated());
    }

    @Test
    void enderecoRecusaUmCaractereAMaisEmCadaCampo() throws Exception {
        enviar(enderecos(), endereco(201, 20, 100, 100, 100)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("logradouro"));
        enviar(enderecos(), endereco(200, 21, 100, 100, 100)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("numero"));
        enviar(enderecos(), endereco(200, 20, 101, 100, 100)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("complemento"));
        enviar(enderecos(), endereco(200, 20, 100, 101, 100)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("bairro"));
        enviar(enderecos(), endereco(200, 20, 100, 100, 101)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("cidade"));
    }

    @Test
    void contatoAceitaONomeNoMaximoDeCaracteresAcentuados() throws Exception {
        enviar("/api/clientes/" + clienteId + "/contatos", Map.of("nome", acentuado(150), "email", "a@exemplo.com"))
                .andExpect(status().isCreated());
    }

    @Test
    void contatoRecusaONomeComUmCaractereAMais() throws Exception {
        enviar("/api/clientes/" + clienteId + "/contatos", Map.of("nome", acentuado(151), "email", "a@exemplo.com"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("nome"));
    }

    @Test
    void empresaAceitaOMaximoDeCaracteresAcentuados() throws Exception {
        enviar("/api/clientes/" + clienteId + "/empresas", Map.of(
                "cnpj", GeradorDeDocumentos.cnpj(), "razaoSocial", acentuado(200), "nomeFantasia", acentuado(200)))
                .andExpect(status().isCreated());
    }

    @Test
    void empresaRecusaUmCaractereAMais() throws Exception {
        enviar("/api/clientes/" + clienteId + "/empresas", Map.of(
                "cnpj", GeradorDeDocumentos.cnpj(), "razaoSocial", acentuado(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("razaoSocial"));
        enviar("/api/clientes/" + clienteId + "/empresas", Map.of(
                "cnpj", GeradorDeDocumentos.cnpj(), "razaoSocial", "Empresa X", "nomeFantasia", acentuado(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("nomeFantasia"));
    }

    @Test
    void filialAceitaONomeNoMaximoDeCaracteresAcentuados() throws Exception {
        enviar("/api/empresas/" + empresaId + "/filiais", Map.of(
                "cnpj", GeradorDeDocumentos.cnpj(raizDaEmpresa, "0002"), "nome", acentuado(150)))
                .andExpect(status().isCreated());
    }

    @Test
    void filialRecusaONomeComUmCaractereAMais() throws Exception {
        enviar("/api/empresas/" + empresaId + "/filiais", Map.of(
                "cnpj", GeradorDeDocumentos.cnpj(raizDaEmpresa, "0002"), "nome", acentuado(151)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("nome"));
    }

    @Test
    void socioAceitaONomeNoMaximoDeCaracteresAcentuados() throws Exception {
        enviar("/api/empresas/" + empresaId + "/socios", Map.of(
                "nome", acentuado(150), "cpf", GeradorDeDocumentos.cpf(), "participacao", 10))
                .andExpect(status().isCreated());
    }

    @Test
    void socioRecusaONomeComUmCaractereAMais() throws Exception {
        enviar("/api/empresas/" + empresaId + "/socios", Map.of(
                "nome", acentuado(151), "cpf", GeradorDeDocumentos.cpf(), "participacao", 10))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("nome"));
    }

    // ---- apoio ----------------------------------------------------------------------------

    private String enderecos() {
        return "/api/clientes/" + clienteId + "/enderecos";
    }

    private static Map<String, Object> cliente(int razaoSocial, int nomeFantasia) {
        return Map.of("cnpj", GeradorDeDocumentos.cnpj(), "razaoSocial", acentuado(razaoSocial),
                "nomeFantasia", acentuado(nomeFantasia));
    }

    private static Map<String, Object> endereco(int logradouro, int numero, int complemento, int bairro, int cidade) {
        return Map.of("logradouro", acentuado(logradouro), "numero", acentuado(numero),
                "complemento", acentuado(complemento), "bairro", acentuado(bairro), "cidade", acentuado(cidade),
                "uf", "SP", "cep", "01001000");
    }

    private ResultActions enviar(String caminho, Map<String, Object> corpo) throws Exception {
        return mvc.perform(post(caminho)
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content(json(corpo).getBytes(StandardCharsets.UTF_8)));
    }

    private long criar(String caminho, Map<String, Object> corpo) throws Exception {
        String resposta = enviar(caminho, corpo).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(resposta, "$.id")).longValue();
    }

    /** JSON minimo a partir de um mapa de textos e numeros (sem dependencia da biblioteca de JSON). */
    private static String json(Map<String, Object> corpo) {
        StringBuilder sb = new StringBuilder("{");
        corpo.forEach((chave, valor) -> {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append('"').append(chave).append("\":");
            if (valor instanceof Number) {
                sb.append(valor);
            } else {
                sb.append('"').append(valor.toString().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        });
        return sb.append('}').toString();
    }
}
