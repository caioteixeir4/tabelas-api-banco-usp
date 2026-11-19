package br.com.kawashi.banco.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.jayway.jsonpath.JsonPath;

import br.com.kawashi.banco.testes.Api;
import br.com.kawashi.banco.testes.GeradorDeDocumentos;
import br.com.kawashi.banco.testes.IntegracaoApi;
import br.com.kawashi.banco.testes.UsuarioDeTeste;

/**
 * O login de ponta a ponta contra o Oracle real: token, bloqueios, sessoes, troca de senha e o que
 * fica (e o que nao fica) no banco. Os numeros vem de PARAMETRO_SEGURANCA (15 min, teto de 8 h,
 * 3 sessoes, 5 erros bloqueiam 30 min, 10 erros por IP em 10 min bloqueiam 1 h, 90 dias).
 */
@IntegracaoApi
class LoginApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UsuarioDeTeste usuarios;
    @Autowired
    private SchemasDoLogin schemas;

    /** Cada teste usa um IP proprio, para as falhas de um nao contarem no bloqueio de IP de outro. */
    private final String ip = "10." + ThreadLocalRandom.current().nextInt(1, 255) + "." + ThreadLocalRandom.current().nextInt(1, 255)
            + "." + ThreadLocalRandom.current().nextInt(1, 255);

    /**
     * A sessao do banco e posta num fuso que NAO e UTC durante o teste. Foi assim que o defeito de
     * fuso apareceu (token vencido valendo mais 5 h): com a sessao em UTC ele ficaria escondido.
     * A transacao do teste usa uma conexao so, e os pedidos do MockMvc a compartilham.
     */
    @BeforeEach
    void sessaoForaDeUtc() {
        jdbc.execute("ALTER SESSION SET TIME_ZONE = '-05:00'");
    }

    @AfterEach
    void sessaoDeVolta() {
        jdbc.execute("ALTER SESSION SET TIME_ZONE = '" + java.time.ZonedDateTime.now().getOffset().getId().replace("Z", "+00:00") + "'");
    }

    @Test
    void horasDaRespostaSaemEmUtcEBatemComOBanco() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo();
        String corpo = Api.corpo(login(u.email(), u.senha()).andExpect(status().isOk()));
        String expira = JsonPath.read(corpo, "$.expiraEm");
        assertThat(expira).endsWith("Z");
        // O vencimento informado ao cliente fica entre 14 e 15 minutos a partir de agora, em UTC.
        long minutos = java.time.Duration.between(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC),
                java.time.OffsetDateTime.parse(expira)).toMinutes();
        assertThat(minutos).isBetween(13L, 15L);
    }

    private ResultActions login(String email, String senha) throws Exception {
        return login(email, senha, ip);
    }

    private ResultActions login(String email, String senha, String deOnde) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/auth/login").with(r -> { r.setRemoteAddr(deOnde); return r; })
                .contentType(MediaType.APPLICATION_JSON).characterEncoding(StandardCharsets.UTF_8)
                .content("{\"email\":\"" + email + "\",\"senha\":\"" + senha + "\"}"));
    }

    private String token(UsuarioDeTeste.Criado u) throws Exception {
        return JsonPath.read(Api.corpo(login(u.email(), u.senha()).andExpect(status().isOk())), "$.token");
    }

    private ResultActions comToken(MockHttpServletRequestBuilder pedido, String token) throws Exception {
        return mvc.perform(pedido.header("Authorization", "Bearer " + token).with(r -> { r.setRemoteAddr(ip); return r; }));
    }

    private ResultActions empresas(String token) throws Exception {
        return comToken(MockMvcRequestBuilders.get("/api/empresas"), token);
    }

    private ResultActions trocar(String token, String atual, String nova) throws Exception {
        return comToken(MockMvcRequestBuilders.post("/api/auth/trocar-senha").contentType(MediaType.APPLICATION_JSON)
                .characterEncoding(StandardCharsets.UTF_8)
                .content("{\"senhaAtual\":\"" + atual + "\",\"senhaNova\":\"" + nova + "\"}"), token);
    }

    private List<String> eventos(long usuarioId) {
        return jdbc.queryForList("SELECT EVENTO FROM " + schemas.log("LOG_SEGURANCA") + " WHERE USUARIO_ID = ? ORDER BY ORDEM",
                String.class, usuarioId);
    }

    private String motivoDaRevogacao(String token) {
        return jdbc.queryForObject("SELECT MOTIVO_REVOGACAO FROM " + schemas.sessao("TOKEN") + " WHERE TOKEN_HASH = ?",
                String.class, Tokens.hash(token));
    }

    // ---- sem token, nada ---------------------------------------------------------

    @Test
    void semTokenOuComTokenInvalidoTodaApiRespondeOMesmo401() throws Exception {
        String esperado = "Faça login e envie o token no cabeçalho Authorization: Bearer <token>.";
        mvc.perform(MockMvcRequestBuilders.get("/api/empresas"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Bearer")))
                .andExpect(jsonPath("$.detail").value(esperado));
        empresas("kw_curto").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(esperado));
        // Formato perfeito, mas nao existe: mesma resposta (quem testa tokens nao aprende nada).
        empresas("kw_" + "A".repeat(43)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(esperado));
        mvc.perform(MockMvcRequestBuilders.get("/api/empresas").header("Authorization", "Basic dXNlcjpzZW5oYQ=="))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenNaUrlERecusadoMesmoSendoValido() throws Exception {
        String token = token(usuarios.novo());
        mvc.perform(MockMvcRequestBuilders.get("/api/empresas?access_token=" + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value(containsString("nunca na URL")));
        mvc.perform(MockMvcRequestBuilders.get("/api/empresas?ativa=true&token=" + token)).andExpect(status().isUnauthorized());
    }

    // ---- o token ------------------------------------------------------------------

    @Test
    void loginDevolveTokenOpacoQueAbreAApi() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo();
        String corpo = Api.corpo(login(u.email().toUpperCase(), u.senha()).andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("Bearer")).andExpect(jsonPath("$.escopo").value("COMPLETO")));
        String token = JsonPath.read(corpo, "$.token");
        assertThat(token).matches("kw_[A-Za-z0-9_-]{43}");

        // Teto de 8 h depois do login; o vencimento deslizante, 15 min (conferido pelo relogio do banco).
        Map<String, Object> t = jdbc.queryForMap("SELECT ROUND((CAST(EXPIRA_EM AS DATE) - CAST(CRIADO_EM AS DATE)) * 1440) MIN,"
                + " ROUND((CAST(EXPIRA_ABSOLUTO_EM AS DATE) - CAST(CRIADO_EM AS DATE)) * 24) HORAS FROM "
                + schemas.sessao("TOKEN") + " WHERE TOKEN_HASH = ?", Tokens.hash(token));
        assertThat(((Number) t.get("MIN")).intValue()).isEqualTo(15);
        assertThat(((Number) t.get("HORAS")).intValue()).isEqualTo(8);

        empresas(token).andExpect(status().isOk());
        comToken(MockMvcRequestBuilders.get("/api/auth/eu"), token).andExpect(status().isOk())
                .andExpect(jsonPath("$.usuarioId").value(u.usuarioId())).andExpect(jsonPath("$.pessoaId").value(u.pessoaId()));
        assertThat(eventos(u.usuarioId())).contains("USUARIO_CRIADO", "LOGIN");
    }

    @Test
    void noBancoNaoFicaTokenSenhaNemEmailEmClaro() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo();
        String token = token(u);
        Map<String, Object> linhaDoToken = jdbc.queryForMap("SELECT * FROM " + schemas.sessao("TOKEN") + " WHERE USUARIO_ID = ?", u.usuarioId());
        assertThat(linhaDoToken.get("TOKEN_HASH")).isEqualTo(Tokens.hash(token));
        assertThat(linhaDoToken.values()).noneMatch(v -> v != null && v.toString().contains(token.substring(3)));

        Map<String, Object> usuario = jdbc.queryForMap("SELECT * FROM " + schemas.login("USUARIO") + " WHERE ID = ?", u.usuarioId());
        assertThat((String) usuario.get("SENHA_HASH")).startsWith("$argon2id$").doesNotContain(u.senha());
        assertThat((String) usuario.get("EMAIL_CIFRADO")).startsWith("v1:").doesNotContain(u.email());
        assertThat(usuario.values()).noneMatch(v -> v != null && v.toString().contains(u.email()));
        // A tentativa de login guarda a impressao digital do e-mail, nunca o e-mail.
        assertThat(jdbc.queryForList("SELECT * FROM " + schemas.log("TENTATIVA_LOGIN") + " WHERE USUARIO_ID = ?", u.usuarioId()))
                .allSatisfy(l -> assertThat(l.values()).noneMatch(v -> v != null && v.toString().contains(u.email())));
    }

    // ---- recusas ------------------------------------------------------------------

    @Test
    void senhaErradaEEmailInexistenteRecebemAMesmaResposta() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo();
        String errada = Api.corpo(login(u.email(), "senha errada qualquer").andExpect(status().isUnauthorized()));
        String inexistente = Api.corpo(login("ninguem" + u.email(), "senha errada qualquer").andExpect(status().isUnauthorized()));
        assertThat(JsonPath.<String>read(errada, "$.detail")).isEqualTo(JsonPath.read(inexistente, "$.detail"));
        assertThat(jdbc.queryForList("SELECT MOTIVO FROM " + schemas.log("TENTATIVA_LOGIN") + " WHERE IP_ORIGEM = ? ORDER BY ID",
                String.class, ip)).containsExactly("SENHA_ERRADA", "USUARIO_INEXISTENTE");
    }

    @Test
    void cincoSenhasErradasBloqueiamAContaMesmoParaASenhaCerta() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo();
        for (int i = 0; i < 5; i++) {
            login(u.email(), "senha errada numero " + i).andExpect(status().isUnauthorized());
        }
        login(u.email(), u.senha()).andExpect(status().isUnauthorized());
        assertThat(eventos(u.usuarioId())).contains("CONTA_BLOQUEADA");
        assertThat(jdbc.queryForObject("SELECT ROUND((CAST(BLOQUEADO_ATE AS DATE) - CAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) AS DATE)) * 1440) FROM "
                + schemas.login("USUARIO") + " WHERE ID = ?", Integer.class, u.usuarioId())).isBetween(29, 30);
        // Passado o bloqueio (simulado), a senha certa volta a entrar e o contador zera.
        jdbc.update("UPDATE " + schemas.login("USUARIO") + " SET BLOQUEADO_ATE = SYS_EXTRACT_UTC(SYSTIMESTAMP) - INTERVAL '1' MINUTE WHERE ID = ?", u.usuarioId());
        login(u.email(), u.senha()).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT TENTATIVAS_FALHAS FROM " + schemas.login("USUARIO") + " WHERE ID = ?",
                Integer.class, u.usuarioId())).isZero();
    }

    @Test
    void dezErrosDoMesmoIpBloqueiamOIpMesmoComEmailsDiferentes() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo();
        for (int i = 0; i < 10; i++) {
            login("robo" + i + "@teste.kawashi.invalid", "tentativa de robo " + i).andExpect(status().isUnauthorized());
        }
        login(u.email(), u.senha()).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("\\d+")));
        // Outro IP nao e afetado.
        login(u.email(), u.senha(), "192.0.2." + ThreadLocalRandom.current().nextInt(1, 255)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.log("LOG_SEGURANCA")
                + " WHERE EVENTO = 'IP_BLOQUEADO' AND IP_ORIGEM = ?", Long.class, ip)).isEqualTo(1);
    }

    @Test
    void usuarioInativoNaoEntraEPerdeAsSessoes() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo();
        String token = token(u);
        jdbc.update("UPDATE " + schemas.login("USUARIO") + " SET ATIVO = FALSE WHERE ID = ?", u.usuarioId());
        empresas(token).andExpect(status().isUnauthorized());
        login(u.email(), u.senha()).andExpect(status().isUnauthorized());
    }

    @Test
    void corpoDeLoginInvalidoE400() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b.c\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- sessoes --------------------------------------------------------------------

    @Test
    void logoutDerrubaOTokenNaHora() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo();
        String token = token(u);
        comToken(MockMvcRequestBuilders.post("/api/auth/logout"), token).andExpect(status().isNoContent());
        empresas(token).andExpect(status().isUnauthorized());
        assertThat(motivoDaRevogacao(token)).isEqualTo("LOGOUT");
        assertThat(eventos(u.usuarioId())).contains("LOGOUT");
    }

    @Test
    void sairDeTodosDerrubaTodasAsSessoes() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo();
        String a = token(u);
        String b = token(u);
        comToken(MockMvcRequestBuilders.post("/api/auth/sair-de-todos"), a).andExpect(status().isNoContent());
        empresas(a).andExpect(status().isUnauthorized());
        empresas(b).andExpect(status().isUnauthorized());
        assertThat(motivoDaRevogacao(b)).isEqualTo("SAIR_DE_TODOS");
    }

    @Test
    void aQuartaSessaoDerrubaAMaisAntiga() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo();
        String primeira = token(u);
        String segunda = token(u);
        String terceira = token(u);
        String quarta = token(u);
        empresas(primeira).andExpect(status().isUnauthorized());
        assertThat(motivoDaRevogacao(primeira)).isEqualTo("LIMITE_SESSOES");
        empresas(segunda).andExpect(status().isOk());
        empresas(terceira).andExpect(status().isOk());
        empresas(quarta).andExpect(status().isOk());
        assertThat(eventos(u.usuarioId())).contains("SESSAO_ENCERRADA");
    }

    @Test
    void tokenVencidoOuPassadoDoTetoNaoEntra() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo();
        String parado = token(u);
        jdbc.update("UPDATE " + schemas.sessao("TOKEN") + " SET CRIADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) - INTERVAL '1' HOUR,"
                + " ULTIMO_USO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) - INTERVAL '20' MINUTE, EXPIRA_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) - INTERVAL '5' MINUTE"
                + " WHERE TOKEN_HASH = ?", Tokens.hash(parado));
        empresas(parado).andExpect(status().isUnauthorized());

        String noTeto = token(u);
        jdbc.update("UPDATE " + schemas.sessao("TOKEN") + " SET CRIADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) - INTERVAL '9' HOUR,"
                + " EXPIRA_ABSOLUTO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) - INTERVAL '1' MINUTE, EXPIRA_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) - INTERVAL '1' MINUTE"
                + " WHERE TOKEN_HASH = ?", Tokens.hash(noTeto));
        empresas(noTeto).andExpect(status().isUnauthorized());
    }

    @Test
    void usoRenovaAValidadeSemPassarDoTetoEGravaNoMaximoUmaVezPorMinuto() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo();
        String token = token(u);
        String hash = Tokens.hash(token);
        // Ultimo uso ha 5 min, vencendo em 1 min: o pedido empurra para 15 min a frente.
        jdbc.update("UPDATE " + schemas.sessao("TOKEN") + " SET CRIADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) - INTERVAL '10' MINUTE,"
                + " ULTIMO_USO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) - INTERVAL '5' MINUTE, EXPIRA_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) + INTERVAL '1' MINUTE WHERE TOKEN_HASH = ?", hash);
        empresas(token).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT ROUND((CAST(EXPIRA_EM AS DATE) - CAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) AS DATE)) * 1440) FROM "
                + schemas.sessao("TOKEN") + " WHERE TOKEN_HASH = ?", Integer.class, hash)).isBetween(14, 15);

        // Usado de novo em menos de 1 minuto: nao grava (o vencimento fica onde estava).
        Object antes = jdbc.queryForObject("SELECT ULTIMO_USO_EM FROM " + schemas.sessao("TOKEN") + " WHERE TOKEN_HASH = ?", Object.class, hash);
        empresas(token).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT ULTIMO_USO_EM FROM " + schemas.sessao("TOKEN") + " WHERE TOKEN_HASH = ?", Object.class, hash))
                .isEqualTo(antes);

        // Perto do teto, a renovacao para no teto.
        jdbc.update("UPDATE " + schemas.sessao("TOKEN") + " SET ULTIMO_USO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) - INTERVAL '5' MINUTE,"
                + " EXPIRA_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) + INTERVAL '1' MINUTE, EXPIRA_ABSOLUTO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) + INTERVAL '3' MINUTE WHERE TOKEN_HASH = ?", hash);
        empresas(token).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT CASE WHEN EXPIRA_EM = EXPIRA_ABSOLUTO_EM THEN 1 ELSE 0 END FROM "
                + schemas.sessao("TOKEN") + " WHERE TOKEN_HASH = ?", Integer.class, hash)).isEqualTo(1);
    }

    // ---- senha ----------------------------------------------------------------------

    @Test
    void senhaVencidaSoServeParaTrocarASenha() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo(true);
        String corpo = Api.corpo(login(u.email(), u.senha()).andExpect(status().isOk()).andExpect(jsonPath("$.escopo").value("TROCAR_SENHA")));
        String restrito = JsonPath.read(corpo, "$.token");
        empresas(restrito).andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("Senha vencida"));
        comToken(MockMvcRequestBuilders.get("/api/auth/eu"), restrito).andExpect(status().isOk())
                .andExpect(jsonPath("$.escopo").value("TROCAR_SENHA"));

        trocar(restrito, u.senha(), "outra frase bem comprida").andExpect(status().isNoContent());
        empresas(restrito).andExpect(status().isUnauthorized());
        assertThat(motivoDaRevogacao(restrito)).isEqualTo("TROCA_SENHA");
        login(u.email(), "outra frase bem comprida").andExpect(status().isOk()).andExpect(jsonPath("$.escopo").value("COMPLETO"));
        assertThat(eventos(u.usuarioId())).contains("SENHA_ALTERADA");
    }

    @Test
    void trocarASenhaDerrubaTodasAsSessoes() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo();
        String a = token(u);
        String b = token(u);
        trocar(a, u.senha(), "frase nova e comprida 1").andExpect(status().isNoContent());
        empresas(a).andExpect(status().isUnauthorized());
        empresas(b).andExpect(status().isUnauthorized());
    }

    @Test
    void trocaRecusaSenhaAtualErradaSenhaFracaERepetida() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo();
        String token = token(u);
        trocar(token, "nao e a atual de jeito nenhum", "frase nova e comprida 2").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Senha atual incorreta"));
        trocar(token, u.senha(), "curta").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.problemas").isArray());
        trocar(token, u.senha(), "password1234").andExpect(status().isUnprocessableEntity());
        String nomeDoEmail = u.email().substring(0, u.email().indexOf('@'));
        trocar(token, u.senha(), nomeDoEmail + " e mais coisa").andExpect(status().isUnprocessableEntity());
        trocar(token, u.senha(), u.senha()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("não pode repetir")));
        // Troca de verdade, e tenta voltar para a anterior: recusado pelo historico.
        trocar(token, u.senha(), "frase nova e comprida 3").andExpect(status().isNoContent());
        String novo = token(new UsuarioDeTeste.Criado(u.usuarioId(), u.pessoaId(), u.email(), "frase nova e comprida 3"));
        trocar(novo, "frase nova e comprida 3", u.senha()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("não pode repetir")));
    }

    // ---- o banco protege o que a API nao deve poder fazer ------------------------------

    @Test
    void aApiNaoAfrouxaParametrosNemApagaSessoesOuLogs() {
        assertThatThrownBy(() -> jdbc.update("UPDATE " + schemas.login("PARAMETRO_SEGURANCA") + " SET VALOR = 999 WHERE CHAVE = 'LOGIN_MAX_TENTATIVAS'"))
                .isInstanceOf(DataAccessException.class).hasMessageMatching("(?s).*ORA-(01031|41900).*");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + schemas.sessao("TOKEN")))
                .isInstanceOf(DataAccessException.class).hasMessageMatching("(?s).*ORA-(01031|41900).*");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + schemas.log("LOG_SEGURANCA")))
                .isInstanceOf(DataAccessException.class).hasMessageMatching("(?s).*ORA-(01031|41900).*");
        assertThatThrownBy(() -> jdbc.update("UPDATE " + schemas.log("TENTATIVA_LOGIN") + " SET SUCESSO = TRUE"))
                .isInstanceOf(DataAccessException.class).hasMessageMatching("(?s).*ORA-(01031|41900).*");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + schemas.login("USUARIO")))
                .isInstanceOf(DataAccessException.class).hasMessageMatching("(?s).*ORA-(01031|41900).*");
    }

    @Test
    void osLogsDizemQuemFezEACorrenteDeSegurancaEstaInteira() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo();
        String token = token(u);
        String corpo = Api.corpo(comToken(MockMvcRequestBuilders.post("/api/empresas").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cnpj\":\"" + GeradorDeDocumentos.cnpj() + "\",\"razaoSocial\":\"Quem Criou LTDA\"}"), token)
                .andExpect(status().isCreated()));
        long empresaId = ((Number) JsonPath.read(corpo, "$.id")).longValue();
        String nomeDoLog = jdbc.queryForObject("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') || '_LOG' FROM DUAL", String.class);
        assertThat(jdbc.queryForObject("SELECT USUARIO FROM " + nomeDoLog + ".LOG_ALTERACAO WHERE TABELA = 'EMPRESA' AND REGISTRO_ID = ?",
                String.class, empresaId)).isEqualTo("USUARIO:" + u.usuarioId());
        assertThat(jdbc.queryForList("SELECT USUARIO FROM " + nomeDoLog + ".LOG_ACESSO WHERE CAMINHO = '/api/empresas' AND IP_ORIGEM = ?",
                String.class, ip)).contains("USUARIO:" + u.usuarioId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.log("V_LOG_SEGURANCA_QUEBRAS"), Long.class)).isZero();
    }
}
