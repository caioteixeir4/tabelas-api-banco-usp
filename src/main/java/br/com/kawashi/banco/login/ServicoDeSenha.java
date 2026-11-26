package br.com.kawashi.banco.login;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.kawashi.banco.compartilhado.cifra.CifraDeDados;
import br.com.kawashi.banco.compartilhado.erros.ConflitoException;
import br.com.kawashi.banco.compartilhado.erros.RecursoNaoEncontradoException;
import br.com.kawashi.banco.compartilhado.erros.RegraDeNegocioException;
import br.com.kawashi.banco.login.ParametrosDeSeguranca.Parametros;
import br.com.kawashi.banco.login.ServicoDeLogin.Entrou;
import br.com.kawashi.banco.login.ServicoDeLogin.Opcoes;
import br.com.kawashi.banco.login.ServicoDeLogin.Recusado;
import br.com.kawashi.banco.login.ServicoDeLogin.Resultado;
import br.com.kawashi.banco.login.ServicoDeLogin.SegundoFator;

/**
 * O login por senha, com o 2FA (app autenticador) opcional e fortemente recomendado. Regras:
 * <ul>
 *   <li>o identificador pode ser o e-mail, o nome de usuario ou o celular (este so se a pessoa
 *       ligou o login pelo celular, o que exige o 2FA: regra do banco);</li>
 *   <li>toda recusa recebe a MESMA resposta; conta que nao existe gasta o mesmo tempo de uma senha
 *       errada (o tempo nao revela quem tem conta);</li>
 *   <li>bloqueio do IP (como na passkey) e da conta (CONTA_MAX_FALHAS senhas/codigos errados), ver
 *       {@link Tentativas};</li>
 *   <li>com o 2FA ativo, a senha certa leva ao passo do codigo, a nao ser num computador de
 *       confianca ({@link DispositivosConfiaveis});</li>
 *   <li>administrador (de empresa ou da plataforma) que entra com senha precisa do 2FA: sem ele, a
 *       sessao nasce com escopo CONFIGURAR_2FA e so serve para ativa-lo. Quem entra com passkey ja
 *       tem dois fatores (o aparelho e a digital/PIN) e nao passa por isso;</li>
 *   <li>trocar ou redefinir a senha derruba as outras sessoes e esquece os computadores de
 *       confianca; o dono recebe aviso por e-mail.</li>
 * </ul>
 * Como no {@link ServicoDeLogin}, a recusa que registra tentativa volta como valor (excecao
 * desfaria o registro).
 */
@Service
public class ServicoDeSenha {

    public static final String CONTEXTO_TOTP = "USUARIO.TOTP_SEGREDO";

    /** O que a tela de ativar o 2FA mostra: o QR code (matriz de 0/1) e o segredo para digitar a mao. */
    public record Configuracao2fa(String segredo, String uri, List<String> qr) {
    }

    /** {@code tokenNovo}: quem estava com escopo CONFIGURAR_2FA ganha a sessao completa. */
    public record Ativacao(boolean ativado, Tokens.TokenEmitido tokenNovo) {
    }

    public enum Redefinicao { FEITA, INVALIDA, PRECISA_CODIGO, CODIGO_ERRADO, IP_BLOQUEADO }

    /** A situacao da conta para o /eu e para a tela de configuracoes. */
    public record Conta(boolean temSenha, boolean doisFatores, boolean precisaDe2fa, String nomeUsuario, String celular,
            boolean loginPorCelular) {
    }

    private final JdbcTemplate jdbc;
    private final SchemasDoLogin schemas;
    private final CifraDeDados cifra;
    private final ParametrosDeSeguranca parametros;
    private final Tokens tokens;
    private final Desafios desafios;
    private final Convites convites;
    private final CodigosDeRecuperacao codigos;
    private final Usuarios usuarios;
    private final LogDeSeguranca log;
    private final br.com.kawashi.banco.autorizacao.Autorizacao autorizacao;
    private final br.com.kawashi.banco.email.Avisos avisos;
    private final Tentativas tentativas;
    private final DispositivosConfiaveis dispositivos;
    private final Senhas senhas;
    private final Totp totp;

