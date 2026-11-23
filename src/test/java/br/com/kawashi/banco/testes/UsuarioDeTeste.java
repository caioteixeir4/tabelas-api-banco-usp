package br.com.kawashi.banco.testes;

import java.util.concurrent.ThreadLocalRandom;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import br.com.kawashi.banco.autorizacao.Autorizacao;
import br.com.kawashi.banco.compartilhado.cifra.CifraDeDados;
import br.com.kawashi.banco.login.ParametrosDeSeguranca;
import br.com.kawashi.banco.login.SchemasDoLogin;
import br.com.kawashi.banco.login.Tokens;
import br.com.kawashi.banco.login.Usuarios;
import br.com.kawashi.banco.pessoa.DocumentoCifrado;

/**
 * Usuarios de login para os testes. Toda a /api exige token, e todo cadastro vive dentro de uma
 * empresa, entao quase todo teste precisa de um usuario COM empresa.
 *
 * <ul>
 *   <li>{@link #novo}: empresa, perfis padrao, pessoa e usuario, e o usuario e ADMINISTRADOR dessa
 *       empresa (vinculo ativo). O convite de cadastro vem junto. Tudo dentro da transacao do teste
 *       (desfeita no fim);</li>
 *   <li>{@link #api}: um cliente da API ja logado nessa empresa. Cada {@code api.empresa()} cria
 *       outra empresa do mesmo usuario e troca a sessao para ela, pela rota real
 *       /api/auth/empresa;</li>
 *   <li>{@link #token}: token emitido direto (sem cerimonia de passkey), preso a empresa do
 *       usuario. A cerimonia de verdade e testada no PasskeyApiTest;</li>
 *   <li>{@link #sessaoPersistente}: para o teste com servidor real, que grava de verdade em outras
 *       threads. Usa um usuario e uma empresa FIXOS, criados na primeira execucao e reaproveitados
 *       (a API nao tem permissao para apagar usuario, perfil nem vinculo, de proposito).</li>
 * </ul>
 */
@Component
public class UsuarioDeTeste {

    /** Autor das mudancas feitas pelos testes no lugar de uma pessoa logada (o banco exige um). */
    public static final String QUEM = "SCRIPT:TESTE";
    private static final String EMAIL_PERSISTENTE = "servidor-real@teste.kawashi.invalid";
    private static final String EMPRESA_PERSISTENTE = "Empresa fixa do ServidorRealTest";

    /** Usuario recem-criado: ADMINISTRADOR da propria empresa, ainda sem passkey, com o convite. */
    public record Criado(long usuarioId, long pessoaId, String email, String convite, long empresaId, String cnpjDaEmpresa) {
    }

    public record SessaoPersistente(String token, long empresaId) {
    }

    private final JdbcTemplate jdbc;
    private final CifraDeDados cifra;
    private final Usuarios usuarios;
    private final Tokens tokens;
    private final ParametrosDeSeguranca parametros;
    private final SchemasDoLogin schemas;
    private final Autorizacao autorizacao;
    private final TransactionTemplate novaTransacao;

