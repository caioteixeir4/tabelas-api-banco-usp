package br.com.kawashi.banco.login;

import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.kawashi.banco.compartilhado.cifra.CifraDeDados;
import br.com.kawashi.banco.compartilhado.erros.ConflitoException;
import br.com.kawashi.banco.compartilhado.erros.RecursoNaoEncontradoException;
import br.com.kawashi.banco.compartilhado.erros.RegraDeNegocioException;

/**
 * Cria usuarios do login. Cada usuario e uma PESSOA (relacao 1 para 1). O e-mail segue a regra dos
 * dados pessoais: vai cifrado para o banco, com uma impressao digital (HMAC) para a busca e a
 * unicidade.
 *
 * <p>Nao existe senha. O usuario nasce SEM passkey e com um convite de uso unico: e no link do
 * convite que a pessoa cadastra a primeira passkey. O "handle" WebAuthn (o identificador da conta
 * que fica guardado no aparelho) e aleatorio: nao carrega e-mail, nome nem id do banco.
 *
 * <p>Ainda nao ha endpoint para criar usuario: isso depende da autorizacao (so um administrador
 * pode). Hoje quem cria e o script scripts/criar-usuario.ps1 (que usa este servico) e os testes.
 */
@Service
public class Usuarios {

    public static final String CONTEXTO_EMAIL = "USUARIO.EMAIL";

    public record Criado(long usuarioId, String convite) {
    }

    private final JdbcTemplate jdbc;
    private final SchemasDoLogin schemas;
    private final CifraDeDados cifra;
    private final ParametrosDeSeguranca parametros;
    private final Convites convites;
    private final LogDeSeguranca log;

    public Usuarios(JdbcTemplate jdbc, SchemasDoLogin schemas, CifraDeDados cifra, ParametrosDeSeguranca parametros,
            Convites convites, LogDeSeguranca log) {
        this.jdbc = jdbc;
        this.schemas = schemas;
        this.cifra = cifra;
        this.parametros = parametros;
        this.convites = convites;
        this.log = log;
    }

