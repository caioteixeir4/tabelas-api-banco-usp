package br.com.kawashi.banco.socio;

import java.math.BigDecimal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SocioRepository extends JpaRepository<Socio, Long> {

    boolean existsByEmpresaIdAndCpf(Long empresaId, String cpf);

    Page<Socio> findByEmpresaId(Long empresaId, Pageable paginacao);

    /** Soma das participacoes da empresa. Devolve nulo quando a empresa ainda nao tem socios. */
    @Query("select sum(s.participacao) from Socio s where s.empresaId = :empresaId")
    BigDecimal somarParticipacoes(@Param("empresaId") Long empresaId);

    /** Igual a anterior, mas desconsiderando um socio (usada na atualizacao dele proprio). */
    @Query("select sum(s.participacao) from Socio s where s.empresaId = :empresaId and s.id <> :socioId")
    BigDecimal somarParticipacoesExceto(@Param("empresaId") Long empresaId, @Param("socioId") Long socioId);
}
