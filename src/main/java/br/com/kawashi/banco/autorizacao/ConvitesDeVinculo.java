package br.com.kawashi.banco.autorizacao;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.kawashi.banco.compartilhado.erros.ConflitoException;
import br.com.kawashi.banco.compartilhado.erros.RecursoNaoEncontradoException;
import br.com.kawashi.banco.email.Avisos;
import br.com.kawashi.banco.login.SchemasDoLogin;
import br.com.kawashi.banco.login.SessaoAtual;
import br.com.kawashi.banco.login.Tokens;
import br.com.kawashi.banco.login.Usuarios;

/**
 * Convite de vinculo: o administrador de uma empresa chama para trabalhar nela uma pessoa que JA tem
 * conta (em outra empresa). Nada muda ate a PROPRIA pessoa aceitar, logada com a passkey dela.
 *
 * <p>Quem convidou, quando, de qual IP, para qual empresa e com qual cargo ficam no CONVITE_VINCULO e
 * nao mudam depois (o banco garante, 18_convite_de_vinculo.sql); o convite e a resposta vao sozinhos
 * para o LOG_PERMISSAO e o LOG_SEGURANCA. O vinculo criado no aceite aponta para o convite
 * (USUARIO_EMPRESA.CONVITE_VINCULO_ID) e e gravado em nome de quem convidou.
 *
 * <p>A resposta ao administrador e sempre a mesma ("enviado para j***@dominio"), tenha o e-mail conta
 * ou nao, ja tenha acesso ou nao: ele nao descobre quem usa o sistema em outras empresas.
 */
@Service
@Transactional
public class ConvitesDeVinculo {

    /** Dias que o convite vale (o banco aceita ate 30). */
    static final int DIAS = 7;

    public record ConviteParaMim(long id, long empresaId, String empresa, String perfil, String convidadoPor,
            OffsetDateTime criadoEm, OffsetDateTime expiraEm) {
    }

    public record ConviteDaEmpresa(long id, long usuarioId, String perfil, String situacao, String convidadoPor, String ipOrigem,
            OffsetDateTime criadoEm, OffsetDateTime expiraEm, String respondidoPor, OffsetDateTime respondidoEm) {
    }

    private final JdbcTemplate jdbc;
    private final SchemasDoLogin schemas;
    private final Usuarios usuarios;
    private final Avisos avisos;

    public ConvitesDeVinculo(JdbcTemplate jdbc, SchemasDoLogin schemas, Usuarios usuarios, Avisos avisos) {
        this.jdbc = jdbc;
        this.schemas = schemas;
        this.usuarios = usuarios;
        this.avisos = avisos;
    }

