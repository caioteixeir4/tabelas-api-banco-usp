package br.com.kawashi.banco.filial;

import br.com.kawashi.banco.autorizacao.Exige;
import br.com.kawashi.banco.autorizacao.Permissoes;

import java.net.URI;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import br.com.kawashi.banco.compartilhado.Pagina;
import br.com.kawashi.banco.compartilhado.Paginas;
import jakarta.validation.Valid;

/**
 * Criar e listar sao aninhados na empresa (a filial nasce dentro de uma); operar sobre uma filial
 * especifica usa o caminho plano /api/filiais/{id}.
 */
@RestController
@RequestMapping("/api")
public class FilialController {

    private final FilialService servico;

    public FilialController(FilialService servico) {
        this.servico = servico;
    }

    @Exige({Permissoes.FILIAL_GERENCIAR})
    @PostMapping("/empresas/{empresaId}/filiais")
    public ResponseEntity<FilialResponse> criar(@PathVariable Long empresaId, @Valid @RequestBody FilialRequest dados,
            UriComponentsBuilder uri) {
        Filial criada = servico.criar(empresaId, dados);
        URI local = uri.path("/api/filiais/{id}").buildAndExpand(criada.getId()).toUri();
        return ResponseEntity.created(local).body(FilialResponse.de(criada));
    }

    @Exige({Permissoes.FILIAL_LER, Permissoes.FILIAL_GERENCIAR})
    @GetMapping("/empresas/{empresaId}/filiais")
    public Pagina<FilialResponse> listar(@PathVariable Long empresaId,
            @PageableDefault(size = 20, sort = "id") Pageable paginacao) {
        return Pagina.de(servico.listar(empresaId, Paginas.seguro(paginacao)), FilialResponse::de);
    }

    @Exige({Permissoes.FILIAL_LER, Permissoes.FILIAL_GERENCIAR})
    @GetMapping("/filiais/{id}")
    public FilialResponse buscar(@PathVariable Long id) {
        return FilialResponse.de(servico.buscar(id));
    }

    @Exige({Permissoes.FILIAL_GERENCIAR})
    @PutMapping("/filiais/{id}")
    public FilialResponse atualizar(@PathVariable Long id, @Valid @RequestBody FilialRequest dados) {
        return FilialResponse.de(servico.atualizar(id, dados));
    }

    @Exige({Permissoes.FILIAL_GERENCIAR})
    @PostMapping("/filiais/{id}/inativar")
    public FilialResponse inativar(@PathVariable Long id) {
        return FilialResponse.de(servico.inativar(id));
    }

    @Exige({Permissoes.FILIAL_GERENCIAR})
    @PostMapping("/filiais/{id}/reativar")
    public FilialResponse reativar(@PathVariable Long id) {
        return FilialResponse.de(servico.reativar(id));
    }

    @Exige({Permissoes.FILIAL_GERENCIAR})
    @DeleteMapping("/filiais/{id}")
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        servico.excluir(id);
        return ResponseEntity.noContent().build();
    }
}
