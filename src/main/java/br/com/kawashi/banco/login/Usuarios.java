package br.com.kawashi.banco.login;

import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.kawashi.banco.compartilhado.cifra.CifraDeDados;
import br.com.kawashi.banco.compartilhado.erros.ConflitoException;
import br.com.kawashi.banco.compartilhado.erros.RecursoNaoEncontradoException;
import br.com.kawashi.banco.compartilhado.erros.RegraDeNegocioException;

/**
 * Cria usuarios do login. Cada usuario e uma PESSOA (relacao 1 para 1). O e-mail, que e o nome de
 * login, segue a regra dos dados pessoais: vai cifrado para o banco, com uma impressao digital
 * (HMAC) para a busca e a unicidade. A senha vai so como hash argon2id.
 *
 * <p>Ainda nao ha endpoint para criar usuario: isso depende da autorizacao (so um administrador
 * pode). Hoje quem cria e o script scripts/criar-usuario.ps1 (que usa este servico) e os testes.
 */
@Service
public class Usuarios {

    public static final String CONTEXTO_EMAIL = "USUARIO.EMAIL";

    private final JdbcTemplate jdbc;
    private final SchemasDoLogin schemas;
    private final CifraDeDados cifra;
    private final HashDeSenha hashDeSenha;
    private final ParametrosDeSeguranca parametros;
    private final LogDeSeguranca log;

    public Usuarios(JdbcTemplate jdbc, SchemasDoLogin schemas, CifraDeDados cifra, HashDeSenha hashDeSenha,
            ParametrosDeSeguranca parametros, LogDeSeguranca log) {
        this.jdbc = jdbc;
        this.schemas = schemas;
        this.cifra = cifra;
        this.hashDeSenha = hashDeSenha;
        this.parametros = parametros;
        this.log = log;
    }

    /**
     * Cria o usuario da pessoa. Com {@code senhaTemporaria}, a senha ja nasce vencida: o primeiro
     * login so serve para troca-la (quem criou a conta conhece a senha temporaria; a definitiva,
     * so a propria pessoa).
     */
    @Transactional
    public long criar(long pessoaId, String email, char[] senha, boolean senhaTemporaria) {
        String normalizado = normalizar(email);
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
        List<String> problemas = PoliticaDeSenha.problemas(new String(senha), normalizado, parametros.atuais().senhaMinimo());
        if (!problemas.isEmpty()) {
            throw new RegraDeNegocioException(String.join(" ", problemas));
        }
        jdbc.update("INSERT INTO " + schemas.login("USUARIO")
                + " (PESSOA_ID, EMAIL_CIFRADO, EMAIL_INDICE, SENHA_HASH, SENHA_ALTERADA_EM) VALUES (?, ?, ?, ?, "
                + (senhaTemporaria ? "TIMESTAMP '2000-01-01 00:00:00'" : "SYS_EXTRACT_UTC(SYSTIMESTAMP)") + ")",
                pessoaId, cifra.cifrar(CONTEXTO_EMAIL, normalizado), indice, hashDeSenha.gerar(senha));
        long id = jdbc.queryForObject("SELECT ID FROM " + schemas.login("USUARIO") + " WHERE EMAIL_INDICE = ?", Long.class, indice);
        log.registrar("USUARIO_CRIADO", id, senhaTemporaria ? "senha temporaria: troca obrigatoria no primeiro login" : null,
                null, "API");
        return id;
    }

    public static String normalizar(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
