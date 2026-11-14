package br.com.kawashi.banco.testes;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.jayway.jsonpath.JsonPath;

/**
 * Apoio dos testes de integracao: chamadas a API por MockMvc, sempre em UTF-8, e atalhos para criar
 * os cadastros de que quase todo teste precisa (empresa, filial, pessoa) com documentos validos e
 * aleatorios.
 */
public class Api {

    private final MockMvc mvc;

    public Api(MockMvc mvc) {
        this.mvc = mvc;
    }

    public ResultActions post(String caminho, String json) throws Exception {
        return mvc.perform(comCorpo(MockMvcRequestBuilders.post(caminho), json));
    }

    public ResultActions put(String caminho, String json) throws Exception {
        return mvc.perform(comCorpo(MockMvcRequestBuilders.put(caminho), json));
    }

    public ResultActions post(String caminho) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(caminho));
    }

    public ResultActions get(String caminho) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(caminho));
    }

    public ResultActions delete(String caminho) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.delete(caminho));
    }

    /** Cria e devolve o id; falha o teste se a resposta nao for 201. */
    public long criar(String caminho, String json) throws Exception {
        return id(post(caminho, json).andExpect(status().isCreated()));
    }

    public long empresa() throws Exception {
        return empresa(GeradorDeDocumentos.cnpj());
    }

    public long empresa(String cnpj) throws Exception {
        return criar("/api/empresas", "{\"cnpj\":\"" + cnpj + "\",\"razaoSocial\":\"Empresa de Teste LTDA\"}");
    }

    /** Filial de uma empresa nova; devolve o id da filial. */
    public long filial() throws Exception {
        String raiz = GeradorDeDocumentos.raizDeCnpj();
        long empresaId = empresa(GeradorDeDocumentos.cnpj(raiz, "0001"));
        return filial(empresaId, raiz);
    }

    public long filial(long empresaId, String raiz) throws Exception {
        return criar("/api/empresas/" + empresaId + "/filiais",
                "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj(raiz, "0002") + "\",\"nome\":\"Filial Centro\"}");
    }

    /** Pessoa fisica do tipo CLIENTE numa empresa nova; devolve o id da pessoa. */
    public long pessoa() throws Exception {
        return pessoa(empresa(), "CLIENTE");
    }

    public long pessoa(long empresaId, String tipo) throws Exception {
        return criar("/api/empresas/" + empresaId + "/pessoas", pessoaJson(tipo, GeradorDeDocumentos.cpf(), null, null));
    }

    public long socio(long empresaId, String participacao) throws Exception {
        return criar("/api/empresas/" + empresaId + "/pessoas", pessoaJson("SOCIO", GeradorDeDocumentos.cpf(), participacao, null));
    }

    /** JSON de pessoa fisica; participacao e cargo entram so quando informados. */
    public static String pessoaJson(String tipo, String cpf, String participacao, String cargo) {
        return "{\"tipo\":\"" + tipo + "\",\"natureza\":\"F\",\"documento\":\"" + cpf + "\",\"nome\":\"Fulano de Tal\""
                + (participacao == null ? "" : ",\"participacao\":" + participacao)
                + (cargo == null ? "" : ",\"cargo\":\"" + cargo + "\"") + "}";
    }

    public static long id(ResultActions resposta) throws Exception {
        return ((Number) JsonPath.read(corpo(resposta), "$.id")).longValue();
    }

    public static String corpo(ResultActions resposta) throws Exception {
        return resposta.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static MockHttpServletRequestBuilder comCorpo(MockHttpServletRequestBuilder pedido, String json) {
        return pedido.contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content(json.getBytes(StandardCharsets.UTF_8));
    }
}
