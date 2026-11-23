package br.com.kawashi.banco.autorizacao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import br.com.kawashi.banco.compartilhado.erros.AcessoNegadoException;
import br.com.kawashi.banco.login.SessaoAtual;
import br.com.kawashi.banco.login.Tokens;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Confere a regra de acesso ({@link Exige}) de cada rota de /api antes do controlador. Roda depois
 * dos filtros (o token ja foi conferido). /api/auth fica de fora: e o proprio login, com regras
 * no {@code AutenticacaoFilter}.
 *
 * <p>Rota de /api SEM a marca {@code @Exige} e recusada (403) e o erro vai para o log: a regra
 * esquecida fecha a rota, nunca abre.
 */
@Configuration
public class FiltroDePermissao implements HandlerInterceptor, WebMvcConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(FiltroDePermissao.class);

    private final Autorizacao autorizacao;

    public FiltroDePermissao(Autorizacao autorizacao) {
        this.autorizacao = autorizacao;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registro) {
        registro.addInterceptor(this).addPathPatterns("/api/**").excludePathPatterns("/api/auth/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest requisicao, HttpServletResponse resposta, Object alvo) {
        if (!(alvo instanceof HandlerMethod metodo)) {
            return true;  // 404/405 do proprio Spring: nao ha controlador a proteger
        }
        Exige regra = metodo.getMethodAnnotation(Exige.class);
        if (regra == null) {
            LOG.error("Rota sem regra de acesso (@Exige): {}.{}", metodo.getBeanType().getSimpleName(), metodo.getMethod().getName());
            throw new AcessoNegadoException("Sem permissão", "Esta rota não tem regra de acesso definida.");
        }
        Tokens.Sessao sessao = SessaoAtual.de(requisicao)
                .orElseThrow(() -> new AcessoNegadoException("Sem permissão", "Pedido sem sessão."));
        if (regra.adminPlataforma()) {
            if (!autorizacao.ehAdminPlataforma(sessao.usuarioId())) {
                throw new AcessoNegadoException("Sem permissão", "Só o administrador da plataforma faz isto.");
            }
            return true;
        }
        if (sessao.empresaId() == null) {
            throw new AcessoNegadoException("Escolha a empresa", "Escolha em qual empresa vai trabalhar (/api/auth/empresa).");
        }
        if (!regra.noServico()) {
            autorizacao.exigir(regra.value());
        }
        return true;
    }
}
