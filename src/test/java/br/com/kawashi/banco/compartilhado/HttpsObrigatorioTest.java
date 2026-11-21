package br.com.kawashi.banco.compartilhado;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Com {@code app.exigir-https=true} (como deve rodar fora do desenvolvimento), a API nao processa
 * pedido que chegou em HTTP puro.
 */
@SpringBootTest(properties = {"app.exigir-https=true", "server.ssl.enabled=false"})
@AutoConfigureMockMvc
@Transactional
class HttpsObrigatorioTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private br.com.kawashi.banco.testes.UsuarioDeTeste usuarios;

    @Test
    void pedidoEmHttpPuroEhRecusadoSemSerProcessado() throws Exception {
        mvc.perform(get("/api/empresas"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("HTTPS obrigatório"));
    }

    @Test
    void pedidoEmHttpsPassaEGanhaHsts() throws Exception {
        mvc.perform(br.com.kawashi.banco.testes.Api.doBff(get("/api/empresas")).secure(true).header("Authorization", "Bearer " + usuarios.token()))
                .andExpect(status().isOk())
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000; includeSubDomains"));
    }

    @Test
    void checagemDeSaudeContinuaAcessivelEmHttp() throws Exception {
        // So a API e protegida: o monitor interno consulta a saude sem TLS.
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void todaRespostaLevaOsCabecalhosDeSeguranca() throws Exception {
        mvc.perform(br.com.kawashi.banco.testes.Api.doBff(get("/api/empresas")).secure(true).header("Authorization", "Bearer " + usuarios.token()))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }
}
