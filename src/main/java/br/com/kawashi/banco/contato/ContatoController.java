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

import br.com.kawashi.banco.auxiliar.Dono;
import br.com.kawashi.banco.compartilhado.Pagina;
import br.com.kawashi.banco.compartilhado.Paginas;
import jakarta.validation.Valid;

/**
 * Criar e listar sao aninhados no dono (uma filial ou uma pessoa); operar sobre um contato
 * especifico usa o caminho plano /api/contatos/{id}.
 */
@RestController
@RequestMapping("/api")
public class ContatoController {

    private final ContatoService servico;

    public ContatoController(ContatoService servico) {
        this.servico = servico;
    }

    @PostMapping("/filiais/{filialId}/contatos")
    public ResponseEntity<ContatoResponse> criarNaFilial(@PathVariable Long filialId,
            @Valid @RequestBody ContatoRequest dados, UriComponentsBuilder uri) {
        return criado(servico.criar(Dono.filial(filialId), dados), uri);
    }

    @PostMapping("/pessoas/{pessoaId}/contatos")
    public ResponseEntity<ContatoResponse> criarNaPessoa(@PathVariable Long pessoaId,
            @Valid @RequestBody ContatoRequest dados, UriComponentsBuilder uri) {
        return criado(servico.criar(Dono.pessoa(pessoaId), dados), uri);
    }

    @GetMapping("/filiais/{filialId}/contatos")
    public Pagina<ContatoResponse> listarDaFilial(@PathVariable Long filialId,
            @PageableDefault(size = 20, sort = "id") Pageable paginacao) {
        return Pagina.de(servico.listar(Dono.filial(filialId), Paginas.seguro(paginacao)), ContatoResponse::de);
    }

    @GetMapping("/pessoas/{pessoaId}/contatos")
    public Pagina<ContatoResponse> listarDaPessoa(@PathVariable Long pessoaId,
            @PageableDefault(size = 20, sort = "id") Pageable paginacao) {
        return Pagina.de(servico.listar(Dono.pessoa(pessoaId), Paginas.seguro(paginacao)), ContatoResponse::de);
    }

    @GetMapping("/contatos/{id}")
    public ContatoResponse buscar(@PathVariable Long id) {
        return ContatoResponse.de(servico.buscar(id));
    }

    /**
     * Revela o valor completo do contato. Caminho separado de proposito: toda consulta fica no log
     * de acesso (LOG_ACESSO) com o caminho /api/contatos/{id}/valor.
     */
    @GetMapping("/contatos/{id}/valor")
    public ValorRevelado revelarValor(@PathVariable Long id) {
        Contato contato = servico.buscar(id);
        return new ValorRevelado(contato.getId(), contato.getTipo().name(), contato.getValor());
    }

    /** Resposta do "revelar": so o necessario para identificar de qual contato e o valor. */
    public record ValorRevelado(Long id, String tipo, String valor) {
    }

    @PutMapping("/contatos/{id}")
    public ContatoResponse atualizar(@PathVariable Long id, @Valid @RequestBody ContatoRequest dados) {
        return ContatoResponse.de(servico.atualizar(id, dados));
    }

    @DeleteMapping("/contatos/{id}")
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        servico.excluir(id);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<ContatoResponse> criado(Contato contato, UriComponentsBuilder uri) {
        URI local = uri.path("/api/contatos/{id}").buildAndExpand(contato.getId()).toUri();
        return ResponseEntity.created(local).body(ContatoResponse.de(contato));
    }
}
