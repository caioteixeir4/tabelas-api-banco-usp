package br.com.kawashi.banco;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A conexao da API com o banco e cifrada. O servidor exige cifra AES-256 e conferencia SHA-256
 * (repositorio de regras: infra/scripts/apply-network-hardening.ps1), e o driver JDBC negocia
 * sozinho, sem configuracao na API.
 *
 * <p>A conta de execucao nao tem permissao para ler qual algoritmo foi negociado (isso esta no
 * dicionario do banco, e de proposito ela nao ve o dicionario). A prova e pelos dois lados: a
 * conexao normal funciona, e a MESMA conta, com um cliente que se recusa a cifrar, e barrada
 * pelo servidor. Logo, toda conexao que funciona esta cifrada. O teste do repositorio de regras
 * (Test-ConexaoCifrada.ps1) confere o algoritmo pelo dicionario.
 */
@SpringBootTest(properties = {"app.exigir-https=false", "server.ssl.enabled=false"})
class ConexaoCifradaTest {

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String usuario;

    @Value("${spring.datasource.password}")
    private String senha;

    private Connection conectar(String regraDeCifra, String regraDeIntegridade) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", usuario);
        p.setProperty("password", senha);
        if (regraDeCifra != null) {
            p.setProperty("oracle.net.encryption_client", regraDeCifra);
        }
        if (regraDeIntegridade != null) {
            p.setProperty("oracle.net.crypto_checksum_client", regraDeIntegridade);
        }
        return DriverManager.getConnection(url, p);
    }

    @Test
    void conexaoNormalDaApiFuncionaComOServidorExigindoCifra() throws SQLException {
        try (Connection c = conectar(null, null); Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT 'OK' FROM DUAL")) {
            assertThat(r.next()).isTrue();
            assertThat(r.getString(1)).isEqualTo("OK");
        }
    }

    @Test
    void clienteQueRecusaCifrarEBarradoPeloServidor() {
        assertThatThrownBy(() -> conectar("REJECTED", null).close())
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ORA-12660");
    }

    @Test
    void clienteQueRecusaAConferenciaDeIntegridadeEBarrado() {
        assertThatThrownBy(() -> conectar(null, "REJECTED").close())
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ORA-12660");
    }
}
