package br.com.kawashi.banco.socio;

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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import br.com.kawashi.banco.compartilhado.Pagina;
import jakarta.validation.Valid;

/**
 * Criar e listar sao aninhados na empresa (o socio nasce dentro de uma); ler, atualizar e excluir
 * um socio especifico usam o caminho plano, ja que o id dele basta.
 */
@RestController
public class SocioController {

    private final SocioService servico;

    public SocioController(SocioService servico) {
        this.servico = servico;
    }

    @PostMapping("/api/empresas/{empresaId}/socios")
    public ResponseEntity<SocioResponse> criar(@PathVariable Long empresaId, @Valid @RequestBody SocioRequest dados,
            UriComponentsBuilder uri) {
        Socio criado = servico.criar(empresaId, dados);
        URI local = uri.path("/api/socios/{id}").buildAndExpand(criado.getId()).toUri();
        return ResponseEntity.created(local).body(SocioResponse.de(criado));
    }

    @GetMapping("/api/empresas/{empresaId}/socios")
    public Pagina<SocioResponse> listar(@PathVariable Long empresaId,
            @PageableDefault(size = 20, sort = "id") Pageable paginacao) {
        return Pagina.de(servico.listar(empresaId, paginacao), SocioResponse::de);
    }

    @GetMapping("/api/socios/{id}")
    public SocioResponse buscar(@PathVariable Long id) {
        return SocioResponse.de(servico.buscar(id));
    }

    @PutMapping("/api/socios/{id}")
    public SocioResponse atualizar(@PathVariable Long id, @Valid @RequestBody SocioRequest dados) {
        return SocioResponse.de(servico.atualizar(id, dados));
    }

    @DeleteMapping("/api/socios/{id}")
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        servico.excluir(id);
        return ResponseEntity.noContent().build();
    }
}
