package br.com.kawashi.banco.endereco;

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

import br.com.kawashi.banco.auxiliar.Dono;
import br.com.kawashi.banco.compartilhado.Pagina;
import br.com.kawashi.banco.compartilhado.Paginas;
import jakarta.validation.Valid;

/**
 * Criar e listar sao aninhados no dono (uma filial ou uma pessoa); operar sobre um endereco
 * especifico usa o caminho plano /api/enderecos/{id}.
 */
@RestController
@RequestMapping("/api")
public class EnderecoController {

    private final EnderecoService servico;

    public EnderecoController(EnderecoService servico) {
        this.servico = servico;
    }

    @Exige(value = {}, noServico = true)
    @PostMapping("/filiais/{filialId}/enderecos")
    public ResponseEntity<EnderecoResponse> criarNaFilial(@PathVariable Long filialId,
            @Valid @RequestBody EnderecoRequest dados, UriComponentsBuilder uri) {
        return criado(servico.criar(Dono.filial(filialId), dados), uri);
    }

    @Exige(value = {}, noServico = true)
    @PostMapping("/pessoas/{pessoaId}/enderecos")
    public ResponseEntity<EnderecoResponse> criarNaPessoa(@PathVariable Long pessoaId,
            @Valid @RequestBody EnderecoRequest dados, UriComponentsBuilder uri) {
        return criado(servico.criar(Dono.pessoa(pessoaId), dados), uri);
    }

    @Exige(value = {}, noServico = true)
    @GetMapping("/filiais/{filialId}/enderecos")
    public Pagina<EnderecoResponse> listarDaFilial(@PathVariable Long filialId,
            @PageableDefault(size = 20, sort = "id") Pageable paginacao) {
        return Pagina.de(servico.listar(Dono.filial(filialId), Paginas.seguro(paginacao)), EnderecoResponse::de);
    }

    @Exige(value = {}, noServico = true)
    @GetMapping("/pessoas/{pessoaId}/enderecos")
    public Pagina<EnderecoResponse> listarDaPessoa(@PathVariable Long pessoaId,
            @PageableDefault(size = 20, sort = "id") Pageable paginacao) {
        return Pagina.de(servico.listar(Dono.pessoa(pessoaId), Paginas.seguro(paginacao)), EnderecoResponse::de);
    }

    @Exige(value = {}, noServico = true)
    @GetMapping("/enderecos/{id}")
    public EnderecoResponse buscar(@PathVariable Long id) {
        return EnderecoResponse.de(servico.buscar(id));
    }

    @Exige(value = {}, noServico = true)
    @PutMapping("/enderecos/{id}")
    public EnderecoResponse atualizar(@PathVariable Long id, @Valid @RequestBody EnderecoRequest dados) {
        return EnderecoResponse.de(servico.atualizar(id, dados));
    }

    @Exige(value = {}, noServico = true)
    @DeleteMapping("/enderecos/{id}")
    public ResponseEntity<Void> excluir(@PathVariable Long id) {
        servico.excluir(id);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<EnderecoResponse> criado(Endereco endereco, UriComponentsBuilder uri) {
        URI local = uri.path("/api/enderecos/{id}").buildAndExpand(endereco.getId()).toUri();
        return ResponseEntity.created(local).body(EnderecoResponse.de(endereco));
    }
}
