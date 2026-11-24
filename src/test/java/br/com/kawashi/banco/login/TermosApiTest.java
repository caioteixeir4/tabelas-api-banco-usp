package br.com.kawashi.banco.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import br.com.kawashi.banco.testes.Api;
import br.com.kawashi.banco.testes.IntegracaoApi;
import br.com.kawashi.banco.testes.UsuarioDeTeste;

/**
 * LGPD: sem aceitar as versoes vigentes dos termos de uso e da politica de privacidade, nenhuma rota
 * de negocio responde (403); o login continua (para a pessoa poder ler e aceitar). O aceite guarda a
 * versao, a hora e o IP, e vai para o LOG_SEGURANCA. (Publicar versao nova e coisa do DBA: o teste
 * disso fica no repositorio de regras.)
 */
@IntegracaoApi
class TermosApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UsuarioDeTeste usuarios;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SchemasDoLogin schemas;

    @Test
    void semAceiteNadaDeNegocioDepoisDoAceiteTudoNormal() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novoSemAceite();
        Api api = usuarios.api(mvc, u);

        api.get("/api/empresas/" + u.empresaId()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Aceite os termos"));
        api.get("/api/auth/eu").andExpect(status().isOk()).andExpect(jsonPath("$.termosPendentes").value(true));

        String termos = Api.corpo(api.get("/api/auth/termos").andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.aceito == true)]", hasSize(0))));
        List<Integer> ids = JsonPath.read(termos, "$[*].id");
        List<String> hashes = JsonPath.read(termos, "$[*].hashTexto");
        assertThat(hashes).allMatch(h -> h.matches("[0-9a-f]{64}"));

        // Aceitar so um dos dois: continua pendente.
        api.post("/api/auth/termos/aceite", "{\"termos\":[" + ids.get(0) + "]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.termosPendentes").value(true));
        api.get("/api/empresas/" + u.empresaId()).andExpect(status().isForbidden());

        api.post("/api/auth/termos/aceite", "{\"termos\":[" + ids.get(0) + "," + ids.get(1) + "]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.termosPendentes").value(false));
        api.get("/api/empresas/" + u.empresaId()).andExpect(status().isOk());
        api.get("/api/auth/eu").andExpect(jsonPath("$.termosPendentes").value(false));

        // Um aceite por versao (o repetido nao duplicou), com IP e hora; e cada um no LOG_SEGURANCA.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.login("ACEITE_TERMO")
                + " WHERE USUARIO_ID = ? AND IP_ORIGEM = ? AND ACEITO_EM IS NOT NULL", Integer.class, u.usuarioId(), Api.IP_DE_TESTE))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.log("LOG_SEGURANCA")
                + " WHERE EVENTO = 'TERMO_ACEITO' AND USUARIO_ID = ?", Integer.class, u.usuarioId())).isEqualTo(2);
    }

    @Test
    void aceitarVersaoQueNaoEaVigenteE422ENadaEGravado() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novoSemAceite();
        Api api = usuarios.api(mvc, u);
        Integer vigente = JsonPath.read(Api.corpo(api.get("/api/auth/termos")), "$[0].id");

        api.post("/api/auth/termos/aceite", "{\"termos\":[" + vigente + ",999999999]}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Termo inválido"));
        api.post("/api/auth/termos/aceite", "{\"termos\":[]}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.login("ACEITE_TERMO") + " WHERE USUARIO_ID = ?",
                Integer.class, u.usuarioId())).isZero();
    }

    @Test
    void semAceiteNemARotaDaPlataformaResponde() throws Exception {
        UsuarioDeTeste.Criado u = usuarios.novoSemAceite();
        usuarios.api(mvc, u).get("/api/plataforma/empresas").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Aceite os termos"));
    }
}