    public ServicoDeSenha(JdbcTemplate jdbc, SchemasDoLogin schemas, CifraDeDados cifra, ParametrosDeSeguranca parametros, Tokens tokens,
            Desafios desafios, Convites convites, CodigosDeRecuperacao codigos, Usuarios usuarios, LogDeSeguranca log,
            br.com.kawashi.banco.autorizacao.Autorizacao autorizacao, br.com.kawashi.banco.email.Avisos avisos, Tentativas tentativas,
            DispositivosConfiaveis dispositivos, Senhas senhas, Totp totp) {
        this.jdbc = jdbc;
        this.schemas = schemas;
        this.cifra = cifra;
        this.parametros = parametros;
        this.tokens = tokens;
        this.desafios = desafios;
        this.convites = convites;
        this.codigos = codigos;
        this.usuarios = usuarios;
        this.log = log;
        this.autorizacao = autorizacao;
        this.avisos = avisos;
        this.tentativas = tentativas;
        this.dispositivos = dispositivos;
        this.senhas = senhas;
        this.totp = totp;
    }

    // ---- entrar ------------------------------------------------------------------------------

    /** Primeiro passo: identificador + senha. {@code dispositivo}: o segredo do cookie de confianca, se houver. */
    @Transactional
    public Resultado entrar(String identificador, String senha, String dispositivo, String ip) {
        Parametros p = parametros.atuais();
        Optional<ServicoDeLogin.IpBloqueado> bloqueio = tentativas.bloqueio(ip);
        if (bloqueio.isPresent()) {
            tentativas.registrar("SENHA", null, ip, false, "IP_BLOQUEADO");
            return bloqueio.get();
        }
        Optional<Usuarios.Credenciais> c = usuarios.porIdentificador(identificador);
        if (c.isEmpty() || !c.get().ativo() || c.get().senhaHash() == null) {
            senhas.gastarOMesmoTempo(senha);
            return tentativas.falhou("SENHA", c.isPresent() && !c.get().ativo() ? "USUARIO_INATIVO" : "CREDENCIAL_DESCONHECIDA",
                    c.map(Usuarios.Credenciais::id).orElse(null), ip, p);
        }
        long usuarioId = c.get().id();
        if (tentativas.contaBloqueada(usuarioId, p)) {
            senhas.gastarOMesmoTempo(senha);
            return tentativas.falhou("SENHA", "CONTA_BLOQUEADA", usuarioId, ip, p);
        }
        if (!senhas.confere(senha, c.get().senhaHash())) {
            return falhouNaConta("SENHA", "SENHA_INVALIDA", usuarioId, ip, p);
        }
        boolean confiavel = c.get().totpAtivo() && dispositivos.confiavel(dispositivo, usuarioId);
        if (c.get().totpAtivo() && !confiavel) {
            return new SegundoFator(desafios.criar("SEGUNDO_FATOR", usuarioId, p), false);
        }
        return abrirSessao(usuarioId, c.get().totpAtivo(), ip, p, "SENHA", confiavel ? "senha; computador de confianca" : "senha", null);
    }

    /**
     * Segundo passo: o codigo do app autenticador. Codigo errado: um desafio novo (a pessoa tenta de
     * novo sem redigitar a senha), ate o limite da conta ou do IP. {@code confiar}: marca o computador.
     */
    @Transactional
    public Resultado segundoFator(String desafio, String codigo, boolean confiar, String nomeDoComputador, String ip) {
        Parametros p = parametros.atuais();
        Optional<ServicoDeLogin.IpBloqueado> bloqueio = tentativas.bloqueio(ip);
        if (bloqueio.isPresent()) {
            tentativas.registrar("SEGUNDO_FATOR", null, ip, false, "IP_BLOQUEADO");
            return bloqueio.get();
        }
        Optional<Desafios.Desafio> d = desafios.consumir(desafio, "SEGUNDO_FATOR");
        if (d.isEmpty() || d.get().usuarioId() == null) {
            return tentativas.falhou("SEGUNDO_FATOR", "DESAFIO_INVALIDO", null, ip, p);
        }
        long usuarioId = d.get().usuarioId();
        Usuarios.Credenciais c = usuarios.credenciais(usuarioId);
        if (!c.ativo() || !c.totpAtivo()) {
            return tentativas.falhou("SEGUNDO_FATOR", c.ativo() ? "DESAFIO_INVALIDO" : "USUARIO_INATIVO", usuarioId, ip, p);
        }
        if (tentativas.contaBloqueada(usuarioId, p)) {
            return tentativas.falhou("SEGUNDO_FATOR", "CONTA_BLOQUEADA", usuarioId, ip, p);
        }
        if (!codigoConfere(c, codigo)) {
            Resultado r = falhouNaConta("SEGUNDO_FATOR", "CODIGO_2FA_INVALIDO", usuarioId, ip, p);
            if (tentativas.contaBloqueada(usuarioId, p) || tentativas.bloqueio(ip).isPresent()) {
                return r;
            }
            return new SegundoFator(desafios.criar("SEGUNDO_FATOR", usuarioId, p), true);
        }
        String novo = confiar ? dispositivos.confiar(usuarioId, nomeDoComputador(nomeDoComputador), ip, p.dispositivoConfiavelDias()) : null;
        return abrirSessao(usuarioId, true, ip, p, "SEGUNDO_FATOR",
                "senha + 2FA" + (confiar ? "; computador marcado como de confianca" : ""), novo);
    }

