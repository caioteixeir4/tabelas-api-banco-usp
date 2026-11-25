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
    @Exige(value = {Permissoes.USUARIO_GERENCIAR}, confirmar = true)
    @PostMapping("/usuarios")
    public ResponseEntity<ServicoDeUsuarios.Enviado> criar(@Valid @RequestBody NovoUsuario dados) {
        // 202 e so "enviado para": a resposta e a mesma, exista ou nao conta com esse e-mail.
        return ResponseEntity.accepted().body(servico.criar(dados.pessoaId(), dados.email(), dados.perfilId()));
    }

    @Exige(value = {Permissoes.PERFIL_GERENCIAR}, confirmar = true)
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

    @Exige(value = {Permissoes.USUARIO_GERENCIAR}, confirmar = true)
    @PostMapping("/usuarios/{usuarioId}/reativar")
    public ResponseEntity<Void> reativar(@PathVariable long usuarioId) {
        servico.reativar(usuarioId);
        return ResponseEntity.noContent().build();
    }

    @Exige(value = {Permissoes.USUARIO_GERENCIAR}, confirmar = true)
    @PostMapping("/usuarios/{usuarioId}/convite")
    public ResponseEntity<ServicoDeUsuarios.Enviado> novoConvite(@PathVariable long usuarioId) {
        // O segredo vai para o e-mail da conta; quem pediu ve so para onde.
        return ResponseEntity.accepted().body(new ServicoDeUsuarios.Enviado(servico.novoConvite(usuarioId)));
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

    @Exige(value = {Permissoes.PERFIL_GERENCIAR}, confirmar = true)
    @PostMapping("/perfis")
    public ResponseEntity<Map<String, Long>> criarPerfil(@Valid @RequestBody NovoPerfil dados) {
        long id = servico.criarPerfil(dados.nome(), dados.descricao(), dados.permissoes());
        return ResponseEntity.created(URI.create("/api/perfis/" + id)).body(Map.of("id", id));
    }

    @Exige(value = {Permissoes.PERFIL_GERENCIAR}, confirmar = true)
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
