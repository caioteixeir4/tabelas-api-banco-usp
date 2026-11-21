package br.com.kawashi.banco.login;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Codigos de recuperacao: para quem perdeu os aparelhos com as passkeys. A pessoa recebe
 * {@code CODIGOS_RECUPERACAO} codigos (hoje 10) uma unica vez, ao cadastrar a primeira passkey, e
 * deve guarda-los fora do computador. Cada um vale uma vez. No banco fica so o SHA-256.
 *
 * <p>Usar um codigo NAO da acesso aos dados: da um token que so serve para cadastrar uma passkey
 * nova (escopo CADASTRAR_PASSKEY), e derruba todas as outras sessoes. Gerar codigos novos invalida
 * os anteriores que ainda nao foram usados.
 */
@Component
public class CodigosDeRecuperacao {

    private final JdbcTemplate jdbc;
    private final SchemasDoLogin schemas;

    public CodigosDeRecuperacao(JdbcTemplate jdbc, SchemasDoLogin schemas) {
        this.jdbc = jdbc;
        this.schemas = schemas;
    }

    /** Invalida os codigos ainda nao usados e devolve uma lista nova (a unica vez que eles existem em claro). */
    public List<String> gerar(long usuarioId, int quantos) {
        jdbc.update("UPDATE " + schemas.login("CODIGO_RECUPERACAO") + " SET REVOGADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP)"
                + " WHERE USUARIO_ID = ? AND USADO_EM IS NULL AND REVOGADO_EM IS NULL", usuarioId);
        List<String> codigos = new ArrayList<>();
        for (int i = 0; i < quantos; i++) {
            String codigo = Aleatorio.codigoDeRecuperacao();
            jdbc.update("INSERT INTO " + schemas.login("CODIGO_RECUPERACAO") + " (USUARIO_ID, CODIGO_HASH) VALUES (?, ?)",
                    usuarioId, Aleatorio.sha256(normalizar(codigo)));
            codigos.add(codigo);
        }
        return codigos;
    }

    /** Usa um codigo do usuario. Falso se nao confere, ja foi usado ou foi invalidado. */
    public boolean usar(long usuarioId, String codigo) {
        if (codigo == null) {
            return false;
        }
        return jdbc.update("UPDATE " + schemas.login("CODIGO_RECUPERACAO") + " SET USADO_EM = SYS_EXTRACT_UTC(SYSTIMESTAMP)"
                + " WHERE USUARIO_ID = ? AND CODIGO_HASH = ? AND USADO_EM IS NULL AND REVOGADO_EM IS NULL",
                usuarioId, Aleatorio.sha256(normalizar(codigo))) == 1;
    }

    /** Aceita o codigo digitado com ou sem hifens, maiusculo ou minusculo, com espacos nas pontas. */
    static String normalizar(String codigo) {
        return codigo.strip().toUpperCase(Locale.ROOT).replace("-", "").replace(" ", "");
    }
}
