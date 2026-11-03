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
 * Recusa, antes de qualquer leitura do corpo, requisicao que DECLARA um corpo maior que o limite
 * (413, no mesmo formato RFC 9457 dos demais erros). Sem isso, um JSON de dezenas de megabytes era
 * lido inteiro e so depois recusado pela validacao. O limite padrao (64 KiB) e folgado: o maior
 * corpo legitimo da API tem poucas centenas de bytes.
 *
 * <p>Cobre o Content-Length declarado; corpo em transferencia fragmentada (chunked) nao declara
 * tamanho e fica sujeito so ao limite interno do leitor de JSON. Um limite completo para esse caso
 * pertence ao proxy reverso na frente da aplicacao.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LimiteDeCorpoFilter extends OncePerRequestFilter {

    private final long limiteEmBytes;

    public LimiteDeCorpoFilter(@Value("${app.limite-corpo-bytes:65536}") long limiteEmBytes) {
        this.limiteEmBytes = limiteEmBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        if (requisicao.getContentLengthLong() > limiteEmBytes) {
            resposta.setStatus(413);
            resposta.setContentType("application/problem+json");
            resposta.setCharacterEncoding("UTF-8");
            resposta.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Corpo grande demais\",\"status\":413,"
                    + "\"detail\":\"O corpo da requisição passa do limite de " + limiteEmBytes + " bytes.\"}");
            return;
        }
        cadeia.doFilter(requisicao, resposta);
    }
}
