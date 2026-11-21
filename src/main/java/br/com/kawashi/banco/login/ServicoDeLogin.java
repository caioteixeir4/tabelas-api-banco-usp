package br.com.kawashi.banco.login;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.kawashi.banco.compartilhado.cifra.CifraDeDados;
import br.com.kawashi.banco.compartilhado.erros.RecursoNaoEncontradoException;
import br.com.kawashi.banco.compartilhado.erros.RegraDeNegocioException;
import br.com.kawashi.banco.login.ParametrosDeSeguranca.Parametros;

/**
 * As cerimonias do login por passkey: entrar, aceitar convite, cadastrar mais uma passkey, remover,
 * recuperar o acesso com codigo, sair. Regras:
 * <ul>
 *   <li>toda recusa recebe a MESMA resposta (o motivo vai so para TENTATIVA_LOGIN);</li>
 *   <li>{@code IP_MAX_FALHAS} falhas do mesmo IP em {@code IP_JANELA_MINUTOS} bloqueiam o IP por
 *       {@code IP_BLOQUEIO_MINUTOS} (robo testando credenciais, codigos ou convites);</li>
 *   <li>codigo de recuperacao da so um token de escopo CADASTRAR_PASSKEY e derruba as outras
 *       sessoes;</li>
 *   <li>a ultima passkey ativa nao pode ser removida (a pessoa ficaria sem como entrar).</li>
 * </ul>
 * As recusas NAO lancam excecao dentro da transacao, senao a tentativa e o bloqueio de IP seriam
 * desfeitos junto: o resultado volta como valor.
 */
@Service
public class ServicoDeLogin {

    public sealed interface Resultado permits Entrou, Opcoes, Recusado, IpBloqueado {
    }

    public record Entrou(Tokens.TokenEmitido token, List<String> codigosDeRecuperacao) implements Resultado {
    }

    public record Opcoes(Map<String, Object> publicKey) implements Resultado {
    }

    public record Recusado() implements Resultado {
    }

    public record IpBloqueado(long segundos) implements Resultado {
    }

    private final JdbcTemplate jdbc;
    private final SchemasDoLogin schemas;
    private final CifraDeDados cifra;
    private final ParametrosDeSeguranca parametros;
    private final Tokens tokens;
    private final Desafios desafios;
    private final Convites convites;
    private final CodigosDeRecuperacao codigos;
    private final Passkeys passkeys;
    private final Usuarios usuarios;
    private final LogDeSeguranca log;

    public ServicoDeLogin(JdbcTemplate jdbc, SchemasDoLogin schemas, CifraDeDados cifra, ParametrosDeSeguranca parametros,
            Tokens tokens, Desafios desafios, Convites convites, CodigosDeRecuperacao codigos, Passkeys passkeys,
            Usuarios usuarios, LogDeSeguranca log) {
        this.jdbc = jdbc;
        this.schemas = schemas;
        this.cifra = cifra;
        this.parametros = parametros;
        this.tokens = tokens;
        this.desafios = desafios;
        this.convites = convites;
        this.codigos = codigos;
        this.passkeys = passkeys;
        this.usuarios = usuarios;
        this.log = log;
    }

    // ---- entrar com passkey ------------------------------------------------------------------

    @Transactional
    public Resultado opcoesDeLogin(String ip) {
        Parametros p = parametros.atuais();
        Optional<IpBloqueado> bloqueio = bloqueio(ip);
        if (bloqueio.isPresent()) {
            return bloqueio.get();
        }
        return new Opcoes(passkeys.opcoesDeLogin(desafios.criar("LOGIN", null, p)));
    }

    @Transactional
    public Resultado entrar(String respostaJson, String ip) {
        Parametros p = parametros.atuais();
        Optional<IpBloqueado> bloqueio = bloqueio(ip);
        if (bloqueio.isPresent()) {
            tentativa("PASSKEY", null, ip, false, "IP_BLOQUEADO");
            return bloqueio.get();
        }
        try {
            String desafio = passkeys.desafioDoLogin(respostaJson);
            if (desafios.consumir(desafio, "LOGIN").isEmpty()) {
                return falhou("PASSKEY", "DESAFIO_INVALIDO", null, ip, p);
            }
            Passkeys.Login login = passkeys.verificarLogin(respostaJson, desafio);
            passkeys.registrarUso(login);
            Tokens.TokenEmitido token = tokens.emitir(login.usuarioId(), login.passkeyId(), "COMPLETO", ip, p);
            tentativa("PASSKEY", login.usuarioId(), ip, true, null);
            log.registrar("LOGIN", login.usuarioId(), "passkey " + login.passkeyId(), ip, LogDeSeguranca.quem(login.usuarioId()));
            return new Entrou(token, null);
        } catch (Passkeys.Recusa r) {
            if ("CONTADOR_SUSPEITO".equals(r.motivo())) {
                log.registrar("CONTADOR_SUSPEITO", r.usuarioId(), "contador da passkey voltou: possivel copia do aparelho", ip, "API");
            }
            return falhou("PASSKEY", r.motivo(), r.usuarioId(), ip, p);
        }
    }

