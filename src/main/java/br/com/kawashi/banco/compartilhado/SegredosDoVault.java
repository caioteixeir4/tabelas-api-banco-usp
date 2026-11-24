package br.com.kawashi.banco.compartilhado;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Le os segredos da API do Vault na subida, antes de qualquer bean (e antes de abrir o banco),
 * quando a API tem um papel AppRole (infra/scripts/criar-approle.ps1 do repositorio de regras):
 * <ul>
 *   <li>com VAULT_ADDR, VAULT_ROLE_ID e VAULT_SECRET_ID (do .env ou do ambiente): entra no Vault com o
 *       papel e le, do ambiente VAULT_AMBIENTE (padrao: o do DB_SCHEMA):
 *       <ul>
 *         <li>{@code kawashi-secrets/cifra/<AMB>}: CIFRA_CHAVE_DADOS e CIFRA_CHAVE_INDICE;</li>
 *         <li>{@code kawashi-secrets/bff/<AMB>}: BFF_SEGREDO;</li>
 *         <li>{@code kawashi-secrets/api/<conta>} das contas DB_USER e DB_MIGRACAO_USER: DB_PASSWORD
 *             e DB_MIGRACAO_PASSWORD.</li>
 *       </ul>
 *       Revoga o proprio token e poe os valores na frente de tudo (valem mais que o .env). Qualquer
 *       falha IMPEDE a subida: configurado o Vault, nada de cair no .env sem avisar;</li>
 *   <li>sem nenhum dos tres: nada muda, os segredos vem do .env (desenvolvimento);</li>
 *   <li>so parte deles: nao sobe (configuracao pela metade).</li>
 * </ul>
 * Vault em http so no proprio computador (localhost ou o conteiner kawashi-vault); fora disso, https.
 * Os valores lidos nunca vao para o log nem para mensagem de erro.
 */
public class SegredosDoVault implements EnvironmentPostProcessor, Ordered {

    static final String FONTE = "segredosDoVault";
    private static final Pattern AMBIENTE = Pattern.compile("[A-Z][A-Z_]{1,19}");
    private static final Set<String> HOSTS_SEM_TLS = Set.of("localhost", "127.0.0.1", "[::1]", "kawashi-vault");
    private static final Duration ESPERA = Duration.ofSeconds(5);
    private static final JsonMapper JSON = JsonMapper.shared();

    /** Depois do ConfigDataEnvironmentPostProcessor: o .env ja foi lido. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment ambiente, SpringApplication aplicacao) {
        String endereco = ambiente.getProperty("VAULT_ADDR", "").strip();
        String roleId = ambiente.getProperty("VAULT_ROLE_ID", "").strip();
        String secretId = ambiente.getProperty("VAULT_SECRET_ID", "").strip();
        if (endereco.isEmpty() && roleId.isEmpty() && secretId.isEmpty()) {
            return;  // sem Vault: o segredo vem do .env
        }
        if (endereco.isEmpty() || roleId.isEmpty() || secretId.isEmpty()) {
            throw new IllegalStateException("Vault configurado pela metade: informe VAULT_ADDR, VAULT_ROLE_ID e VAULT_SECRET_ID (ou nenhum).");
        }
        String schema = ambiente.getProperty("DB_SCHEMA", "").strip();
        String amb = ambiente.getProperty("VAULT_AMBIENTE", schema.startsWith("KAWASHI_ENV_") ? schema.substring(12) : "").strip();
        if (!AMBIENTE.matcher(amb).matches()) {
            throw new IllegalStateException("VAULT_AMBIENTE ausente ou invalido (ex.: TESTE_DEV).");
        }
        String execucao = conta(ambiente.getProperty("DB_USER", ""), "DB_USER");
        String migracao = ambiente.getProperty("DB_MIGRACAO_USER", "").isBlank() ? null
                : conta(ambiente.getProperty("DB_MIGRACAO_USER"), "DB_MIGRACAO_USER");
        Cliente vault = new Cliente(endereco);
        String token = vault.entrar(roleId, secretId);
        try {
            Map<String, Object> lidos = new LinkedHashMap<>();
            lidos.put("CIFRA_CHAVE_DADOS", vault.campo(token, "cifra/" + amb, "chave_dados"));
            lidos.put("CIFRA_CHAVE_INDICE", vault.campo(token, "cifra/" + amb, "chave_indice"));
            lidos.put("BFF_SEGREDO", vault.campo(token, "bff/" + amb, "segredo"));
            lidos.put("DB_PASSWORD", vault.campo(token, "api/" + execucao, "db_password"));
            if (migracao != null) {
                lidos.put("DB_MIGRACAO_PASSWORD", vault.campo(token, "api/" + migracao, "db_password"));
            }
            ambiente.getPropertySources().addFirst(new MapPropertySource(FONTE, lidos));
        } finally {
            vault.revogar(token);
        }
    }

    private static final Pattern CONTA = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,29}");

    /** A conta do banco, sem o schema do proxy ("CONTA[KAWASHI_ENV_X_APP]" vira "CONTA"). */
    static String conta(String usuario, String nome) {
        String c = usuario.strip();
        int colchete = c.indexOf('[');
        if (colchete >= 0) {
            c = c.substring(0, colchete);
        }
        if (!CONTA.matcher(c).matches()) {
            throw new IllegalStateException(nome + " ausente ou fora do padrao: e dele que sai o caminho da senha no Vault.");
        }
        return c;
    }

