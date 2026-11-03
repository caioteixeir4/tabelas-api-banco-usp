package br.com.kawashi.banco.empresa;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface EmpresaRepository extends JpaRepository<Empresa, Long> {

    boolean existsByCnpj(String cnpj);

    Page<Empresa> findByClienteId(Long clienteId, Pageable paginacao);

    /**
     * Le a empresa travando a linha ate o fim da transacao (SELECT ... FOR UPDATE). Usado por quem
     * cria um filho e precisa que "a empresa esta ativa" continue verdade ate o INSERT.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Empresa e where e.id = :id")
    Optional<Empresa> buscarTravando(@Param("id") Long id);
}
