package br.com.kawashi.banco.login;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Toda a /api/** exige um token valido, menos o proprio login. Regras:
 * <ul>
 *   <li>o token so e aceito no cabecalho {@code Authorization: Bearer kw_...}. Token na URL (que
 *       iria parar em logs, historico do navegador e proxies) e recusado;</li>
 *   <li>token ausente, malformado, inexistente, vencido ou revogado recebem a MESMA resposta 401:
 *       quem testa tokens nao aprende nada;</li>
 *   <li>token de escopo TROCAR_SENHA (senha vencida) so serve para trocar a senha, sair e ver a
 *       propria sessao: o resto da 403.</li>
 * </ul>
 * Roda depois do registro de acesso (que assim fica sabendo quem chamou) e da checagem de HTTPS
 * (um token nunca deve ser conferido se chegou por HTTP puro).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AutenticacaoFilter extends OncePerRequestFilter {

    static final String LOGIN = "/api/auth/login";
    private static final Set<String> PERMITIDOS_COM_SENHA_VENCIDA = Set.of("/api/auth/trocar-senha", "/api/auth/logout",
            "/api/auth/eu");

    private final Tokens tokens;
    private final ParametrosDeSeguranca parametros;

    public AutenticacaoFilter(Tokens tokens, ParametrosDeSeguranca parametros) {
        this.tokens = tokens;
        this.parametros = parametros;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest requisicao) {
        String caminho = requisicao.getRequestURI();
        return !caminho.startsWith("/api/") || ("POST".equals(requisicao.getMethod()) && LOGIN.equals(caminho));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        String consulta = requisicao.getQueryString();
        if (consulta != null && consulta.matches("(?i)(^|.*&)(access_token|token)=.*")) {
            recusar(resposta, 401, "Não autenticado", "O token só é aceito no cabeçalho Authorization, nunca na URL.");
            return;
        }
        String cabecalho = requisicao.getHeader("Authorization");
        String token = cabecalho != null && cabecalho.regionMatches(true, 0, "Bearer ", 0, 7) ? cabecalho.substring(7).strip() : null;
        Optional<Tokens.Sessao> sessao = token == null ? Optional.empty() : tokens.validar(token, parametros.atuais());
        if (sessao.isEmpty()) {
            recusar(resposta, 401, "Não autenticado", "Faça login e envie o token no cabeçalho Authorization: Bearer <token>.");
            return;
        }
        if ("TROCAR_SENHA".equals(sessao.get().escopo()) && !PERMITIDOS_COM_SENHA_VENCIDA.contains(requisicao.getRequestURI())) {
            recusar(resposta, 403, "Senha vencida", "A senha venceu. Troque a senha em POST /api/auth/trocar-senha para continuar.");
            return;
        }
        SessaoAtual.guardar(requisicao, sessao.get());
        cadeia.doFilter(requisicao, resposta);
    }

    private static void recusar(HttpServletResponse resposta, int status, String titulo, String detalhe) throws IOException {
        resposta.setStatus(status);
        if (status == 401) {
            resposta.setHeader("WWW-Authenticate", "Bearer realm=\"kawashi\", error=\"invalid_token\"");
        }
        resposta.setContentType("application/problem+json");
        resposta.setCharacterEncoding("UTF-8");
        resposta.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + titulo + "\",\"status\":" + status
                + ",\"detail\":\"" + detalhe + "\"}");
    }
}
