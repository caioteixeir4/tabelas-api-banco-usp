package br.com.kawashi.banco.filial;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FilialRepository extends JpaRepository<Filial, Long> {

    boolean existsByCnpj(String cnpj);

    Page<Filial> findByEmpresaId(Long empresaId, Pageable paginacao);
}