    public UsuarioDeTeste(JdbcTemplate jdbc, CifraDeDados cifra, Usuarios usuarios, Tokens tokens, ParametrosDeSeguranca parametros,
            SchemasDoLogin schemas, Autorizacao autorizacao, PlatformTransactionManager transacoes) {
        this.jdbc = jdbc;
        this.cifra = cifra;
        this.usuarios = usuarios;
        this.tokens = tokens;
        this.parametros = parametros;
        this.schemas = schemas;
        this.autorizacao = autorizacao;
        this.novaTransacao = new TransactionTemplate(transacoes);
        this.novaTransacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Usuario novo, ADMINISTRADOR de uma empresa nova; o convite de cadastro vem junto. */
    public Criado novo() {
        return novo("ADMINISTRADOR");
    }

    /** Usuario novo numa empresa nova, com o perfil pedido (ADMINISTRADOR, OPERADOR ou CONSULTA). */
    public Criado novo(String perfil) {
        String cnpj = GeradorDeDocumentos.cnpj();
        long empresaId = empresaNova(cnpj);
        long pessoaId = pessoaNova(empresaId);
        String email = "usuario" + ThreadLocalRandom.current().nextLong(1_000_000_000L) + "@teste.kawashi.invalid";
        Usuarios.Criado c = usuarios.criar(pessoaId, email, QUEM);
        autorizacao.vincular(c.usuarioId(), empresaId, autorizacao.perfilPorNome(empresaId, perfil).orElseThrow(), QUEM);
        return new Criado(c.usuarioId(), pessoaId, email, c.convite(), empresaId, cnpj);
    }

    /** Um usuario a mais na empresa, com o perfil pedido (sem passkey). */
    public Criado naEmpresa(long empresaId, String perfil) {
        long pessoaId = pessoaNova(empresaId);
        String email = "usuario" + ThreadLocalRandom.current().nextLong(1_000_000_000L) + "@teste.kawashi.invalid";
        Usuarios.Criado c = usuarios.criar(pessoaId, email, QUEM);
        autorizacao.vincular(c.usuarioId(), empresaId, autorizacao.perfilPorNome(empresaId, perfil).orElseThrow(), QUEM);
        return new Criado(c.usuarioId(), pessoaId, email, c.convite(), empresaId, null);
    }

    /** Cliente da API logado como ADMINISTRADOR de uma empresa nova. */
    public Api api(MockMvc mvc) {
        return api(mvc, novo());
    }

    public Api api(MockMvc mvc, Criado usuario) {
        return new Api(mvc, token(usuario), cnpj -> {
            long empresaId = empresaNova(cnpj);
            autorizacao.vincular(usuario.usuarioId(), empresaId, autorizacao.perfilPorNome(empresaId, "ADMINISTRADOR").orElseThrow(), QUEM);
            return empresaId;
        }, usuario.empresaId(), usuario.cnpjDaEmpresa());
    }

    /** Token de escopo completo, preso a empresa de um usuario novo. */
    public String token() {
        return token(novo());
    }

    public String token(Criado usuario) {
        return tokens.emitir(usuario.usuarioId(), null, "COMPLETO", Api.IP_DE_TESTE, parametros.atuais(), usuario.empresaId()).token();
    }

    /** Token sem empresa (logo depois de entrar, antes de escolher). */
    public String tokenSemEmpresa(Criado usuario) {
        return tokens.emitir(usuario.usuarioId(), null, "COMPLETO", Api.IP_DE_TESTE, parametros.atuais(), null).token();
    }

    /** Usuario e empresa fixos, gravados de verdade (commit), para pedidos que chegam por outras threads. */
    public SessaoPersistente sessaoPersistente() {
        return novaTransacao.execute(status -> {
            Long empresaId = jdbc.query("SELECT ID FROM EMPRESA WHERE RAZAO_SOCIAL = ?", (r, n) -> r.getLong(1), EMPRESA_PERSISTENTE)
                    .stream().findFirst().orElse(null);
            if (empresaId == null) {
                empresaId = empresaNova(GeradorDeDocumentos.cnpj());
                jdbc.update("UPDATE EMPRESA SET RAZAO_SOCIAL = ? WHERE ID = ?", EMPRESA_PERSISTENTE, empresaId);
            } else if (autorizacao.perfilPorNome(empresaId, "ADMINISTRADOR").isEmpty()) {
                autorizacao.criarPerfisPadrao(empresaId, QUEM);
            }
            String indice = cifra.indice(Usuarios.CONTEXTO_EMAIL, EMAIL_PERSISTENTE);
            Long id = jdbc.query("SELECT ID FROM " + schemas.login("USUARIO") + " WHERE EMAIL_INDICE = ?",
                    (r, n) -> r.getLong(1), indice).stream().findFirst().orElse(null);
            if (id == null) {
                // A pessoa do usuario fica numa empresa propria: a limpeza do teste apaga as pessoas da empresa fixa.
                id = usuarios.criar(pessoaNova(empresaNova(GeradorDeDocumentos.cnpj())), EMAIL_PERSISTENTE, QUEM).usuarioId();
            } else {
                // Volta ao estado de partida, seja qual for o que outra execucao deixou.
                jdbc.update("UPDATE " + schemas.login("USUARIO") + " SET ATIVO = TRUE WHERE ID = ?", id);
            }
            long administrador = autorizacao.perfilPorNome(empresaId, "ADMINISTRADOR").orElseThrow();
            Long vinculos = jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.login("USUARIO_EMPRESA")
                    + " WHERE USUARIO_ID = ? AND EMPRESA_ID = ?", Long.class, id, empresaId);
            if (vinculos == null || vinculos == 0) {
                autorizacao.vincular(id, empresaId, administrador, QUEM);
            } else {
                jdbc.update("UPDATE " + schemas.login("USUARIO_EMPRESA") + " SET ATIVO = TRUE, PERFIL_ID = ?, ATUALIZADO_POR = ?"
                        + " WHERE USUARIO_ID = ? AND EMPRESA_ID = ?", administrador, QUEM, id, empresaId);
            }
            return new SessaoPersistente(tokens.emitir(id, null, "COMPLETO", Api.IP_DE_TESTE, parametros.atuais(), empresaId).token(),
                    empresaId);
        });
    }