    // ---- convite com senha (em vez da passkey) -------------------------------------------------

    /** Aceita o convite de cadastro (ou de acesso perdido) criando uma senha. Devolve a sessao e os codigos de recuperacao. */
    @Transactional
    public Resultado aceitarConvite(String segredo, String senha, String ip) {
        Parametros p = parametros.atuais();
        Optional<ServicoDeLogin.IpBloqueado> bloqueio = tentativas.bloqueio(ip);
        if (bloqueio.isPresent()) {
            tentativas.registrar("CONVITE", null, ip, false, "IP_BLOQUEADO");
            return bloqueio.get();
        }
        Optional<Convites.Convite> convite = convites.valido(segredo)
                .filter(cv -> "CADASTRO".equals(cv.finalidade()) || "NOVA_PASSKEY".equals(cv.finalidade()));
        if (convite.isEmpty()) {
            return tentativas.falhou("CONVITE", "CONVITE_INVALIDO", null, ip, p);
        }
        long usuarioId = convite.get().usuarioId();
        senhas.exigirBoa(senha, p.senhaMinCaracteres(), usuarios.email(usuarioId), usuarios.nome(usuarioId));
        if (!convites.usar(convite.get().id())) {
            return tentativas.falhou("CONVITE", "CONVITE_INVALIDO", usuarioId, ip, p);
        }
        gravarSenha(usuarioId, senha);
        log.registrar("CONVITE_USADO", usuarioId, convite.get().finalidade() + "; senha criada", ip, LogDeSeguranca.quem(usuarioId));
        if ("NOVA_PASSKEY".equals(convite.get().finalidade())) {
            tokens.revogarTodos(usuarioId, "RECUPERACAO", null);
            dispositivos.revogarTodos(usuarioId);
            avisos.aviso(usuarioId, "o acesso foi recuperado com um convite e uma senha nova foi criada; as outras sessões caíram", ip);
        }
        List<String> novos = codigos.gerar(usuarioId, p.codigosRecuperacao());
        log.registrar("CODIGOS_GERADOS", usuarioId, novos.size() + " codigos", ip, LogDeSeguranca.quem(usuarioId));
        Entrou e = (Entrou) abrirSessao(usuarioId, usuarios.credenciais(usuarioId).totpAtivo(), ip, p, "CONVITE",
                "convite aceito com senha", null);
        return new Entrou(e.token(), novos, null);
    }

    // ---- esqueci / redefinir -------------------------------------------------------------------

    /**
     * "Esqueci minha senha": se o identificador e de uma conta ativa COM senha, manda o link para o
     * e-mail dela. A resposta e sempre a mesma (nao revela quem tem conta). Conta so com passkey nao
     * recebe: o e-mail nao vira uma porta para criar senha sem a passkey (para isso ha os codigos de
     * recuperacao).
     */
    @Transactional
    public void esqueci(String identificador, String ip) {
        Parametros p = parametros.atuais();
        if (tentativas.bloqueio(ip).isPresent()) {
            return;
        }
        usuarios.porIdentificador(identificador).filter(c -> c.ativo() && c.senhaHash() != null).ifPresent(c ->
                convites.criarRedefinicao(c.id(), p.redefinicaoSenhaMinutos()).ifPresent(segredo -> {
                    log.registrar("CONVITE_CRIADO", c.id(), "redefinir senha (pedido pelo login)", ip, "API");
                    avisos.redefinicaoDeSenha(c.id(), segredo, p.redefinicaoSenhaMinutos(), ip);
                }));
    }