    /**
     * Convida o dono do e-mail para a empresa da sessao, com o cargo (perfil desta empresa, ja
     * conferido por quem chama). Devolve o endereco mascarado, seja qual for o caso:
     * <ul>
     *   <li>o e-mail tem conta, sem acesso a esta empresa e sem convite pendente: cria o convite e avisa;</li>
     *   <li>o e-mail nao tem conta: avisa o dono que a empresa quis dar acesso e que peca o cadastro;</li>
     *   <li>ja tem acesso ativo ou convite pendente: nada muda.</li>
     * </ul>
     */
    public String convidar(long empresaId, String email, long perfilId) {
        String ip = SessaoAtual.ipDoPedido();
        String razao = jdbc.queryForObject("SELECT RAZAO_SOCIAL FROM EMPRESA WHERE ID = ?", String.class, empresaId);
        Optional<Long> dono = usuarios.porEmail(email);
        if (dono.isEmpty()) {
            return avisos.semConta(email, razao, ip);
        }
        long usuarioId = dono.get();
        Long ocupado = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM " + schemas.login("USUARIO_EMPRESA")
                + " WHERE USUARIO_ID = ? AND EMPRESA_ID = ? AND ATIVO) + (SELECT COUNT(*) FROM " + schemas.login("CONVITE_VINCULO")
                + " WHERE USUARIO_ID = ? AND EMPRESA_ID = ? AND SITUACAO = 'PENDENTE' AND EXPIRA_EM > SYS_EXTRACT_UTC(SYSTIMESTAMP)) FROM DUAL",
                Long.class, usuarioId, empresaId, usuarioId, empresaId);
        long convidante = SessaoAtual.doPedido().map(Tokens.Sessao::usuarioId).orElseThrow();
        if (usuarioId == convidante || (ocupado != null && ocupado > 0)) {
            return Avisos.mascarar(usuarios.email(usuarioId));
        }
        // Pendente que ja venceu nao conta: e cancelado antes, para o novo poder existir.
        jdbc.update("UPDATE " + schemas.login("CONVITE_VINCULO") + " SET SITUACAO = 'CANCELADO', RESPONDIDO_POR = ?, IP_RESPOSTA = ?"
                + " WHERE USUARIO_ID = ? AND EMPRESA_ID = ? AND SITUACAO = 'PENDENTE' AND EXPIRA_EM <= SYS_EXTRACT_UTC(SYSTIMESTAMP)",
                SessaoAtual.quemNoLog(), ip, usuarioId, empresaId);
        jdbc.update("INSERT INTO " + schemas.login("CONVITE_VINCULO") + " (USUARIO_ID, EMPRESA_ID, PERFIL_ID, CONVIDADO_POR,"
                + " CONVIDADO_POR_USUARIO_ID, IP_ORIGEM, EXPIRA_EM) VALUES (?, ?, ?, ?, ?, ?, SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'DAY'))",
                usuarioId, empresaId, perfilId, "USUARIO:" + convidante, convidante, ip, DIAS);
        String perfil = jdbc.queryForObject("SELECT NOME FROM " + schemas.login("PERFIL") + " WHERE ID = ?", String.class, perfilId);
        return avisos.conviteDeVinculo(usuarioId, razao, perfil, nomeDe(convidante), ip);
    }

    /** Os convites desta empresa (pendentes e respondidos), com quem convidou, quando e de onde. */
    @Transactional(readOnly = true)
    public List<ConviteDaEmpresa> daEmpresa(long empresaId) {
        return jdbc.query("SELECT c.ID, c.USUARIO_ID, p.NOME, CASE WHEN c.SITUACAO = 'PENDENTE' AND c.EXPIRA_EM <= SYS_EXTRACT_UTC(SYSTIMESTAMP)"
                + " THEN 'VENCIDO' ELSE c.SITUACAO END, c.CONVIDADO_POR, c.IP_ORIGEM, c.CRIADO_EM, c.EXPIRA_EM, c.RESPONDIDO_POR, c.RESPONDIDO_EM"
                + " FROM " + schemas.login("CONVITE_VINCULO") + " c JOIN " + schemas.login("PERFIL") + " p ON p.ID = c.PERFIL_ID"
                + " WHERE c.EMPRESA_ID = ? ORDER BY c.ID DESC FETCH FIRST 200 ROWS ONLY",
                (r, n) -> new ConviteDaEmpresa(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6),
                        utc(r.getObject(7, LocalDateTime.class)), utc(r.getObject(8, LocalDateTime.class)), r.getString(9),
                        utc(r.getObject(10, LocalDateTime.class))),
                empresaId);
    }

    /** O administrador desiste do convite (so os pendentes desta empresa). */
    public void cancelar(long empresaId, long conviteId) {
        int mudou = jdbc.update("UPDATE " + schemas.login("CONVITE_VINCULO") + " SET SITUACAO = 'CANCELADO', RESPONDIDO_POR = ?, IP_RESPOSTA = ?"
                + " WHERE ID = ? AND EMPRESA_ID = ? AND SITUACAO = 'PENDENTE'", SessaoAtual.quemNoLog(), SessaoAtual.ipDoPedido(), conviteId, empresaId);
        if (mudou == 0) {
            throw new RecursoNaoEncontradoException("Convite pendente", conviteId);
        }
    }

    /** Os convites pendentes para quem esta logado, com a empresa, o cargo e quem convidou. */
    @Transactional(readOnly = true)
    public List<ConviteParaMim> paraMim(long usuarioId) {
        return jdbc.query("SELECT c.ID, c.EMPRESA_ID, e.RAZAO_SOCIAL, p.NOME, c.CONVIDADO_POR_USUARIO_ID, c.CRIADO_EM, c.EXPIRA_EM"
                + " FROM " + schemas.login("CONVITE_VINCULO") + " c JOIN EMPRESA e ON e.ID = c.EMPRESA_ID"
                + " JOIN " + schemas.login("PERFIL") + " p ON p.ID = c.PERFIL_ID"
                + " WHERE c.USUARIO_ID = ? AND c.SITUACAO = 'PENDENTE' AND c.EXPIRA_EM > SYS_EXTRACT_UTC(SYSTIMESTAMP) AND e.ATIVA"
                + " ORDER BY c.ID",
                (r, n) -> new ConviteParaMim(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4),
                        nomeDe((Long) r.getObject(5, Long.class)), utc(r.getObject(6, LocalDateTime.class)), utc(r.getObject(7, LocalDateTime.class))),
                usuarioId);
    }

    /**
     * A propria pessoa aceita: o vinculo e criado (ou reativado, se ela ja teve acesso a esta empresa)
     * com o cargo do convite, em nome de quem convidou, e aponta para o convite. Depois e so escolher a
     * empresa (/api/auth/empresa).
     */
    public void aceitar(long usuarioId, long conviteId) {
        Object[] c = pendenteDe(usuarioId, conviteId);
        long empresaId = (Long) c[0];
        long perfilId = (Long) c[1];
        String convidadoPor = (String) c[2];
        // O vinculo fica em nome de quem DEU o acesso; convite feito direto no banco fica em nome do proprio convidado.
        String autor = convidadoPor.matches("USUARIO:[0-9]+") ? convidadoPor : "USUARIO:" + usuarioId;
        int mudou = jdbc.update("UPDATE " + schemas.login("USUARIO_EMPRESA") + " SET ATIVO = TRUE, PERFIL_ID = ?, CONVITE_VINCULO_ID = ?,"
                + " ATUALIZADO_POR = ? WHERE USUARIO_ID = ? AND EMPRESA_ID = ?", perfilId, conviteId, autor, usuarioId, empresaId);
        if (mudou == 0) {
            jdbc.update("INSERT INTO " + schemas.login("USUARIO_EMPRESA") + " (USUARIO_ID, EMPRESA_ID, PERFIL_ID, CONVITE_VINCULO_ID, ATUALIZADO_POR)"
                    + " VALUES (?, ?, ?, ?, ?)", usuarioId, empresaId, perfilId, conviteId, autor);
        }
        responder(usuarioId, conviteId, "ACEITO");
    }

    public void recusar(long usuarioId, long conviteId) {
        pendenteDe(usuarioId, conviteId);
        responder(usuarioId, conviteId, "RECUSADO");
    }

    // ---- apoio ---------------------------------------------------------------------------------

    /** Trava o convite: e deste usuario, pendente, no prazo, e a empresa esta ativa. Senao, 404 ou 409. */
    private Object[] pendenteDe(long usuarioId, long conviteId) {
        List<Object[]> achado = jdbc.query("SELECT c.EMPRESA_ID, c.PERFIL_ID, c.CONVIDADO_POR, c.SITUACAO,"
                + " CASE WHEN c.EXPIRA_EM > SYS_EXTRACT_UTC(SYSTIMESTAMP) THEN 1 ELSE 0 END"
                + " FROM " + schemas.login("CONVITE_VINCULO") + " c WHERE c.ID = ? AND c.USUARIO_ID = ? FOR UPDATE",
                (r, n) -> new Object[] {r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getInt(5)}, conviteId, usuarioId);
        if (achado.isEmpty()) {
            throw new RecursoNaoEncontradoException("Convite", conviteId);  // de outra pessoa: nem confirma que existe
        }
        Object[] c = achado.get(0);
        if (!"PENDENTE".equals(c[3]) || (Integer) c[4] == 0) {
            throw new ConflitoException("Este convite já foi respondido, foi cancelado ou venceu.");
        }
        Boolean ativa = jdbc.queryForObject("SELECT ATIVA FROM EMPRESA WHERE ID = ?", Boolean.class, c[0]);
        if (!Boolean.TRUE.equals(ativa)) {
            throw new ConflitoException("A empresa deste convite está inativa.");
        }
        return c;
    }

    private void responder(long usuarioId, long conviteId, String situacao) {
        jdbc.update("UPDATE " + schemas.login("CONVITE_VINCULO") + " SET SITUACAO = ?, RESPONDIDO_POR = ?, IP_RESPOSTA = ? WHERE ID = ?",
                situacao, "USUARIO:" + usuarioId, SessaoAtual.ipDoPedido(), conviteId);
    }

    /** O nome da pessoa do usuario (para o convidado saber quem o chamou). */
    private String nomeDe(Long usuarioId) {
        if (usuarioId == null) {
            return "equipe da plataforma";
        }
        return jdbc.query("SELECT p.NOME FROM " + schemas.login("USUARIO") + " u JOIN PESSOA p ON p.ID = u.PESSOA_ID WHERE u.ID = ?",
                (r, n) -> r.getString(1), usuarioId).stream().findFirst().orElse("usuário " + usuarioId);
    }

    private static OffsetDateTime utc(LocalDateTime emUtc) {
        return emUtc == null ? null : emUtc.atOffset(ZoneOffset.UTC);
    }
}
