package br.com.kawashi.banco.compartilhado;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Recusa (400) o caminho que nao esta na forma canonica, ANTES de qualquer outro filtro.
 *
 * <p>Os filtros decidem pelo caminho cru ({@code getRequestURI().startsWith("/api/")}), mas o Spring
 * MVC casa a rota pelo caminho decodificado e sem parametros de caminho. Sem esta barreira,
 * {@code /api;x/auth/...} ou {@code /%61pi/auth/...} chegariam ao controlador sem passar pelo segredo
 * do BFF, pelo log de acesso e pelo limite de pedidos (achado da revisao de 23/11/2026). O unico
 * cliente e o BFF, que so repassa caminhos de uma lista fechada (sem {@code ;}, {@code %} nem
 * {@code ..}): nenhum pedido legitimo cai aqui.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CaminhoCanonicoFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(CaminhoCanonicoFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        if (!canonico(requisicao)) {
            LOG.warn("Caminho fora da forma canonica recusado: {}", requisicao.getRequestURI().replaceAll("[^\\x21-\\x7e]", "?"));
            resposta.setStatus(400);
            resposta.setContentType("application/problem+json");
            resposta.setCharacterEncoding("UTF-8");
            resposta.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Caminho inválido\",\"status\":400,"
                    + "\"detail\":\"O endereço do pedido não está na forma esperada.\"}");
            return;
        }
        cadeia.doFilter(requisicao, resposta);
    }

    /** Sem ;, %, \, //, /./ ou /../, e o caminho cru igual ao que o servidor entendeu. */
    static boolean canonico(HttpServletRequest requisicao) {
        String cru = requisicao.getRequestURI();
        if (cru == null || cru.isEmpty() || cru.indexOf(';') >= 0 || cru.indexOf('%') >= 0 || cru.indexOf('\\') >= 0
                || cru.contains("//") || cru.contains("/./") || cru.contains("/../") || cru.endsWith("/.") || cru.endsWith("/..")) {
            return false;
        }
        String entendido = requisicao.getContextPath() + requisicao.getServletPath()
                + (requisicao.getPathInfo() == null ? "" : requisicao.getPathInfo());
        return cru.equals(entendido);
    }
}
