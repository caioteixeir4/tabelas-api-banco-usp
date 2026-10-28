package br.com.kawashi.banco.contato;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import br.com.kawashi.banco.cliente.Cliente;
import jakarta.persistence.LockModeType;

public interface ContatoRepository extends JpaRepository<Contato, Long> {

    Page<Contato> findByClienteId(Long clienteId, Pageable paginacao);

    /** Procura o contato DENTRO do cliente: contato de outro cliente nao e encontrado. */
    Optional<Contato> findByIdAndClienteId(Long id, Long clienteId);

    long countByClienteId(Long clienteId);

    List<Contato> findByClienteIdAndPrincipalTrue(Long clienteId);

    boolean existsByClienteIdAndPrincipalTrueAndIdNot(Long clienteId, Long id);

    /**
     * Le o cliente travando a linha dele ate o fim da transacao. Todas as regras de "um unico
     * principal" olham para os contatos do cliente inteiro; sem esta trava, dois pedidos simultaneos
     * poderiam gravar dois principais (ou nenhum). A trava serializa as alteracoes por cliente.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cliente c where c.id = :id")
    Optional<Cliente> buscarClienteTravando(@Param("id") Long id);
}
