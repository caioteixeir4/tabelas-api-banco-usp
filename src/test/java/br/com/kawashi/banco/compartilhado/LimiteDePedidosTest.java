package br.com.kawashi.banco.compartilhado;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import br.com.kawashi.banco.testes.Api;

/**
 * Com um limite baixo (5 por minuto), o sexto pedido do mesmo IP de navegador recebe 429 com
 * Retry-After, e outro IP continua sendo atendido. Os outros testes rodam com o limite alto.
 */
@SpringBootTest(properties = {"app.exigir-https=false", "server.ssl.enabled=false", "app.limite-pedidos.por-minuto=5"})
@AutoConfigureMockMvc
class LimiteDePedidosTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void passouDoLimiteRecebe429SoAqueleIp() throws Exception {
        String ip = "203.0.113." + (1 + (int) (Math.random() * 250));
        for (int i = 0; i < 5; i++) {
            // sem token: 401, mas o pedido conta
            mvc.perform(Api.doBff(get("/api/empresas"), ip)).andExpect(status().isUnauthorized());
        }
        mvc.perform(Api.doBff(get("/api/empresas"), ip)).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.title").value("Muitos pedidos"));
        mvc.perform(Api.doBff(get("/api/empresas"), "198.51.100.200")).andExpect(status().isUnauthorized());
    }
}
