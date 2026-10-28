package br.com.kawashi.banco.empresa;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.kawashi.banco.cliente.Cliente;
import br.com.kawashi.banco.cliente.ClienteRepository;
import br.com.kawashi.banco.compartilhado.Documentos;
import br.com.kawashi.banco.compartilhado.erros.ConflitoException;
import br.com.kawashi.banco.compartilhado.erros.RecursoNaoEncontradoException;
import br.com.kawashi.banco.compartilhado.erros.RegraDeNegocioException;

/**
 * Regras de negocio de Empresa. O banco garante formato, tamanho, unicidade e chaves; o que e REGRA
 * mora aqui: o cliente existe e esta ativo, a empresa e sempre a matriz (ordem 0001), o CNPJ nao
 * muda, e empresa com filiais ou socios nao e apagada.
 */
@Service
@Transactional
public class EmpresaService {

    /** Ordem do CNPJ (digitos 9 a 12) que identifica a matriz. */
    private static final String ORDEM_MATRIZ = "0001";

    private final EmpresaRepository repositorio;
    private final ClienteRepository clientes;
    private final JdbcTemplate jdbc;

    public EmpresaService(EmpresaRepository repositorio, ClienteRepository clientes, JdbcTemplate jdbc) {
        this.repositorio = repositorio;
        this.clientes = clientes;
        this.jdbc = jdbc;
    }

    public Empresa criar(Long clienteId, EmpresaRequest dados) {
        Cliente cliente = buscarCliente(clienteId);
        if (!cliente.isAtivo()) {
            throw new RegraDeNegocioException("O cliente " + clienteId
                    + " está inativo: só é possível cadastrar empresa para cliente ativo. Reative o cliente primeiro.");
        }
        String cnpj = Documentos.somenteDigitos(dados.cnpj());
        if (!ORDEM_MATRIZ.equals(Documentos.ordemCnpj(cnpj))) {
            throw new RegraDeNegocioException("A empresa é sempre a matriz, então o CNPJ precisa ter a ordem "
                    + ORDEM_MATRIZ + " (dígitos 9 a 12), mas o informado tem a ordem " + Documentos.ordemCnpj(cnpj)
                    + ". Filiais se cadastram em /api/empresas/{id}/filiais.");
        }
        if (repositorio.existsByCnpj(cnpj)) {
            throw new ConflitoException("Já existe uma empresa com o CNPJ " + cnpj + ".");
        }
        return repositorio.save(new Empresa(cliente, cnpj, dados.razaoSocial().trim(), limpar(dados.nomeFantasia())));
    }

    @Transactional(readOnly = true)
    public Empresa buscar(Long id) {
        return repositorio.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Empresa", id));
    }

    /** Empresas de um cliente. Cliente que nao existe e 404 (e nao uma lista vazia). */
    @Transactional(readOnly = true)
    public Page<Empresa> listar(Long clienteId, Pageable paginacao) {
        buscarCliente(clienteId);
        return repositorio.findByClienteId(clienteId, paginacao);
    }

    public Empresa atualizar(Long id, EmpresaRequest dados) {
        Empresa empresa = buscar(id);
        if (!empresa.getCnpj().equals(Documentos.somenteDigitos(dados.cnpj()))) {
            throw new RegraDeNegocioException("O CNPJ de uma empresa não pode ser alterado depois do cadastro.");
        }
        empresa.atualizar(dados.razaoSocial().trim(), limpar(dados.nomeFantasia()));
        return empresa;
    }

    /** Apenas troca a situacao: nao ha cascata para filiais nem socios. */
    public Empresa inativar(Long id) {
        Empresa empresa = buscar(id);
        empresa.inativar();
        return empresa;
    }

    public Empresa reativar(Long id) {
        Empresa empresa = buscar(id);
        empresa.reativar();
        return empresa;
    }

    /**
     * Apaga uma empresa SEM vinculos. Com filiais ou socios a resposta e um 409 dizendo quantos sao,
     * e o caminho correto e inativar. A checagem e por SQL direto para esta fatia nao depender das
     * outras: o banco ainda barraria pela chave estrangeira.
     */
    public void excluir(Long id) {
        Empresa empresa = buscar(id);
        long filiais = contar("FILIAL", id);
        long socios = contar("SOCIO", id);
        if (filiais + socios > 0) {
            throw new ConflitoException("A empresa possui vínculos (" + filiais + " filial(is), " + socios
                    + " sócio(s)) e não pode ser excluída. Inative-a em vez de excluir.");
        }
        repositorio.delete(empresa);
        // Grava ja: quem consulta por SQL direto na mesma transacao (como a exclusao de Cliente,
        // que conta as empresas) precisa enxergar a empresa como apagada.
        repositorio.flush();
    }

    private Cliente buscarCliente(Long clienteId) {
        return clientes.findById(clienteId).orElseThrow(() -> new RecursoNaoEncontradoException("Cliente", clienteId));
    }

    private long contar(String tabela, Long empresaId) {
        // O nome da tabela vem de constantes deste metodo, nunca de entrada do usuario.
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + tabela + " WHERE EMPRESA_ID = ?", Long.class, empresaId);
        return total == null ? 0 : total;
    }

    private static String limpar(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }
}
