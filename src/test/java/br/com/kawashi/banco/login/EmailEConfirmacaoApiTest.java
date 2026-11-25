package br.com.kawashi.banco.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import br.com.kawashi.banco.autorizacao.Autorizacao;
import br.com.kawashi.banco.testes.Api;
import br.com.kawashi.banco.testes.AutenticadorDeTeste;
import br.com.kawashi.banco.testes.CorreioDeTeste;
import br.com.kawashi.banco.testes.IntegracaoApi;
import br.com.kawashi.banco.testes.UsuarioDeTeste;

/**
 * Os achados 1, 4, 5, 8 e 11 da revisao de 23/11/2026:
 * <ul>
 *   <li>o convite vai para o e-mail da pessoa (nunca para quem pediu), e usa-lo confirma o e-mail;</li>
 *   <li>criar usuario com e-mail que ja tem conta responde igual, e quem fica sabendo e o dono;</li>
 *   <li>operacao sensivel sem a passkey recente: 403 "Confirme com sua passkey"; confirmar com uma
 *       passkey da propria conta libera; com a de outra pessoa, 422;</li>
 *   <li>as operacoes da conta geram aviso por e-mail;</li>
 *   <li>falhas de enderecos IPv6 do mesmo bloco /64 bloqueiam o bloco inteiro.</li>
 * </ul>
 */
