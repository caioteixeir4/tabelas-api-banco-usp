package br.com.kawashi.banco.autorizacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import br.com.kawashi.banco.compartilhado.erros.AcessoNegadoException;
import br.com.kawashi.banco.compartilhado.erros.RecursoNaoEncontradoException;
import br.com.kawashi.banco.empresa.EmpresaService;
import br.com.kawashi.banco.login.Convites;
import br.com.kawashi.banco.login.SchemasDoLogin;
import br.com.kawashi.banco.testes.Api;
import br.com.kawashi.banco.testes.IntegracaoApi;
import br.com.kawashi.banco.testes.UsuarioDeTeste;

/**
 * As correcoes da revisao de seguranca de 23/11/2026, cada uma com o caso que a revisao descreveu:
 * convite so para conta que e so desta empresa; convites antigos vencem; empresa inativada derruba
 * as sessoes; revelar documento exige ler a pessoa; falha fechada fora de um pedido; e o caminho fora
 * da forma canonica nao desvia dos filtros.
 */
@IntegracaoApi
class RevisaoDeSegurancaApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioDeTeste usuarios;
    @Autowired
    private Autorizacao autorizacao;
    @Autowired
    private Convites convites;
    @Autowired
    private EmpresaService empresas;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SchemasDoLogin schemas;
    @Autowired
    private br.com.kawashi.banco.testes.CorreioDeTeste correio;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager jpa;

    @Test
    void conviteNovoSoParaContaQueESoDestaEmpresa() throws Exception {
        UsuarioDeTeste.Criado admin = usuarios.novo();
        Api api = usuarios.api(mvc, admin);
        UsuarioDeTeste.Criado soDaqui = usuarios.naEmpresa(admin.empresaId(), "OPERADOR");
        UsuarioDeTeste.Criado tambemDeOutra = usuarios.naEmpresa(admin.empresaId(), "OPERADOR");
        // O caso da revisao: a conta tambem e ADMINISTRADOR de outra empresa.
        UsuarioDeTeste.Criado outra = usuarios.novo();
        autorizacao.vincular(tambemDeOutra.usuarioId(), outra.empresaId(),
                autorizacao.perfilPorNome(outra.empresaId(), "ADMINISTRADOR").orElseThrow(), UsuarioDeTeste.QUEM);

        api.post("/api/usuarios/" + soDaqui.usuarioId() + "/convite").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.enviadoPara").exists());
        api.post("/api/usuarios/" + tambemDeOutra.usuarioId() + "/convite").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("equipe da plataforma")));

        // Vinculo desativado aqui: nem existe para o convite.
        api.post("/api/usuarios/" + soDaqui.usuarioId() + "/desativar").andExpect(status().is2xxSuccessful());
        api.post("/api/usuarios/" + soDaqui.usuarioId() + "/convite").andExpect(status().isNotFound());
    }

    @Test
    void conviteNovoFazOsPendentesVencerem() throws Exception {
        UsuarioDeTeste.Criado admin = usuarios.novo();
        Api api = usuarios.api(mvc, admin);
        UsuarioDeTeste.Criado u = usuarios.naEmpresa(admin.empresaId(), "OPERADOR");
        api.post("/api/usuarios/" + u.usuarioId() + "/convite").andExpect(status().isAccepted());
        String primeiro = correio.ultimoConvite(u.email()).orElseThrow();
        Thread.sleep(5);  // o vencimento so atinge convite criado ANTES (hora do banco, em microssegundos)
        api.post("/api/usuarios/" + u.usuarioId() + "/convite").andExpect(status().isAccepted());
        String segundo = correio.ultimoConvite(u.email()).orElseThrow();
        assertThat(segundo).isNotEqualTo(primeiro);
        assertThat(convites.valido(primeiro)).as("convite anterior").isEmpty();
        assertThat(convites.valido(u.convite())).as("convite de cadastro").isEmpty();
        assertThat(convites.valido(segundo)).as("convite mais recente").isPresent();
    }

    @Test
    void empresaInativadaDerrubaAsSessoesDela() throws Exception {
        UsuarioDeTeste.Criado admin = usuarios.novo();
        Api api = usuarios.api(mvc, admin);
        api.get("/api/auth/eu").andExpect(status().isOk());

        empresas.inativar(admin.empresaId());
        jpa.flush();  // a consulta do token e JDBC: precisa ver a empresa inativa (em producao, o commit faz isso)

        api.get("/api/auth/eu").andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.sessao("TOKEN")
                + " WHERE USUARIO_ID = ? AND MOTIVO_REVOGACAO = 'EMPRESA_INATIVADA'", Integer.class, admin.usuarioId())).isEqualTo(1);
        // E mesmo um token emitido depois (sem revogar) nao vale numa empresa inativa.
        usuarios.api(mvc, admin).get("/api/auth/eu").andExpect(status().isUnauthorized());
    }

    @Test
    void revelarDocumentoExigeTambemLerAPessoa() throws Exception {
        UsuarioDeTeste.Criado admin = usuarios.novo();
        long pessoa = usuarios.api(mvc, admin).pessoa();
        autorizacao.criarPerfil(admin.empresaId(), "SO_REVELAR", "so revelar", List.of(Permissoes.DADO_PESSOAL_REVELAR), UsuarioDeTeste.QUEM);
        autorizacao.criarPerfil(admin.empresaId(), "LER_E_REVELAR", "ler e revelar",
                List.of(Permissoes.PESSOA_LER, Permissoes.DADO_PESSOAL_REVELAR), UsuarioDeTeste.QUEM);

        Api soRevelar = usuarios.api(mvc, usuarios.naEmpresa(admin.empresaId(), "SO_REVELAR"));
        soRevelar.get("/api/pessoas/" + pessoa + "/documento").andExpect(status().isForbidden());
        Api lerERevelar = usuarios.api(mvc, usuarios.naEmpresa(admin.empresaId(), "LER_E_REVELAR"));
        lerERevelar.get("/api/pessoas/" + pessoa + "/documento").andExpect(status().isOk()).andExpect(jsonPath("$.documento").exists());
    }

    /**
     * Numa thread nova (o caso de uma tarefa agendada): nao ha pedido HTTP nenhum. A thread do teste
     * nao serve, porque o Spring ja poe nela um pedido falso.
     */
    @Test
    void foraDeUmPedidoTudoENegadoMenosOQueOCodigoLiberaExplicitamente() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Throwable> falha = new java.util.concurrent.atomic.AtomicReference<>();
        Thread tarefa = new Thread(() -> {
            try {
                assertThat(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()).as("sem pedido").isNull();
                assertThatThrownBy(() -> Autorizacao.conferirEmpresa(1L, "Empresa", 1L)).isInstanceOf(RecursoNaoEncontradoException.class);
                assertThatThrownBy(() -> autorizacao.exigir(Permissoes.PESSOA_LER)).isInstanceOf(AcessoNegadoException.class);
                assertThatThrownBy(() -> empresas.listar(null, null, null, Pageable.ofSize(5))).isInstanceOf(AcessoNegadoException.class);
                assertThat(Autorizacao.semConferir(() -> {
                    Autorizacao.conferirEmpresa(1L, "Empresa", 1L);
                    autorizacao.exigir(Permissoes.PESSOA_LER);
                    return "liberado";
                })).isEqualTo("liberado");
            } catch (Throwable t) {
                falha.set(t);
            }
        });
        tarefa.start();
        tarefa.join(30_000);
        if (falha.get() != null) {
            throw new AssertionError("Na thread sem pedido: " + falha.get().getMessage(), falha.get());
        }
    }

    @Test
    void caminhoForaDaFormaCanonicaNaoDesviaDosFiltros() throws Exception {
        // Sem o segredo do BFF: com o caminho normal, 403 do FiltroDoBff; com ";x" ou "%61", o Spring
        // casaria a rota, mas o caminho e recusado antes de tudo.
        mvc.perform(post("/api/auth/passkey/login/opcoes")).andExpect(status().isForbidden());
        mvc.perform(post("/api;x/auth/passkey/login/opcoes")).andExpect(status().isBadRequest());
        mvc.perform(post(URI.create("/%61pi/auth/passkey/login/opcoes"))).andExpect(status().isBadRequest());
        mvc.perform(Api.doBff(get("/api/empresas")).with(r -> { r.setRequestURI("/api//empresas"); return r; }))
                .andExpect(status().isBadRequest());
        mvc.perform(Api.doBff(post("/api/auth/passkey/login/opcoes"))).andExpect(status().isOk());
    }
}
