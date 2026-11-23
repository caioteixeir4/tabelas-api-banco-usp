package br.com.kawashi.banco.autorizacao;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.kawashi.banco.compartilhado.erros.ConflitoException;
import br.com.kawashi.banco.compartilhado.erros.RecursoNaoEncontradoException;
import br.com.kawashi.banco.compartilhado.erros.RegraDeNegocioException;
import br.com.kawashi.banco.login.SchemasDoLogin;
import br.com.kawashi.banco.login.SessaoAtual;
import br.com.kawashi.banco.login.Tokens;
import br.com.kawashi.banco.login.Usuarios;
import br.com.kawashi.banco.pessoa.Pessoa;
import br.com.kawashi.banco.pessoa.PessoaService;

/**
 * Usuarios e cargos DENTRO da empresa da sessao (o administrador dela). Toda mudanca de cargo e de
 * permissao vai para o LOG_PERMISSAO pelos gatilhos do banco, com "USUARIO:&lt;id&gt;" de quem fez.
 * Regras que o banco tambem garante: pelo menos um administrador ativo; perfil da mesma empresa;
 * permissoes exclusivas so no ADMINISTRADOR, que tem todas e nao se edita.
 */
@Service
@Transactional
public class ServicoDeUsuarios {

    public record Criado(long usuarioId, String convite) {
    }

    private final JdbcTemplate jdbc;
    private final SchemasDoLogin schemas;
    private final Autorizacao autorizacao;
    private final Usuarios usuarios;
    private final PessoaService pessoas;
    private final Tokens tokens;

    public ServicoDeUsuarios(JdbcTemplate jdbc, SchemasDoLogin schemas, Autorizacao autorizacao, Usuarios usuarios,
            PessoaService pessoas, Tokens tokens) {
        this.jdbc = jdbc;
        this.schemas = schemas;
        this.autorizacao = autorizacao;
        this.usuarios = usuarios;
        this.pessoas = pessoas;
        this.tokens = tokens;
    }