    /** O link do e-mail + a senha nova (+ o codigo do 2FA, se a conta tem). Todas as sessoes caem. */
    @Transactional
    public Redefinicao redefinir(String segredo, String nova, String codigo, String ip) {
        Parametros p = parametros.atuais();
        if (tentativas.bloqueio(ip).isPresent()) {
            tentativas.registrar("CONVITE", null, ip, false, "IP_BLOQUEADO");
            return Redefinicao.IP_BLOQUEADO;
        }
        Optional<Convites.Convite> convite = convites.valido(segredo).filter(cv -> "REDEFINIR_SENHA".equals(cv.finalidade()));
        if (convite.isEmpty()) {
            tentativas.falhou("CONVITE", "CONVITE_INVALIDO", null, ip, p);
            return Redefinicao.INVALIDA;
        }
        long usuarioId = convite.get().usuarioId();
        Usuarios.Credenciais c = usuarios.credenciais(usuarioId);
        senhas.exigirBoa(nova, p.senhaMinCaracteres(), usuarios.email(usuarioId), usuarios.nome(usuarioId));
        if (c.totpAtivo()) {
            if (codigo == null || codigo.isBlank()) {
                return Redefinicao.PRECISA_CODIGO;
            }
            if (tentativas.contaBloqueada(usuarioId, p)) {
                tentativas.falhou("SEGUNDO_FATOR", "CONTA_BLOQUEADA", usuarioId, ip, p);
                return Redefinicao.CODIGO_ERRADO;
            }
            if (!codigoConfere(c, codigo)) {
                falhouNaConta("SEGUNDO_FATOR", "CODIGO_2FA_INVALIDO", usuarioId, ip, p);
                return Redefinicao.CODIGO_ERRADO;
            }
        }
        if (!convites.usar(convite.get().id())) {
            tentativas.falhou("CONVITE", "CONVITE_INVALIDO", usuarioId, ip, p);
            return Redefinicao.INVALIDA;
        }
        gravarSenha(usuarioId, nova);
        int caidas = tokens.revogarTodos(usuarioId, "SENHA_ALTERADA", null);
        dispositivos.revogarTodos(usuarioId);
        tentativas.registrar("CONVITE", usuarioId, ip, true, null);
        log.registrar("CONVITE_USADO", usuarioId, "REDEFINIR_SENHA; " + caidas + " sessao(oes) derrubada(s)", ip, LogDeSeguranca.quem(usuarioId));
        avisos.aviso(usuarioId, "a senha foi redefinida pelo link do e-mail; todas as sessões caíram e os computadores de confiança "
                + "foram esquecidos", ip);
        return Redefinicao.FEITA;
    }

    // ---- senha da conta logada -------------------------------------------------------------------

    /**
     * Cria ou troca a senha. Quem ja tem senha informa a atual; quem so tinha passkey confirma com a
     * passkey; quem entrou com codigo de recuperacao (escopo CADASTRAR_PASSKEY) define sem isso. Falso:
     * a senha atual nao confere (conta como tentativa). As outras sessoes caem.
     */
    @Transactional
    public boolean definirSenha(Tokens.Sessao sessao, String atual, String nova, String ip) {
        Parametros p = parametros.atuais();
        long usuarioId = sessao.usuarioId();
        Usuarios.Credenciais c = usuarios.credenciais(usuarioId);
        boolean recuperacao = "CADASTRAR_PASSKEY".equals(sessao.escopo());
        senhas.exigirBoa(nova, p.senhaMinCaracteres(), usuarios.email(usuarioId), usuarios.nome(usuarioId));
        if (!recuperacao) {
            if (c.senhaHash() == null) {
                exigirConfirmacao(sessao);
            } else if (tentativas.contaBloqueada(usuarioId, p)) {
                tentativas.falhou("CONFIRMACAO", "CONTA_BLOQUEADA", usuarioId, ip, p);
                return false;
            } else if (!senhas.confere(atual, c.senhaHash())) {
                falhouNaConta("CONFIRMACAO", "SENHA_INVALIDA", usuarioId, ip, p);
                return false;
            }
        }
        if (c.senhaHash() != null && senhas.confere(nova, c.senhaHash())) {
            throw new RegraDeNegocioException("A senha nova precisa ser diferente da atual.");
        }
        gravarSenha(usuarioId, nova);
        // Quem entrou com codigo de recuperacao cai tambem: entra de novo, ja com a senha nova.
        int caidas = tokens.revogarTodos(usuarioId, "SENHA_ALTERADA", recuperacao ? null : sessao.tokenId());
        int computadores = dispositivos.revogarTodos(usuarioId);
        avisos.aviso(usuarioId, "a senha foi " + (c.senhaHash() == null ? "criada" : "trocada") + "; " + caidas
                + " outra(s) sessão(ões) caiu(caíram) e " + computadores + " computador(es) de confiança foi(foram) esquecido(s)", ip);
        return true;
    }

