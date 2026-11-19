package br.com.kawashi.banco.login;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Os tokens de acesso. Decisoes (detalhadas em docs/modelo-de-dados.md, "Token de acesso"):
 * <ul>
 *   <li><b>opaco</b>, nao JWT: "kw_" + 32 bytes aleatorios. Pode ser cortado na hora (logout, troca
 *       de senha, desligamento) e nao ha chave de assinatura que, vazada, permita forjar acesso;</li>
 *   <li>no banco so fica o <b>SHA-256</b>: quem le a tabela nao consegue usar o token;</li>
 *   <li>validade <b>deslizante com teto</b>: cada uso empurra o vencimento ({@code TOKEN_MINUTOS})
 *       sem passar do teto ({@code TOKEN_TETO_HORAS} depois do login). A renovacao e gravada no
 *       maximo uma vez por minuto, nao a cada pedido;</li>
 *   <li>no maximo {@code SESSOES_MAXIMAS} sessoes ativas: a seguinte derruba a mais antiga;</li>
 *   <li>toda conta de tempo usa o relogio do BANCO (SYS_EXTRACT_UTC(SYSTIMESTAMP)), nunca o da aplicacao.</li>
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
            LocalDateTime expiraAbsolutoEm) {
    }

    private final JdbcTemplate jdbc;
    private final SchemasDoLogin schemas;
    private final LogDeSeguranca log;
    private final SecureRandom sorteio = new SecureRandom();

    public Tokens(JdbcTemplate jdbc, SchemasDoLogin schemas, LogDeSeguranca log) {
        this.jdbc = jdbc;
        this.schemas = schemas;
        this.log = log;
    }

    public TokenEmitido emitir(long usuarioId, String escopo, String ip, ParametrosDeSeguranca.Parametros p) {
        // Limite de sessoes: se ja tem o maximo, derruba as mais antigas ate abrir uma vaga.
        List<Long> ativas = jdbc.queryForList("SELECT ID FROM " + schemas.sessao("TOKEN")
                + " WHERE USUARIO_ID = ? AND REVOGADO_EM IS NULL AND EXPIRA_EM > SYS_EXTRACT_UTC(SYSTIMESTAMP) AND EXPIRA_ABSOLUTO_EM > SYS_EXTRACT_UTC(SYSTIMESTAMP)"
                + " ORDER BY CRIADO_EM, ID", Long.class, usuarioId);
        for (int i = 0; i <= ativas.size() - p.sessoesMaximas(); i++) {
            revogar(ativas.get(i), "LIMITE_SESSOES");
            log.registrar("SESSAO_ENCERRADA", usuarioId, "limite de " + p.sessoesMaximas() + " sessoes: a mais antiga caiu",
                    ip, LogDeSeguranca.quem(usuarioId));
        }

        byte[] aleatorio = new byte[32];
        sorteio.nextBytes(aleatorio);
        String token = PREFIXO + Base64.getUrlEncoder().withoutPadding().encodeToString(aleatorio);
        String hash = hash(token);
        jdbc.update("INSERT INTO " + schemas.sessao("TOKEN")
                + " (USUARIO_ID, TOKEN_HASH, ESCOPO, EXPIRA_EM, EXPIRA_ABSOLUTO_EM, IP_ORIGEM) VALUES (?, ?, ?,"
                + " LEAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'MINUTE'), SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'HOUR')),"
                + " SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'HOUR'), ?)",
                usuarioId, hash, escopo, p.tokenMinutos(), p.tokenTetoHoras(), p.tokenTetoHoras(), ip);
        return jdbc.queryForObject("SELECT ID, ESCOPO, EXPIRA_EM, EXPIRA_ABSOLUTO_EM FROM " + schemas.sessao("TOKEN")
                + " WHERE TOKEN_HASH = ?", (r, n) -> new TokenEmitido(r.getLong(1), token, r.getString(2),
                        r.getTimestamp(3).toLocalDateTime(), r.getTimestamp(4).toLocalDateTime()), hash);
    }

    /**
     * Confere o token e renova a validade. Vazio = nao vale (inexistente, vencido, revogado, ou o
     * usuario foi desativado): quem chama nao precisa, e nao deve, saber qual dos casos.
     */
    public Optional<Sessao> validar(String token, ParametrosDeSeguranca.Parametros p) {
        if (token == null || !FORMATO.matcher(token).matches()) {
            return Optional.empty();
        }
        List<Object[]> achados = jdbc.query("SELECT t.ID, t.USUARIO_ID, u.PESSOA_ID, t.ESCOPO,"
                + " CASE WHEN t.ULTIMO_USO_EM < SYS_EXTRACT_UTC(SYSTIMESTAMP) - INTERVAL '1' MINUTE THEN 1 ELSE 0 END"
                + " FROM " + schemas.sessao("TOKEN") + " t JOIN " + schemas.login("USUARIO") + " u ON u.ID = t.USUARIO_ID"
                + " WHERE t.TOKEN_HASH = ? AND t.REVOGADO_EM IS NULL AND t.EXPIRA_EM > SYS_EXTRACT_UTC(SYSTIMESTAMP)"
                + " AND t.EXPIRA_ABSOLUTO_EM > SYS_EXTRACT_UTC(SYSTIMESTAMP) AND u.ATIVO = TRUE",
                (r, n) -> new Object[] {r.getLong(1), r.getLong(2), r.getLong(3), r.getString(4), r.getInt(5)}, hash(token));
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
                        r.getTimestamp(1).toLocalDateTime(), r.getTimestamp(2).toLocalDateTime()), tokenId)
                .stream().findFirst();
    }

    public void revogar(long tokenId, String motivo) {
        jdbc.update("UPDATE " + schemas.sessao("TOKEN") + " SET REVOGADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP), MOTIVO_REVOGACAO = ?"
                + " WHERE ID = ? AND REVOGADO_EM IS NULL", motivo, tokenId);
    }

    /** Derruba todas as sessoes ativas do usuario. Devolve quantas caiam. */
    public int revogarTodos(long usuarioId, String motivo) {
        return jdbc.update("UPDATE " + schemas.sessao("TOKEN") + " SET REVOGADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP), MOTIVO_REVOGACAO = ?"
                + " WHERE USUARIO_ID = ? AND REVOGADO_EM IS NULL", motivo, usuarioId);
    }

    /** SHA-256 em hexadecimal: e so isto que vai para o banco. */
    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
