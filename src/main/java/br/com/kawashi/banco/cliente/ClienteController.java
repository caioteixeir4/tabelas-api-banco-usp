package br.com.kawashi.banco.cliente;

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
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/clientes")
public class ClienteController {

    private final ClienteService servico;

    public ClienteController(ClienteService servico) {
        this.servico = servico;
    }

    @PostMapping
    public ResponseEntity<ClienteResponse> criar(@Valid @RequestBody ClienteRequest dados, UriComponentsBuilder uri) {
        Cliente criado = servico.criar(dados);
        URI local = uri.path("/api/clientes/{id}").buildAndExpand(criado.getId()).toUri();
        return ResponseEntity.created(local).body(ClienteResponse.de(criado));
    }

    @GetMapping
    public Pagina<ClienteResponse> listar(
            @RequestParam(required = false) String cnpj,
            @RequestParam(required = false) String razaoSocial,
            @RequestParam(required = false) Boolean ativo,
            @PageableDefault(size = 20, sort = "razaoSocial") Pageable paginacao) {
        return Pagina.de(servico.listar(cnpj, razaoSocial, ativo, paginacao), ClienteResponse::de);
    }

    @GetMapping("/{id}")
    public ClienteResponse buscar(@PathVariable Long id) {
        return ClienteResponse.de(servico.buscar(id));
    }

    @PutMapping("/{id}")
    public ClienteResponse atualizar(@PathVariable Long id, @Valid @RequestBody ClienteRequest dados) {
        return ClienteResponse.de(servico.atualizar(id, dados));
    }

    @PostMapping("/{id}/inativar")
    public ClienteResponse inativar(@PathVariable Long id) {
        return ClienteResponse.de(servico.inativar(id));
    }

    @PostMapping("/{id}/reativar")
    public ClienteResponse reativar(@PathVariable Long id) {
        return ClienteResponse.de(servico.reativar(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        servico.excluir(id);
        return ResponseEntity.noContent().build();
    }
}
