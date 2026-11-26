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
 * Toda a /api/** exige um token valido, menos as cerimonias que levam a um token (pedir desafio,
 * entrar com passkey, aceitar convite, usar codigo de recuperacao). Regras:
 * <ul>
 *   <li>o token so e aceito no cabecalho {@code Authorization: Bearer kw_...}. Token na URL e
 *       recusado. (Quem manda o cabecalho e o BFF; o navegador nem ve o token.)</li>
 *   <li>token ausente, malformado, inexistente, vencido ou revogado recebem a MESMA resposta 401;</li>
 *   <li>token de escopo CADASTRAR_PASSKEY (veio de um codigo de recuperacao) so serve para cadastrar
 *       uma passkey nova, ver as passkeys, sair e ver a propria sessao: o resto da 403.</li>
 * </ul>
 * Roda depois do registro de acesso, da checagem de HTTPS e da checagem do BFF.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AutenticacaoFilter extends OncePerRequestFilter {

    /** Cerimonias que nao exigem token (todas POST). */
    static final Set<String> SEM_TOKEN = Set.of("/api/auth/passkey/login/opcoes", "/api/auth/passkey/login",
            "/api/auth/convite/opcoes", "/api/auth/convite", "/api/auth/recuperacao",
            "/api/auth/senha/login", "/api/auth/senha/segundo-fator", "/api/auth/senha/esqueci", "/api/auth/senha/redefinir",
            "/api/auth/convite/senha");
    /** Quem entrou com codigo de recuperacao: cadastra uma passkey nova OU define uma senha nova. */
    private static final Set<String> PERMITIDOS_SO_PARA_CADASTRAR = Set.of("/api/auth/passkeys", "/api/auth/passkeys/opcoes",
            "/api/auth/logout", "/api/auth/eu", "/api/auth/senha");
    /** Administrador que entrou com senha e ainda nao tem 2FA: so ativa o 2FA (e le os termos). */
    private static final Set<String> PERMITIDOS_SO_PARA_2FA = Set.of("/api/auth/2fa/iniciar", "/api/auth/2fa/ativar",
            "/api/auth/logout", "/api/auth/eu", "/api/auth/termos", "/api/auth/termos/aceite");

    private final Tokens tokens;
    private final ParametrosDeSeguranca parametros;

    public AutenticacaoFilter(Tokens tokens, ParametrosDeSeguranca parametros) {
        this.tokens = tokens;
        this.parametros = parametros;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest requisicao) {
        String caminho = requisicao.getRequestURI();
        return !caminho.startsWith("/api/") || ("POST".equals(requisicao.getMethod()) && SEM_TOKEN.contains(caminho));
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
            recusar(resposta, 401, "Não autenticado", "Entre com a sua senha ou passkey.");
            return;
        }
        if ("CADASTRAR_PASSKEY".equals(sessao.get().escopo())
                && !PERMITIDOS_SO_PARA_CADASTRAR.contains(requisicao.getRequestURI())) {
            recusar(resposta, 403, "Cadastre uma passkey", "Você entrou com um código de recuperação: cadastre uma passkey nova "
                    + "e entre com ela para continuar.");
            return;
        }
        if ("CONFIGURAR_2FA".equals(sessao.get().escopo()) && !PERMITIDOS_SO_PARA_2FA.contains(requisicao.getRequestURI())) {
            recusar(resposta, 403, "Ative o 2FA", "Administrador precisa do segundo fator: ative o app autenticador para continuar.");
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
