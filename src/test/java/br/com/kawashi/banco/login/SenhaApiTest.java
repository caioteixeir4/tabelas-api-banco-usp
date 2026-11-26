package br.com.kawashi.banco.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import br.com.kawashi.banco.testes.Api;
import br.com.kawashi.banco.testes.CorreioDeTeste;
import br.com.kawashi.banco.testes.IntegracaoApi;
import br.com.kawashi.banco.testes.UsuarioDeTeste;

/**
 * O login por senha, de ponta a ponta pela API: convite com senha, entrar por e-mail, nome de
 * usuario e celular, recusa igual para tudo, bloqueio da conta, 2FA (ativar, codigo errado, codigo
 * repetido, computador de confianca), administrador obrigado a ter 2FA, esqueci/redefinir, trocar a
 * senha e confirmar operacao sensivel com a senha.
 */
@IntegracaoApi
class SenhaApiTest {

    private static final String SENHA = "Cavalo-Bateria-Grampo-42";
    private static final String OUTRA_SENHA = "Lanterna-Mochila-Tijolo-77";
    private static final Pattern LINK = Pattern.compile("#(kwc_[A-Za-z0-9_-]{43})");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioDeTeste usuarios;
    @Autowired
    private CorreioDeTeste correio;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SchemasDoLogin schemas;
    @Autowired
    private Totp totp;
    @Autowired
    private ParametrosDeSeguranca parametros;

    /** Cada teste vem de um IP proprio: as falhas de um nao bloqueiam o outro. */
    private final String ip = ipNovo();

    private record Conta(long usuarioId, String email, String token, UsuarioDeTeste.Criado criado) {
    }

    // ---- convite com senha ---------------------------------------------------------------------------

    @Test
    void conviteComSenhaCriaAContaEEntra() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo("OPERADOR");
        String r = corpo(postar("/api/auth/convite/senha", "{\"convite\":\"" + u.convite() + "\",\"senha\":\"" + SENHA + "\"}", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.escopo").value("COMPLETO")));
        assertThat(JsonPath.<List<String>>read(r, "$.codigosDeRecuperacao")).hasSize(parametros.atuais().codigosRecuperacao());
        String hash = jdbc.queryForObject("SELECT SENHA_HASH FROM " + schemas.login("USUARIO") + " WHERE ID = ?", String.class, u.usuarioId());
        assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$").doesNotContain(SENHA);
        // O convite e de uso unico.
        postar("/api/auth/convite/senha", "{\"convite\":\"" + u.convite() + "\",\"senha\":\"" + SENHA + "\"}", null)
                .andExpect(status().isUnauthorized());
        chamar(get("/api/auth/eu"), JsonPath.read(r, "$.token")).andExpect(status().isOk())
                .andExpect(jsonPath("$.conta.temSenha").value(true)).andExpect(jsonPath("$.conta.doisFatores").value(false))
                .andExpect(jsonPath("$.conta.precisaDe2fa").value(false));
    }

    @Test
    void senhaFracaERecusadaEOConviteContinuaValendo() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo("OPERADOR");
        String local = u.email().substring(0, u.email().indexOf('@'));
        for (String fraca : List.of("curta1", "1234567890", "senha12345", "aaaaaaaaaaaa", "x" + local + "!", "Meu-Usuario-2026",
                "teste-de-novo-99")) {
            postar("/api/auth/convite/senha", "{\"convite\":\"" + u.convite() + "\",\"senha\":\"" + fraca + "\"}", null)
                    .andExpect(status().isUnprocessableEntity());
        }
        postar("/api/auth/convite/senha", "{\"convite\":\"" + u.convite() + "\",\"senha\":\"" + SENHA + "\"}", null)
                .andExpect(status().isOk());
    }

    @Test
    void linkDeRedefinirNaoCadastraPasskey() throws Exception {
        Conta c = contaComSenha("OPERADOR");
        String link = esqueci(c.email());
        postar("/api/auth/convite/opcoes", "{\"convite\":\"" + link + "\"}", null).andExpect(status().isUnauthorized());
        postar("/api/auth/convite/senha", "{\"convite\":\"" + link + "\",\"senha\":\"" + OUTRA_SENHA + "\"}", null)
                .andExpect(status().isUnauthorized());
    }

    // ---- entrar ------------------------------------------------------------------------------------

