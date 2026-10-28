package br.com.kawashi.banco.endereco;

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

@RestController
@RequestMapping("/api/clientes/{clienteId}/enderecos")
public class EnderecoController {

    private final EnderecoService servico;

    public EnderecoController(EnderecoService servico) {
        this.servico = servico;
    }

    @PostMapping
    public ResponseEntity<EnderecoResponse> criar(@PathVariable Long clienteId,
            @Valid @RequestBody EnderecoRequest dados, UriComponentsBuilder uri) {
        Endereco criado = servico.criar(clienteId, dados);
        URI local = uri.path("/api/clientes/{clienteId}/enderecos/{id}").buildAndExpand(clienteId, criado.getId()).toUri();
        return ResponseEntity.created(local).body(EnderecoResponse.de(criado));
    }

    @GetMapping
    public Pagina<EnderecoResponse> listar(@PathVariable Long clienteId,
            @PageableDefault(size = 20, sort = "id") Pageable paginacao) {
        return Pagina.de(servico.listar(clienteId, paginacao), EnderecoResponse::de);
    }

    @GetMapping("/{id}")
    public EnderecoResponse buscar(@PathVariable Long clienteId, @PathVariable Long id) {
        return EnderecoResponse.de(servico.buscar(clienteId, id));
    }

    @PutMapping("/{id}")
    public EnderecoResponse atualizar(@PathVariable Long clienteId, @PathVariable Long id,
            @Valid @RequestBody EnderecoRequest dados) {
        return EnderecoResponse.de(servico.atualizar(clienteId, id, dados));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable Long clienteId, @PathVariable Long id) {
        servico.excluir(clienteId, id);
        return ResponseEntity.noContent().build();
    }
}
