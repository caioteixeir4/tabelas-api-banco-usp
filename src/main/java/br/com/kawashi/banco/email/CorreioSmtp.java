package br.com.kawashi.banco.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * O correio de verdade, por SMTP ({@code MAIL_HOST}/{@code MAIL_PORT}; no desenvolvimento, o Mailpit
 * do repositorio de regras). Dentro de uma transacao, o envio fica para DEPOIS do commit: se a
 * transacao for desfeita, nada sai. Falha no envio vai para o log da aplicacao (sem endereco nem
 * texto) e nao desfaz o que ja foi gravado: o administrador pode pedir outro convite.
 */
@Component
public class CorreioSmtp implements Correio {

    private static final Logger LOG = LoggerFactory.getLogger(CorreioSmtp.class);

    private final JavaMailSender smtp;
    private final String remetente;

    public CorreioSmtp(JavaMailSender smtp, @Value("${app.email.remetente}") String remetente) {
        this.smtp = smtp;
        this.remetente = remetente;
    }

    @Override
    public void enviar(Mensagem mensagem) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    mandar(mensagem);
                }
            });
        } else {
            mandar(mensagem);
        }
    }

    private void mandar(Mensagem mensagem) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(remetente);
        m.setTo(mensagem.para());
        m.setSubject(mensagem.assunto());
        m.setText(mensagem.texto());
        try {
            smtp.send(m);
        } catch (RuntimeException e) {
            LOG.warn("E-mail {} nao enviado: {}", mensagem.tipo(), e.getClass().getSimpleName());
        }
    }
}