    @Test
    void entraPeloEmailEPeloNomeDeUsuario() throws Exception {
        Conta c = contaComSenha("OPERADOR");
        entrar(c.email().toUpperCase(), SENHA).andExpect(status().isOk()).andExpect(jsonPath("$.escopo").value("COMPLETO"))
                .andExpect(jsonPath("$.dispositivo").doesNotExist());
        String apelido = "apelido." + ThreadLocalRandom.current().nextInt(1_000_000);
        chamar(put("/api/auth/identificadores").contentType(MediaType.APPLICATION_JSON).content("{\"nomeUsuario\":\"" + apelido.toUpperCase() + "\"}"),
                c.token()).andExpect(status().isOk()).andExpect(jsonPath("$.nomeUsuario").value(apelido));
        entrar(apelido, SENHA).andExpect(status().isOk());
        entrar(" " + apelido.toUpperCase() + " ", SENHA).andExpect(status().isOk());
    }

    @Test
    void recusaEAMesmaParaContaInexistenteSenhaErradaEContaSemSenha() throws Exception {
        Conta c = contaComSenha("OPERADOR");
        UsuarioDeTeste.Criado semSenha = usuarios.novo("OPERADOR");
        String a = corpo(entrar("ninguem" + ThreadLocalRandom.current().nextInt() + "@teste.kawashi.invalid", SENHA)
                .andExpect(status().isUnauthorized()));
        String b = corpo(entrar(c.email(), "Errada-Errada-Errada-1").andExpect(status().isUnauthorized()));
        String d = corpo(entrar(semSenha.email(), SENHA).andExpect(status().isUnauthorized()));
        assertThat(a).isEqualTo(b).isEqualTo(d);
        assertThat(motivos(c.usuarioId())).contains("SENHA_INVALIDA");
        assertThat(motivos(semSenha.usuarioId())).contains("CREDENCIAL_DESCONHECIDA");
    }

    @Test
    void erroDeSenhaRepetidoBloqueiaAContaMesmoDeIpsDiferentes() throws Exception {
        Conta c = contaComSenha("OPERADOR");
        int maximo = parametros.atuais().contaMaxFalhas();
        for (int i = 0; i < maximo; i++) {
            entrarDe(ipNovo(), c.email(), "Errada-Errada-Errada-" + i).andExpect(status().isUnauthorized());
        }
        // Agora nem a senha certa entra (e a resposta e a mesma de sempre).
        entrarDe(ipNovo(), c.email(), SENHA).andExpect(status().isUnauthorized());
        assertThat(motivos(c.usuarioId())).contains("CONTA_BLOQUEADA");
        assertThat(eventos(c.usuarioId())).contains("CONTA_BLOQUEADA");
        assertThat(correio.para(c.email())).anyMatch(m -> m.texto().contains("bloqueada"));
    }