    /**
     * Cliente da API logado como ADMINISTRADOR DA PLATAFORMA (sem empresa). O papel so se concede
     * pelo banco (a API so le ADMIN_PLATAFORMA, de proposito): o usuario fixo dos testes recebe uma
     * vez, pelo script do repositorio de regras. Sem isso, o teste falha dizendo o que rodar.
     */
    public Api apiDaPlataforma(MockMvc mvc) {
        // So LE o usuario fixo. Nada de transacao nova aqui: a do teste ja travou a linha de controle
        // do LOG_SEGURANCA (criar usuario grava nele), e uma segunda transacao que gravasse no mesmo
        // log ficaria esperando a primeira para sempre (as duas na mesma thread).
        Long id = jdbc.query("SELECT ID FROM " + schemas.login("USUARIO") + " WHERE EMAIL_INDICE = ?", (r, n) -> r.getLong(1),
                cifra.indice(Usuarios.CONTEXTO_EMAIL, EMAIL_PERSISTENTE)).stream().findFirst().orElse(null);
        if (id == null) {
            throw new IllegalStateException("O usuario fixo dos testes (" + EMAIL_PERSISTENTE + ") ainda nao existe: rode o ServidorRealTest "
                    + "uma vez, que o cria, e depois conceda o papel de administrador da plataforma a ele.");
        }
        if (!autorizacao.ehAdminPlataforma(id)) {
            throw new IllegalStateException("O usuario fixo dos testes (" + id + ") nao e administrador da plataforma. Rode uma vez, no "
                    + "repositorio de regras: infra/scripts/conceder-admin-plataforma.ps1 -Ambiente TESTE_DEV -UsuarioId " + id
                    + " -Motivo \"testes da plataforma\"");
        }
        return new Api(mvc, tokens.emitir(id, null, "COMPLETO", Api.IP_DE_TESTE, parametros.atuais(), null).token());
    }

    /** Empresa nova (direto no banco, como faria o administrador da plataforma) com os perfis padrao. */
    public long empresaNova(String cnpj) {
        jdbc.update("INSERT INTO EMPRESA (CNPJ, RAZAO_SOCIAL) VALUES (?, 'Empresa de Teste LTDA')", cnpj);
        long empresaId = jdbc.queryForObject("SELECT ID FROM EMPRESA WHERE CNPJ = ?", Long.class, cnpj);
        autorizacao.criarPerfisPadrao(empresaId, QUEM);
        return empresaId;
    }

    private long pessoaNova(long empresaId) {
        String cpf = GeradorDeDocumentos.cpf();
        String indice = cifra.indice(DocumentoCifrado.CONTEXTO, cpf);
        jdbc.update("INSERT INTO PESSOA (EMPRESA_ID, TIPO, NATUREZA, DOCUMENTO_CIFRADO, DOCUMENTO_INDICE, NOME)"
                + " VALUES (?, 'COLABORADOR', 'F', ?, ?, 'Usuario de Teste')", empresaId,
                cifra.cifrar(DocumentoCifrado.CONTEXTO, cpf), indice);
        return jdbc.queryForObject("SELECT ID FROM PESSOA WHERE EMPRESA_ID = ? AND DOCUMENTO_INDICE = ?", Long.class,
                empresaId, indice);
    }
}
