package br.com.kawashi.banco.autorizacao;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import br.com.kawashi.banco.compartilhado.erros.AcessoNegadoException;
import br.com.kawashi.banco.compartilhado.erros.RecursoNaoEncontradoException;
import br.com.kawashi.banco.login.SchemasDoLogin;
import br.com.kawashi.banco.login.SessaoAtual;
import br.com.kawashi.banco.login.Tokens;

/**
 * O que o usuario do pedido pode fazer, e em qual empresa. Regras:
 * <ul>
 *   <li>a sessao esta presa a UMA empresa ({@code TOKEN.EMPRESA_ID}); todo registro de outra
 *       empresa responde 404, como se nao existisse ({@link #conferirEmpresa});</li>
 *   <li>as permissoes sao lidas do banco a cada pedido (uma consulta, guardada no proprio pedido):
 *       trocar o cargo de alguem vale na hora;</li>
 *   <li>o perfil FIXO (ADMINISTRADOR) tem todas as permissoes, implicitamente;</li>
 *   <li>FALHA FECHADA: sem sessao (inclusive fora de um pedido HTTP: uma tarefa agendada, uma
 *       thread nova) tudo e negado. Codigo interno que precisa passar sem conferir diz isso
 *       explicitamente com {@link #semConferir}.</li>
 * </ul>
 */
@Component
public class Autorizacao {

    private static final String ATRIBUTO = "kawashi.permissoes";
    private static final ThreadLocal<Boolean> SEM_CONFERIR = new ThreadLocal<>();

    /** As permissoes de cada perfil com que uma empresa nasce (o ADMINISTRADOR tem todas). */
    static final Map<String, List<String>> PERFIS_PADRAO = Map.of(
            "OPERADOR", List.of(Permissoes.EMPRESA_LER, Permissoes.FILIAL_LER, Permissoes.FILIAL_GERENCIAR,
                    Permissoes.PESSOA_LER, Permissoes.PESSOA_GERENCIAR),
            "CONSULTA", List.of(Permissoes.EMPRESA_LER, Permissoes.FILIAL_LER, Permissoes.PESSOA_LER));

    public record Vinculo(long empresaId, String razaoSocial, String perfil, boolean administrador) {
    }

    private final JdbcTemplate jdbc;
    private final SchemasDoLogin schemas;

    public Autorizacao(JdbcTemplate jdbc, SchemasDoLogin schemas) {
        this.jdbc = jdbc;
        this.schemas = schemas;
    }

    // ---- conferencias do pedido em andamento ----------------------------------------------------

    /** A empresa da sessao do pedido (vazio fora de um pedido ou sem empresa escolhida). */
    public static Optional<Long> empresaDoPedido() {
        return SessaoAtual.doPedido().map(Tokens.Sessao::empresaId);
    }

    /**
     * O registro e da empresa da sessao? Senao, 404 (nem confirma que existe). Sem sessao (ate fora
     * de um pedido HTTP), 404 tambem; so {@link #semConferir} passa direto.
     */
    public static void conferirEmpresa(Long empresaDoRegistro, String recurso, Object id) {
        if (liberado()) {
            return;
        }
        Long atual = empresaDoPedido().orElse(null);
        if (atual == null || !atual.equals(empresaDoRegistro)) {
            throw new RecursoNaoEncontradoException(recurso, id);
        }
    }

    /** Exige pelo menos uma das permissoes, na empresa da sessao (403). */
    public void exigir(String... algumaDestas) {
        if (liberado()) {
            return;
        }
        Set<String> tem = permissoesDoPedido();
        if (Arrays.stream(algumaDestas).noneMatch(tem::contains)) {
            throw new AcessoNegadoException("Sem permissão", "O seu perfil nesta empresa não permite esta ação.");
        }
    }

    /** As permissoes do usuario na empresa da sessao (vazio sem empresa). Lidas uma vez por pedido. */
    @SuppressWarnings("unchecked")
    public Set<String> permissoesDoPedido() {
        RequestAttributes atributos = RequestContextHolder.getRequestAttributes();
        if (atributos != null) {
            Object guardadas = atributos.getAttribute(ATRIBUTO, RequestAttributes.SCOPE_REQUEST);
            if (guardadas != null) {
                return (Set<String>) guardadas;
            }
        }
        Optional<Tokens.Sessao> sessao = SessaoAtual.doPedido();
        Set<String> permissoes = sessao.isEmpty() || sessao.get().empresaId() == null ? Set.of()
                : permissoes(sessao.get().usuarioId(), sessao.get().empresaId());
        if (atributos != null) {
            atributos.setAttribute(ATRIBUTO, permissoes, RequestAttributes.SCOPE_REQUEST);
        }
        return permissoes;
    }

    /** O que o usuario pode fazer na empresa (vazio sem vinculo ativo). */
    public Set<String> permissoes(long usuarioId, long empresaId) {
        List<Boolean> fixo = jdbc.query("SELECT pf.FIXO FROM " + schemas.login("USUARIO_EMPRESA") + " ue JOIN "
                + schemas.login("PERFIL") + " pf ON pf.ID = ue.PERFIL_ID"
                + " WHERE ue.USUARIO_ID = ? AND ue.EMPRESA_ID = ? AND ue.ATIVO AND pf.ATIVO",
                (r, n) -> r.getBoolean(1), usuarioId, empresaId);
        if (fixo.isEmpty()) {
            return Set.of();
        }
        List<String> codigos = fixo.get(0)
                ? jdbc.queryForList("SELECT CODIGO FROM " + schemas.login("PERMISSAO") + " ORDER BY CODIGO", String.class)
                : jdbc.queryForList("SELECT pm.CODIGO FROM " + schemas.login("USUARIO_EMPRESA") + " ue JOIN "
                        + schemas.login("PERFIL_PERMISSAO") + " pp ON pp.PERFIL_ID = ue.PERFIL_ID AND pp.ATIVO JOIN "
                        + schemas.login("PERMISSAO") + " pm ON pm.ID = pp.PERMISSAO_ID"
                        + " WHERE ue.USUARIO_ID = ? AND ue.EMPRESA_ID = ? ORDER BY pm.CODIGO", String.class, usuarioId, empresaId);
        return Set.copyOf(new LinkedHashSet<>(codigos));
    }

