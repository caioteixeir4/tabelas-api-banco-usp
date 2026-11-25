package br.com.kawashi.banco.autorizacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import br.com.kawashi.banco.login.SchemasDoLogin;
import br.com.kawashi.banco.testes.Api;
import br.com.kawashi.banco.testes.CorreioDeTeste;
import br.com.kawashi.banco.testes.IntegracaoApi;
import br.com.kawashi.banco.testes.UsuarioDeTeste;

/**
 * Dar acesso a quem ja tem conta em outra empresa: o administrador convida, a propria pessoa aceita.
 * Fica registrado, sem poder mudar depois, quem convidou, quando, de qual IP, para qual empresa e
 * cargo, e quem respondeu; o vinculo criado aponta para o convite.
 */
@IntegracaoApi
class ConviteDeVinculoApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioDeTeste usuarios;
    @Autowired
    private Autorizacao autorizacao;
    @Autowired
    private CorreioDeTeste correio;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SchemasDoLogin schemas;

    private long perfil(long empresaId, String nome) {
        return autorizacao.perfilPorNome(empresaId, nome).orElseThrow();
    }

    private String convidar(Api admin, String email, long perfilId) throws Exception {
        return Api.corpo(admin.post("/api/usuarios/convites-de-vinculo", "{\"email\":\"" + email + "\",\"perfilId\":" + perfilId + "}")
                .andExpect(status().isAccepted()));
    }

    @Test
    void convidadoAceitaEQuemConvidouFicaRegistradoComHoraIpEEmpresa() throws Exception {
        UsuarioDeTeste.Criado contador = usuarios.novo();           // tem conta na empresa A
        UsuarioDeTeste.Criado adminB = usuarios.novo();             // administrador da empresa B
        Api b = usuarios.api(mvc, adminB);
        long operadorB = perfil(adminB.empresaId(), "OPERADOR");

        String resposta = convidar(b, contador.email(), operadorB);
        assertThat(JsonPath.<Map<String, Object>>read(resposta, "$").keySet()).containsExactly("enviadoPara");
        assertThat(correio.para(contador.email())).anyMatch(m -> "CONVITE_VINCULO".equals(m.tipo()));

        // O convite guarda quem, quando, de onde, para qual empresa e cargo; ainda sem acesso nenhum.
        Map<String, Object> c = jdbc.queryForMap("SELECT ID, CONVIDADO_POR, CONVIDADO_POR_USUARIO_ID, IP_ORIGEM, CRIADO_EM, SITUACAO FROM "
                + schemas.login("CONVITE_VINCULO") + " WHERE USUARIO_ID = ? AND EMPRESA_ID = ?", contador.usuarioId(), adminB.empresaId());
        long conviteId = ((Number) c.get("ID")).longValue();
        assertThat(c.get("CONVIDADO_POR")).isEqualTo("USUARIO:" + adminB.usuarioId());
        assertThat(((Number) c.get("CONVIDADO_POR_USUARIO_ID")).longValue()).isEqualTo(adminB.usuarioId());
        assertThat(c.get("IP_ORIGEM")).isEqualTo(Api.IP_DE_TESTE);
        assertThat(c.get("CRIADO_EM")).isNotNull();
        assertThat(c.get("SITUACAO")).isEqualTo("PENDENTE");
        assertThat(vinculoAtivo(contador.usuarioId(), adminB.empresaId())).isFalse();

        // O convidado ve o convite, com a empresa, o cargo e quem chamou; e aceita.
        Api dele = usuarios.api(mvc, contador);
        dele.get("/api/auth/convites-de-vinculo").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(conviteId)).andExpect(jsonPath("$[0].perfil").value("OPERADOR"))
                .andExpect(jsonPath("$[0].convidadoPor").isNotEmpty());
        dele.post("/api/auth/convites-de-vinculo/" + conviteId + "/aceitar").andExpect(status().isNoContent());
        dele.post("/api/auth/convites-de-vinculo/" + conviteId + "/aceitar").andExpect(status().isConflict());

        // Agora tem acesso, e o vinculo aponta para o convite: de qualquer acesso se chega em quem convidou.
        assertThat(vinculoAtivo(contador.usuarioId(), adminB.empresaId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT c.CONVIDADO_POR || '|' || c.IP_ORIGEM FROM " + schemas.login("USUARIO_EMPRESA") + " ue JOIN "
                + schemas.login("CONVITE_VINCULO") + " c ON c.ID = ue.CONVITE_VINCULO_ID WHERE ue.USUARIO_ID = ? AND ue.EMPRESA_ID = ?",
                String.class, contador.usuarioId(), adminB.empresaId())).isEqualTo("USUARIO:" + adminB.usuarioId() + "|" + Api.IP_DE_TESTE);
        dele.post("/api/auth/empresa", "{\"empresaId\":" + adminB.empresaId() + "}").andExpect(status().isOk());

        // Os logs encadeados: o convite e o aceite (LOG_PERMISSAO, por quem fez) e no LOG_SEGURANCA, com o IP.
        List<String> permissao = jdbc.queryForList("SELECT EVENTO || '|' || FEITO_POR FROM " + schemas.log("LOG_PERMISSAO")
                + " WHERE EMPRESA_ID = ? AND USUARIO_ID = ? ORDER BY ORDEM", String.class, adminB.empresaId(), contador.usuarioId());
        assertThat(permissao).containsExactly("VINCULO_CONVIDADO|USUARIO:" + adminB.usuarioId(),
                "VINCULO_CRIADO|USUARIO:" + adminB.usuarioId(), "CONVITE_ACEITO|USUARIO:" + contador.usuarioId());
        assertThat(jdbc.queryForList("SELECT EVENTO || '|' || FEITO_POR || '|' || IP_ORIGEM FROM " + schemas.log("LOG_SEGURANCA")
                + " WHERE USUARIO_ID = ? AND EVENTO LIKE 'VINCULO_CONVI%' ORDER BY ORDEM", String.class, contador.usuarioId()))
                .containsExactly("VINCULO_CONVIDADO|USUARIO:" + adminB.usuarioId() + "|" + Api.IP_DE_TESTE,
                        "VINCULO_CONVITE_RESPONDIDO|USUARIO:" + contador.usuarioId() + "|" + Api.IP_DE_TESTE);

        // O administrador ve o convite respondido, com quem convidou e de onde.
        b.get("/api/usuarios/convites-de-vinculo").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].situacao").value("ACEITO")).andExpect(jsonPath("$[0].ipOrigem").value(Api.IP_DE_TESTE))
                .andExpect(jsonPath("$[0].convidadoPor").value("USUARIO:" + adminB.usuarioId()));
    }

    @Test
    void soOConvidadoResponde_recusaECancelamentoNaoDaoAcesso() throws Exception {
        UsuarioDeTeste.Criado convidado = usuarios.novo();
        UsuarioDeTeste.Criado intruso = usuarios.novo();
        UsuarioDeTeste.Criado adminB = usuarios.novo();
        Api b = usuarios.api(mvc, adminB);
        convidar(b, convidado.email(), perfil(adminB.empresaId(), "CONSULTA"));
        long id = conviteDe(convidado.usuarioId(), adminB.empresaId());

        usuarios.api(mvc, intruso).post("/api/auth/convites-de-vinculo/" + id + "/aceitar").andExpect(status().isNotFound());
        usuarios.api(mvc, convidado).post("/api/auth/convites-de-vinculo/" + id + "/recusar").andExpect(status().isNoContent());
        assertThat(vinculoAtivo(convidado.usuarioId(), adminB.empresaId())).isFalse();

        // Convidado de novo, e o administrador desiste: o convite cancelado nao serve mais.
        convidar(b, convidado.email(), perfil(adminB.empresaId(), "CONSULTA"));
        long segundo = conviteDe(convidado.usuarioId(), adminB.empresaId());
        b.post("/api/usuarios/convites-de-vinculo/" + segundo + "/cancelar").andExpect(status().isNoContent());
        usuarios.api(mvc, convidado).post("/api/auth/convites-de-vinculo/" + segundo + "/aceitar").andExpect(status().isConflict());
        assertThat(vinculoAtivo(convidado.usuarioId(), adminB.empresaId())).isFalse();
        b.get("/api/usuarios/convites-de-vinculo").andExpect(jsonPath("$[0].situacao").value("CANCELADO"))
                .andExpect(jsonPath("$[1].situacao").value("RECUSADO"))
                .andExpect(jsonPath("$[1].respondidoPor").value("USUARIO:" + convidado.usuarioId()));
    }

    @Test
    void respostaSempreIgualEPedeAPasskeyRecente() throws Exception {
        UsuarioDeTeste.Criado adminB = usuarios.novo();
        Api b = usuarios.api(mvc, adminB);
        long consulta = perfil(adminB.empresaId(), "CONSULTA");
        // E-mail sem conta, o proprio administrador, e quem ja tem acesso: mesma forma de resposta, nenhum convite.
        String semConta = convidar(b, "ninguem" + System.nanoTime() + "@teste.kawashi.invalid", consulta);
        String siMesmo = convidar(b, adminB.email(), consulta);
        UsuarioDeTeste.Criado jaTem = usuarios.naEmpresa(adminB.empresaId(), "CONSULTA");
        String jaTemAcesso = convidar(b, jaTem.email(), consulta);
        for (String r : List.of(semConta, siMesmo, jaTemAcesso)) {
            assertThat(JsonPath.<Map<String, Object>>read(r, "$").keySet()).containsExactly("enviadoPara");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.login("CONVITE_VINCULO") + " WHERE EMPRESA_ID = ?", Long.class,
                adminB.empresaId())).isZero();

        // Sem a passkey usada ha pouco, convidar pede a confirmacao.
        new Api(mvc, usuarios.tokenSemConfirmacao(adminB)).post("/api/usuarios/convites-de-vinculo",
                "{\"email\":\"x@teste.kawashi.invalid\",\"perfilId\":" + consulta + "}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.confirmar").value(true));
    }

    private boolean vinculoAtivo(long usuarioId, long empresaId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.login("USUARIO_EMPRESA") + " WHERE USUARIO_ID = ? AND EMPRESA_ID = ? AND ATIVO",
                Long.class, usuarioId, empresaId) == 1L;
    }

    private long conviteDe(long usuarioId, long empresaId) {
        return jdbc.queryForObject("SELECT ID FROM " + schemas.login("CONVITE_VINCULO") + " WHERE USUARIO_ID = ? AND EMPRESA_ID = ?"
                + " AND SITUACAO = 'PENDENTE'", Long.class, usuarioId, empresaId);
    }
}
