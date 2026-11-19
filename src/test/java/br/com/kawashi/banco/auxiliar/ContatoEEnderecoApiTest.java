package br.com.kawashi.banco.auxiliar;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import br.com.kawashi.banco.testes.Api;
import br.com.kawashi.banco.testes.UsuarioDeTeste;
import br.com.kawashi.banco.testes.IntegracaoApi;

/**
 * Contato e endereco sao tabelas auxiliares com as mesmas regras de dono: servem a uma filial ou a
 * uma pessoa. Os testes parametrizados rodam uma vez com cada tipo de dono.
 */
@IntegracaoApi
class ContatoEEnderecoApiTest {

    private static final String ENDERECO = "{\"logradouro\":\"Rua das Flores\",\"numero\":\"10\",\"cidade\":\"São Paulo\",\"uf\":\"sp\",\"cep\":\"01001-000\"%s}";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioDeTeste usuarios;
    private Api api;

    @BeforeEach
    void preparar() {
        api = new Api(mvc, usuarios.token());
    }

    /** Caminho base do dono ("/api/filiais/7" ou "/api/pessoas/7"), criando o dono. */
    private String dono(String tipo) throws Exception {
        return tipo.equals("filiais") ? "/api/filiais/" + api.filial() : "/api/pessoas/" + api.pessoa();
    }

    private static String endereco(String extra) {
        return ENDERECO.formatted(extra);
    }

