package br.com.kawashi.banco.login;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Grava em LOG_SEGURANCA (schema _LOG) os eventos que mudam a seguranca de uma conta: login,
 * logout, troca de senha, bloqueio, sessao derrubada. A numeracao, a hora e o encadeamento por
 * hash sao do trigger do banco; a API so insere (nao altera nem apaga) e roda na mesma transacao
 * do evento.
 */
@Component
public class LogDeSeguranca {

    private final JdbcTemplate jdbc;
    private final SchemasDoLogin schemas;

    public LogDeSeguranca(JdbcTemplate jdbc, SchemasDoLogin schemas) {
        this.jdbc = jdbc;
        this.schemas = schemas;
    }

    public void registrar(String evento, Long usuarioId, String detalhe, String ip, String feitoPor) {
        jdbc.update("INSERT INTO " + schemas.log("LOG_SEGURANCA")
                + " (EVENTO, USUARIO_ID, DETALHE, IP_ORIGEM, FEITO_POR) VALUES (?, ?, ?, ?, ?)",
                evento, usuarioId, detalhe, ip, feitoPor);
    }

    /** Quem fez: o proprio usuario logado, ou a API quando nao ha ninguem logado. */
    public static String quem(Long usuarioId) {
        return usuarioId == null ? "API" : "USUARIO:" + usuarioId;
    }
}