@IntegracaoApi
class EmailEConfirmacaoApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioDeTeste usuarios;
    @Autowired
    private CorreioDeTeste correio;
    @Autowired
    private Autorizacao autorizacao;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SchemasDoLogin schemas;

    private record Conta(long usuarioId, String email, AutenticadorDeTeste aparelho, String token) {
    }

    // ---- convite por e-mail e resposta neutra -----------------------------------------------------

    @Test
    void conviteVaiParaOEmailDaPessoaEUsaLoConfirmaOEmail() throws Exception {
        String ip = ipNovo();
        UsuarioDeTeste.Criado admin = usuarios.novo();
        Api api = usuarios.api(mvc, admin);
        String email = "pessoa" + ThreadLocalRandom.current().nextInt(1_000_000_000) + "@teste.kawashi.invalid";
        String resposta = Api.corpo(api.post("/api/usuarios", "{\"pessoaId\":" + api.pessoa() + ",\"email\":\"" + email + "\",\"perfilId\":"
                + autorizacao.perfilPorNome(admin.empresaId(), "CONSULTA").orElseThrow() + "}").andExpect(status().isAccepted()));
        assertThat(resposta).doesNotContain("kwc_").contains("p***@teste.kawashi.invalid");
        String convite = correio.ultimoConvite(email).orElseThrow();
        long usuarioId = jdbc.queryForObject("SELECT USUARIO_ID FROM " + schemas.login("CONVITE") + " WHERE TOKEN_HASH = ?",
                Long.class, Aleatorio.sha256(convite));
        assertThat(emailConfirmado(usuarioId)).isFalse();

        aceitarConvite(convite, new AutenticadorDeTeste(), ip);
        assertThat(emailConfirmado(usuarioId)).as("convite que chegou por e-mail confirma o e-mail").isTrue();

        // Convite entregue pela equipe (Vault), e nao por e-mail: usar nao confirma nada.
        UsuarioDeTeste.Criado pelaEquipe = usuarios.novo();
        aceitarConvite(pelaEquipe.convite(), new AutenticadorDeTeste(), ip);
        assertThat(emailConfirmado(pelaEquipe.usuarioId())).isFalse();
    }

    @Test
    void emailQueJaTemContaRespondeIgualEQuemSabeEODono() throws Exception {
        UsuarioDeTeste.Criado deOutraEmpresa = usuarios.novo();
        UsuarioDeTeste.Criado admin = usuarios.novo();
        Api api = usuarios.api(mvc, admin);
        long consulta = autorizacao.perfilPorNome(admin.empresaId(), "CONSULTA").orElseThrow();
        long pessoa = api.pessoa();
        String jaExiste = Api.corpo(api.post("/api/usuarios", "{\"pessoaId\":" + pessoa + ",\"email\":\"" + deOutraEmpresa.email()
                + "\",\"perfilId\":" + consulta + "}").andExpect(status().isAccepted()));
        String novo = Api.corpo(api.post("/api/usuarios", "{\"pessoaId\":" + api.pessoa() + ",\"email\":\"u" + deOutraEmpresa.email()
                + "\",\"perfilId\":" + consulta + "}").andExpect(status().isAccepted()));
        // A mesma forma de resposta: so "enviadoPara", mascarado.
        assertThat(JsonPath.<java.util.Map<String, Object>>read(jaExiste, "$").keySet())
                .isEqualTo(JsonPath.<java.util.Map<String, Object>>read(novo, "$").keySet()).containsExactly("enviadoPara");
        // Nada foi criado para a pessoa; o dono do e-mail recebeu o aviso (com o nome da empresa).
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.login("USUARIO") + " WHERE PESSOA_ID = ?", Long.class, pessoa))
                .isZero();
        assertThat(correio.para(deOutraEmpresa.email())).anyMatch(m -> "JA_TEM_CONTA".equals(m.tipo()));
        assertThat(correio.ultimoConvite(deOutraEmpresa.email())).isEmpty();
    }

    // ---- confirmar com a passkey -----------------------------------------------------------------

    @Test
    void operacaoSensivelSemPasskeyRecentePedeConfirmacao() throws Exception {
        String ip = ipNovo();
        Conta c = contaComPasskey(ip);
        String antigo = usuarios.tokenSemConfirmacao(new UsuarioDeTeste.Criado(c.usuarioId(), 0, c.email(), null, empresaDe(c), null));

        chamar(post("/api/auth/codigos-de-recuperacao"), antigo, ip).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Confirme com sua passkey")).andExpect(jsonPath("$.confirmar").value(true));
        chamar(post("/api/auth/passkeys/opcoes"), antigo, ip).andExpect(status().isForbidden());
        chamar(post("/api/usuarios/" + c.usuarioId() + "/convite"), antigo, ip).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.confirmar").value(true));
        // Ler continua livre.
        chamar(get("/api/auth/passkeys"), antigo, ip).andExpect(status().isOk());

        // Confirmar com uma passkey de OUTRA conta nao vale (422, e a sessao continua).
        Conta outra = contaComPasskey(ip);
        String opcoes = Api.corpo(chamar(post("/api/auth/confirmar/opcoes"), antigo, ip).andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey.allowCredentials[0].id").value(c.aparelho().credencialIdB64())));
        postar("/api/auth/confirmar", "{\"credencial\":" + outra.aparelho().entrar(JsonPath.read(opcoes, "$.publicKey.challenge")) + "}",
                antigo, ip).andExpect(status().isUnprocessableContent());
        chamar(get("/api/auth/eu"), antigo, ip).andExpect(status().isOk());

        // Com a propria passkey: confirmada, e a operacao passa.
        opcoes = Api.corpo(chamar(post("/api/auth/confirmar/opcoes"), antigo, ip).andExpect(status().isOk()));
        postar("/api/auth/confirmar", "{\"credencial\":" + c.aparelho().entrar(JsonPath.read(opcoes, "$.publicKey.challenge")) + "}",
                antigo, ip).andExpect(status().isNoContent());
        chamar(post("/api/auth/codigos-de-recuperacao"), antigo, ip).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.log("LOG_SEGURANCA")
                + " WHERE EVENTO = 'PASSKEY_CONFIRMADA' AND USUARIO_ID = ?", Long.class, c.usuarioId())).isEqualTo(1L);
        // E o aviso dos codigos novos chegou ao dono da conta.
        assertThat(correio.para(c.email())).anyMatch(m -> "AVISO".equals(m.tipo()) && m.texto().contains("códigos de recuperação novos"));
    }

    // ---- IPv6 por bloco /64 ---------------------------------------------------------------------

    @Test
    void falhasDeEnderecosDoMesmoBlocoIpv6BloqueiamOBlocoInteiro() throws Exception {
        int r = ThreadLocalRandom.current().nextInt(1, 0xffff);
        String bloco = "2001:db8:" + Integer.toHexString(r) + ":7::";
        for (int i = 1; i <= 10; i++) {
            // Cada tentativa de um endereco diferente do mesmo /64 (como faria quem quer escapar do bloqueio).
            postar("/api/auth/passkey/login", "{\"credencial\":{\"id\":\"x\"}}", null, bloco + Integer.toHexString(i))
                    .andExpect(status().isUnauthorized());
        }
        postar("/api/auth/passkey/login/opcoes", "{}", null, bloco + "abcd").andExpect(status().isTooManyRequests());
        postar("/api/auth/passkey/login/opcoes", "{}", null, "2001:db8:" + Integer.toHexString(r) + ":8::1").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.log("TENTATIVA_LOGIN") + " WHERE IP_GRUPO = ?", Long.class,
                "2001:db8:" + Integer.toHexString(r) + ":7::/64")).isEqualTo(10L);
    }

    // ---- apoio ------------------------------------------------------------------------------------

    private Conta contaComPasskey(String ip) throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novo();
        AutenticadorDeTeste aparelho = new AutenticadorDeTeste();
        String token = aceitarConvite(u.convite(), aparelho, ip);
        return new Conta(u.usuarioId(), u.email(), aparelho, token);
    }

    private String aceitarConvite(String convite, AutenticadorDeTeste aparelho, String ip) throws Exception {
        String opcoes = Api.corpo(postar("/api/auth/convite/opcoes", "{\"convite\":\"" + convite + "\"}", null, ip).andExpect(status().isOk()));
        String credencial = aparelho.cadastrar(JsonPath.read(opcoes, "$.publicKey.challenge"), JsonPath.read(opcoes, "$.publicKey.user.id"));
        String resposta = Api.corpo(postar("/api/auth/convite", "{\"convite\":\"" + convite + "\",\"nome\":\"Notebook\",\"credencial\":"
                + credencial + "}", null, ip).andExpect(status().isOk()));
        return JsonPath.read(resposta, "$.token");
    }

    private long empresaDe(Conta c) {
        return autorizacao.empresaUnica(c.usuarioId()).orElseThrow();
    }

    private boolean emailConfirmado(long usuarioId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.login("USUARIO") + " WHERE ID = ? AND EMAIL_CONFIRMADO_EM IS NOT NULL",
                Long.class, usuarioId) == 1L;
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

    private static String ipNovo() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "203.0." + r.nextInt(256) + "." + (1 + r.nextInt(254));
    }
}