    // ---- convite (primeira passkey, ou passkey nova de quem perdeu o acesso) ---------------------

    @Transactional
    public Resultado opcoesDoConvite(String segredo, String ip) {
        Parametros p = parametros.atuais();
        Optional<IpBloqueado> bloqueio = bloqueio(ip);
        if (bloqueio.isPresent()) {
            return bloqueio.get();
        }
        Optional<Convites.Convite> convite = convites.valido(segredo);
        if (convite.isEmpty()) {
            return falhou("CONVITE", "CONVITE_INVALIDO", null, ip, p);
        }
        long usuarioId = convite.get().usuarioId();
        String desafio = desafios.criar("CADASTRO", usuarioId, p);
        return new Opcoes(passkeys.opcoesDeCadastro(desafio, usuarios.handle(usuarioId), usuarios.email(usuarioId),
                passkeys.ativasDoUsuario(usuarioId)));
    }

    @Transactional
    public Resultado aceitarConvite(String segredo, String respostaJson, String nome, String ip) {
        Parametros p = parametros.atuais();
        Optional<IpBloqueado> bloqueio = bloqueio(ip);
        if (bloqueio.isPresent()) {
            tentativa("CONVITE", null, ip, false, "IP_BLOQUEADO");
            return bloqueio.get();
        }
        Optional<Convites.Convite> convite = convites.valido(segredo);
        if (convite.isEmpty()) {
            return falhou("CONVITE", "CONVITE_INVALIDO", null, ip, p);
        }
        long usuarioId = convite.get().usuarioId();
        Long passkeyId = cadastrar(usuarioId, respostaJson, nome, ip, p, "CONVITE");
        if (passkeyId == null) {
            conferirIp(ip, p);   // a tentativa ja foi registrada no cadastro, com o motivo
            return new Recusado();
        }
        if (!convites.usar(convite.get().id())) {
            // Outro pedido usou o convite ao mesmo tempo: este nao vale, e a passkey nao fica.
            passkeys.revogar(usuarioId, passkeyId);
            return falhou("CONVITE", "CONVITE_INVALIDO", usuarioId, ip, p);
        }
        log.registrar("CONVITE_USADO", usuarioId, convite.get().finalidade(), ip, LogDeSeguranca.quem(usuarioId));
        if ("NOVA_PASSKEY".equals(convite.get().finalidade())) {
            // Quem perdeu o acesso: as sessoes antigas (que podem estar com outra pessoa) caem.
            tokens.revogarTodos(usuarioId, "RECUPERACAO", null);
        }
        List<String> novos = codigos.gerar(usuarioId, p.codigosRecuperacao());
        log.registrar("CODIGOS_GERADOS", usuarioId, novos.size() + " codigos", ip, LogDeSeguranca.quem(usuarioId));
        Tokens.TokenEmitido token = tokens.emitir(usuarioId, passkeyId, "COMPLETO", ip, p);
        tentativa("CONVITE", usuarioId, ip, true, null);
        log.registrar("LOGIN", usuarioId, "convite aceito; passkey " + passkeyId, ip, LogDeSeguranca.quem(usuarioId));
        return new Entrou(token, novos);
    }

    // ---- codigo de recuperacao ---------------------------------------------------------------

