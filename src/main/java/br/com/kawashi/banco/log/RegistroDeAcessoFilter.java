package br.com.kawashi.banco.log;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Grava em LOG_ACESSO cada chamada a API: de qual IP, metodo, caminho e status devolvido. So o
 * caminho entra, nunca a query string nem o corpo, para nao levar dado pessoal para o log.
 *
 * <p>Uma falha ao gravar o log nao derruba a resposta: a chamada ja foi atendida. Ela vai para o
 * log da aplicacao como aviso.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RegistroDeAcessoFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RegistroDeAcessoFilter.class);
    private static final int TAMANHO_DO_CAMINHO = 500;

    private final JdbcTemplate jdbc;
    private final SchemaDeLog schema;

    public RegistroDeAcessoFilter(JdbcTemplate jdbc, SchemaDeLog schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest requisicao) {
        // So a API e registrada; a checagem de saude e chamada o tempo todo e nao diz nada.
        return !requisicao.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        try {
            cadeia.doFilter(requisicao, resposta);
        } finally {
            registrar(requisicao, resposta);
        }
    }

    private void registrar(HttpServletRequest requisicao, HttpServletResponse resposta) {
        try {
            String caminho = requisicao.getRequestURI();
            if (caminho.length() > TAMANHO_DO_CAMINHO) {
                caminho = caminho.substring(0, TAMANHO_DO_CAMINHO);
            }
            jdbc.update("INSERT INTO " + schema.tabela("LOG_ACESSO")
                    + " (USUARIO, IP_ORIGEM, METODO, CAMINHO, STATUS) VALUES (?, ?, ?, ?, ?)",
                    null, requisicao.getRemoteAddr(), requisicao.getMethod(), caminho, resposta.getStatus());
        } catch (RuntimeException ex) {
            LOG.warn("Não foi possível gravar o LOG_ACESSO: {}", ex.getMessage());
        }
    }
}
