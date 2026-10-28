package br.com.kawashi.banco.endereco;

import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.kawashi.banco.cliente.Cliente;
import br.com.kawashi.banco.cliente.ClienteRepository;
import br.com.kawashi.banco.compartilhado.Documentos;
import br.com.kawashi.banco.compartilhado.erros.RecursoNaoEncontradoException;
import br.com.kawashi.banco.compartilhado.erros.RegraDeNegocioException;

/**
 * Regras de negocio de Endereco (sempre aninhado em um cliente). O banco garante tipo, tamanho e
 * formato; o que e REGRA mora aqui: so cliente ativo recebe endereco, o primeiro endereco e o
 * principal, e um cliente com enderecos tem sempre exatamente UM principal.
 */
@Service
@Transactional
public class EnderecoService {

    private static final String PROMOVA_OUTRO = "promova outro endereço a principal antes.";

    private final EnderecoRepository repositorio;
    private final ClienteRepository clientes;
    private final JdbcTemplate jdbc;

    public EnderecoService(EnderecoRepository repositorio, ClienteRepository clientes, JdbcTemplate jdbc) {
        this.repositorio = repositorio;
        this.clientes = clientes;
        this.jdbc = jdbc;
    }

    public Endereco criar(Long clienteId, EnderecoRequest dados) {
        Cliente cliente = travarCliente(clienteId);
        if (!cliente.isAtivo()) {
            throw new RegraDeNegocioException("O cliente " + clienteId
                    + " está inativo e não pode receber endereço. Reative-o antes de cadastrar um endereço.");
        }
        // O primeiro endereco do cliente e sempre o principal, mesmo que o pedido diga o contrario.
        boolean principal = !repositorio.existsByClienteId(clienteId) || Boolean.TRUE.equals(dados.principal());
        if (principal) {
            rebaixarPrincipais(clienteId, null);
        }
        return repositorio.save(new Endereco(cliente,
                dados.logradouro().trim(),
                limpar(dados.numero()),
                limpar(dados.complemento()),
                limpar(dados.bairro()),
                dados.cidade().trim(),
                dados.uf().trim().toUpperCase(Locale.ROOT),
                Documentos.somenteDigitos(dados.cep()),
                principal));
    }

    @Transactional(readOnly = true)
    public Endereco buscar(Long clienteId, Long id) {
        buscarCliente(clienteId);
        return localizar(clienteId, id);
    }

    @Transactional(readOnly = true)
    public Page<Endereco> listar(Long clienteId, Pageable paginacao) {
        buscarCliente(clienteId);
        return repositorio.findByClienteId(clienteId, paginacao);
    }

    public Endereco atualizar(Long clienteId, Long id, EnderecoRequest dados) {
        travarCliente(clienteId);
        Endereco endereco = localizar(clienteId, id);
        boolean principal = Boolean.TRUE.equals(dados.principal());
        if (endereco.isPrincipal() && !principal) {
            // Como ha sempre um unico principal, se este deixar de ser, o cliente ficaria sem nenhum.
            throw new RegraDeNegocioException(
                    "O endereço principal não pode deixar de ser principal sem que outro assuma: " + PROMOVA_OUTRO);
        }
        if (principal && !endereco.isPrincipal()) {
            rebaixarPrincipais(clienteId, id);
        }
        endereco.atualizar(
                dados.logradouro().trim(),
                limpar(dados.numero()),
                limpar(dados.complemento()),
                limpar(dados.bairro()),
                dados.cidade().trim(),
                dados.uf().trim().toUpperCase(Locale.ROOT),
                Documentos.somenteDigitos(dados.cep()),
                principal);
        return endereco;
    }

    /**
     * Apaga um endereco. O principal so sai quando e o unico do cliente; havendo outros, antes e
     * preciso promover um deles (senao o cliente ficaria com enderecos e sem principal).
     */
    public void excluir(Long clienteId, Long id) {
        travarCliente(clienteId);
        Endereco endereco = localizar(clienteId, id);
        if (endereco.isPrincipal() && repositorio.countByClienteId(clienteId) > 1) {
            throw new RegraDeNegocioException(
                    "O endereço principal não pode ser excluído enquanto o cliente tiver outros endereços: "
                            + PROMOVA_OUTRO);
        }
        repositorio.delete(endereco);
    }

    private Cliente buscarCliente(Long clienteId) {
        return clientes.findById(clienteId).orElseThrow(() -> new RecursoNaoEncontradoException("Cliente", clienteId));
    }

    /**
     * Busca o cliente trancando a linha dele ate o fim da transacao. O banco nao tem como impedir
     * dois principais (nao ha indice unico parcial), entao as operacoes que mexem em enderecos de um
     * mesmo cliente sao serializadas aqui: sem isto, dois pedidos simultaneos poderiam deixar o
     * cliente com dois principais. A leitura do cliente vem DEPOIS da trava, para enxergar o estado
     * mais recente (por exemplo, uma inativacao concorrente).
     */
    private Cliente travarCliente(Long clienteId) {
        List<Long> encontrados = jdbc.queryForList("SELECT ID FROM CLIENTE WHERE ID = ? FOR UPDATE", Long.class, clienteId);
        if (encontrados.isEmpty()) {
            throw new RecursoNaoEncontradoException("Cliente", clienteId);
        }
        return buscarCliente(clienteId);
    }

    /** Endereco de OUTRO cliente e tratado como inexistente. */
    private Endereco localizar(Long clienteId, Long id) {
        return repositorio.findByIdAndClienteId(id, clienteId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Endereço do cliente " + clienteId, id));
    }

    /** Rebaixa os principais do cliente, menos o endereco {@code excetoId} (nulo = nenhuma excecao). */
    private void rebaixarPrincipais(Long clienteId, Long excetoId) {
        for (Endereco atual : repositorio.findByClienteIdAndPrincipalTrue(clienteId)) {
            if (!atual.getId().equals(excetoId)) {
                atual.rebaixar();
            }
        }
    }

    private static String limpar(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }
}
