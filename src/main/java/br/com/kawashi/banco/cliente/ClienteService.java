package br.com.kawashi.banco.cliente;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.kawashi.banco.compartilhado.Documentos;
import br.com.kawashi.banco.compartilhado.erros.ConflitoException;
import br.com.kawashi.banco.compartilhado.erros.RecursoNaoEncontradoException;
import br.com.kawashi.banco.compartilhado.erros.RegraDeNegocioException;

/**
 * Regras de negocio de Cliente. O banco garante formato, tamanho e unicidade; o que e REGRA mora
 * aqui: CNPJ valido e normalizado, CNPJ nao muda, cliente com vinculos nao e apagado.
 */
@Service
@Transactional
public class ClienteService {

    private final ClienteRepository repositorio;
    private final JdbcTemplate jdbc;

    public ClienteService(ClienteRepository repositorio, JdbcTemplate jdbc) {
        this.repositorio = repositorio;
        this.jdbc = jdbc;
    }

    public Cliente criar(ClienteRequest dados) {
        String cnpj = Documentos.somenteDigitos(dados.cnpj());
        if (repositorio.existsByCnpj(cnpj)) {
            throw new ConflitoException("Já existe um cliente com o CNPJ " + cnpj + ".");
        }
        return repositorio.save(new Cliente(cnpj, dados.razaoSocial().trim(), limpar(dados.nomeFantasia())));
    }

    @Transactional(readOnly = true)
    public Cliente buscar(Long id) {
        return repositorio.findById(id).orElseThrow(() -> new RecursoNaoEncontradoException("Cliente", id));
    }

    @Transactional(readOnly = true)
    public Page<Cliente> listar(String cnpj, String razaoSocial, Boolean ativo, Pageable paginacao) {
        List<Specification<Cliente>> filtros = new ArrayList<>();
        if (cnpj != null && !cnpj.isBlank()) {
            String digitos = Documentos.somenteDigitos(cnpj);
            filtros.add((raiz, consulta, cb) -> cb.equal(raiz.get("cnpj"), digitos));
        }
        if (razaoSocial != null && !razaoSocial.isBlank()) {
            String trecho = "%" + razaoSocial.trim().toLowerCase() + "%";
            filtros.add((raiz, consulta, cb) -> cb.like(cb.lower(raiz.get("razaoSocial")), trecho));
        }
        if (ativo != null) {
            filtros.add((raiz, consulta, cb) -> cb.equal(raiz.get("ativo"), ativo));
        }
        return repositorio.findAll(Specification.allOf(filtros), paginacao);
    }

    public Cliente atualizar(Long id, ClienteRequest dados) {
        Cliente cliente = buscar(id);
        if (!cliente.getCnpj().equals(Documentos.somenteDigitos(dados.cnpj()))) {
            throw new RegraDeNegocioException("O CNPJ de um cliente não pode ser alterado depois do cadastro.");
        }
        cliente.atualizar(dados.razaoSocial().trim(), limpar(dados.nomeFantasia()));
        return cliente;
    }

    public Cliente inativar(Long id) {
        Cliente cliente = buscar(id);
        cliente.inativar();
        return cliente;
    }

    public Cliente reativar(Long id) {
        Cliente cliente = buscar(id);
        cliente.reativar();
        return cliente;
    }

    /**
     * Apaga um cliente SEM vinculos. Com vinculos (empresas, enderecos, contatos) a resposta e um
     * 409 dizendo quantos sao, e o caminho correto e inativar. A checagem e por SQL direto para
     * esta fatia nao depender das outras: o banco ainda barraria pela chave estrangeira.
     */
    public void excluir(Long id) {
        Cliente cliente = buscar(id);
        long empresas = contar("EMPRESA", id);
        long enderecos = contar("ENDERECO", id);
        long contatos = contar("CONTATO", id);
        if (empresas + enderecos + contatos > 0) {
            throw new ConflitoException("O cliente possui vínculos (" + empresas + " empresa(s), " + enderecos
                    + " endereço(s), " + contatos + " contato(s)) e não pode ser excluído. Inative-o em vez de excluir.");
        }
        repositorio.delete(cliente);
    }

    private long contar(String tabela, Long clienteId) {
        // O nome da tabela vem de constantes deste metodo, nunca de entrada do usuario.
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + tabela + " WHERE CLIENTE_ID = ?", Long.class, clienteId);
        return total == null ? 0 : total;
    }

    private static String limpar(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }
}
