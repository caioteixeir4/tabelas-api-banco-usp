package br.com.kawashi.banco.compartilhado;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import br.com.kawashi.banco.compartilhado.erros.CorpoGrandeDemaisException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Limite de tamanho do corpo da requisicao (413, no mesmo formato RFC 9457 dos demais erros). O
 * limite padrao (64 KiB) e folgado: o maior corpo legitimo da API tem poucas centenas de bytes.
 *
 * <p>Duas barreiras, porque o tamanho pode ou nao vir declarado:
 * <ul>
 *   <li>corpo que DECLARA (Content-Length) mais que o limite e recusado antes de qualquer leitura;</li>
 *   <li>corpo sem tamanho declarado (transferencia fragmentada, "chunked") e contado enquanto e
 *       lido, e a leitura e interrompida no byte que passa do limite. Sem isso, bastava omitir o
 *       Content-Length para mandar um corpo de qualquer tamanho.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
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
                    + "\"detail\":\"" + CorpoGrandeDemaisException.mensagem(limiteEmBytes) + "\"}");
            return;
        }
        cadeia.doFilter(new RequisicaoComCorpoLimitado(requisicao, limiteEmBytes), resposta);
    }

    /** Entrega o corpo por um fluxo que conta os bytes lidos. */
    private static final class RequisicaoComCorpoLimitado extends HttpServletRequestWrapper {

        private final long limiteEmBytes;
        private ServletInputStream fluxo;

        RequisicaoComCorpoLimitado(HttpServletRequest requisicao, long limiteEmBytes) {
            super(requisicao);
            this.limiteEmBytes = limiteEmBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (fluxo == null) {
                fluxo = new FluxoLimitado(super.getInputStream(), limiteEmBytes);
            }
            return fluxo;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String codificacao = getCharacterEncoding();
            Charset charset = codificacao == null ? StandardCharsets.UTF_8 : Charset.forName(codificacao);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class FluxoLimitado extends ServletInputStream {

        private final ServletInputStream origem;
        private final long limiteEmBytes;
        private long lidos;

        FluxoLimitado(ServletInputStream origem, long limiteEmBytes) {
            this.origem = origem;
            this.limiteEmBytes = limiteEmBytes;
        }

        @Override
        public int read() throws IOException {
            int valor = origem.read();
            if (valor >= 0) {
                contar(1);
            }
            return valor;
        }

        @Override
        public int read(byte[] destino, int inicio, int tamanho) throws IOException {
            int quantidade = origem.read(destino, inicio, tamanho);
            if (quantidade > 0) {
                contar(quantidade);
            }
            return quantidade;
        }

        private void contar(int quantidade) throws IOException {
            lidos += quantidade;
            if (lidos > limiteEmBytes) {
                throw new CorpoGrandeDemaisException(limiteEmBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return origem.isFinished();
        }

        @Override
        public boolean isReady() {
            return origem.isReady();
        }

        @Override
        public void setReadListener(ReadListener ouvinte) {
            origem.setReadListener(ouvinte);
        }

        @Override
        public void close() throws IOException {
            origem.close();
        }
    }
}
