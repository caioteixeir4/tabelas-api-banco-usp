package br.com.kawashi.banco.login;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Os termos de uso e a politica de privacidade (LGPD). Cada documento tem versoes publicadas pelo
 * DBA (TERMO, script publicar-termo.ps1); vale a mais recente de cada um. O usuario precisa ter
 * aceitado as versoes vigentes para usar o sistema: enquanto houver pendencia, as rotas de negocio
 * respondem 403 "Aceite os termos" ({@code FiltroDePermissao}).
 *
 * <p>O aceite (ACEITE_TERMO) guarda quem, qual versao (com o SHA-256 do texto, no TERMO), quando
 * (hora do banco) e de qual IP. A API so insere: nao altera nem apaga. O gatilho do banco poe o
 * aceite tambem no LOG_SEGURANCA (TERMO_ACEITO).
 */
@Component
public class Termos {

    public record Termo(long id, String documento, String versao, String texto, String hashTexto, OffsetDateTime publicadoEm,
            boolean aceito) {
    }

    private final JdbcTemplate jdbc;
    private final SchemasDoLogin schemas;

    public Termos(JdbcTemplate jdbc, SchemasDoLogin schemas) {
        this.jdbc = jdbc;
        this.schemas = schemas;
    }

    /** As versoes vigentes (a mais recente de cada documento), marcando as que o usuario ja aceitou. */
    public List<Termo> vigentes(long usuarioId) {
        return jdbc.query("SELECT t.ID, t.DOCUMENTO, t.VERSAO, t.TEXTO, t.HASH_TEXTO, t.PUBLICADO_EM,"
                + " CASE WHEN a.ID IS NULL THEN 0 ELSE 1 END ACEITO"
                + " FROM " + schemas.login("TERMO") + " t"
                + " LEFT JOIN " + schemas.login("ACEITE_TERMO") + " a ON a.TERMO_ID = t.ID AND a.USUARIO_ID = ?"
                + " WHERE " + vigente("t") + " ORDER BY t.DOCUMENTO",
                (r, i) -> new Termo(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                        r.getObject(6, LocalDateTime.class).atOffset(ZoneOffset.UTC), r.getInt(7) == 1),
                usuarioId);
    }

    /** Se ainda falta aceitar alguma versao vigente. Uma consulta por pedido de negocio. */
    public boolean temPendencia(long usuarioId) {
        Integer pendentes = jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.login("TERMO") + " t WHERE " + vigente("t")
                + " AND NOT EXISTS (SELECT 1 FROM " + schemas.login("ACEITE_TERMO") + " a WHERE a.TERMO_ID = t.ID AND a.USUARIO_ID = ?)",
                Integer.class, usuarioId);
        return pendentes != null && pendentes > 0;
    }

    /**
     * Aceita as versoes informadas. So vale aceitar versao VIGENTE: id antigo ou inexistente e
     * recusado (false), e nada e gravado. Aceitar de novo o que ja foi aceito nao muda nada.
     */
    @Transactional
    public boolean aceitar(long usuarioId, Collection<Long> termoIds, String ip) {
        if (termoIds.isEmpty()) {
            return false;
        }
        List<Long> vigentes = jdbc.queryForList("SELECT ID FROM " + schemas.login("TERMO") + " t WHERE " + vigente("t"), Long.class);
        if (!vigentes.containsAll(termoIds)) {
            return false;
        }
        for (Long termoId : termoIds) {
            try {
                jdbc.update("INSERT INTO " + schemas.login("ACEITE_TERMO") + " (USUARIO_ID, TERMO_ID, IP_ORIGEM) VALUES (?, ?, ?)",
                        usuarioId, termoId, ip);
            } catch (DuplicateKeyException jaAceito) {
                // ja aceitou esta versao: o primeiro aceite e o que vale
            }
        }
        return true;
    }

    private String vigente(String apelido) {
        return apelido + ".ID = (SELECT MAX(u.ID) FROM " + schemas.login("TERMO") + " u WHERE u.DOCUMENTO = " + apelido + ".DOCUMENTO)";
    }
}
