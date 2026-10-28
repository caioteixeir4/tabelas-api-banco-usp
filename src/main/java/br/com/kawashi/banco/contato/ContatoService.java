package br.com.kawashi.banco.contato;

import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.kawashi.banco.cliente.Cliente;
import br.com.kawashi.banco.cliente.ClienteRepository;
import br.com.kawashi.banco.compartilhado.Documentos;
import br.com.kawashi.banco.compartilhado.erros.RecursoNaoEncontradoException;
import br.com.kawashi.banco.compartilhado.erros.RegraDeNegocioException;

/**
 * Regras de negocio de Contato. O banco garante tipo, tamanho e formato do telefone; o que e REGRA
 * mora aqui: o contato sempre pertence a um cliente existente (e e procurado DENTRO dele), so se cria
 * contato para cliente ativo, todo contato tem email ou telefone, o primeiro contato e o principal,
 * existe um unico principal por cliente e o principal nao some enquanto houver outros contatos.
 */
@Service
@Transactional
public class ContatoService {

    private final ContatoRepository repositorio;
    private final ClienteRepository clientes;

    public ContatoService(ContatoRepository repositorio, ClienteRepository clientes) {
        this.repositorio = repositorio;
        this.clientes = clientes;
    }

    public Contato criar(Long clienteId, ContatoRequest dados) {
        Cliente cliente = clienteParaAlterar(clienteId);
        if (!cliente.isAtivo()) {
            throw new RegraDeNegocioException(
                    "Não é possível cadastrar contato para um cliente inativo. Reative o cliente antes.");
        }
        String email = normalizarEmail(dados.email());
        String telefone = normalizarTelefone(dados.telefone());
        exigirMeioDeContato(email, telefone);

        boolean primeiroContato = repositorio.countByClienteId(clienteId) == 0;
        boolean principal = primeiroContato || Boolean.TRUE.equals(dados.principal());
        if (principal) {
            rebaixarOutrosPrincipais(clienteId, null);
        }
        return repositorio.save(new Contato(cliente, dados.nome().trim(), email, telefone, principal));
    }

    @Transactional(readOnly = true)
    public Contato buscar(Long clienteId, Long id) {
        clienteExistente(clienteId);
        return contatoDoCliente(clienteId, id);
    }

    @Transactional(readOnly = true)
    public Page<Contato> listar(Long clienteId, Pageable paginacao) {
        clienteExistente(clienteId);
        return repositorio.findByClienteId(clienteId, paginacao);
    }

    public Contato atualizar(Long clienteId, Long id, ContatoRequest dados) {
        clienteParaAlterar(clienteId);
        Contato contato = contatoDoCliente(clienteId, id);
        String email = normalizarEmail(dados.email());
        String telefone = normalizarTelefone(dados.telefone());
        exigirMeioDeContato(email, telefone);

        boolean principal = Boolean.TRUE.equals(dados.principal());
        if (contato.isPrincipal() && !principal
                && !repositorio.existsByClienteIdAndPrincipalTrueAndIdNot(clienteId, id)) {
            throw new RegraDeNegocioException("O cliente precisa ter um contato principal. Para trocar o principal, "
                    + "marque outro contato como principal em vez de desmarcar este.");
        }
        if (principal) {
            rebaixarOutrosPrincipais(clienteId, id);
        }
        contato.atualizar(dados.nome().trim(), email, telefone, principal);
        return contato;
    }

    public void excluir(Long clienteId, Long id) {
        clienteParaAlterar(clienteId);
        Contato contato = contatoDoCliente(clienteId, id);
        if (contato.isPrincipal() && repositorio.countByClienteId(clienteId) > 1) {
            throw new RegraDeNegocioException("O contato principal não pode ser excluído enquanto o cliente tiver "
                    + "outros contatos. Marque outro contato como principal antes de excluir este.");
        }
        repositorio.delete(contato);
    }

    /** Leitura: o cliente do caminho precisa existir. */
    private Cliente clienteExistente(Long clienteId) {
        return clientes.findById(clienteId).orElseThrow(() -> new RecursoNaoEncontradoException("Cliente", clienteId));
    }

    /** Escrita: igual a leitura, mas trava a linha do cliente para serializar as alteracoes dos contatos dele. */
    private Cliente clienteParaAlterar(Long clienteId) {
        return repositorio.buscarClienteTravando(clienteId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Cliente", clienteId));
    }

    /** Contato de outro cliente e tratado como inexistente. */
    private Contato contatoDoCliente(Long clienteId, Long id) {
        return repositorio.findByIdAndClienteId(id, clienteId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Contato", id));
    }

    private static void exigirMeioDeContato(String email, String telefone) {
        if (email == null && telefone == null) {
            throw new RegraDeNegocioException(
                    "O contato precisa de pelo menos um meio de contato: informe o e-mail ou o telefone.");
        }
    }

    /** Garante um unico principal: quem esta sendo gravado como principal rebaixa os demais. */
    private void rebaixarOutrosPrincipais(Long clienteId, Long idMantido) {
        repositorio.findByClienteIdAndPrincipalTrue(clienteId).stream()
                .filter(outro -> !outro.getId().equals(idMantido))
                .forEach(Contato::rebaixar);
    }

    private static String normalizarEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizarTelefone(String telefone) {
        return telefone == null || telefone.isBlank() ? null : Documentos.somenteDigitos(telefone);
    }
}
