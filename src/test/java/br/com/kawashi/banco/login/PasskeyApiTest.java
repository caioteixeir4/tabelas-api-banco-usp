package br.com.kawashi.banco.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import br.com.kawashi.banco.testes.Api;
import br.com.kawashi.banco.testes.AutenticadorDeTeste;
import br.com.kawashi.banco.testes.IntegracaoApi;
import br.com.kawashi.banco.testes.UsuarioDeTeste;

/**
 * O login por passkey de ponta a ponta, com um aparelho de teste ({@link AutenticadorDeTeste}) que
 * assina de verdade: convite, entrar, desafio de uso unico, origem e dominio conferidos, contador,
 * clone, codigo de recuperacao, remocao de passkey, sair, bloqueio por IP e a exigencia do BFF.
 */
@IntegracaoApi
class PasskeyApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioDeTeste usuarios;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SchemasDoLogin schemas;

    /**
     * A sessao do banco FORA de UTC, como a da API no Windows (-05:00). Com a sessao em UTC, comparar
     * uma coluna com SYSTIMESTAMP em vez de SYS_EXTRACT_UTC(SYSTIMESTAMP) passaria despercebido, e foi
     * assim que um token vencido valeu mais 5 horas (item 31 de bugs-primeiro-teste.md, no repositorio
     * de regras). O teste roda numa transacao, entao a API usa esta mesma conexao.
     */
    @org.junit.jupiter.api.BeforeEach
    void sessaoForaDeUtc() {
        jdbc.execute("ALTER SESSION SET TIME_ZONE = '-05:00'");
    }

    /** Uma conta que ja aceitou o convite: tem uma passkey, um token e os codigos de recuperacao. */
    private record Conta(UsuarioDeTeste.Criado usuario, String handle, AutenticadorDeTeste aparelho, String token,
            List<String> codigos) {
    }

    // ---- caminho feliz -------------------------------------------------------------------------

    @Test
    void conviteCadastraAPasskeyEDaTokenECodigos() throws Exception {
        Conta c = contaComPasskey(ipNovo());

        assertThat(c.token()).startsWith("kw_");
        assertThat(c.codigos()).hasSize(10).allMatch(k -> k.matches("[A-Z2-9]{4}(-[A-Z2-9]{4}){3}"));
        chamar(get("/api/auth/eu"), c.token(), Api.IP_DE_TESTE).andExpect(status().isOk())
                .andExpect(jsonPath("$.usuarioId").value(c.usuario().usuarioId()))
                .andExpect(jsonPath("$.escopo").value("COMPLETO"));
        chamar(get("/api/empresas"), c.token(), Api.IP_DE_TESTE).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.login("PASSKEY") + " WHERE USUARIO_ID = ? AND CREDENCIAL_ID = ?",
                Long.class, c.usuario().usuarioId(), c.aparelho().credencialIdB64())).isEqualTo(1L);
        // Do segredo do convite e dos codigos, o banco so tem o hash.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.login("CODIGO_RECUPERACAO")
                + " WHERE USUARIO_ID = ? AND CODIGO_HASH = ?", Long.class, c.usuario().usuarioId(), c.codigos().get(0))).isZero();
    }

    @Test
    void entraComAPasskeyCadastrada() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);

        String token = JsonPath.read(corpo(entrar(c.aparelho(), ip).andExpect(status().isOk())), "$.token");

        assertThat(token).startsWith("kw_").isNotEqualTo(c.token());
        chamar(get("/api/auth/eu"), token, ip).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT CONTADOR FROM " + schemas.login("PASSKEY") + " WHERE CREDENCIAL_ID = ?", Long.class,
                c.aparelho().credencialIdB64())).isEqualTo(1L);
        assertThat(ultimoMotivo(ip)).isNull();   // ultima tentativa foi sucesso
        // A lista mostra a passkey com as horas em UTC explicito, e nada da chave publica.
        chamar(get("/api/auth/passkeys"), token, ip).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nome").value("Notebook"))
                .andExpect(jsonPath("$[0].ultimoUsoEm").value(org.hamcrest.Matchers.endsWith("Z")))
                .andExpect(jsonPath("$[0].dadosDaCredencial").doesNotExist());
    }

    // ---- so o BFF ------------------------------------------------------------------------------

    @Test
    void semOSegredoDoBffNemOLoginAtende() throws Exception {
        mvc.perform(post("/api/auth/passkey/login/opcoes")).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/passkey/login/opcoes").header("X-Kawashi-Bff", "segredo-errado-0123456789abcdef-0123"))
                .andExpect(status().isForbidden());
        // Segredo certo, mas sem o IP do navegador: sem ele o bloqueio por IP pegaria o BFF inteiro.
        mvc.perform(post("/api/auth/passkey/login/opcoes").header("X-Kawashi-Bff", Api.SEGREDO_DO_BFF))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/passkey/login/opcoes").header("X-Kawashi-Bff", Api.SEGREDO_DO_BFF)
                .header("X-Kawashi-Ip-Cliente", "1.2.3.4, 5.6.7.8")).andExpect(status().isBadRequest());
        mvc.perform(Api.doBff(post("/api/auth/passkey/login/opcoes"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey.challenge").isString())
                .andExpect(jsonPath("$.publicKey.userVerification").value("required"));
    }

    @Test
    void tokenValidoSemOSegredoDoBffEhRecusado() throws Exception {
        String token = usuarios.token();
        mvc.perform(get("/api/empresas").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    // ---- convite -------------------------------------------------------------------------------

    @Test
    void conviteSoServeUmaVez() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);
        postar("/api/auth/convite/opcoes", "{\"convite\":\"" + c.usuario().convite() + "\"}", null, ip)
                .andExpect(status().isUnauthorized());
        assertThat(ultimoMotivo(ip)).isEqualTo("CONVITE_INVALIDO");
    }

    @Test
    void conviteInventadoEhRecusado() throws Exception {
        String ip = ipNovo();
        postar("/api/auth/convite/opcoes", "{\"convite\":\"kwc_inventado\"}", null, ip).andExpect(status().isUnauthorized());
        assertThat(ultimoMotivo(ip)).isEqualTo("CONVITE_INVALIDO");
    }

    @Test
    void desafioDeCadastroDeOutroUsuarioNaoServe() throws Exception {
        String ip = ipNovo();
        UsuarioDeTeste.Criado a = usuarios.novo();
        UsuarioDeTeste.Criado b = usuarios.novo();
        String opcoesDeA = corpo(postar("/api/auth/convite/opcoes", "{\"convite\":\"" + a.convite() + "\"}", null, ip));
        String credencial = new AutenticadorDeTeste().cadastrar(JsonPath.read(opcoesDeA, "$.publicKey.challenge"),
                JsonPath.read(opcoesDeA, "$.publicKey.user.id"));

        postar("/api/auth/convite", aceite(b.convite(), credencial), null, ip).andExpect(status().isUnauthorized());
        assertThat(ultimoMotivo(ip)).isEqualTo("DESAFIO_INVALIDO");
    }

    // ---- verificacao do login ------------------------------------------------------------------

    @Test
    void desafioDeLoginSoServeUmaVez() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);
        String resposta = c.aparelho().entrar(desafioDeLogin(ip));
        postar("/api/auth/passkey/login", "{\"credencial\":" + resposta + "}", null, ip).andExpect(status().isOk());

        postar("/api/auth/passkey/login", "{\"credencial\":" + resposta + "}", null, ip).andExpect(status().isUnauthorized());
        assertThat(ultimoMotivo(ip)).isEqualTo("DESAFIO_INVALIDO");
    }

    @Test
    void desafioVencidoNaoServe() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);
        String desafio = desafioDeLogin(ip);
        // Venceu ha 1 minuto (em UTC): com a sessao em -05:00, uma comparacao errada ainda o aceitaria.
        jdbc.update("UPDATE " + schemas.sessao("DESAFIO") + " SET CRIADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) - INTERVAL '10' MINUTE,"
                + " EXPIRA_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) - INTERVAL '1' MINUTE WHERE VALOR = ?", desafio);
        postar("/api/auth/passkey/login", "{\"credencial\":" + c.aparelho().entrar(desafio) + "}", null, ip)
                .andExpect(status().isUnauthorized());
        assertThat(ultimoMotivo(ip)).isEqualTo("DESAFIO_INVALIDO");
    }

    @Test
    void tokenVencidoNaoEntra() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);
        jdbc.update("UPDATE " + schemas.sessao("TOKEN") + " SET CRIADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) - INTERVAL '1' HOUR,"
                + " EXPIRA_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) - INTERVAL '5' MINUTE WHERE TOKEN_HASH = ?", Tokens.hash(c.token()));
        chamar(get("/api/auth/eu"), c.token(), ip).andExpect(status().isUnauthorized());
    }

    @Test
    void desafioInventadoEhRecusado() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);
        String resposta = c.aparelho().entrar("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        postar("/api/auth/passkey/login", "{\"credencial\":" + resposta + "}", null, ip).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Não foi possível entrar."));
        assertThat(ultimoMotivo(ip)).isEqualTo("DESAFIO_INVALIDO");
    }

    @Test
    void respostaFeitaEmOutroSiteEhRecusada() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);
        c.aparelho().origem = "https://kawashi-falso.example";
        entrar(c.aparelho(), ip).andExpect(status().isUnauthorized());
        assertThat(ultimoMotivo(ip)).isEqualTo("VERIFICACAO_FALHOU");
    }

    @Test
    void passkeyDeOutroDominioEhRecusada() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);
        c.aparelho().rpId = "kawashi-falso.example";
        entrar(c.aparelho(), ip).andExpect(status().isUnauthorized());
        assertThat(ultimoMotivo(ip)).isEqualTo("VERIFICACAO_FALHOU");
    }

    @Test
    void semVerificarAPessoaNaoEntra() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);
        c.aparelho().verificouAPessoa = false;
        entrar(c.aparelho(), ip).andExpect(status().isUnauthorized());
        assertThat(ultimoMotivo(ip)).isEqualTo("VERIFICACAO_FALHOU");
    }

    @Test
    void assinaturaDeOutraChaveComOMesmoIdEhRecusada() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);
        AutenticadorDeTeste falso = new AutenticadorDeTeste(c.aparelho().credencialId());
        falso.handleDoUsuario(c.handle());
        entrar(falso, ip).andExpect(status().isUnauthorized());
        assertThat(ultimoMotivo(ip)).isEqualTo("VERIFICACAO_FALHOU");
    }

    @Test
    void passkeyDesconhecidaEhRecusada() throws Exception {
        String ip = ipNovo();
        entrar(new AutenticadorDeTeste(), ip).andExpect(status().isUnauthorized());
        assertThat(ultimoMotivo(ip)).isEqualTo("CREDENCIAL_DESCONHECIDA");
    }

    @Test
    void contadorQueVoltaIndicaCopiaDoAparelho() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);
        entrar(c.aparelho(), ip).andExpect(status().isOk());   // contador 1
        entrar(c.aparelho(), ip).andExpect(status().isOk());   // contador 2

        c.aparelho().contador = 1;                               // um clone que ficou para tras
        postar("/api/auth/passkey/login", "{\"credencial\":" + c.aparelho().assinar(desafioDeLogin(ip)) + "}", null, ip)
                .andExpect(status().isUnauthorized());
        assertThat(ultimoMotivo(ip)).isEqualTo("CONTADOR_SUSPEITO");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.log("LOG_SEGURANCA")
                + " WHERE EVENTO = 'CONTADOR_SUSPEITO' AND USUARIO_ID = ?", Long.class, c.usuario().usuarioId())).isEqualTo(1L);
    }

    @Test
    void passkeySincronizadaComContadorZeroContinuaEntrando() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);
        c.aparelho().contaAssinaturas = false;
        entrar(c.aparelho(), ip).andExpect(status().isOk());
        entrar(c.aparelho(), ip).andExpect(status().isOk());
    }

    @Test
    void usuarioDesligadoNaoEntra() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);
        jdbc.update("UPDATE " + schemas.login("USUARIO") + " SET ATIVO = FALSE WHERE ID = ?", c.usuario().usuarioId());
        entrar(c.aparelho(), ip).andExpect(status().isUnauthorized());
        assertThat(ultimoMotivo(ip)).isEqualTo("USUARIO_INATIVO");
    }

    // ---- bloqueio por IP -----------------------------------------------------------------------

    @Test
    void dezFalhasDoMesmoIpBloqueiamSoEsseIp() throws Exception {
        String ip = ipNovo();
        for (int i = 0; i < 10; i++) {
            postar("/api/auth/convite/opcoes", "{\"convite\":\"kwc_inventado" + i + "\"}", null, ip).andExpect(status().isUnauthorized());
        }
        postar("/api/auth/passkey/login/opcoes", "{}", null, ip).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        postar("/api/auth/passkey/login/opcoes", "{}", null, ipNovo()).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.log("LOG_SEGURANCA")
                + " WHERE EVENTO = 'IP_BLOQUEADO' AND IP_ORIGEM = ?", Long.class, ip)).isEqualTo(1L);
    }

    // ---- codigo de recuperacao -----------------------------------------------------------------

    @Test
    void codigoDeRecuperacaoSoServeParaCadastrarPasskeyNova() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);
        // Digitado de qualquer jeito: minusculas, sem tracos.
        String digitado = c.codigos().get(3).toLowerCase().replace("-", "");
        String resposta = corpo(postar("/api/auth/recuperacao",
                "{\"email\":\"" + c.usuario().email().toUpperCase() + "\",\"codigo\":\"" + digitado + "\"}", null, ip)
                .andExpect(status().isOk()).andExpect(jsonPath("$.escopo").value("CADASTRAR_PASSKEY")));
        String tokenDeRecuperacao = JsonPath.read(resposta, "$.token");

        // A sessao antiga caiu (pode estar com quem pegou o aparelho).
        chamar(get("/api/auth/eu"), c.token(), ip).andExpect(status().isUnauthorized());
        // Com o token de recuperacao, so cadastrar passkey.
        chamar(get("/api/empresas"), tokenDeRecuperacao, ip).andExpect(status().isForbidden());
        chamar(get("/api/auth/passkeys"), tokenDeRecuperacao, ip).andExpect(status().isOk());
        AutenticadorDeTeste novo = new AutenticadorDeTeste();
        cadastrarMaisUma(tokenDeRecuperacao, novo, ip).andExpect(status().isCreated());
        entrar(novo, ip).andExpect(status().isOk());

        // O mesmo codigo nao serve de novo.
        postar("/api/auth/recuperacao", "{\"email\":\"" + c.usuario().email() + "\",\"codigo\":\"" + digitado + "\"}", null, ip)
                .andExpect(status().isUnauthorized());
        assertThat(ultimoMotivo(ip)).isEqualTo("CODIGO_INVALIDO");
    }

    @Test
    void novosCodigosInvalidamOsAnteriores() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);
        chamar(post("/api/auth/codigos-de-recuperacao"), c.token(), ip).andExpect(status().isOk())
                .andExpect(jsonPath("$.codigos.length()").value(10));
        postar("/api/auth/recuperacao", "{\"email\":\"" + c.usuario().email() + "\",\"codigo\":\"" + c.codigos().get(0) + "\"}", null, ip)
                .andExpect(status().isUnauthorized());
    }

    // ---- passkeys do usuario -------------------------------------------------------------------

    @Test
    void naoRemoveAUltimaPasskeyERemoverDerrubaAsSessoesDela() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);
        long primeira = jdbc.queryForObject("SELECT ID FROM " + schemas.login("PASSKEY") + " WHERE CREDENCIAL_ID = ?", Long.class,
                c.aparelho().credencialIdB64());
        chamar(delete("/api/auth/passkeys/" + primeira), c.token(), ip).andExpect(status().isUnprocessableContent());

        AutenticadorDeTeste segundo = new AutenticadorDeTeste();
        cadastrarMaisUma(c.token(), segundo, ip).andExpect(status().isCreated());
        String tokenDaPrimeira = JsonPath.read(corpo(entrar(c.aparelho(), ip)), "$.token");
        String tokenDaSegunda = JsonPath.read(corpo(entrar(segundo, ip)), "$.token");

        chamar(delete("/api/auth/passkeys/" + primeira), tokenDaSegunda, ip).andExpect(status().isNoContent());

        chamar(get("/api/auth/eu"), tokenDaPrimeira, ip).andExpect(status().isUnauthorized());
        chamar(get("/api/auth/eu"), tokenDaSegunda, ip).andExpect(status().isOk());
        entrar(c.aparelho(), ip).andExpect(status().isUnauthorized());
        assertThat(ultimoMotivo(ip)).isEqualTo("CREDENCIAL_DESCONHECIDA");
    }

    @Test
    void naoCadastraOMesmoAparelhoDuasVezes() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);
        cadastrarMaisUma(c.token(), c.aparelho(), ip).andExpect(status().isUnprocessableContent());
    }

    @Test
    void naoRemovePasskeyDeOutraPessoa() throws Exception {
        String ip = ipNovo();
        Conta a = contaComPasskey(ip);
        Conta b = contaComPasskey(ip);
        cadastrarMaisUma(a.token(), new AutenticadorDeTeste(), ip).andExpect(status().isCreated());
        long daOutra = jdbc.queryForObject("SELECT ID FROM " + schemas.login("PASSKEY") + " WHERE CREDENCIAL_ID = ?", Long.class,
                b.aparelho().credencialIdB64());
        chamar(delete("/api/auth/passkeys/" + daOutra), a.token(), ip).andExpect(status().isNotFound());
    }

    // ---- sair ----------------------------------------------------------------------------------

    @Test
    void sairDerrubaOToken() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);
        chamar(post("/api/auth/logout"), c.token(), ip).andExpect(status().isNoContent());
        chamar(get("/api/auth/eu"), c.token(), ip).andExpect(status().isUnauthorized());
    }

    @Test
    void sairDeTodosDerrubaTodasAsSessoes() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);
        String outro = JsonPath.read(corpo(entrar(c.aparelho(), ip)), "$.token");
        chamar(post("/api/auth/sair-de-todos"), c.token(), ip).andExpect(status().isNoContent());
        chamar(get("/api/auth/eu"), outro, ip).andExpect(status().isUnauthorized());
        chamar(get("/api/auth/eu"), c.token(), ip).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenNaUrlEhRecusado() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);
        mvc.perform(Api.doBff(get("/api/auth/eu?token=" + c.token()), ip)).andExpect(status().isUnauthorized());
    }

    // ---- apoio ---------------------------------------------------------------------------------

    /** Cria um usuario e faz a cerimonia do convite com um aparelho novo. */
    private Conta contaComPasskey(String ip) throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo();
        String opcoes = corpo(postar("/api/auth/convite/opcoes", "{\"convite\":\"" + u.convite() + "\"}", null, ip)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey.authenticatorSelection.residentKey").value("required")));
        String handle = JsonPath.read(opcoes, "$.publicKey.user.id");
        AutenticadorDeTeste aparelho = new AutenticadorDeTeste();
        String credencial = aparelho.cadastrar(JsonPath.read(opcoes, "$.publicKey.challenge"), handle);
        String resposta = corpo(postar("/api/auth/convite", aceite(u.convite(), credencial), null, ip).andExpect(status().isOk()));
        return new Conta(u, handle, aparelho, JsonPath.read(resposta, "$.token"), JsonPath.read(resposta, "$.codigosDeRecuperacao"));
    }

    private ResultActions cadastrarMaisUma(String token, AutenticadorDeTeste aparelho, String ip) throws Exception {
        String opcoes = corpo(chamar(post("/api/auth/passkeys/opcoes"), token, ip).andExpect(status().isOk()));
        String credencial = aparelho.cadastrar(JsonPath.read(opcoes, "$.publicKey.challenge"), JsonPath.read(opcoes, "$.publicKey.user.id"));
        return postar("/api/auth/passkeys", "{\"nome\":\"Celular\",\"credencial\":" + credencial + "}", token, ip);
    }

    private ResultActions entrar(AutenticadorDeTeste aparelho, String ip) throws Exception {
        return postar("/api/auth/passkey/login", "{\"credencial\":" + aparelho.entrar(desafioDeLogin(ip)) + "}", null, ip);
    }

    private String desafioDeLogin(String ip) throws Exception {
        return JsonPath.read(corpo(postar("/api/auth/passkey/login/opcoes", "{}", null, ip).andExpect(status().isOk())),
                "$.publicKey.challenge");
    }

    private static String aceite(String convite, String credencial) {
        return "{\"convite\":\"" + convite + "\",\"nome\":\"Notebook\",\"credencial\":" + credencial + "}";
    }

    private ResultActions postar(String caminho, String json, String token, String ip) throws Exception {
        return chamar(post(caminho).contentType(MediaType.APPLICATION_JSON).content(json.getBytes(StandardCharsets.UTF_8)), token, ip);
    }

    private ResultActions chamar(MockHttpServletRequestBuilder pedido, String token, String ip) throws Exception {
        Api.doBff(pedido, ip);
        if (token != null) {
            pedido.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(pedido);
    }

    /** Motivo da tentativa mais recente deste IP (nulo = foi sucesso). */
    private String ultimoMotivo(String ip) {
        return jdbc.queryForObject("SELECT MOTIVO FROM " + schemas.log("TENTATIVA_LOGIN")
                + " WHERE IP_ORIGEM = ? ORDER BY ID DESC FETCH FIRST 1 ROW ONLY", String.class, ip);
    }

    /** Cada teste com o seu IP (faixa de documentacao, RFC 5737), para um nao bloquear o outro. */
    private static String ipNovo() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "203.0." + r.nextInt(256) + "." + (1 + r.nextInt(254));
    }

    private static String corpo(ResultActions resposta) throws Exception {
        return resposta.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
