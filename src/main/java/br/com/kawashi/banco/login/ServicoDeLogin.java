package br.com.kawashi.banco.login;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.kawashi.banco.compartilhado.cifra.CifraDeDados;
import br.com.kawashi.banco.login.ParametrosDeSeguranca.Parametros;

/**
 * Entrar, sair e trocar a senha. Regras (numeros em PARAMETRO_SEGURANCA):
 * <ul>
 *   <li>e-mail inexistente, usuario inativo, conta bloqueada e senha errada recebem a MESMA resposta
 *       e levam o MESMO tempo (a senha e conferida contra um hash de disfarce): quem testa nao
 *       descobre quais e-mails tem conta;</li>
 *   <li>{@code LOGIN_MAX_TENTATIVAS} senhas erradas seguidas bloqueiam a conta por
 *       {@code LOGIN_BLOQUEIO_MINUTOS}, mesmo para a senha certa;</li>
 *   <li>{@code IP_MAX_FALHAS} erros do mesmo IP em {@code IP_JANELA_MINUTOS} bloqueiam o IP por
 *       {@code IP_BLOQUEIO_MINUTOS}, mesmo testando e-mails diferentes (robo);</li>
 *   <li>senha com mais de {@code SENHA_VALIDADE_DIAS} entra, mas recebe um token que so serve para
 *       trocar a senha;</li>
 *   <li>trocar a senha derruba TODAS as sessoes, e a nova nao repete as ultimas
 *       {@code SENHA_HISTORICO}.</li>
 * </ul>
 * Toda tentativa vai para TENTATIVA_LOGIN (sem o e-mail: so a impressao digital dele) e todo evento
 * para LOG_SEGURANCA. As recusas NAO lancam excecao dentro da transacao, senao o contador de
 * tentativas e o bloqueio seriam desfeitos junto: o resultado volta como valor.
 */
@Service
public class ServicoDeLogin {

    public sealed interface Resultado permits Entrou, Recusado, IpBloqueado {
    }

    public record Entrou(Tokens.TokenEmitido token) implements Resultado {
    }

    public record Recusado() implements Resultado {
    }

    public record IpBloqueado(long segundos) implements Resultado {
    }

    public enum ResultadoDaTroca { TROCADA, SENHA_ATUAL_ERRADA, NOVA_REPETE_ANTERIOR, NOVA_FRACA }

    public record Troca(ResultadoDaTroca resultado, List<String> problemas) {
    }

    private final JdbcTemplate jdbc;
    private final SchemasDoLogin schemas;
    private final CifraDeDados cifra;
    private final HashDeSenha hashDeSenha;
    private final ParametrosDeSeguranca parametros;
    private final Tokens tokens;
    private final LogDeSeguranca log;

    public ServicoDeLogin(JdbcTemplate jdbc, SchemasDoLogin schemas, CifraDeDados cifra, HashDeSenha hashDeSenha,
            ParametrosDeSeguranca parametros, Tokens tokens, LogDeSeguranca log) {
        this.jdbc = jdbc;
        this.schemas = schemas;
        this.cifra = cifra;
        this.hashDeSenha = hashDeSenha;
        this.parametros = parametros;
        this.tokens = tokens;
        this.log = log;
    }

    private record UsuarioDoLogin(long id, String senhaHash, boolean ativo, boolean bloqueado, boolean senhaVencida,
            int tentativas) {
    }