    /** Confirmar com a senha (+ codigo do 2FA, se ativo) antes de operacao sensivel: o mesmo efeito da passkey. */
    @Transactional
    public Resultado confirmar(Tokens.Sessao sessao, String senha, String codigo, String ip) {
        Parametros p = parametros.atuais();
        long usuarioId = sessao.usuarioId();
        Optional<ServicoDeLogin.IpBloqueado> bloqueio = tentativas.bloqueio(ip);
        if (bloqueio.isPresent()) {
            tentativas.registrar("CONFIRMACAO", usuarioId, ip, false, "IP_BLOQUEADO");
            return bloqueio.get();
        }
        Usuarios.Credenciais c = usuarios.credenciais(usuarioId);
        if (c.senhaHash() == null) {
            return tentativas.falhou("CONFIRMACAO", "CREDENCIAL_DESCONHECIDA", usuarioId, ip, p);
        }
        if (tentativas.contaBloqueada(usuarioId, p)) {
            return tentativas.falhou("CONFIRMACAO", "CONTA_BLOQUEADA", usuarioId, ip, p);
        }
        if (!senhas.confere(senha, c.senhaHash())) {
            return falhouNaConta("CONFIRMACAO", "SENHA_INVALIDA", usuarioId, ip, p);
        }
        if (c.totpAtivo() && !codigoConfere(c, codigo)) {
            return falhouNaConta("CONFIRMACAO", "CODIGO_2FA_INVALIDO", usuarioId, ip, p);
        }
        tokens.marcarConfirmacao(sessao.tokenId());
        tentativas.registrar("CONFIRMACAO", usuarioId, ip, true, null);
        log.registrar("SENHA_CONFIRMADA", usuarioId, c.totpAtivo() ? "senha + 2FA" : "senha", ip, LogDeSeguranca.quem(usuarioId));
        return new Opcoes(Map.of());
    }

    // ---- 2FA -------------------------------------------------------------------------------------

    /** Gera o segredo (ainda nao vale) e o QR code. Chamar de novo troca o segredo pendente. */
    @Transactional
    public Configuracao2fa iniciar2fa(Tokens.Sessao sessao) {
        exigirConfirmacao(sessao);
        long usuarioId = sessao.usuarioId();
        if (usuarios.credenciais(usuarioId).totpAtivo()) {
            throw new RegraDeNegocioException("O app autenticador (2FA) já está ativo nesta conta.");
        }
        String segredo = totp.novoSegredo();
        jdbc.update("UPDATE " + schemas.login("USUARIO") + " SET TOTP_SEGREDO_CIFRADO = ? WHERE ID = ? AND TOTP_ATIVADO_EM IS NULL",
                cifra.cifrar(CONTEXTO_TOTP, segredo), usuarioId);
        String uri = totp.uri(usuarios.email(usuarioId), segredo);
        return new Configuracao2fa(segredo, uri, totp.qr(uri));
    }

