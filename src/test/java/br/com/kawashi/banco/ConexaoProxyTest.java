package br.com.kawashi.banco;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Prova de fogo da arquitetura de contas. A API usa DUAS contas de banco, as duas por proxy:
 * a de migracao (so na subida, pelo Flyway) entra como o dono das tabelas; a de execucao, que
 * atende os pedidos, entra num schema sem objetos e so le e grava linhas. Roda contra o Oracle
 * real do repositorio de regras.
 */
@SpringBootTest(properties = {"app.exigir-https=false", "server.ssl.enabled=false"})
class ConexaoProxyTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${spring.datasource.username}")
    private String usuarioDeExecucao;

    @Value("${spring.flyway.user}")
    private String usuarioDeMigracao;

    @Value("${DB_SCHEMA}")
    private String schemaDoAmbiente;

    private String contexto(String nome) {
        return jdbc.queryForObject("SELECT SYS_CONTEXT('USERENV', '" + nome + "') FROM DUAL", String.class);
    }

    @Test
    void contaDeExecucaoEntraNoSchemaSemObjetosEEnxergaAsTabelasDoAmbiente() {
        // usuarioDeExecucao tem a forma CONTA[SCHEMA_APP]
        String conta = usuarioDeExecucao.substring(0, usuarioDeExecucao.indexOf('[')).toUpperCase();

        assertThat(contexto("PROXY_USER")).isEqualTo(conta);
        assertThat(contexto("SESSION_USER")).isEqualTo(schemaDoAmbiente.toUpperCase() + "_APP");
        // O schema corrente e o do ambiente (ALTER SESSION na abertura da conexao), entao as
        // tabelas sao achadas sem prefixo.
        assertThat(contexto("CURRENT_SCHEMA")).isEqualTo(schemaDoAmbiente.toUpperCase());
    }

    @Test
    void asDuasContasSaoDiferentesEAMigracaoEntraComoODono() {
        assertThat(usuarioDeMigracao).isNotEqualToIgnoringCase(usuarioDeExecucao);
        assertThat(usuarioDeMigracao.toUpperCase()).endsWith("[" + schemaDoAmbiente.toUpperCase() + "]");
        assertThat(usuarioDeExecucao.toUpperCase()).endsWith("[" + schemaDoAmbiente.toUpperCase() + "_APP]");
    }

    @Test
    void contaDeExecucaoNaoConsegueCriarAlterarNemApagarTabela() {
        // Quem invadir a API herda esta conta. Ela le e grava linhas, e so.
        assertThatThrownBy(() -> jdbc.execute("DROP TABLE EMPRESA"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageMatching("(?s).*ORA-(01031|41900).*");
        assertThatThrownBy(() -> jdbc.execute("ALTER TABLE PESSOA ADD (INVASOR VARCHAR2(10))"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageMatching("(?s).*ORA-(01031|41900).*");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE TABLE CONTATO"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageMatching("(?s).*ORA-(01031|41900).*");
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE INVASOR (ID NUMBER)"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageMatching("(?s).*ORA-(01031|41900).*");
        // Nem no proprio schema _APP ela cria alguma coisa.
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE " + schemaDoAmbiente + "_APP.INVASOR (ID NUMBER)"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageMatching("(?s).*ORA-(01031|41900|01950).*");
    }

    @Test
    void contaDeExecucaoSoTemLeituraEEscritaDeLinhasNasTabelas() {
        List<String> privilegios = jdbc.queryForList(
                "SELECT DISTINCT privilege FROM all_tab_privs WHERE table_schema = ? AND grantee = ? ORDER BY privilege",
                String.class, schemaDoAmbiente.toUpperCase(), schemaDoAmbiente.toUpperCase() + "_APP");
        assertThat(privilegios).containsExactly("DELETE", "INSERT", "SELECT", "UPDATE");
    }

    @Test
    void migracoesCriaramAsTabelasDeCadastro() {
        List<String> tabelas = jdbc.queryForList(
                "SELECT table_name FROM all_tables WHERE owner = ? AND table_name IN "
                        + "('CLIENTE','SOCIO','ENDERECO','CONTATO','EMPRESA','FILIAL','PESSOA') ORDER BY table_name",
                String.class, schemaDoAmbiente.toUpperCase());

        // As tabelas do modelo antigo (CLIENTE, SOCIO) nao podem ter sobrado.
        assertThat(tabelas).containsExactly("CONTATO", "EMPRESA", "ENDERECO", "FILIAL", "PESSOA");
    }

    @Test
    void contaDeExecucaoNaoLeOHistoricoDoFlyway() {
        // O historico das migracoes e assunto da conta de migracao; a de execucao nem o enxerga.
        assertThatThrownBy(() -> jdbc.queryForList("SELECT * FROM \"flyway_schema_history\""))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("ORA-00942");
    }
}
