package br.com.kawashi.banco.pessoa;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PessoaRepository extends JpaRepository<Pessoa, Long>, JpaSpecificationExecutor<Pessoa> {

    /** A busca e pela impressao digital do documento: o banco nao tem o documento em claro. */
    boolean existsByEmpresaIdAndDocumentoIndiceAndTipo(Long empresaId, String documentoIndice, TipoDePessoa tipo);

    /** Soma das participacoes dos socios da empresa. Nulo quando ainda nao ha socio. */
    @Query("select sum(p.participacao) from Pessoa p where p.empresaId = :empresaId and p.tipo = br.com.kawashi.banco.pessoa.TipoDePessoa.SOCIO")
    BigDecimal somarParticipacoes(@Param("empresaId") Long empresaId);

    /** Igual a anterior, desconsiderando uma pessoa (usada na atualizacao dela propria). */
    @Query("select sum(p.participacao) from Pessoa p where p.empresaId = :empresaId and p.tipo = br.com.kawashi.banco.pessoa.TipoDePessoa.SOCIO and p.id <> :pessoaId")
    BigDecimal somarParticipacoesExceto(@Param("empresaId") Long empresaId, @Param("pessoaId") Long pessoaId);

    /** Le a pessoa travando a linha ate o fim da transacao; usado pelos contatos e enderecos dela. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pessoa p where p.id = :id")
    Optional<Pessoa> buscarTravando(@Param("id") Long id);
}
