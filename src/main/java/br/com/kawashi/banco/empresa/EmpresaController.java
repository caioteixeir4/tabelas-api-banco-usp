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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import br.com.kawashi.banco.compartilhado.Pagina;
import jakarta.validation.Valid;

/**
 * Criar e listar sao aninhados no cliente (a empresa nasce dentro de um cliente); operar sobre uma
 * empresa especifica usa o caminho plano /api/empresas/{id}.
 */
@RestController
@RequestMapping("/api")
public class EmpresaController {

    private final EmpresaService servico;

    public EmpresaController(EmpresaService servico) {
        this.servico = servico;
    }

    @PostMapping("/clientes/{clienteId}/empresas")
    public ResponseEntity<EmpresaResponse> criar(@PathVariable Long clienteId, @Valid @RequestBody EmpresaRequest dados,
            UriComponentsBuilder uri) {
        Empresa criada = servico.criar(clienteId, dados);
        URI local = uri.path("/api/empresas/{id}").buildAndExpand(criada.getId()).toUri();
        return ResponseEntity.created(local).body(EmpresaResponse.de(criada));
    }

    @GetMapping("/clientes/{clienteId}/empresas")
    public Pagina<EmpresaResponse> listar(@PathVariable Long clienteId,
            @PageableDefault(size = 20, sort = "id") Pageable paginacao) {
        return Pagina.de(servico.listar(clienteId, paginacao), EmpresaResponse::de);
    }

    @GetMapping("/empresas/{id}")
    public EmpresaResponse buscar(@PathVariable Long id) {
        return EmpresaResponse.de(servico.buscar(id));
    }

    @PutMapping("/empresas/{id}")
    public EmpresaResponse atualizar(@PathVariable Long id, @Valid @RequestBody EmpresaRequest dados) {
        return EmpresaResponse.de(servico.atualizar(id, dados));
    }

    @PostMapping("/empresas/{id}/inativar")
    public EmpresaResponse inativar(@PathVariable Long id) {
        return EmpresaResponse.de(servico.inativar(id));
    }

    @PostMapping("/empresas/{id}/reativar")
    public EmpresaResponse reativar(@PathVariable Long id) {
        return EmpresaResponse.de(servico.reativar(id));
    }

    @DeleteMapping("/empresas/{id}")
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        servico.excluir(id);
        return ResponseEntity.noContent().build();
    }
}
