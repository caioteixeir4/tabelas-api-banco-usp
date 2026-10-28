package br.com.kawashi.banco;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Prova de fogo da arquitetura: a API entra no Oracle como CONTA[SCHEMA_DO_AMBIENTE] (proxy
 * authentication, mesmo modelo das pessoas) e, no fim da subida, as migracoes do Flyway deixaram
 * as 6 tabelas padrao no schema do ambiente. Roda contra o Oracle real do repositorio de regras.
 */
@SpringBootTest
class ConexaoProxyTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${spring.datasource.username}")
    private String usuarioJdbc;

    @Test
    void entraComoSchemaDoAmbienteViaProxy() {
        // usuarioJdbc tem a forma CONTA[SCHEMA]
        String conta = usuarioJdbc.substring(0, usuarioJdbc.indexOf('[')).toUpperCase();
        String schema = usuarioJdbc.substring(usuarioJdbc.indexOf('[') + 1, usuarioJdbc.indexOf(']')).toUpperCase();

        String schemaCorrente = jdbc.queryForObject(
                "SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL", String.class);
        String usuarioProxy = jdbc.queryForObject(
                "SELECT SYS_CONTEXT('USERENV', 'PROXY_USER') FROM DUAL", String.class);

        assertThat(schemaCorrente).isEqualTo(schema);
        assertThat(usuarioProxy).isEqualTo(conta);
    }

    @Test
    void migracoesCriaramAsTabelasPadrao() {
        List<String> tabelas = jdbc.queryForList(
                "SELECT table_name FROM user_tables WHERE table_name IN "
                        + "('CLIENTE','ENDERECO','CONTATO','EMPRESA','FILIAL','SOCIO') ORDER BY table_name",
                String.class);

        assertThat(tabelas).containsExactly("CLIENTE", "CONTATO", "EMPRESA", "ENDERECO", "FILIAL", "SOCIO");
    }

    @Test
    void historicoDoFlywayRegistraTodasAsMigracoes() {
        Integer aplicadas = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"success\" = 1 AND \"version\" IS NOT NULL AND \"version\" <> '0'",
                Integer.class);

        assertThat(aplicadas).isGreaterThanOrEqualTo(3);
    }
}
