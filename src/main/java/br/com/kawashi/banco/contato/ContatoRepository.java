package br.com.kawashi.banco.contato;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** As consultas "do dono" usam o filtro de {@link br.com.kawashi.banco.auxiliar.Dono#filtro()}. */
public interface ContatoRepository extends JpaRepository<Contato, Long>, JpaSpecificationExecutor<Contato> {
}
