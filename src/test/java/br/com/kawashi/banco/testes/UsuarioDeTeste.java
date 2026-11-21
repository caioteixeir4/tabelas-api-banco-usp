package br.com.kawashi.banco.testes;

import java.util.concurrent.ThreadLocalRandom;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.kawashi.banco.compartilhado.cifra.CifraDeDados;
import br.com.kawashi.banco.login.ParametrosDeSeguranca;
import br.com.kawashi.banco.login.SchemasDoLogin;
import br.com.kawashi.banco.login.Tokens;
import br.com.kawashi.banco.login.Usuarios;
import br.com.kawashi.banco.pessoa.DocumentoCifrado;

/**
 * Usuarios de login para os testes. Toda a /api exige token, entao quase todo teste precisa de um.
 *
 * <ul>
 *   <li>{@link #novo}: cria empresa, pessoa e usuario (com o convite de cadastro) dentro da
 *       transacao do teste (desfeita no fim: nao sobra nada);</li>
 *   <li>{@link #token}: token de escopo completo emitido direto, sem cerimonia de passkey. Os testes
 *       de cadastro so precisam de "alguem logado"; a cerimonia de verdade e testada no
 *       PasskeyApiTest, com o {@link AutenticadorDeTeste};</li>
 *   <li>{@link #tokenPersistente}: para o teste com servidor real, que grava de verdade em outras
 *       threads. Usa um usuario FIXO, criado na primeira execucao e reaproveitado (a API nao tem
 *       permissao para apagar usuario, de proposito).</li>
 * </ul>
 */
@Component
public class UsuarioDeTeste {

    private static final String EMAIL_PERSISTENTE = "servidor-real@teste.kawashi.invalid";

    /** Usuario recem-criado: ainda sem passkey, com o convite de cadastro. */
    public record Criado(long usuarioId, long pessoaId, String email, String convite) {
    }

    private final JdbcTemplate jdbc;
    private final CifraDeDados cifra;
    private final Usuarios usuarios;
    private final Tokens tokens;
    private final ParametrosDeSeguranca parametros;
    private final SchemasDoLogin schemas;
    private final TransactionTemplate novaTransacao;

    public UsuarioDeTeste(JdbcTemplate jdbc, CifraDeDados cifra, Usuarios usuarios, Tokens tokens, ParametrosDeSeguranca parametros,
            SchemasDoLogin schemas, PlatformTransactionManager transacoes) {
        this.jdbc = jdbc;
        this.cifra = cifra;
        this.usuarios = usuarios;
        this.tokens = tokens;
        this.parametros = parametros;
        this.schemas = schemas;
        this.novaTransacao = new TransactionTemplate(transacoes);
        this.novaTransacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Usuario novo numa empresa e pessoa novas; o convite de cadastro vem junto. */
    public Criado novo() {
        long pessoaId = pessoaNova();
        String email = "usuario" + ThreadLocalRandom.current().nextLong(1_000_000_000L) + "@teste.kawashi.invalid";
        Usuarios.Criado c = usuarios.criar(pessoaId, email, "TESTE");
        return new Criado(c.usuarioId(), pessoaId, email, c.convite());
    }

    /** Token de escopo completo para um usuario novo: o que a maioria dos testes precisa. */
    public String token() {
        return token(novo());
    }

    public String token(Criado usuario) {
        return tokens.emitir(usuario.usuarioId(), null, "COMPLETO", Api.IP_DE_TESTE, parametros.atuais()).token();
    }

    /** Token gravado de verdade (commit), para pedidos que chegam por outras threads. */
    public String tokenPersistente() {
        return novaTransacao.execute(status -> {
            String indice = cifra.indice(Usuarios.CONTEXTO_EMAIL, EMAIL_PERSISTENTE);
            Long id = jdbc.query("SELECT ID FROM " + schemas.login("USUARIO") + " WHERE EMAIL_INDICE = ?",
                    (r, n) -> r.getLong(1), indice).stream().findFirst().orElse(null);
            if (id == null) {
                id = usuarios.criar(pessoaNova(), EMAIL_PERSISTENTE, "TESTE").usuarioId();
            } else {
                // Volta ao estado de partida, seja qual for o que outra execucao deixou.
                jdbc.update("UPDATE " + schemas.login("USUARIO") + " SET ATIVO = TRUE WHERE ID = ?", id);
            }
            return tokens.emitir(id, null, "COMPLETO", Api.IP_DE_TESTE, parametros.atuais()).token();
        });
    }

    private long pessoaNova() {
        String cnpj = GeradorDeDocumentos.cnpj();
        jdbc.update("INSERT INTO EMPRESA (CNPJ, RAZAO_SOCIAL) VALUES (?, 'Empresa do usuario de teste')", cnpj);
        long empresaId = jdbc.queryForObject("SELECT ID FROM EMPRESA WHERE CNPJ = ?", Long.class, cnpj);
        String cpf = GeradorDeDocumentos.cpf();
        String indice = cifra.indice(DocumentoCifrado.CONTEXTO, cpf);
        jdbc.update("INSERT INTO PESSOA (EMPRESA_ID, TIPO, NATUREZA, DOCUMENTO_CIFRADO, DOCUMENTO_INDICE, NOME)"
                + " VALUES (?, 'COLABORADOR', 'F', ?, ?, 'Usuario de Teste')", empresaId,
                cifra.cifrar(DocumentoCifrado.CONTEXTO, cpf), indice);
        return jdbc.queryForObject("SELECT ID FROM PESSOA WHERE EMPRESA_ID = ? AND DOCUMENTO_INDICE = ?", Long.class,
                empresaId, indice);
    }
}