    /** O minimo da API HTTP do Vault: login AppRole, leitura de KV v2 e revogacao do proprio token. */
    static final class Cliente {

        private final URI base;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(ESPERA).followRedirects(HttpClient.Redirect.NEVER).build();

        Cliente(String endereco) {
            URI uri = URI.create(endereco.endsWith("/") ? endereco.substring(0, endereco.length() - 1) : endereco);
            if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && HOSTS_SEM_TLS.contains(uri.getHost()))) {
                throw new IllegalStateException("VAULT_ADDR precisa de https fora do proprio computador.");
            }
            this.base = uri;
        }

        String entrar(String roleId, String secretId) {
            JsonNode r = pedir(HttpRequest.newBuilder(base.resolve("/v1/auth/approle/login"))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("role_id", roleId, "secret_id", secretId)))),
                    "entrar com o papel (role_id/secret_id vencido ou errado?)");
            String token = r.path("auth").path("client_token").stringValue(null);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("O Vault nao devolveu token ao entrar com o papel.");
            }
            return token;
        }

        String campo(String token, String caminho, String campo) {
            JsonNode r = pedir(HttpRequest.newBuilder(base.resolve("/v1/kawashi-secrets/data/" + caminho)).header("X-Vault-Token", token).GET(),
                    "ler kawashi-secrets/" + caminho);
            String valor = r.path("data").path("data").path(campo).stringValue(null);
            if (valor == null || valor.isBlank()) {
                throw new IllegalStateException("Falta o campo '" + campo + "' em kawashi-secrets/" + caminho + " no Vault.");
            }
            return valor;
        }

        void revogar(String token) {
            try {
                http.send(HttpRequest.newBuilder(base.resolve("/v1/auth/token/revoke-self")).timeout(ESPERA).header("X-Vault-Token", token)
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                // o token dura 5 minutos de qualquer jeito
            }
        }

        private JsonNode pedir(HttpRequest.Builder pedido, String oQue) {
            HttpResponse<String> resposta;
            try {
                resposta = http.send(pedido.timeout(ESPERA).header("Content-Type", "application/json").build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Vault inacessivel ao " + oQue + ": " + e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrompido ao " + oQue + ".");
            }
            if (resposta.statusCode() != 200) {
                // So o status: o corpo pode ecoar o que foi mandado.
                throw new IllegalStateException("O Vault recusou " + oQue + " (HTTP " + resposta.statusCode() + ").");
            }
            return JSON.readTree(resposta.body());
        }
    }
}
