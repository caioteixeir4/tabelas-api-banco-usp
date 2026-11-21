package br.com.kawashi.banco.login;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

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
 * A API so atende o BFF (o servidor do site). Todo pedido a /api/** precisa do cabecalho
 * {@code X-Kawashi-Bff} com o segredo compartilhado ({@code app.bff.segredo}); sem ele, 403, antes
 * de qualquer outra coisa (inclusive do login). Assim o navegador nunca fala direto com a API: o
 * token fica guardado no BFF, e quem achar o endereco da API nao consegue nem pedir um desafio de
 * login.
 *
 * <p><b>IP do navegador.</b> Como todo pedido chega do BFF, o endereco de origem da conexao e sempre
 * o dele. O BFF manda o IP de quem esta no navegador em {@code X-Kawashi-Ip-Cliente}, e a API so
 * acredita nesse cabecalho DEPOIS de conferir o segredo (de outro jeito, qualquer um inventaria o
 * proprio IP e fugiria do bloqueio). Sem ele (ou com algo que nao e um IP), 400: sem o IP real, o
 * bloqueio por IP do login bloquearia o BFF inteiro, isto e, todo mundo de uma vez.
 * {@link #ipDoCliente} e o IP que o login e o LOG_ACESSO usam.
 *
 * <p>Comparacao do segredo em tempo constante. Segredo curto demais (menos de 32 caracteres)
 * impede a API de subir: na duvida, ela nao atende. Em producao, a API tambem nao deve ser
 * alcancavel pela rede de fora (so o BFF a enxerga); o segredo e a segunda barreira.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class FiltroDoBff extends OncePerRequestFilter {

    public static final String CABECALHO = "X-Kawashi-Bff";
    public static final String CABECALHO_IP = "X-Kawashi-Ip-Cliente";
    private static final String ATRIBUTO_IP = FiltroDoBff.class.getName() + ".ip";
    /** IPv4 ou IPv6 escrito por extenso ou abreviado; nada de nome, porta ou lista. */
    private static final Pattern IP = Pattern.compile("[0-9]{1,3}([.][0-9]{1,3}){3}|[0-9A-Fa-f:]{2,39}(%[0-9A-Za-z]{1,15})?");

    private final byte[] segredo;

    public FiltroDoBff(@Value("${app.bff.segredo:}") String segredo) {
        if (segredo == null || segredo.strip().length() < 32) {
            throw new IllegalStateException("app.bff.segredo (variavel BFF_SEGREDO) ausente ou curto: a API so atende o BFF, "
                    + "e o segredo compartilhado precisa de pelo menos 32 caracteres.");
        }
        this.segredo = segredo.strip().getBytes(StandardCharsets.UTF_8);
    }

    /** O IP do navegador informado pelo BFF (ja conferido); fora da /api, o da conexao. */
    public static String ipDoCliente(HttpServletRequest requisicao) {
        Object ip = requisicao.getAttribute(ATRIBUTO_IP);
        return ip instanceof String s ? s : requisicao.getRemoteAddr();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest requisicao) {
        return !requisicao.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        String informado = requisicao.getHeader(CABECALHO);
        if (informado == null || !MessageDigest.isEqual(segredo, informado.getBytes(StandardCharsets.UTF_8))) {
            recusar(resposta, 403, "Acesso negado", "Esta API só atende o servidor do site.");
            return;
        }
        String ip = requisicao.getHeader(CABECALHO_IP);
        if (ip == null || !IP.matcher(ip.strip()).matches()) {
            recusar(resposta, 400, "Pedido inválido", "Falta o IP do navegador (" + CABECALHO_IP + ").");
            return;
        }
        requisicao.setAttribute(ATRIBUTO_IP, ip.strip());
        cadeia.doFilter(requisicao, resposta);
    }

    private static void recusar(HttpServletResponse resposta, int status, String titulo, String detalhe) throws IOException {
        resposta.setStatus(status);
        resposta.setContentType("application/problem+json");
        resposta.setCharacterEncoding("UTF-8");
        resposta.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + titulo + "\",\"status\":" + status
                + ",\"detail\":\"" + detalhe + "\"}");
    }
}
