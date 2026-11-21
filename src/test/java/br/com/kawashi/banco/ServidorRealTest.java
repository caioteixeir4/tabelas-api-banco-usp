package br.com.kawashi.banco;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

import com.jayway.jsonpath.JsonPath;

import br.com.kawashi.banco.log.SchemaDeLog;
import br.com.kawashi.banco.testes.Api;
import br.com.kawashi.banco.testes.GeradorDeDocumentos;

/**
 * Testes que so fazem sentido contra o servidor de verdade (porta real, uma transacao por pedido):
 * <ul>
 *   <li><b>concorrencia</b>: as regras "entre linhas" (soma das participacoes ate 100%, um unico
 *       principal por dono) nao existem no banco; quem as garante e a trava na linha do pai. Os
 *       demais testes rodam numa unica transacao e nunca disputam essa trava; aqui varios pedidos
 *       chegam ao mesmo tempo. O encadeamento do log tambem e conferido sob essa concorrencia;</li>
 *   <li><b>corpo fragmentado</b> (sem Content-Length), que o MockMvc nao reproduz.</li>
 * </ul>
 * Diferente dos outros testes, aqui os dados sao gravados de verdade; cada teste apaga os cadastros
 * que criou. As linhas de LOG ficam: a API nao tem como apaga-las, e esse e o ponto.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {"app.exigir-https=false", "server.ssl.enabled=false"})
class ServidorRealTest {

    private static final int PEDIDOS_SIMULTANEOS = 12;

    @Value("${local.server.port}")
    private int porta;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SchemaDeLog schemaDeLog;

    @Autowired
    private br.com.kawashi.banco.testes.UsuarioDeTeste usuarios;

    private final HttpClient http = HttpClient.newHttpClient();
    /** Token gravado de verdade: os pedidos chegam por outras threads, fora de qualquer transacao do teste. */
    private String token;
    private long empresaId;
    private long pessoaId;

    @BeforeEach
    void criarEmpresaEPessoa() throws Exception {
        token = usuarios.tokenPersistente();
        empresaId = id(enviar("POST", "/api/empresas",
                "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj() + "\",\"razaoSocial\":\"Concorrencia LTDA\"}", 201));
        pessoaId = id(enviar("POST", "/api/empresas/" + empresaId + "/pessoas",
                Api.pessoaJson("CLIENTE", GeradorDeDocumentos.cpf(), null, null), 201));
    }

    /**
     * Limpeza por SQL direto, e nao pela API: se um teste falhar deixando os dados num estado que a
     * propria API recusa desfazer (dois principais, por exemplo), eles ainda saem do banco.
     */
    @AfterEach
    void apagarCadastros() {
        jdbc.update("DELETE FROM CONTATO WHERE PESSOA_ID IN (SELECT ID FROM PESSOA WHERE EMPRESA_ID = ?)", empresaId);
        jdbc.update("DELETE FROM ENDERECO WHERE PESSOA_ID IN (SELECT ID FROM PESSOA WHERE EMPRESA_ID = ?)", empresaId);
        jdbc.update("DELETE FROM PESSOA WHERE EMPRESA_ID = ?", empresaId);
        jdbc.update("DELETE FROM FILIAL WHERE EMPRESA_ID = ?", empresaId);
        jdbc.update("DELETE FROM EMPRESA WHERE ID = ?", empresaId);
    }

    @Test
    void sociosCriadosAoMesmoTempoNuncaPassamDe100PorCento() throws Exception {
        // 12 pedidos simultaneos de 30% cada: cabem exatamente 3 (90%). Sem a trava na empresa,
        // varios leriam "soma = 0" juntos e todos seriam gravados.
        List<Integer> status = aoMesmoTempo(i -> enviar("POST", "/api/empresas/" + empresaId + "/pessoas",
                Api.pessoaJson("SOCIO", GeradorDeDocumentos.cpf(), "30", null)).statusCode());

        assertThat(status).containsOnly(201, 422);
        assertThat(status).filteredOn(s -> s == 201).hasSize(3);

        String lista = enviar("GET", "/api/empresas/" + empresaId + "/pessoas?tipo=SOCIO&size=100", null, 200).body();
        BigDecimal soma = JsonPath.<List<Number>>read(lista, "$.itens[*].participacao").stream()
                .map(n -> new BigDecimal(n.toString()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(soma).isEqualByComparingTo("90");
    }

    @Test
    void enderecosPrincipaisCriadosAoMesmoTempoDeixamUmSoPrincipal() throws Exception {
        String caminho = "/api/pessoas/" + pessoaId + "/enderecos";
        List<Integer> status = aoMesmoTempo(i -> enviar("POST", caminho,
                "{\"logradouro\":\"Rua " + i + "\",\"cidade\":\"Sao Paulo\",\"uf\":\"SP\",\"cep\":\"01001000\",\"principal\":true}")
                .statusCode());

        assertThat(status).containsOnly(201);
        assertUmSoPrincipal(caminho);
    }

    @Test
    void contatosPrincipaisCriadosAoMesmoTempoDeixamUmSoPrincipal() throws Exception {
        String caminho = "/api/pessoas/" + pessoaId + "/contatos";
        List<Integer> status = aoMesmoTempo(i -> enviar("POST", caminho,
                "{\"tipo\":\"EMAIL\",\"valor\":\"contato" + i + "@exemplo.com.br\",\"principal\":true}").statusCode());

        assertThat(status).containsOnly(201);
        assertUmSoPrincipal(caminho);
    }

    @Test
    void logContinuaEncadeadoComGravacoesSimultaneas() throws Exception {
        long antes = jdbc.queryForObject("SELECT NVL(MAX(ORDEM), 0) FROM " + schemaDeLog.tabela("LOG_ALTERACAO"), Long.class);
        List<Integer> status = aoMesmoTempo(i -> enviar("POST", "/api/empresas/" + empresaId + "/pessoas",
                Api.pessoaJson("CLIENTE", GeradorDeDocumentos.cpf(), null, null)).statusCode());
        assertThat(status).containsOnly(201);

        // 12 gravacoes concorrentes: 12 numeros seguidos, sem buraco nem repeticao, e corrente inteira.
        long depois = jdbc.queryForObject("SELECT NVL(MAX(ORDEM), 0) FROM " + schemaDeLog.tabela("LOG_ALTERACAO"), Long.class);
        assertThat(depois - antes).isEqualTo(PEDIDOS_SIMULTANEOS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schemaDeLog.tabela("V_LOG_ALTERACAO_QUEBRAS"), Long.class)).isZero();
    }

    @Test
    void corpoFragmentadoAcimaDoLimiteEhRecusadoCom413() throws Exception {
        // ofInputStream nao declara tamanho: o corpo vai em transferencia fragmentada (chunked) e o
        // limite so pode ser aplicado durante a leitura.
        String corpo = "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj() + "\",\"razaoSocial\":\"" + "a".repeat(70_000) + "\"}";
        HttpResponse<String> resposta = http.send(fragmentado("/api/empresas", corpo), HttpResponse.BodyHandlers.ofString());

        assertThat(resposta.statusCode()).isEqualTo(413);
        assertThat(resposta.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        assertThat((String) JsonPath.read(resposta.body(), "$.title")).isEqualTo("Corpo grande demais");
    }

    @Test
    void corpoFragmentadoDentroDoLimiteEhAceito() throws Exception {
        String corpo = "{\"tipo\":\"EMAIL\",\"valor\":\"fragmentado@exemplo.com.br\"}";
        assertThat(http.send(fragmentado("/api/pessoas/" + pessoaId + "/contatos", corpo), HttpResponse.BodyHandlers.ofString())
                .statusCode()).isEqualTo(201);
    }

    @Test
    void corpoDeclaradoGrandeDemaisEhRecusadoCom413SemSerLido() throws Exception {
        HttpResponse<String> resposta = enviar("POST", "/api/empresas", "{\"razaoSocial\":\"" + "a".repeat(70_000) + "\"}");
        assertThat(resposta.statusCode()).isEqualTo(413);
    }

    @Test
    void carimbosDeTempoSaoIguaisNoPostENoGetSeguinte() throws Exception {
        String criado = enviar("POST", "/api/empresas",
                "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj() + "\",\"razaoSocial\":\"Carimbo LTDA\"}", 201).body();
        long id = ((Number) JsonPath.read(criado, "$.id")).longValue();
        try {
            String lido = enviar("GET", "/api/empresas/" + id, null, 200).body();
            assertThat((String) JsonPath.read(lido, "$.criadoEm")).isEqualTo(JsonPath.read(criado, "$.criadoEm"));
            assertThat((String) JsonPath.read(lido, "$.atualizadoEm")).isEqualTo(JsonPath.read(criado, "$.atualizadoEm"));
        } finally {
            jdbc.update("DELETE FROM EMPRESA WHERE ID = ?", id);
        }
    }

    // ---- apoio ---------------------------------------------------------------------------

    private interface Pedido {
        int enviar(int indice) throws Exception;
    }

    /** Dispara os pedidos juntos (todos esperam a mesma largada) e devolve o status de cada um. */
    private List<Integer> aoMesmoTempo(Pedido pedido) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(PEDIDOS_SIMULTANEOS);
        try {
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<Integer>> futuros = new ArrayList<>();
            for (int i = 0; i < PEDIDOS_SIMULTANEOS; i++) {
                int indice = i;
                Callable<Integer> tarefa = () -> {
                    largada.await();
                    return pedido.enviar(indice);
                };
                futuros.add(executor.submit(tarefa));
            }
            largada.countDown();
            List<Integer> status = new ArrayList<>();
            for (Future<Integer> futuro : futuros) {
                status.add(futuro.get(60, TimeUnit.SECONDS));
            }
            return status;
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertUmSoPrincipal(String caminho) throws Exception {
        String lista = enviar("GET", caminho + "?size=100", null, 200).body();
        assertThat(JsonPath.<Integer>read(lista, "$.total")).isEqualTo(PEDIDOS_SIMULTANEOS);
        assertThat(JsonPath.<List<Number>>read(lista, "$.itens[?(@.principal == true)].id")).hasSize(1);
    }

    private HttpRequest fragmentado(String caminho, String corpo) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("X-Kawashi-Bff", Api.SEGREDO_DO_BFF)
                .header("X-Kawashi-Ip-Cliente", Api.IP_DE_TESTE)
                .POST(HttpRequest.BodyPublishers.ofInputStream(
                        () -> new ByteArrayInputStream(corpo.getBytes(StandardCharsets.UTF_8))))
                .build();
    }

    private HttpResponse<String> enviar(String metodo, String caminho, String corpoJson) throws Exception {
        HttpRequest.BodyPublisher corpo = corpoJson == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(corpoJson, StandardCharsets.UTF_8);
        HttpRequest pedido = HttpRequest.newBuilder(URI.create("http://localhost:" + porta + caminho))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("X-Kawashi-Bff", Api.SEGREDO_DO_BFF)
                .header("X-Kawashi-Ip-Cliente", Api.IP_DE_TESTE)
                .method(metodo, corpo)
                .build();
        return http.send(pedido, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> enviar(String metodo, String caminho, String corpoJson, int statusEsperado) throws Exception {
        HttpResponse<String> resposta = enviar(metodo, caminho, corpoJson);
        assertThat(resposta.statusCode()).as("%s %s -> %s", metodo, caminho, resposta.body()).isEqualTo(statusEsperado);
        return resposta;
    }

    private static long id(HttpResponse<String> resposta) {
        return ((Number) JsonPath.read(resposta.body(), "$.id")).longValue();
    }
}
