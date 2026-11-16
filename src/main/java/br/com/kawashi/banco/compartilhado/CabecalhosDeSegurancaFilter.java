package br.com.kawashi.banco.compartilhado;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Duas protecoes de transporte, em toda resposta:
 * <ul>
 *   <li><b>HTTPS obrigatorio</b> (quando {@code app.exigir-https=true}): pedido a /api/** que chegou
 *       em HTTP puro e recusado com 403, sem ser processado. Um token ou um CPF que trafega aberto
 *       pode ser lido por qualquer um no caminho; de nada adianta o resto da seguranca. Atras de um
 *       proxy reverso, vale o cabecalho X-Forwarded-Proto (ver server.forward-headers-strategy);</li>
 *   <li><b>cabecalhos de seguranca</b>: a resposta nunca e guardada em cache (ela carrega dado
 *       pessoal), o navegador nao tenta adivinhar o tipo do conteudo, a API nao pode ser embutida
 *       em outra pagina e, em HTTPS, o navegador passa a recusar HTTP para este endereco (HSTS).</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class CabecalhosDeSegurancaFilter extends OncePerRequestFilter {

    private final boolean exigirHttps;

    public CabecalhosDeSegurancaFilter(@Value("${app.exigir-https:false}") boolean exigirHttps) {
        this.exigirHttps = exigirHttps;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        resposta.setHeader("X-Content-Type-Options", "nosniff");
        resposta.setHeader("X-Frame-Options", "DENY");
        resposta.setHeader("Referrer-Policy", "no-referrer");
        resposta.setHeader("Cache-Control", "no-store");
        if (requisicao.isSecure()) {
            resposta.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        } else if (exigirHttps && requisicao.getRequestURI().startsWith("/api/")) {
            resposta.setStatus(403);
            resposta.setContentType("application/problem+json");
            resposta.setCharacterEncoding("UTF-8");
            resposta.getWriter().write("{\"type\":\"about:blank\",\"title\":\"HTTPS obrigatório\",\"status\":403,"
                    + "\"detail\":\"Esta API só aceita conexões HTTPS.\"}");
            return;
        }
        cadeia.doFilter(requisicao, resposta);
    }
}