    /** Liga o 2FA com o primeiro codigo do app (prova que o app guardou o segredo certo). */
    @Transactional
    public Ativacao ativar2fa(Tokens.Sessao sessao, String codigo, String ip) {
        Parametros p = parametros.atuais();
        long usuarioId = sessao.usuarioId();
        Usuarios.Credenciais c = usuarios.credenciais(usuarioId);
        if (c.totpAtivo()) {
            throw new RegraDeNegocioException("O app autenticador (2FA) já está ativo nesta conta.");
        }
        if (c.totpSegredoCifrado() == null) {
            throw new RegraDeNegocioException("Comece pelo QR code: gere o segredo e leia com o app autenticador.");
        }
        if (tentativas.bloqueio(ip).isPresent()) {
            tentativas.registrar("SEGUNDO_FATOR", usuarioId, ip, false, "IP_BLOQUEADO");
            return new Ativacao(false, null);
        }
        OptionalLong passo = totp.confere(cifra.decifrar(CONTEXTO_TOTP, c.totpSegredoCifrado()), codigo, null, agoraEmSegundos());
        if (passo.isEmpty()) {
            tentativas.falhou("SEGUNDO_FATOR", "CODIGO_2FA_INVALIDO", usuarioId, ip, p);
            return new Ativacao(false, null);
        }
        jdbc.update("UPDATE " + schemas.login("USUARIO") + " SET TOTP_ATIVADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP), TOTP_ULTIMO_PASSO = ?"
                + " WHERE ID = ? AND TOTP_ATIVADO_EM IS NULL", passo.getAsLong(), usuarioId);
        avisos.aviso(usuarioId, "o app autenticador (2FA) foi ativado", ip);
        if (!"CONFIGURAR_2FA".equals(sessao.escopo())) {
            return new Ativacao(true, null);
        }
        // Administrador que entrou so para ativar o 2FA: agora ganha a sessao completa.
        tokens.revogar(sessao.tokenId(), "TOTP_ATIVADO");
        Tokens.TokenEmitido novo = tokens.emitir(usuarioId, null, "COMPLETO", ip, p, autorizacao.empresaUnica(usuarioId).orElse(null), true);
        log.registrar("LOGIN", usuarioId, "senha + 2FA recem-ativado", ip, LogDeSeguranca.quem(usuarioId));
        return new Ativacao(true, novo);
    }

    /** Desliga o 2FA (com um codigo atual do app). Administrador e quem entra pelo celular nao podem. */
    @Transactional
    public boolean desativar2fa(Tokens.Sessao sessao, String codigo, String ip) {
        exigirConfirmacao(sessao);
        Parametros p = parametros.atuais();
        long usuarioId = sessao.usuarioId();
        if (precisaDe2fa(usuarioId)) {
            throw new RegraDeNegocioException("Administrador não pode desligar o app autenticador (2FA).");
        }
        Usuarios.Credenciais c = usuarios.credenciais(usuarioId);
        if (!c.totpAtivo()) {
            throw new RegraDeNegocioException("O app autenticador (2FA) já está desligado.");
        }
        if (c.loginPorTelefone()) {
            throw new RegraDeNegocioException("Desligue antes o login pelo celular: ele exige o app autenticador (2FA).");
        }
        if (tentativas.contaBloqueada(usuarioId, p)) {
            tentativas.falhou("SEGUNDO_FATOR", "CONTA_BLOQUEADA", usuarioId, ip, p);
            return false;
        }
        if (!codigoConfere(c, codigo)) {
            falhouNaConta("SEGUNDO_FATOR", "CODIGO_2FA_INVALIDO", usuarioId, ip, p);
            return false;
        }
        jdbc.update("UPDATE " + schemas.login("USUARIO") + " SET TOTP_ATIVADO_EM = NULL, TOTP_SEGREDO_CIFRADO = NULL, TOTP_ULTIMO_PASSO = NULL"
                + " WHERE ID = ?", usuarioId);
        dispositivos.revogarTodos(usuarioId);
        avisos.aviso(usuarioId, "o app autenticador (2FA) foi DESLIGADO; os computadores de confiança foram esquecidos", ip);
        return true;
    }

    // ---- identificadores e computadores de confianca -----------------------------------------------

