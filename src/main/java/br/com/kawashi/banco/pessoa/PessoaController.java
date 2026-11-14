package br.com.kawashi.banco.pessoa;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import br.com.kawashi.banco.compartilhado.Pagina;
import br.com.kawashi.banco.compartilhado.Paginas;
import jakarta.validation.Valid;

/**
 * Criar e listar sao aninhados na empresa (toda pessoa pertence a uma); operar sobre uma pessoa
 * especifica usa o caminho plano /api/pessoas/{id}.
 */
@RestController
@RequestMapping("/api")
public class PessoaController {

    private final PessoaService servico;

    public PessoaController(PessoaService servico) {
        this.servico = servico;
    }

    @PostMapping("/empresas/{empresaId}/pessoas")
    public ResponseEntity<PessoaResponse> criar(@PathVariable Long empresaId, @Valid @RequestBody PessoaRequest dados,
            UriComponentsBuilder uri) {
        Pessoa criada = servico.criar(empresaId, dados);
        URI local = uri.path("/api/pessoas/{id}").buildAndExpand(criada.getId()).toUri();
        return ResponseEntity.created(local).body(PessoaResponse.de(criada));
    }

    @GetMapping("/empresas/{empresaId}/pessoas")
    public Pagina<PessoaResponse> listar(@PathVariable Long empresaId,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) Boolean ativa,
            @PageableDefault(size = 20, sort = "nome") Pageable paginacao) {
        return Pagina.de(servico.listar(empresaId, tipo, ativa, Paginas.seguro(paginacao)), PessoaResponse::de);
    }

    @GetMapping("/pessoas/{id}")
    public PessoaResponse buscar(@PathVariable Long id) {
        return PessoaResponse.de(servico.buscar(id));
    }

    @PutMapping("/pessoas/{id}")
    public PessoaResponse atualizar(@PathVariable Long id, @Valid @RequestBody PessoaRequest dados) {
        return PessoaResponse.de(servico.atualizar(id, dados));
    }

    @PostMapping("/pessoas/{id}/inativar")
    public PessoaResponse inativar(@PathVariable Long id) {
        return PessoaResponse.de(servico.inativar(id));
    }

    @PostMapping("/pessoas/{id}/reativar")
    public PessoaResponse reativar(@PathVariable Long id) {
        return PessoaResponse.de(servico.reativar(id));
    }

    @DeleteMapping("/pessoas/{id}")
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        servico.excluir(id);
        return ResponseEntity.noContent().build();
    }
}
