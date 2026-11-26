package br.com.kawashi.banco.login;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * "Confiar neste computador": depois do codigo do 2FA, a pessoa pode marcar o computador. Ele
 * recebe um segredo ("kwd_" + 32 bytes) que o BFF guarda num cookie HttpOnly e manda a API no
 * cabecalho {@value #CABECALHO}; no banco fica so o SHA-256. Por DISPOSITIVO_CONFIAVEL_DIAS, a senha
 * basta nesse computador. Trocar a senha, desativar o 2FA ou "sair de todos" revoga todos.
 *
 * <p>Quem confiou, quando e de onde nao muda, e revogado nao volta (gatilho do banco, ORA-20115);
 * confiar e revogar vao sozinhos para o LOG_SEGURANCA.
 */
@Component
public class DispositivosConfiaveis {

    public static final String PREFIXO = "kwd_";
    public static final String CABECALHO = "X-Kawashi-Dispositivo";

    private final JdbcTemplate jdbc;
    private final SchemasDoLogin schemas;

    public DispositivosConfiaveis(JdbcTemplate jdbc, SchemasDoLogin schemas) {
        this.jdbc = jdbc;
        this.schemas = schemas;
    }

    /** Marca o computador como confiavel e devolve o segredo (a unica vez em que ele existe em claro). */
    public String confiar(long usuarioId, String nome, String ip, int dias) {
        String segredo = PREFIXO + Aleatorio.base64Url32();
        jdbc.update("INSERT INTO " + schemas.sessao("DISPOSITIVO_CONFIAVEL") + " (USUARIO_ID, TOKEN_HASH, NOME, IP_ORIGEM, EXPIRA_EM)"
                + " VALUES (?, ?, ?, ?, SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'DAY'))",
                usuarioId, Aleatorio.sha256(segredo), nome, ip, dias);
        return segredo;
    }

    /** O computador e de confianca PARA ESTE usuario (segredo de outra conta nao serve). Registra o uso. */
    public boolean confiavel(String segredo, long usuarioId) {
        if (segredo == null || !segredo.matches("kwd_[A-Za-z0-9_-]{43}")) {
            return false;
        }
        Optional<Long> id = jdbc.query("SELECT ID FROM " + schemas.sessao("DISPOSITIVO_CONFIAVEL")
                + " WHERE TOKEN_HASH = ? AND USUARIO_ID = ? AND REVOGADO_EM IS NULL AND EXPIRA_EM > SYS_EXTRACT_UTC(SYSTIMESTAMP)",
                (r, n) -> r.getLong(1), Aleatorio.sha256(segredo), usuarioId).stream().findFirst();
        id.ifPresent(i -> jdbc.update("UPDATE " + schemas.sessao("DISPOSITIVO_CONFIAVEL")
                + " SET ULTIMO_USO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) WHERE ID = ?", i));
        return id.isPresent();
    }

    /** Os computadores de confianca ainda validos do usuario (sem o segredo, claro). */
    public List<Map<String, Object>> listar(long usuarioId) {
        return jdbc.query("SELECT ID, NOME, IP_ORIGEM, CRIADO_EM, ULTIMO_USO_EM, EXPIRA_EM FROM " + schemas.sessao("DISPOSITIVO_CONFIAVEL")
                + " WHERE USUARIO_ID = ? AND REVOGADO_EM IS NULL AND EXPIRA_EM > SYS_EXTRACT_UTC(SYSTIMESTAMP) ORDER BY CRIADO_EM DESC",
                (r, n) -> {
                    Map<String, Object> d = new java.util.LinkedHashMap<>();
                    d.put("id", r.getLong(1));
                    d.put("nome", r.getString(2));
                    d.put("ip", r.getString(3));
                    d.put("criadoEm", utc(r.getTimestamp(4)));
                    d.put("ultimoUsoEm", utc(r.getTimestamp(5)));
                    d.put("expiraEm", utc(r.getTimestamp(6)));
                    return d;
                }, usuarioId);
    }

    /** Revoga um computador do usuario. Falso se nao existe, e de outro, ou ja foi revogado. */
    public boolean revogar(long usuarioId, long id) {
        return jdbc.update("UPDATE " + schemas.sessao("DISPOSITIVO_CONFIAVEL") + " SET REVOGADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP)"
                + " WHERE ID = ? AND USUARIO_ID = ? AND REVOGADO_EM IS NULL", id, usuarioId) == 1;
    }

    /** Revoga todos os computadores de confianca do usuario. Devolve quantos. */
    public int revogarTodos(long usuarioId) {
        return jdbc.update("UPDATE " + schemas.sessao("DISPOSITIVO_CONFIAVEL") + " SET REVOGADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP)"
                + " WHERE USUARIO_ID = ? AND REVOGADO_EM IS NULL", usuarioId);
    }

    private static java.time.OffsetDateTime utc(java.sql.Timestamp t) {
        return t == null ? null : t.toLocalDateTime().atOffset(java.time.ZoneOffset.UTC);
    }
}