    // ---- usuarios ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listar() {
        long empresa = empresa();
        return jdbc.query("SELECT USUARIO_ID, NOME, CARGO, PERFIL, ATIVO, PASSKEYS_ATIVAS, ULTIMO_LOGIN_EM FROM "
                + schemas.login("V_USUARIO_ACESSOS") + " WHERE EMPRESA_ID = ? ORDER BY NOME, USUARIO_ID", (r, n) -> {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("usuarioId", r.getLong(1));
                    u.put("nome", r.getString(2));
                    u.put("cargo", r.getString(3));
                    u.put("perfil", r.getString(4));
                    u.put("ativo", r.getBoolean(5));
                    u.put("passkeysAtivas", r.getInt(6));
                    u.put("ultimoLoginEm", r.getTimestamp(7) == null ? null : r.getTimestamp(7).toLocalDateTime().atOffset(ZoneOffset.UTC));
                    return u;
                }, empresa);
    }

    /** Cria o usuario de uma pessoa DESTA empresa, ja com o cargo; o convite vem uma vez. */
    public Criado criar(long pessoaId, String email, long perfilId) {
        long empresa = empresa();
        autorizacao.exigir(Permissoes.PERFIL_GERENCIAR);  // criar o usuario ja da o cargo
        Pessoa pessoa = pessoas.buscar(pessoaId);  // de outra empresa: 404
        if (!pessoa.isAtiva()) {
            throw new RegraDeNegocioException("A pessoa " + pessoaId + " está inativa.");
        }
        Long jaTem = jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.login("USUARIO") + " WHERE PESSOA_ID = ?", Long.class, pessoaId);
        if (jaTem != null && jaTem > 0) {
            throw new ConflitoException("A pessoa " + pessoaId + " já tem usuário.");
        }
        exigirPerfilDaEmpresa(perfilId, empresa, true);
        String quem = SessaoAtual.quemNoLog();
        Usuarios.Criado criado = usuarios.criar(pessoaId, email, quem);
        autorizacao.vincular(criado.usuarioId(), empresa, perfilId, quem);
        return new Criado(criado.usuarioId(), criado.convite());
    }

    /** Troca o cargo. O banco recusa (422) se a empresa ficar sem administrador ativo. */
    public void trocarPerfil(long usuarioId, long perfilId) {
        long empresa = empresa();
        exigirPerfilDaEmpresa(perfilId, empresa, true);
        int mudou = jdbc.update("UPDATE " + schemas.login("USUARIO_EMPRESA") + " SET PERFIL_ID = ?, ATUALIZADO_POR = ?"
                + " WHERE USUARIO_ID = ? AND EMPRESA_ID = ?", perfilId, SessaoAtual.quemNoLog(), usuarioId, empresa);
        if (mudou == 0) {
            throw new RecursoNaoEncontradoException("Usuário", usuarioId);
        }
    }

    /** Tira o acesso a esta empresa (as sessoes dele nela caem na hora). Nada e apagado. */
    public void desativar(long usuarioId) {
        long empresa = empresa();
        mudarSituacao(usuarioId, empresa, false);
        tokens.revogarDaEmpresa(usuarioId, empresa, "VINCULO_DESATIVADO");
    }

    public void reativar(long usuarioId) {
        mudarSituacao(usuarioId, empresa(), true);
    }

    /**
     * Convite novo para quem perdeu as passkeys e os codigos. So para usuario cuja pessoa e DESTA
     * empresa: usado, o convite derruba todas as sessoes dele, e o administrador de uma empresa nao
     * pode fazer isso com quem e de outra.
     */
    public String novoConvite(long usuarioId) {
        long empresa = empresa();
        Long daEmpresa = jdbc.queryForObject("SELECT COUNT(*) FROM " + schemas.login("USUARIO") + " u JOIN PESSOA p ON p.ID = u.PESSOA_ID"
                + " JOIN " + schemas.login("USUARIO_EMPRESA") + " ue ON ue.USUARIO_ID = u.ID AND ue.EMPRESA_ID = p.EMPRESA_ID"
                + " WHERE u.ID = ? AND p.EMPRESA_ID = ?", Long.class, usuarioId, empresa);
        if (daEmpresa == null || daEmpresa == 0) {
            throw new RecursoNaoEncontradoException("Usuário", usuarioId);
        }
        return usuarios.novoConvite(usuarioId, SessaoAtual.quemNoLog());
    }

    /** O LOG_PERMISSAO desta empresa: quem mudou o cargo de quem, quando, de qual para qual. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> historico() {
        return jdbc.query("SELECT ORDEM, EVENTO, USUARIO_ID, PERFIL_ANTERIOR, PERFIL_NOVO, PERMISSAO, FEITO_POR, DATA_HORA FROM "
                + schemas.log("LOG_PERMISSAO") + " WHERE EMPRESA_ID = ? ORDER BY ORDEM DESC FETCH FIRST 200 ROWS ONLY", (r, n) -> {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("ordem", r.getLong(1));
                    e.put("evento", r.getString(2));
                    e.put("usuarioId", r.getObject(3, Long.class));
                    e.put("de", r.getString(4));
                    e.put("para", r.getString(5));
                    e.put("permissao", r.getString(6));
                    e.put("feitoPor", r.getString(7));
                    e.put("dataHora", r.getTimestamp(8).toLocalDateTime().atOffset(ZoneOffset.UTC));
                    return e;
                }, empresa());
    }

    // ---- perfis --------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> perfis() {
        long empresa = empresa();
        List<String> todas = codigosDePermissao();
        return jdbc.query("SELECT ID, NOME, DESCRICAO, FIXO, ATIVO FROM " + schemas.login("PERFIL") + " WHERE EMPRESA_ID = ?"
                + " ORDER BY FIXO DESC, NOME", (r, n) -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    long id = r.getLong(1);
                    boolean fixo = r.getBoolean(4);
                    p.put("id", id);
                    p.put("nome", r.getString(2));
                    p.put("descricao", r.getString(3));
                    p.put("fixo", fixo);
                    p.put("ativo", r.getBoolean(5));
                    p.put("permissoes", fixo ? todas : permissoesDoPerfil(id));
                    return p;
                }, empresa);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> permissoes() {
        return jdbc.query("SELECT CODIGO, DESCRICAO, EXCLUSIVA_ADMIN FROM " + schemas.login("PERMISSAO") + " ORDER BY CODIGO", (r, n) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("codigo", r.getString(1));
            p.put("descricao", r.getString(2));
            p.put("exclusivaDoAdministrador", r.getBoolean(3));
            return p;
        });
    }

    public long criarPerfil(String nome, String descricao, List<String> permissoes) {
        long empresa = empresa();
        String limpo = nome.strip().toUpperCase(java.util.Locale.ROOT);
        if (autorizacao.perfilPorNome(empresa, limpo).isPresent()) {
            throw new ConflitoException("Já existe o perfil " + limpo + " nesta empresa.");
        }
        Set<String> pedidas = validarPermissoes(permissoes);
        return autorizacao.criarPerfil(empresa, limpo, descricao, List.copyOf(pedidas), SessaoAtual.quemNoLog());
    }

    /** Deixa o perfil com exatamente estas permissoes (as que saem sao desativadas, nunca apagadas). */
    public void definirPermissoes(long perfilId, List<String> permissoes) {
        long empresa = empresa();
        boolean fixo = exigirPerfilDaEmpresa(perfilId, empresa, false);
        if (fixo) {
            throw new RegraDeNegocioException("O ADMINISTRADOR tem todas as permissões e não se edita.");
        }
        Set<String> pedidas = validarPermissoes(permissoes);
        String quem = SessaoAtual.quemNoLog();
        for (String codigo : codigosDePermissao()) {
            boolean quer = pedidas.contains(codigo);
            List<Boolean> atual = jdbc.query("SELECT pp.ATIVO FROM " + schemas.login("PERFIL_PERMISSAO") + " pp JOIN "
                    + schemas.login("PERMISSAO") + " pm ON pm.ID = pp.PERMISSAO_ID WHERE pp.PERFIL_ID = ? AND pm.CODIGO = ?",
                    (r, n) -> r.getBoolean(1), perfilId, codigo);
            if (atual.isEmpty()) {
                if (quer) {
                    jdbc.update("INSERT INTO " + schemas.login("PERFIL_PERMISSAO") + " (PERFIL_ID, PERMISSAO_ID, ATUALIZADO_POR)"
                            + " SELECT ?, ID, ? FROM " + schemas.login("PERMISSAO") + " WHERE CODIGO = ?", perfilId, quem, codigo);
                }
            } else if (atual.get(0) != quer) {
                jdbc.update("UPDATE " + schemas.login("PERFIL_PERMISSAO") + " SET ATIVO = ?, ATUALIZADO_POR = ? WHERE PERFIL_ID = ?"
                        + " AND PERMISSAO_ID = (SELECT ID FROM " + schemas.login("PERMISSAO") + " WHERE CODIGO = ?)", quer, quem, perfilId, codigo);
            }
        }
    }

    // ---- apoio ---------------------------------------------------------------------------------

    private static long empresa() {
        return Autorizacao.empresaDoPedido().orElseThrow(() -> new IllegalStateException("Pedido sem empresa depois do filtro."));
    }

    /** O perfil e desta empresa (senao 404). Devolve se e o fixo. */
    private boolean exigirPerfilDaEmpresa(long perfilId, long empresa, boolean precisaAtivo) {
        List<Boolean[]> achado = jdbc.query("SELECT FIXO, ATIVO FROM " + schemas.login("PERFIL") + " WHERE ID = ? AND EMPRESA_ID = ?",
                (r, n) -> new Boolean[] {r.getBoolean(1), r.getBoolean(2)}, perfilId, empresa);
        if (achado.isEmpty()) {
            throw new RecursoNaoEncontradoException("Perfil", perfilId);
        }
        if (precisaAtivo && !achado.get(0)[1]) {
            throw new RegraDeNegocioException("O perfil " + perfilId + " está desativado.");
        }
        return achado.get(0)[0];
    }

    private void mudarSituacao(long usuarioId, long empresa, boolean ativo) {
        int mudou = jdbc.update("UPDATE " + schemas.login("USUARIO_EMPRESA") + " SET ATIVO = ?, ATUALIZADO_POR = ?"
                + " WHERE USUARIO_ID = ? AND EMPRESA_ID = ?", ativo, SessaoAtual.quemNoLog(), usuarioId, empresa);
        if (mudou == 0) {
            throw new RecursoNaoEncontradoException("Usuário", usuarioId);
        }
    }

    private List<String> codigosDePermissao() {
        return jdbc.queryForList("SELECT CODIGO FROM " + schemas.login("PERMISSAO") + " ORDER BY CODIGO", String.class);
    }

    private List<String> permissoesDoPerfil(long perfilId) {
        return jdbc.queryForList("SELECT pm.CODIGO FROM " + schemas.login("PERFIL_PERMISSAO") + " pp JOIN " + schemas.login("PERMISSAO")
                + " pm ON pm.ID = pp.PERMISSAO_ID WHERE pp.PERFIL_ID = ? AND pp.ATIVO ORDER BY pm.CODIGO", String.class, perfilId);
    }

    /** Codigos que existem e nao sao exclusivos do ADMINISTRADOR (422 senao). */
    private Set<String> validarPermissoes(List<String> permissoes) {
        Map<String, Boolean> existentes = new LinkedHashMap<>();
        jdbc.query("SELECT CODIGO, EXCLUSIVA_ADMIN FROM " + schemas.login("PERMISSAO"),
                (org.springframework.jdbc.core.RowCallbackHandler) r -> existentes.put(r.getString(1), r.getBoolean(2)));
        Set<String> pedidas = new LinkedHashSet<>();
        for (String c : permissoes == null ? List.<String>of() : permissoes) {
            String codigo = c == null ? "" : c.strip();
            if (!existentes.containsKey(codigo)) {
                throw new RegraDeNegocioException("Permissão desconhecida: " + codigo + ".");
            }
            if (existentes.get(codigo)) {
                throw new RegraDeNegocioException("A permissão " + codigo + " é exclusiva do ADMINISTRADOR.");
            }
            pedidas.add(codigo);
        }
        return pedidas;
    }
}
