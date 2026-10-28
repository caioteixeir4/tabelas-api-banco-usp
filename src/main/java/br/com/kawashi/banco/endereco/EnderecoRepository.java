package br.com.kawashi.banco.endereco;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnderecoRepository extends JpaRepository<Endereco, Long> {

    Page<Endereco> findByClienteId(Long clienteId, Pageable paginacao);

    Optional<Endereco> findByIdAndClienteId(Long id, Long clienteId);

    List<Endereco> findByClienteIdAndPrincipalTrue(Long clienteId);

    boolean existsByClienteId(Long clienteId);

    long countByClienteId(Long clienteId);
}