    @Transactional
    public Resultado entrar(String email, char[] senha, String ip) {
        Parametros p = parametros.atuais();

        Long segundosDeBloqueio = jdbc.query("SELECT CEIL((CAST(BLOQUEADO_ATE AS DATE) - CAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) AS DATE)) * 86400)"
                + " FROM " + schemas.sessao("BLOQUEIO_IP") + " WHERE IP = ? AND BLOQUEADO_ATE > SYS_EXTRACT_UTC(SYSTIMESTAMP)",
                (r, n) -> r.getLong(1), ip).stream().findFirst().orElse(null);
        if (segundosDeBloqueio != null) {
            hashDeSenha.confereDisfarce(senha);
            tentativa(null, null, ip, false, "IP_BLOQUEADO");
            return new IpBloqueado(Math.max(1, segundosDeBloqueio));
        }

        String indice = cifra.indice(Usuarios.CONTEXTO_EMAIL, Usuarios.normalizar(email));
        UsuarioDoLogin u = jdbc.query("SELECT ID, SENHA_HASH, CASE WHEN ATIVO THEN 1 ELSE 0 END,"
                + " CASE WHEN BLOQUEADO_ATE > SYS_EXTRACT_UTC(SYSTIMESTAMP) THEN 1 ELSE 0 END,"
                + " CASE WHEN SENHA_ALTERADA_EM + NUMTODSINTERVAL(?, 'DAY') < SYS_EXTRACT_UTC(SYSTIMESTAMP) THEN 1 ELSE 0 END, TENTATIVAS_FALHAS"
                + " FROM " + schemas.login("USUARIO") + " WHERE EMAIL_INDICE = ?",
                (r, n) -> new UsuarioDoLogin(r.getLong(1), r.getString(2), r.getInt(3) == 1, r.getInt(4) == 1,
                        r.getInt(5) == 1, r.getInt(6)), p.senhaValidadeDias(), indice).stream().findFirst().orElse(null);

        if (u == null) {
            hashDeSenha.confereDisfarce(senha);
            return falhou(indice, null, ip, "USUARIO_INEXISTENTE", p);
        }
        if (!u.ativo()) {
            hashDeSenha.confereDisfarce(senha);
            return falhou(indice, u.id(), ip, "USUARIO_INATIVO", p);
        }
        if (u.bloqueado()) {
            // Nem confere a senha: durante o bloqueio, acertar nao pode servir de pista.
            hashDeSenha.confereDisfarce(senha);
            return falhou(indice, u.id(), ip, "CONTA_BLOQUEADA", p);
        }
        if (!hashDeSenha.confere(senha, u.senhaHash())) {
            Integer agora = somarTentativa(u.id());
            if (agora != null && agora >= p.loginMaxTentativas()) {
                jdbc.update("UPDATE " + schemas.login("USUARIO") + " SET TENTATIVAS_FALHAS = 0,"
                        + " BLOQUEADO_ATE = SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'MINUTE') WHERE ID = ?", p.loginBloqueioMinutos(), u.id());
                log.registrar("CONTA_BLOQUEADA", u.id(), p.loginMaxTentativas() + " senhas erradas seguidas; bloqueada por "
                        + p.loginBloqueioMinutos() + " min", ip, "API");
            }
            return falhou(indice, u.id(), ip, "SENHA_ERRADA", p);
        }

        // Senha certa.
        if (hashDeSenha.precisaRefazer(u.senhaHash())) {
            jdbc.update("UPDATE " + schemas.login("USUARIO") + " SET SENHA_HASH = ? WHERE ID = ?", hashDeSenha.gerar(senha), u.id());
        }
        jdbc.update("UPDATE " + schemas.login("USUARIO") + " SET TENTATIVAS_FALHAS = 0, BLOQUEADO_ATE = NULL,"
                + " ATUALIZADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) WHERE ID = ?", u.id());
        String escopo = u.senhaVencida() ? "TROCAR_SENHA" : "COMPLETO";
        Tokens.TokenEmitido emitido = tokens.emitir(u.id(), escopo, ip, p);
        tentativa(indice, u.id(), ip, true, null);
        log.registrar("LOGIN", u.id(), "escopo " + escopo, ip, LogDeSeguranca.quem(u.id()));
        return new Entrou(emitido);
    }

    @Transactional
    public void sair(Tokens.Sessao sessao, String ip) {
        tokens.revogar(sessao.tokenId(), "LOGOUT");
        log.registrar("LOGOUT", sessao.usuarioId(), null, ip, LogDeSeguranca.quem(sessao.usuarioId()));
    }

    @Transactional
    public int sairDeTodos(Tokens.Sessao sessao, String ip) {
        int quantas = tokens.revogarTodos(sessao.usuarioId(), "SAIR_DE_TODOS");
        log.registrar("SAIR_DE_TODOS", sessao.usuarioId(), quantas + " sessao(oes) encerrada(s)", ip,
                LogDeSeguranca.quem(sessao.usuarioId()));
        return quantas;
    }

