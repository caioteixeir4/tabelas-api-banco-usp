package br.com.kawashi.banco.cliente;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ClienteRepository extends JpaRepository<Cliente, Long>, JpaSpecificationExecutor<Cliente> {

    boolean existsByCnpj(String cnpj);

    /**
     * Le o cliente travando a linha ate o fim da transacao (SELECT ... FOR UPDATE). Usado por quem
     * cria um filho e precisa que "o cliente esta ativo" continue verdade ate o INSERT.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cliente c where c.id = :id")
    Optional<Cliente> buscarTravando(@Param("id") Long id);
}
