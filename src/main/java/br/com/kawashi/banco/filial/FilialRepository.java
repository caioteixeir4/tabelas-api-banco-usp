package br.com.kawashi.banco.filial;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface FilialRepository extends JpaRepository<Filial, Long> {

    boolean existsByCnpj(String cnpj);

    Page<Filial> findByEmpresaId(Long empresaId, Pageable paginacao);

    /** Le a filial travando a linha ate o fim da transacao; usado pelos contatos e enderecos dela. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Filial f where f.id = :id")
    Optional<Filial> buscarTravando(@Param("id") Long id);
}
