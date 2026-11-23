package br.com.kawashi.banco.autorizacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.jayway.jsonpath.JsonPath;

import br.com.kawashi.banco.login.SchemasDoLogin;
import br.com.kawashi.banco.testes.Api;
import br.com.kawashi.banco.testes.GeradorDeDocumentos;
import br.com.kawashi.banco.testes.IntegracaoApi;
import br.com.kawashi.banco.testes.UsuarioDeTeste;

/**
 * A autorizacao multiempresa de ponta a ponta: uma empresa nao enxerga a outra; cada perfil so faz
 * o que pode; a sessao fica presa a uma empresa; so o administrador da cargo, e toda troca vai para
 * o LOG_PERMISSAO; a empresa nunca fica sem administrador; e nenhuma rota esta sem regra.
 */
@IntegracaoApi
class AutorizacaoApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioDeTeste usuarios;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SchemasDoLogin schemas;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping rotas;

    private UsuarioDeTeste.Criado admin;
    private Api api;

    @BeforeEach
    void preparar() {
        admin = usuarios.novo();
        api = usuarios.api(mvc, admin);
    }

    // ---- toda rota tem regra ------------------------------------------------------------------

    @Test
    void nenhumaRotaDaApiFicaSemRegraDeAcesso() {
        List<String> semRegra = rotas.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getKey().getPatternValues().stream().anyMatch(p -> p.startsWith("/api/") && !p.startsWith("/api/auth/")))
                .filter(e -> ((HandlerMethod) e.getValue()).getMethodAnnotation(Exige.class) == null)
                .map(e -> e.getKey().toString())
                .toList();
        assertThat(semRegra).as("rotas de /api sem @Exige").isEmpty();
    }

    // ---- uma empresa nao enxerga a outra ------------------------------------------------------

    @Test
    void cadastrosDeOutraEmpresaRespondem404ComoSeNaoExistissem() throws Exception {
        UsuarioDeTeste.Criado outro = usuarios.novo();
        Api deOutra = usuarios.api(mvc, outro);
        long pessoa = deOutra.pessoa();
        long filial = deOutra.filial();
        long contato = deOutra.criar("/api/pessoas/" + pessoa + "/contatos", "{\"tipo\":\"EMAIL\",\"valor\":\"x@exemplo.com.br\"}");
        long endereco = deOutra.criar("/api/filiais/" + filial + "/enderecos",
                "{\"logradouro\":\"Rua A\",\"cidade\":\"Sao Paulo\",\"uf\":\"SP\",\"cep\":\"01001000\"}");

        for (String caminho : List.of("/api/empresas/" + outro.empresaId(), "/api/empresas/" + outro.empresaId() + "/pessoas",
                "/api/empresas/" + outro.empresaId() + "/filiais", "/api/pessoas/" + pessoa, "/api/pessoas/" + pessoa + "/documento",
                "/api/pessoas/" + pessoa + "/contatos", "/api/filiais/" + filial, "/api/filiais/" + filial + "/enderecos",
                "/api/contatos/" + contato, "/api/contatos/" + contato + "/valor", "/api/enderecos/" + endereco)) {
            api.get(caminho).andExpect(status().isNotFound());
        }
        api.put("/api/pessoas/" + pessoa, Api.pessoaJson("CLIENTE", GeradorDeDocumentos.cpf(), null, null)).andExpect(status().isNotFound());
        api.delete("/api/pessoas/" + pessoa).andExpect(status().isNotFound());
        api.post("/api/pessoas/" + pessoa + "/inativar").andExpect(status().isNotFound());
        api.delete("/api/contatos/" + contato).andExpect(status().isNotFound());
        api.delete("/api/enderecos/" + endereco).andExpect(status().isNotFound());
        api.post("/api/empresas/" + outro.empresaId() + "/pessoas", Api.pessoaJson("CLIENTE", GeradorDeDocumentos.cpf(), null, null))
                .andExpect(status().isNotFound());
        // Nada mudou do outro lado.
        deOutra.get("/api/pessoas/" + pessoa).andExpect(status().isOk()).andExpect(jsonPath("$.ativa").value(true));
        deOutra.get("/api/contatos/" + contato).andExpect(status().isOk());
    }

    // ---- cada perfil so faz o que pode --------------------------------------------------------

    @Test
    void consultaSoLe() throws Exception {
        long pessoa = api.pessoa();
        Api consulta = usuarios.api(mvc, usuarios.naEmpresa(admin.empresaId(), "CONSULTA"));
        consulta.get("/api/pessoas/" + pessoa).andExpect(status().isOk());
        consulta.get("/api/empresas/" + admin.empresaId()).andExpect(status().isOk());
        consulta.post("/api/empresas/" + admin.empresaId() + "/pessoas", Api.pessoaJson("CLIENTE", GeradorDeDocumentos.cpf(), null, null))
                .andExpect(status().isForbidden());
        consulta.post("/api/pessoas/" + pessoa + "/contatos", "{\"tipo\":\"EMAIL\",\"valor\":\"c@exemplo.com.br\"}")
                .andExpect(status().isForbidden());
        consulta.get("/api/pessoas/" + pessoa + "/documento").andExpect(status().isForbidden());
    }

    @Test
    void operadorCadastraMasNaoRevelaNemMexeNaEmpresaNemNosUsuarios() throws Exception {
        Api operador = usuarios.api(mvc, usuarios.naEmpresa(admin.empresaId(), "OPERADOR"));
        long pessoa = operador.criar("/api/empresas/" + admin.empresaId() + "/pessoas",
                Api.pessoaJson("CLIENTE", GeradorDeDocumentos.cpf(), null, null));
        operador.criar("/api/pessoas/" + pessoa + "/contatos", "{\"tipo\":\"EMAIL\",\"valor\":\"o@exemplo.com.br\"}");
        operador.get("/api/pessoas/" + pessoa + "/documento").andExpect(status().isForbidden());
        operador.put("/api/empresas/" + admin.empresaId(), "{\"cnpj\":\"" + admin.cnpjDaEmpresa() + "\",\"razaoSocial\":\"X\"}")
                .andExpect(status().isForbidden());
        operador.get("/api/usuarios").andExpect(status().isForbidden());
        operador.get("/api/perfis").andExpect(status().isForbidden());
        // O administrador revela (e fica no LOG_ACESSO).
        api.get("/api/pessoas/" + pessoa + "/documento").andExpect(status().isOk());
    }

    // ---- sessao presa a uma empresa -----------------------------------------------------------

    @Test
    void semEmpresaEscolhidaSoDaParaEscolher() throws Exception {
        long segunda = api.empresa();
        Api semEmpresa = new Api(mvc, usuarios.tokenSemEmpresa(admin));
        semEmpresa.get("/api/empresas").andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("Escolha a empresa"));
        semEmpresa.get("/api/auth/empresas").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        String tokenAntigo = semEmpresa.token();

        String resposta = Api.corpo(semEmpresa.post("/api/auth/empresa", "{\"empresaId\":" + segunda + "}").andExpect(status().isOk()));
        String tokenNovo = JsonPath.read(resposta, "$.token");
        new Api(mvc, tokenNovo).get("/api/auth/eu").andExpect(jsonPath("$.empresaId").value(segunda))
                .andExpect(jsonPath("$.permissoes", org.hamcrest.Matchers.hasItem("PERFIL_GERENCIAR")));
        new Api(mvc, tokenAntigo).get("/api/auth/eu").andExpect(status().isUnauthorized());   // o anterior caiu
        // Empresa sem vinculo: 404.
        new Api(mvc, tokenNovo).post("/api/auth/empresa", "{\"empresaId\":" + usuarios.novo().empresaId() + "}")
                .andExpect(status().isNotFound());
        // Duas escolhas: a do api.empresa() do comeco (que tambem troca pela rota real) e a de agora.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.log("LOG_SEGURANCA") + " WHERE EVENTO = 'EMPRESA_ESCOLHIDA'"
                + " AND USUARIO_ID = ?", Long.class, admin.usuarioId())).isEqualTo(2L);
    }

    // ---- usuarios, cargos e o log das trocas --------------------------------------------------

    @Test
    void administradorCriaUsuarioSoParaPessoaEPerfilDaPropriaEmpresa() throws Exception {
        long consulta = perfil(admin.empresaId(), "CONSULTA");
        long pessoa = api.pessoa();
        api.post("/api/usuarios", "{\"pessoaId\":" + pessoa + ",\"email\":\"nova@teste.kawashi.invalid\",\"perfilId\":" + consulta + "}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.convite").value(org.hamcrest.Matchers.startsWith("kwc_")));
        api.get("/api/usuarios").andExpect(jsonPath("$[?(@.perfil == 'CONSULTA')]").isNotEmpty());

        UsuarioDeTeste.Criado outro = usuarios.novo();
        long pessoaDeOutra = usuarios.api(mvc, outro).pessoa();
        api.post("/api/usuarios", "{\"pessoaId\":" + pessoaDeOutra + ",\"email\":\"x@teste.kawashi.invalid\",\"perfilId\":" + consulta + "}")
                .andExpect(status().isNotFound());
        api.post("/api/usuarios", "{\"pessoaId\":" + api.pessoa() + ",\"email\":\"y@teste.kawashi.invalid\",\"perfilId\":"
                + perfil(outro.empresaId(), "CONSULTA") + "}").andExpect(status().isNotFound());
    }

    @Test
    void trocaDeCargoValeNaHoraEFicaNoLogComDeParaEQuem() throws Exception {
        UsuarioDeTeste.Criado funcionario = usuarios.naEmpresa(admin.empresaId(), "CONSULTA");
        Api dele = usuarios.api(mvc, funcionario);
        String novaPessoa = Api.pessoaJson("CLIENTE", GeradorDeDocumentos.cpf(), null, null);
        dele.post("/api/empresas/" + admin.empresaId() + "/pessoas", novaPessoa).andExpect(status().isForbidden());

        api.put("/api/usuarios/" + funcionario.usuarioId() + "/perfil", "{\"perfilId\":" + perfil(admin.empresaId(), "OPERADOR") + "}")
                .andExpect(status().isNoContent());
        // Com o MESMO token, ja pode (a permissao e lida do banco a cada pedido).
        dele.post("/api/empresas/" + admin.empresaId() + "/pessoas", novaPessoa).andExpect(status().isCreated());

        List<Map<String, Object>> log = jdbc.queryForList("SELECT EVENTO, PERFIL_ANTERIOR, PERFIL_NOVO, FEITO_POR FROM "
                + schemas.log("LOG_PERMISSAO") + " WHERE EMPRESA_ID = ? AND USUARIO_ID = ? ORDER BY ORDEM", admin.empresaId(), funcionario.usuarioId());
        assertThat(log).extracting(l -> l.get("EVENTO")).containsExactly("VINCULO_CRIADO", "PERFIL_TROCADO");
        assertThat(log.get(1)).containsEntry("PERFIL_ANTERIOR", "CONSULTA").containsEntry("PERFIL_NOVO", "OPERADOR")
                .containsEntry("FEITO_POR", "USUARIO:" + admin.usuarioId());
        api.get("/api/usuarios/historico").andExpect(jsonPath("$[0].evento").value("PERFIL_TROCADO"))
                .andExpect(jsonPath("$[0].de").value("CONSULTA")).andExpect(jsonPath("$[0].para").value("OPERADOR"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.log("V_LOG_PERMISSAO_QUEBRAS"), Long.class)).isZero();
    }

    @Test
    void aEmpresaNuncaFicaSemAdministrador() throws Exception {
        long operador = perfil(admin.empresaId(), "OPERADOR");
        api.put("/api/usuarios/" + admin.usuarioId() + "/perfil", "{\"perfilId\":" + operador + "}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(containsString("sem nenhum administrador")));
        api.post("/api/usuarios/" + admin.usuarioId() + "/desativar")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(containsString("sem nenhum administrador")));
        // Com um segundo administrador, o primeiro pode sair do cargo.
        UsuarioDeTeste.Criado segundo = usuarios.naEmpresa(admin.empresaId(), "ADMINISTRADOR");
        api.put("/api/usuarios/" + admin.usuarioId() + "/perfil", "{\"perfilId\":" + operador + "}").andExpect(status().isNoContent());
        // E agora o segundo e o ultimo: tambem nao sai.
        usuarios.api(mvc, segundo).post("/api/usuarios/" + segundo.usuarioId() + "/desativar").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void desativarTiraOAcessoNaHoraEReativarDevolve() throws Exception {
        UsuarioDeTeste.Criado funcionario = usuarios.naEmpresa(admin.empresaId(), "OPERADOR");
        Api dele = usuarios.api(mvc, funcionario);
        dele.get("/api/empresas").andExpect(status().isOk());
        api.post("/api/usuarios/" + funcionario.usuarioId() + "/desativar").andExpect(status().isNoContent());
        dele.get("/api/empresas").andExpect(status().isUnauthorized());
        api.post("/api/usuarios/" + funcionario.usuarioId() + "/reativar").andExpect(status().isNoContent());
        usuarios.api(mvc, funcionario).get("/api/empresas").andExpect(status().isOk());   // entra de novo
        api.post("/api/usuarios/999999999/desativar").andExpect(status().isNotFound());
    }

    @Test
    void tokenDeEmpresaDeixaDeValerSeOVinculoCaiPorOutroCaminho() throws Exception {
        // Desativado direto no banco (sem a API revogar as sessoes): o token ainda tem de cair,
        // porque a validacao confere o vinculo a cada pedido.
        UsuarioDeTeste.Criado funcionario = usuarios.naEmpresa(admin.empresaId(), "OPERADOR");
        Api dele = usuarios.api(mvc, funcionario);
        dele.get("/api/empresas").andExpect(status().isOk());
        jdbc.update("UPDATE " + schemas.login("USUARIO_EMPRESA") + " SET ATIVO = FALSE, ATUALIZADO_POR = ? WHERE USUARIO_ID = ? AND EMPRESA_ID = ?",
                UsuarioDeTeste.QUEM, funcionario.usuarioId(), admin.empresaId());
        dele.get("/api/empresas").andExpect(status().isUnauthorized());
    }

    @Test
    void conviteNovoSoParaQuemEDaEmpresa() throws Exception {
        UsuarioDeTeste.Criado funcionario = usuarios.naEmpresa(admin.empresaId(), "CONSULTA");
        api.post("/api/usuarios/" + funcionario.usuarioId() + "/convite").andExpect(status().isOk())
                .andExpect(jsonPath("$.convite").value(org.hamcrest.Matchers.startsWith("kwc_")));
        api.post("/api/usuarios/" + usuarios.novo().usuarioId() + "/convite").andExpect(status().isNotFound());
    }

    // ---- perfis -------------------------------------------------------------------------------

    @Test
    void administradorEditaOsOutrosPerfisMasNaoOProprioNemDaPermissaoExclusiva() throws Exception {
        long operadorId = perfil(admin.empresaId(), "OPERADOR");
        Api operador = usuarios.api(mvc, usuarios.naEmpresa(admin.empresaId(), "OPERADOR"));
        String novaPessoa = Api.pessoaJson("CLIENTE", GeradorDeDocumentos.cpf(), null, null);
        operador.post("/api/empresas/" + admin.empresaId() + "/pessoas", novaPessoa).andExpect(status().isCreated());

        // Tira PESSOA_GERENCIAR do OPERADOR: vale na hora, e fica no log.
        api.put("/api/perfis/" + operadorId + "/permissoes", "{\"permissoes\":[\"EMPRESA_LER\",\"PESSOA_LER\"]}").andExpect(status().isNoContent());
        operador.post("/api/empresas/" + admin.empresaId() + "/pessoas", Api.pessoaJson("CLIENTE", GeradorDeDocumentos.cpf(), null, null))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForList("SELECT PERMISSAO FROM " + schemas.log("LOG_PERMISSAO") + " WHERE EMPRESA_ID = ? AND EVENTO = 'PERMISSAO_REMOVIDA'",
                String.class, admin.empresaId())).containsExactlyInAnyOrder("PESSOA_GERENCIAR", "FILIAL_LER", "FILIAL_GERENCIAR");

        // Permissao exclusiva do administrador nao vai para outro perfil; o ADMINISTRADOR nao se edita.
        api.put("/api/perfis/" + operadorId + "/permissoes", "{\"permissoes\":[\"USUARIO_GERENCIAR\"]}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(containsString("exclusiva")));
        api.put("/api/perfis/" + perfil(admin.empresaId(), "ADMINISTRADOR") + "/permissoes", "{\"permissoes\":[]}")
                .andExpect(status().isUnprocessableEntity());
        // Perfil de outra empresa: 404.
        api.put("/api/perfis/" + perfil(usuarios.novo().empresaId(), "OPERADOR") + "/permissoes", "{\"permissoes\":[]}")
                .andExpect(status().isNotFound());
    }

    @Test
    void administradorCriaPerfilProprio() throws Exception {
        api.post("/api/perfis", "{\"nome\":\"financeiro\",\"permissoes\":[\"EMPRESA_LER\",\"DADO_PESSOAL_REVELAR\",\"PESSOA_LER\"]}")
                .andExpect(status().isCreated());
        api.get("/api/perfis").andExpect(jsonPath("$[?(@.nome == 'FINANCEIRO')].permissoes[*]",
                org.hamcrest.Matchers.containsInAnyOrder("DADO_PESSOAL_REVELAR", "EMPRESA_LER", "PESSOA_LER")));
        api.post("/api/perfis", "{\"nome\":\"Financeiro\",\"permissoes\":[]}").andExpect(status().isConflict());
        api.post("/api/perfis", "{\"nome\":\"chefe\",\"permissoes\":[\"PERFIL_GERENCIAR\"]}").andExpect(status().isUnprocessableEntity());
        api.post("/api/perfis", "{\"nome\":\"x\",\"permissoes\":[\"NAO_EXISTE\"]}").andExpect(status().isUnprocessableEntity());
    }

    private long perfil(long empresaId, String nome) {
        return jdbc.queryForObject("SELECT ID FROM " + schemas.login("PERFIL") + " WHERE EMPRESA_ID = ? AND NOME = ?", Long.class, empresaId, nome);
    }
}
