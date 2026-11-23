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
    private String token;
    private final java.util.function.Function<String, Long> criadorDeEmpresa;
    private long empresaAtual;
    /** Raiz do CNPJ da empresa atual (as filiais dela usam a mesma raiz) e a proxima ordem livre. */
    private String raizAtual;
    private int proximaOrdem = 2;

    /** Sem token: so serve para o login e para conferir que o resto da API recusa (401). */
    public Api(MockMvc mvc) {
        this(mvc, null);
    }

    /** Com token: toda chamada leva o cabecalho Authorization: Bearer. Nao cria empresas. */
    public Api(MockMvc mvc, String token) {
        this(mvc, token, null, 0, null);
    }

    /**
     * Cliente que tambem cria empresas (feito por UsuarioDeTeste.api): cada empresa nova e do mesmo
     * usuario, como ADMINISTRADOR, e a sessao passa para ela pela rota real /api/auth/empresa.
     */
    public Api(MockMvc mvc, String token, java.util.function.Function<String, Long> criadorDeEmpresa, long empresaAtual,
            String cnpjDaEmpresaAtual) {
        this.mvc = mvc;
        this.token = token;
        this.criadorDeEmpresa = criadorDeEmpresa;
        this.empresaAtual = empresaAtual;
        this.raizAtual = cnpjDaEmpresaAtual == null ? null : cnpjDaEmpresaAtual.substring(0, 8);
    }

    /** O token atual (muda a cada troca de empresa). */
    public String token() {
        return token;
    }

    /** A empresa em que a sessao esta. */
    public long empresaAtual() {
        return empresaAtual;
    }

    /** Troca a sessao para outra empresa do usuario (token novo; o anterior deixa de valer). */
    public void entrarNa(long empresaId) throws Exception {
        String resposta = corpo(post("/api/auth/empresa", "{\"empresaId\":" + empresaId + "}").andExpect(status().isOk()));
        token = JsonPath.read(resposta, "$.token");
        empresaAtual = empresaId;
    }

    public ResultActions post(String caminho, String json) throws Exception {
        return mvc.perform(autenticado(comCorpo(MockMvcRequestBuilders.post(caminho), json)));
    }

    public ResultActions put(String caminho, String json) throws Exception {
        return mvc.perform(autenticado(comCorpo(MockMvcRequestBuilders.put(caminho), json)));
    }

    public ResultActions post(String caminho) throws Exception {
        return mvc.perform(autenticado(MockMvcRequestBuilders.post(caminho)));
    }

    public ResultActions get(String caminho) throws Exception {
        return mvc.perform(autenticado(MockMvcRequestBuilders.get(caminho)));
    }

    public ResultActions delete(String caminho) throws Exception {
        return mvc.perform(autenticado(MockMvcRequestBuilders.delete(caminho)));
    }

    /**
     * Segredo do BFF dos testes (src/test/resources/application.properties). Todo pedido dos testes
     * finge vir do BFF, como em producao; quem quer conferir a recusa sem ele monta o pedido na mao.
     */
    public static final String SEGREDO_DO_BFF = "segredo-so-dos-testes-0123456789abcdef";

    /** IP do navegador que os testes fingem ter (o BFF manda o IP real em X-Kawashi-Ip-Cliente). */
    public static final String IP_DE_TESTE = "198.51.100.7";

    /** Marca o pedido como vindo do BFF (segredo e IP do navegador). */
    public static MockHttpServletRequestBuilder doBff(MockHttpServletRequestBuilder pedido) {
        return doBff(pedido, IP_DE_TESTE);
    }

    /** Idem, com outro IP de navegador (testes de bloqueio por IP). */
    public static MockHttpServletRequestBuilder doBff(MockHttpServletRequestBuilder pedido, String ipDoNavegador) {
        return pedido.header("X-Kawashi-Bff", SEGREDO_DO_BFF).header("X-Kawashi-Ip-Cliente", ipDoNavegador);
    }

    /** O pedido montado por quem chama, com o segredo do BFF e o token deste cliente (para casos com IP, HTTPS etc.). */
    public MockHttpServletRequestBuilder autenticado(MockHttpServletRequestBuilder pedido) {
        doBff(pedido);
        return token == null ? pedido : pedido.header("Authorization", "Bearer " + token);
    }

    /** Cria e devolve o id; falha o teste se a resposta nao for 201. */
    public long criar(String caminho, String json) throws Exception {
        return id(post(caminho, json).andExpect(status().isCreated()));
    }

    public long empresa() throws Exception {
        return empresa(GeradorDeDocumentos.cnpj());
    }

    /** Empresa nova do mesmo usuario; a sessao passa para ela. */
    public long empresa(String cnpj) throws Exception {
        if (criadorDeEmpresa == null) {
            throw new IllegalStateException("Este cliente nao cria empresas: use UsuarioDeTeste.api(mvc).");
        }
        long id = criadorDeEmpresa.apply(cnpj);
        entrarNa(id);
        raizAtual = cnpj.substring(0, 8);
        proximaOrdem = 2;
        return id;
    }

    /** Filial nova NA EMPRESA ATUAL da sessao; devolve o id da filial. */
    public long filial() throws Exception {
        return filial(empresaAtual, raizAtual);
    }

    /** Filial da empresa (CNPJ com a raiz dela e a proxima ordem livre: 0002, 0003...). */
    public long filial(long empresaId, String raiz) throws Exception {
        String ordem = String.format("%04d", raiz.equals(raizAtual) ? proximaOrdem++ : 2);
        return criar("/api/empresas/" + empresaId + "/filiais",
                "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj(raiz, ordem) + "\",\"nome\":\"Filial Centro\"}");
    }

    /** Pessoa fisica do tipo CLIENTE NA EMPRESA ATUAL da sessao; devolve o id da pessoa. */
    public long pessoa() throws Exception {
        return pessoa(empresaAtual, "CLIENTE");
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
