package br.com.kawashi.banco.login;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login, logout, sair de todos os aparelhos, trocar a senha e "quem sou eu". O login e o unico
 * caminho da /api que nao exige token. Ver as regras em {@link ServicoDeLogin} e {@link Tokens}.
 */
@RestController
@RequestMapping("/api/auth")
public class AutenticacaoController {

    public record PedidoDeLogin(
            @NotBlank(message = "Informe o e-mail") @Size(max = 150, message = "O e-mail aceita no máximo 150 caracteres")
            String email,
            @NotBlank(message = "Informe a senha") @Size(max = PoliticaDeSenha.MAXIMO, message = "A senha aceita no máximo 128 caracteres")
            String senha) {

        /** A senha nunca aparece em log, nem no toString. */
        @Override
        public String toString() {
            return "PedidoDeLogin[email=***, senha=***]";
        }
    }

    /** As horas saem com fuso explicito (UTC, "Z"): o banco grava tudo em UTC. */
    public record RespostaDoLogin(String token, String tipo, String escopo, OffsetDateTime expiraEm, OffsetDateTime expiraAbsolutoEm) {
    }

    public record PedidoDeTroca(
            @NotBlank(message = "Informe a senha atual") @Size(max = PoliticaDeSenha.MAXIMO) String senhaAtual,
            @NotBlank(message = "Informe a senha nova") @Size(max = PoliticaDeSenha.MAXIMO) String senhaNova) {

        @Override
        public String toString() {
            return "PedidoDeTroca[***]";
        }
    }

    public record Eu(long usuarioId, long pessoaId, String escopo, OffsetDateTime expiraEm, OffsetDateTime expiraAbsolutoEm) {
    }

    private final ServicoDeLogin servico;

    public AutenticacaoController(ServicoDeLogin servico) {
        this.servico = servico;
    }

    @PostMapping("/login")
    public ResponseEntity<?> entrar(@Valid @RequestBody PedidoDeLogin pedido, HttpServletRequest requisicao) {
        char[] senha = pedido.senha().toCharArray();
        try {
            return switch (servico.entrar(pedido.email(), senha, requisicao.getRemoteAddr())) {
                case ServicoDeLogin.Entrou e -> ResponseEntity.ok(new RespostaDoLogin(e.token().token(), "Bearer",
                        e.token().escopo(), utc(e.token().expiraEm()), utc(e.token().expiraAbsolutoEm())));
                case ServicoDeLogin.Recusado r -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .header("WWW-Authenticate", "Bearer realm=\"kawashi\"")
                        .body(problema(HttpStatus.UNAUTHORIZED, "Não autenticado",
                                "E-mail ou senha inválidos, ou conta temporariamente bloqueada."));
                case ServicoDeLogin.IpBloqueado b -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", String.valueOf(b.segundos()))
                        .body(problema(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas",
                                "Muitos logins errados vindos deste endereço. Tente de novo mais tarde."));
            };
        } finally {
            Arrays.fill(senha, '\0');
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> sair(HttpServletRequest requisicao) {
        servico.sair(sessao(requisicao), requisicao.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sair-de-todos")
    public ResponseEntity<Void> sairDeTodos(HttpServletRequest requisicao) {
        servico.sairDeTodos(sessao(requisicao), requisicao.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    /** Troca a senha e derruba TODAS as sessoes, inclusive esta: depois, e preciso entrar de novo. */
    @PostMapping("/trocar-senha")
    public ResponseEntity<?> trocarSenha(@Valid @RequestBody PedidoDeTroca pedido, HttpServletRequest requisicao) {
        char[] atual = pedido.senhaAtual().toCharArray();
        char[] nova = pedido.senhaNova().toCharArray();
        try {
            ServicoDeLogin.Troca troca = servico.trocarSenha(sessao(requisicao), atual, nova, requisicao.getRemoteAddr());
            return switch (troca.resultado()) {
                case TROCADA -> ResponseEntity.noContent().build();
                case SENHA_ATUAL_ERRADA -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body(problema(HttpStatus.UNPROCESSABLE_ENTITY, "Senha atual incorreta", "A senha atual não confere."));
                case NOVA_FRACA, NOVA_REPETE_ANTERIOR -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body(comProblemas(problema(HttpStatus.UNPROCESSABLE_ENTITY, "Senha nova recusada",
                                String.join(" ", troca.problemas())), troca.problemas()));
            };
        } finally {
            Arrays.fill(atual, '\0');
            Arrays.fill(nova, '\0');
        }
    }

    @GetMapping("/eu")
    public Eu eu(HttpServletRequest requisicao) {
        Tokens.Sessao s = sessao(requisicao);
        return new Eu(s.usuarioId(), s.pessoaId(), s.escopo(), utc(s.expiraEm()), utc(s.expiraAbsolutoEm()));
    }

    /** O banco grava as horas do login em UTC (13_schema_login.sql); aqui ganham o fuso explicito. */
    private static OffsetDateTime utc(LocalDateTime emUtc) {
        return emUtc.atOffset(ZoneOffset.UTC);
    }

    private static Tokens.Sessao sessao(HttpServletRequest requisicao) {
        // O filtro garante a sessao em todo caminho daqui que nao e o login.
        return SessaoAtual.de(requisicao).orElseThrow(() -> new IllegalStateException("Pedido sem sessão depois do filtro."));
    }

    private static ProblemDetail problema(HttpStatus status, String titulo, String detalhe) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detalhe);
        p.setTitle(titulo);
        return p;
    }

    private static ProblemDetail comProblemas(ProblemDetail p, List<String> problemas) {
        p.setProperty("problemas", problemas);
        return p;
    }
}
