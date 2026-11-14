package br.com.kawashi.banco.empresa;

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

@RestController
@RequestMapping("/api/empresas")
public class EmpresaController {

    private final EmpresaService servico;

    public EmpresaController(EmpresaService servico) {
        this.servico = servico;
    }

    @PostMapping
    public ResponseEntity<EmpresaResponse> criar(@Valid @RequestBody EmpresaRequest dados, UriComponentsBuilder uri) {
        Empresa criada = servico.criar(dados);
        URI local = uri.path("/api/empresas/{id}").buildAndExpand(criada.getId()).toUri();
        return ResponseEntity.created(local).body(EmpresaResponse.de(criada));
    }

    @GetMapping
    public Pagina<EmpresaResponse> listar(
            @RequestParam(required = false) String cnpj,
            @RequestParam(required = false) String razaoSocial,
            @RequestParam(required = false) Boolean ativa,
            @PageableDefault(size = 20, sort = "razaoSocial") Pageable paginacao) {
        return Pagina.de(servico.listar(cnpj, razaoSocial, ativa, Paginas.seguro(paginacao)), EmpresaResponse::de);
    }

    @GetMapping("/{id}")
    public EmpresaResponse buscar(@PathVariable Long id) {
        return EmpresaResponse.de(servico.buscar(id));
    }

    @PutMapping("/{id}")
    public EmpresaResponse atualizar(@PathVariable Long id, @Valid @RequestBody EmpresaRequest dados) {
        return EmpresaResponse.de(servico.atualizar(id, dados));
    }

    @PostMapping("/{id}/inativar")
    public EmpresaResponse inativar(@PathVariable Long id) {
        return EmpresaResponse.de(servico.inativar(id));
    }

    @PostMapping("/{id}/reativar")
    public EmpresaResponse reativar(@PathVariable Long id) {
        return EmpresaResponse.de(servico.reativar(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        servico.excluir(id);
        return ResponseEntity.noContent().build();
    }
}
