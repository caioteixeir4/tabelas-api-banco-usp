package br.com.kawashi.banco.login;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * O desafio de cada cerimonia WebAuthn: 32 bytes aleatorios que o aparelho assina junto com a
 * resposta. Garante que a resposta foi feita AGORA, para ESTE pedido: uma resposta gravada e
 * reenviada depois (replay) nao serve, porque o desafio ja foi usado ou venceu.
 *
 * <p>Uso unico de verdade: {@link #consumir} marca o desafio como usado num UPDATE condicionado
 * ("ainda nao usado e nao vencido"). Dois pedidos com o mesmo desafio ao mesmo tempo: o banco deixa
 * so um passar.
 */
@Component
public class Desafios {

    public record Desafio(long id, Long usuarioId) {
    }

    private final JdbcTemplate jdbc;
    private final SchemasDoLogin schemas;

    public Desafios(JdbcTemplate jdbc, SchemasDoLogin schemas) {
        this.jdbc = jdbc;
        this.schemas = schemas;
    }

    /** Cria um desafio. {@code usuarioId} e obrigatorio no cadastro e vazio no login (sem nome de usuario). */
    public String criar(String finalidade, Long usuarioId, ParametrosDeSeguranca.Parametros p) {
        String valor = Aleatorio.base64Url32();
        jdbc.update("INSERT INTO " + schemas.sessao("DESAFIO") + " (VALOR, FINALIDADE, USUARIO_ID, EXPIRA_EM)"
                + " VALUES (?, ?, ?, SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'MINUTE'))",
                valor, finalidade, usuarioId, p.desafioMinutos());
        return valor;
    }

    /** Marca o desafio como usado. Vazio se nao existe, ja foi usado, venceu ou e de outra finalidade. */
    public Optional<Desafio> consumir(String valor, String finalidade) {
        if (valor == null || !valor.matches("[A-Za-z0-9_-]{43}")) {
            return Optional.empty();
        }
        int marcados = jdbc.update("UPDATE " + schemas.sessao("DESAFIO") + " SET USADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP)"
                + " WHERE VALOR = ? AND FINALIDADE = ? AND USADO_EM IS NULL AND EXPIRA_EM > SYS_EXTRACT_UTC(SYSTIMESTAMP)",
                valor, finalidade);
        if (marcados != 1) {
            return Optional.empty();
        }
        return jdbc.query("SELECT ID, USUARIO_ID FROM " + schemas.sessao("DESAFIO") + " WHERE VALOR = ?",
                (r, n) -> new Desafio(r.getLong(1), (Long) r.getObject(2, Long.class)), valor).stream().findFirst();
    }
}
