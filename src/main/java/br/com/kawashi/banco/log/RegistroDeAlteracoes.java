package br.com.kawashi.banco.log;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Grava em LOG_ALTERACAO quem criou, alterou ou apagou qual registro. Roda na MESMA transacao da
 * mudanca: se a mudanca for desfeita, o registro dela some junto, e nao existe mudanca gravada sem
 * log.
 *
 * <p>A API so informa o que aconteceu. A numeracao, a hora e o hash de cada linha sao calculados
 * por um trigger do schema de log (ver oracle-init/11_schema_log.sql no repositorio de regras), que
 * trava a corrente ate o fim da transacao. Por isso os servicos chamam este registro por ultimo,
 * depois das validacoes e da gravacao: a trava dura o minimo possivel.
 */
@Component
public class RegistroDeAlteracoes {

    /** Enquanto a API nao tem login, toda mudanca e atribuida a ela mesma. */
    static final String USUARIO_SEM_LOGIN = "API";

    private final JdbcTemplate jdbc;
    private final SchemaDeLog schema;

    public RegistroDeAlteracoes(JdbcTemplate jdbc, SchemaDeLog schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    public void criou(String tabela, Long id) {
        registrar(tabela, id, "INSERT", null);
    }

    public void alterou(String tabela, Long id, String detalhe) {
        registrar(tabela, id, "UPDATE", detalhe);
    }

    public void apagou(String tabela, Long id) {
        registrar(tabela, id, "DELETE", null);
    }

    private void registrar(String tabela, Long id, String acao, String detalhe) {
        jdbc.update("INSERT INTO " + schema.tabela("LOG_ALTERACAO")
                + " (TABELA, REGISTRO_ID, ACAO, USUARIO, DETALHE) VALUES (?, ?, ?, ?, ?)",
                tabela, id, acao, USUARIO_SEM_LOGIN, detalhe);
    }
}
