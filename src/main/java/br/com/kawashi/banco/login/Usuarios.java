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

    /** Cria o usuario da pessoa e o convite para a primeira passkey. Devolve o segredo do convite. */
    @Transactional
    public Criado criar(long pessoaId, String email, String criadoPor) {
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
        String convite = convites.criar(id, "CADASTRO", criadoPor, parametros.atuais());
        log.registrar("CONVITE_CRIADO", id, "cadastro da primeira passkey", null, criadoPor);
        return new Criado(id, convite);
    }

    /** Convite para quem perdeu todos os aparelhos e os codigos: cadastra uma passkey nova. */
    @Transactional
    public String novoConvite(long usuarioId, String criadoPor) {
        Long existe = jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.login("USUARIO") + " WHERE ID = ? AND ATIVO = TRUE",
                Long.class, usuarioId);
        if (existe == null || existe == 0) {
            throw new RecursoNaoEncontradoException("Usuário", usuarioId);
        }
        String convite = convites.criar(usuarioId, "NOVA_PASSKEY", criadoPor, parametros.atuais());
        log.registrar("CONVITE_CRIADO", usuarioId, "nova passkey (acesso perdido)", null, criadoPor);
        return convite;
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
}
