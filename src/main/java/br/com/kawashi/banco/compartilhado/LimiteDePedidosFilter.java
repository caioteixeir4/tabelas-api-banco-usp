package br.com.kawashi.banco.compartilhado;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import br.com.kawashi.banco.login.FiltroDoBff;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Limite de pedidos por IP do navegador em toda a /api (segunda barreira: a primeira e o BFF). Janela
 * fixa de um minuto: passou de {@code app.limite-pedidos.por-minuto}, 429 com Retry-After ate a
 * janela virar. O IP e o que o BFF informa ({@link FiltroDoBff#ipDoCliente}), entao este filtro roda
 * DEPOIS da checagem do segredo do BFF.
 *
 * <p>Fica em memoria: com mais de uma instancia da API, cada uma conta o seu (o limite efetivo
 * multiplica). Para varias instancias, o contador iria para um armazenamento comum.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 17)
public class LimiteDePedidosFilter extends OncePerRequestFilter {

    private record Janela(long minuto, AtomicInteger pedidos) {
    }

    private final int porMinuto;
    private final ConcurrentHashMap<String, Janela> janelas = new ConcurrentHashMap<>();

    public LimiteDePedidosFilter(@Value("${app.limite-pedidos.por-minuto:600}") int porMinuto) {
        this.porMinuto = porMinuto;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest requisicao) {
        return !requisicao.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        long agora = System.currentTimeMillis();
        long minuto = agora / 60_000;
        if (janelas.size() > 50_000) {
            janelas.values().removeIf(j -> j.minuto() < minuto);  // nao deixa a memoria crescer sem fim
        }
        Janela janela = janelas.compute(FiltroDoBff.ipDoCliente(requisicao),
                (ip, atual) -> atual == null || atual.minuto() != minuto ? new Janela(minuto, new AtomicInteger()) : atual);
        if (janela.pedidos().incrementAndGet() > porMinuto) {
            long segundos = Math.max(1, ((minuto + 1) * 60_000 - agora + 999) / 1000);
            resposta.setStatus(429);
            resposta.setHeader("Retry-After", String.valueOf(segundos));
            resposta.setContentType("application/problem+json");
            resposta.setCharacterEncoding("UTF-8");
            resposta.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Muitos pedidos\",\"status\":429,"
                    + "\"detail\":\"Muitos pedidos deste endereço. Tente de novo em instantes.\"}");
            return;
        }
        cadeia.doFilter(requisicao, resposta);
    }
}
