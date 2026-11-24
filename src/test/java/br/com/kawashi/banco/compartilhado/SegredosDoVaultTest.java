package br.com.kawashi.banco.compartilhado;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A leitura dos segredos da API no Vault, contra um Vault FALSO (servidor HTTP do proprio JDK, so em
 * 127.0.0.1, sem banco): os quatro caminhos certos, valores na frente do .env, token revogado, e
 * falha fechada sem mostrar segredo.
 */
class SegredosDoVaultTest {

    private static final String ROLE = "11111111-2222-3333-4444-555555555555";
    private static final String SECRET = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String TOKEN = "hvs.token-de-teste";
    private static final Map<String, String> NO_VAULT = Map.of(
            "/v1/kawashi-secrets/data/cifra/TESTE_DEV", "{\"data\":{\"data\":{\"chave_dados\":\"cd-do-vault\",\"chave_indice\":\"ci-do-vault\"}}}",
            "/v1/kawashi-secrets/data/bff/TESTE_DEV", "{\"data\":{\"data\":{\"segredo\":\"bff-do-vault\"}}}",
            "/v1/kawashi-secrets/data/api/KW_EXEC", "{\"data\":{\"data\":{\"db_password\":\"senha-exec-do-vault\"}}}",
            "/v1/kawashi-secrets/data/api/KW_MIG", "{\"data\":{\"data\":{\"db_password\":\"senha-mig-do-vault\"}}}");

    private HttpServer vault;
    private final List<String> pedidos = new CopyOnWriteArrayList<>();

    @BeforeEach
    void subirVaultFalso() throws IOException {
        vault = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        vault.createContext("/", this::responder);
        vault.start();
    }

    @AfterEach
    void derrubar() {
        vault.stop(0);
    }

    private void responder(HttpExchange troca) throws IOException {
        String caminho = troca.getRequestURI().getPath();
        String corpo = new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String token = troca.getRequestHeaders().getFirst("X-Vault-Token");
        pedidos.add(troca.getRequestMethod() + " " + caminho);
        int status;
        String resposta;
        if (caminho.equals("/v1/auth/approle/login")) {
            boolean certo = corpo.contains(ROLE) && corpo.contains(SECRET);
            status = certo ? 200 : 400;
            resposta = certo ? "{\"auth\":{\"client_token\":\"" + TOKEN + "\"}}" : "{}";
        } else if (caminho.equals("/v1/auth/token/revoke-self")) {
            status = 204;
            resposta = "";
        } else if (TOKEN.equals(token) && NO_VAULT.containsKey(caminho)) {
            status = 200;
            resposta = NO_VAULT.get(caminho);
        } else {
            status = 403;
            resposta = "{\"errors\":[\"permission denied\"]}";
        }
        byte[] bytes = resposta.getBytes(StandardCharsets.UTF_8);
        troca.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (status != 204) {
            troca.getResponseBody().write(bytes);
        }
        troca.close();
    }

    private StandardEnvironment ambiente(Map<String, Object> extra) {
        Map<String, Object> valores = new HashMap<>(Map.of("VAULT_ADDR", "http://127.0.0.1:" + vault.getAddress().getPort(),
                "VAULT_ROLE_ID", ROLE, "VAULT_SECRET_ID", SECRET, "DB_SCHEMA", "KAWASHI_ENV_TESTE_DEV",
                "DB_USER", "KW_EXEC[KAWASHI_ENV_TESTE_DEV_APP]", "DB_MIGRACAO_USER", "KW_MIG", "DB_PASSWORD", "senha-velha-do-env"));
        valores.putAll(extra);
        StandardEnvironment ambiente = new StandardEnvironment();
        ambiente.getPropertySources().addLast(new MapPropertySource("dotenv", valores));
        return ambiente;
    }

    @Test
    void leOsQuatroCaminhosDoAmbienteDoSchemaEPoeNaFrenteDoEnv() {
        StandardEnvironment ambiente = ambiente(Map.of());
        new SegredosDoVault().postProcessEnvironment(ambiente, null);
        assertThat(ambiente.getProperty("CIFRA_CHAVE_DADOS")).isEqualTo("cd-do-vault");
        assertThat(ambiente.getProperty("CIFRA_CHAVE_INDICE")).isEqualTo("ci-do-vault");
        assertThat(ambiente.getProperty("BFF_SEGREDO")).isEqualTo("bff-do-vault");
        assertThat(ambiente.getProperty("DB_PASSWORD")).isEqualTo("senha-exec-do-vault");
        assertThat(ambiente.getProperty("DB_MIGRACAO_PASSWORD")).isEqualTo("senha-mig-do-vault");
        assertThat(pedidos).first().isEqualTo("POST /v1/auth/approle/login");
        assertThat(pedidos).last().isEqualTo("POST /v1/auth/token/revoke-self");
        assertThat(pedidos).contains("GET /v1/kawashi-secrets/data/api/KW_EXEC");  // a conta, sem o "[schema]" do proxy
    }

    @Test
    void semVaultNadaMuda() {
        StandardEnvironment ambiente = new StandardEnvironment();
        ambiente.getPropertySources().addLast(new MapPropertySource("dotenv", Map.of("DB_PASSWORD", "do-env")));
        new SegredosDoVault().postProcessEnvironment(ambiente, null);
        assertThat(ambiente.getProperty("DB_PASSWORD")).isEqualTo("do-env");
        assertThat(pedidos).isEmpty();
    }

    @Test
    void papelDeOutroAmbienteContaInvalidaOuPapelErradoImpedemASubida() {
        // O papel do TESTE_DEV pedindo o PROD: o Vault nega (403) e a API nao sobe; o token e revogado.
        assertThatThrownBy(() -> new SegredosDoVault().postProcessEnvironment(ambiente(Map.of("VAULT_AMBIENTE", "PROD")), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP 403");
        assertThat(pedidos).last().isEqualTo("POST /v1/auth/token/revoke-self");
        assertThatThrownBy(() -> new SegredosDoVault().postProcessEnvironment(ambiente(Map.of("DB_USER", "../bff/PROD")), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DB_USER");
        assertThatThrownBy(() -> new SegredosDoVault().postProcessEnvironment(ambiente(Map.of("VAULT_SECRET_ID", "zzzz-secret-falso-zzzz")), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP 400").hasMessageNotContaining("zzzz-secret-falso-zzzz");
    }
}
