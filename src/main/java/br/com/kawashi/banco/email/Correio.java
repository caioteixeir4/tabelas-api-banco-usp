package br.com.kawashi.banco.email;

/**
 * Manda um e-mail. Quem chama nao espera a entrega: o {@link CorreioSmtp} manda so depois que a
 * transacao em andamento for confirmada (um convite desfeito nunca chega a ninguem). Nos testes, um
 * correio falso guarda as mensagens.
 */
public interface Correio {

    /** {@code tipo}: CONVITE, AVISO ou JA_TEM_CONTA (vai para o log, nunca o endereco nem o texto). */
    record Mensagem(String para, String assunto, String texto, String tipo) {

        @Override
        public String toString() {
            return "Mensagem[" + tipo + "]";  // nada de endereco nem de segredo em log
        }
    }

    void enviar(Mensagem mensagem);
}
