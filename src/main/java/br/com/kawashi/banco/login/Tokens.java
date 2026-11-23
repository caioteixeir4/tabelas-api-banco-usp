package br.com.kawashi.banco.login;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Os tokens de acesso. Decisoes (detalhadas em docs/modelo-de-dados.md, "Token de acesso"):
 * <ul>
 *   <li><b>opaco</b>, nao JWT: "kw_" + 32 bytes aleatorios. Pode ser cortado na hora (logout,
 *       passkey removida, desligamento) e nao ha chave de assinatura que, vazada, permita forjar
 *       acesso;</li>
 *   <li>no banco so fica o <b>SHA-256</b>: quem le a tabela nao consegue usar o token;</li>
 *   <li>o token nunca chega ao navegador: quem o guarda e o BFF, do lado do servidor;</li>
 *   <li>validade <b>deslizante com teto</b>: cada uso empurra o vencimento ({@code TOKEN_MINUTOS})
 *       sem passar do teto ({@code TOKEN_TETO_HORAS} depois do login). A renovacao e gravada no
 *       maximo uma vez por minuto;</li>
 *   <li>no maximo {@code SESSOES_MAXIMAS} sessoes ativas: a seguinte derruba a mais antiga;</li>
 *   <li>o token guarda com qual passkey a sessao foi aberta: remover a passkey derruba as sessoes
 *       dela;</li>
 *   <li>toda conta de tempo usa o relogio do BANCO, em UTC, nunca o da aplicacao.</li>
 * </ul>
 */
@Component
public class Tokens {

    public static final String PREFIXO = "kw_";
    private static final Pattern FORMATO = Pattern.compile("kw_[A-Za-z0-9_-]{43}");

    public record TokenEmitido(long id, String token, String escopo, LocalDateTime expiraEm, LocalDateTime expiraAbsolutoEm) {
    }

    /** O que a API sabe de quem esta chamando, depois de conferir o token. */
    public record Sessao(long tokenId, long usuarioId, long pessoaId, String escopo, LocalDateTime expiraEm,
            LocalDateTime expiraAbsolutoEm, Long empresaId, Long passkeyId) {
    }

    private final JdbcTemplate jdbc;
    private final SchemasDoLogin schemas;
    private final LogDeSeguranca log;

    public Tokens(JdbcTemplate jdbc, SchemasDoLogin schemas, LogDeSeguranca log) {
        this.jdbc = jdbc;
        this.schemas = schemas;
        this.log = log;
    }

    public TokenEmitido emitir(long usuarioId, Long passkeyId, String escopo, String ip, ParametrosDeSeguranca.Parametros p) {
        return emitir(usuarioId, passkeyId, escopo, ip, p, null);
    }

    /**
     * Emite um token. Com {@code empresaId}, a sessao fica presa a essa empresa (quem chama ja
     * conferiu o vinculo); sem, a sessao so serve para escolher a empresa e para o proprio login.
     */
    public TokenEmitido emitir(long usuarioId, Long passkeyId, String escopo, String ip, ParametrosDeSeguranca.Parametros p,
            Long empresaId) {
        // Limite de sessoes: se ja tem o maximo, derruba as mais antigas ate abrir uma vaga.
        List<Long> ativas = jdbc.queryForList("SELECT ID FROM " + schemas.sessao("TOKEN")
                + " WHERE USUARIO_ID = ? AND REVOGADO_EM IS NULL AND EXPIRA_EM > SYS_EXTRACT_UTC(SYSTIMESTAMP)"
                + " AND EXPIRA_ABSOLUTO_EM > SYS_EXTRACT_UTC(SYSTIMESTAMP) ORDER BY CRIADO_EM, ID", Long.class, usuarioId);
        for (int i = 0; i <= ativas.size() - p.sessoesMaximas(); i++) {
            revogar(ativas.get(i), "LIMITE_SESSOES");
            log.registrar("SESSAO_ENCERRADA", usuarioId, "limite de " + p.sessoesMaximas() + " sessoes: a mais antiga caiu",
                    ip, LogDeSeguranca.quem(usuarioId));
        }
        String token = PREFIXO + Aleatorio.base64Url32();
        String hash = hash(token);
        jdbc.update("INSERT INTO " + schemas.sessao("TOKEN")
                + " (USUARIO_ID, PASSKEY_ID, TOKEN_HASH, ESCOPO, EXPIRA_EM, EXPIRA_ABSOLUTO_EM, IP_ORIGEM, EMPRESA_ID) VALUES (?, ?, ?, ?,"
                + " LEAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'MINUTE'), SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'HOUR')),"
                + " SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'HOUR'), ?, ?)",
                usuarioId, passkeyId, hash, escopo, p.tokenMinutos(), p.tokenTetoHoras(), p.tokenTetoHoras(), ip, empresaId);
        return jdbc.queryForObject("SELECT ID, ESCOPO, EXPIRA_EM, EXPIRA_ABSOLUTO_EM FROM " + schemas.sessao("TOKEN")
                + " WHERE TOKEN_HASH = ?", (r, n) -> new TokenEmitido(r.getLong(1), token, r.getString(2),
                        r.getTimestamp(3).toLocalDateTime(), r.getTimestamp(4).toLocalDateTime()), hash);
    }

