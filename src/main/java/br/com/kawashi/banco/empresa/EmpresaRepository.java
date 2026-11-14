package br.com.kawashi.banco.empresa;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface EmpresaRepository extends JpaRepository<Empresa, Long>, JpaSpecificationExecutor<Empresa> {

    boolean existsByCnpj(String cnpj);

    /**
     * Le a empresa travando a linha ate o fim da transacao (SELECT ... FOR UPDATE). Usado por quem
     * cria ou altera um filho (filial, pessoa): garante que "a empresa esta ativa" continue verdade
     * ate o INSERT e serializa a soma das participacoes dos socios.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Empresa e where e.id = :id")
    Optional<Empresa> buscarTravando(@Param("id") Long id);
}
