package br.com.kawashi.banco.login;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Nomes dos schemas do login deste ambiente: o schema em que a API esta (KAWASHI_ENV_<AMBIENTE>)
 * seguido de "_LOGIN" (usuario, senhas, parametros), "_SESSAO" (tokens, bloqueios de IP) ou
 * "_LOG" (tentativas e eventos de seguranca). As tabelas sao criadas pelo repositorio de regras
 * (infra/oracle-init/13_schema_login.sql): a API nao e dona de nenhuma delas.
 */
@Component
public class SchemasDoLogin {

    private final JdbcTemplate jdbc;
    private volatile String ambiente;

    public SchemasDoLogin(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String login(String tabela) {
        return ambiente() + "_LOGIN." + tabela;
    }

    public String sessao(String tabela) {
        return ambiente() + "_SESSAO." + tabela;
    }

    public String log(String tabela) {
        return ambiente() + "_LOG." + tabela;
    }

    private String ambiente() {
        String atual = ambiente;
        if (atual == null) {
            atual = jdbc.queryForObject("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL", String.class);
            // O nome entra concatenado nos comandos (schema nao pode ser parametro): so letras,
            // numeros e sublinhado.
            if (atual == null || !atual.matches("[A-Za-z0-9_]+")) {
                throw new IllegalStateException("Nome de schema inválido: " + atual);
            }
            ambiente = atual;
        }
        return atual;
    }
}
