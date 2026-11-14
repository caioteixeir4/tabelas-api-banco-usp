package br.com.kawashi.banco.endereco;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** As consultas "do dono" usam o filtro de {@link br.com.kawashi.banco.auxiliar.Dono#filtro()}. */
public interface EnderecoRepository extends JpaRepository<Endereco, Long>, JpaSpecificationExecutor<Endereco> {
}