    /**
     * Muda os identificadores de login. Nulo = nao mexer; texto vazio = remover. O nome de usuario
     * nao pode ser o nome da pessoa nem so numeros; o login pelo celular exige celular e 2FA.
     */
    @Transactional
    public Usuarios.Identificadores mudarIdentificadores(Tokens.Sessao sessao, String nomeUsuario, String celular, Boolean loginPorCelular,
            String ip) {
        exigirConfirmacao(sessao);
        long usuarioId = sessao.usuarioId();
        Usuarios.Credenciais c = usuarios.credenciais(usuarioId);
        Map<String, Object> atual = jdbc.queryForMap("SELECT NOME_USUARIO, TELEFONE_CIFRADO, TELEFONE_INDICE,"
                + " CASE WHEN LOGIN_POR_TELEFONE THEN 1 ELSE 0 END AS LPT FROM " + schemas.login("USUARIO") + " WHERE ID = ?", usuarioId);
        String novoNome = (String) atual.get("NOME_USUARIO");
        if (nomeUsuario != null) {
            novoNome = nomeUsuario.isBlank() ? null : validarNomeUsuario(nomeUsuario, usuarios.nome(usuarioId));
        }
        String cifrado = (String) atual.get("TELEFONE_CIFRADO");
        String indice = (String) atual.get("TELEFONE_INDICE");
        if (celular != null) {
            if (celular.isBlank()) {
                cifrado = null;
                indice = null;
            } else {
                String telefone = Usuarios.normalizarTelefone(celular);
                if (telefone == null) {
                    throw new RegraDeNegocioException("Celular inválido: use DDD e número, ex.: (11) 98765-4321.");
                }
                cifrado = cifra.cifrar(Usuarios.CONTEXTO_TELEFONE, telefone);
                indice = cifra.indice(Usuarios.CONTEXTO_TELEFONE, telefone);
            }
        }
        boolean porCelular = loginPorCelular != null ? loginPorCelular : ((Number) atual.get("LPT")).intValue() == 1 && indice != null;
        if (porCelular && indice == null) {
            throw new RegraDeNegocioException("Informe o celular para entrar com ele.");
        }
        if (porCelular && !c.totpAtivo()) {
            throw new RegraDeNegocioException("Para entrar com o celular, ative antes o app autenticador (2FA).");
        }
        try {
            jdbc.update("UPDATE " + schemas.login("USUARIO") + " SET NOME_USUARIO = ?, TELEFONE_CIFRADO = ?, TELEFONE_INDICE = ?,"
                    + " LOGIN_POR_TELEFONE = ? WHERE ID = ?", novoNome, cifrado, indice, porCelular, usuarioId);
        } catch (DuplicateKeyException e) {
            String mensagem = String.valueOf(e.getMostSpecificCause().getMessage());
            throw new ConflitoException(mensagem.contains("UK_USUARIO_TELEFONE") ? "Este celular já está em outra conta."
                    : "Este nome de usuário já está em uso: escolha outro.");
        }
        if (nomeUsuario != null || celular != null || loginPorCelular != null) {
            avisos.aviso(usuarioId, "os identificadores de login (nome de usuário, celular) foram alterados", ip);
        }
        return usuarios.identificadores(usuarioId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> dispositivosDe(Tokens.Sessao sessao) {
        return dispositivos.listar(sessao.usuarioId());
    }

    @Transactional
    public void revogarDispositivo(Tokens.Sessao sessao, long id, String ip) {
        if (!dispositivos.revogar(sessao.usuarioId(), id)) {
            throw new RecursoNaoEncontradoException("Computador de confiança", id);
        }
    }

    @Transactional(readOnly = true)
    public Conta conta(long usuarioId) {
        Usuarios.Credenciais c = usuarios.credenciais(usuarioId);
        Usuarios.Identificadores i = usuarios.identificadores(usuarioId);
        return new Conta(c.senhaHash() != null, c.totpAtivo(), precisaDe2fa(usuarioId), i.nomeUsuario(), i.celular(), i.loginPorCelular());
    }

    /** Administrador de alguma empresa, ou da plataforma: com senha, so entra com o 2FA. */
    public boolean precisaDe2fa(long usuarioId) {
        return autorizacao.ehAdminPlataforma(usuarioId)
                || autorizacao.vinculosAtivos(usuarioId).stream().anyMatch(br.com.kawashi.banco.autorizacao.Autorizacao.Vinculo::administrador);
    }

    // ---- apoio ---------------------------------------------------------------------------------

    private Resultado abrirSessao(long usuarioId, boolean totpAtivo, String ip, Parametros p, String metodo, String detalhe,
            String dispositivoNovo) {
        tentativas.registrar(metodo, usuarioId, ip, true, null);
        boolean so2fa = !totpAtivo && precisaDe2fa(usuarioId);
        Tokens.TokenEmitido token = so2fa
                ? tokens.emitir(usuarioId, null, "CONFIGURAR_2FA", ip, p, null, true)
                : tokens.emitir(usuarioId, null, "COMPLETO", ip, p, autorizacao.empresaUnica(usuarioId).orElse(null), true);
        log.registrar("LOGIN", usuarioId, detalhe + (so2fa ? "; administrador sem 2FA: sessao so para ativa-lo" : ""), ip,
                LogDeSeguranca.quem(usuarioId));
        return new Entrou(token, null, dispositivoNovo);
    }

    private Resultado falhouNaConta(String metodo, String motivo, long usuarioId, String ip, Parametros p) {
        if (tentativas.falhouNaConta(metodo, motivo, usuarioId, ip, p)) {
            avisos.aviso(usuarioId, "a senha foi bloqueada por " + p.contaBloqueioMinutos() + " minutos depois de " + p.contaMaxFalhas()
                    + " tentativas erradas (de senha ou do código do 2FA). Se não foi você, troque a senha", ip);
        }
        return new Recusado();
    }

    /** O codigo do app confere e ainda nao foi usado; grava o passo (o mesmo codigo nao vale de novo). */
    private boolean codigoConfere(Usuarios.Credenciais c, String codigo) {
        if (c.totpSegredoCifrado() == null) {
            return false;
        }
        OptionalLong passo = totp.confere(cifra.decifrar(CONTEXTO_TOTP, c.totpSegredoCifrado()), codigo, c.totpUltimoPasso(),
                agoraEmSegundos());
        // Condicionado ao passo anterior: dois pedidos com o mesmo codigo ao mesmo tempo, so um passa.
        return passo.isPresent() && jdbc.update("UPDATE " + schemas.login("USUARIO") + " SET TOTP_ULTIMO_PASSO = ? WHERE ID = ?"
                + " AND (TOTP_ULTIMO_PASSO IS NULL OR TOTP_ULTIMO_PASSO < ?)", passo.getAsLong(), c.id(), passo.getAsLong()) == 1;
    }

    private void gravarSenha(long usuarioId, String senha) {
        jdbc.update("UPDATE " + schemas.login("USUARIO") + " SET SENHA_HASH = ? WHERE ID = ?", senhas.hash(senha), usuarioId);
    }

    /** O relogio do banco (UTC), em segundos desde 1970: o mesmo para todas as instancias da API. */
    private long agoraEmSegundos() {
        return jdbc.queryForObject("SELECT ROUND((CAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) AS DATE) - DATE '1970-01-01') * 86400) FROM DUAL",
                Long.class);
    }

    private static void exigirConfirmacao(Tokens.Sessao sessao) {
        if ("COMPLETO".equals(sessao.escopo()) && !sessao.confirmadaAgora()) {
            throw new br.com.kawashi.banco.compartilhado.erros.ConfirmacaoNecessariaException();
        }
    }

    private static String nomeDoComputador(String nome) {
        if (nome == null || nome.isBlank()) {
            return "Computador";
        }
        String limpo = nome.strip().replaceAll("[^\\p{L}0-9 .,\\-()/']", "");
        return limpo.isEmpty() ? "Computador" : limpo.substring(0, Math.min(limpo.length(), 100));
    }

    /** Minusculas, 3 a 30 de a-z 0-9 . _ -, nao so numeros, e nao o nome da pessoa (nem com numeros em volta). */
    static String validarNomeUsuario(String texto, String nomeDaPessoa) {
        String nome = texto.strip().toLowerCase(java.util.Locale.ROOT);
        if (!nome.matches("[a-z0-9._-]{3,30}")) {
            throw new RegraDeNegocioException("O nome de usuário aceita de 3 a 30 caracteres: letras sem acento, números, ponto, "
                    + "hífen e sublinhado.");
        }
        if (nome.matches("[0-9]+")) {
            throw new RegraDeNegocioException("O nome de usuário não pode ser só números.");
        }
        String letras = nome.replaceAll("[^a-z]", "");
        List<String> partes = List.copyOf(Senhas.partesDoNome(nomeDaPessoa));
        Set<String> proibidos = new LinkedHashSet<>(partes);
        if (nomeDaPessoa != null) {
            proibidos.add(Senhas.semAcento(nomeDaPessoa).replaceAll("[^a-z]", ""));  // o nome inteiro, com "de", "da"...
        }
        if (!partes.isEmpty()) {
            proibidos.add(String.join("", partes));
            proibidos.add(partes.get(0) + partes.get(partes.size() - 1));
            for (int i = 0; i + 1 < partes.size(); i++) {
                proibidos.add(partes.get(i) + partes.get(i + 1));
            }
        }
        if (proibidos.contains(letras)) {
            throw new RegraDeNegocioException("O nome de usuário não pode ser o seu nome: escolha um apelido.");
        }
        return nome;
    }
}
