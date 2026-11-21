package br.com.kawashi.banco.login;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Convites de uso unico. Como nao existe senha, uma conta nova nasce de um convite: um link com um
 * segredo aleatorio ("kwc_" + 32 bytes), valido por {@code CONVITE_HORAS}, em que a pessoa cadastra a
 * primeira passkey. O mesmo mecanismo serve para quem perdeu todos os aparelhos e os codigos de
 * recuperacao: o administrador gera um convite NOVA_PASSKEY.
 *
 * <p>No banco so fica o SHA-256 do segredo. O link leva o segredo no fragmento da URL ({@code #}),
 * que o navegador nao envia ao servidor: ele nao aparece em log de acesso nem em cabecalho Referer.
 */
@Component
public class Convites {

    public static final String PREFIXO = "kwc_";

    public record Convite(long id, long usuarioId, String finalidade) {
    }

    private final JdbcTemplate jdbc;
    private final SchemasDoLogin schemas;

    public Convites(JdbcTemplate jdbc, SchemasDoLogin schemas) {
        this.jdbc = jdbc;
        this.schemas = schemas;
    }

    /** Cria o convite e devolve o segredo (a unica vez em que ele existe em claro). */
    public String criar(long usuarioId, String finalidade, String criadoPor, ParametrosDeSeguranca.Parametros p) {
        String segredo = PREFIXO + Aleatorio.base64Url32();
        jdbc.update("INSERT INTO " + schemas.login("CONVITE") + " (USUARIO_ID, TOKEN_HASH, FINALIDADE, CRIADO_POR, EXPIRA_EM)"
                + " VALUES (?, ?, ?, ?, SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'HOUR'))",
                usuarioId, Aleatorio.sha256(segredo), finalidade, criadoPor, p.conviteHoras());
        return segredo;
    }

    /** O convite vale: existe, nao foi usado, nao venceu e o usuario esta ativo. */
    public Optional<Convite> valido(String segredo) {
        if (segredo == null || !segredo.matches("kwc_[A-Za-z0-9_-]{43}")) {
            return Optional.empty();
        }
        return jdbc.query("SELECT c.ID, c.USUARIO_ID, c.FINALIDADE FROM " + schemas.login("CONVITE") + " c JOIN "
                + schemas.login("USUARIO") + " u ON u.ID = c.USUARIO_ID"
                + " WHERE c.TOKEN_HASH = ? AND c.USADO_EM IS NULL AND c.EXPIRA_EM > SYS_EXTRACT_UTC(SYSTIMESTAMP) AND u.ATIVO = TRUE",
                (r, n) -> new Convite(r.getLong(1), r.getLong(2), r.getString(3)), Aleatorio.sha256(segredo)).stream().findFirst();
    }

    /** Marca como usado. Falso se outro pedido usou primeiro (uso unico garantido pelo banco). */
    public boolean usar(long conviteId) {
        return jdbc.update("UPDATE " + schemas.login("CONVITE") + " SET USADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP)"
                + " WHERE ID = ? AND USADO_EM IS NULL AND EXPIRA_EM > SYS_EXTRACT_UTC(SYSTIMESTAMP)", conviteId) == 1;
    }
}