    @Transactional
    public Resultado recuperar(String email, String codigo, String ip) {
        Parametros p = parametros.atuais();
        Optional<IpBloqueado> bloqueio = bloqueio(ip);
        if (bloqueio.isPresent()) {
            tentativa("CODIGO_RECUPERACAO", null, ip, false, "IP_BLOQUEADO");
            return bloqueio.get();
        }
        String indice = cifra.indice(Usuarios.CONTEXTO_EMAIL, Usuarios.normalizar(email));
        Long usuarioId = jdbc.query("SELECT ID FROM " + schemas.login("USUARIO") + " WHERE EMAIL_INDICE = ? AND ATIVO = TRUE",
                (r, n) -> r.getLong(1), indice).stream().findFirst().orElse(null);
        if (usuarioId == null || !codigos.usar(usuarioId, codigo)) {
            return falhou("CODIGO_RECUPERACAO", "CODIGO_INVALIDO", usuarioId, ip, p);
        }
        int caidas = tokens.revogarTodos(usuarioId, "RECUPERACAO", null);
        Tokens.TokenEmitido token = tokens.emitir(usuarioId, null, "CADASTRAR_PASSKEY", ip, p);
        tentativa("CODIGO_RECUPERACAO", usuarioId, ip, true, null);
        log.registrar("CODIGO_USADO", usuarioId, caidas + " sessao(oes) derrubada(s); token so para cadastrar passkey", ip,
                LogDeSeguranca.quem(usuarioId));
        return new Entrou(token, null);
    }

    // ---- passkeys do usuario logado ----------------------------------------------------------

    @Transactional
    public Map<String, Object> opcoesDeNovaPasskey(Tokens.Sessao sessao) {
        Parametros p = parametros.atuais();
        String desafio = desafios.criar("CADASTRO", sessao.usuarioId(), p);
        return passkeys.opcoesDeCadastro(desafio, usuarios.handle(sessao.usuarioId()), usuarios.email(sessao.usuarioId()),
                passkeys.ativasDoUsuario(sessao.usuarioId()));
    }

