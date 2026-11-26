package br.com.kawashi.banco.login;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.kawashi.banco.compartilhado.Ips;
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

    public sealed interface Resultado permits Entrou, Opcoes, Recusado, IpBloqueado, SegundoFator {
    }

    /** {@code dispositivo}: o segredo de "confiar neste computador", quando a pessoa pediu (senao nulo). */
    public record Entrou(Tokens.TokenEmitido token, List<String> codigosDeRecuperacao, String dispositivo) implements Resultado {

        public Entrou(Tokens.TokenEmitido token, List<String> codigosDeRecuperacao) {
            this(token, codigosDeRecuperacao, null);
        }
    }

    /**
     * A senha confere, mas falta o codigo do app autenticador: {@code desafio} prende o proximo passo
     * a esta conta e vale por DESAFIO_MINUTOS. {@code codigoErrado}: o codigo anterior nao bateu.
     */
    public record SegundoFator(String desafio, boolean codigoErrado) implements Resultado {
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
    private final br.com.kawashi.banco.autorizacao.Autorizacao autorizacao;
    private final br.com.kawashi.banco.email.Avisos avisos;
    private final Tentativas tentativas;
    private final DispositivosConfiaveis dispositivos;

    public ServicoDeLogin(JdbcTemplate jdbc, SchemasDoLogin schemas, CifraDeDados cifra, ParametrosDeSeguranca parametros,
            Tokens tokens, Desafios desafios, Convites convites, CodigosDeRecuperacao codigos, Passkeys passkeys,
            Usuarios usuarios, LogDeSeguranca log, br.com.kawashi.banco.autorizacao.Autorizacao autorizacao,
            br.com.kawashi.banco.email.Avisos avisos, Tentativas tentativas, DispositivosConfiaveis dispositivos) {
        this.tentativas = tentativas;
        this.dispositivos = dispositivos;
        this.avisos = avisos;
        this.autorizacao = autorizacao;
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
            // Quem entra em uma empresa so ja comeca nela; com mais de uma, escolhe depois.
            Tokens.TokenEmitido token = tokens.emitir(login.usuarioId(), login.passkeyId(), "COMPLETO", ip, p,
                    autorizacao.empresaUnica(login.usuarioId()).orElse(null));
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
        Optional<Convites.Convite> convite = convites.valido(segredo).filter(ServicoDeLogin::serveParaPasskey);
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
        Optional<Convites.Convite> convite = convites.valido(segredo).filter(ServicoDeLogin::serveParaPasskey);
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
            avisos.aviso(usuarioId, "o acesso foi recuperado com um convite e uma passkey nova foi cadastrada; as outras sessões caíram", ip);
        }
        List<String> novos = codigos.gerar(usuarioId, p.codigosRecuperacao());
        log.registrar("CODIGOS_GERADOS", usuarioId, novos.size() + " codigos", ip, LogDeSeguranca.quem(usuarioId));
        Tokens.TokenEmitido token = tokens.emitir(usuarioId, passkeyId, "COMPLETO", ip, p,
                autorizacao.empresaUnica(usuarioId).orElse(null));
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
        avisos.aviso(usuarioId, "um código de recuperação foi usado e as outras sessões caíram", ip);
        return new Entrou(token, null);
    }

    // ---- passkeys do usuario logado ----------------------------------------------------------

    @Transactional
    public Map<String, Object> opcoesDeNovaPasskey(Tokens.Sessao sessao) {
        exigirConfirmacao(sessao);
        Parametros p = parametros.atuais();
        String desafio = desafios.criar("CADASTRO", sessao.usuarioId(), p);
        return passkeys.opcoesDeCadastro(desafio, usuarios.handle(sessao.usuarioId()), usuarios.email(sessao.usuarioId()),
                passkeys.ativasDoUsuario(sessao.usuarioId()));
    }

    /** Cadastra mais uma passkey. Vazio = a resposta nao passou na verificacao. */
    @Transactional
    public Optional<Long> novaPasskey(Tokens.Sessao sessao, String respostaJson, String nome, String ip) {
        exigirConfirmacao(sessao);
        Parametros p = parametros.atuais();
        Long id = cadastrar(sessao.usuarioId(), respostaJson, nome, ip, p, "PASSKEY");
        if (id == null) {
            conferirIp(ip, p);   // a tentativa ja foi registrada no cadastro, com o motivo
        } else {
            avisos.aviso(sessao.usuarioId(), "uma passkey nova foi cadastrada (\"" + nome + "\")", ip);
        }
        return Optional.ofNullable(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> passkeysDe(Tokens.Sessao sessao) {
        return passkeys.listar(sessao.usuarioId());
    }

    @Transactional
    public void removerPasskey(Tokens.Sessao sessao, long passkeyId, String ip) {
        exigirConfirmacao(sessao);
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
        avisos.aviso(sessao.usuarioId(), "uma passkey foi removida", ip);
    }

    @Transactional
    public List<String> novosCodigos(Tokens.Sessao sessao, String ip) {
        exigirConfirmacao(sessao);
        List<String> novos = codigos.gerar(sessao.usuarioId(), parametros.atuais().codigosRecuperacao());
        log.registrar("CODIGOS_GERADOS", sessao.usuarioId(), novos.size() + " codigos; os anteriores deixaram de valer", ip,
                LogDeSeguranca.quem(sessao.usuarioId()));
        avisos.aviso(sessao.usuarioId(), "foram gerados códigos de recuperação novos (os anteriores deixaram de valer)", ip);
        return novos;
    }

    // ---- confirmar com a passkey (antes de operacao sensivel) ------------------------------------

    /** Desafio preso ao usuario da sessao; so as passkeys dele servem. */
    @Transactional
    public Resultado opcoesDeConfirmacao(Tokens.Sessao sessao, String ip) {
        Parametros p = parametros.atuais();
        Optional<IpBloqueado> bloqueio = bloqueio(ip);
        if (bloqueio.isPresent()) {
            return bloqueio.get();
        }
        return new Opcoes(passkeys.opcoesDeConfirmacao(desafios.criar("CONFIRMACAO", sessao.usuarioId(), p),
                passkeys.ativasDoUsuario(sessao.usuarioId())));
    }

    /**
     * Confere a passkey (do PROPRIO usuario da sessao, com verificacao) e marca a sessao como
     * confirmada agora. Falhou: conta como tentativa do IP, como um login. A resposta de recusa NAO e
     * 401 (o BFF derrubaria a sessao): quem chama devolve 422.
     */
    @Transactional
    public Resultado confirmar(Tokens.Sessao sessao, String respostaJson, String ip) {
        Parametros p = parametros.atuais();
        Optional<IpBloqueado> bloqueio = bloqueio(ip);
        if (bloqueio.isPresent()) {
            tentativa("CONFIRMACAO", sessao.usuarioId(), ip, false, "IP_BLOQUEADO");
            return bloqueio.get();
        }
        try {
            String desafio = passkeys.desafioDoLogin(respostaJson);
            Optional<Desafios.Desafio> d = desafios.consumir(desafio, "CONFIRMACAO");
            if (d.isEmpty() || d.get().usuarioId() == null || d.get().usuarioId() != sessao.usuarioId()) {
                return falhou("CONFIRMACAO", "DESAFIO_INVALIDO", sessao.usuarioId(), ip, p);
            }
            Passkeys.Login login = passkeys.verificarLogin(respostaJson, desafio);
            if (login.usuarioId() != sessao.usuarioId()) {
                return falhou("CONFIRMACAO", "VERIFICACAO_FALHOU", sessao.usuarioId(), ip, p);  // passkey de outra pessoa
            }
            passkeys.registrarUso(login);
            tokens.marcarConfirmacao(sessao.tokenId());
            tentativa("CONFIRMACAO", sessao.usuarioId(), ip, true, null);
            log.registrar("PASSKEY_CONFIRMADA", sessao.usuarioId(), "passkey " + login.passkeyId(), ip, LogDeSeguranca.quem(sessao.usuarioId()));
            return new Opcoes(Map.of());
        } catch (Passkeys.Recusa r) {
            if ("CONTADOR_SUSPEITO".equals(r.motivo())) {
                log.registrar("CONTADOR_SUSPEITO", r.usuarioId(), "contador da passkey voltou: possivel copia do aparelho", ip, "API");
            }
            return falhou("CONFIRMACAO", r.motivo(), sessao.usuarioId(), ip, p);
        }
    }

    /**
     * Operacao sensivel da conta: com a sessao normal, a passkey tem de ter sido usada ha pouco. Quem
     * entrou com codigo de recuperacao (escopo CADASTRAR_PASSKEY) cadastra a passkey nova sem isso:
     * e a propria recuperacao, e ele nao tem passkey para confirmar.
     */
    private static void exigirConfirmacao(Tokens.Sessao sessao) {
        if ("COMPLETO".equals(sessao.escopo()) && !sessao.confirmadaAgora()) {
            throw new br.com.kawashi.banco.compartilhado.erros.ConfirmacaoNecessariaException();
        }
    }

    // ---- empresa da sessao ---------------------------------------------------------------------

    /**
     * Prende a sessao a uma empresa em que o usuario tem vinculo ativo: emite um token NOVO (com a
     * mesma passkey) e revoga o atual. Cada token vale para uma empresa so, e o log mostra onde
     * cada acao aconteceu. Empresa sem vinculo: 404.
     */
    @Transactional
    public Entrou escolherEmpresa(Tokens.Sessao sessao, long empresaId, String ip) {
        boolean temVinculo = autorizacao.vinculosAtivos(sessao.usuarioId()).stream().anyMatch(v -> v.empresaId() == empresaId);
        if (!temVinculo) {
            throw new RecursoNaoEncontradoException("Empresa", empresaId);
        }
        // Revoga antes de emitir: o limite de sessoes nao derruba outra sessao por causa desta troca.
        tokens.revogar(sessao.tokenId(), "TROCA_EMPRESA");
        Tokens.TokenEmitido token = tokens.emitir(sessao.usuarioId(), sessao.passkeyId(), sessao.escopo(), ip, parametros.atuais(), empresaId);
        tokens.herdarConfirmacao(sessao.tokenId(), token.id());
        log.registrar("EMPRESA_ESCOLHIDA", sessao.usuarioId(), "empresa " + empresaId, ip, LogDeSeguranca.quem(sessao.usuarioId()));
        return new Entrou(token, null);
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
        // "Sair de todos os aparelhos" tambem tira a confianca dos computadores: o 2FA volta a ser pedido.
        int computadores = dispositivos.revogarTodos(sessao.usuarioId());
        log.registrar("SAIR_DE_TODOS", sessao.usuarioId(), quantas + " sessao(oes) encerrada(s); " + computadores
                + " computador(es) de confianca revogado(s)", ip, LogDeSeguranca.quem(sessao.usuarioId()));
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

    /** Convite de redefinir senha nao cadastra passkey: senao o link do e-mail pularia o 2FA da conta. */
    private static boolean serveParaPasskey(Convites.Convite c) {
        return "CADASTRO".equals(c.finalidade()) || "NOVA_PASSKEY".equals(c.finalidade());
    }

    private Optional<IpBloqueado> bloqueio(String ip) {
        return tentativas.bloqueio(ip);
    }

    private Resultado falhou(String metodo, String motivo, Long usuarioId, String ip, Parametros p) {
        return tentativas.falhou(metodo, motivo, usuarioId, ip, p);
    }

    private void conferirIp(String ip, Parametros p) {
        tentativas.conferirIp(ip, p);
    }

    private void tentativa(String metodo, Long usuarioId, String ip, boolean sucesso, String motivo) {
        tentativas.registrar(metodo, usuarioId, ip, sucesso, motivo);
    }
}