    public boolean ehAdminPlataforma(long usuarioId) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.login("ADMIN_PLATAFORMA") + " WHERE USUARIO_ID = ?",
                Long.class, usuarioId);
        return total != null && total > 0;
    }

    // ---- vinculos ----------------------------------------------------------------------------

    /** As empresas em que o usuario entra hoje (vinculo ativo e empresa ativa). */
    public List<Vinculo> vinculosAtivos(long usuarioId) {
        return jdbc.query("SELECT ue.EMPRESA_ID, e.RAZAO_SOCIAL, pf.NOME, pf.FIXO FROM " + schemas.login("USUARIO_EMPRESA") + " ue"
                + " JOIN " + schemas.login("PERFIL") + " pf ON pf.ID = ue.PERFIL_ID"
                + " JOIN EMPRESA e ON e.ID = ue.EMPRESA_ID"
                + " WHERE ue.USUARIO_ID = ? AND ue.ATIVO AND e.ATIVA ORDER BY e.RAZAO_SOCIAL, e.ID",
                (r, n) -> new Vinculo(r.getLong(1), r.getString(2), r.getString(3), r.getBoolean(4)), usuarioId);
    }

    /** A empresa, se o usuario entra em exatamente uma (para a sessao ja nascer nela). */
    public Optional<Long> empresaUnica(long usuarioId) {
        List<Vinculo> v = vinculosAtivos(usuarioId);
        return v.size() == 1 ? Optional.of(v.get(0).empresaId()) : Optional.empty();
    }

    /**
     * Cria os perfis com que toda empresa nasce: ADMINISTRADOR (fixo, tem tudo), OPERADOR e
     * CONSULTA. Devolve o id do ADMINISTRADOR. {@code quem}: "USUARIO:&lt;id&gt;" ou "SCRIPT:&lt;nome&gt;".
     */
    public long criarPerfisPadrao(long empresaId, String quem) {
        jdbc.update("INSERT INTO " + schemas.login("PERFIL") + " (EMPRESA_ID, NOME, DESCRICAO, FIXO, ATUALIZADO_POR)"
                + " VALUES (?, 'ADMINISTRADOR', 'Tem todas as permissões e é o único que dá cargo', TRUE, ?)", empresaId, quem);
        criarPerfil(empresaId, "OPERADOR", "Cadastra e altera filiais e pessoas", PERFIS_PADRAO.get("OPERADOR"), quem);
        criarPerfil(empresaId, "CONSULTA", "Só consulta", PERFIS_PADRAO.get("CONSULTA"), quem);
        return perfilPorNome(empresaId, "ADMINISTRADOR").orElseThrow();
    }

    /** Cria um perfil (nunca fixo) com as permissoes dadas. */
    public long criarPerfil(long empresaId, String nome, String descricao, List<String> permissoes, String quem) {
        jdbc.update("INSERT INTO " + schemas.login("PERFIL") + " (EMPRESA_ID, NOME, DESCRICAO, ATUALIZADO_POR) VALUES (?, ?, ?, ?)",
                empresaId, nome, descricao, quem);
        long id = perfilPorNome(empresaId, nome).orElseThrow();
        for (String codigo : permissoes) {
            jdbc.update("INSERT INTO " + schemas.login("PERFIL_PERMISSAO") + " (PERFIL_ID, PERMISSAO_ID, ATUALIZADO_POR)"
                    + " SELECT ?, ID, ? FROM " + schemas.login("PERMISSAO") + " WHERE CODIGO = ?", id, quem, codigo);
        }
        return id;
    }

    public Optional<Long> perfilPorNome(long empresaId, String nome) {
        return jdbc.query("SELECT ID FROM " + schemas.login("PERFIL") + " WHERE EMPRESA_ID = ? AND NOME = ?",
                (r, n) -> r.getLong(1), empresaId, nome).stream().findFirst();
    }

    /** Liga o usuario a empresa com o perfil (o banco confere que o perfil e da mesma empresa). */
    public void vincular(long usuarioId, long empresaId, long perfilId, String quem) {
        jdbc.update("INSERT INTO " + schemas.login("USUARIO_EMPRESA") + " (USUARIO_ID, EMPRESA_ID, PERFIL_ID, ATUALIZADO_POR)"
                + " VALUES (?, ?, ?, ?)", usuarioId, empresaId, perfilId, quem);
    }

    /**
     * Executa sem as conferencias de empresa e permissao. So para o administrador da plataforma
     * criar uma empresa e o primeiro administrador dela (ele nao tem, e nao deve ter, vinculo com a
     * empresa). Quem chama ja conferiu que e o administrador da plataforma.
     */
    public static <T> T semConferir(java.util.function.Supplier<T> acao) {
        Boolean antes = SEM_CONFERIR.get();
        SEM_CONFERIR.set(Boolean.TRUE);
        try {
            return acao.get();
        } finally {
            if (antes == null) {
                SEM_CONFERIR.remove();
            } else {
                SEM_CONFERIR.set(antes);
            }
        }
    }

    /** Dentro de {@link #semConferir}: o codigo interno pediu, explicitamente, para nao conferir. */
    public static boolean liberado() {
        return Boolean.TRUE.equals(SEM_CONFERIR.get());
    }
}
