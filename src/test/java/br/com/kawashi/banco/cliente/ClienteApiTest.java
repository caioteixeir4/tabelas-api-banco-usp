package br.com.kawashi.banco.cliente;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import br.com.kawashi.banco.testes.GeradorDeDocumentos;
import br.com.kawashi.banco.testes.IntegracaoApi;

@IntegracaoApi
class ClienteApiTest {

    @Autowired
    private MockMvc mvc;

    private static String json(String cnpj, String razaoSocial, String nomeFantasia) {
        String fantasia = nomeFantasia == null ? "null" : "\"" + nomeFantasia + "\"";
        return """
                {"cnpj": "%s", "razaoSocial": "%s", "nomeFantasia": %s}
                """.formatted(cnpj, razaoSocial, fantasia);
    }

    private long criar(String cnpj, String razaoSocial) throws Exception {
        String corpo = mvc.perform(post("/api/clientes").contentType(MediaType.APPLICATION_JSON).content(json(cnpj, razaoSocial, null)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(corpo, "$.id")).longValue();
    }

    @Test
    void criaClienteComCnpjFormatadoEGuardaSoDigitos() throws Exception {
        String cnpj = GeradorDeDocumentos.cnpj();

        mvc.perform(post("/api/clientes").contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.formatarCnpj(cnpj), "Padaria Pão Quente LTDA", "Pão Quente")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/clientes/")))
                .andExpect(jsonPath("$.cnpj").value(cnpj))
                .andExpect(jsonPath("$.razaoSocial").value("Padaria Pão Quente LTDA"))
                .andExpect(jsonPath("$.nomeFantasia").value("Pão Quente"))
                .andExpect(jsonPath("$.ativo").value(true))
                .andExpect(jsonPath("$.criadoEm").exists());
    }

    @Test
    void recusaCnpjComDigitoVerificadorErrado() throws Exception {
        String invalido = GeradorDeDocumentos.comDigitoVerificadorErrado(GeradorDeDocumentos.cnpj());

        mvc.perform(post("/api/clientes").contentType(MediaType.APPLICATION_JSON).content(json(invalido, "Empresa X", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Dados inválidos"))
                .andExpect(jsonPath("$.erros[0].campo").value("cnpj"))
                .andExpect(jsonPath("$.erros[0].mensagem").value("CNPJ inválido"));
    }

    @Test
    void recusaRazaoSocialEmBranco() throws Exception {
        mvc.perform(post("/api/clientes").contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.cnpj(), "  ", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("razaoSocial"));
    }

    @Test
    void recusaCorpoQueNaoEJson() throws Exception {
        mvc.perform(post("/api/clientes").contentType(MediaType.APPLICATION_JSON).content("{isto nao e json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Corpo da requisição ilegível"));
    }

    @Test
    void recusaCnpjDuplicadoComConflito() throws Exception {
        String cnpj = GeradorDeDocumentos.cnpj();
        criar(cnpj, "Primeira LTDA");

        mvc.perform(post("/api/clientes").contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.formatarCnpj(cnpj), "Segunda LTDA", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflito"))
                .andExpect(jsonPath("$.detail").value(containsString(cnpj)));
    }

    @Test
    void buscaPorIdEDevolve404QuandoNaoExiste() throws Exception {
        long id = criar(GeradorDeDocumentos.cnpj(), "Busca LTDA");

        mvc.perform(get("/api/clientes/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.razaoSocial").value("Busca LTDA"));

        mvc.perform(get("/api/clientes/{id}", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Não encontrado"));
    }

    @Test
    void atualizaRazaoSocialMasNaoAceitaTrocarOCnpj() throws Exception {
        String cnpj = GeradorDeDocumentos.cnpj();
        long id = criar(cnpj, "Nome Antigo LTDA");

        mvc.perform(put("/api/clientes/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json(cnpj, "Nome Novo LTDA", "Fantasia Nova")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razaoSocial").value("Nome Novo LTDA"))
                .andExpect(jsonPath("$.nomeFantasia").value("Fantasia Nova"));

        mvc.perform(put("/api/clientes/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content(json(GeradorDeDocumentos.cnpj(), "Nome Novo LTDA", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Regra de negócio violada"))
                .andExpect(jsonPath("$.detail").value(containsString("CNPJ")));
    }

    @Test
    void inativaEReativaCliente() throws Exception {
        long id = criar(GeradorDeDocumentos.cnpj(), "Liga Desliga LTDA");

        mvc.perform(post("/api/clientes/{id}/inativar", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(false));

        mvc.perform(post("/api/clientes/{id}/reativar", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ativo").value(true));
    }

    @Test
    void excluiClienteSemVinculos() throws Exception {
        long id = criar(GeradorDeDocumentos.cnpj(), "Descartavel LTDA");

        mvc.perform(delete("/api/clientes/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(get("/api/clientes/{id}", id)).andExpect(status().isNotFound());
    }

    @Test
    void listaComFiltroOrdenacaoEPaginacao() throws Exception {
        String marca = "Marca" + ThreadLocalRandom.current().nextInt(1_000_000);
        criar(GeradorDeDocumentos.cnpj(), marca + " Alfa");
        criar(GeradorDeDocumentos.cnpj(), marca + " Beta");
        criar(GeradorDeDocumentos.cnpj(), marca + " Gama");
        criar(GeradorDeDocumentos.cnpj(), "Outro Nome Qualquer");

        mvc.perform(get("/api/clientes").param("razaoSocial", marca.toLowerCase()).param("size", "2").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPaginas").value(2))
                .andExpect(jsonPath("$.tamanho").value(2))
                .andExpect(jsonPath("$.itens.length()").value(2))
                .andExpect(jsonPath("$.itens[0].razaoSocial").value(marca + " Alfa"));

        mvc.perform(get("/api/clientes").param("razaoSocial", marca).param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andExpect(jsonPath("$.itens[0].razaoSocial").value(marca + " Gama"));
    }

    @Test
    void filtraPorCnpjComOuSemPontuacaoEPorSituacao() throws Exception {
        String cnpj = GeradorDeDocumentos.cnpj();
        long id = criar(cnpj, "Filtro Exato LTDA");
        mvc.perform(post("/api/clientes/{id}/inativar", id)).andExpect(status().isOk());

        mvc.perform(get("/api/clientes").param("cnpj", GeradorDeDocumentos.formatarCnpj(cnpj)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mvc.perform(get("/api/clientes").param("cnpj", cnpj).param("ativo", "true"))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/clientes").param("cnpj", cnpj).param("ativo", "false"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void recusaOrdenarPorCampoQueNaoExiste() throws Exception {
        mvc.perform(get("/api/clientes").param("sort", "campoInexistente,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Ordenação inválida"));
    }

    @Test
    void rotaInexistenteTambemSaiNoFormatoPadraoDeErro() throws Exception {
        mvc.perform(get("/api/isto-nao-existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void parametroComTipoErradoViraErro400() throws Exception {
        mvc.perform(get("/api/clientes/{id}", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(endsWith("tem um valor com tipo errado.")));
    }
}
