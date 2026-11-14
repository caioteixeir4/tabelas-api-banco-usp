package br.com.kawashi.banco.log;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Nome do schema de log deste ambiente: o schema em que a API esta (KAWASHI_ENV_<AMBIENTE>) seguido
 * de "_LOG". As tabelas de la sao criadas pelo repositorio de regras e a API so recebe INSERT e
 * SELECT: nem ela consegue alterar ou apagar o que registrou.
 */
@Component
public class SchemaDeLog {

    private final JdbcTemplate jdbc;
    private final String configurado;
    private volatile String nome;

    public SchemaDeLog(JdbcTemplate jdbc, @Value("${app.schema-log:}") String configurado) {
        this.jdbc = jdbc;
        this.configurado = configurado;
    }

    /** Resolvido na primeira vez que e pedido (precisa do banco) e guardado. */
    public String nome() {
        String atual = nome;
        if (atual == null) {
            atual = configurado.isBlank()
                    ? jdbc.queryForObject("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') || '_LOG' FROM DUAL", String.class)
                    : configurado.strip();
            // O nome entra concatenado nos comandos (schema nao pode ser parametro): so letras,
            // numeros e sublinhado.
            if (atual == null || !atual.matches("[A-Za-z0-9_]+")) {
                throw new IllegalStateException("Nome de schema de log inválido: " + atual);
            }
            nome = atual;
        }
        return atual;
    }

    public String tabela(String tabela) {
        return nome() + "." + tabela;
    }
}