    /** Cadastra mais uma passkey. Vazio = a resposta nao passou na verificacao. */
    @Transactional
    public Optional<Long> novaPasskey(Tokens.Sessao sessao, String respostaJson, String nome, String ip) {
        Parametros p = parametros.atuais();
        Long id = cadastrar(sessao.usuarioId(), respostaJson, nome, ip, p, "PASSKEY");
        if (id == null) {
            conferirIp(ip, p);   // a tentativa ja foi registrada no cadastro, com o motivo
        }
        return Optional.ofNullable(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> passkeysDe(Tokens.Sessao sessao) {
        return passkeys.listar(sessao.usuarioId());
    }

    @Transactional
    public void removerPasskey(Tokens.Sessao sessao, long passkeyId, String ip) {
        List<String> ativas = passkeys.ativasDoUsuario(sessao.usuarioId());
        if (ativas.size() <= 1) {
            throw new RegraDeNegocioException("Esta é a sua única passkey: cadastre outra antes de remover esta, senão você "
                    + "fica sem como entrar.");
        }
        if (!passkeys.revogar(sessao.usuarioId(), passkeyId)) {
            throw new RecursoNaoEncontradoException("Passkey", passkeyId);
        }
        int caidas = tokens.revogarDaPasskey(passkeyId, "PASSKEY_REMOVIDA");
        log.registrar("PASSKEY_REMOVIDA", sessao.usuarioId(), "passkey " + passkeyId + "; " + caidas + " sessao(oes) dela encerrada(s)",
                ip, LogDeSeguranca.quem(sessao.usuarioId()));
    }

    @Transactional
    public List<String> novosCodigos(Tokens.Sessao sessao, String ip) {
        List<String> novos = codigos.gerar(sessao.usuarioId(), parametros.atuais().codigosRecuperacao());
        log.registrar("CODIGOS_GERADOS", sessao.usuarioId(), novos.size() + " codigos; os anteriores deixaram de valer", ip,
                LogDeSeguranca.quem(sessao.usuarioId()));
        return novos;
    }

    // ---- sair ---------------------------------------------------------------------------------

    @Transactional
    public void sair(Tokens.Sessao sessao, String ip) {
        tokens.revogar(sessao.tokenId(), "LOGOUT");
        log.registrar("LOGOUT", sessao.usuarioId(), null, ip, LogDeSeguranca.quem(sessao.usuarioId()));
    }

    @Transactional
    public int sairDeTodos(Tokens.Sessao sessao, String ip) {
        int quantas = tokens.revogarTodos(sessao.usuarioId(), "SAIR_DE_TODOS", null);
        log.registrar("SAIR_DE_TODOS", sessao.usuarioId(), quantas + " sessao(oes) encerrada(s)", ip,
                LogDeSeguranca.quem(sessao.usuarioId()));
        return quantas;
    }

    // ---- apoio ---------------------------------------------------------------------------------

    /** Verifica e grava uma passkey nova do usuario. Nulo = recusada (o motivo vai para a tentativa). */
    private Long cadastrar(long usuarioId, String respostaJson, String nome, String ip, Parametros p, String metodo) {
        try {
            String desafio = passkeys.desafioDoCadastro(respostaJson);
            Optional<Desafios.Desafio> d = desafios.consumir(desafio, "CADASTRO");
            if (d.isEmpty() || d.get().usuarioId() == null || d.get().usuarioId() != usuarioId) {
                tentativa(metodo, usuarioId, ip, false, "DESAFIO_INVALIDO");
                return null;
            }
            Passkeys.Cadastro cadastro = passkeys.verificarCadastro(respostaJson, desafio);
            if (passkeys.credencialJaExiste(cadastro.credencialId())
                    || passkeys.ativasDoUsuario(usuarioId).size() >= p.passkeysMaximas()) {
                tentativa(metodo, usuarioId, ip, false, "VERIFICACAO_FALHOU");
                return null;
            }
            long id = passkeys.gravar(usuarioId, cadastro, nome);
            log.registrar("PASSKEY_CADASTRADA", usuarioId, "passkey " + id + (cadastro.backupElegivel() ? " (sincronizada)" : ""),
                    ip, LogDeSeguranca.quem(usuarioId));
            return id;
        } catch (Passkeys.Recusa r) {
            tentativa(metodo, usuarioId, ip, false, r.motivo());
            return null;
        }
    }

    private Optional<IpBloqueado> bloqueio(String ip) {
        return jdbc.query("SELECT CEIL((CAST(BLOQUEADO_ATE AS DATE) - CAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) AS DATE)) * 86400)"
                + " FROM " + schemas.sessao("BLOQUEIO_IP") + " WHERE IP = ? AND BLOQUEADO_ATE > SYS_EXTRACT_UTC(SYSTIMESTAMP)",
                (r, n) -> new IpBloqueado(Math.max(1, r.getLong(1))), ip).stream().findFirst();
    }

    private Resultado falhou(String metodo, String motivo, Long usuarioId, String ip, Parametros p) {
        tentativa(metodo, usuarioId, ip, false, motivo);
        conferirIp(ip, p);
        return new Recusado();
    }

    /** Se o IP passou do limite de falhas na janela, bloqueia o IP. */
    private void conferirIp(String ip, Parametros p) {
        Long falhas = jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.log("TENTATIVA_LOGIN")
                + " WHERE IP_ORIGEM = ? AND SUCESSO = FALSE AND DATA_HORA > SYS_EXTRACT_UTC(SYSTIMESTAMP) - NUMTODSINTERVAL(?, 'MINUTE')",
                Long.class, ip, p.ipJanelaMinutos());
        if (falhas != null && falhas >= p.ipMaxFalhas()) {
            String motivoDoBloqueio = falhas + " falhas em " + p.ipJanelaMinutos() + " min";
            jdbc.update("MERGE INTO " + schemas.sessao("BLOQUEIO_IP") + " b USING (SELECT ? AS IP FROM DUAL) n ON (b.IP = n.IP)"
                    + " WHEN MATCHED THEN UPDATE SET b.BLOQUEADO_ATE = SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'MINUTE'), b.MOTIVO = ?"
                    + " WHEN NOT MATCHED THEN INSERT (IP, BLOQUEADO_ATE, MOTIVO) VALUES (n.IP, SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'MINUTE'), ?)",
                    ip, p.ipBloqueioMinutos(), motivoDoBloqueio, p.ipBloqueioMinutos(), motivoDoBloqueio);
            log.registrar("IP_BLOQUEADO", null, motivoDoBloqueio + "; bloqueado por " + p.ipBloqueioMinutos() + " min", ip, "API");
        }
    }

    private void tentativa(String metodo, Long usuarioId, String ip, boolean sucesso, String motivo) {
        jdbc.update("INSERT INTO " + schemas.log("TENTATIVA_LOGIN") + " (METODO, USUARIO_ID, IP_ORIGEM, SUCESSO, MOTIVO)"
                + " VALUES (?, ?, ?, ?, ?)", metodo, usuarioId, ip, sucesso, motivo);
    }
}
