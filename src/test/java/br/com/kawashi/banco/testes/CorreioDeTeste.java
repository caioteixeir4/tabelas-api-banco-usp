package br.com.kawashi.banco.testes;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import br.com.kawashi.banco.email.Correio;

/**
 * O correio dos testes: guarda as mensagens NA HORA (a transacao do teste e desfeita, entao o
 * correio de verdade, que manda depois do commit, nunca mandaria nada). Nada sai da maquina.
 */
@Component
@Primary
public class CorreioDeTeste implements Correio {

    private static final Pattern CONVITE = Pattern.compile("#(kwc_[A-Za-z0-9_-]{43})");

    private final List<Mensagem> enviadas = new CopyOnWriteArrayList<>();

    @Override
    public void enviar(Mensagem mensagem) {
        enviadas.add(mensagem);
    }

    public List<Mensagem> para(String email) {
        return enviadas.stream().filter(m -> m.para().equalsIgnoreCase(email)).toList();
    }

    /** O segredo do ultimo convite que chegou a este e-mail. */
    public Optional<String> ultimoConvite(String email) {
        List<Mensagem> dele = para(email).stream().filter(m -> "CONVITE".equals(m.tipo())).toList();
        if (dele.isEmpty()) {
            return Optional.empty();
        }
        Matcher m = CONVITE.matcher(dele.get(dele.size() - 1).texto());
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    public List<Mensagem> todas() {
        return List.copyOf(enviadas);
    }
}
