package br.com.kawashi.banco.autorizacao;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Usuarios, cargos e perfis da empresa da sessao. Tudo aqui e exclusivo do ADMINISTRADOR
 * (USUARIO_GERENCIAR e PERFIL_GERENCIAR so existem nele).
 */
@RestController
@RequestMapping("/api")
public class UsuariosController {

    public record NovoUsuario(@NotNull(message = "Informe a pessoa") Long pessoaId,
            @NotBlank(message = "Informe o e-mail") @Size(max = 150) @Email(message = "E-mail inválido") String email,
            @NotNull(message = "Informe o perfil") Long perfilId) {
    }

    public record TrocaDePerfil(@NotNull(message = "Informe o perfil") Long perfilId) {
    }

    public record NovoPerfil(
            @NotBlank(message = "Dê um nome ao perfil") @Size(max = 50) @Pattern(regexp = "[\\p{L}0-9 _-]+",
                    message = "Use só letras, números, espaço, - e _") String nome,
            @Size(max = 200) String descricao,
            List<String> permissoes) {
    }

    public record ListaDePermissoes(List<String> permissoes) {
    }

    private final ServicoDeUsuarios servico;

    public UsuariosController(ServicoDeUsuarios servico) {
        this.servico = servico;
    }

    @Exige({Permissoes.USUARIO_GERENCIAR})
    @GetMapping("/usuarios")
    public List<Map<String, Object>> listar() {
        return servico.listar();
    }

    /** Criar o usuario ja da o cargo: precisa das duas permissoes (as duas sao so do administrador). */
    @Exige({Permissoes.USUARIO_GERENCIAR})
    @PostMapping("/usuarios")
    public ResponseEntity<ServicoDeUsuarios.Criado> criar(@Valid @RequestBody NovoUsuario dados) {
        ServicoDeUsuarios.Criado criado = servico.criar(dados.pessoaId(), dados.email(), dados.perfilId());
        return ResponseEntity.created(URI.create("/api/usuarios/" + criado.usuarioId())).body(criado);
    }

    @Exige({Permissoes.PERFIL_GERENCIAR})
    @PutMapping("/usuarios/{usuarioId}/perfil")
    public ResponseEntity<Void> trocarPerfil(@PathVariable long usuarioId, @Valid @RequestBody TrocaDePerfil dados) {
        servico.trocarPerfil(usuarioId, dados.perfilId());
        return ResponseEntity.noContent().build();
    }

    @Exige({Permissoes.USUARIO_GERENCIAR})
    @PostMapping("/usuarios/{usuarioId}/desativar")
    public ResponseEntity<Void> desativar(@PathVariable long usuarioId) {
        servico.desativar(usuarioId);
        return ResponseEntity.noContent().build();
    }

    @Exige({Permissoes.USUARIO_GERENCIAR})
    @PostMapping("/usuarios/{usuarioId}/reativar")
    public ResponseEntity<Void> reativar(@PathVariable long usuarioId) {
        servico.reativar(usuarioId);
        return ResponseEntity.noContent().build();
    }

    @Exige({Permissoes.USUARIO_GERENCIAR})
    @PostMapping("/usuarios/{usuarioId}/convite")
    public Map<String, String> novoConvite(@PathVariable long usuarioId) {
        return Map.of("convite", servico.novoConvite(usuarioId));
    }

    @Exige({Permissoes.USUARIO_GERENCIAR, Permissoes.PERFIL_GERENCIAR})
    @GetMapping("/usuarios/historico")
    public List<Map<String, Object>> historico() {
        return servico.historico();
    }

    @Exige({Permissoes.PERFIL_GERENCIAR})
    @GetMapping("/perfis")
    public List<Map<String, Object>> perfis() {
        return servico.perfis();
    }

    @Exige({Permissoes.PERFIL_GERENCIAR})
    @PostMapping("/perfis")
    public ResponseEntity<Map<String, Long>> criarPerfil(@Valid @RequestBody NovoPerfil dados) {
        long id = servico.criarPerfil(dados.nome(), dados.descricao(), dados.permissoes());
        return ResponseEntity.created(URI.create("/api/perfis/" + id)).body(Map.of("id", id));
    }

    @Exige({Permissoes.PERFIL_GERENCIAR})
    @PutMapping("/perfis/{perfilId}/permissoes")
    public ResponseEntity<Void> definirPermissoes(@PathVariable long perfilId, @RequestBody ListaDePermissoes dados) {
        servico.definirPermissoes(perfilId, dados.permissoes());
        return ResponseEntity.noContent().build();
    }

    @Exige({Permissoes.PERFIL_GERENCIAR})
    @GetMapping("/permissoes")
    public List<Map<String, Object>> permissoes() {
        return servico.permissoes();
    }
}
