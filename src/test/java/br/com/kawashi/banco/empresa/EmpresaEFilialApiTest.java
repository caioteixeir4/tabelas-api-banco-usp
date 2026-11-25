package br.com.kawashi.banco.empresa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import br.com.kawashi.banco.testes.Api;
import br.com.kawashi.banco.testes.UsuarioDeTeste;
import br.com.kawashi.banco.testes.GeradorDeDocumentos;
import br.com.kawashi.banco.testes.IntegracaoApi;

@IntegracaoApi
class EmpresaEFilialApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioDeTeste usuarios;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired
    private br.com.kawashi.banco.testes.CorreioDeTeste correio;
    private Api api;

    @BeforeEach
    void preparar() {
        api = usuarios.api(mvc);
    }

    // ---- empresa: criada so pelo administrador da plataforma -------------------------------

    private static String novaEmpresa(String cnpj, String razaoSocial) {
        return "{\"cnpj\":\"" + cnpj + "\",\"razaoSocial\":\"" + razaoSocial + "\",\"nomeFantasia\":\"   \","
                + "\"administrador\":{\"cpf\":\"" + GeradorDeDocumentos.cpf() + "\",\"nome\":\"Primeira Administradora\","
                + "\"email\":\"adm" + ThreadLocalRandom.current().nextInt(1_000_000) + "@teste.kawashi.invalid\"}}";
    }

    @Test
    void plataformaCriaEmpresaComOPrimeiroAdministradorMasNaoEnxergaOsDados() throws Exception {
        Api plataforma = usuarios.apiDaPlataforma(mvc);
        String cnpj = GeradorDeDocumentos.cnpj();
        var resposta = plataforma.post("/api/plataforma/empresas", novaEmpresa(GeradorDeDocumentos.formatarCnpj(cnpj), "  Padaria São João LTDA  "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enviadoPara").value(org.hamcrest.Matchers.startsWith("a***@")))
                .andExpect(jsonPath("$.convite").doesNotExist())   // o convite foi para o e-mail do administrador novo
                .andExpect(header().string("Location", containsString("/api/plataforma/empresas/")));
        assertThat(correio.todas()).anyMatch(m -> "CONVITE".equals(m.tipo()) && m.para().startsWith("adm"));
        long empresaId = ((Number) com.jayway.jsonpath.JsonPath.read(Api.corpo(resposta), "$.empresaId")).longValue();

        plataforma.get("/api/plataforma/empresas?razaoSocial=Padaria São João").andExpect(status().isOk())
                .andExpect(jsonPath("$.itens[?(@.id == " + empresaId + ")].cnpj").value(org.hamcrest.Matchers.contains(cnpj)))
                .andExpect(jsonPath("$.itens[?(@.id == " + empresaId + ")].razaoSocial").value(org.hamcrest.Matchers.contains("Padaria São João LTDA")));
        // Sem vinculo com a empresa: nao entra nos dados dela.
        plataforma.get("/api/empresas/" + empresaId).andExpect(status().isForbidden());
        plataforma.post("/api/auth/empresa", "{\"empresaId\":" + empresaId + "}").andExpect(status().isNotFound());
    }

    @Test
    void administradorDeEmpresaNaoUsaAsRotasDaPlataforma() throws Exception {
        api.post("/api/plataforma/empresas", novaEmpresa(GeradorDeDocumentos.cnpj(), "X")).andExpect(status().isForbidden());
        api.get("/api/plataforma/empresas").andExpect(status().isForbidden());
        api.post("/api/plataforma/empresas/" + api.empresaAtual() + "/inativar").andExpect(status().isForbidden());
        // E a rota antiga de criar empresa nao existe mais.
        api.post("/api/empresas", "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj() + "\",\"razaoSocial\":\"X\"}")
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void cnpjComDigitoErradoOuComLetrasEhRecusadoNoCampo() throws Exception {
        Api plataforma = usuarios.apiDaPlataforma(mvc);
        String errado = GeradorDeDocumentos.comDigitoVerificadorErrado(GeradorDeDocumentos.cnpj());
        plataforma.post("/api/plataforma/empresas", novaEmpresa(errado, "X"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("cnpj"));
        plataforma.post("/api/plataforma/empresas", novaEmpresa("abc" + GeradorDeDocumentos.cnpj() + "xyz", "X"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("cnpj"));
    }

    @Test
    void empresaPrecisaSerMatrizENaoRepeteCnpj() throws Exception {
        Api plataforma = usuarios.apiDaPlataforma(mvc);
        String raiz = GeradorDeDocumentos.raizDeCnpj();
        plataforma.post("/api/plataforma/empresas", novaEmpresa(GeradorDeDocumentos.cnpj(raiz, "0002"), "X"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("matriz")));
        String cnpj = GeradorDeDocumentos.cnpj(raiz, "0001");
        plataforma.post("/api/plataforma/empresas", novaEmpresa(cnpj, "Primeira")).andExpect(status().isCreated());
        plataforma.post("/api/plataforma/empresas", novaEmpresa(cnpj, "Outra")).andExpect(status().isConflict());
    }

    @Test
    void razaoSocialAceita200LetrasAcentuadasERecusa201() throws Exception {
        Api plataforma = usuarios.apiDaPlataforma(mvc);
        plataforma.post("/api/plataforma/empresas", novaEmpresa(GeradorDeDocumentos.cnpj(), "ã".repeat(200))).andExpect(status().isCreated());
        plataforma.post("/api/plataforma/empresas", novaEmpresa(GeradorDeDocumentos.cnpj(), "ã".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("razaoSocial"));
        // So espaco (inclusive o espaco Unicode U+2003) nao e razao social.
        plataforma.post("/api/plataforma/empresas", novaEmpresa(GeradorDeDocumentos.cnpj(), " ")).andExpect(status().isBadRequest());
    }

    @Test
    void atualizaSemTrocarOCnpjENaoReativaAoAtualizar() throws Exception {
        Api plataforma = usuarios.apiDaPlataforma(mvc);
        String cnpj = GeradorDeDocumentos.cnpj();
        long id = api.empresa(cnpj);
        api.put("/api/empresas/" + id, "{\"cnpj\":\"" + cnpj + "\",\"razaoSocial\":\"Nome Novo\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razaoSocial").value("Nome Novo"));
        api.put("/api/empresas/" + id, "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj() + "\",\"razaoSocial\":\"X\"}")
                .andExpect(status().isUnprocessableEntity());
        api.get("/api/empresas/999999999").andExpect(status().isNotFound());
        plataforma.post("/api/plataforma/empresas/" + id + "/inativar").andExpect(status().isOk()).andExpect(jsonPath("$.ativa").value(false));
        plataforma.post("/api/plataforma/empresas/" + id + "/inativar").andExpect(status().isOk());
        // Inativada, quem estava logado nela sai na hora (revisao de 23/11/2026).
        api.get("/api/empresas/" + id).andExpect(status().isUnauthorized());
        plataforma.post("/api/plataforma/empresas/" + id + "/reativar").andExpect(jsonPath("$.ativa").value(true));
    }

    @Test
    void cadaSessaoSoEnxergaASuaEmpresa() throws Exception {
        long primeira = api.empresaAtual();
        long segunda = api.empresa();
        api.get("/api/empresas").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.itens[0].id").value(segunda));
        api.get("/api/empresas/" + primeira).andExpect(status().isNotFound());
        api.entrarNa(primeira);
        api.get("/api/empresas/" + segunda).andExpect(status().isNotFound());
        api.get("/api/empresas/" + primeira).andExpect(status().isOk());
    }

    @Test
    void listaDaPlataformaTrataPorcentoComoTextoEPaginacaoSegura() throws Exception {
        Api plataforma = usuarios.apiDaPlataforma(mvc);
        String marca = "Filtro" + ThreadLocalRandom.current().nextInt(1_000_000);
        plataforma.post("/api/plataforma/empresas", novaEmpresa(GeradorDeDocumentos.cnpj(), marca + " 100% Natural")).andExpect(status().isCreated());
        plataforma.post("/api/plataforma/empresas", novaEmpresa(GeradorDeDocumentos.cnpj(), marca + " 1000 Natural")).andExpect(status().isCreated());

        plataforma.get("/api/plataforma/empresas?razaoSocial=" + marca).andExpect(jsonPath("$.total").value(2));
        mvc.perform(plataforma.autenticado(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/plataforma/empresas")
                        .param("razaoSocial", marca + " 100%")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.itens[0].razaoSocial").value(marca + " 100% Natural"));
        plataforma.get("/api/plataforma/empresas?page=2147483647").andExpect(status().isBadRequest());
        plataforma.get("/api/plataforma/empresas?sort=naoExiste,asc").andExpect(status().isBadRequest());
        plataforma.get("/api/plataforma/empresas?size=1000").andExpect(jsonPath("$.tamanho").value(100));
    }

    @Test
    void empresaComFilialPessoaOuPerfisNaoEhExcluida() throws Exception {
        Api plataforma = usuarios.apiDaPlataforma(mvc);
        String raiz = GeradorDeDocumentos.raizDeCnpj();
        long comFilial = api.empresa(GeradorDeDocumentos.cnpj(raiz, "0001"));
        api.filial(comFilial, raiz);
        plataforma.delete("/api/plataforma/empresas/" + comFilial).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("1 filial")));
        // Toda empresa criada tem os perfis de acesso: nao se apaga, se inativa.
        long soComPerfis = api.empresa();
        plataforma.delete("/api/plataforma/empresas/" + soComPerfis).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("3 perfil(is)")));
        // Sem nada pendurado (empresa de antes da autorizacao), sai.
        String cnpj = GeradorDeDocumentos.cnpj();
        jdbc.update("INSERT INTO EMPRESA (CNPJ, RAZAO_SOCIAL) VALUES (?, 'Sem Nada LTDA')", cnpj);
        long semNada = jdbc.queryForObject("SELECT ID FROM EMPRESA WHERE CNPJ = ?", Long.class, cnpj);
        plataforma.delete("/api/plataforma/empresas/" + semNada).andExpect(status().isNoContent());
        plataforma.delete("/api/plataforma/empresas/" + semNada).andExpect(status().isNotFound());
    }
    // ---- filial --------------------------------------------------------------------------

    @Test
    void criaFilialComAMesmaRaizDaEmpresa() throws Exception {
        String raiz = GeradorDeDocumentos.raizDeCnpj();
        long empresaId = api.empresa(GeradorDeDocumentos.cnpj(raiz, "0001"));
        String cnpj = GeradorDeDocumentos.cnpj(raiz, "0002");
        api.post("/api/empresas/" + empresaId + "/filiais", "{\"cnpj\":\"" + cnpj + "\",\"nome\":\"Filial Centro\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.empresaId").value(empresaId))
                .andExpect(jsonPath("$.cnpj").value(cnpj));
        api.get("/api/empresas/" + empresaId + "/filiais").andExpect(jsonPath("$.total").value(1));
        // duplicada
        api.post("/api/empresas/" + empresaId + "/filiais", "{\"cnpj\":\"" + cnpj + "\",\"nome\":\"Outra\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void filialRecusaRaizDiferenteOrdemDaMatrizEEmpresaInativaOuInexistente() throws Exception {
        String raiz = GeradorDeDocumentos.raizDeCnpj();
        String cnpjMatriz = GeradorDeDocumentos.cnpj(raiz, "0001");
        long empresaId = api.empresa(cnpjMatriz);
        String caminho = "/api/empresas/" + empresaId + "/filiais";

        api.post(caminho, "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj(GeradorDeDocumentos.raizDeCnpj(), "0002") + "\",\"nome\":\"X\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(containsString("raiz")));
        api.post(caminho, "{\"cnpj\":\"" + cnpjMatriz + "\",\"nome\":\"X\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(containsString("0001")));

        api.post("/api/empresas/999999999/filiais", "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj(raiz, "0003") + "\",\"nome\":\"X\"}")
                .andExpect(status().isNotFound());
        api.get("/api/empresas/999999999/filiais").andExpect(status().isNotFound());

        // Empresa inativada: a sessao cai antes de chegar a regra (o servico ainda recusaria com 422).
        usuarios.apiDaPlataforma(mvc).post("/api/plataforma/empresas/" + empresaId + "/inativar").andExpect(status().isOk());
        api.post(caminho, "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj(raiz, "0002") + "\",\"nome\":\"X\"}")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void filialSoMudaONomeESoEhExcluidaSemContatosNemEnderecos() throws Exception {
        String raiz = GeradorDeDocumentos.raizDeCnpj();
        long empresaId = api.empresa(GeradorDeDocumentos.cnpj(raiz, "0001"));
        String cnpj = GeradorDeDocumentos.cnpj(raiz, "0002");
        long id = api.criar("/api/empresas/" + empresaId + "/filiais", "{\"cnpj\":\"" + cnpj + "\",\"nome\":\"Antes\"}");

        api.put("/api/filiais/" + id, "{\"cnpj\":\"" + cnpj + "\",\"nome\":\"Depois\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.nome").value("Depois"));
        api.put("/api/filiais/" + id, "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj(raiz, "0003") + "\",\"nome\":\"X\"}")
                .andExpect(status().isUnprocessableEntity());

        api.criar("/api/filiais/" + id + "/contatos", "{\"tipo\":\"EMAIL\",\"valor\":\"filial@exemplo.com.br\"}");
        api.delete("/api/filiais/" + id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("1 contato")));
    }
}