    // ---- contato -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"filiais", "pessoas"})
    void contatoNormalizaOValorEMarcaODono(String tipoDeDono) throws Exception {
        String dono = dono(tipoDeDono);
        String campoDoDono = tipoDeDono.equals("filiais") ? "$.filialId" : "$.pessoaId";
        String campoVazio = tipoDeDono.equals("filiais") ? "$.pessoaId" : "$.filialId";

        api.post(dono + "/contatos", "{\"tipo\":\"email\",\"valor\":\"  FULANO@Exemplo.com.br \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("EMAIL"))
                .andExpect(jsonPath("$.valor").value("f***@exemplo.com.br"))
                .andExpect(jsonPath("$.principal").value(true))
                .andExpect(jsonPath(campoDoDono).isNumber())
                .andExpect(jsonPath(campoVazio).doesNotExist());
        api.post(dono + "/contatos", "{\"tipo\":\"TELEFONE\",\"valor\":\"(11) 98765-4321\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.valor").value("(11) *****-4321"));
        api.get(dono + "/contatos").andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void valorPrecisaCombinarComOTipo() throws Exception {
        String contatos = dono("pessoas") + "/contatos";
        api.post(contatos, "{\"tipo\":\"EMAIL\",\"valor\":\"sem-arroba\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("valor"))
                .andExpect(jsonPath("$.erros[0].mensagem").value("E-mail inválido"));
        api.post(contatos, "{\"tipo\":\"TELEFONE\",\"valor\":\"1234\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.erros[0].campo").value("valor"));
        api.post(contatos, "{\"tipo\":\"TELEFONE\",\"valor\":\"fulano@exemplo.com.br\"}").andExpect(status().isBadRequest());
        api.post(contatos, "{\"tipo\":\"FAX\",\"valor\":\"1133334444\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.erros[0].campo").value("tipo"));
        api.post(contatos, "{\"tipo\":\"EMAIL\",\"valor\":\" \"}").andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"filiais", "pessoas"})
    void haUmPrincipalPorTipoDeContato(String tipoDeDono) throws Exception {
        String contatos = dono(tipoDeDono) + "/contatos";
        long email1 = api.criar(contatos, "{\"tipo\":\"EMAIL\",\"valor\":\"a@exemplo.com.br\",\"principal\":false}");
        long telefone = api.criar(contatos, "{\"tipo\":\"TELEFONE\",\"valor\":\"1133334444\"}");
        long email2 = api.criar(contatos, "{\"tipo\":\"EMAIL\",\"valor\":\"b@exemplo.com.br\",\"principal\":true}");

        // o primeiro de cada tipo nasce principal; o segundo e-mail assumiu e rebaixou o primeiro;
        // o telefone nao foi afetado pelo e-mail
        api.get("/api/contatos/" + email1).andExpect(jsonPath("$.principal").value(false));
        api.get("/api/contatos/" + email2).andExpect(jsonPath("$.principal").value(true));
        api.get("/api/contatos/" + telefone).andExpect(jsonPath("$.principal").value(true));

        // o principal nao pode ser desmarcado nem excluido enquanto houver outro do mesmo tipo
        api.put("/api/contatos/" + email2, "{\"tipo\":\"EMAIL\",\"valor\":\"b@exemplo.com.br\",\"principal\":false}")
                .andExpect(status().isUnprocessableEntity());
        api.delete("/api/contatos/" + email2).andExpect(status().isUnprocessableEntity());
        // promover o outro resolve
        api.put("/api/contatos/" + email1, "{\"tipo\":\"EMAIL\",\"valor\":\"a@exemplo.com.br\",\"principal\":true}")
                .andExpect(status().isOk());
        api.delete("/api/contatos/" + email2).andExpect(status().isNoContent());
        // o unico telefone e principal e pode sair
        api.delete("/api/contatos/" + telefone).andExpect(status().isNoContent());
    }

    @Test
    void contatoNaoRepeteNoMesmoDonoNemTrocaDeTipo() throws Exception {
        String dono = dono("pessoas");
        long id = api.criar(dono + "/contatos", "{\"tipo\":\"EMAIL\",\"valor\":\"a@exemplo.com.br\"}");
        api.post(dono + "/contatos", "{\"tipo\":\"EMAIL\",\"valor\":\"A@EXEMPLO.com.br\"}").andExpect(status().isConflict());
        // o mesmo e-mail em OUTRO dono e permitido
        api.criar(dono("filiais") + "/contatos", "{\"tipo\":\"EMAIL\",\"valor\":\"a@exemplo.com.br\"}");
        api.put("/api/contatos/" + id, "{\"tipo\":\"TELEFONE\",\"valor\":\"1133334444\",\"principal\":true}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(containsString("tipo")));
        api.put("/api/contatos/" + id, "{\"tipo\":\"EMAIL\",\"valor\":\"novo@exemplo.com.br\",\"principal\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.valor").value("n***@exemplo.com.br"));
    }

    // ---- endereco ------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"filiais", "pessoas"})
    void enderecoNormalizaUfECepETemUmSoPrincipal(String tipoDeDono) throws Exception {
        String enderecos = dono(tipoDeDono) + "/enderecos";
        long primeiro = Api.id(api.post(enderecos, endereco(",\"principal\":false"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uf").value("SP"))
                .andExpect(jsonPath("$.cep").value("01001000"))
                .andExpect(jsonPath("$.principal").value(true)));
        long segundo = api.criar(enderecos, endereco(",\"principal\":true"));

        api.get("/api/enderecos/" + primeiro).andExpect(jsonPath("$.principal").value(false));
        api.get(enderecos).andExpect(jsonPath("$.total").value(2));
        api.put("/api/enderecos/" + segundo, endereco("")).andExpect(status().isUnprocessableEntity());
        api.delete("/api/enderecos/" + segundo).andExpect(status().isUnprocessableEntity());
        api.delete("/api/enderecos/" + primeiro).andExpect(status().isNoContent());
        api.delete("/api/enderecos/" + segundo).andExpect(status().isNoContent());
    }

    @Test
    void enderecoValidaUfCepETamanhoEmLetrasAcentuadas() throws Exception {
        String enderecos = dono("filiais") + "/enderecos";
        api.post(enderecos, "{\"logradouro\":\"Rua\",\"cidade\":\"X\",\"uf\":\"XX\",\"cep\":\"01001000\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.erros[0].campo").value("uf"));
        api.post(enderecos, "{\"logradouro\":\"Rua\",\"cidade\":\"X\",\"uf\":\"SP\",\"cep\":\"123\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.erros[0].campo").value("cep"));
        api.post(enderecos, "{\"logradouro\":\"" + "é".repeat(200) + "\",\"cidade\":\"" + "ã".repeat(100) + "\",\"uf\":\"SP\",\"cep\":\"01001000\"}")
                .andExpect(status().isCreated());
        api.post(enderecos, "{\"logradouro\":\"" + "é".repeat(201) + "\",\"cidade\":\"X\",\"uf\":\"SP\",\"cep\":\"01001000\"}")
                .andExpect(status().isBadRequest());
    }

    // ---- regras do dono, iguais para as duas tabelas ---------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"filiais", "pessoas"})
    void donoInexistenteOuInativoNaoRecebeContatoNemEndereco(String tipoDeDono) throws Exception {
        String inexistente = "/api/" + tipoDeDono + "/999999999";
        api.post(inexistente + "/contatos", "{\"tipo\":\"EMAIL\",\"valor\":\"a@exemplo.com.br\"}").andExpect(status().isNotFound());
        api.post(inexistente + "/enderecos", endereco("")).andExpect(status().isNotFound());
        api.get(inexistente + "/contatos").andExpect(status().isNotFound());
        api.get(inexistente + "/enderecos").andExpect(status().isNotFound());

        String dono = dono(tipoDeDono);
        long contato = api.criar(dono + "/contatos", "{\"tipo\":\"EMAIL\",\"valor\":\"a@exemplo.com.br\"}");
        api.post(dono + "/inativar").andExpect(status().isOk());
        api.post(dono + "/contatos", "{\"tipo\":\"EMAIL\",\"valor\":\"b@exemplo.com.br\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(containsString("inativa")));
        api.post(dono + "/enderecos", endereco("")).andExpect(status().isUnprocessableEntity());
        // dono inativo so barra a CRIACAO: alterar e excluir o que ja existe continua permitido
        api.put("/api/contatos/" + contato, "{\"tipo\":\"EMAIL\",\"valor\":\"c@exemplo.com.br\",\"principal\":true}")
                .andExpect(status().isOk());
        api.delete("/api/contatos/" + contato).andExpect(status().isNoContent());
    }

    @Test
    void contatoEEnderecoInexistentesDevolvem404() throws Exception {
        api.get("/api/contatos/999999999").andExpect(status().isNotFound());
        api.get("/api/enderecos/999999999").andExpect(status().isNotFound());
        api.delete("/api/enderecos/999999999").andExpect(status().isNotFound());
    }
}