    @Test
    void celularSoEntraComLoginPeloCelularLigadoE2fa() throws Exception {
        Conta c = contaComSenha("OPERADOR");
        String celular = "(11) 9" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));
        // Ligar o login pelo celular sem 2FA: recusado.
        identificadores(c.token(), "{\"celular\":\"" + celular + "\",\"loginPorCelular\":true}").andExpect(status().isUnprocessableEntity());
        identificadores(c.token(), "{\"celular\":\"" + celular + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.celular").value(org.hamcrest.Matchers.startsWith("+55 ")))
                .andExpect(jsonPath("$.loginPorCelular").value(false));
        // Celular cadastrado mas sem o login por ele: nao entra.
        entrar(celular, SENHA).andExpect(status().isUnauthorized());

        String segredo = ativar2fa(c);
        identificadores(c.token(), "{\"loginPorCelular\":true}").andExpect(status().isOk()).andExpect(jsonPath("$.loginPorCelular").value(true));
        String desafio = JsonPath.read(corpo(entrar("+55 " + celular, SENHA).andExpect(status().isOk())
                .andExpect(jsonPath("$.segundoFator").value(true))), "$.desafio");
        segundoFator(desafio, codigoNovo(c, segredo), false).andExpect(status().isOk()).andExpect(jsonPath("$.escopo").value("COMPLETO"));
        // Desligar o 2FA com o login pelo celular ligado: recusado.
        postar("/api/auth/2fa/desativar", "{\"codigo\":\"" + codigoNovo(c, segredo) + "\"}", c.token()).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void nomeDeUsuarioNaoPodeSerONomeNemSoNumerosNemRepetido() throws Exception {
        Conta c = contaComSenha("OPERADOR");
        // A pessoa dos testes se chama "Usuario de Teste".
        for (String ruim : List.of("123456789", "usuario_teste", "usuario.de.teste", "teste99", "ab", "com espaco", "acentuação")) {
            identificadores(c.token(), "{\"nomeUsuario\":\"" + ruim + "\"}").andExpect(status().isUnprocessableEntity());
        }
        String apelido = "kw-" + ThreadLocalRandom.current().nextInt(1_000_000);
        identificadores(c.token(), "{\"nomeUsuario\":\"" + apelido + "\"}").andExpect(status().isOk());
        Conta outra = contaComSenha("OPERADOR");
        identificadores(outra.token(), "{\"nomeUsuario\":\"" + apelido + "\"}").andExpect(status().isConflict());
        // Texto vazio remove.
        identificadores(c.token(), "{\"nomeUsuario\":\"\"}").andExpect(status().isOk()).andExpect(jsonPath("$.nomeUsuario").doesNotExist());
        assertThat(eventos(c.usuarioId())).contains("IDENTIFICADOR_ALTERADO");
    }

    // ---- 2FA ---------------------------------------------------------------------------------------

    @Test
    void com2faASenhaLevaAoCodigoECodigoErradoDaOutraChance() throws Exception {
        Conta c = contaComSenha("OPERADOR");
        String segredo = ativar2fa(c);
        assertThat(eventos(c.usuarioId())).contains("TOTP_ATIVADO");
        String r = corpo(entrar(c.email(), SENHA).andExpect(status().isOk()).andExpect(jsonPath("$.segundoFator").value(true))
                .andExpect(jsonPath("$.token").doesNotExist()));
        String desafio = JsonPath.read(r, "$.desafio");
        String errado = corpo(segundoFator(desafio, errado(c, segredo), false)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.segundoFator").value(true)));
        // O desafio antigo nao vale mais; o novo sim.
        segundoFator(desafio, codigoNovo(c, segredo), false).andExpect(status().isUnauthorized());
        segundoFator(JsonPath.read(errado, "$.desafio"), codigoNovo(c, segredo), false).andExpect(status().isOk())
                .andExpect(jsonPath("$.escopo").value("COMPLETO"));
    }

    @Test
    void oMesmoCodigoNaoValeDuasVezes() throws Exception {
        Conta c = contaComSenha("OPERADOR");
        String segredo = ativar2fa(c);
        String codigo = codigoNovo(c, segredo);
        segundoFator(desafioDoLogin(c), codigo, false).andExpect(status().isOk());
        segundoFator(desafioDoLogin(c), codigo, false).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void computadorDeConfiancaDispensaOCodigoSoParaEstaConta() throws Exception {
        Conta c = contaComSenha("OPERADOR");
        String segredo = ativar2fa(c);
        String r = corpo(segundoFator(desafioDoLogin(c), codigoNovo(c, segredo), true).andExpect(status().isOk()));
        String dispositivo = JsonPath.read(r, "$.dispositivo");
        assertThat(dispositivo).startsWith("kwd_");
        assertThat(eventos(c.usuarioId())).contains("DISPOSITIVO_CONFIADO");

        chamar(post("/api/auth/senha/login").header(DispositivosConfiaveis.CABECALHO, dispositivo).contentType(MediaType.APPLICATION_JSON)
                .content("{\"identificador\":\"" + c.email() + "\",\"senha\":\"" + SENHA + "\"}"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").exists());
        // Sem o cookie: pede o codigo de novo.
        entrar(c.email(), SENHA).andExpect(jsonPath("$.segundoFator").value(true));
        // O segredo de outra conta nao serve.
        Conta outra = contaComSenha("OPERADOR");
        ativar2fa(outra);
        chamar(post("/api/auth/senha/login").header(DispositivosConfiaveis.CABECALHO, dispositivo).contentType(MediaType.APPLICATION_JSON)
                .content("{\"identificador\":\"" + outra.email() + "\",\"senha\":\"" + SENHA + "\"}"), null)
                .andExpect(jsonPath("$.segundoFator").value(true));

        // Listar e revogar.
        String lista = corpo(chamar(get("/api/auth/dispositivos"), c.token()).andExpect(status().isOk()));
        long id = ((Number) JsonPath.read(lista, "$[0].id")).longValue();
        chamar(delete("/api/auth/dispositivos/" + id), c.token()).andExpect(status().isNoContent());
        chamar(delete("/api/auth/dispositivos/" + id), c.token()).andExpect(status().isNotFound());
        chamar(post("/api/auth/senha/login").header(DispositivosConfiaveis.CABECALHO, dispositivo).contentType(MediaType.APPLICATION_JSON)
                .content("{\"identificador\":\"" + c.email() + "\",\"senha\":\"" + SENHA + "\"}"), null)
                .andExpect(jsonPath("$.segundoFator").value(true));
    }

    @Test
    void administradorSemDoisFatoresSoConsegueAtivarO2fa() throws Exception {
        Conta admin = contaComSenha("ADMINISTRADOR");
        String r = corpo(entrar(admin.email(), SENHA).andExpect(status().isOk()).andExpect(jsonPath("$.escopo").value("CONFIGURAR_2FA")));
        String token = JsonPath.read(r, "$.token");
        chamar(get("/api/auth/empresas"), token).andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("Ative o 2FA"));
        chamar(get("/api/auth/eu"), token).andExpect(status().isOk()).andExpect(jsonPath("$.conta.precisaDe2fa").value(true));

        String segredo = JsonPath.read(corpo(chamar(post("/api/auth/2fa/iniciar"), token).andExpect(status().isOk())
                .andExpect(jsonPath("$.qr").isArray())), "$.segredo");
        String novo = JsonPath.read(corpo(postar("/api/auth/2fa/ativar", "{\"codigo\":\"" + codigoNovo(admin, segredo) + "\"}", token)
                .andExpect(status().isOk()).andExpect(jsonPath("$.escopo").value("COMPLETO"))), "$.token");
        chamar(get("/api/auth/empresas"), token).andExpect(status().isUnauthorized());
        chamar(get("/api/auth/empresas"), novo).andExpect(status().isOk());
        // Administrador nao desliga o 2FA.
        postar("/api/auth/2fa/desativar", "{\"codigo\":\"" + codigoNovo(admin, segredo) + "\"}", novo).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void quemNaoEAdministradorDesligaO2faComUmCodigoAtual() throws Exception {
        Conta c = contaComSenha("OPERADOR");
        String segredo = ativar2fa(c);
        postar("/api/auth/2fa/desativar", "{\"codigo\":\"" + errado(c, segredo) + "\"}", c.token()).andExpect(status().isUnprocessableEntity());
        postar("/api/auth/2fa/desativar", "{\"codigo\":\"" + codigoNovo(c, segredo) + "\"}", c.token()).andExpect(status().isNoContent());
        assertThat(eventos(c.usuarioId())).contains("TOTP_DESATIVADO");
        entrar(c.email(), SENHA).andExpect(jsonPath("$.token").exists());
    }

    // ---- esqueci / redefinir --------------------------------------------------------------------------

    @Test
    void esqueciMandaOLinkSoParaQuemTemSenhaERedefinirDerrubaTudo() throws Exception {
        Conta c = contaComSenha("OPERADOR");
        UsuarioDeTeste.Criado soPasskey = usuarios.novo("OPERADOR");
        postar("/api/auth/senha/esqueci", "{\"identificador\":\"" + soPasskey.email() + "\"}", null).andExpect(status().isNoContent());
        postar("/api/auth/senha/esqueci", "{\"identificador\":\"ninguem@teste.kawashi.invalid\"}", null).andExpect(status().isNoContent());
        assertThat(correio.para(soPasskey.email())).noneMatch(m -> "REDEFINIR_SENHA".equals(m.tipo()));

        String link = esqueci(c.email());
        postar("/api/auth/senha/redefinir", "{\"convite\":\"" + link + "\",\"senha\":\"curta\"}", null).andExpect(status().isUnprocessableEntity());
        postar("/api/auth/senha/redefinir", "{\"convite\":\"" + link + "\",\"senha\":\"" + OUTRA_SENHA + "\"}", null)
                .andExpect(status().isNoContent());
        chamar(get("/api/auth/eu"), c.token()).andExpect(status().isUnauthorized());
        entrar(c.email(), SENHA).andExpect(status().isUnauthorized());
        entrar(c.email(), OUTRA_SENHA).andExpect(status().isOk());
        postar("/api/auth/senha/redefinir", "{\"convite\":\"" + link + "\",\"senha\":\"" + SENHA + "\"}", null)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void redefinirComDoisFatoresPedeOCodigo() throws Exception {
        Conta c = contaComSenha("OPERADOR");
        String segredo = ativar2fa(c);
        String link = esqueci(c.email());
        postar("/api/auth/senha/redefinir", "{\"convite\":\"" + link + "\",\"senha\":\"" + OUTRA_SENHA + "\"}", null)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.precisaCodigo").value(true));
        postar("/api/auth/senha/redefinir", "{\"convite\":\"" + link + "\",\"senha\":\"" + OUTRA_SENHA + "\",\"codigo\":\"" + errado(c, segredo)
                + "\"}", null).andExpect(status().isUnprocessableEntity());
        postar("/api/auth/senha/redefinir", "{\"convite\":\"" + link + "\",\"senha\":\"" + OUTRA_SENHA + "\",\"codigo\":\""
                + codigoNovo(c, segredo) + "\"}", null).andExpect(status().isNoContent());
    }

    // ---- trocar a senha e confirmar com ela ------------------------------------------------------------

    @Test
    void trocarASenhaPedeAAtualEDerrubaAsOutrasSessoes() throws Exception {
        Conta c = contaComSenha("OPERADOR");
        String outraSessao = JsonPath.read(corpo(entrar(c.email(), SENHA).andExpect(status().isOk())), "$.token");
        postar("/api/auth/senha", "{\"atual\":\"Errada-Errada-Errada-1\",\"nova\":\"" + OUTRA_SENHA + "\"}", c.token())
                .andExpect(status().isUnprocessableEntity());
        postar("/api/auth/senha", "{\"atual\":\"" + SENHA + "\",\"nova\":\"" + SENHA + "\"}", c.token()).andExpect(status().isUnprocessableEntity());
        postar("/api/auth/senha", "{\"atual\":\"" + SENHA + "\",\"nova\":\"" + OUTRA_SENHA + "\"}", c.token()).andExpect(status().isNoContent());
        chamar(get("/api/auth/eu"), c.token()).andExpect(status().isOk());
        chamar(get("/api/auth/eu"), outraSessao).andExpect(status().isUnauthorized());
        assertThat(eventos(c.usuarioId())).contains("SENHA_ALTERADA");
        assertThat(correio.para(c.email())).anyMatch(m -> m.texto().contains("a senha foi trocada"));
    }

    @Test
    void operacaoSensivelSeConfirmaComASenha() throws Exception {
        Conta c = contaComSenha("OPERADOR");
        String antigo = usuarios.tokenSemConfirmacao(c.criado());
        chamar(post("/api/auth/codigos-de-recuperacao"), antigo).andExpect(status().isForbidden()).andExpect(jsonPath("$.confirmar").value(true));
        postar("/api/auth/confirmar/senha", "{\"senha\":\"Errada-Errada-Errada-1\"}", antigo).andExpect(status().isUnprocessableEntity());
        postar("/api/auth/confirmar/senha", "{\"senha\":\"" + SENHA + "\"}", antigo).andExpect(status().isNoContent());
        chamar(post("/api/auth/codigos-de-recuperacao"), antigo).andExpect(status().isOk());
        assertThat(eventos(c.usuarioId())).contains("SENHA_CONFIRMADA");
    }

    // ---- apoio -----------------------------------------------------------------------------------------

    private Conta contaComSenha(String perfil) throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo(perfil);
        String r = corpo(postar("/api/auth/convite/senha", "{\"convite\":\"" + u.convite() + "\",\"senha\":\"" + SENHA + "\"}", null)
                .andExpect(status().isOk()));
        return new Conta(u.usuarioId(), u.email(), JsonPath.read(r, "$.token"), u);
    }

    /** Liga o 2FA da conta (que nao e administradora) e devolve o segredo do app. */
    private String ativar2fa(Conta c) throws Exception {
        String segredo = JsonPath.read(corpo(chamar(post("/api/auth/2fa/iniciar"), c.token()).andExpect(status().isOk())), "$.segredo");
        postar("/api/auth/2fa/ativar", "{\"codigo\":\"" + errado(c, segredo) + "\"}", c.token()).andExpect(status().isUnprocessableEntity());
        postar("/api/auth/2fa/ativar", "{\"codigo\":\"" + codigoNovo(c, segredo) + "\"}", c.token()).andExpect(status().isNoContent());
        return segredo;
    }

    /**
     * O codigo atual do app. O teste usa varios codigos dentro dos mesmos 30 segundos, e um codigo
     * usado nao vale de novo: aqui o "ultimo passo usado" volta a zero antes (so nos testes).
     */
    private String codigoNovo(Conta c, String segredo) {
        jdbc.update("UPDATE " + schemas.login("USUARIO") + " SET TOTP_ULTIMO_PASSO = NULL WHERE ID = ?", c.usuarioId());
        return totp.codigoDoPasso(segredo, passoAtual());
    }

    /** Um codigo que certamente nao bate (nenhum dos passos aceitos). */
    private String errado(Conta c, String segredo) {
        long p = passoAtual();
        List<String> validos = List.of(totp.codigoDoPasso(segredo, p - 1), totp.codigoDoPasso(segredo, p), totp.codigoDoPasso(segredo, p + 1));
        for (int i = 0; ; i++) {
            String candidato = String.format("%06d", i);
            if (!validos.contains(candidato)) {
                return candidato;
            }
        }
    }

    private long passoAtual() {
        return jdbc.queryForObject("SELECT ROUND((CAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) AS DATE) - DATE '1970-01-01') * 86400) FROM DUAL",
                Long.class) / 30;
    }

    private String desafioDoLogin(Conta c) throws Exception {
        return JsonPath.read(corpo(entrar(c.email(), SENHA).andExpect(jsonPath("$.segundoFator").value(true))), "$.desafio");
    }

    private String esqueci(String email) throws Exception {
        postar("/api/auth/senha/esqueci", "{\"identificador\":\"" + email + "\"}", null).andExpect(status().isNoContent());
        var dele = correio.para(email).stream().filter(m -> "REDEFINIR_SENHA".equals(m.tipo())).toList();
        assertThat(dele).isNotEmpty();
        Matcher m = LINK.matcher(dele.get(dele.size() - 1).texto());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private ResultActions entrar(String identificador, String senha) throws Exception {
        return entrarDe(ip, identificador, senha);
    }

    private ResultActions entrarDe(String deIp, String identificador, String senha) throws Exception {
        MockHttpServletRequestBuilder pedido = post("/api/auth/senha/login").contentType(MediaType.APPLICATION_JSON)
                .content(("{\"identificador\":\"" + identificador + "\",\"senha\":\"" + senha + "\"}").getBytes(StandardCharsets.UTF_8));
        Api.doBff(pedido, deIp);
        return mvc.perform(pedido);
    }

    private ResultActions segundoFator(String desafio, String codigo, boolean confiar) throws Exception {
        return postar("/api/auth/senha/segundo-fator", "{\"desafio\":\"" + desafio + "\",\"codigo\":\"" + codigo + "\",\"confiar\":" + confiar
                + ",\"nomeDoComputador\":\"Notebook de teste\"}", null);
    }

    private ResultActions identificadores(String token, String json) throws Exception {
        return chamar(put("/api/auth/identificadores").contentType(MediaType.APPLICATION_JSON).content(json.getBytes(StandardCharsets.UTF_8)), token);
    }

    private ResultActions postar(String caminho, String json, String token) throws Exception {
        return chamar(post(caminho).contentType(MediaType.APPLICATION_JSON).content(json.getBytes(StandardCharsets.UTF_8)), token);
    }

    private ResultActions chamar(MockHttpServletRequestBuilder pedido, String token) throws Exception {
        Api.doBff(pedido, ip);
        if (token != null) {
            pedido.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(pedido);
    }

    private List<String> motivos(long usuarioId) {
        return jdbc.queryForList("SELECT MOTIVO FROM " + schemas.log("TENTATIVA_LOGIN") + " WHERE USUARIO_ID = ? AND MOTIVO IS NOT NULL",
                String.class, usuarioId);
    }

    private List<String> eventos(long usuarioId) {
        return jdbc.queryForList("SELECT EVENTO FROM " + schemas.log("LOG_SEGURANCA") + " WHERE USUARIO_ID = ?", String.class, usuarioId);
    }

    private static String corpo(ResultActions r) throws Exception {
        return Api.corpo(r);
    }

    private static String ipNovo() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "192.0." + (2 + r.nextInt(200)) + "." + (1 + r.nextInt(254));
    }
}
