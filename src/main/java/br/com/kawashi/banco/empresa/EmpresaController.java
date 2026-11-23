package br.com.kawashi.banco.empresa;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.kawashi.banco.autorizacao.Exige;
import br.com.kawashi.banco.autorizacao.Permissoes;
import br.com.kawashi.banco.compartilhado.Pagina;
import br.com.kawashi.banco.compartilhado.Paginas;

import jakarta.validation.Valid;

/**
 * A empresa da sessao: ver e alterar os dados dela. Criar, inativar, reativar e excluir empresas
 * e coisa do administrador da plataforma ({@code /api/plataforma/empresas}, ver PlataformaController).
 * A listagem so mostra a empresa da sessao, e outra empresa responde 404.
 */
@RestController
@RequestMapping("/api/empresas")
public class EmpresaController {

    private final EmpresaService servico;

    public EmpresaController(EmpresaService servico) {
        this.servico = servico;
    }

    @Exige({Permissoes.EMPRESA_LER})
    @GetMapping
    public Pagina<EmpresaResponse> listar(
            @RequestParam(required = false) String cnpj,
            @RequestParam(required = false) String razaoSocial,
            @RequestParam(required = false) Boolean ativa,
            @PageableDefault(size = 20, sort = "razaoSocial") Pageable paginacao) {
        return Pagina.de(servico.listar(cnpj, razaoSocial, ativa, Paginas.seguro(paginacao)), EmpresaResponse::de);
    }

    @Exige({Permissoes.EMPRESA_LER})
    @GetMapping("/{id}")
    public EmpresaResponse buscar(@PathVariable Long id) {
        return EmpresaResponse.de(servico.buscar(id));
    }

    @Exige({Permissoes.EMPRESA_ALTERAR})
    @PutMapping("/{id}")
    public EmpresaResponse atualizar(@PathVariable Long id, @Valid @RequestBody EmpresaRequest dados) {
        return EmpresaResponse.de(servico.atualizar(id, dados));
    }
}
