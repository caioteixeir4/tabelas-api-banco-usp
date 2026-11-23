package br.com.kawashi.banco.autorizacao;

import java.net.URI;
import java.util.Map;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.kawashi.banco.compartilhado.Pagina;
import br.com.kawashi.banco.compartilhado.Paginas;
import br.com.kawashi.banco.empresa.EmpresaResponse;
import br.com.kawashi.banco.empresa.EmpresaService;
import br.com.kawashi.banco.login.FiltroDoBff;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * O que so o administrador da PLATAFORMA (a equipe da Kawashi, tabela ADMIN_PLATAFORMA) faz: criar
 * uma empresa nova com o primeiro administrador dela, listar, inativar, reativar e excluir
 * empresas. Ele NAO enxerga os dados de dentro das empresas: para isso precisaria de um vinculo,
 * como qualquer usuario.
 */
@RestController
@RequestMapping("/api/plataforma/empresas")
public class PlataformaController {

    private final ServicoDePlataforma plataforma;
    private final EmpresaService empresas;

    public PlataformaController(ServicoDePlataforma plataforma, EmpresaService empresas) {
        this.plataforma = plataforma;
        this.empresas = empresas;
    }

    @Exige(value = {}, adminPlataforma = true)
    @GetMapping
    public Pagina<EmpresaResponse> listar(@RequestParam(required = false) String razaoSocial,
            @PageableDefault(size = 20, sort = "razaoSocial") Pageable paginacao) {
        return Pagina.de(empresas.listarTodas(razaoSocial, Paginas.seguro(paginacao)), EmpresaResponse::de);
    }

    /** Cria a empresa, os perfis padrao e o primeiro administrador; o convite dele vem UMA vez. */
    @Exige(value = {}, adminPlataforma = true)
    @PostMapping
    public ResponseEntity<ServicoDePlataforma.EmpresaCriada> criar(@Valid @RequestBody ServicoDePlataforma.NovaEmpresa dados,
            HttpServletRequest requisicao) {
        ServicoDePlataforma.EmpresaCriada criada = plataforma.criarEmpresa(dados, FiltroDoBff.ipDoCliente(requisicao));
        return ResponseEntity.created(URI.create("/api/plataforma/empresas/" + criada.empresaId())).body(criada);
    }

    @Exige(value = {}, adminPlataforma = true)
    @PostMapping("/{id}/inativar")
    public EmpresaResponse inativar(@PathVariable Long id) {
        return EmpresaResponse.de(empresas.inativar(id));
    }

    @Exige(value = {}, adminPlataforma = true)
    @PostMapping("/{id}/reativar")
    public EmpresaResponse reativar(@PathVariable Long id) {
        return EmpresaResponse.de(empresas.reativar(id));
    }

    @Exige(value = {}, adminPlataforma = true)
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> excluir(@PathVariable Long id) {
        empresas.excluir(id);
        return ResponseEntity.noContent().build();
    }
}