    /**
     * Confere o token e renova a validade. Vazio = nao vale (inexistente, vencido, revogado, o
     * usuario foi desativado, ou o token e de uma empresa em que o usuario nao tem mais vinculo
     * ativo): quem chama nao precisa, e nao deve, saber qual dos casos.
     */
    public Optional<Sessao> validar(String token, ParametrosDeSeguranca.Parametros p) {
        if (token == null || !FORMATO.matcher(token).matches()) {
            return Optional.empty();
        }
        List<Object[]> achados = jdbc.query("SELECT t.ID, t.USUARIO_ID, u.PESSOA_ID, t.ESCOPO,"
                + " CASE WHEN t.ULTIMO_USO_EM < SYS_EXTRACT_UTC(SYSTIMESTAMP) - INTERVAL '1' MINUTE THEN 1 ELSE 0 END,"
                + " t.EMPRESA_ID, t.PASSKEY_ID"
                + " FROM " + schemas.sessao("TOKEN") + " t JOIN " + schemas.login("USUARIO") + " u ON u.ID = t.USUARIO_ID"
                + " WHERE t.TOKEN_HASH = ? AND t.REVOGADO_EM IS NULL AND t.EXPIRA_EM > SYS_EXTRACT_UTC(SYSTIMESTAMP)"
                + " AND t.EXPIRA_ABSOLUTO_EM > SYS_EXTRACT_UTC(SYSTIMESTAMP) AND u.ATIVO = TRUE"
                + " AND (t.EMPRESA_ID IS NULL OR EXISTS (SELECT 1 FROM " + schemas.login("USUARIO_EMPRESA") + " ue"
                + " WHERE ue.USUARIO_ID = t.USUARIO_ID AND ue.EMPRESA_ID = t.EMPRESA_ID AND ue.ATIVO))",
                (r, n) -> new Object[] {r.getLong(1), r.getLong(2), r.getLong(3), r.getString(4), r.getInt(5),
                        (Long) r.getObject(6, Long.class), (Long) r.getObject(7, Long.class)}, hash(token));
        if (achados.isEmpty()) {
            return Optional.empty();
        }
        Object[] a = achados.get(0);
        long tokenId = (Long) a[0];
        if ((Integer) a[4] == 1) {
            jdbc.update("UPDATE " + schemas.sessao("TOKEN") + " SET ULTIMO_USO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP),"
                    + " EXPIRA_EM = LEAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'MINUTE'), EXPIRA_ABSOLUTO_EM) WHERE ID = ?",
                    p.tokenMinutos(), tokenId);
        }
        return jdbc.query("SELECT EXPIRA_EM, EXPIRA_ABSOLUTO_EM FROM " + schemas.sessao("TOKEN") + " WHERE ID = ?",
                (r, n) -> new Sessao(tokenId, (Long) a[1], (Long) a[2], (String) a[3],
                        r.getTimestamp(1).toLocalDateTime(), r.getTimestamp(2).toLocalDateTime(), (Long) a[5], (Long) a[6]), tokenId)
                .stream().findFirst();
    }

    public void revogar(long tokenId, String motivo) {
        jdbc.update("UPDATE " + schemas.sessao("TOKEN") + " SET REVOGADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP), MOTIVO_REVOGACAO = ?"
                + " WHERE ID = ? AND REVOGADO_EM IS NULL", motivo, tokenId);
    }

    /** Derruba todas as sessoes ativas do usuario, menos (opcionalmente) uma. Devolve quantas cairam. */
    public int revogarTodos(long usuarioId, String motivo, Long menosEsta) {
        return jdbc.update("UPDATE " + schemas.sessao("TOKEN") + " SET REVOGADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP), MOTIVO_REVOGACAO = ?"
                + " WHERE USUARIO_ID = ? AND REVOGADO_EM IS NULL AND ID <> NVL(?, -1)", motivo, usuarioId, menosEsta);
    }

    /** Derruba as sessoes abertas com uma passkey. */
    public int revogarDaPasskey(long passkeyId, String motivo) {
        return jdbc.update("UPDATE " + schemas.sessao("TOKEN") + " SET REVOGADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP), MOTIVO_REVOGACAO = ?"
                + " WHERE PASSKEY_ID = ? AND REVOGADO_EM IS NULL", motivo, passkeyId);
    }

    /** Derruba as sessoes do usuario presas a uma empresa (o acesso a ela foi desativado). */
    public int revogarDaEmpresa(long usuarioId, long empresaId, String motivo) {
        return jdbc.update("UPDATE " + schemas.sessao("TOKEN") + " SET REVOGADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP), MOTIVO_REVOGACAO = ?"
                + " WHERE USUARIO_ID = ? AND EMPRESA_ID = ? AND REVOGADO_EM IS NULL", motivo, usuarioId, empresaId);
    }

    /** SHA-256 em hexadecimal: e so isto que vai para o banco. */
    public static String hash(String token) {
        return Aleatorio.sha256(token);
    }
}
