package br.com.kawashi.banco.login;

import java.util.Optional;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Quem esta chamando, neste pedido. O {@link AutenticacaoFilter} guarda a sessao conferida como
 * atributo do pedido; os logs (acesso e alteracoes) e os endpoints leem daqui.
 */
public final class SessaoAtual {

    static final String ATRIBUTO = "kawashi.sessao";

    private SessaoAtual() {
    }

    static void guardar(HttpServletRequest requisicao, Tokens.Sessao sessao) {
        requisicao.setAttribute(ATRIBUTO, sessao);
    }

    public static Optional<Tokens.Sessao> de(HttpServletRequest requisicao) {
        return Optional.ofNullable((Tokens.Sessao) requisicao.getAttribute(ATRIBUTO));
    }

    /** A sessao do pedido em andamento, de qualquer lugar (vazio fora de um pedido, ou sem login). */
    public static Optional<Tokens.Sessao> doPedido() {
        RequestAttributes atributos = RequestContextHolder.getRequestAttributes();
        if (atributos == null) {
            return Optional.empty();
        }
        return Optional.ofNullable((Tokens.Sessao) atributos.getAttribute(ATRIBUTO, RequestAttributes.SCOPE_REQUEST));
    }

    /** Como o usuario aparece nos logs: "USUARIO:<id>", ou "API" quando nao ha ninguem logado. */
    public static String quemNoLog() {
        return doPedido().map(s -> "USUARIO:" + s.usuarioId()).orElse("API");
    }
}