    @Transactional
    public Troca trocarSenha(Tokens.Sessao sessao, char[] atual, char[] nova, String ip) {
        Parametros p = parametros.atuais();
        String hashAtual = jdbc.queryForObject("SELECT SENHA_HASH FROM " + schemas.login("USUARIO") + " WHERE ID = ?",
                String.class, sessao.usuarioId());
        if (!hashDeSenha.confere(atual, hashAtual)) {
            // Conta como tentativa errada: um token roubado nao vira um jeito de adivinhar a senha.
            Integer agora = somarTentativa(sessao.usuarioId());
            if (agora != null && agora >= p.loginMaxTentativas()) {
                jdbc.update("UPDATE " + schemas.login("USUARIO") + " SET TENTATIVAS_FALHAS = 0,"
                        + " BLOQUEADO_ATE = SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'MINUTE') WHERE ID = ?",
                        p.loginBloqueioMinutos(), sessao.usuarioId());
                tokens.revogarTodos(sessao.usuarioId(), "SUSPEITA");
                log.registrar("CONTA_BLOQUEADA", sessao.usuarioId(), "senha atual errada " + p.loginMaxTentativas()
                        + " vezes na troca de senha; sessoes derrubadas", ip, LogDeSeguranca.quem(sessao.usuarioId()));
            }
            return new Troca(ResultadoDaTroca.SENHA_ATUAL_ERRADA, List.of("A senha atual não confere."));
        }
        String email = cifra.decifrar(Usuarios.CONTEXTO_EMAIL, jdbc.queryForObject("SELECT EMAIL_CIFRADO FROM "
                + schemas.login("USUARIO") + " WHERE ID = ?", String.class, sessao.usuarioId()));
        List<String> problemas = new ArrayList<>(PoliticaDeSenha.problemas(new String(nova), email, p.senhaMinimo()));
        if (!problemas.isEmpty()) {
            return new Troca(ResultadoDaTroca.NOVA_FRACA, problemas);
        }
        List<String> anteriores = new ArrayList<>();
        anteriores.add(hashAtual);
        anteriores.addAll(jdbc.queryForList("SELECT SENHA_HASH FROM " + schemas.login("HISTORICO_SENHA")
                + " WHERE USUARIO_ID = ? ORDER BY CRIADO_EM DESC, ID DESC FETCH FIRST ? ROWS ONLY",
                String.class, sessao.usuarioId(), p.senhaHistorico()));
        for (String anterior : anteriores) {
            if (hashDeSenha.confere(nova, anterior)) {
                return new Troca(ResultadoDaTroca.NOVA_REPETE_ANTERIOR, List.of("A senha nova não pode repetir a atual nem as "
                        + p.senhaHistorico() + " anteriores."));
            }
        }
        jdbc.update("INSERT INTO " + schemas.login("HISTORICO_SENHA") + " (USUARIO_ID, SENHA_HASH) VALUES (?, ?)",
                sessao.usuarioId(), hashAtual);
        jdbc.update("UPDATE " + schemas.login("USUARIO") + " SET SENHA_HASH = ?, SENHA_ALTERADA_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP),"
                + " TENTATIVAS_FALHAS = 0, ATUALIZADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) WHERE ID = ?", hashDeSenha.gerar(nova), sessao.usuarioId());
        int caidas = tokens.revogarTodos(sessao.usuarioId(), "TROCA_SENHA");
        log.registrar("SENHA_ALTERADA", sessao.usuarioId(), caidas + " sessao(oes) encerrada(s)", ip,
                LogDeSeguranca.quem(sessao.usuarioId()));
        return new Troca(ResultadoDaTroca.TROCADA, List.of());
    }

    private Resultado falhou(String indice, Long usuarioId, String ip, String motivo, Parametros p) {
        tentativa(indice, usuarioId, ip, false, motivo);
        Long falhas = jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.log("TENTATIVA_LOGIN")
                + " WHERE IP_ORIGEM = ? AND SUCESSO = FALSE AND DATA_HORA > SYS_EXTRACT_UTC(SYSTIMESTAMP) - NUMTODSINTERVAL(?, 'MINUTE')",
                Long.class, ip, p.ipJanelaMinutos());
        if (falhas != null && falhas >= p.ipMaxFalhas()) {
            jdbc.update("MERGE INTO " + schemas.sessao("BLOQUEIO_IP") + " b USING (SELECT ? AS IP FROM DUAL) n ON (b.IP = n.IP)"
                    + " WHEN MATCHED THEN UPDATE SET b.BLOQUEADO_ATE = SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'MINUTE'), b.MOTIVO = ?"
                    + " WHEN NOT MATCHED THEN INSERT (IP, BLOQUEADO_ATE, MOTIVO) VALUES (n.IP, SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'MINUTE'), ?)",
                    ip, p.ipBloqueioMinutos(), falhas + " logins errados em " + p.ipJanelaMinutos() + " min",
                    p.ipBloqueioMinutos(), falhas + " logins errados em " + p.ipJanelaMinutos() + " min");
            log.registrar("IP_BLOQUEADO", null, falhas + " logins errados em " + p.ipJanelaMinutos() + " min; bloqueado por "
                    + p.ipBloqueioMinutos() + " min", ip, "API");
        }
        return new Recusado();
    }

    /** Soma um erro de senha e devolve o total. O UPDATE trava a linha: dois erros ao mesmo tempo contam dois. */
    private Integer somarTentativa(long usuarioId) {
        jdbc.update("UPDATE " + schemas.login("USUARIO")
                + " SET TENTATIVAS_FALHAS = TENTATIVAS_FALHAS + 1, ATUALIZADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP) WHERE ID = ?", usuarioId);
        return jdbc.queryForObject("SELECT TENTATIVAS_FALHAS FROM " + schemas.login("USUARIO") + " WHERE ID = ?",
                Integer.class, usuarioId);
    }

    private void tentativa(String indice, Long usuarioId, String ip, boolean sucesso, String motivo) {
        jdbc.update("INSERT INTO " + schemas.log("TENTATIVA_LOGIN") + " (EMAIL_INDICE, USUARIO_ID, IP_ORIGEM, SUCESSO, MOTIVO)"
                + " VALUES (?, ?, ?, ?, ?)", indice, usuarioId, ip, sucesso, motivo);
    }
}
