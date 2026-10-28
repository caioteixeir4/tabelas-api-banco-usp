package br.com.kawashi.banco.contato;

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
@RequestMapping("/api/clientes/{clienteId}/contatos")
public class ContatoController {

    private final ContatoService servico;

    public ContatoController(ContatoService servico) {
        this.servico = servico;
    }

    @PostMapping
    public ResponseEntity<ContatoResponse> criar(@PathVariable Long clienteId,
            @Valid @RequestBody ContatoRequest dados, UriComponentsBuilder uri) {
        Contato criado = servico.criar(clienteId, dados);
        URI local = uri.path("/api/clientes/{clienteId}/contatos/{id}").buildAndExpand(clienteId, criado.getId()).toUri();
        return ResponseEntity.created(local).body(ContatoResponse.de(criado));
    }

    @GetMapping
    public Pagina<ContatoResponse> listar(@PathVariable Long clienteId,
            @PageableDefault(size = 20, sort = "id") Pageable paginacao) {
        return Pagina.de(servico.listar(clienteId, paginacao), ContatoResponse::de);
    }

    @GetMapping("/{id}")
    public ContatoResponse buscar(@PathVariable Long clienteId, @PathVariable Long id) {
        return ContatoResponse.de(servico.buscar(clienteId, id));
    }

    @PutMapping("/{id}")
    public ContatoResponse atualizar(@PathVariable Long clienteId, @PathVariable Long id,
            @Valid @RequestBody ContatoRequest dados) {
        return ContatoResponse.de(servico.atualizar(clienteId, id, dados));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable Long clienteId, @PathVariable Long id) {
        servico.excluir(clienteId, id);
        return ResponseEntity.noContent().build();
    }
}