    /**
     * Cria o usuario da pessoa e o convite para a primeira passkey. Devolve o segredo do convite;
     * {@code porEmail}: ele vai para o e-mail da pessoa (quem chama manda, ver Avisos).
     */
    @Transactional
    public Criado criar(long pessoaId, String email, String criadoPor, boolean porEmail) {
        String normalizado = normalizar(email);
        if (normalizado == null || !normalizado.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s.]+") || normalizado.length() > 150) {
            throw new RegraDeNegocioException("E-mail inválido.");
        }
        Integer pessoaAtiva = jdbc.query("SELECT CASE WHEN ATIVA THEN 1 ELSE 0 END FROM PESSOA WHERE ID = ?",
                (r, n) -> r.getInt(1), pessoaId).stream().findFirst()
                .orElseThrow(() -> new RecursoNaoEncontradoException("Pessoa", pessoaId));
        if (pessoaAtiva == 0) {
            throw new RegraDeNegocioException("A pessoa " + pessoaId + " está inativa: não recebe usuário.");
        }
        String indice = cifra.indice(CONTEXTO_EMAIL, normalizado);
        Long existentes = jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.login("USUARIO")
                + " WHERE EMAIL_INDICE = ? OR PESSOA_ID = ?", Long.class, indice, pessoaId);
        if (existentes != null && existentes > 0) {
            throw new ConflitoException("Já existe usuário com este e-mail ou para esta pessoa.");
        }
        jdbc.update("INSERT INTO " + schemas.login("USUARIO") + " (PESSOA_ID, EMAIL_CIFRADO, EMAIL_INDICE, HANDLE) VALUES (?, ?, ?, ?)",
                pessoaId, cifra.cifrar(CONTEXTO_EMAIL, normalizado), indice, Aleatorio.base64Url32());
        long id = jdbc.queryForObject("SELECT ID FROM " + schemas.login("USUARIO") + " WHERE EMAIL_INDICE = ?", Long.class, indice);
        log.registrar("USUARIO_CRIADO", id, null, null, criadoPor);
        String convite = convites.criar(id, "CADASTRO", criadoPor, parametros.atuais(), porEmail);
        log.registrar("CONVITE_CRIADO", id, "cadastro da primeira passkey", null, criadoPor);
        return new Criado(id, convite);
    }

    /** Convite para quem perdeu todos os aparelhos e os codigos: cadastra uma passkey nova. */
    @Transactional
    public String novoConvite(long usuarioId, String criadoPor, boolean porEmail) {
        Long existe = jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.login("USUARIO") + " WHERE ID = ? AND ATIVO = TRUE",
                Long.class, usuarioId);
        if (existe == null || existe == 0) {
            throw new RecursoNaoEncontradoException("Usuário", usuarioId);
        }
        String convite = convites.criar(usuarioId, "NOVA_PASSKEY", criadoPor, parametros.atuais(), porEmail);
        log.registrar("CONVITE_CRIADO", usuarioId, "nova passkey (acesso perdido)", null, criadoPor);
        return convite;
    }

    /** O usuario dono deste e-mail, se houver (pela impressao digital: o e-mail e gravado cifrado). */
    public java.util.Optional<Long> porEmail(String email) {
        return jdbc.query("SELECT ID FROM " + schemas.login("USUARIO") + " WHERE EMAIL_INDICE = ?", (r, n) -> r.getLong(1),
                cifra.indice(CONTEXTO_EMAIL, normalizar(email))).stream().findFirst();
    }

    public String email(long usuarioId) {
        return cifra.decifrar(CONTEXTO_EMAIL, jdbc.queryForObject("SELECT EMAIL_CIFRADO FROM " + schemas.login("USUARIO")
                + " WHERE ID = ?", String.class, usuarioId));
    }

    public String handle(long usuarioId) {
        return jdbc.queryForObject("SELECT HANDLE FROM " + schemas.login("USUARIO") + " WHERE ID = ?", String.class, usuarioId);
    }

    public static String normalizar(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    // ---- login por senha: identificadores e credenciais -----------------------------------------

    public static final String CONTEXTO_TELEFONE = "USUARIO.TELEFONE";

    /** O que o login por senha precisa saber da conta. O segredo do 2FA continua cifrado aqui. */
    public record Credenciais(long id, boolean ativo, String senhaHash, String totpSegredoCifrado, boolean totpAtivo,
            Long totpUltimoPasso, boolean loginPorTelefone) {

        @Override
        public String toString() {
            return "Credenciais[" + id + "]";  // nada de hash nem de segredo em log
        }
    }

    private static final String COLUNAS_DAS_CREDENCIAIS = "SELECT ID, CASE WHEN ATIVO THEN 1 ELSE 0 END, SENHA_HASH, TOTP_SEGREDO_CIFRADO,"
            + " CASE WHEN TOTP_ATIVADO_EM IS NOT NULL THEN 1 ELSE 0 END, TOTP_ULTIMO_PASSO, CASE WHEN LOGIN_POR_TELEFONE THEN 1 ELSE 0 END FROM ";

    /**
     * A conta do identificador digitado no login: com @ e o e-mail; so digitos (com ou sem espacos,
     * parenteses, hifen e +) e o celular, e so vale se a pessoa ligou o login pelo celular; o resto e
     * o nome de usuario (que nunca e so numeros, regra do banco: nao ha como confundir).
     */
    public java.util.Optional<Credenciais> porIdentificador(String identificador) {
        if (identificador == null || identificador.isBlank() || identificador.length() > 150) {
            return java.util.Optional.empty();
        }
        String id = identificador.strip();
        if (id.contains("@")) {
            return credenciaisOnde("EMAIL_INDICE = ?", cifra.indice(CONTEXTO_EMAIL, normalizar(id)));
        }
        String telefone = normalizarTelefone(id);
        if (telefone != null) {
            return credenciaisOnde("TELEFONE_INDICE = ? AND LOGIN_POR_TELEFONE", cifra.indice(CONTEXTO_TELEFONE, telefone));
        }
        return credenciaisOnde("NOME_USUARIO = ?", id.toLowerCase(Locale.ROOT));
    }

    public Credenciais credenciais(long usuarioId) {
        return credenciaisOnde("ID = ?", usuarioId).orElseThrow(() -> new RecursoNaoEncontradoException("Usuário", usuarioId));
    }

    private java.util.Optional<Credenciais> credenciaisOnde(String condicao, Object valor) {
        return jdbc.query(COLUNAS_DAS_CREDENCIAIS + schemas.login("USUARIO") + " WHERE " + condicao,
                (r, n) -> new Credenciais(r.getLong(1), r.getInt(2) == 1, r.getString(3), r.getString(4), r.getInt(5) == 1,
                        (Long) r.getObject(6, Long.class), r.getInt(7) == 1), valor).stream().findFirst();
    }

    /** O nome da pessoa dona da conta (para recusar senha e nome de usuario que o contenham). */
    public String nome(long usuarioId) {
        return jdbc.query("SELECT p.NOME FROM PESSOA p JOIN " + schemas.login("USUARIO") + " u ON u.PESSOA_ID = p.ID WHERE u.ID = ?",
                (r, n) -> r.getString(1), usuarioId).stream().findFirst().orElse(null);
    }

    /** O que a tela de configuracoes mostra dos identificadores (o celular, so mascarado). */
    public record Identificadores(String nomeUsuario, String celular, boolean loginPorCelular) {
    }

    public Identificadores identificadores(long usuarioId) {
        return jdbc.queryForObject("SELECT NOME_USUARIO, TELEFONE_CIFRADO, CASE WHEN LOGIN_POR_TELEFONE THEN 1 ELSE 0 END FROM "
                + schemas.login("USUARIO") + " WHERE ID = ?", (r, n) -> {
                    String telefone = cifra.decifrar(CONTEXTO_TELEFONE, r.getString(2));
                    return new Identificadores(r.getString(1), mascararTelefone(telefone), r.getInt(3) == 1);
                }, usuarioId);
    }

    /**
     * Celular so com digitos e com o codigo do pais: "(11) 98765-4321" vira "5511987654321" (10 ou 11
     * digitos ganham o 55 do Brasil). Nulo se nao parece um telefone (entre 10 e 15 digitos).
     */
    public static String normalizarTelefone(String texto) {
        if (texto == null || !texto.strip().matches("\\+?[0-9 ()\\-.]{8,25}")) {
            return null;
        }
        String digitos = texto.replaceAll("[^0-9]", "");
        if (digitos.length() == 10 || digitos.length() == 11) {
            digitos = "55" + digitos;
        }
        return digitos.length() >= 12 && digitos.length() <= 15 ? digitos : null;
    }

    /** "5511987654321" vira "+55 •••••••4321". */
    public static String mascararTelefone(String telefone) {
        if (telefone == null) {
            return null;
        }
        return "+" + telefone.substring(0, 2) + " " + "•".repeat(telefone.length() - 6) + telefone.substring(telefone.length() - 4);
    }
}
