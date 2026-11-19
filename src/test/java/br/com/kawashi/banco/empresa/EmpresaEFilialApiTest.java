package br.com.kawashi.banco.empresa;

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
    private Api api;

    @BeforeEach
    void preparar() {
        api = new Api(mvc, usuarios.token());
    }

    // ---- empresa -------------------------------------------------------------------------

    @Test
    void criaEmpresaNormalizandoOCnpjEDevolveLocation() throws Exception {
        String cnpj = GeradorDeDocumentos.cnpj();
        var resposta = api.post("/api/empresas", "{\"cnpj\":\"" + GeradorDeDocumentos.formatarCnpj(cnpj)
                + "\",\"razaoSocial\":\"  Padaria São João LTDA  \",\"nomeFantasia\":\"   \"}");
        resposta.andExpect(status().isCreated())
                .andExpect(jsonPath("$.cnpj").value(cnpj))
                .andExpect(jsonPath("$.razaoSocial").value("Padaria São João LTDA"))
                .andExpect(jsonPath("$.nomeFantasia").doesNotExist())
                .andExpect(jsonPath("$.ativa").value(true))
                .andExpect(header().string("Location", endsWith("/api/empresas/" + Api.id(resposta))));
    }

    @Test
    void cnpjComDigitoErradoOuComLetrasEhRecusadoNoCampo() throws Exception {
        String errado = GeradorDeDocumentos.comDigitoVerificadorErrado(GeradorDeDocumentos.cnpj());
        api.post("/api/empresas", "{\"cnpj\":\"" + errado + "\",\"razaoSocial\":\"X\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("cnpj"));
        api.post("/api/empresas", "{\"cnpj\":\"abc" + GeradorDeDocumentos.cnpj() + "xyz\",\"razaoSocial\":\"X\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("cnpj"));
    }

    @Test
    void empresaPrecisaSerMatrizENaoRepeteCnpj() throws Exception {
        String raiz = GeradorDeDocumentos.raizDeCnpj();
        api.post("/api/empresas", "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj(raiz, "0002") + "\",\"razaoSocial\":\"X\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("matriz")));
        String cnpj = GeradorDeDocumentos.cnpj(raiz, "0001");
        api.empresa(cnpj);
        api.post("/api/empresas", "{\"cnpj\":\"" + cnpj + "\",\"razaoSocial\":\"Outra\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void razaoSocialAceita200LetrasAcentuadasERecusa201() throws Exception {
        api.post("/api/empresas", "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj() + "\",\"razaoSocial\":\"" + "ã".repeat(200) + "\"}")
                .andExpect(status().isCreated());
        api.post("/api/empresas", "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj() + "\",\"razaoSocial\":\"" + "ã".repeat(201) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("razaoSocial"));
        // So espaco (inclusive o espaco Unicode U+2003) nao e razao social.
        api.post("/api/empresas", "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj() + "\",\"razaoSocial\":\" \"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void atualizaSemTrocarOCnpjENaoReativaAoAtualizar() throws Exception {
        String cnpj = GeradorDeDocumentos.cnpj();
        long id = api.empresa(cnpj);
        api.post("/api/empresas/" + id + "/inativar").andExpect(status().isOk()).andExpect(jsonPath("$.ativa").value(false));
        api.post("/api/empresas/" + id + "/inativar").andExpect(status().isOk());
        api.put("/api/empresas/" + id, "{\"cnpj\":\"" + cnpj + "\",\"razaoSocial\":\"Nome Novo\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razaoSocial").value("Nome Novo"))
                .andExpect(jsonPath("$.ativa").value(false));
        api.put("/api/empresas/" + id, "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj() + "\",\"razaoSocial\":\"X\"}")
                .andExpect(status().isUnprocessableEntity());
        api.post("/api/empresas/" + id + "/reativar").andExpect(jsonPath("$.ativa").value(true));
        api.get("/api/empresas/999999999").andExpect(status().isNotFound());
    }

    @Test
    void listaComFiltroQueTrataPorcentoComoTextoEPaginacaoSegura() throws Exception {
        String marca = "Filtro" + ThreadLocalRandom.current().nextInt(1_000_000);
        api.criar("/api/empresas", "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj() + "\",\"razaoSocial\":\"" + marca + " 100% Natural\"}");
        api.criar("/api/empresas", "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj() + "\",\"razaoSocial\":\"" + marca + " 1000 Natural\"}");

        api.get("/api/empresas?razaoSocial=" + marca).andExpect(jsonPath("$.total").value(2));
        mvc.perform(api.autenticado(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/empresas")
                        .param("razaoSocial", marca + " 100%")))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.itens[0].razaoSocial").value(marca + " 100% Natural"));
        api.get("/api/empresas?page=2147483647").andExpect(status().isBadRequest());
        api.get("/api/empresas?sort=naoExiste,asc").andExpect(status().isBadRequest());
        api.get("/api/empresas?size=1000").andExpect(jsonPath("$.tamanho").value(100));
    }

    @Test
    void empresaComFilialOuPessoaNaoEhExcluida() throws Exception {
        String raiz = GeradorDeDocumentos.raizDeCnpj();
        long comFilial = api.empresa(GeradorDeDocumentos.cnpj(raiz, "0001"));
        api.filial(comFilial, raiz);
        api.delete("/api/empresas/" + comFilial).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("1 filial")));

        long comPessoa = api.empresa();
        api.pessoa(comPessoa, "CLIENTE");
        api.delete("/api/empresas/" + comPessoa).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("1 pessoa")));

        long semNada = api.empresa();
        api.delete("/api/empresas/" + semNada).andExpect(status().isNoContent());
        api.get("/api/empresas/" + semNada).andExpect(status().isNotFound());
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

        api.post("/api/empresas/" + empresaId + "/inativar").andExpect(status().isOk());
        api.post(caminho, "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj(raiz, "0002") + "\",\"nome\":\"X\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(containsString("inativa")));

        api.post("/api/empresas/999999999/filiais", "{\"cnpj\":\"" + GeradorDeDocumentos.cnpj(raiz, "0003") + "\",\"nome\":\"X\"}")
                .andExpect(status().isNotFound());
        api.get("/api/empresas/999999999/filiais").andExpect(status().isNotFound());
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
