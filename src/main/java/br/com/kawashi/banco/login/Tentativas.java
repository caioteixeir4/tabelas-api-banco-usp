package br.com.kawashi.banco.login;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import br.com.kawashi.banco.compartilhado.Ips;
import br.com.kawashi.banco.login.ParametrosDeSeguranca.Parametros;

/**
 * As tentativas de login (TENTATIVA_LOGIN) e os dois bloqueios que nascem delas:
 * <ul>
 *   <li><b>do IP</b>: {@code IP_MAX_FALHAS} falhas do mesmo grupo de IP (o IPv4, ou o /64 do IPv6)
 *       em {@code IP_JANELA_MINUTOS} bloqueiam o grupo por {@code IP_BLOQUEIO_MINUTOS} (robo testando
 *       senhas, codigos ou convites de um lugar so);</li>
 *   <li><b>da conta</b> (so para senha e codigo do 2FA): {@code CONTA_MAX_FALHAS} falhas da MESMA
 *       conta em {@code CONTA_BLOQUEIO_MINUTOS} param de aceitar senha nela por esse tempo (quem tenta
 *       de muitos IPs). As recusas por conta bloqueada nao contam: senao quem ataca manteria a conta
 *       bloqueada para sempre. A passkey continua entrando: o bloqueio e so da senha.</li>
 * </ul>
 * Quem chama esta sempre numa transacao e NAO lanca excecao depois de registrar a falha (senao o
 * registro seria desfeito junto).
 */
@Component
public class Tentativas {

    private final JdbcTemplate jdbc;
    private final SchemasDoLogin schemas;
    private final LogDeSeguranca log;

    public Tentativas(JdbcTemplate jdbc, SchemasDoLogin schemas, LogDeSeguranca log) {
        this.jdbc = jdbc;
        this.schemas = schemas;
        this.log = log;
    }

    /** O IP (o grupo dele) esta bloqueado agora? */
    public Optional<ServicoDeLogin.IpBloqueado> bloqueio(String ip) {
        return jdbc.query("SELECT CEIL((CAST(BLOQUEADO_ATE AS DATE) - CAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) AS DATE)) * 86400)"
                + " FROM " + schemas.sessao("BLOQUEIO_IP") + " WHERE IP = ? AND BLOQUEADO_ATE > SYS_EXTRACT_UTC(SYSTIMESTAMP)",
                (r, n) -> new ServicoDeLogin.IpBloqueado(Math.max(1, r.getLong(1))), Ips.grupo(ip)).stream().findFirst();
    }

    /** Registra a falha e confere o limite do IP. Devolve a recusa (sempre a mesma, para quem chamou). */
    public ServicoDeLogin.Recusado falhou(String metodo, String motivo, Long usuarioId, String ip, Parametros p) {
        registrar(metodo, usuarioId, ip, false, motivo);
        conferirIp(ip, p);
        return new ServicoDeLogin.Recusado();
    }

    public void registrar(String metodo, Long usuarioId, String ip, boolean sucesso, String motivo) {
        jdbc.update("INSERT INTO " + schemas.log("TENTATIVA_LOGIN") + " (METODO, USUARIO_ID, IP_ORIGEM, IP_GRUPO, SUCESSO, MOTIVO)"
                + " VALUES (?, ?, ?, ?, ?, ?)", metodo, usuarioId, ip, Ips.grupo(ip), sucesso, motivo);
    }

    /** Se o grupo do IP passou do limite de falhas na janela, bloqueia o grupo inteiro. */
    public void conferirIp(String ip, Parametros p) {
        String grupo = Ips.grupo(ip);
        Long falhas = jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.log("TENTATIVA_LOGIN")
                + " WHERE IP_GRUPO = ? AND SUCESSO = FALSE AND DATA_HORA > SYS_EXTRACT_UTC(SYSTIMESTAMP) - NUMTODSINTERVAL(?, 'MINUTE')",
                Long.class, grupo, p.ipJanelaMinutos());
        if (falhas != null && falhas >= p.ipMaxFalhas()) {
            String motivoDoBloqueio = falhas + " falhas em " + p.ipJanelaMinutos() + " min";
            jdbc.update("MERGE INTO " + schemas.sessao("BLOQUEIO_IP") + " b USING (SELECT ? AS IP FROM DUAL) n ON (b.IP = n.IP)"
                    + " WHEN MATCHED THEN UPDATE SET b.BLOQUEADO_ATE = SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'MINUTE'), b.MOTIVO = ?"
                    + " WHEN NOT MATCHED THEN INSERT (IP, BLOQUEADO_ATE, MOTIVO) VALUES (n.IP, SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'MINUTE'), ?)",
                    grupo, p.ipBloqueioMinutos(), motivoDoBloqueio, p.ipBloqueioMinutos(), motivoDoBloqueio);
            log.registrar("IP_BLOQUEADO", null, motivoDoBloqueio + "; bloqueado por " + p.ipBloqueioMinutos() + " min: " + grupo, ip, "API");
        }
    }

    /**
     * Falhas de senha/codigo da conta na janela (sem contar as recusas por conta ja bloqueada). So as
     * depois da ultima troca de senha: quem redefiniu a senha pelo e-mail entra logo, sem esperar.
     */
    public long falhasDaConta(long usuarioId, Parametros p) {
        Long falhas = jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.log("TENTATIVA_LOGIN")
                + " WHERE USUARIO_ID = ? AND SUCESSO = FALSE AND MOTIVO IN ('SENHA_INVALIDA', 'CODIGO_2FA_INVALIDO')"
                + " AND DATA_HORA > SYS_EXTRACT_UTC(SYSTIMESTAMP) - NUMTODSINTERVAL(?, 'MINUTE')"
                + " AND DATA_HORA > NVL((SELECT SENHA_ALTERADA_EM FROM " + schemas.login("USUARIO") + " WHERE ID = ?), TIMESTAMP '1970-01-01 00:00:00')",
                Long.class, usuarioId, p.contaBloqueioMinutos(), usuarioId);
        return falhas == null ? 0 : falhas;
    }

    public boolean contaBloqueada(long usuarioId, Parametros p) {
        return falhasDaConta(usuarioId, p) >= p.contaMaxFalhas();
    }

    /**
     * Registra a falha de senha/codigo da conta. Se foi ESTA que alcancou o limite, grava
     * CONTA_BLOQUEADA no log e devolve verdadeiro (quem chama avisa o dono por e-mail).
     */
    public boolean falhouNaConta(String metodo, String motivo, long usuarioId, String ip, Parametros p) {
        falhou(metodo, motivo, usuarioId, ip, p);
        if (falhasDaConta(usuarioId, p) == p.contaMaxFalhas()) {
            log.registrar("CONTA_BLOQUEADA", usuarioId, p.contaMaxFalhas() + " senhas/codigos errados; senha recusada por "
                    + p.contaBloqueioMinutos() + " min", ip, "API");
            return true;
        }
        return false;
    }
}
